import { expect, test } from '@playwright/test';
import { login } from './real-session';

/**
 * 真实栈 J2 创建入口（M02-AC-06）。
 *
 * 员工必须在真实 UI 中选择一个已授权 MCP 工具并提交主题；该用例直接断言
 * `/api/v1/mcp-tools` 可返回租户授权工具且页面可创建任务。若端点缺失，
 * 页面会显示“授权工具加载失败”并禁用提交，用例失败。
 */
test.describe('真实栈 J2 创建入口', () => {
  test('报告页可加载已授权工具并创建任务', async ({ page }) => {
    await login(page);
    await page.goto('/research/tasks');
    await expect(page.getByRole('heading', { name: '市场调研报告' })).toBeVisible();

    // 端点缺失时页面显示 warning；这正是需要暴露的真实栈断裂点。
    await expect(page.getByText('授权工具加载失败')).toHaveCount(0);

    const toolSelect = page.getByRole('combobox', { name: '数据工具' });
    await expect(toolSelect).toBeEnabled({ timeout: 30_000 });
    await toolSelect.click();
    await expect(page.getByText('market_data · 只读 · 需审批')).toBeVisible();

    await page
      .getByPlaceholder('例如：2026 年中国企业协同办公市场规模与竞争格局')
      .fill('真实栈 J2 创建入口验证');
    await page.getByText('market_data · 只读 · 需审批').click();
    await page.getByRole('button', { name: /创建\s*报告任务/ }).click();
    await expect(page).toHaveURL(/\/research\/(?!tasks$)[^/]+$/);
  });
});
