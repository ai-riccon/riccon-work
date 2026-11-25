import { expect, test, type Page } from '@playwright/test';
import { readSeed } from './real-session';
import {
  api,
  approvalGraph,
  createWorkflow,
  getWorkflow,
  loginApi,
  loginAs,
  M06_ROUTES,
  saveDraft,
  seed,
  selectOption,
  simpleGraph,
  validateWorkflow,
  waitForRun,
} from './m06-support';

/**
 * M06 编排 Studio 创建/编辑/发布/模板/历史 E2E（E2E-M06-01/02/07/08）。
 *
 * 全部用例运行在真实 API + MySQL + Worker + Web 生产构建上；浏览器只走产品路由与真实接口。
 */

/** 打开编辑器并等待加载完成。 */
async function openEditor(page: Page, workflowId: string): Promise<void> {
  await page.goto(M06_ROUTES.editor(workflowId));
  await expect(page.getByTestId('workflow-editor-page')).toBeVisible();
  await expect(page.getByRole('heading', { level: 1, name: '编排 Studio' })).toHaveCount(0);
  await expect(page.getByText('流程信息')).toBeVisible();
}

/** 在编辑器中选择节点。 */
async function selectNode(page: Page, name: string): Promise<void> {
  await page.getByRole('button', { name, exact: true }).click();
  await expect(page.locator('#workflow-node-config').getByLabel('节点名称')).toHaveValue(name);
}

/** 保存节点配置。 */
async function applyNodeConfig(page: Page): Promise<void> {
  await page.getByRole('button', { name: '保存节点配置' }).click();
}

/** 添加节点；选择类型后点击添加。 */
async function addNode(page: Page, typeLabel: string): Promise<void> {
  await selectOption(page, page.getByLabel('节点类型'), typeLabel);
  await page.getByRole('button', { name: '添加节点' }).click();
}

/** 添加连线。 */
async function addEdge(page: Page, source: RegExp, target: RegExp): Promise<void> {
  await selectOption(page, page.getByLabel('连线源节点'), source);
  await selectOption(page, page.getByLabel('连线目标节点'), target);
  await page.getByRole('button', { name: '添加连线' }).click();
}

/** 确认发布弹窗并断言成功。 */
async function publishFromEditor(page: Page, scopeLabel = '仅自己'): Promise<void> {
  await page.getByLabel('运行范围').click();
  await page.locator('.ant-segmented-item').filter({ hasText: scopeLabel }).click();
  await page.getByRole('button', { name: '发布', exact: true }).click();
  const modal = page.locator('.ant-modal').filter({ hasText: '确认发布' }).last();
  await expect(modal).toBeVisible();
  await modal.getByRole('button', { name: '发布', exact: true }).click();
  await expect(page.getByText(/发布成功：v\d+/)).toBeVisible({ timeout: 30_000 });
}

test.describe('M06 Studio 创建与发布', () => {
  test('E2E-M06-01 新建/校验失败/修正/保存/重开一致与并发 409 另存副本', async ({
    page,
    browser,
  }) => {
    const data = seed();
    const name = `E2E-M06-01 编辑流程 ${Date.now()}`;
    await loginAs(page, 'alpha-editor', data.passwords.alphaEditor);

    await page.goto(M06_ROUTES.list);
    await page.getByRole('button', { name: '新建空白流程' }).first().click();
    const createModal = page.locator('.ant-modal').filter({ hasText: '新建空白流程' }).last();
    await createModal.getByPlaceholder('例如：销售部市场调研').fill(name);
    await createModal.getByRole('button', { name: '创建并打开' }).click();
    await page.waitForURL(/\/workflows\/[0-9a-f-]{36}\/edit$/);
    const workflowId = page.url().match(/\/workflows\/([0-9a-f-]{36})\/edit$/)?.[1];
    expect(workflowId, '创建后应进入编辑器').toBeTruthy();

    // 先只添加开始节点：校验必须逐项阻断，发布不产生版本。
    await addNode(page, '开始');
    await page.getByRole('button', { name: '校验', exact: true }).click();
    const issues = page.getByTestId('workflow-issue-list');
    await expect(issues).toBeVisible();
    await expect(page.getByText(/流程还有 \d+ 个问题/)).toBeVisible();
    const detailBefore = await getWorkflow(page, workflowId!);
    expect(detailBefore.publishedVersionNo ?? null).toBeNull();

    // 修正：补齐检索、模型、审批、结束节点并配置参数。
    await addNode(page, '知识库检索');
    await addNode(page, '模型');
    await addNode(page, '人工审批');
    await addNode(page, '结束');

    await selectNode(page, '知识库检索');
    await page.getByLabel('查询模板').fill('{{input.topic}}');
    await page.getByLabel('检索命中数（topK）').fill('5');
    await applyNodeConfig(page);

    await selectNode(page, '模型生成');
    await page.getByLabel('提示词模板').fill('根据检索结果整理：{{input.topic}}');
    await selectOption(page, page.getByLabel('输出形态'), '文本');
    await applyNodeConfig(page);

    await selectNode(page, '人工审批');
    await selectOption(page, page.getByLabel('审批人类型'), '按具体用户');
    const assignee = page
      .locator('.ant-form-item')
      .filter({ hasText: '审批人用户 ID' })
      .locator('.ant-select')
      .first();
    await assignee.click();
    await page.keyboard.type(data.userIds.alphaApprover);
    await page.keyboard.press('Enter');
    await selectOption(page, page.getByLabel('审批解锁动作'), '继续后续节点');
    await applyNodeConfig(page);

    await selectNode(page, '结束');
    await selectOption(page, page.getByLabel('结果来源节点'), /模型生成/);
    await page.getByLabel('结果标题').fill('E2E-M06-01 结果');
    await applyNodeConfig(page);

    await addEdge(page, /开始/, /知识库检索/);
    await addEdge(page, /知识库检索/, /模型生成/);
    await addEdge(page, /模型生成/, /人工审批/);
    await addEdge(page, /人工审批/, /结束/);

    await page.getByRole('button', { name: '保存草稿' }).click();
    await expect(page.getByText(/草稿已保存（版本 v\d+）/)).toBeVisible();
    await page.getByRole('button', { name: '校验', exact: true }).click();
    await expect(page.getByText('校验通过，可以保存或发布。')).toBeVisible();

    // 重新打开：节点、连线与参数一致。
    await page.reload();
    await expect(page.getByText('流程信息')).toBeVisible();
    const nodeList = page.getByRole('list', { name: '流程节点列表' });
    for (const nodeName of ['开始', '知识库检索', '模型生成', '人工审批', '结束']) {
      await expect(nodeList.getByRole('button', { name: nodeName, exact: true })).toBeVisible();
    }
    const edgeList = page.getByRole('list', { name: '流程连线列表' });
    await expect(edgeList).toContainText('开始 → 知识库检索');
    await expect(edgeList).toContainText('人工审批 → 结束');
    await selectNode(page, '模型生成');
    await expect(page.getByLabel('提示词模板')).toHaveValue('根据检索结果整理：{{input.topic}}');
    await selectNode(page, '人工审批');
    await expect(page.locator('.ant-form-item').filter({ hasText: '审批人用户 ID' })).toContainText(
      data.userIds.alphaApprover,
    );

    // 未发布草稿不出现在“可运行”列表。
    await page.goto(M06_ROUTES.list);
    await page.getByText('可运行', { exact: true }).click();
    const list = page.getByTestId('workflow-list');
    if (await list.isVisible()) {
      await expect(list).not.toContainText(name);
    } else {
      await expect(page.getByText(/还没有流程|当前没有可运行的流程/)).toBeVisible();
    }

    // 并发修改：同账号第二个会话保存后，第一个会话保存收到 409，可另存副本。
    const secondContext = await browser.newContext({
      baseURL: process.env.REAL_WEB_BASE ?? 'http://127.0.0.1:8092',
    });
    const secondPage = await secondContext.newPage();
    try {
      const webBase = process.env.REAL_WEB_BASE ?? 'http://127.0.0.1:8092';
      await secondPage.goto(`${webBase}/login`);
      await secondPage.getByPlaceholder('例如 acme').fill(data.tenants.alpha);
      await secondPage.getByPlaceholder('请输入账号').fill('alpha-editor');
      await secondPage.getByPlaceholder('请输入密码').fill(data.passwords.alphaEditor);
      await secondPage.getByRole('button', { name: /登\s*录/ }).click();
      await secondPage.waitForURL(/\/chat$/);
      // 第一会话先重新打开编辑器，持有当前草稿版本；第二会话随后保存，形成版本冲突。
      await page.goto(M06_ROUTES.editor(workflowId!));
      await expect(page.getByText('流程信息')).toBeVisible();
      await page.getByLabel('流程名称').fill(`${name}（本地修改）`);

      await openEditor(secondPage, workflowId!);
      await secondPage.getByLabel('流程名称').fill(`${name}（第二会话）`);
      await secondPage.getByRole('button', { name: '保存草稿' }).click();
      await expect(secondPage.getByText(/草稿已保存（版本 v\d+）/)).toBeVisible();


      await page.getByRole('button', { name: '保存草稿' }).click();
      const conflict = page
        .locator('.ant-modal')
        .filter({ hasText: '草稿已被其他会话修改' })
        .last();
      await expect(conflict).toBeVisible();
      await expect(conflict).toContainText('另存副本');
      await expect(conflict).toContainText('放弃本地修改并加载最新草稿');
      await conflict.getByRole('button', { name: '另存副本' }).click();
      await page.waitForURL(new RegExp(`/workflows/(?!${workflowId})[0-9a-f-]{36}/edit$`), {
        timeout: 30_000,
      });
      await expect(page.getByLabel('流程名称')).toHaveValue(/副本$/);
      const copyId = page.url().match(/\/workflows\/([0-9a-f-]{36})\/edit$/)?.[1];
      expect(copyId).toBeTruthy();
      const copy = await getWorkflow(page, copyId!);
      expect(copy.name.endsWith(' 副本')).toBe(true);
      expect(copy.id).not.toBe(workflowId);
    } finally {
      await secondContext.close();
    }
  });

  test('E2E-M06-02 发布版本不可变、运行绑定版本、范围变更与旧版本运行', async ({ page }) => {
    const data = seed();
    await loginAs(page, 'alpha-editor', data.passwords.alphaEditor);

    const name = `E2E-M06-02 版本流程 ${Date.now()}`;
    const workflow = await createWorkflow(page, name);
    await saveDraft(page, workflow.id, workflow.draftVersion, simpleGraph());
    const validation = await validateWorkflow(page, workflow.id);
    expect(validation.valid, JSON.stringify(validation.issues)).toBe(true);

    await page.goto(M06_ROUTES.runs);
    await page.getByLabel('选择流程').click();
    await page
      .locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)')
      .last()
      .locator('.ant-select-item-option')
      .filter({ hasText: name })
      .click();
    await expect(page.getByTestId('workflow-release-panel')).toBeVisible();
    await page.getByRole('button', { name: '发布版本' }).click();
    const publishModal = page.locator('.ant-modal').filter({ hasText: '确认发布' }).last();
    await expect(publishModal).toBeVisible();
    await publishModal.getByRole('button', { name: '确认发布版本' }).click();
    await expect(page.getByText(/已发布 v1/)).toBeVisible();
    await expect(page.getByTestId('workflow-version-list')).toContainText('v1');
    await expect(page.getByTestId('workflow-version-list')).toContainText('仅自己');

    // 修改草稿图但不发布：运行仍绑定 v1，且草稿显示未发布修改。
    const modifiedGraph = simpleGraph();
    modifiedGraph.nodes = modifiedGraph.nodes.map((node) =>
      node.id === 'model'
        ? {
            ...node,
            config: { ...node.config, promptTemplate: '修改后的草稿提示词：{{input.topic}}' },
          }
        : node,
    );
    await saveDraft(page, workflow.id, workflow.draftVersion + 1, modifiedGraph);
    const detailV1 = await getWorkflow(page, workflow.id);
    expect(detailV1.publishedVersionNo).toBe(1);
    expect(detailV1.draftVersion).toBeGreaterThanOrEqual(workflow.draftVersion + 1);
    expect(detailV1.hasUnpublishedChanges).toBe(true);
    expect(
      detailV1.draftGraph?.nodes.find((node) => node.id === 'model')?.config.promptTemplate,
    ).toBe('修改后的草稿提示词：{{input.topic}}');

    await page.reload();
    await page.getByLabel('选择流程').click();
    await page
      .locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)')
      .last()
      .locator('.ant-select-item-option')
      .filter({ hasText: name })
      .click();
    await expect(page.getByTestId('workflow-release-panel')).toBeVisible();
    await page.getByRole('button', { name: '运行流程' }).click();
    const runModal = page.locator('.ant-modal').filter({ hasText: '运行流程' }).last();
    await runModal.getByLabel('运行输入 JSON').fill(JSON.stringify({ topic: '市场调研' }));
    await runModal.getByRole('button', { name: '提交运行' }).click();
    await page.waitForURL(/\/workflows\/runs\/[0-9a-f-]{36}$/);
    const runV1 = page.url().match(/\/workflows\/runs\/([0-9a-f-]{36})$/)?.[1];
    expect(runV1).toBeTruthy();
    await expect(page.getByText('版本 v1')).toBeVisible();
    const runOne = await (
      await api<{ versionNo: number; status: string }>(
        page,
        'GET',
        `/api/v1/workflow-runs/${runV1}`,
      )
    ).body;
    expect(runOne.versionNo).toBe(1);

    // 发布第二版：新运行默认 v2，旧运行仍 v1。
    await page.goto(M06_ROUTES.runs);
    await page.getByLabel('选择流程').click();
    await page
      .locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)')
      .last()
      .locator('.ant-select-item-option')
      .filter({ hasText: name })
      .click();
    await expect(page.getByTestId('workflow-release-panel')).toBeVisible();
    await page.getByRole('button', { name: '发布版本' }).click();
    const publishSecond = page.locator('.ant-modal').filter({ hasText: '确认发布' }).last();
    await publishSecond.getByRole('button', { name: '确认发布版本' }).click();
    await expect(page.getByText(/已发布 v2/)).toBeVisible();
    await expect(page.getByTestId('workflow-version-list')).toContainText('v2');

    await page.getByRole('button', { name: '运行流程' }).click();
    const runModal2 = page.locator('.ant-modal').filter({ hasText: '运行流程' }).last();
    await runModal2.getByLabel('运行输入 JSON').fill(JSON.stringify({ topic: '第二版运行' }));
    await runModal2.getByRole('button', { name: '提交运行' }).click();
    await page.waitForURL(/\/workflows\/runs\/[0-9a-f-]{36}$/);
    const runV2 = page.url().match(/\/workflows\/runs\/([0-9a-f-]{36})$/)?.[1];
    await expect(page.getByText('版本 v2')).toBeVisible();
    expect(runV2).not.toBe(runV1);

    await page.goto(M06_ROUTES.run(runV1!));
    await expect(page.getByText('版本 v1')).toBeVisible();

    // 修改发布范围为“本部门”并发布 v3；旧运行仍按原版本可查。
    await page.goto(M06_ROUTES.runs);
    await page.getByLabel('选择流程').click();
    await page
      .locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)')
      .last()
      .locator('.ant-select-item-option')
      .filter({ hasText: name })
      .click();
    await expect(page.getByTestId('workflow-release-panel')).toBeVisible();
    await page.getByLabel('发布运行范围').click();
    await page
      .locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)')
      .last()
      .locator('.ant-select-item-option')
      .filter({ hasText: '本部门' })
      .click();
    await page.getByLabel('发布部门').click();
    await page
      .locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)')
      .last()
      .locator('.ant-select-item-option')
      .filter({ hasText: 'Alpha 流程编排部' })
      .click();
    await page.getByRole('button', { name: '发布版本' }).click();
    const publishThird = page.locator('.ant-modal').filter({ hasText: '确认发布' }).last();
    await publishThird.getByRole('button', { name: '确认发布版本' }).click();
    await expect(page.getByText(/已发布 v3/)).toBeVisible();
    await expect(page.getByTestId('workflow-version-list')).toContainText('本部门');
    await page.goto(M06_ROUTES.run(runV2!));
    await expect(page.getByText('版本 v2')).toBeVisible();

    // 旧版本仍可选择运行。
    await page.goto(M06_ROUTES.runs);
    await page.getByLabel('选择流程').click();
    await page
      .locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)')
      .last()
      .locator('.ant-select-item-option')
      .filter({ hasText: name })
      .click();
    await expect(page.getByTestId('workflow-version-list')).toContainText('v1');
    const v1Row = page
      .locator('.workflow-history-version')
      .filter({ hasText: 'v1' })
      .filter({ hasNotText: 'v2' })
      .first();
    await v1Row.getByRole('button', { name: '用此版本运行' }).click();
    const oldRunModal = page.locator('.ant-modal').filter({ hasText: '运行流程' }).last();
    await oldRunModal.getByLabel('运行输入 JSON').fill(JSON.stringify({ topic: '旧版本运行' }));
    await oldRunModal.getByRole('button', { name: '提交运行' }).click();
    await page.waitForURL(/\/workflows\/runs\/[0-9a-f-]{36}$/);
    await expect(page.getByText('版本 v1')).toBeVisible();
  });

  test('E2E-M06-07 模板复制、配置工具、发布运行与模板不被修改', async ({ page, browser }) => {
    const data = seed();
    const uniqueName = `E2E-M06-07 模板副本 ${Date.now()}`;
    await loginAs(page, 'alpha-editor', data.passwords.alphaEditor);
    await page.goto(M06_ROUTES.templates);
    await expect(page.getByTestId('workflow-template-list')).toContainText('市场调研报告');
    await page.getByRole('button', { name: '复制模板：市场调研报告' }).click();
    await page.waitForURL(/\/workflows\/[0-9a-f-]{36}\/edit$/, { timeout: 30_000 });
    const workflowId = page.url().match(/\/workflows\/([0-9a-f-]{36})\/edit$/)?.[1];
    expect(workflowId).toBeTruthy();
    await expect(page.getByLabel('流程名称')).toHaveValue('市场调研报告');

    // 模板节点、连线可见且可改；工具为空时必须先选择工具才能保存。
    await expect(page.getByRole('list', { name: '流程节点列表' })).toContainText('获取市场数据');
    await expect(page.getByRole('list', { name: '流程连线列表' })).toContainText('检索知识库');
    await page.getByRole('button', { name: '保存草稿' }).click();
    await expect(page.getByTestId('workflow-issue-list')).toBeVisible();
    await expect(page.getByTestId('workflow-issue-list')).toContainText('工具');
    await page.getByRole('button', { name: '获取市场数据', exact: true }).click();
    await selectOption(page, page.getByLabel('授权工具'), /market_data/);
    await applyNodeConfig(page);
    await page.getByLabel('流程名称').fill(uniqueName);
    await page.getByRole('button', { name: '保存草稿' }).click();
    await expect(page.getByText(/草稿已保存（版本 v\d+）/)).toBeVisible();

    // 模板本身未被修改：名称、说明与场景仍相同。
    await page.goto(M06_ROUTES.templates);
    const templateList = page.getByTestId('workflow-template-list');
    await expect(templateList).toContainText('市场调研报告');
    await expect(templateList).toContainText('市场调研');
    const templateApi = await api<{
      items: Array<{ id: string; name: string; updatedAt: string }>;
    }>(page, 'GET', '/api/v1/workflow-templates?page=1&size=100');
    const builtIn = templateApi.body.items.find(
      (item) => item.id === '01990000-0000-7000-8000-000000000601',
    );
    expect(builtIn?.name).toBe('市场调研报告');

    // 校验、发布（仅自己）、运行；审批人 alpha-admin 审批；结果与引用可见。
    await page.goto(M06_ROUTES.editor(workflowId!));
    await page.getByRole('button', { name: '校验', exact: true }).click();
    await expect(page.getByText('校验通过，可以保存或发布。')).toBeVisible();
    await publishFromEditor(page, '仅自己');

    await page.goto(M06_ROUTES.runs);
    await page.getByLabel('选择流程').click();
    await page
      .locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)')
      .last()
      .locator('.ant-select-item-option')
      .filter({ hasText: uniqueName })
      .first()
      .click();
    await expect(page.getByTestId('workflow-release-panel')).toBeVisible();
    await page.getByRole('button', { name: '运行流程' }).click();
    const runModal = page.locator('.ant-modal').filter({ hasText: '运行流程' }).last();
    await runModal
      .getByLabel('运行输入 JSON')
      .fill(JSON.stringify({ topic: '2026 中国企业协同办公市场' }));
    await runModal.getByRole('button', { name: '提交运行' }).click();
    await page.waitForURL(/\/workflows\/runs\/[0-9a-f-]{36}$/);
    const runId = page.url().match(/\/workflows\/runs\/([0-9a-f-]{36})$/)?.[1];
    expect(runId).toBeTruthy();
    await waitForRun(page, runId!, (detail) => detail.status === 'WAITING_APPROVAL');

    // 审批人登录并批准（真实待办 + 角色 TENANT_ADMIN）。
    const approverContext = await browser.newContext({
      baseURL: process.env.REAL_WEB_BASE ?? 'http://127.0.0.1:8092',
    });
    const approverPage = await approverContext.newPage();
    try {
      await loginAs(approverPage, 'alpha-admin', data.passwords.alphaAdmin);
      await approverPage.goto(M06_ROUTES.approvals);
      const inbox = approverPage.getByTestId('workflow-approval-inbox');
      await expect(inbox).toContainText(uniqueName);
      await approverPage
        .getByRole('button', { name: `查看审批详情：${uniqueName} · 审批取数工具` })
        .click();
      await expect(approverPage.getByTestId('workflow-approval-detail')).toBeVisible();
      await approverPage.getByLabel('批准').click();
      await expect(approverPage.getByText(/审批已批准/)).toBeVisible({ timeout: 30_000 });
    } finally {
      await approverContext.close();
    }

    await page.goto(M06_ROUTES.run(runId!));
    await expect(page.getByRole('heading', { name: '运行详情' })).toBeVisible();
    await expect(page.getByText(/成功|已完成/).first()).toBeVisible({ timeout: 120_000 });
    await expect(page.getByTestId('workflow-run-result-text')).toBeVisible();
    const run = await (
      await api<{
        status: string;
        citations: unknown[];
        nodes: Array<{ nodeId: string; status: string }>;
      }>(page, 'GET', `/api/v1/workflow-runs/${runId}`)
    ).body;
    expect(run.status).toBe('SUCCEEDED');
    expect(run.citations.length).toBeGreaterThan(0);
    const toolNode = run.nodes.find((node) => node.nodeId === 'tool');
    expect(toolNode?.status).toBe('SUCCEEDED');
  });

  test('E2E-M06-08 运行历史筛选、详情、保留期与跨租户/平台 404', async ({ page, browser }) => {
    const data = seed();
    await loginAs(page, 'alpha-editor', data.passwords.alphaEditor);
    const name = `E2E-M06-08 历史流程 ${Date.now()}`;
    const workflow = await createWorkflow(page, name);
    await saveDraft(page, workflow.id, workflow.draftVersion, simpleGraph());
    await api(
      page,
      'POST',
      `/api/v1/workflows/${workflow.id}/publish`,
      { runScope: 'ONLY_SELF' },
      {
        'Idempotency-Key': `e2e-publish-${Date.now()}`,
      },
    );
    const run = await (
      await api<{ id: string }>(
        page,
        'POST',
        `/api/v1/workflows/${workflow.id}/runs`,
        { inputs: { topic: '历史查询测试' } },
        { 'Idempotency-Key': `e2e-run-${Date.now()}` },
      )
    ).body;

    await page.goto(M06_ROUTES.runs);
    await expect(page.getByRole('heading', { name: '运行历史' })).toBeVisible();
    await page.getByLabel('流程筛选').click();
    await page
      .locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)')
      .last()
      .locator('.ant-select-item-option')
      .filter({ hasText: name })
      .click();
    await page.getByRole('button', { name: '查询运行历史' }).click();
    const historyList = page.getByTestId('workflow-run-list');
    await expect(historyList).toContainText(name);
    await expect(historyList).toContainText('版本 v1');

    // 空态与清除筛选。
    await page.getByLabel('状态筛选').click();
    await page
      .locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)')
      .last()
      .locator('.ant-select-item-option')
      .filter({ hasText: '已取消' })
      .click();
    await page.getByRole('button', { name: '查询运行历史' }).click();
    await expect(page.getByText(/当前筛选条件下没有运行/)).toBeVisible();
    await page.getByRole('button', { name: '清除筛选' }).click();

    // 详情：节点时间线、结果、保留到期；不显示完整提示词。
    await page.goto(M06_ROUTES.run(run.id));
    await expect(page.getByRole('heading', { name: '节点时间线' })).toBeVisible();
    await expect(page.getByText('保留到期')).toBeVisible();
    await expect(page.getByRole('list', { name: '节点时间线' })).toContainText('检索知识库');
    await expect(page.getByText('整理主题：{{input.topic}}')).toHaveCount(0);

    // 同部门但有 read 权限的普通员工直接访问他人运行：404，无内容泄露。
    const memberContext = await browser.newContext({
      baseURL: process.env.REAL_WEB_BASE ?? 'http://127.0.0.1:8092',
    });
    const memberPage = await memberContext.newPage();
    try {
      await loginAs(memberPage, 'alpha-member', data.passwords.alphaMember);
      await memberPage.goto(M06_ROUTES.run(run.id));
      await expect(memberPage.getByText('运行不存在或你无权查看')).toBeVisible();
      await expect(memberPage.getByText(name)).toHaveCount(0);
      const denied = await api(memberPage, 'GET', `/api/v1/workflow-runs/${run.id}`);
      expect(denied.status).toBe(404);

      await memberPage.goto(M06_ROUTES.runs);
      await expect(memberPage.getByText(name)).toHaveCount(0);
    } finally {
      await memberContext.close();
    }

    // 跨租户 404：Beta 员工持有运行 ID 也不能读取。
    const betaContext = await browser.newContext({
      baseURL: process.env.REAL_WEB_BASE ?? 'http://127.0.0.1:8092',
    });
    const betaPage = await betaContext.newPage();
    try {
      await loginAs(betaPage, 'beta-member', data.passwords.betaMember, data.tenants.beta);
      await betaPage.goto(M06_ROUTES.run(run.id));
      await expect(betaPage.getByText('运行不存在或你无权查看')).toBeVisible();
      const crossTenant = await api(betaPage, 'GET', `/api/v1/workflow-runs/${run.id}`);
      expect(crossTenant.status).toBe(404);
      const crossWorkflow = await api(betaPage, 'GET', `/api/v1/workflows/${workflow.id}`);
      expect(crossWorkflow.status).toBe(404);
    } finally {
      await betaContext.close();
    }

    // 平台管理员访问租户流程/运行统一 404。
    const platformContext = await browser.newContext({
      baseURL: process.env.REAL_WEB_BASE ?? 'http://127.0.0.1:8092',
    });
    const platformPage = await platformContext.newPage();
    try {
      await loginApi(platformPage, 'platform', 'platform-admin', data.passwords.platformAdmin);
      const platformRun = await api(platformPage, 'GET', `/api/v1/workflow-runs/${run.id}`);
      expect(platformRun.status).toBe(404);
      const platformWorkflow = await api(platformPage, 'GET', `/api/v1/workflows/${workflow.id}`);
      expect(platformWorkflow.status).toBe(404);
      const platformList = await api(platformPage, 'GET', '/api/v1/workflows');
      expect(platformList.status).toBe(404);
    } finally {
      await platformContext.close();
    }
  });
});
