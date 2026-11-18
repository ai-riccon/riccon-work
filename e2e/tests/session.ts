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

/** M03 演示账号；密码与 stub 登录表一致，只用于 E2E。 */
export const M03_CREDENTIALS = {
  demo: DEMO_CREDENTIALS,
  member: { tenantCode: 'acme', username: 'wangmin', password: 'wangmin1234' },
  manager: { tenantCode: 'acme', username: 'manager', password: 'manager1234' },
  tenantAdmin: { tenantCode: 'acme', username: 'admin', password: 'admin1234' },
  outsider: { tenantCode: 'acme', username: 'outsider', password: 'outsider1234' },
  unassigned: { tenantCode: 'acme', username: 'unassigned', password: 'unassigned1234' },
  disabled: { tenantCode: 'acme', username: 'disabled', password: 'disabled1234' },
  platformAdmin: { tenantCode: 'platform', username: 'platform-admin', password: 'platform1234' },
} as const;

/** 登录凭据。 */
export interface LoginCredentials {
  tenantCode: string;
  username: string;
  password: string;
}

/**
 * 使用指定账号走真实登录表单。
 *
 * @param page Playwright 页面
 * @param credentials 登录凭据
 * @param expectedDisplayName 登录后应显示的用户名
 */
export async function loginAs(
  page: Page,
  credentials: LoginCredentials,
  expectedDisplayName: string,
): Promise<void> {
  await page.goto('/login');
  await page.getByPlaceholder('例如 acme').fill(credentials.tenantCode);
  await page.getByPlaceholder('请输入账号').fill(credentials.username);
  await page.getByPlaceholder('请输入密码').fill(credentials.password);
  await page.getByRole('button', { name: /登\s*录/ }).click();

  await expect(page).toHaveURL(/\/chat$/);
  await expect(page.getByRole('heading', { name: 'Riccon 工作空间' })).toBeVisible();
  await expect(page.getByText(expectedDisplayName)).toBeVisible();
}

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

/**
 * 平台管理员登录后进入租户列表；登录页仍走真实表单。
 *
 * @param page Playwright 页面
 */
export async function loginPlatformAdmin(page: Page): Promise<void> {
  await loginAs(page, M03_CREDENTIALS.platformAdmin, '赵蕾');
  await page.goto('/admin/tenants');
  await expect(page.getByText('租户与部门', { exact: true })).toBeVisible();
}
