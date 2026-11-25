import { expect, test, type Page } from '@playwright/test';
import {
  api,
  createRun,
  createWorkflow,
  expectAxeClean,
  expectNoHorizontalScroll,
  loginAs,
  M06_ROUTES,
  saveDraft,
  seed,
  simpleGraph,
  tabTo,
  waitForRun,
} from './m06-support';

/**
 * M06 可访问性、375px 与键盘全流程（E2E-M06-09、@a11y）。
 *
 * 五个界面 + 模板库/审批/运行历史/详情逐页 axe；375px 文档宽度不得横向滚动；
 * 键盘用例只用 Tab/Enter/字符键完成模板复制、节点/连线编辑、校验、发布、运行与审批。
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

    // 流程列表。
    await page.goto(M06_ROUTES.list);
    await expect(page.getByRole('heading', { name: '编排 Studio' })).toBeVisible();
    await expectAxeClean(page, '流程列表');

    // 编辑器。
    await page.goto(M06_ROUTES.editor(workflowId));
    await expect(page.getByText('流程信息')).toBeVisible();
    await expectAxeClean(page, '流程编辑器');

    // 模板库。
    await page.goto(M06_ROUTES.templates);
    await expect(page.getByRole('heading', { name: '模板库' })).toBeVisible();
    await expectAxeClean(page, '模板库');

    // 审批待办（空态或真实待办均按当前数据检查）。
    await page.goto(M06_ROUTES.approvals);
    await expect(page.getByRole('heading', { name: '审批待办' })).toBeVisible();
    await expectAxeClean(page, '审批待办');

    // 运行历史。
    await page.goto(M06_ROUTES.runs);
    await expect(page.getByRole('heading', { name: '运行历史' })).toBeVisible();
    await expectAxeClean(page, '运行历史');

    // 运行详情。
    const run = await createRun(page, workflowId, { topic: 'a11y 详情' });
    await waitForRun(page, run.id, (detail) => detail.status === 'SUCCEEDED');
    await page.goto(M06_ROUTES.run(run.id));
    await expect(page.getByRole('heading', { name: '运行详情' })).toBeVisible();
    await expectAxeClean(page, '运行详情');
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
    test.setTimeout(360_000);
    const data = seed();
    const workflowName = `E2E-M06-09 键盘 ${Date.now()}`;
    await loginAs(page, 'alpha-editor', data.passwords.alphaEditor);

    // 1. 键盘从模板创建：Tab 到“从模板创建”，Enter，填写名称并创建。
    await page.goto(M06_ROUTES.templates);
    await expect(page.getByRole('heading', { name: '模板库' })).toBeVisible();
    const copyButton = page.getByRole('button', { name: '复制模板：市场调研报告' });
    await tabTo(page, copyButton);
    await page.keyboard.press('Enter');
    await page.waitForURL(/\/workflows\/[0-9a-f-]{36}\/edit$/, { timeout: 30_000 });
    const workflowId = page.url().match(/\/workflows\/([0-9a-f-]{36})\/edit$/)?.[1];
    expect(workflowId).toBeTruthy();
    await expect(page.getByText('流程信息')).toBeVisible();

    // 2. 键盘修改流程名。
    const nameInput = page.getByLabel('流程名称');
    await tabTo(page, nameInput);
    await typeInto(page, nameInput, workflowName);

    // 3. 键盘配置工具节点：选中节点 → 打开授权工具 → 选择 market_data → 保存配置。
    const toolNodeButton = page.getByRole('button', { name: '获取市场数据', exact: true });
    await tabTo(page, toolNodeButton);
    await page.keyboard.press('Enter');
    const toolSelect = page.getByLabel('授权工具');
    await tabTo(page, toolSelect);
    await page.keyboard.press('Enter');
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Enter');
    const applyConfig = page.getByRole('button', { name: '保存节点配置' });
    await tabTo(page, applyConfig);
    await page.keyboard.press('Enter');
    await expect(page.locator('#workflow-node-config')).toContainText(/market_data/);

    // 4. 键盘添加模型节点并用键盘删除：证明列表/表单可增删节点，不依赖画布。
    const addType = page.getByLabel('节点类型');
    await tabTo(page, addType);
    await page.keyboard.press('Enter');
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Enter');
    const addNodeButton = page.getByRole('button', { name: '添加节点' });
    await tabTo(page, addNodeButton);
    await page.keyboard.press('Enter');
    const addedNode = page.getByRole('button', { name: '模型生成', exact: true });
    await expect(addedNode).toBeVisible();
    const deleteNode = page.getByRole('button', { name: '删除节点 模型生成' });
    await tabTo(page, deleteNode, 500);
    await page.keyboard.press('Enter');
    const popconfirm = page.locator('.ant-popover:not(.ant-popover-hidden)').last();
    await expect(popconfirm).toBeVisible();
    const confirmDelete = popconfirm.getByRole('button', { name: '删除', exact: true });
    await tabTo(page, confirmDelete, 40);
    await page.keyboard.press('Enter');
    await expect(addedNode).toHaveCount(0);

    // 5. 键盘改连线：删除“生成调研报告 → 结束”后再用键盘加回同一连线。
    const deleteOldEdge = page.getByRole('button', { name: '删除连线 report 到 end' });
    await tabTo(page, deleteOldEdge, 500);
    await page.keyboard.press('Enter');
    await expect(page.getByRole('list', { name: '流程连线列表' })).not.toContainText(
      '生成调研报告 → 结束',
    );

    const edgeSource = page.getByLabel('连线源节点');
    await tabTo(page, edgeSource, 500);
    await page.keyboard.press('Enter');
    await page.keyboard.press('Home');
    // 删除新增节点后顺序：start,retrieval,draft,approval,tool,report,end；源为 report。
    for (let index = 0; index < 5; index += 1) {
      await page.keyboard.press('ArrowDown');
    }
    await page.keyboard.press('Enter');
    const edgeTarget = page.getByLabel('连线目标节点');
    await tabTo(page, edgeTarget, 120);
    await page.keyboard.press('Enter');
    await page.keyboard.press('Home');
    for (let index = 0; index < 6; index += 1) {
      await page.keyboard.press('ArrowDown');
    }
    await page.keyboard.press('Enter');
    const addEdgeButton = page.getByRole('button', { name: '添加连线' });
    await tabTo(page, addEdgeButton, 120);
    await page.keyboard.press('Enter');
    await expect(page.getByRole('list', { name: '流程连线列表' })).toContainText(
      '生成调研报告 → 结束',
    );

    // 6. 键盘把 END.resultFrom 保持为“生成调研报告”（模板默认，重新保存一次验证表单可写）。
    const endNodeButton = page.getByRole('button', { name: '结束', exact: true });
    await tabTo(page, endNodeButton, 500);
    await page.keyboard.press('Enter');
    await tabTo(page, applyConfig);
    await page.keyboard.press('Enter');

    // 7. 键盘保存、校验、发布。
    const saveButton = page.getByRole('button', { name: '保存草稿' });
    await tabTo(page, saveButton, 500);
    await page.keyboard.press('Enter');
    await expect(page.getByText(/草稿已保存（版本 v\d+）/)).toBeVisible();
    const validateButton = page.getByRole('button', { name: '校验', exact: true });
    await tabTo(page, validateButton, 500);
    await page.keyboard.press('Enter');
    await expect(page.getByText('校验通过，可以保存或发布。')).toBeVisible({ timeout: 30_000 });

    const publishButton = page.getByRole('button', { name: '发布', exact: true });
    await tabTo(page, publishButton, 500);
    await page.keyboard.press('Enter');
    const modal = page.locator('.ant-modal').filter({ hasText: '确认发布' }).last();
    await expect(modal).toBeVisible();
    const confirmPublish = modal.getByRole('button', { name: '发布', exact: true });
    await tabTo(page, confirmPublish, 40);
    await page.keyboard.press('Enter');
    await expect(page.getByText(/已发布 v1/)).toBeVisible({ timeout: 30_000 });

    // 8. 键盘去运行历史，选择流程并运行（tab 到 Select 后键入筛选）。
    await page.goto(M06_ROUTES.runs);
    const workflowSelect = page.getByLabel('选择流程');
    await tabTo(page, workflowSelect, 200);
    await page.keyboard.press('Enter');
    await page.keyboard.type(workflowName.slice(0, 12));
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('workflow-release-panel')).toBeVisible();
    const runButton = page.getByRole('button', { name: '运行流程' });
    await tabTo(page, runButton, 300);
    await page.keyboard.press('Enter');
    const runModal = page.locator('.ant-modal').filter({ hasText: '运行流程' }).last();
    await expect(runModal).toBeVisible();
    const runInput = runModal.getByLabel('运行输入 JSON');
    await tabTo(page, runInput, 40);
    await typeInto(page, runInput, JSON.stringify({ topic: '键盘市场调研' }));
    const submitRun = runModal.getByRole('button', { name: '提交运行' });
    await tabTo(page, submitRun, 40);
    await page.keyboard.press('Enter');
    await page.waitForURL(/\/workflows\/runs\/[0-9a-f-]{36}$/);
    await expect(page.getByRole('heading', { name: '运行详情' })).toBeVisible();
    const runId = page.url().match(/\/workflows\/runs\/([0-9a-f-]{36})$/)?.[1];
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
      await tabTo(approverPage, detailButton, 300);
      await approverPage.keyboard.press('Enter');
      await expect(approverPage.getByTestId('workflow-approval-detail')).toBeVisible();
      const comment = approverPage.getByLabel('审批意见（拒绝时必填，最多 500 字）');
      await tabTo(approverPage, comment, 300);
      await typeInto(approverPage, comment, '键盘审批通过');
      const approve = approverPage.getByLabel('批准');
      await tabTo(approverPage, approve, 60);
      await approverPage.keyboard.press('Enter');
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
