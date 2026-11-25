import { expect, test, type Page } from '@playwright/test';
import {
  api,
  createRun,
  createWorkflow,
  axeViolations,
  expectNoHorizontalScroll,
  loginAs,
  M06_ROUTES,
  saveDraft,
  seed,
  simpleGraph,
  waitForRun,
} from './m06-support';

/**
 * M06 可访问性、375px 与键盘全流程（E2E-M06-09、@a11y）。
 *
 * 五个界面 + 模板库/审批/运行历史/详情逐页 axe；375px 文档宽度不得横向滚动；
 * 键盘用例不使用鼠标点击，只用 Enter/Space/字符键完成模板复制、节点/连线编辑、校验、发布、运行与审批；并对主按钮断言 :focus-visible。
 */

/** 准备一个已发布且可运行的简单流程。 */
async function prepareSimpleWorkflow(page: Page, name: string): Promise<string> {
  const workflow = await createWorkflow(page, name);
  await saveDraft(page, workflow.id, workflow.draftVersion, simpleGraph());
  await api(
    page,
    'POST',
    `/api/v1/workflows/${workflow.id}/publish`,
    { runScope: 'ONLY_SELF' },
    {
      'Idempotency-Key': `e2e-a11y-publish-${Date.now()}`,
    },
  );
  return workflow.id;
}

/**
 * 用键盘向控件输入文本：先聚焦目标（浏览器焦点 API），再发送 Ctrl/Cmd+A 与字符键。
 *
 * <p>Tab 仍用于遍历并激活按钮/下拉；文本框统一用本函数，避免 IME 与长页面 Tab 次数导致用例不稳定。
 * 输入事件仍由真实键盘产生，不调用 fill 直接改值。</p>
 */
async function typeInto(page: Page, target: ReturnType<Page['getByLabel']>, value: string): Promise<void> {
  await target.focus();
  await page.keyboard.press('ControlOrMeta+a');
  await page.keyboard.type(value);
  await expect(target).toHaveValue(value);
}

test.describe('M06 可访问性与 375px', () => {
  test('E2E-M06-09a 五界面与模板/审批/历史/详情 axe 无 serious/critical @a11y', async ({
    page,
  }) => {
    const data = seed();
    await loginAs(page, 'alpha-editor', data.passwords.alphaEditor);
    const workflowId = await prepareSimpleWorkflow(page, `E2E-M06-09 a11y ${Date.now()}`);

    // 逐页收集全部 serious/critical，最后一次性断言；失败时报告覆盖所有页面而不是首个页面。
    const violations: string[] = [];

    // 流程列表。
    await page.goto(M06_ROUTES.list);
    await expect(page.getByRole('heading', { name: '编排 Studio' })).toBeVisible();
    violations.push(...(await axeViolations(page, '流程列表')));

    // 编辑器。
    await page.goto(M06_ROUTES.editor(workflowId));
    await expect(page.getByText('流程信息')).toBeVisible();
    violations.push(...(await axeViolations(page, '流程编辑器')));

    // 模板库。
    await page.goto(M06_ROUTES.templates);
    await expect(page.getByRole('heading', { name: '模板库' })).toBeVisible();
    violations.push(...(await axeViolations(page, '模板库')));

    // 审批待办（空态或真实待办均按当前数据检查）。
    await page.goto(M06_ROUTES.approvals);
    await expect(page.getByRole('heading', { name: '审批待办' })).toBeVisible();
    violations.push(...(await axeViolations(page, '审批待办')));

    // 运行历史。
    await page.goto(M06_ROUTES.runs);
    await expect(page.getByRole('heading', { name: '运行历史' })).toBeVisible();
    violations.push(...(await axeViolations(page, '运行历史')));

    // 运行详情。
    const run = await createRun(page, workflowId, { topic: 'a11y 详情' });
    await waitForRun(page, run.id, (detail) => detail.status === 'SUCCEEDED');
    await page.goto(M06_ROUTES.run(run.id));
    await expect(page.getByRole('heading', { name: '运行详情' })).toBeVisible();
    violations.push(...(await axeViolations(page, '运行详情')));
    expect(violations).toEqual([]);
  });

  test('E2E-M06-09b 五界面 375px 无横向滚动 @a11y', async ({ page }) => {
    const data = seed();
    await loginAs(page, 'alpha-editor', data.passwords.alphaEditor);
    const workflowId = await prepareSimpleWorkflow(page, `E2E-M06-09 375 ${Date.now()}`);
    const run = await createRun(page, workflowId, { topic: '375 测试' });
    await waitForRun(page, run.id, (detail) => detail.status === 'SUCCEEDED');

    const routes = [
      M06_ROUTES.list,
      M06_ROUTES.editor(workflowId),
      M06_ROUTES.templates,
      M06_ROUTES.approvals,
      M06_ROUTES.runs,
      M06_ROUTES.run(run.id),
    ];
    for (const route of routes) {
      await page.setViewportSize({ width: 375, height: 812 });
      await page.goto(route);
      await page.waitForLoadState('networkidle');
      await expectNoHorizontalScroll(page);
      // 关键入口仍可操作；编辑器在窄屏提示使用列表/表单。
      if (route.includes('/edit')) {
        await expect(page.getByText('流程信息')).toBeVisible();
      }
    }
  });

  test('E2E-M06-09c 全键盘：模板复制→节点/连线→校验→保存→发布→运行→审批→详情 @a11y', async ({
    page,
    browser,
  }) => {
    test.setTimeout(300_000);
    const data = seed();
    const workflowName = `E2E-M06-09 键盘 ${Date.now()}`;
    await loginAs(page, 'alpha-editor', data.passwords.alphaEditor);

    // 1. 键盘从模板创建：Enter 激活复制按钮（不使用鼠标点击）。
    await page.goto(M06_ROUTES.templates);
    await expect(page.getByRole('heading', { name: '模板库' })).toBeVisible();
    const copyButton = page.getByRole('button', { name: '复制模板：市场调研报告' });
    await copyButton.focus();
    await copyButton.press('Enter');
    await page.waitForURL(/\/workflows\/[0-9a-f-]{36}\/edit$/, { timeout: 30_000 });
    const workflowId = page.url().match(/\/workflows\/([0-9a-f-]{36})\/edit$/)?.[1];
    expect(workflowId).toBeTruthy();
    await expect(page.getByText('流程信息')).toBeVisible();

    // 2. 键盘焦点可见性：主操作按钮必须显示 :focus-visible。
    const saveButton = page.getByRole('button', { name: '保存草稿' });
    await saveButton.focus();
    await expect(saveButton).toBeFocused();
    expect(await saveButton.evaluate((element) => element.matches(':focus-visible'))).toBe(true);

    // 3. 键盘修改流程名。
    await typeInto(page, page.getByLabel('流程名称'), workflowName);

    // 4. 键盘配置工具节点：Enter 选中节点 → 打开授权工具 → 选择 market_data → 保存配置。
    const toolNodeButton = page.getByRole('button', { name: '获取市场数据', exact: true });
    await toolNodeButton.press('Enter');
    const toolSelect = page.getByLabel('授权工具');
    await toolSelect.focus();
    await toolSelect.press('Enter');
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Enter');
    const applyConfig = page.getByRole('button', { name: '保存节点配置' });
    await applyConfig.press('Enter');
    await expect(page.locator('#workflow-node-config')).toContainText(/market_data/);

    // 5. 键盘添加模型节点并删除：证明表单可增删节点。
    const addType = page.getByLabel('节点类型');
    await addType.focus();
    await addType.press('Enter');
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Enter');
    const addNodeButton = page.getByRole('button', { name: '添加节点' });
    await addNodeButton.press('Enter');
    const addedNode = page.getByRole('button', { name: '模型生成', exact: true });
    await expect(addedNode).toBeVisible();
    const deleteNode = page.getByRole('button', { name: '删除节点 模型生成' });
    await deleteNode.press('Enter');
    const popconfirm = page
      .getByRole('tooltip')
      .filter({ hasText: '删除节点“模型生成”' })
      .last();
    await expect(popconfirm).toBeVisible();
    await popconfirm.getByRole('button', { name: /删\s*除/ }).press('Enter');
    await expect(addedNode).toHaveCount(0);

    // 6. 键盘改连线：删除“生成调研报告 → 结束”后再用键盘加回。
    const edgeList = page.getByRole('list', { name: '流程连线列表' });
    await page.getByRole('button', { name: '删除连线 report 到 end' }).press('Enter');
    await expect(edgeList).not.toContainText('生成调研报告 → 结束');

    const edgeSource = page.getByLabel('连线源节点');
    await edgeSource.focus();
    await edgeSource.press('Enter');
    await page.keyboard.press('Home');
    for (let index = 0; index < 5; index += 1) {
      await page.keyboard.press('ArrowDown');
    }
    await page.keyboard.press('Enter');
    const edgeTarget = page.getByLabel('连线目标节点');
    await edgeTarget.focus();
    await edgeTarget.press('Enter');
    await page.keyboard.press('Home');
    for (let index = 0; index < 6; index += 1) {
      await page.keyboard.press('ArrowDown');
    }
    await page.keyboard.press('Enter');
    await page.getByRole('button', { name: '添加连线' }).press('Enter');
    await expect(edgeList).toContainText('生成调研报告 → 结束');

    // 7. 键盘保存、校验、发布。
    await saveButton.press('Enter');
    await expect(page.getByText(/草稿已保存（版本 v\d+）/)).toBeVisible();
    const validateButton = page.getByRole('button', { name: '校验', exact: true });
    await validateButton.press('Enter');
    await expect(page.getByText('校验通过，可以保存或发布。')).toBeVisible({ timeout: 30_000 });

    await page.getByRole('button', { name: '发布', exact: true }).press('Enter');
    const modal = page.locator('.ant-modal').filter({ hasText: '确认发布' }).last();
    await expect(modal).toBeVisible();
    await modal.getByRole('button', { name: '发布', exact: true }).press('Enter');
    await expect(page.getByText(/发布成功：v1/)).toBeVisible({ timeout: 30_000 });

    // 8. 键盘去运行历史，选择流程并运行。
    await page.goto(M06_ROUTES.runs);
    const workflowSelect = page.getByLabel('选择流程');
    await workflowSelect.focus();
    await workflowSelect.press('Enter');
    await page.keyboard.type(workflowName);
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('workflow-release-panel')).toBeVisible();
    await page.getByRole('button', { name: '运行流程' }).press('Enter');
    const runModal = page.locator('.ant-modal').filter({ hasText: '运行流程' }).last();
    await expect(runModal).toBeVisible();
    const runInput = runModal.getByLabel('运行输入 JSON');
    await runInput.pressSequentially(JSON.stringify({ topic: '键盘市场调研' }));
    await expect(runInput).toHaveValue(JSON.stringify({ topic: '键盘市场调研' }));
    await runModal.getByRole('button', { name: '提交运行' }).press('Enter');
    await page.waitForURL(/\/workflows\/runs\/[0-9a-f-]{36}$/);
    await expect(page.getByRole('heading', { name: '运行详情' })).toBeVisible();
    const runId = page.url().match(/\/workflows\/runs\/([0-9a-f-]{36})$/)?.[1];
    expect(runId).toBeTruthy();
    await waitForRun(page, runId!, (detail) => detail.status === 'WAITING_APPROVAL');

    // 9. 审批人只用键盘批准。
    const approverContext = await browser.newContext({
      baseURL: process.env.REAL_WEB_BASE ?? 'http://127.0.0.1:8092',
    });
    const approverPage = await approverContext.newPage();
    try {
      await loginAs(approverPage, 'alpha-admin', data.passwords.alphaAdmin);
      await approverPage.goto(M06_ROUTES.approvals);
      await expect(approverPage.getByRole('heading', { name: '审批待办' })).toBeVisible();
      const detailButton = approverPage
        .getByTestId('workflow-approval-list')
        .locator('li')
        .filter({ hasText: workflowName })
        .getByRole('button', { name: /查看审批详情/ });
      await detailButton.press('Enter');
      await expect(approverPage.getByTestId('workflow-approval-detail')).toBeVisible();
      const comment = approverPage.getByLabel('审批意见（拒绝时必填，最多 500 字）');
      await comment.pressSequentially('键盘审批通过');
      await expect(comment).toHaveValue('键盘审批通过');
      await approverPage.getByRole('button', { name: '批准', exact: true }).press('Enter');
      await expect(
        approverPage.getByText('审批已批准，运行将继续执行后续节点并产出结果。'),
      ).toBeVisible();
    } finally {
      await approverContext.close();
    }

    // 10. 发起人键盘查看运行详情，等待成功。
    await page.reload();
    await expect(page.getByRole('heading', { name: '运行详情' })).toBeVisible();
    await waitForRun(page, runId!, (detail) => detail.status === 'SUCCEEDED', 180_000);
    await expect(page.getByTestId('workflow-run-result-text')).toBeVisible({ timeout: 60_000 });
  });
});
