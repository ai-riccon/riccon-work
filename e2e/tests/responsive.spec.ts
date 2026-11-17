import { expect, test } from './fixtures';
import { login } from './session';

/**
 * 断言页面没有横向滚动。
 *
 * @param page Playwright 页面
 */
async function expectNoHorizontalScroll(page: import('@playwright/test').Page): Promise<void> {
  const metrics = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }));
  expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth);
}

/**
 * 375px 窄屏回归（M02-AC-16）：登录、会话列表、文件面板与输入框均不得产生页面横向滚动。
 */
test.describe('窄屏布局', () => {
  test('员工端工作空间在 375px 下无横向滚动', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await login(page);
    await page.getByRole('button', { name: '新建会话' }).click();
    await expect(page.getByText('开始 J1 问答')).toBeVisible();

    await expectNoHorizontalScroll(page);
    await expect(page.getByRole('button', { name: /上传文件/ })).toBeVisible();
    await expect(page.getByPlaceholder('输入问题，Enter 发送，Shift+Enter 换行')).toBeVisible();
  });

  test('报告任务列表、详情与产物在 375px 下无横向滚动', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await login(page);
    await page.getByRole('button', { name: '市场调研报告' }).click();
    await expect(page).toHaveURL(/\/research\/tasks$/);
    await expectNoHorizontalScroll(page);

    await page
      .getByPlaceholder('例如：2026 年中国企业协同办公市场规模与竞争格局')
      .fill('窄屏市场调研报告演示');
    await page.getByRole('combobox', { name: '数据工具' }).click();
    await page.getByText('market_data_lookup · 只读 · 需审批').click();
    await page.getByRole('button', { name: /创建\s*报告任务/ }).click();

    await expect(page.getByText('待审批：调用外部工具')).toBeVisible();
    await expectNoHorizontalScroll(page);

    const approve = page.getByRole('button', { name: /批\s*准/ });
    await approve.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText('报告已生成', { exact: true })).toBeVisible({
      timeout: 20_000,
    });
    await expect(page.getByRole('heading', { name: '市场规模与增长' })).toBeVisible();
    await expectNoHorizontalScroll(page);
  });
});
