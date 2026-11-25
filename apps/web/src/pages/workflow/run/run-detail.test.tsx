// @vitest-environment jsdom
import { fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Citation } from '@/services/api/types';
import { withProviders } from '@/test/render';
import type { WorkflowRunApproval, WorkflowRunDetail, WorkflowNodeRun } from './run-api';
import { RESULT_PREVIEW_LIMIT, RETRY_T12B_NOTICE, SECURITY_NOTICE } from './format';
import { WorkflowRunDetailView } from './run-detail';

/**
 * 运行详情展示组件测试。
 *
 * 覆盖节点时间线（全部尝试、当前节点、输入/输出摘要）、引用遮蔽、审批记录与数据范围、
 * 结果三态、失败原因/重试入口与空态；真实浏览器 375 px 无横向滚动、axe 与键盘旅程
 * 由 T16a 的 E2E-M06-08/@a11y 覆盖。
 */

/** 构造节点尝试。 */
function makeNode(overrides: Partial<WorkflowNodeRun> = {}): WorkflowNodeRun {
  return {
    id: 'node-run-1',
    nodeId: 'model-1',
    nodeType: 'MODEL',
    name: '生成报告',
    status: 'SUCCEEDED',
    attempt: 1,
    startedAt: '2026-11-25T09:00:00+08:00',
    finishedAt: '2026-11-25T09:00:02+08:00',
    durationMs: 2000,
    inputSummary: '主题：新能源市场',
    outputSummary: '已生成 3 段报告摘要',
    ...overrides,
  };
}

/** 构造引用。 */
function makeCitation(overrides: Partial<Citation> = {}): Citation {
  return {
    ordinal: 1,
    sourceType: 'KNOWLEDGE',
    knowledgeDocumentId: 'doc-1',
    chunkId: 'chunk-1',
    locatorType: 'PAGE',
    pageNo: 3,
    sourceStatus: 'AVAILABLE',
    title: '新能源行业报告.pdf',
    snippet: '2026 年新能源市场规模持续增长。',
    ...overrides,
  };
}

/** 构造审批记录。 */
function makeApproval(overrides: Partial<WorkflowRunApproval> = {}): WorkflowRunApproval {
  return {
    id: 'approval-1',
    workflowRunId: 'run-1',
    workflowId: 'workflow-1',
    workflowName: '市场调研流程',
    nodeId: 'approval-node',
    nodeName: '人工审批',
    status: 'APPROVED',
    assigneeType: 'USER',
    assigneeUserIds: ['approver-1'],
    actionKind: 'MCP_TOOL',
    dataScopeSummary: {
      runScope: 'DEPARTMENT',
      departmentId: 'dept-0001-aaaa',
      documentReferenceCount: 2,
    },
    targetToolName: 'market_data',
    planSha256: 'a'.repeat(64),
    requestedBy: 'user-0001',
    decidedBy: 'approver-1',
    decidedAt: '2026-11-25T09:05:00+08:00',
    decisionComment: '参数符合范围，同意取数。',
    createdAt: '2026-11-25T09:04:00+08:00',
    ...overrides,
  };
}

/** 构造运行详情。 */
function makeDetail(overrides: Partial<WorkflowRunDetail> = {}): WorkflowRunDetail {
  return {
    id: 'run-1',
    workflowId: 'workflow-1',
    workflowName: '市场调研流程',
    workflowVersionId: 'version-1',
    versionNo: 2,
    requesterUserId: 'user-0001',
    status: 'RUNNING',
    currentNodeId: 'model-1',
    approvalStatus: 'APPROVED',
    resultStatus: 'NONE',
    createdAt: '2026-11-25T08:59:00+08:00',
    startedAt: '2026-11-25T09:00:00+08:00',
    updatedAt: '2026-11-25T09:06:00+08:00',
    pendingApprovalCount: 0,
    approvedApprovalCount: 1,
    rejectedApprovalCount: 0,
    retentionExpiresAt: '2027-05-24T09:00:00+08:00',
    nodes: [],
    approvals: [],
    citations: [],
    result: null,
    ...overrides,
  };
}

/** 渲染展示组件。 */
function renderView(detail: WorkflowRunDetail, onRetry?: () => void) {
  return render(withProviders(<WorkflowRunDetailView detail={detail} onRetry={onRetry} />));
}

/**
 * 断言按钮满足 axe `label-content-name-mismatch`：可见文本必须包含在可访问名称中。
 *
 * 本页采用“移除 aria-label、以可见文本作为名称”的方案，因此可见文本与 textContent 一致。
 *
 * @param button 按钮元素
 */
function expectLabelInName(button: HTMLElement): void {
  const visibleText = (button.textContent ?? '').replace(/\s+/g, ' ').trim();
  const accessibleName = (button.getAttribute('aria-label') ?? visibleText)
    .replace(/\s+/g, ' ')
    .trim();
  expect(visibleText.length).toBeGreaterThan(0);
  expect(accessibleName).toContain(visibleText);
}

describe('WorkflowRunDetailView', () => {
  beforeEach(() => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1024 });
  });

  it('展示运行摘要、全部节点尝试、当前节点与输入输出摘要', () => {
    const failedAttempt = makeNode({
      id: 'node-run-1',
      attempt: 1,
      status: 'FAILED',
      errorCode: 'WF_NODE_TIMEOUT',
      errorMessage: '模型服务在 300 秒内未返回。',
    });
    const retryAttempt = makeNode({
      id: 'node-run-2',
      attempt: 2,
      status: 'RUNNING',
      finishedAt: null,
      durationMs: null,
    });
    renderView(
      makeDetail({
        nodes: [
          makeNode({ id: 'start-1', nodeId: 'start', nodeType: 'START', name: '开始' }),
          failedAttempt,
          retryAttempt,
        ],
      }),
    );

    expect(screen.getByRole('heading', { name: '运行详情', level: 1 })).toBeVisible();
    expect(screen.getByText('市场调研流程')).toBeVisible();
    expect(screen.getByText('版本 v2')).toBeVisible();
    expect(screen.getByText('当前节点：生成报告')).toBeVisible();
    expect(screen.getByText('已批准 · 待审批 0 · 已批准 1 · 已拒绝 0')).toBeVisible();
    expect(screen.getByText('2027-05-24 09:00:00 +08:00')).toBeVisible();

    const firstAttempt = screen.getByTestId('workflow-run-node-model-1-1');
    expect(within(firstAttempt).getByText('失败')).toBeVisible();
    expect(within(firstAttempt).getByText('第 1 次尝试')).toBeVisible();
    expect(within(firstAttempt).getByText('输入摘要')).toBeVisible();
    expect(within(firstAttempt).getByText('主题：新能源市场')).toBeInTheDocument();
    expect(within(firstAttempt).getByText('输出摘要')).toBeVisible();
    expect(within(firstAttempt).getByText('已生成 3 段报告摘要')).toBeInTheDocument();
    expect(within(firstAttempt).getByText('节点超时：模型服务在 300 秒内未返回。')).toBeVisible();
    expect(within(firstAttempt).queryByText('当前节点')).toBeNull();

    const secondAttempt = screen.getByTestId('workflow-run-node-model-1-2');
    expect(within(secondAttempt).getByText('运行中')).toBeVisible();
    expect(within(secondAttempt).getByText('第 2 次尝试')).toBeVisible();
    expect(within(secondAttempt).getByText('当前节点')).toBeVisible();
    expect(secondAttempt).toHaveAttribute('aria-current', 'step');
  });

  it('引用按 sourceStatus 遮蔽标题/片段/定位，UPDATED 保留快照提示', () => {
    renderView(
      makeDetail({
        citations: [
          makeCitation(),
          makeCitation({
            ordinal: 2,
            sourceStatus: 'NO_ACCESS',
            title: '机密标题',
            snippet: '机密片段',
            pageNo: 9,
          }),
          makeCitation({
            ordinal: 3,
            sourceStatus: 'DELETED',
            title: '已删除标题',
            snippet: '已删除片段',
            pageNo: 4,
          }),
          makeCitation({
            ordinal: 4,
            sourceStatus: 'UPDATED',
            title: '更新过的报告.pdf',
            snippet: '生成时快照。',
            pageNo: 5,
          }),
        ],
      }),
    );

    expect(screen.getByText('[1] 新能源行业报告.pdf')).toBeVisible();
    expect(screen.getByText('第 3 页')).toBeVisible();
    expect(screen.getByText('2026 年新能源市场规模持续增长。')).toBeVisible();

    expect(screen.queryByText('机密标题')).toBeNull();
    expect(screen.queryByText('机密片段')).toBeNull();
    expect(screen.queryByText('第 9 页')).toBeNull();
    expect(screen.queryByText('已删除标题')).toBeNull();
    expect(screen.queryByText('已删除片段')).toBeNull();
    expect(screen.queryByText('第 4 页')).toBeNull();

    expect(screen.getByText('[4] 更新过的报告.pdf')).toBeVisible();
    expect(screen.getByText('源内容已更新')).toBeVisible();
    expect(
      screen.getByText('引用保留生成时的快照；源内容已更新，定位指向当前版本。'),
    ).toBeVisible();
  });

  it('展示审批状态、数据范围、决定记录与仅审批人可见的脱敏参数', () => {
    renderView(
      makeDetail({
        approvals: [
          makeApproval({ paramsPreview: { symbol: '600519', limit: 20 } }),
          makeApproval({
            id: 'approval-2',
            nodeName: '二次审批',
            status: 'PENDING',
            assigneeType: 'ROLE',
            assigneeRole: 'TENANT_ADMIN',
            assigneeUserIds: null,
            dataScopeSummary: { runScope: 'ONLY_SELF', documentReferenceCount: 0 },
            actionKind: 'MODEL',
            targetToolName: null,
            paramsPreview: null,
            decidedBy: null,
            decidedAt: null,
            decisionComment: null,
          }),
        ],
        pendingApprovalCount: 1,
        approvalStatus: 'PENDING',
      }),
    );

    expect(screen.getByText('待审批 · 待审批 1 · 已批准 1 · 已拒绝 0')).toBeVisible();
    expect(screen.getByText('人工审批')).toBeVisible();
    expect(screen.getByText('调用 MCP 工具：market_data')).toBeVisible();
    expect(screen.getByText('指定用户审批（1 人）')).toBeVisible();
    expect(screen.getByText('本部门 · 部门 dept-000… · 引用文档 2 篇')).toBeVisible();
    expect(screen.getByText('参数符合范围，同意取数。')).toBeVisible();
    expect(screen.getByText(/symbol: 600519/)).toBeInTheDocument();
    expect(screen.getByText(/limit: 20/)).toBeInTheDocument();

    // 第二次审批未返回 paramsPreview（非当前 assignee），页面不得推断或展示参数。
    expect(screen.getAllByText('脱敏参数摘要（仅审批人可见）')).toHaveLength(1);
    expect(screen.getByText('调用模型生成内容')).toBeVisible();
  });

  it('结果已保存时先展示预览，展开后展示全文并保留滚动区域', () => {
    const text = `报告正文${'甲'.repeat(RESULT_PREVIEW_LIMIT)}`;
    renderView(
      makeDetail({
        status: 'SUCCEEDED',
        currentNodeId: null,
        resultStatus: 'SAVED',
        result: {
          format: 'MARKDOWN',
          text,
          sizeBytes: text.length,
          generatedAt: '2026-11-25T09:10:00+08:00',
        },
      }),
    );

    const preview = screen.getByTestId('workflow-run-result-text');
    expect(preview.textContent).toHaveLength(RESULT_PREVIEW_LIMIT);
    expect(screen.getByText(`结果较长，当前仅显示前 ${RESULT_PREVIEW_LIMIT} 个字符`)).toBeVisible();

    const expandButton = screen.getByRole('button', { name: '展开全文' });
    expectLabelInName(expandButton);
    fireEvent.click(expandButton);

    expect(screen.getByTestId('workflow-run-result-text').textContent).toBe(text);
    expect(screen.getByRole('button', { name: '收起结果' })).toBeVisible();
    expect(screen.queryByText(/仅显示前/)).toBeNull();
  });

  it('结果超限与无结果有明确说明', () => {
    const { unmount } = renderView(
      makeDetail({
        status: 'FAILED',
        resultStatus: 'OVER_LIMIT',
        errorCode: 'WF_RESULT_LIMIT',
        errorMessage: '结果超过 5 MiB。',
        result: null,
      }),
    );

    expect(screen.getByText('结果超过大小上限，未保存完整内容')).toBeVisible();
    expect(screen.getByText('超过大小上限未保存')).toBeVisible();
    expect(screen.getByText(/系统不会静默截断/)).toBeVisible();

    unmount();
    renderView(makeDetail({ status: 'CANCELLED', resultStatus: 'NONE' }));
    expect(screen.getByText('本次运行没有最终结果')).toBeVisible();
    expect(screen.getByText('运行已取消')).toBeVisible();
  });

  it('失败运行展示原因与重试入口；无回调时只提示不提交请求', () => {
    const onRetry = vi.fn();
    const { unmount } = renderView(
      makeDetail({
        status: 'FAILED',
        currentNodeId: 'model-1',
        errorCode: 'WF_NODE_TIMEOUT',
        errorMessage: '模型服务在 300 秒内未返回。',
        nodes: [makeNode({ status: 'FAILED' })],
      }),
      onRetry,
    );

    expect(screen.getByText('运行失败')).toBeVisible();
    expect(
      screen.getAllByText('节点超时：模型服务在 300 秒内未返回。').length,
    ).toBeGreaterThanOrEqual(1);
    fireEvent.click(screen.getByRole('button', { name: '重试失败节点' }));
    expect(onRetry).toHaveBeenCalledTimes(1);

    unmount();
    renderView(
      makeDetail({
        status: 'FAILED',
        nodes: [makeNode({ status: 'FAILED' })],
      }),
    );
    fireEvent.click(screen.getByRole('button', { name: '重试失败节点' }));
    expect(screen.getByText(RETRY_T12B_NOTICE)).toBeVisible();
  });

  it('空运行对节点、引用、审批与结果给出空态说明', () => {
    renderView(
      makeDetail({
        status: 'PENDING',
        currentNodeId: null,
        nodes: [],
        approvals: [],
        citations: [],
        approvalStatus: null,
      }),
    );

    expect(screen.getByText('运行尚未产生节点记录；开始后会自动刷新。')).toBeVisible();
    expect(screen.getByText('本次运行没有知识库引用')).toBeVisible();
    expect(screen.getByText('本次运行没有审批节点')).toBeVisible();
    expect(screen.getByText('本次运行没有最终结果')).toBeVisible();
    expect(screen.getByText(SECURITY_NOTICE)).toBeVisible();
  });

  it('输入/输出摘要与结果滚动区可通过键盘聚焦', () => {
    const text = `报告正文${'甲'.repeat(RESULT_PREVIEW_LIMIT)}`;
    renderView(
      makeDetail({
        nodes: [makeNode()],
        approvals: [makeApproval({ paramsPreview: { symbol: '600519', limit: 20 } })],
        resultStatus: 'SAVED',
        result: {
          format: 'MARKDOWN',
          text,
          sizeBytes: text.length,
          generatedAt: '2026-11-25T09:10:00+08:00',
        },
      }),
    );

    fireEvent.click(screen.getByText('输入摘要'));
    fireEvent.click(screen.getByText('输出摘要'));
    fireEvent.click(screen.getByText('脱敏参数摘要（仅审批人可见）'));
    const inputRegion = screen.getByRole('region', { name: '节点生成报告输入摘要' });
    const outputRegion = screen.getByRole('region', { name: '节点生成报告输出摘要' });
    const approvalRegion = screen.getByRole('region', { name: '审批参数摘要：人工审批' });
    const resultRegion = screen.getByRole('region', { name: '运行结果全文' });
    for (const region of [inputRegion, outputRegion, approvalRegion, resultRegion]) {
      expect(region).toHaveAttribute('tabindex', '0');
      region.focus();
      expect(region).toHaveFocus();
    }
  });

  it('标题自动聚焦，引用按钮可键盘聚焦；375px 下关键入口仍可见', () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 375 });
    renderView(makeDetail({ citations: [makeCitation()] }));

    const heading = screen.getByRole('heading', { name: '运行详情', level: 1 });
    expect(heading).toHaveFocus();

    const citationButton = screen.getByRole('button', {
      name: /新能源行业报告\.pdf/,
    });
    expectLabelInName(citationButton);
    citationButton.focus();
    expect(citationButton).toHaveFocus();

    // jsdom 不做布局计算；这里只证明页面结构与关键入口在窄屏下仍渲染，真实无横向滚动归 T16a。
    expect(screen.getByTestId('workflow-run-detail')).toBeVisible();
    expect(citationButton).toBeVisible();
  });
});
