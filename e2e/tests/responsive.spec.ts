import { expect, test } from './fixtures';

/**
 * 375px 窄屏回归：云工作空间是员工在移动浏览器/窄窗口下最常打开的页面，
 * M02-AC-16 要求 375px 宽度无横向滚动。
 */
test.describe('窄屏布局', () => {
  test('员工端工作空间在 375px 下无横向滚动', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await page.goto('/chat');

    await expect(page.getByRole('heading', { name: 'Riccon 工作空间' })).toBeVisible();

    const metrics = await page.evaluate(() => ({
      clientWidth: document.documentElement.clientWidth,
      scrollWidth: document.documentElement.scrollWidth,
    }));
    expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth);
  });
});
