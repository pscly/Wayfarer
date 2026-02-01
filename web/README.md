# Wayfarer Web（控制台）

本目录是 Wayfarer 的 Web 控制台：

- Next.js 14（App Router）
- React 18 + TypeScript
- Tailwind CSS
- Playwright E2E（严格禁止第三方网络请求）

## 开发

在 `web/` 下：

```bash
npm install
npm run dev
```

然后访问：`http://localhost:3000`

## 构建与运行

```bash
npm run build
npm run start
```

## 代码质量与测试

```bash
npm run lint
npm run test:e2e
```

## 环境变量

- `NEXT_PUBLIC_API_BASE_URL`
  - 本地默认：`http://localhost:8000`
  - 线上示例：`https://waf.pscly.cc`
- `NEXT_PUBLIC_MAPBOX_ACCESS_TOKEN`（可选）
  - 不配置时页面会显示“地图已禁用”的占位卡片，并且不会触发任何外部网络请求
