import { expect, test } from './fixtures';
import { expectNoSeriousA11yViolations } from './a11y';
import { DEMO_CREDENTIALS, login } from './session';

/**
 * 登录页 E2E：覆盖 M02-AC-01 的成功/失败路径、会话过期入口与可访问性。
 * 认证请求发往独立 stub API，由真实 ApiClient 完成 Cookie/CSRF 流程。
 */
test.describe('登录页', () => {
  test('错误凭据显示统一失败提示，不泄露账号是否存在 @a11y', async ({ page, consoleGuard }) => {
    // 错误凭据必然返回 401；这是用例的预期响应，显式登记后守卫仍会拦截其他错误。
    consoleGuard.allowNetworkError(/\/api\/v1\/auth\/login$/, 401);
    await page.goto('/login');
    await page.getByPlaceholder('例如 acme').fill(DEMO_CREDENTIALS.tenantCode);
    await page.getByPlaceholder('请输入账号').fill(DEMO_CREDENTIALS.username);
    await page.getByPlaceholder('请输入密码').fill('wrong-password');
    await page.getByRole('button', { name: /登\s*录/ }).click();

    await expect(page.getByText(/租户代码、账号或密码不正确/)).toBeVisible();
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByText(/账号不存在/)).toHaveCount(0);
    await expectNoSeriousA11yViolations(page);
  });

  test('正确凭据进入工作空间并显示租户名 @a11y', async ({ page }) => {
    await login(page);

    await expect(page.getByText('我的工作空间')).toBeVisible();
    await expect(page.getByText('演示员工')).toBeVisible();
    await expectNoSeriousA11yViolations(page);
  });

  test('375px 宽度无横向滚动', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await page.goto('/login');
    await expect(page.getByRole('heading', { name: '登录 Riccon' })).toBeVisible();

    const metrics = await page.evaluate(() => ({
      clientWidth: document.documentElement.clientWidth,
      scrollWidth: document.documentElement.scrollWidth,
    }));
    expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth);
  });
});
