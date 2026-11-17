import { expect, type Page } from '@playwright/test';

/**
 * E2E 演示凭据；由独立 stub API（`e2e/stub/server.mjs`）校验。
 * 仅在 E2E 环境使用，不包含任何真实账号。
 */
export const DEMO_CREDENTIALS = {
  tenantCode: 'acme',
  username: 'demo',
  password: 'demo1234',
} as const;

/**
 * 通过真实登录表单进入工作空间。
 *
 * 不预置 Cookie 或绕过 UI，保证 M02-AC-01 的登录路径被实际覆盖。
 *
 * @param page Playwright 页面
 */
export async function login(page: Page): Promise<void> {
  await page.goto('/login');
  await page.getByPlaceholder('例如 acme').fill(DEMO_CREDENTIALS.tenantCode);
  await page.getByPlaceholder('请输入账号').fill(DEMO_CREDENTIALS.username);
  await page.getByPlaceholder('请输入密码').fill(DEMO_CREDENTIALS.password);
  await page.getByRole('button', { name: /登\s*录/ }).click();

  await expect(page).toHaveURL(/\/chat$/);
  await expect(page.getByRole('heading', { name: 'Riccon 工作空间' })).toBeVisible();
  await expect(page.getByText('示例企业')).toBeVisible();
}
