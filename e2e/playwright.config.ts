import { defineConfig, devices } from '@playwright/test';
import path from 'node:path';

/** Playwright 配置所在目录的上一级即仓库根目录，用于让 webServer 在根目录执行 pnpm。 */
const repoRoot = path.resolve(__dirname, '..');

/** E2E 使用的 Web 端口；与 Umi 默认 8000 区分，避免本地开发时端口冲突。 */
const port = Number(process.env.E2E_WEB_PORT ?? 8123);
const baseURL = `http://127.0.0.1:${port}`;

/** 独立 stub API 端口；与 Web 端口分离，保证 stub 不进入 Web 构建产物。 */
const stubPort = Number(process.env.E2E_STUB_PORT ?? 8124);
const stubURL = `http://127.0.0.1:${stubPort}`;

/**
 * Riccon Web E2E 配置。
 *
 * 关键约束：
 * 1. Playwright 同时启动独立 stub API（`e2e/stub/server.mjs`）与 Umi dev server；
 * 2. Web 只通过开发代理 `/api` 指向 stub，浏览器仍使用同源 Cookie/CSRF 语义，
 *    应用内不存在 mock 适配层，也不把 stub 打进生产构建；
 * 3. 只使用 chromium，workers=1，保证状态机与 stub 数据可重复；
 * 4. 报告与 trace 落在 e2e 目录，已被 .gitignore 忽略。
 */
export default defineConfig({
  testDir: './tests',
  timeout: 90_000,
  // 冷启动时 Umi/MFSU 首次预打包会展示 Bundling 页面；给首屏断言留出预热时间。
  expect: { timeout: 45_000 },
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],
  use: {
    baseURL,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  webServer: [
    {
      // 独立 stub API：只实现 M02 使用的 OpenAPI 子集与 SSE，不依赖 Web 源码。
      command: 'node e2e/stub/server.mjs',
      cwd: repoRoot,
      env: {
        STUB_PORT: String(stubPort),
        STUB_HOST: '127.0.0.1',
      },
      url: `${stubURL}/__stub__/health`,
      reuseExistingServer: false,
      timeout: 60_000,
    },
    {
      command: 'pnpm --filter @riccon/web run dev',
      cwd: repoRoot,
      env: {
        PORT: String(port),
        // 浏览器侧保持同源；dev server 把 `/api` 代理到独立 stub。
        RICCON_API_BASE_URL: '',
        RICCON_E2E_API_TARGET: stubURL,
        // E2E 关闭 MFSU，避免冷启动预打包与首次路由重定向不稳定。
        RICCON_E2E: '1',
        // 关闭 dev server 的 gzip 中间件：它会缓冲 SSE，导致浏览器在流结束后才收到增量，
        // 使“首字/停止”路径失真。生产部署由反向代理负责压缩策略。
        UMI_DEV_SERVER_COMPRESS: 'none',
      },
      // 端口专用于 E2E；禁用复用，避免误连到未代理 stub 的旧 dev server。
      reuseExistingServer: false,
      timeout: 180_000,
      url: `${baseURL}/chat`,
    },
  ],
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
