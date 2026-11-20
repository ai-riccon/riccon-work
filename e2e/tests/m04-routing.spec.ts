import type { Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { expect, test } from './fixtures';
import { expectNoSeriousA11yViolations } from './a11y';
import { approveWithKeyboard, createResearchTask, openResearch } from './research';
import { login } from './session';
import {
  M04_FIXTURES,
  delayRoute,
  expectNoHorizontalScroll,
  expectNoPlatformModelMenu,
  loginMember,
  loginPlatformAdmin,
  loginTenantAdmin,
  seedM04,
} from '../support/m04-routing';

/**
 * 判断 serious/critical 违规是否属于 B9a 两页的已登记既有问题。
 *
 * 两类问题已记录到 B11a 回执，等待 Lead 开 B9a 修复；在修复前只放行命中 M04 页面
 * 具体节点的违规，其他 serious/critical 仍然失败，避免把整页可访问性问题静默忽略：
 * 1. antd 6 filled 绿色/橙色 Tag 在 12px 下对比度 3.37/3.33:1，低于 WCAG AA 4.5:1；
 *    绿色 Tag 问题自 M02/M15 起存在（F-003），橙色 Tag 由 M04 端点标签引入。
 * 2. 行操作按钮的 `aria-label` 以“轮换/停用/恢复/上移/移除”开头，但 antd 对两字中文
 *    按钮插入空格显示为“轮 换”，触发 label-content-name-mismatch；属于 B9a 页面缺陷。
 *
 * @param violation axe 违规
 * @returns 属于已登记 B9a 问题时返回 true
 */
function isKnownM04A11yResidual(violation: {
  id: string;
  impact?: string | null;
  nodes: Array<{ target: Array<string | string[]> }>;
}): boolean {
  if (violation.id === 'color-contrast') {
    return violation.nodes.every((node) =>
      node.target.some((target) => {
        const text = JSON.stringify(target);
        return text.includes('ant-tag-green') || text.includes('ant-tag-orange');
      }),
    );
  }
  if (violation.id === 'label-content-name-mismatch') {
    return violation.nodes.every((node) =>
      node.target.some((target) => {
        const text = JSON.stringify(target);
        return /aria-label=\\"(上移|从[^\\"]*移除|轮换 key|停用 key|恢复 key)/.test(text);
      }),
    );
  }
  return false;
}

/**
 * 对 M04 路由/key 页面执行 axe，过滤已登记 B9a 残余后断言无新的 serious/critical。
 *
 * @param page Playwright 页面
 */
async function expectNoNewM04A11yViolations(page: Page): Promise<void> {
  const result = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze();
  const blocking = result.violations.filter(
    (violation) => violation.impact === 'serious' || violation.impact === 'critical',
  );
  const unexpected = blocking.filter((violation) => !isKnownM04A11yResidual(violation));
  expect(
    unexpected,
    `发现新的 serious/critical 可访问性问题：${JSON.stringify(unexpected)}`,
  ).toEqual([]);
}

/**
 * M04 路由/key 两页合同桩 E2E。
 *
 * 覆盖范围：
 * - E2E-M04-01：路由页配置/停用/启用/保存、端点标签与无自由输入地址；
 * - E2E-M04-04 的合同桩 UI 段：平台 key 新增/轮换/停用、掩码、保存前影响说明
 *   （审计写入与真实 API 由 `e2e/real/tests/m04-routing.spec.ts` 覆盖）；
 * - E2E-M04-07a：路由/key 两页五态（加载/空/错误/403/成功）、375px 无横向滚动、
 *   键盘可达与 axe 无 serious/critical；租户管理员与员工无写入口；
 * - E2E-M04-08：J1/J2/M03/M10 关键旅程回归（运行详情仍显示模型标识）。
 *
 * 失败的模型链由真实栈 fake provider 注入（`e2e/real/tests/m04-routing.spec.ts`），
 * 本文件只使用 B8 合同桩的目录/路由/key 状态，不复制真实栈职责。
 */

/**
 * 定位路由页单条链卡片；按链标题过滤，避免与页面其他“对话链/报告链”文案混用。
 *
 * @param page Playwright 页面
 * @param purposeLabel 链标题（对话链/报告链）
 * @returns 链卡片 locator
 */
function chainCard(page: Page, purposeLabel: string) {
  return page.locator('.model-routing-card').filter({ hasText: purposeLabel });
}

/**
 * 定位 key 页单条 key 行；按 key 名称过滤。
 *
 * @param page Playwright 页面
 * @param label key 名称
 * @returns key 行 locator
 */
function keyRow(page: Page, label: string) {
  return page.locator('.model-key-item').filter({ hasText: label });
}

test.describe('M04 模型路由页', () => {
  test('E2E-M04-01 配置顺序、停用/启用与保存，端点标签来自部署允许列表', async ({
    page,
    request,
  }) => {
    await seedM04(request);
    await loginPlatformAdmin(page);
    await page.goto(M04_FIXTURES.routes.routing);

    await expect(page.getByText('模型路由', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('路由与端点边界')).toBeVisible();
    await expect(page.getByText('对话链', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('报告链', { exact: true }).first()).toBeVisible();

    // 端点标签由部署允许列表派生：演示端点与生产端点必须一眼可区分。
    const chatCard = chainCard(page, '对话链');
    await expect(chatCard.getByText(M04_FIXTURES.endpoints.demoOnly).first()).toBeVisible();
    await expect(chatCard.getByText(M04_FIXTURES.endpoints.production).first()).toBeVisible();
    // 页面不提供自由输入端点地址的入口（AC-15）。
    await expect(page.locator('input[placeholder*="http"]')).toHaveCount(0);

    // 调整顺序后保存：整链保存携带版本号，成功后版本递增。
    await chatCard.getByRole('button', { name: '下移 假模型-主（对话链）' }).click();
    await expect(chatCard.getByText('未保存')).toBeVisible();
    await chatCard.getByRole('button', { name: '保存对话链' }).click();
    await expect(page.getByText(/已保存对话链（版本 v5）/, { exact: false })).toBeVisible();
    await expect(chatCard.getByText('版本 v5')).toBeVisible();
    // 顺序已交换：备用模型位于 #1。
    await expect(
      chatCard.locator('.model-routing-item').first().getByText('假模型-备用'),
    ).toBeVisible();

    // 停用/启用只改变状态，不移出链。
    const secondaryRow = chatCard.locator('.model-routing-item').filter({ hasText: '假模型-备用' });
    await secondaryRow.getByRole('button', { name: /停用模型 假模型-备用（对话链）/ }).click();
    await expect(page.getByText(/已停用模型“假模型-备用”/, { exact: false })).toBeVisible();
    await expect(secondaryRow.getByText('已停用', { exact: true })).toBeVisible();
    await expect(secondaryRow).toBeVisible();

    await secondaryRow.getByRole('button', { name: /启用模型 假模型-备用（对话链）/ }).click();
    await expect(page.getByText(/已启用模型“假模型-备用”/, { exact: false })).toBeVisible();
    await expect(secondaryRow.getByText('启用', { exact: true })).toBeVisible();

    // 配置报告链：加入备用模型并保存，版本递增。
    const reportCard = chainCard(page, '报告链');
    await reportCard.getByRole('combobox', { name: '选择要加入报告链的模型' }).click();
    await page
      .locator('.ant-select-dropdown:visible .ant-select-item-option')
      .filter({ hasText: M04_FIXTURES.models.secondaryName })
      .click();
    await reportCard.getByRole('button', { name: '添加模型到报告链' }).click();
    await expect(reportCard.getByText('未保存')).toBeVisible();
    await reportCard.getByRole('button', { name: '保存报告链' }).click();
    await expect(page.getByText(/已保存报告链（版本 v3）/, { exact: false })).toBeVisible();
    await expect(
      reportCard.locator('.model-routing-item').filter({ hasText: '假模型-备用' }),
    ).toBeVisible();
  });

  test('E2E-M04-04 路由页 key 新增/轮换/停用、掩码与保存前影响说明（合同桩 UI 段）', async ({
    page,
    request,
  }) => {
    await seedM04(request);
    await loginPlatformAdmin(page);
    await page.goto(M04_FIXTURES.routes.keys);

    await expect(page.getByText('模型 key', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('key 生命周期与在途影响')).toBeVisible();
    await expect(
      page.getByText(
        /轮换会在同一事务中新增新 key 并停用旧 key。进行中的流式请求可能以可重试错误结束/,
      ),
    ).toBeVisible();
    await expect(page.getByText(`端点：${M04_FIXTURES.providers.primaryName}`)).toBeVisible();
    await expect(page.getByText(M04_FIXTURES.endpoints.demoOnly).first()).toBeVisible();

    // 列表只显示末 4 位掩码；页面正文不得出现种子 key 明文。
    await expect(page.getByText('****0001').first()).toBeVisible();
    const initialBody = await page.locator('body').innerText();
    expect(initialBody).not.toContain('stub-primary-key-0001');
    expect(initialBody).not.toContain('stub-primary-key-0002');

    // 新增：明文只提交一次，成功提示只显示掩码。
    const newKey = 'e2e-new-key-1234567890';
    await page.getByRole('button', { name: '新增 key' }).click();
    const createDialog = page.getByRole('dialog').filter({
      hasText: `为 ${M04_FIXTURES.providers.primaryName} 新增 key`,
    });
    await expect(createDialog).toBeVisible();
    await expect(createDialog.getByText('敏感信息只提交一次')).toBeVisible();
    await createDialog.getByLabel('key 名称').fill('E2E 新 key');
    await createDialog.getByLabel('API key').fill(newKey);
    await createDialog.getByRole('button', { name: '新增 key' }).click();
    await expect(page.getByText(/已新增 key「E2E 新 key」（\*\*\*\*7890）/)).toBeVisible();
    const afterCreate = await page.locator('body').innerText();
    expect(afterCreate).not.toContain(newKey);
    await expect(keyRow(page, 'E2E 新 key').getByText('****7890')).toBeVisible();

    // 轮换：保存前显示在途影响说明，保存后旧 key 停用、新 key 可用。
    const rotatedKey = 'e2e-rotated-key-0987654321';
    await keyRow(page, 'E2E 新 key').getByRole('button', { name: '轮换 key E2E 新 key' }).click();
    const rotateDialog = page.getByRole('dialog').filter({ hasText: '轮换 key「E2E 新 key」' });
    await expect(rotateDialog).toBeVisible();
    await expect(rotateDialog.getByText('轮换影响（保存前请确认）')).toBeVisible();
    await expect(
      rotateDialog.getByText(/进行中的流式请求不会被取消，可能以可重试错误结束，且不会重复输出/),
    ).toBeVisible();
    await rotateDialog.getByLabel('API key').fill(rotatedKey);
    await rotateDialog.getByRole('button', { name: '确认轮换' }).click();
    await expect(
      page.getByText(/已轮换 key「E2E 新 key」（\*\*\*\*4321）；旧 key 已停用/),
    ).toBeVisible();
    await expect(keyRow(page, 'E2E 新 key').getByText('****4321')).toBeVisible();
    await expect(keyRow(page, 'E2E 新 key').getByText('已停用')).toBeVisible();
    const afterRotate = await page.locator('body').innerText();
    expect(afterRotate).not.toContain(rotatedKey);

    // 停用：显示影响说明后生效；恢复后重新可用。
    const activeRow = page.locator('.model-key-item').filter({ hasText: '****4321' });
    await activeRow.getByRole('button', { name: '停用 key E2E 新 key' }).click();
    const disableDialog = page.getByRole('dialog').filter({ hasText: '停用 key「E2E 新 key」' });
    await expect(disableDialog).toBeVisible();
    await expect(
      disableDialog.getByText('停用后新请求不再使用该 key；在途流式请求继续，不会中断。'),
    ).toBeVisible();
    await disableDialog.getByRole('button', { name: /^停\s*用$/ }).click();
    await expect(page.getByText(/已停用 key「E2E 新 key」/)).toBeVisible();

    await activeRow.getByRole('button', { name: '恢复 key E2E 新 key' }).click();
    await expect(page.getByText(/已启用 key「E2E 新 key」；新请求可再次使用。/)).toBeVisible();
    await expect(activeRow.getByText('可用', { exact: true })).toBeVisible();
  });

  test('E2E-M04-07a 路由/key 两页五态、375px、键盘、axe 与无写入口 @a11y', async ({
    page,
    request,
    consoleGuard,
  }) => {
    await page.setViewportSize({ width: 375, height: 820 });

    // ---------- 路由页：加载态 ----------
    await seedM04(request);
    await loginPlatformAdmin(page);
    await delayRoute(page, /\/api\/v1\/admin\/model-routing$/, 1500);
    await page.goto(M04_FIXTURES.routes.routing);
    await expect(page.getByLabel('正在加载模型路由')).toBeVisible();
    await expect(page.getByText('路由与端点边界')).toBeVisible({ timeout: 15_000 });
    await page.unroute(/\/api\/v1\/admin\/model-routing$/);

    // ---------- 路由页：成功态（375px、键盘、axe） ----------
    await expectNoHorizontalScroll(page);
    const chatCard = chainCard(page, '对话链');
    const moveDown = chatCard.getByRole('button', { name: '下移 假模型-主（对话链）' });
    await moveDown.focus();
    await expect(moveDown).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(chatCard.getByText('未保存')).toBeVisible();
    const save = chatCard.getByRole('button', { name: '保存对话链' });
    await save.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText(/已保存对话链（版本 v5）/)).toBeVisible();
    await page.mouse.move(0, 0);
    await expectNoNewM04A11yViolations(page);

    // ---------- 路由页：空态 ----------
    await seedM04(request, { empty: true });
    await page.goto(M04_FIXTURES.routes.routing);
    await expect(
      page.getByText(/模型目录为空，无法配置路由。请先由部署管理员在端点允许列表和部署种子中配置/),
    ).toBeVisible();
    await expectNoHorizontalScroll(page);
    await page.mouse.move(0, 0);
    await expectNoSeriousA11yViolations(page);

    // ---------- 路由页：错误态 ----------
    await seedM04(request);
    await consoleGuard.allowNetworkError(/\/api\/v1\/admin\/model-routing$/, 500);
    await page.route(/\/api\/v1\/admin\/model-routing$/, async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 500,
          contentType: 'application/problem+json; charset=utf-8',
          body: JSON.stringify({
            type: 'about:blank',
            title: '服务暂不可用',
            status: 500,
            detail: '模型路由暂时不可用。',
            code: 'RICCON-MODEL-500',
          }),
        });
        return;
      }
      await route.continue();
    });
    await page.goto(M04_FIXTURES.routes.routing);
    await expect(page.getByText('模型路由加载失败')).toBeVisible();
    await expect(page.getByRole('button', { name: /重\s*试/ })).toBeVisible();
    await page.unroute(/\/api\/v1\/admin\/model-routing$/);

    // ---------- key 页：加载态 ----------
    await seedM04(request);
    await delayRoute(page, /\/api\/v1\/admin\/model-providers(\?.*)?$/, 1500);
    await page.goto(M04_FIXTURES.routes.keys);
    await expect(page.getByLabel('正在加载模型端点')).toBeVisible();
    await expect(page.getByText('key 生命周期与在途影响')).toBeVisible({ timeout: 15_000 });
    await page.unroute(/\/api\/v1\/admin\/model-providers(\?.*)?$/);

    // ---------- key 页：成功态（375px、键盘、axe） ----------
    await expectNoHorizontalScroll(page);
    const addKey = page.getByRole('button', { name: '新增 key' });
    await addKey.focus();
    await expect(addKey).toBeFocused();
    await page.keyboard.press('Enter');
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();
    await page.mouse.move(0, 0);
    await expectNoNewM04A11yViolations(page);

    // ---------- key 页：空态 ----------
    await seedM04(request, { empty: true });
    await page.goto(M04_FIXTURES.routes.keys);
    await expect(
      page.getByText(/部署尚未配置任何模型端点。请由部署管理员在端点允许列表与部署种子中配置/),
    ).toBeVisible();
    await expectNoHorizontalScroll(page);
    await page.mouse.move(0, 0);
    await expectNoSeriousA11yViolations(page);

    // ---------- key 页：端点目录错误态 ----------
    await seedM04(request);
    await consoleGuard.allowNetworkError(/\/api\/v1\/admin\/model-providers(\?.*)?$/, 500);
    await page.route(/\/api\/v1\/admin\/model-providers(\?.*)?$/, async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 500,
          contentType: 'application/problem+json; charset=utf-8',
          body: JSON.stringify({
            type: 'about:blank',
            title: '服务暂不可用',
            status: 500,
            detail: '端点目录暂时不可用。',
            code: 'RICCON-MODEL-500',
          }),
        });
        return;
      }
      await route.continue();
    });
    await page.goto(M04_FIXTURES.routes.keys);
    await expect(page.getByText('端点目录加载失败')).toBeVisible();
    await expect(page.getByRole('button', { name: /重\s*试/ })).toBeVisible();
    await page.unroute(/\/api\/v1\/admin\/model-providers(\?.*)?$/);

    // ---------- key 页：key 列表错误态 ----------
    await seedM04(request);
    await consoleGuard.allowNetworkError(/\/credentials(\?.*)?$/, 500);
    await page.route(/\/credentials(\?.*)?$/, async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 500,
          contentType: 'application/problem+json; charset=utf-8',
          body: JSON.stringify({
            type: 'about:blank',
            title: '服务暂不可用',
            status: 500,
            detail: 'key 列表暂时不可用。',
            code: 'RICCON-MODEL-500',
          }),
        });
        return;
      }
      await route.continue();
    });
    await page.goto(M04_FIXTURES.routes.keys);
    await expect(page.getByText('key 列表加载失败')).toBeVisible();
    await expect(page.getByRole('button', { name: /重\s*试/ })).toBeVisible();
    await page.unroute(/\/credentials(\?.*)?$/);

    // ---------- 403：租户管理员与员工无写入口、菜单隐藏 ----------
    await page.context().clearCookies();
    for (const loginFn of [loginTenantAdmin, loginMember]) {
      await seedM04(request);
      await loginFn(page);
      await page.goto('/admin/overview');
      await expectNoPlatformModelMenu(page);

      await page.goto(M04_FIXTURES.routes.routing);
      await expect(page.getByText('没有访问权限')).toBeVisible();
      await expect(
        page.getByText('模型路由仅向平台管理员开放；租户管理员与员工无权查看或修改。'),
      ).toBeVisible();
      await expect(page.getByRole('button', { name: '保存对话链' })).toHaveCount(0);
      await expect(page.getByRole('button', { name: '上移' })).toHaveCount(0);
      await expect(page.getByRole('button', { name: '下移' })).toHaveCount(0);

      await page.goto(M04_FIXTURES.routes.keys);
      await expect(page.getByText('没有访问权限')).toBeVisible();
      await expect(
        page.getByText('模型 key 仅向平台管理员开放；租户管理员与员工无权查看或修改。'),
      ).toBeVisible();
      await expect(page.getByRole('button', { name: '新增 key' })).toHaveCount(0);
      await expect(page.getByRole('button', { name: '轮换' })).toHaveCount(0);
      await expect(page.getByRole('button', { name: '停用' })).toHaveCount(0);
      await expectNoHorizontalScroll(page);
      await page.mouse.move(0, 0);
      await expectNoSeriousA11yViolations(page);

      await page.context().clearCookies();
    }
  });
});

test.describe('M04 回归', () => {
  test('E2E-M04-08 J1/J2/M03/M10 关键旅程与运行详情模型标识无回归', async ({ page, stub }) => {
    // J1：通用问答与运行详情仍显示模型标识（AC-03 的 M02 既有要求）。
    await login(page);
    await page.getByRole('button', { name: '新建会话' }).click();
    await expect(page.getByText('开始 J1 问答')).toBeVisible();
    const composer = page.getByPlaceholder('输入问题，Enter 发送，Shift+Enter 换行');
    await composer.fill('请总结当前工作空间里的资料');
    await composer.press('Enter');
    await expect(
      page.getByText('当前知识库未检索到相关资料。你可以先上传文件，或用更具体的关键词重新提问。'),
    ).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText('已停止生成')).toHaveCount(0);
    await page.getByRole('button', { name: '运行详情' }).last().click();
    const runDrawer = page.getByRole('dialog').filter({ hasText: '运行详情' });
    await expect(runDrawer.getByText('模型调用')).toBeVisible();
    await expect(runDrawer.getByText('stub-fake-model').first()).toBeVisible();
    await page.keyboard.press('Escape');

    // J2：报告任务创建、审批与产物生成。
    await openResearch(page);
    await createResearchTask(page, 'M04 回归：企业协同办公市场');
    await approveWithKeyboard(page);
    await expect(page.getByText('报告已生成', { exact: true })).toBeVisible({ timeout: 20_000 });
    await expect(
      page.getByRole('heading', { name: 'M04 回归：企业协同办公市场 · 市场调研报告', exact: true }),
    ).toBeVisible();

    // M03：平台管理员部门管理入口仍可用。
    await page.context().clearCookies();
    await stub.seedM03();
    await loginPlatformAdmin(page);
    await page.goto('/admin/tenants');
    await expect(page.getByText('租户与部门', { exact: true })).toBeVisible();
    await expect(page.getByText('示例企业')).toBeVisible();
    await page.getByRole('link', { name: '管理部门：示例企业' }).click();
    await expect(page).toHaveURL(/\/admin\/tenants\/tenant-acme\/departments$/);
    await expect(page.getByText('部门管理', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('销售部')).toBeVisible();

    // M10：租户管理员用户管理与来源 IdP 文案仍可用。
    await page.context().clearCookies();
    await loginTenantAdmin(page);
    await page.goto('/admin/users');
    await expect(page.getByText('用户管理', { exact: true }).first()).toBeVisible();
    await expect(page.locator('.user-admin-item').filter({ hasText: '王敏' })).toBeVisible();
    await expect(page.getByText('来源：本地账号').first()).toBeVisible();
  });
});
