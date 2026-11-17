import { expect, test } from './fixtures';
import { login } from './session';

/**
 * 375px 窄屏回归（M02-AC-16）：登录、会话列表、文件面板与输入框均不得产生页面横向滚动。
 */
test.describe('窄屏布局', () => {
  test('员工端工作空间在 375px 下无横向滚动', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await login(page);
    await page.getByRole('button', { name: '新建会话' }).click();
    await expect(page.getByText('开始 J1 问答')).toBeVisible();

    const metrics = await page.evaluate(() => ({
      clientWidth: document.documentElement.clientWidth,
      scrollWidth: document.documentElement.scrollWidth,
    }));
    expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth);
    await expect(page.getByRole('button', { name: /上传文件/ })).toBeVisible();
    await expect(page.getByPlaceholder('输入问题，Enter 发送，Shift+Enter 换行')).toBeVisible();
  });
});
