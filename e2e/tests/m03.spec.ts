import { expect, test } from './fixtures';
import { expectNoSeriousA11yViolations } from './a11y';
import { loginAs, M03_CREDENTIALS } from './session';

/**
 * 断言当前页面无横向滚动。
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
 * M03 部门与空间共享 E2E。
 *
 * 覆盖 M03-AC-01/02/03/04/06/07 与 M03-AC-21 的部门管理页段：
 * 平台管理员创建部门、加/移成员与任命负责人；负责人创建部门空间并维护 CUSTOM 成员；
 * 普通成员看不到管理入口；知识库全租户共享明示与源文件 404 文案；375px、键盘与 axe。
 */
test.describe('M03 部门与空间共享', () => {
  test('E2E-M03-01 平台管理员创建部门、加移成员并任命负责人', async ({ page }) => {
    await loginAs(page, M03_CREDENTIALS.platformAdmin, '赵蕾');
    await page.goto('/admin/overview');
    const tenantNav = page.getByRole('link', { name: '租户管理' });
    await expect(tenantNav).toBeVisible();
    await tenantNav.click();
    await expect(page).toHaveURL(/\/admin\/tenants$/);
    await expect(page.getByText('租户与部门', { exact: true })).toBeVisible();
    await expect(page.getByText('示例企业')).toBeVisible();

    await page.getByRole('link', { name: '管理部门：示例企业' }).click();
    await expect(page).toHaveURL(/\/admin\/tenants\/tenant-acme\/departments$/);
    await expect(page.getByText('部门管理', { exact: true }).first()).toBeVisible();
    await expect(
      page.getByText('还没有部门；创建第一个部门后即可分配成员与部门空间。'),
    ).toBeVisible();

    await page.getByRole('button', { name: '创建部门' }).first().click();
    const createDialog = page.getByRole('dialog');
    await expect(createDialog.getByText('创建部门', { exact: true }).first()).toBeVisible();
    await createDialog.getByPlaceholder('例如：销售部').fill('销售部');
    await createDialog.getByRole('button', { name: '创建部门' }).click();

    await expect(page.getByText('部门“销售部”已创建。')).toBeVisible();
    const salesRow = page.locator('.department-list-item').filter({ hasText: '销售部' });
    await expect(salesRow.getByText('成员 0 人')).toBeVisible();
    await expect(salesRow.getByText('负责人：未任命')).toBeVisible();

    await page.getByRole('button', { name: '成员管理：销售部' }).click();
    const membersDrawer = page.getByRole('dialog').filter({ hasText: '成员管理 · 销售部' });
    await expect(membersDrawer.getByText('成员管理 · 销售部')).toBeVisible();
    await expect(membersDrawer.getByText('部门还没有成员')).toBeVisible();

    await membersDrawer.getByRole('combobox', { name: '加入目标租户账号' }).click();
    await page
      .locator('.ant-select-dropdown:visible .ant-select-item-option')
      .filter({ hasText: '李强（manager）' })
      .click();
    await membersDrawer.getByRole('button', { name: '加入部门' }).click();
    await expect(membersDrawer.getByText('李强')).toBeVisible();

    await membersDrawer.getByRole('combobox', { name: '加入目标租户账号' }).click();
    await page
      .locator('.ant-select-dropdown:visible .ant-select-item-option')
      .filter({ hasText: '王敏（wangmin）' })
      .click();
    await membersDrawer.getByRole('button', { name: '加入部门' }).click();
    await expect(membersDrawer.getByText('王敏')).toBeVisible();

    await membersDrawer.getByRole('button', { name: '移出成员：王敏' }).click();
    await expect(
      membersDrawer.getByRole('list', { name: '部门成员' }).getByText('王敏'),
    ).toHaveCount(0);

    await membersDrawer.getByRole('button', { name: '设为负责人：李强' }).click();
    await expect(membersDrawer.getByRole('button', { name: '设为负责人：李强' })).toHaveCount(0);
    await expect(
      membersDrawer.getByText('当前负责人不能直接移出，请先移交给其他成员。'),
    ).toBeVisible();
    await page.keyboard.press('Escape');

    await expect(salesRow.getByText('成员 1 人')).toBeVisible();
    await expect(salesRow.getByText('负责人：李强')).toBeVisible();
  });

  test('E2E-M03-02 负责人空间共享、普通成员入口隐藏与知识库边界', async ({
    page,
    stub,
    consoleGuard,
  }) => {
    await stub.seedM03();
    await loginAs(page, M03_CREDENTIALS.manager, '李强');
    // M05-AC-06/22：M03 的“全租户共享/文档级权限后续提供”声明已由文档级 ACL 取代。
    await expect(page.getByText(/知识库按文档可见范围共享/)).toBeVisible();
    await expect(page.getByText(/创建人始终可见/).first()).toBeVisible();
    await expect(page.getByText(/文档级权限在后续版本提供/)).toHaveCount(0);

    // M03-AC-03/04：切换到部门空间，会话与文件按该空间加载并可继续使用。
    const workspaceSelector = page.getByRole('combobox', { name: '切换工作空间' });
    await expect(workspaceSelector).toBeVisible();
    await workspaceSelector.click();
    await page
      .locator('.ant-select-dropdown:visible .ant-select-item-option')
      .filter({ hasText: '销售部项目空间' })
      .click();
    await expect(page.locator('.chat-workspace-select')).toContainText('销售部项目空间');
    await expect(page.getByRole('button', { name: /销售部部门会话/ })).toBeVisible();
    await page.getByRole('button', { name: /销售部部门会话/ }).click();
    await expect(page.getByText('销售部本周新增 3 家重点客户，详见客户清单。')).toBeVisible();
    const departmentFile = page
      .locator('.chat-file-item')
      .filter({ hasText: '销售部客户清单.pdf' });
    await expect(departmentFile.getByText('解析成功', { exact: true })).toBeVisible();

    const composer = page.getByPlaceholder('输入问题，Enter 发送，Shift+Enter 换行');
    await composer.fill('继续汇总客户风险');
    await composer.press('Enter');
    await expect(page.getByText('继续汇总客户风险')).toBeVisible();
    await expect(page.getByText(/根据你上传的文件《销售部客户清单\.pdf》/)).toBeVisible({
      timeout: 10_000,
    });

    // 切回个人空间，验证切换后旧空间会话/文件被清空。
    await workspaceSelector.click();
    await page
      .locator('.ant-select-dropdown:visible .ant-select-item-option')
      .filter({ hasText: '我的工作空间' })
      .click();
    await expect(page.locator('.chat-workspace-select')).toContainText('我的工作空间');
    await expect(page.getByText('销售部本周新增 3 家重点客户，详见客户清单。')).toHaveCount(0);
    await expect(page.getByRole('button', { name: /销售部部门会话/ })).toHaveCount(0);
    await expect(
      page.locator('.chat-file-item').filter({ hasText: '销售部客户清单.pdf' }),
    ).toHaveCount(0);

    await expect(page.getByRole('button', { name: '工作空间共享' })).toBeVisible();
    await page.getByRole('button', { name: '工作空间共享' }).click();
    const sharingDrawer = page.getByRole('dialog').filter({ hasText: '工作空间共享' });
    await expect(sharingDrawer.getByText('工作空间共享')).toBeVisible();
    const salesSpace = sharingDrawer
      .locator('.workspace-sharing-item')
      .filter({ hasText: '销售部项目空间' });
    await expect(salesSpace.getByText('本部门成员')).toBeVisible();

    await sharingDrawer.getByRole('button', { name: '创建部门空间' }).click();
    const createDialog = page.locator('.ant-modal').filter({ hasText: '创建部门空间' });
    await expect(createDialog.getByText('创建部门空间', { exact: true }).first()).toBeVisible();
    await createDialog.getByRole('combobox', { name: '所属部门' }).click();
    await page
      .locator('.ant-select-dropdown:visible .ant-select-item-option')
      .filter({ hasText: '销售部' })
      .first()
      .click();
    await createDialog.getByPlaceholder('例如：销售部项目空间').fill('重点客户空间');
    await createDialog.getByRole('radio', { name: '指定成员（创建后手动添加）' }).click();
    await createDialog.getByRole('button', { name: '创建空间' }).click();

    await expect(page.getByText('空间“重点客户空间”已创建。')).toBeVisible();
    const customSpace = sharingDrawer
      .locator('.workspace-sharing-item')
      .filter({ hasText: '重点客户空间' });
    await expect(customSpace.getByText('指定成员')).toBeVisible();
    await customSpace.getByRole('button', { name: '管理成员' }).click();

    const memberDialog = page.locator('.ant-modal').filter({ hasText: '成员管理 · 重点客户空间' });
    await expect(memberDialog.getByText('成员管理 · 重点客户空间')).toBeVisible();
    await memberDialog.getByRole('combobox', { name: '添加本部门成员' }).click();
    await page
      .locator('.ant-select-dropdown:visible .ant-select-item-option')
      .filter({ hasText: '王敏（wangmin）' })
      .click();
    await memberDialog.getByRole('button', { name: '加入空间' }).click();
    await expect(memberDialog.getByText('王敏')).toBeVisible();

    await memberDialog.getByRole('button', { name: '关闭' }).click();
    await page.keyboard.press('Escape');

    // F-003：负责人未把自己加入 CUSTOM，刷新后仍能从部门空间列表看到并管理。
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Riccon 工作空间' })).toBeVisible();
    await page.getByRole('button', { name: '工作空间共享' }).click();
    const reloadedCustomSpace = page
      .getByRole('dialog')
      .locator('.workspace-sharing-item')
      .filter({ hasText: '重点客户空间' });
    await expect(reloadedCustomSpace).toBeVisible();
    await expect(reloadedCustomSpace.getByRole('button', { name: '管理成员' })).toBeVisible();
    await page.keyboard.press('Escape');

    // 普通成员看不到管理入口，但仍看到知识库共享范围。
    await page.context().clearCookies();
    await loginAs(page, M03_CREDENTIALS.member, '王敏');
    await expect(page.getByRole('button', { name: '工作空间共享' })).toHaveCount(0);
    await expect(page.getByText(/知识库按文档可见范围共享/)).toBeVisible();
    await expect(page.getByText(/文档级权限在后续版本提供/)).toHaveCount(0);

    // 知识库引用可跨部门展示，但源文件 404 时只显示文件名与不可访问说明。
    await page.getByRole('button', { name: '新建会话' }).click();
    await expect(page.getByText('开始 J1 问答')).toBeVisible();
    const chooserPromise = page.waitForEvent('filechooser');
    await page.getByRole('button', { name: /上传文件/ }).focus();
    await page.keyboard.press('Enter');
    const chooser = await chooserPromise;
    await chooser.setFiles({
      name: '季度报告.pdf',
      mimeType: 'application/pdf',
      buffer: Buffer.from('%PDF-1.4\n% M03 stub file\n'),
    });
    await expect(
      page.locator('.chat-file-item').first().getByText('解析成功', { exact: true }),
    ).toBeVisible({ timeout: 15_000 });

    await stub.setScenario('citation-source-404');
    consoleGuard.allowNetworkError(/\/api\/v1\/files\/[^/]+\/download-url$/, 404);
    await composer.fill('请总结这份销售资料');
    await composer.press('Enter');
    await expect(page.getByText(/根据你上传的文件/)).toBeVisible({ timeout: 10_000 });
    await page.getByRole('button', { name: /销售部市场分析\.pdf/ }).click();

    const citationDialog = page.getByRole('dialog').filter({ hasText: '引用详情' });
    await expect(citationDialog.getByText('引用详情')).toBeVisible();
    await expect(citationDialog.getByText('销售部市场分析.pdf')).toBeVisible();
    await expect(citationDialog.getByText(/知识库按文档可见范围共享/)).toBeVisible();
    await citationDialog.getByRole('button', { name: '打开原文件' }).click();
    await expect(citationDialog.getByRole('button', { name: '打开原文件' })).toHaveCount(0);
    await expect(citationDialog.getByText('源文件不可访问').first()).toBeVisible();
    await expect(
      citationDialog.getByText('源文件不可访问：它可能属于其他部门的共享空间，或已被删除。'),
    ).toBeVisible();
  });

  test('E2E-M03-07 部门管理页 375px 与键盘', async ({ page, consoleGuard }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await loginAs(page, M03_CREDENTIALS.platformAdmin, '赵蕾');
    await page.goto('/admin/tenants');
    await expect(page.getByText('租户与部门', { exact: true })).toBeVisible();
    await expectNoHorizontalScroll(page);

    const manageLink = page.getByRole('link', { name: '管理部门：示例企业' });
    await manageLink.focus();
    await expect(manageLink).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/\/admin\/tenants\/tenant-acme\/departments$/);
    await expect(page.getByText('部门管理', { exact: true }).first()).toBeVisible();
    await expectNoHorizontalScroll(page);

    const createButton = page.getByRole('button', { name: '创建部门' }).first();
    await createButton.focus();
    await page.keyboard.press('Enter');
    const dialog = page.locator('.ant-modal').filter({ hasText: '创建部门' });
    await expect(dialog.getByText('创建部门', { exact: true }).first()).toBeVisible();
    const nameInput = dialog.getByPlaceholder('例如：销售部');
    await nameInput.focus();
    await page.keyboard.type('销售部');
    await nameInput.press('Enter');
    await expect(page.getByText('部门“销售部”已创建。')).toBeVisible();
    await expectNoHorizontalScroll(page);

    // 无权限态：普通成员访问平台部门页有明确文案，不出现任何部门数据。
    await page.context().clearCookies();
    await loginAs(page, M03_CREDENTIALS.member, '王敏');
    consoleGuard.allowNetworkError(/\/api\/v1\/admin\/tenants(\?.*)?$/, 403);
    await page.goto('/admin/tenants');
    await expect(page.getByText('没有访问权限')).toBeVisible();
    await expect(page.getByText('只有平台管理员可以查看租户与部门管理入口。')).toBeVisible();
    await expectNoHorizontalScroll(page);
  });

  test('E2E-M03-07 部门管理页 axe 无 serious/critical @a11y', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await loginAs(page, M03_CREDENTIALS.platformAdmin, '赵蕾');
    await page.goto('/admin/tenants/tenant-acme/departments');
    await expect(page.getByText('部门管理', { exact: true }).first()).toBeVisible();

    await page.getByRole('button', { name: '创建部门' }).first().click();
    const dialog = page.locator('.ant-modal').filter({ hasText: '创建部门' });
    await dialog.getByPlaceholder('例如：销售部').fill('销售部');
    await dialog.getByRole('button', { name: '创建部门' }).click();
    await expect(page.getByText('部门“销售部”已创建。')).toBeVisible();

    await expectNoSeriousA11yViolations(page);
  });
});
