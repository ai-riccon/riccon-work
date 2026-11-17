import { expect, test } from './fixtures';
import { expectNoSeriousA11yViolations } from './a11y';

/**
 * 员工端 /chat 冒烟：验证全高工作空间布局可渲染，且关键区块可被用户与辅助技术识别。
 *
 * 不发起任何模型请求；页面在 M01 只做本地占位。
 */
test.describe('员工端工作空间', () => {
  test('渲染聊天布局且无严重可访问性问题 @a11y', async ({ page }) => {
    await page.goto('/chat');

    await expect(page.getByRole('heading', { name: 'Riccon 工作空间' })).toBeVisible();
    await expect(page.getByText('我的会话')).toBeVisible();
    await expect(page.getByText('新员工入职问答')).toBeVisible();
    await expect(page.getByPlaceholder('输入消息，Enter 发送（M01 不调用模型）')).toBeVisible();

    await expectNoSeriousA11yViolations(page);
  });
});
