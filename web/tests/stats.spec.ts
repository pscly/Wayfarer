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

test("stats page -> daily list -> click day -> hourly + marks", async ({ page }) => {
  await installExternalNetworkBlock(page);

  await page.addInitScript(() => {
    sessionStorage.setItem("wf_access_token", "token_login");
  });

  await page.route("**/v1/**", async (route: any) => {
    const req = route.request();
    const url = new URL(req.url());
    const method = req.method();
    const headers = req.headers();

    function requireAuth(): boolean {
      const authz = headers.authorization || "";
      return authz === "Bearer token_login";
    }

    if (url.pathname === "/v1/users/me" && method === "GET") {
      if (!requireAuth()) {
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
        body: JSON.stringify({
          user_id: "u1",
          username: "alice",
          email: null,
          is_admin: false,
          created_at: new Date().toISOString(),
        }),
      });
      return;
    }

    if (url.pathname === "/v1/stats/steps/daily" && method === "GET") {
      if (!requireAuth()) {
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
        body: JSON.stringify({
          items: [
            { day: "2026-01-30", steps: 22 },
            { day: "2026-01-31", steps: 3 },
          ],
        }),
      });
      return;
    }

    if (url.pathname === "/v1/stats/steps/hourly" && method === "GET") {
      if (!requireAuth()) {
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
        body: JSON.stringify({
          items: [
            { hour_start: "2026-01-30T12:00:00Z", steps: 15 },
            { hour_start: "2026-01-30T13:00:00Z", steps: 7 },
          ],
        }),
      });
      return;
    }

    if (url.pathname === "/v1/life-events" && method === "GET") {
      if (!requireAuth()) {
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
        body: JSON.stringify({
          items: [
            {
              id: "e1",
              event_type: "MARK_POINT",
              start_at: "2026-01-30T12:00:00Z",
              end_at: "2026-01-30T12:00:01Z",
              location_name: "出门买东西",
              manual_note: null,
              latitude: 31.23,
              longitude: 121.47,
            },
          ],
        }),
      });
      return;
    }

    await route.fulfill({
      status: 404,
      contentType: "application/json",
      body: JSON.stringify({ detail: `unmocked endpoint: ${url.pathname}` }),
    });
  });

  await page.goto("/stats");
  await expect(page.getByTestId("stats-daily")).toBeVisible();

  const dayButtons = page.locator('[data-testid="stats-daily"] button');
  await expect(dayButtons.first()).toHaveAttribute("data-testid", "stats-day-2026-01-31");
  await expect(dayButtons.nth(1)).toHaveAttribute("data-testid", "stats-day-2026-01-30");

  await page.getByTestId("stats-day-2026-01-30").click();
  await expect(page.getByTestId("stats-hourly")).toBeVisible();
  await expect(page.getByTestId("stats-marks")).toBeVisible();

  await expect(page.getByText("出门买东西")).toBeVisible();
});
