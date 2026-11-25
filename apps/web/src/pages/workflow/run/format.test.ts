import { describe, expect, it } from 'vitest';
import type { WorkflowNodeRun, WorkflowRunDetail } from './run-api';
import {
  approvalCountsText,
  describeApprovalAssignee,
  describeErrorCode,
  describeFailure,
  findCurrentNodeName,
  formatBytes,
  formatDuration,
  formatResultText,
  hasRetryableFailure,
  isActiveRunStatus,
  isCurrentNodeAttempt,
  isTerminalRunStatus,
  nodeStatusLabel,
  previewResultText,
  RESULT_PREVIEW_LIMIT,
  runStatusLabel,
} from './format';

/**
 * 运行详情展示规则单元测试。
 *
 * 覆盖状态文案、错误码兜底、耗时/字节格式化、结果预览、当前节点判定与重试入口条件；
 * 页面交互与轮询由组件测试覆盖。
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
    requesterUserId: 'user-1',
    status: 'RUNNING',
    currentNodeId: 'model-1',
    resultStatus: 'NONE',
    createdAt: '2026-11-25T08:59:00+08:00',
    startedAt: '2026-11-25T09:00:00+08:00',
    updatedAt: '2026-11-25T09:00:02+08:00',
    pendingApprovalCount: 0,
    approvedApprovalCount: 0,
    rejectedApprovalCount: 0,
    retentionExpiresAt: '2027-05-24T09:00:00+08:00',
    nodes: [],
    approvals: [],
    citations: [],
    result: null,
    ...overrides,
  };
}

describe('运行详情格式规则', () => {
  it('运行状态区分活跃与终态并输出中文文案', () => {
    expect(runStatusLabel('WAITING_APPROVAL')).toBe('等待审批');
    expect(isActiveRunStatus('PENDING')).toBe(true);
    expect(isActiveRunStatus('WAITING_APPROVAL')).toBe(true);
    expect(isActiveRunStatus('SUCCEEDED')).toBe(false);
    expect(isTerminalRunStatus('SUCCEEDED')).toBe(true);
    expect(isTerminalRunStatus('FAILED')).toBe(true);
    expect(isTerminalRunStatus('CANCELLED')).toBe(true);
    expect(isTerminalRunStatus('RUNNING')).toBe(false);
    expect(nodeStatusLabel('SKIPPED')).toBe('跳过');
  });

  it('审批计数摘要包含三种状态，无审批时给出明确文案', () => {
    expect(
      approvalCountsText(
        makeDetail({
          approvalStatus: 'PENDING',
          pendingApprovalCount: 1,
          approvedApprovalCount: 2,
        }),
      ),
    ).toBe('待审批 1 · 已批准 2 · 已拒绝 0');
    expect(approvalCountsText(makeDetail())).toBe('无审批节点');
  });

  it('审批人只展示解析方式与角色/人数，不泄露账号列表', () => {
    expect(
      describeApprovalAssignee({
        assigneeType: 'ROLE',
        assigneeRole: 'WORKFLOW_EDITOR',
      }),
    ).toBe('角色审批：流程编辑者');
    expect(
      describeApprovalAssignee({
        assigneeType: 'ROLE',
        assigneeRole: 'UNKNOWN_ROLE',
      }),
    ).toBe('角色审批：指定角色');
    expect(
      describeApprovalAssignee({
        assigneeType: 'USER',
        assigneeUserIds: ['user-1', 'user-2'],
      }),
    ).toBe('指定用户审批（2 人）');
    expect(describeApprovalAssignee({ assigneeType: 'USER' })).toBe('指定用户审批');
  });

  it('失败原因优先中文错误码并提供服务端脱敏消息', () => {
    expect(describeErrorCode('WF_NODE_TIMEOUT')).toBe('节点超时');
    expect(describeErrorCode(null)).toBe('未知错误');
    expect(describeFailure('WF_NODE_TIMEOUT', '模型服务在 300 秒内未返回。')).toBe(
      '节点超时：模型服务在 300 秒内未返回。',
    );
    expect(describeFailure('WF_INTERNAL', null)).toBe('内部错误');
    expect(describeFailure(null, '服务暂不可用。')).toBe('未知错误：服务暂不可用。');
    expect(describeFailure(null, null)).toBeNull();
  });

  it('格式化耗时与字节数，非法值使用占位符', () => {
    expect(formatDuration(null)).toBe('—');
    expect(formatDuration(-1)).toBe('—');
    expect(formatDuration(850)).toBe('850 ms');
    expect(formatDuration(1200)).toBe('1.2 s');
    expect(formatDuration(125_000)).toBe('2 分 5 秒');
    expect(formatBytes(null)).toBe('—');
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(1536)).toBe('1.5 KB');
    expect(formatBytes(3 * 1024 * 1024)).toBe('3.00 MB');
  });

  it('JSON 结果在可控大小内美化，非法 JSON 原样返回', () => {
    expect(formatResultText({ format: 'JSON', text: '{"a":1}', sizeBytes: 7 })).toBe(
      '{\n  "a": 1\n}',
    );
    expect(formatResultText({ format: 'JSON', text: 'not-json', sizeBytes: 8 })).toBe('not-json');
    expect(formatResultText({ format: 'TEXT', text: '纯文本', sizeBytes: 9 })).toBe('纯文本');
    expect(formatResultText(null)).toBe('');
  });

  it('结果预览超限时明确截断，展开后返回全文', () => {
    const text = 'a'.repeat(RESULT_PREVIEW_LIMIT + 10);
    const collapsed = previewResultText(text, false);
    expect(collapsed.truncated).toBe(true);
    expect(collapsed.text).toHaveLength(RESULT_PREVIEW_LIMIT);
    expect(collapsed.totalChars).toBe(RESULT_PREVIEW_LIMIT + 10);
    const expanded = previewResultText(text, true);
    expect(expanded.truncated).toBe(false);
    expect(expanded.text).toBe(text);
    expect(previewResultText('短结果', false).truncated).toBe(false);
  });

  it('当前节点取最后一条尝试，终态后不再高亮', () => {
    const first = makeNode({ id: 'node-run-1', attempt: 1, status: 'FAILED' });
    const retry = makeNode({ id: 'node-run-2', attempt: 2, status: 'RUNNING' });
    const detail = makeDetail({ nodes: [first, retry] });

    expect(findCurrentNodeName(detail)).toBe('生成报告');
    expect(isCurrentNodeAttempt(detail, retry)).toBe(true);
    expect(isCurrentNodeAttempt(detail, first)).toBe(false);
    expect(findCurrentNodeName(makeDetail({ status: 'FAILED', nodes: [first, retry] }))).toBeNull();
    expect(
      isCurrentNodeAttempt(makeDetail({ status: 'SUCCEEDED', nodes: [first, retry] }), retry),
    ).toBe(false);
  });

  it('只有运行失败且存在失败节点时才展示重试入口', () => {
    const failed = makeNode({ status: 'FAILED' });
    expect(hasRetryableFailure(makeDetail({ status: 'FAILED', nodes: [failed] }))).toBe(true);
    expect(hasRetryableFailure(makeDetail({ status: 'RUNNING', nodes: [failed] }))).toBe(false);
    expect(hasRetryableFailure(makeDetail({ status: 'FAILED', nodes: [] }))).toBe(false);
  });
});
