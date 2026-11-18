import { expect, test } from './fixtures';
import { expectNoSeriousA11yViolations } from './a11y';
import { loginAs, M03_CREDENTIALS } from './session';

/**
 * 断言页面无横向滚动。
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
 * M13 M03 验收补丁 E2E。
 *
 * 覆盖 Product 预验收退回项：
 * - R-M03-01：成员端所属部门/未分配/停用提示，租户管理员不误报主部门；
 * - R-M03-02：可管理空间重命名，列表/切换器同步，409/403/404 文案，TENANT 无入口；
 * - R-M03-03：研究页按空间查看部门报告，默认个人空间不变，他部门 404。
 */
test.describe('M13 M03 验收补丁', () => {
  test('R-M03-01 成员端显示所属部门、未分配与停用提示', async ({ page, stub }) => {
    await stub.seedM03();
    await page.setViewportSize({ width: 375, height: 812 });

    await loginAs(page, M03_CREDENTIALS.member, '王敏');
    const departmentBadge = page.getByTestId('current-department');
    await expect(departmentBadge).toContainText('所属部门：');
    await expect(departmentBadge).toContainText('销售部');
    await expectNoHorizontalScroll(page);
    await expectNoSeriousA11yViolations(page);

    await page.context().clearCookies();
    await loginAs(page, M03_CREDENTIALS.unassigned, '未分配员工');
    await expect(page.getByTestId('current-department')).toContainText('未分配部门');
    await expectNoHorizontalScroll(page);

    await page.context().clearCookies();
    await loginAs(page, M03_CREDENTIALS.disabled, '停用员工');
    await expect(page.getByTestId('current-department')).toContainText('综合部');
    await expect(page.getByTestId('current-department')).toContainText('已停用');
    await expectNoHorizontalScroll(page);

    await page.context().clearCookies();
    await loginAs(page, M03_CREDENTIALS.tenantAdmin, '王芳');
    await expect(page.getByTestId('current-department')).toContainText('租户管理员');
    await expect(page.getByText('所属部门：销售部')).toHaveCount(0);
    await expectNoHorizontalScroll(page);
    await expectNoSeriousA11yViolations(page);
  });

  test('R-M03-02 可管理空间重命名、错误文案与 TENANT 只读', async ({
    page,
    stub,
    consoleGuard,
  }) => {
    await stub.seedM03();
    await loginAs(page, M03_CREDENTIALS.manager, '李强');
    await page.getByRole('button', { name: '工作空间共享' }).click();

    const drawer = page.getByRole('dialog').filter({ hasText: '工作空间共享' });
    const salesSpace = drawer
      .locator('.workspace-sharing-item')
      .filter({ hasText: '销售部项目空间' });
    await expect(salesSpace.getByRole('button', { name: '重命名' })).toBeVisible();

    await salesSpace.getByRole('button', { name: '重命名' }).click();
    const renameDialog = page.locator('.ant-modal').filter({ hasText: '重命名空间' });
    await expect(renameDialog.getByText('重命名空间 · 销售部项目空间')).toBeVisible();
    await renameDialog.getByPlaceholder('例如：销售部项目空间').fill('销售部客户空间');
    await renameDialog.getByRole('button', { name: '保存名称' }).click();

    await expect(page.getByText('空间已重命名为“销售部客户空间”。')).toBeVisible();
    await expect(
      drawer.locator('.workspace-sharing-item').filter({ hasText: '销售部客户空间' }),
    ).toBeVisible();
    await page.keyboard.press('Escape');
    await page.getByRole('combobox', { name: '切换工作空间' }).click();
    await expect(
      page
        .locator('.ant-select-dropdown:visible .ant-select-item-option')
        .filter({ hasText: '销售部客户空间' }),
    ).toBeVisible();
    await page.keyboard.press('Escape');

    // TENANT 历史空间只读：没有重命名入口。
    await page.getByRole('button', { name: '工作空间共享' }).click();
    const tenantSpace = page
      .getByRole('dialog')
      .filter({ hasText: '工作空间共享' })
      .locator('.workspace-sharing-item')
      .filter({ hasText: '历史全租户空间' });
    await expect(tenantSpace).toBeVisible();
    await expect(tenantSpace.getByRole('button', { name: '重命名' })).toHaveCount(0);

    // 409/403/404 使用集中中文文案。
    const scenarios = [
      ['rename-409', '空间名称已存在，请换一个名称。', 409],
      ['rename-403', '你不是该空间的管理者，无法执行此操作。', 403],
      ['rename-404', '空间或部门不存在，或你没有管理权限。', 404],
    ] as const;
    for (const [, , status] of scenarios) {
      consoleGuard.allowNetworkError(/\/api\/v1\/workspaces\/[^/]+$/, status);
    }
    for (const [scenario, message] of scenarios) {
      const currentDrawer = page.getByRole('dialog').filter({ hasText: '工作空间共享' });
      const currentSpace = currentDrawer
        .locator('.workspace-sharing-item')
        .filter({ hasText: '销售部客户空间' });
      await currentSpace.getByRole('button', { name: '重命名' }).click();
      await stub.setScenario(scenario);
      const dialog = page.locator('.ant-modal').filter({ hasText: '重命名空间' });
      await dialog.getByPlaceholder('例如：销售部项目空间').fill(`冲突名称-${scenario}`);
      await dialog.getByRole('button', { name: '保存名称' }).click();
      await expect(dialog.getByText('空间未重命名')).toBeVisible();
      await expect(dialog.getByText(message)).toBeVisible();
      await dialog.getByRole('button', { name: /取\s*消/ }).click();
      await stub.setScenario('none');
    }

    await page.keyboard.press('Escape');
    await page.context().clearCookies();
    await loginAs(page, M03_CREDENTIALS.member, '王敏');
    await expect(page.getByRole('button', { name: '工作空间共享' })).toHaveCount(0);
  });

  test('R-M03-03 研究页按空间选择并打开部门报告', async ({ page, stub }) => {
    await stub.seedM03();
    await loginAs(page, M03_CREDENTIALS.manager, '李强');

    // 默认仍进入个人空间，不显示部门报告。
    await page.getByRole('button', { name: '市场调研报告' }).click();
    await expect(page).toHaveURL(/\/research\/tasks$/);
    await expect(page.getByText('销售部季度市场调研')).toHaveCount(0);

    await page.goto('/chat');
    await page.getByRole('combobox', { name: '切换工作空间' }).click();
    await page
      .locator('.ant-select-dropdown:visible .ant-select-item-option')
      .filter({ hasText: '销售部项目空间' })
      .click();
    await expect(page.locator('.chat-workspace-select')).toContainText('销售部项目空间');
    await page.getByRole('button', { name: '市场调研报告' }).click();

    await expect(page).toHaveURL(/\/research\/tasks\?workspaceId=ws-sales-project$/);
    await expect(page.getByText('销售部项目空间', { exact: true })).toBeVisible();
    await expect(page.getByText('销售部季度市场调研')).toBeVisible();
    await page.getByRole('button', { name: '打开任务' }).click();
    await expect(page).toHaveURL(/\/research\/research-dept-1$/);
    await expect(page.getByRole('heading', { name: '销售部季度市场调研', level: 1 })).toBeVisible();
    await expect(page.getByRole('heading', { name: '报告产物' })).toBeVisible();
    await expect(
      page.getByRole('heading', { name: '销售部季度市场调研 · 市场调研报告' }),
    ).toBeVisible();

    // 他部门成员：直接访问部门空间任务列表/详情统一 404，页面也不显示该任务。
    await page.context().clearCookies();
    await loginAs(page, M03_CREDENTIALS.outsider, '赵敏');
    const listResponse = await page.request.get(
      '/api/v1/workspaces/ws-sales-project/research-tasks',
    );
    expect(listResponse.status()).toBe(404);
    const detailResponse = await page.request.get('/api/v1/research-tasks/research-dept-1');
    expect(detailResponse.status()).toBe(404);
    await page.goto('/research/tasks?workspaceId=ws-sales-project');
    await expect(page.getByText('销售部季度市场调研')).toHaveCount(0);
  });
});
