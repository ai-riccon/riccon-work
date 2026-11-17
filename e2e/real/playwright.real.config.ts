import { defineConfig, devices } from '@playwright/test';
import path from 'node:path';

/** 仓库根目录；真实栈服务由 `scripts/real-stack/run.mjs` 启动。 */
const repoRoot = path.resolve(__dirname, '../..');

/**
 * 真实栈 E2E 配置。
 *
 * 与 `e2e/playwright.config.ts` 的 stub 配置严格分离：
 * - 不启动任何 stub API，也不代理到 stub；
 * - 目标必须是 `scripts/real-stack/run.mjs` 启动的真实 API + Worker + Web 生产构建；
 * - 默认只跑 Chromium、单 worker，失败保留 trace 与截图。
 */
export default defineConfig({
  testDir: path.join(repoRoot, 'e2e/real/tests'),
  timeout: 120_000,
  expect: { timeout: 60_000 },
  fullyParallel: false,
  retries: 0,
  workers: 1,
  reporter: [
    ['list'],
    ['json', { outputFile: path.join(repoRoot, '.riccon/evidence/TASK-0024/real-report.json') }],
  ],
  use: {
    baseURL: process.env.REAL_WEB_BASE ?? 'http://127.0.0.1:8092',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    acceptDownloads: true,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
