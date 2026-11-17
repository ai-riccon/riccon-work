import { expect, test } from './fixtures';
import { expectNoSeriousA11yViolations } from './a11y';

/**
 * 登录页冒烟：验证 M01 骨架期的“不可误用”状态与可访问性。
 *
 * 认证在 M02 接入，当前页面必须明确提示未开放，并保持账号、密码与提交按钮禁用。
 */
test.describe('登录页', () => {
  test('渲染未开放提示且凭据入口禁用，无严重可访问性问题 @a11y', async ({ page }) => {
    await page.goto('/login');

    await expect(page.getByRole('heading', { name: '登录 Riccon' })).toBeVisible();
    await expect(page.getByText('登录尚未开放')).toBeVisible();
    await expect(page.getByPlaceholder('M02 起可用')).toHaveCount(2);

    for (const input of await page.getByPlaceholder('M02 起可用').all()) {
      await expect(input).toBeDisabled();
    }
    await expect(page.getByRole('button', { name: /登\s*录/ })).toBeDisabled();

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
