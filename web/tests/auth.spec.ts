import { expect, test } from "@playwright/test";

const WEB_ORIGINS = new Set(["http://localhost:3000", "http://127.0.0.1:3000"]);
const API_ORIGINS = new Set(["http://localhost:8000", "http://127.0.0.1:8000"]);

type BackendMockOptions = {
  register?: {
    onRequest?: (payload: any) => void;
    response?: {
      status: number;
      body: any;
      contentType?: string;
    };
  };
};

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

async function installBackendMock(
  page: any,
  counters: { refreshCalls: number },
  options: BackendMockOptions = {},
) {
  const seedNow = Date.now();
  const points = [
    {
      client_point_id: "p1",
      recorded_at: new Date(seedNow - 3 * 60 * 60 * 1000).toISOString(),
      latitude: 39.9,
      longitude: 116.3,
      accuracy: 5,
      is_dirty: false,
    },
    {
      client_point_id: "p2",
      recorded_at: new Date(seedNow - 2 * 60 * 60 * 1000).toISOString(),
      latitude: 39.9001,
      longitude: 116.3001,
      accuracy: 5,
      is_dirty: false,
    },
    {
      client_point_id: "p3",
      recorded_at: new Date(seedNow - 1 * 60 * 60 * 1000).toISOString(),
      latitude: 39.9002,
      longitude: 116.3002,
      accuracy: 5,
      is_dirty: false,
    },
  ];
  const deleteRanges: Array<{ startMs: number; endMs: number }> = [];

  function parseMs(value: string | null): number | null {
    if (!value) return null;
    const ms = Date.parse(value);
    return Number.isFinite(ms) ? ms : null;
  }

  await page.route("**/v1/**", async (route: any) => {
    const req = route.request();
    const url = new URL(req.url());
    const method = req.method();
    const headers = req.headers();

     // Capture JSON request bodies for assertions.
     function tryParseJsonBody(): any {
       try {
         return JSON.parse(req.postData() || "null");
       } catch {
         return null;
       }
     }

     // Helper for overriding responses per-test.
     async function fulfillJson(status: number, body: any, contentType?: string) {
       const resolvedContentType = contentType ?? "application/json";
       const resolvedBody =
         typeof body === "string" ? body : JSON.stringify(body ?? null);
       await route.fulfill({
         status,
         contentType: resolvedContentType,
         body: resolvedBody,
       });
     }

    if (url.pathname === "/v1/auth/login" && method === "POST") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ access_token: "token_login" }),
      });
      return;
    }

    if (url.pathname === "/v1/auth/register" && method === "POST") {
      const payload = tryParseJsonBody();
      options.register?.onRequest?.(payload);

      const forced = options.register?.response;
      if (forced) {
        await fulfillJson(forced.status, forced.body, forced.contentType);
        return;
      }

      const email =
        payload && Object.prototype.hasOwnProperty.call(payload, "email")
          ? payload.email
          : null;

      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          user_id: "u_new",
          username: payload?.username ?? "new_user",
          email,
          is_admin: false,
        }),
      });
      return;
    }

    if (url.pathname === "/v1/auth/refresh" && method === "POST") {
      counters.refreshCalls += 1;
      const csrf = headers["x-csrf-token"];
      if (csrf !== "csrf123") {
        await route.fulfill({
          status: 400,
          contentType: "application/json",
          body: JSON.stringify({ detail: "missing csrf" }),
        });
        return;
      }

      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ access_token: "token_refreshed" }),
      });
      return;
    }

    if (url.pathname === "/v1/auth/logout" && method === "POST") {
      await route.fulfill({ status: 204, body: "" });
      return;
    }

    if (url.pathname === "/v1/users/me" && method === "GET") {
      const authz = headers.authorization || "";
      const ok =
        authz === "Bearer token_login" || authz === "Bearer token_refreshed";
      if (!ok) {
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
          created_at: new Date(seedNow - 10_000).toISOString(),
        }),
      });
      return;
    }

    if (url.pathname === "/v1/tracks/query" && method === "GET") {
      const authz = headers.authorization || "";
      const ok =
        authz === "Bearer token_login" || authz === "Bearer token_refreshed";
      if (!ok) {
        await route.fulfill({
          status: 401,
          contentType: "application/json",
          body: JSON.stringify({ detail: "unauthorized" }),
        });
        return;
      }

      const qStart = parseMs(url.searchParams.get("start"));
      const qEnd = parseMs(url.searchParams.get("end"));
      const startMs = qStart ?? -Infinity;
      const endMs = qEnd ?? Infinity;

      const visible = points.filter((p) => {
        const t = Date.parse(p.recorded_at);
        if (!Number.isFinite(t)) return false;
        if (t < startMs || t > endMs) return false;
        for (const r of deleteRanges) {
          if (t >= r.startMs && t <= r.endMs) return false;
        }
        return true;
      });

      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ items: visible }),
      });
      return;
    }

    if (url.pathname === "/v1/tracks/edits" && method === "POST") {
      const authz = headers.authorization || "";
      const ok =
        authz === "Bearer token_login" || authz === "Bearer token_refreshed";
      if (!ok) {
        await route.fulfill({
          status: 401,
          contentType: "application/json",
          body: JSON.stringify({ detail: "unauthorized" }),
        });
        return;
      }

      const payload = tryParseJsonBody();
      if (!payload || payload.type !== "DELETE_RANGE") {
        await route.fulfill({
          status: 400,
          contentType: "application/json",
          body: JSON.stringify({ detail: "unsupported edit" }),
        });
        return;
      }

      const startMs = parseMs(payload.start);
      const endMs = parseMs(payload.end);
      if (startMs === null || endMs === null || startMs >= endMs) {
        await route.fulfill({
          status: 400,
          contentType: "application/json",
          body: JSON.stringify({ detail: "invalid range" }),
        });
        return;
      }

      const applied = points.filter((p) => {
        const t = Date.parse(p.recorded_at);
        return Number.isFinite(t) && t >= startMs && t <= endMs;
      }).length;
      deleteRanges.push({ startMs, endMs });

      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ edit_id: "e1", applied_count: applied }),
      });
      return;
    }

    await route.fulfill({
      status: 404,
      contentType: "application/json",
      body: JSON.stringify({ detail: `unmocked endpoint: ${url.pathname}` }),
    });
  });
}

test("register flow -> auto-login -> tracks list renders", async ({
  page,
  context,
}) => {
  // CSRF cookie is shared across ports on localhost.
  await context.addCookies([
    {
      name: "wf_csrf",
      value: "csrf123",
      domain: "localhost",
      path: "/",
    },
  ]);

  let registerPayload: any = null;
  const counters = { refreshCalls: 0 };
  await installExternalNetworkBlock(page);
  await installBackendMock(page, counters, {
    register: {
      onRequest: (payload) => {
        registerPayload = payload;
      },
    },
  });

  await page.goto("/register");
  await page.getByTestId("register-username").fill("alice");
  // Leave email blank; UI sends `email: null`.
  await page.getByTestId("register-password").fill("password123!");
  const confirm = page.getByTestId("register-password-confirm");
  if ((await confirm.count()) > 0) {
    await confirm.fill("password123!");
  }
  await page.getByTestId("register-submit").click();

  await expect.poll(() => registerPayload).not.toBeNull();
  expect(Object.prototype.hasOwnProperty.call(registerPayload, "email")).toBe(
    true,
  );
  expect(registerPayload.email).toBeNull();

  await expect(page).toHaveURL(/\/tracks$/);
  await expect(page.getByTestId("tracks-count")).toHaveText("3");
  expect(counters.refreshCalls).toBe(0);
});

test("register error is user-safe and includes trace id", async ({
  page,
  context,
}) => {
  await context.addCookies([
    {
      name: "wf_csrf",
      value: "csrf123",
      domain: "localhost",
      path: "/",
    },
  ]);

  const counters = { refreshCalls: 0 };
  await installExternalNetworkBlock(page);
  await installBackendMock(page, counters, {
    register: {
      response: {
        status: 500,
        body: { code: "INTERNAL_ERROR", trace_id: "t123" },
      },
    },
  });

  await page.goto("/register");
  await page.getByTestId("register-username").fill("alice");
  await page.getByTestId("register-password").fill("password123!");
  const confirm = page.getByTestId("register-password-confirm");
  if ((await confirm.count()) > 0) {
    await confirm.fill("password123!");
  }
  await page.getByTestId("register-submit").click();

  const err = page.getByTestId("register-error");
  await expect(err).toBeVisible();
  await expect(err).toContainText("参考编号：t123");
  await expect(err).not.toContainText("{");
  await expect(err).not.toContainText('"code"');
});

test("login flow -> tracks list renders (no external network)", async ({
  page,
  context,
}) => {
  // CSRF cookie is shared across ports on localhost.
  await context.addCookies([
    {
      name: "wf_csrf",
      value: "csrf123",
      domain: "localhost",
      path: "/",
    },
  ]);

  const counters = { refreshCalls: 0 };
  await installExternalNetworkBlock(page);
  await installBackendMock(page, counters);

  await page.goto("/login");
  await page.getByTestId("login-username").fill("alice");
  await page.getByTestId("login-password").fill("pw");
  await page.getByTestId("login-submit").click();

  await expect(page).toHaveURL(/\/tracks$/);
  await expect(page.getByTestId("tracks-count")).toHaveText("3");
  await expect(page.getByTestId("map-disabled")).toBeVisible();
  expect(counters.refreshCalls).toBe(0);
});

test("tracks page refreshes once when access token missing", async ({
  page,
  context,
}) => {
  await context.addCookies([
    {
      name: "wf_csrf",
      value: "csrf123",
      domain: "localhost",
      path: "/",
    },
  ]);

  const counters = { refreshCalls: 0 };
  await installExternalNetworkBlock(page);
  await installBackendMock(page, counters);

  await page.goto("/tracks");
  await expect(page.getByTestId("tracks-count")).toHaveText("3");
  expect(counters.refreshCalls).toBe(1);
});

test("tracks timeline delete-range reduces point count", async ({ page, context }) => {
  await context.addCookies([
    {
      name: "wf_csrf",
      value: "csrf123",
      domain: "localhost",
      path: "/",
    },
  ]);

  const counters = { refreshCalls: 0 };
  await installExternalNetworkBlock(page);
  await installBackendMock(page, counters);

  await page.goto("/tracks");
  await expect(page.getByTestId("timeline")).toBeVisible();

  // Initial load returns 3 points.
  await expect(page.getByTestId("tracks-count")).toHaveText("3");
  await expect(page.getByTestId("timeline-delete")).toBeEnabled();

  // Select a wide range so query stays stable; then delete it.
  await page.getByTestId("timeline-start").fill("2000-01-01T00:00:00.000Z");
  await page.getByTestId("timeline-end").fill("2100-01-01T00:00:00.000Z");
  await expect(page.getByTestId("tracks-count")).toHaveText("3");
  await expect(page.getByTestId("timeline-delete")).toBeEnabled();

  await page.getByTestId("timeline-delete").click();
  await expect(page.getByTestId("tracks-count")).toHaveText("0");
  expect(counters.refreshCalls).toBe(1);
});
