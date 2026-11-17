import { defineConfig, devices } from '@playwright/test';
import path from 'node:path';

/** Playwright 配置所在目录的上一级即仓库根目录，用于让 webServer 在根目录执行 pnpm。 */
const repoRoot = path.resolve(__dirname, '..');

/** E2E 使用的 Web 端口；与 Umi 默认 8000 区分，避免本地开发时端口冲突。 */
const port = Number(process.env.E2E_WEB_PORT ?? 8123);
const baseURL = `http://127.0.0.1:${port}`;

/**
 * Riccon Web E2E 配置。
 *
 * 关键约束：
 * 1. 通过 webServer 自动启动 Umi 开发服务器，`pnpm -w e2e` 不依赖先手工起服务；
 * 2. 只使用 chromium，保证 CI 可重复；
 * 3. 报告与 trace 落在 e2e 目录，已被 .gitignore 忽略。
 */
export default defineConfig({
  testDir: './tests',
  timeout: 90_000,
  expect: { timeout: 15_000 },
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
  webServer: {
    command: 'pnpm --filter @riccon/web run dev',
    cwd: repoRoot,
    env: {
      PORT: String(port),
    },
    reuseExistingServer: !process.env.CI,
    timeout: 180_000,
    url: `${baseURL}/chat`,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
