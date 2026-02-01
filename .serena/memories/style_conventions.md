# 风格与约定

Web：

- Next.js 14 App Router（`web/app/**`），React 18，TypeScript。
- UI 样式主要使用 Tailwind CSS。
- API 调用统一走 `web/lib/api.ts:apiFetch`：
  - `credentials: "include"`（基于 Cookie/CSRF 的 refresh）
  - 可选 `Authorization: Bearer <accessToken>`
  - 401 时最多尝试 refresh 一次（通过 `refreshAccessToken` 回调）
- 认证状态来自 `web/app/providers.tsx`（`useAuth()`），access token 存在 `sessionStorage` 的 key `wf_access_token`。

测试：

- Playwright E2E 位于 `web/tests/**`。
- 测试必须 hermetic：禁止任何外部网络请求；后端通过 `page.route("**/v1/**")` mock。
