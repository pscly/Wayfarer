# Style & Conventions

Web:
- Next.js 14 App Router (`web/app/**`), React 18, TypeScript.
- Tailwind CSS classes used for UI styling.
- API calls go through `web/lib/api.ts:apiFetch`:
  - `credentials: "include"` (refresh/CSRF cookie-based auth)
  - optional `Authorization: Bearer <accessToken>`
  - refresh-once behavior on 401 via `refreshAccessToken` callback.
- Auth state comes from `web/app/providers.tsx` (`useAuth()`), access token persisted in `sessionStorage` key `wf_access_token`.

Tests:
- Playwright E2E tests in `web/tests/**`.
- Hermetic tests: block all external network; mock backend via `page.route("**/v1/**")`.
