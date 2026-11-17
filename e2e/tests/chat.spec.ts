import { expect, test } from './fixtures';
import { expectNoSeriousA11yViolations } from './a11y';
import { login } from './session';

/**
 * 员工端 /chat E2E：验证登录后的工作空间、空态与新建会话入口。
 * 流式、引用与停止在 j1.spec.ts 中覆盖，避免单个用例过长。
 */
test.describe('员工端工作空间', () => {
  test('登录后展示租户、会话列表与空态，无严重可访问性问题 @a11y', async ({ page }) => {
    await login(page);

    await expect(page.getByText('我的会话')).toBeVisible();
    await expect(page.getByText('选择左侧会话，或新建一个会话开始提问')).toBeVisible();
    await expectNoSeriousA11yViolations(page);
  });

  test('新建会话后展示 J1 问答空态', async ({ page }) => {
    await login(page);
    await page.getByRole('button', { name: '新建会话' }).click();

    await expect(page.getByText('开始 J1 问答')).toBeVisible();
    await expect(page.getByText('会话文件 0/5')).toBeVisible();
    await expect(page.getByPlaceholder('输入问题，Enter 发送，Shift+Enter 换行')).toBeVisible();
  });
});
