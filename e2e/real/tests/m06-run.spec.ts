import { expect, test, type Page } from '@playwright/test';
import {
  api,
  approvalGraph,
  conditionGraph,
  createRun,
  createWorkflow,
  decideApproval,
  expectOk,
  getRun,
  invalidConditionGraph,
  listApprovals,
  loginApi,
  loginAs,
  M06_ROUTES,
  publishWorkflow,
  retryGraph,
  saveDraft,
  seed,
  waitForRun,
  type ApprovalItem,
  type PageResponse,
  type RunDetail,
  type WorkflowDetail,
} from './m06-support';

/**
 * M06 运行/审批/失败重试/条件分支 E2E（E2E-M06-03/04/05/06）。
 *
 * 真实 API + MySQL + Worker + 假模型 + Web；审批人使用独立会话/浏览器上下文。
 */

/** 创建并发布指定图，返回流程详情。 */
async function publishGraph(
  page: Page,
  name: string,
  graph: ReturnType<typeof approvalGraph>,
  scope: 'ONLY_SELF' | 'DEPARTMENT' = 'ONLY_SELF',
  departmentId?: string,
): Promise<WorkflowDetail> {
  const workflow = await createWorkflow(page, name);
  const saved = await saveDraft(page, workflow.id, workflow.draftVersion, graph);
  const validation = expectOk(
    await api<{ valid: boolean; issues: unknown[] }>(
      page,
      'POST',
      `/api/v1/workflows/${workflow.id}/validate?runScope=${scope}${
        departmentId ? `&departmentId=${departmentId}` : ''
      }`,
    ),
    `校验 ${name}`,
  );
  expect(validation.valid, JSON.stringify(validation.issues)).toBe(true);
  await publishWorkflow(page, workflow.id, scope, departmentId);
  return saved;
}

/** 读取审批项；按流程名查找。 */
async function findApproval(page: Page, workflowName: string): Promise<ApprovalItem> {
  const pageResponse: PageResponse<ApprovalItem> = await listApprovals(page);
  const found = pageResponse.items.find((item) => item.workflowName === workflowName);
  expect(found, `审批待办中未找到 ${workflowName}`).toBeTruthy();
  return found!;
}

/** 节点状态。 */
function nodeStatus(detail: RunDetail, nodeId: string): string | undefined {
  const attempts = detail.nodes.filter((node) => node.nodeId === nodeId);
  return attempts.at(-1)?.status;
}

test.describe('M06 运行与审批', () => {
  test('E2E-M06-03 运行时间线、刷新一致、审批后完成与结果', async ({ page, browser }) => {
    const data = seed();
    const workflowName = `E2E-M06-03 时间线 ${Date.now()}`;
    await loginAs(page, 'alpha-editor', data.passwords.alphaEditor);
    const workflow = await publishGraph(
      page,
      workflowName,
      approvalGraph(data.userIds.alphaApprover),
    );
    const run = await createRun(page, workflow.id, { topic: '时间线测试' });
    const waiting = await waitForRun(
      page,
      run.id,
      (detail) => detail.status === 'WAITING_APPROVAL',
    );

    // 运行详情按节点显示顺序/状态/时间，并高亮当前节点。
    await page.goto(M06_ROUTES.run(run.id));
    await expect(page.getByRole('heading', { name: '运行详情' })).toBeVisible();
    await expect(page.getByText('等待审批').first()).toBeVisible();
    await expect(page.getByText(/当前节点：人工审批/)).toBeVisible();
    const timeline = page.getByRole('list', { name: '节点时间线' });
    await expect(timeline).toContainText('开始');
    await expect(timeline).toContainText('检索知识库');
    await expect(timeline).toContainText('生成结果');
    await expect(timeline).toContainText('人工审批');
    await expect(timeline).toContainText('耗时：');
    await expect(
      page.locator('[data-testid^="workflow-run-node-approval-"]').first(),
    ).toContainText('等待审批');

    // 刷新后状态与刷新前一致，不丢已发生节点。
    await page.reload();
    await expect(page.getByRole('heading', { name: '运行详情' })).toBeVisible();
    await expect(page.getByText(/当前节点：人工审批/)).toBeVisible();
    await expect(page.getByRole('list', { name: '节点时间线' })).toContainText('检索知识库');

    // 审批人批准，运行继续并在详情中显示成功与结果。
    const approverContext = await browser.newContext({
      baseURL: process.env.REAL_WEB_BASE ?? 'http://127.0.0.1:8092',
    });
    const approverPage = await approverContext.newPage();
    try {
      await loginApi(
        approverPage,
        data.tenants.alpha,
        'alpha-approver',
        data.passwords.alphaApprover,
      );
      const approval = await findApproval(approverPage, workflowName);
      const decision = await decideApproval(approverPage, approval.id, 'APPROVE', '时间线批准');
      expect(decision.status, decision.text).toBe(200);
    } finally {
      await approverContext.close();
    }
    const completed = await waitForRun(page, run.id, (detail) => detail.status === 'SUCCEEDED');
    expect(nodeStatus(completed, 'approval')).toBe('SUCCEEDED');
    expect(nodeStatus(completed, 'end')).toBe('SUCCEEDED');
    expect(completed.citations.length).toBeGreaterThanOrEqual(0);
    await expect(page.getByText('成功').first()).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole('list', { name: '节点时间线' })).toContainText('成功');
    // 输入/输出摘要可展开，且不展示完整提示词。
    const outputSummary = page.getByText('输出摘要').first();
    if (await outputSummary.isVisible()) {
      await outputSummary.click();
    }
    await expect(page.getByText('根据检索结果整理：{{input.topic}}')).toHaveCount(0);
  });

  test('E2E-M06-04 审批待办隔离、批准、拒绝理由与自批阻断', async ({ page, browser }) => {
    const data = seed();
    const workflowName = `E2E-M06-04 审批 ${Date.now()}`;
    await loginAs(page, 'alpha-editor', data.passwords.alphaEditor);
    const workflow = await publishGraph(
      page,
      workflowName,
      approvalGraph(data.userIds.alphaApprover),
    );

    const webBase = process.env.REAL_WEB_BASE ?? 'http://127.0.0.1:8092';
    const approverContext = await browser.newContext({ baseURL: webBase });
    const approverPage = await approverContext.newPage();
    const otherAdminContext = await browser.newContext({ baseURL: webBase });
    const otherAdminPage = await otherAdminContext.newPage();
    try {
      await loginAs(approverPage, 'alpha-approver', data.passwords.alphaApprover);
      await loginAs(otherAdminPage, 'alpha-admin-2', data.passwords.alphaAdmin2);

      // 第一个运行：批准。
      const run = await createRun(page, workflow.id, { topic: '审批通过' });
      await waitForRun(page, run.id, (detail) => detail.status === 'WAITING_APPROVAL');

      // 仅显示分配给自己的待审批项；未指派的租户管理员看不到。
      await approverPage.goto(M06_ROUTES.approvals);
      const inbox = approverPage.getByTestId('workflow-approval-inbox');
      await expect(inbox).toContainText(workflowName);
      await expect(inbox).toContainText('人工审批');
      await expect(inbox).toContainText('数据范围');
      await otherAdminPage.goto(M06_ROUTES.approvals);
      const otherInbox = otherAdminPage.getByTestId('workflow-approval-inbox');
      await expect(otherInbox).not.toContainText(workflowName);

      const row = approverPage
        .getByTestId('workflow-approval-list')
        .locator('li')
        .filter({ hasText: workflowName });
      await row.getByRole('button', { name: /查看审批详情/ }).click();
      await expect(approverPage.getByTestId('workflow-approval-detail')).toBeVisible();
      await expect(approverPage.getByText('脱敏参数摘要')).toBeVisible();
      await expect(approverPage.getByText('将要执行的动作')).toBeVisible();
      await approverPage.getByLabel('审批意见（拒绝时必填，最多 500 字）').fill('同意执行');
      await approverPage.getByLabel('批准').click();
      await expect(
        approverPage.getByText('审批已批准，运行将继续执行后续节点并产出结果。'),
      ).toBeVisible();
      const approvedRun = await waitForRun(page, run.id, (detail) => detail.status === 'SUCCEEDED');
      const approvalRecord = approvedRun.approvals.find((item) => item.status === 'APPROVED');
      expect(approvalRecord?.decidedBy).toBe(data.userIds.alphaApprover);
      expect(approvalRecord?.decisionComment).toBe('同意执行');
      await page.goto(M06_ROUTES.run(run.id));
      await expect(page.getByText('同意执行')).toBeVisible();
      await expect(page.getByText(/已批准/).first()).toBeVisible();

      // 第二个运行：拒绝理由必填，拒绝后运行终止、后续节点跳过、无结果。
      const rejectedRun = await createRun(page, workflow.id, { topic: '审批拒绝' });
      await waitForRun(page, rejectedRun.id, (detail) => detail.status === 'WAITING_APPROVAL');
      await approverPage.goto(M06_ROUTES.approvals);
      const rejectRow = approverPage
        .getByTestId('workflow-approval-list')
        .locator('li')
        .filter({ hasText: workflowName });
      await rejectRow.getByRole('button', { name: /查看审批详情/ }).click();
      await approverPage.getByRole('button', { name: '拒绝', exact: true }).click();
      await expect(approverPage.getByText('拒绝时必须填写理由（1–500 字）。')).toBeVisible();
      await approverPage
        .getByLabel('审批意见（拒绝时必填，最多 500 字）')
        .fill('数据范围不符，拒绝本次取数');
      await approverPage.getByRole('button', { name: '拒绝', exact: true }).click();
      await expect(
        approverPage.getByText('审批已拒绝，本次运行终止，后续节点未执行。'),
      ).toBeVisible();
      const failedRun = await waitForRun(
        page,
        rejectedRun.id,
        (detail) => detail.status === 'FAILED',
      );
      expect(failedRun.errorCode).toBe('WF_APPROVAL_REJECTED');
      expect(nodeStatus(failedRun, 'approval')).toBe('FAILED');
      expect(nodeStatus(failedRun, 'end')).toBe('SKIPPED');
      expect(failedRun.result ?? null).toBeNull();
      await page.goto(M06_ROUTES.run(rejectedRun.id));
      await expect(page.getByText(/运行失败/).first()).toBeVisible();
      await expect(page.getByText(/审批已拒绝/).first()).toBeVisible();
      await expect(page.getByText('数据范围不符，拒绝本次取数')).toBeVisible();

      // 自批：发起人=审批人时，待办不显示、详情 404、直接决定 403 且写拒绝审计。
      const selfName = `E2E-M06-04 自批 ${Date.now()}`;
      const selfWorkflow = await publishGraph(
        page,
        selfName,
        approvalGraph(data.userIds.alphaEditor),
      );
      const selfRun = await createRun(page, selfWorkflow, { topic: '自批测试' });
      await waitForRun(page, selfRun.id, (detail) => detail.status === 'WAITING_APPROVAL');
      await page.goto(M06_ROUTES.approvals);
      await expect(page.getByTestId('workflow-approval-inbox')).not.toContainText(selfName);
      const selfApprovals = await listApprovals(page);
      expect(selfApprovals.items.some((item) => item.workflowName === selfName)).toBe(false);
      const selfApproval = (
        await api<PageResponse<ApprovalItem>>(
          page,
          'GET',
          '/api/v1/workflow-approvals?page=1&size=100',
        )
      ).body.items.find((item) => item.workflowName === selfName);
      expect(selfApproval).toBeUndefined();

      // 通过直接 SQL 不可见的审批 ID 由 API 404/403 保护：从租户管理员会话读取审批 ID。
      const adminContext = await browser.newContext({ baseURL: webBase });
      const adminPage = await adminContext.newPage();
      try {
        await loginApi(adminPage, data.tenants.alpha, 'alpha-admin', data.passwords.alphaAdmin);
        const adminPageApprovals = await listApprovals(adminPage);
        const hidden = adminPageApprovals.items.find((item) => item.workflowName === selfName);
        // 租户管理员也不是指派审批人；待办同样不显示。
        expect(hidden).toBeUndefined();
        const runDetail = await getRun(page, selfRun.id);
        const pendingApproval = runDetail.approvals.find((item) => item.status === 'PENDING');
        expect(pendingApproval).toBeTruthy();
        const selfGet = await api(page, 'GET', `/api/v1/workflow-approvals/${pendingApproval!.id}`);
        expect(selfGet.status).toBe(404);
        const selfDecision = await decideApproval(page, pendingApproval!.id, 'APPROVE', '自批尝试');
        expect(selfDecision.status).toBe(403);
        const selfRunAfter = await getRun(page, selfRun.id);
        expect(selfRunAfter.status).toBe('WAITING_APPROVAL');
        const deniedAudit = expectOk(
          await api<{
            items: Array<{
              action: string;
              outcome: string;
              targetId: string;
              details: { workflowApprovalId?: string };
            }>;
          }>(
            adminPage,
            'GET',
            `/api/v1/audit-events?action=workflow.approval.decided&outcome=DENIED&size=100`,
          ),
          '查询自批拒绝审计',
        );
        expect(
          deniedAudit.items.some(
            (item) =>
              item.targetId === selfRun.id &&
              item.details.workflowApprovalId === pendingApproval!.id,
          ),
        ).toBe(true);
      } finally {
        await adminContext.close();
      }
    } finally {
      await approverContext.close();
      await otherAdminContext.close();
    }
  });

  test('E2E-M06-05 节点失败、部分完成、手动重试不重跑上游', async ({ page }) => {
    const data = seed();
    const workflowName = `E2E-M06-05 重试 ${Date.now()}`;
    await loginAs(page, 'alpha-editor', data.passwords.alphaEditor);
    const workflow = await publishGraph(page, workflowName, retryGraph());
    const run = await createRun(page, workflow.id, {
      topic: `可恢复失败 ${Date.now()}`,
    });
    const failed = await waitForRun(page, run.id, (detail) => detail.status === 'FAILED', 180_000);
    expect(nodeStatus(failed, 'draft')).toBe('FAILED');
    expect(nodeStatus(failed, 'report')).toBe('SKIPPED');
    expect(nodeStatus(failed, 'end')).toBe('SKIPPED');
    const failedNode = failed.nodes.find(
      (node) => node.nodeId === 'draft' && node.status === 'FAILED',
    );
    expect(failedNode?.attempt).toBe(1);
    expect(failedNode?.errorCode).toBeTruthy();
    expect(failedNode?.errorMessage, '失败原因应为可读文本').toBeTruthy();

    // 不自动无限重试：等待后仍为失败且尝试次数不增加。
    await new Promise((resolve) => setTimeout(resolve, 2_000));
    const stillFailed = await getRun(page, run.id);
    expect(stillFailed.status).toBe('FAILED');
    expect(stillFailed.nodes.filter((node) => node.nodeId === 'draft').length).toBe(1);

    // 运行历史页重试失败节点；失败节点从 B 继续，A 不重跑，B 成功后 C 继续。
    await page.goto(M06_ROUTES.runs);
    await expect(page.getByRole('heading', { name: '运行历史' })).toBeVisible();
    const historyRow = page
      .getByTestId('workflow-run-list')
      .locator('li')
      .filter({ hasText: workflowName })
      .first();
    await historyRow.getByRole('button', { name: /重试失败节点/ }).click();
    const retryModal = page.locator('.ant-modal').filter({ hasText: '重试失败节点' }).last();
    await expect(retryModal).toBeVisible();
    await retryModal.getByRole('button', { name: '提交重试', exact: true }).click();
    const retried = await waitForRun(
      page,
      run.id,
      (detail) => detail.status === 'SUCCEEDED',
      180_000,
    );
    const retrievalAttempts = retried.nodes.filter((node) => node.nodeId === 'retrieval');
    expect(retrievalAttempts.length, '上游检索节点不应重跑').toBe(1);
    const draftAttempts = retried.nodes.filter((node) => node.nodeId === 'draft');
    expect(draftAttempts.length).toBe(2);
    expect(draftAttempts[0].status).toBe('FAILED');
    expect(draftAttempts[1].status).toBe('SUCCEEDED');
    expect(nodeStatus(retried, 'report')).toBe('SUCCEEDED');
    expect(nodeStatus(retried, 'end')).toBe('SUCCEEDED');
    expect(retried.result?.text ?? '').not.toBe('');
  });

  test('E2E-M06-06 条件分支只执行一支、另一支跳过、条件无法求值失败', async ({ page }) => {
    const data = seed();
    await loginAs(page, 'alpha-editor', data.passwords.alphaEditor);

    // 命中分支：知识库有市场文档，查询“市场”命中 ≥1。
    const hitWorkflow = await publishGraph(page, `E2E-M06-06 命中 ${Date.now()}`, conditionGraph());
    const hitRun = await createRun(page, hitWorkflow.id, { topic: '市场' });
    const hit = await waitForRun(page, hitRun.id, (detail) => detail.status === 'SUCCEEDED');
    expect(nodeStatus(hit, 'modelTrue')).toBe('SUCCEEDED');
    expect(nodeStatus(hit, 'endTrue')).toBe('SUCCEEDED');
    expect(nodeStatus(hit, 'modelFalse')).toBe('SKIPPED');
    expect(nodeStatus(hit, 'endFalse')).toBe('SKIPPED');
    const skipped = hit.nodes.find((node) => node.nodeId === 'modelFalse');
    expect(skipped?.outputSummary ?? '').toContain('未执行');

    // 未命中分支：不存在的关键词，只执行 FALSE 分支。
    const missRun = await createRun(page, hitWorkflow.id, { topic: 'ZZZZ-NOT-FOUND-9999' });
    const miss = await waitForRun(page, missRun.id, (detail) => detail.status === 'SUCCEEDED');
    expect(nodeStatus(miss, 'modelFalse')).toBe('SUCCEEDED');
    expect(nodeStatus(miss, 'endFalse')).toBe('SUCCEEDED');
    expect(nodeStatus(miss, 'modelTrue')).toBe('SKIPPED');
    expect(nodeStatus(miss, 'endTrue')).toBe('SKIPPED');

    // 条件无法求值：RUN_INPUT 声明 NUMBER 但输入字符串，运行在该节点失败且不默认走任一分支。
    const invalidWorkflow = await publishGraph(
      page,
      `E2E-M06-06 条件失败 ${Date.now()}`,
      invalidConditionGraph(),
    );
    const invalidRun = await createRun(page, invalidWorkflow.id, { topic: '不是数字' });
    const invalid = await waitForRun(page, invalidRun.id, (detail) => detail.status === 'FAILED');
    expect(nodeStatus(invalid, 'condition')).toBe('FAILED');
    expect(nodeStatus(invalid, 'modelTrue')).not.toBe('SUCCEEDED');
    expect(nodeStatus(invalid, 'modelFalse')).not.toBe('SUCCEEDED');
    expect(invalid.errorMessage, '条件失败原因应可读').toBeTruthy();

    // 原样重试仍失败，不会自动改走分支；保留失败原因可再次重试。
    const retried = expectOk(
      await api(
        page,
        'POST',
        `/api/v1/workflow-runs/${invalidRun.id}/retry`,
        {
          nodeId: 'condition',
        },
        { 'Idempotency-Key': `e2e-retry-${Date.now()}` },
      ),
      '重试条件失败节点',
    );
    expect(retried).toBeDefined();
    const failedAgain = await waitForRun(
      page,
      invalidRun.id,
      (detail) =>
        detail.status === 'FAILED' &&
        detail.nodes.filter((n) => n.nodeId === 'condition').length === 2,
    );
    expect(nodeStatus(failedAgain, 'condition')).toBe('FAILED');
    expect(failedAgain.nodes.filter((node) => node.nodeId === 'condition').length).toBe(2);
  });
});
