import { expect, test } from '@playwright/test';
import path from 'node:path';
import { login, readSeed } from './real-session';

/**
 * 真实栈登录旅程（M02-AC-01）：真实 API + Spring Session + CSRF + Web 生产构建。
 */
test.describe('真实栈登录', () => {
  test('正确凭据进入租户工作空间并显示租户名', async ({ page }) => {
    await login(page);
    await expect(page.getByText('我的会话')).toBeVisible();
    await expect(page.getByText('Alpha 员工', { exact: true })).toBeVisible();
  });

  test('错误凭据只显示统一失败提示', async ({ page }) => {
    const seed = readSeed();
    await page.goto('/login');
    await page.getByPlaceholder('例如 acme').fill(seed.tenants.alpha);
    await page.getByPlaceholder('请输入账号').fill('alpha-member');
    await page.getByPlaceholder('请输入密码').fill('definitely-wrong-password');
    await page.getByRole('button', { name: /登\s*录/ }).click();
    await expect(page.getByText(/租户代码、账号或密码不正确/)).toBeVisible();
    await expect(page.getByText(/账号不存在/)).toHaveCount(0);
    await expect(page).toHaveURL(/\/login$/);
  });
});
