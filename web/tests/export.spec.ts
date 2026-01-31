import fs from "fs";

import { expect, test } from "@playwright/test";

const WEB_ORIGINS = new Set(["http://localhost:3000", "http://127.0.0.1:3000"]);
const API_ORIGINS = new Set(["http://localhost:8000", "http://127.0.0.1:8000"]);

async function installExternalNetworkBlock(page: any) {
  await page.route("**/*", async (route: any) => {
    const url = route.request().url();

    // Non-HTTP(S) requests (data:, blob:, about:) are allowed.
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
      await route.continue();
      return;
    }

    const parsed = new URL(url);
    const origin = parsed.origin;
    if (WEB_ORIGINS.has(origin) || API_ORIGINS.has(origin)) {
      await route.continue();
      return;
    }

    // Hard fail: any third-party request means the app isn't hermetic.
    await route.abort();
    throw new Error(`External request blocked: ${url}`);
  });
}

test("export wizard -> create job -> wait SUCCEEDED -> download artifact", async (
  { page },
  testInfo,
) => {
  await installExternalNetworkBlock(page);

  // Ensure TracksPage can load without triggering refresh.
  await page.addInitScript(() => {
    sessionStorage.setItem("wf_access_token", "token_login");
  });

  let createPayload: any = null;
  let exportPollCount = 0;

  await page.route("**/v1/**", async (route: any) => {
    const req = route.request();
    const url = new URL(req.url());
    const method = req.method();
    const headers = req.headers();

    if (url.pathname === "/v1/tracks/query" && method === "GET") {
      const authz = headers.authorization || "";
      if (authz !== "Bearer token_login") {
        await route.fulfill({
          status: 401,
          contentType: "application/json",
          body: JSON.stringify({ detail: "unauthorized" }),
        });
        return;
      }

      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ items: [] }),
      });
      return;
    }

    if (url.pathname === "/v1/export" && method === "POST") {
      const authz = headers.authorization || "";
      if (authz !== "Bearer token_login") {
        await route.fulfill({
          status: 401,
          contentType: "application/json",
          body: JSON.stringify({ detail: "unauthorized" }),
        });
        return;
      }

      try {
        createPayload = JSON.parse(req.postData() || "null");
      } catch {
        createPayload = null;
      }

      await route.fulfill({
        status: 202,
        contentType: "application/json",
        body: JSON.stringify({ job_id: "j1" }),
      });
      return;
    }

    if (url.pathname === "/v1/export/j1" && method === "GET") {
      const authz = headers.authorization || "";
      if (authz !== "Bearer token_login") {
        await route.fulfill({
          status: 401,
          contentType: "application/json",
          body: JSON.stringify({ detail: "unauthorized" }),
        });
        return;
      }

      exportPollCount += 1;
      const state = exportPollCount >= 2 ? "SUCCEEDED" : "RUNNING";
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ job_id: "j1", state, error: null }),
      });
      return;
    }

    if (url.pathname === "/v1/export/j1/download" && method === "GET") {
      const authz = headers.authorization || "";
      if (authz !== "Bearer token_login") {
        await route.fulfill({
          status: 401,
          contentType: "application/json",
          body: JSON.stringify({ detail: "unauthorized" }),
        });
        return;
      }

      await route.fulfill({
        status: 200,
        headers: {
          "content-type": "text/csv",
          "content-disposition": 'attachment; filename="export.csv"',
        },
        body: "a,b\n1,2\n",
      });
      return;
    }

    await route.fulfill({
      status: 404,
      contentType: "application/json",
      body: JSON.stringify({ detail: `unmocked endpoint: ${url.pathname}` }),
    });
  });

  await page.goto("/tracks");
  await expect(page.getByTestId("export-wizard")).toBeVisible();

  await page.getByTestId("export-include-weather").check();
  await page.getByTestId("export-create").click();

  await expect(page.getByTestId("export-job-state")).toHaveText("SUCCEEDED");
  expect(createPayload?.include_weather).toBe(true);

  const [download] = await Promise.all([
    page.waitForEvent("download"),
    page.getByTestId("export-download").click(),
  ]);

  const outPath = testInfo.outputPath(download.suggestedFilename() || "export.csv");
  await download.saveAs(outPath);
  expect(fs.statSync(outPath).size).toBeGreaterThan(0);
});
