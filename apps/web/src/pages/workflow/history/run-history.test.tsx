// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import type { Page, SessionInfo } from '@/services/api/types';
import { authStore } from '@/services/auth/auth-store';
import { DEMO_SESSION, pageOf } from '@/test/fake-api';
import { withProviders } from '@/test/render';
import type { WorkflowDefinition } from '../list/workflow-api';
import type { WorkflowRunDetail, WorkflowNodeRun } from '../run/run-api';
import type { WorkflowHistoryApi, WorkflowRunSummary } from './history-api';
import { RunHistoryPanel } from './run-history';

/**
 * 运行历史面板测试。
 *
 * 覆盖加载/成功/空/错误/403/404/503、筛选、分页、取消、重试、权限与失败节点选择；
 * 真实浏览器 E2E、axe 与 375px 截图归 T16a。
 */

/** 发起人会话。 */
const REQUESTER_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  user: { ...DEMO_SESSION.user, id: 'user-1' },
  roles: ['WORKFLOW_EDITOR'],
  permissions: ['workflow.read', 'workflow.run', 'workflow.publish'],
};

/** 租户管理员会话。 */
const ADMIN_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  user: { ...DEMO_SESSION.user, id: 'admin-1' },
  permissions: ['workflow.read', 'workflow.run', 'workflow.manage', 'workflow.publish'],
};

/** 无读取权限的会话。 */
const NO_READ_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  permissions: [],
};

/** 流程下拉候选。 */
const WORKFLOWS: WorkflowDefinition[] = [
  {
    id: 'workflow-1',
    name: '市场调研流程',
    ownerUserId: 'user-1',
    status: 'ACTIVE',
    draftVersion: 2,
    publishedVersionId: 'version-1',
    publishedVersionNo: 1,
    hasUnpublishedChanges: true,
    createdAt: '2026-11-25T08:00:00+08:00',
    updatedAt: '2026-11-25T08:30:00+08:00',
  },
];

/** 构造运行摘要。 */
function makeRun(overrides: Partial<WorkflowRunSummary> = {}): WorkflowRunSummary {
  return {
    id: 'run-1',
    workflowId: 'workflow-1',
    workflowName: '市场调研流程',
    workflowVersionId: 'version-1',
    versionNo: 1,
    requesterUserId: 'user-1',
    status: 'SUCCEEDED',
    approvalStatus: 'APPROVED',
    resultStatus: 'SAVED',
    createdAt: '2026-11-25T09:00:00+08:00',
    startedAt: '2026-11-25T09:00:01+08:00',
    finishedAt: '2026-11-25T09:00:20+08:00',
    durationMs: 19_000,
    updatedAt: '2026-11-25T09:00:20+08:00',
    pendingApprovalCount: 0,
    approvedApprovalCount: 1,
    rejectedApprovalCount: 0,
    retentionExpiresAt: '2027-05-24T09:00:00+08:00',
    ...overrides,
  };
}

/** 构造节点。 */
function makeNode(overrides: Partial<WorkflowNodeRun> = {}): WorkflowNodeRun {
  return {
    id: 'node-run-1',
    nodeId: 'model-1',
    nodeType: 'MODEL',
    name: '生成报告',
    status: 'FAILED',
    attempt: 1,
    errorMessage: '模型超时',
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
    versionNo: 1,
    requesterUserId: 'user-1',
    status: 'FAILED',
    resultStatus: 'NONE',
    createdAt: '2026-11-25T09:00:00+08:00',
    startedAt: '2026-11-25T09:00:01+08:00',
    finishedAt: '2026-11-25T09:00:20+08:00',
    updatedAt: '2026-11-25T09:00:20+08:00',
    pendingApprovalCount: 0,
    approvedApprovalCount: 0,
    rejectedApprovalCount: 0,
    retentionExpiresAt: '2027-05-24T09:00:00+08:00',
    nodes: [makeNode()],
    approvals: [],
    citations: [],
    result: null,
    ...overrides,
  };
}

/** 构造 API 替身。 */
function createFakeApi(overrides: Partial<WorkflowHistoryApi> = {}): WorkflowHistoryApi {
  return {
    listRuns: vi.fn(async () => pageOf<WorkflowRunSummary>([makeRun()])),
    getRunDetail: vi.fn(async () => makeDetail()),
    cancelRun: vi.fn(async () => undefined),
    retryRunNode: vi.fn(async () => undefined),
    listWorkflows: vi.fn(async () => pageOf<WorkflowDefinition>(WORKFLOWS)),
    getWorkflow: vi.fn(),
    listVersions: vi.fn(async () => pageOf([])),
    publishWorkflow: vi.fn(),
    disableWorkflow: vi.fn(),
    enableWorkflow: vi.fn(),
    createRun: vi.fn(),
    listDepartments: vi.fn(async () => pageOf([])),
    ...overrides,
  };
}

/** 构造 API 错误。 */
function apiError(status: number, code = `RICCON-HTTP-${status}`): ApiError {
  return new ApiError('服务端原文', { status, code });
}

/**
 * 渲染运行历史面板。
 *
 * @param api API 替身
 * @param session 会话
 * @param initialWorkflowId 初始流程筛选
 * @returns 导航与 API 相关断言辅助
 */
function renderPanel(
  api: WorkflowHistoryApi,
  session: SessionInfo = REQUESTER_SESSION,
  initialWorkflowId?: string,
) {
  const onOpenDetail = vi.fn();
  const onBackToStudio = vi.fn();
  authStore.setAuthenticated(session);
  render(
    withProviders(
      <RunHistoryPanel
        api={api}
        session={session}
        workflowOptions={WORKFLOWS}
        initialWorkflowId={initialWorkflowId}
        onOpenDetail={onOpenDetail}
        onBackToStudio={onBackToStudio}
      />,
    ),
  );
  return { onOpenDetail, onBackToStudio };
}

/**
 * 断言按钮满足 axe `label-content-name-mismatch`：可见文本必须包含在可访问名称中。
 *
 * 本页对“查看详情/运行流程/用此版本运行/提交运行/提交重试”采用移除多余 aria-label、
 * 以完整可见文本作为名称的方案；查询也按可见名称进行，避免测试固化错误标签
 * （REV-TASK-0233-001 F-002）。
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

/**
 * 解析 rgb()/hex 颜色为 WCAG 相对亮度。
 *
 * @param color 内联样式颜色
 * @returns 相对亮度（0–1）
 */
function relativeLuminance(color: string): number {
  const channels = color.startsWith('rgb')
    ? (color.match(/\d+/g) ?? []).slice(0, 3).map(Number)
    : [1, 3, 5].map((offset) => Number.parseInt(color.slice(offset, offset + 2), 16));
  const [r, g, b] = channels.map((value) => {
    const channel = value / 255;
    return channel <= 0.03928 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/**
 * 断言 Tag 前景/背景对比度达到 WCAG 2.1 AA 4.5:1。
 *
 * @param tag 状态 Tag
 */
function expectAATagContrast(tag: HTMLElement): void {
  expect(tag.style.color).not.toBe('');
  expect(tag.style.backgroundColor).not.toBe('');
  const [lighter, darker] = [
    relativeLuminance(tag.style.color),
    relativeLuminance(tag.style.backgroundColor),
  ].sort((left, right) => right - left);
  expect((lighter + 0.05) / (darker + 0.05)).toBeGreaterThanOrEqual(4.5);
}

describe('RunHistoryPanel', () => {
  beforeEach(() => {
    authStore.reset();
  });

  it('加载并展示运行元数据、审批聚合与详情入口', async () => {
    const api = createFakeApi();
    const { onOpenDetail } = renderPanel(api);

    expect(await screen.findByText('市场调研流程')).toBeVisible();
    expect(screen.getByText('版本 v1')).toBeVisible();
    expect(screen.getByText('成功')).toBeVisible();
    expect(screen.getByText('已批准 · 待审批 0 · 已批准 1 · 已拒绝 0')).toBeVisible();
    expect(screen.getByText('19.0 s')).toBeVisible();
    expect(api.listRuns).toHaveBeenCalledWith({ page: 1, size: 20 });

    const detailButton = screen.getByRole('button', { name: '查看详情' });
    expectLabelInName(detailButton);
    fireEvent.click(detailButton);
    expect(onOpenDetail).toHaveBeenCalledWith('run-1');
  });

  it('无 workflow.read 时展示 403 且不请求列表', async () => {
    const api = createFakeApi();
    renderPanel(api, NO_READ_SESSION);

    expect(await screen.findByText('没有访问权限')).toBeVisible();
    expect(api.listRuns).not.toHaveBeenCalled();
  });

  it.each([
    [403, 'RICCON-AUTH-403', '没有访问权限'],
    [404, 'RICCON-TENANT-404', '没有可查看的运行'],
    [503, 'RICCON-WF-503', '编排功能已关闭'],
  ])('加载错误 HTTP %s 显示对应结果页', async (status, code, title) => {
    const api = createFakeApi({
      listRuns: vi.fn(async () => {
        throw apiError(status, code);
      }),
    });
    renderPanel(api);
    expect(await screen.findByText(title)).toBeVisible();
  });

  it('通用错误显示重试，点击后重新加载', async () => {
    const listRuns = vi
      .fn<WorkflowHistoryApi['listRuns']>()
      .mockRejectedValueOnce(apiError(500))
      .mockResolvedValueOnce(pageOf([makeRun()]));
    renderPanel(createFakeApi({ listRuns }));

    expect(await screen.findByText('运行历史加载失败')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '重试' }));
    expect(await screen.findByText('市场调研流程')).toBeVisible();
    expect(listRuns).toHaveBeenCalledTimes(2);
  });

  it('空态显示固定文案、当前筛选与清除入口', async () => {
    const listRuns = vi.fn(async () => pageOf<WorkflowRunSummary>([]));
    renderPanel(createFakeApi({ listRuns }));

    expect(await screen.findByText(/当前筛选条件下没有运行/)).toBeVisible();
    expect(screen.getByText('当前筛选：全部可见运行')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '清除全部筛选' }));
    await waitFor(() => expect(listRuns).toHaveBeenCalledTimes(2));
    expect(listRuns).toHaveBeenLastCalledWith({ page: 1, size: 20 });
  });

  it('只看我发起的会以当前账号过滤，并禁用手工发起人输入', async () => {
    const listRuns = vi.fn(async () => pageOf([makeRun()]));
    renderPanel(createFakeApi({ listRuns }));

    await screen.findByText('市场调研流程');
    const requesterInput = screen.getByRole('textbox', { name: '发起人用户 ID' });
    fireEvent.click(screen.getByRole('checkbox', { name: '只看我发起的' }));
    expect(requesterInput).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: '查询运行历史' }));

    await waitFor(() =>
      expect(listRuns).toHaveBeenLastCalledWith(
        expect.objectContaining({ requesterUserId: 'user-1', page: 1, size: 20 }),
      ),
    );
  });

  it('非法发起人 UUID 在客户端阻断，不发起请求', async () => {
    const listRuns = vi.fn(async () => pageOf([makeRun()]));
    renderPanel(createFakeApi({ listRuns }));

    await screen.findByText('市场调研流程');
    fireEvent.change(screen.getByRole('textbox', { name: '发起人用户 ID' }), {
      target: { value: 'not-a-uuid' },
    });
    fireEvent.click(screen.getByRole('button', { name: '查询运行历史' }));

    expect(await screen.findByText('发起人用户 ID 必须是合法 UUID。')).toBeVisible();
    expect(listRuns).toHaveBeenCalledTimes(1);
  });

  it('版本筛选按整数传参', async () => {
    const listRuns = vi.fn(async () => pageOf([makeRun()]));
    renderPanel(createFakeApi({ listRuns }));

    await screen.findByText('市场调研流程');
    fireEvent.change(screen.getByRole('spinbutton', { name: '版本筛选' }), {
      target: { value: '2' },
    });
    fireEvent.click(screen.getByRole('button', { name: '查询运行历史' }));

    await waitFor(() =>
      expect(listRuns).toHaveBeenLastCalledWith(
        expect.objectContaining({ versionNo: 2, page: 1, size: 20 }),
      ),
    );
  });

  it('分页请求下一页', async () => {
    const page: Page<WorkflowRunSummary> = {
      page: 1,
      size: 20,
      total: 25,
      totalPages: 2,
      items: [makeRun()],
    };
    const listRuns = vi.fn<WorkflowHistoryApi['listRuns']>(async () => page);
    renderPanel(createFakeApi({ listRuns }));

    await screen.findByText('市场调研流程');
    await waitFor(() => expect(document.querySelector('.ant-pagination-next')).not.toBeNull());
    const next = document.querySelector('.ant-pagination-next');
    fireEvent.click(next?.querySelector('button') ?? (next as HTMLElement));
    await waitFor(() => expect(listRuns).toHaveBeenLastCalledWith({ page: 2, size: 20 }));
  });

  it('发起人取消运行：确认后刷新列表', async () => {
    const cancelRun = vi.fn(async () => undefined);
    const listRuns = vi.fn(async () =>
      pageOf([makeRun({ status: 'RUNNING', finishedAt: null, durationMs: null })]),
    );
    renderPanel(createFakeApi({ cancelRun, listRuns }));

    await screen.findByText('市场调研流程');
    const cancelButton = screen.getByRole('button', { name: '取消运行：市场调研流程' });
    expectLabelInName(cancelButton);
    fireEvent.click(cancelButton);
    expect(await screen.findByText('确认取消运行')).toBeInTheDocument();
    const confirmButton = screen.getByRole('button', { name: '确认取消运行' });
    expectLabelInName(confirmButton);
    fireEvent.click(confirmButton);

    await waitFor(() => expect(cancelRun).toHaveBeenCalledWith('run-1'));
    await waitFor(() => expect(listRuns).toHaveBeenCalledTimes(2));
    expect(await screen.findByText(/已请求取消运行/)).toBeInTheDocument();
  });

  it('取消遇到 409 时保留弹窗并提示刷新', async () => {
    const cancelRun = vi.fn(async () => {
      throw apiError(409, 'RICCON-WF-409');
    });
    renderPanel(
      createFakeApi({
        cancelRun,
        listRuns: vi.fn(async () => pageOf([makeRun({ status: 'RUNNING' })])),
      }),
    );

    await screen.findByText('市场调研流程');
    fireEvent.click(screen.getByRole('button', { name: '取消运行：市场调研流程' }));
    fireEvent.click(await screen.findByRole('button', { name: '确认取消运行' }));

    expect(
      await screen.findByText('运行已结束或状态已变化，不能取消；请刷新后查看最新状态。'),
    ).toBeInTheDocument();
    expect(screen.getByText('确认取消运行')).toBeInTheDocument();
  });

  it('重试只选择最新尝试失败节点并提交', async () => {
    const retryRunNode = vi.fn(async () => undefined);
    const getRunDetail = vi.fn(async () =>
      makeDetail({
        nodes: [
          makeNode({ nodeId: 'model-1', attempt: 1, status: 'FAILED' }),
          makeNode({ nodeId: 'model-1', attempt: 2, status: 'SUCCEEDED' }),
          makeNode({ nodeId: 'tool-1', name: '查询数据', attempt: 1, status: 'FAILED' }),
        ],
      }),
    );
    const listRuns = vi.fn(async () => pageOf([makeRun({ status: 'FAILED' })]));
    renderPanel(createFakeApi({ getRunDetail, retryRunNode, listRuns }));

    await screen.findByText('市场调研流程');
    const retryButton = screen.getByRole('button', { name: '重试失败节点：市场调研流程' });
    expectLabelInName(retryButton);
    fireEvent.click(retryButton);
    expect(
      await screen.findByText('重试失败节点', { selector: '.ant-modal-title' }),
    ).toBeInTheDocument();
    const dialog = screen.getByRole('dialog');
    expect(await within(dialog).findByText(/查询数据/)).toBeInTheDocument();
    expect(within(dialog).queryByText(/生成报告/)).not.toBeInTheDocument();

    const submitButton = screen.getByRole('button', { name: '提交重试' });
    expectLabelInName(submitButton);
    fireEvent.click(submitButton);
    await waitFor(() => expect(retryRunNode).toHaveBeenCalledWith('run-1', 'tool-1'));
    await waitFor(() => expect(listRuns).toHaveBeenCalledTimes(2));
  });

  it('运行已不在失败状态时不提交重试', async () => {
    const retryRunNode = vi.fn(async () => undefined);
    renderPanel(
      createFakeApi({
        retryRunNode,
        getRunDetail: vi.fn(async () => makeDetail({ status: 'SUCCEEDED' })),
        listRuns: vi.fn(async () => pageOf([makeRun({ status: 'FAILED' })])),
      }),
    );

    await screen.findByText('市场调研流程');
    fireEvent.click(screen.getByRole('button', { name: '重试失败节点：市场调研流程' }));
    expect(
      await screen.findByText('运行已不在失败状态，请刷新列表后查看最新状态。'),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '提交重试' })).toBeDisabled();
    expect(retryRunNode).not.toHaveBeenCalled();
  });

  it('没有最新失败节点时给出明确说明', async () => {
    renderPanel(
      createFakeApi({
        getRunDetail: vi.fn(async () => makeDetail({ nodes: [makeNode({ status: 'SUCCEEDED' })] })),
        listRuns: vi.fn(async () => pageOf([makeRun({ status: 'FAILED' })])),
      }),
    );

    await screen.findByText('市场调研流程');
    fireEvent.click(screen.getByRole('button', { name: '重试失败节点：市场调研流程' }));
    expect(await screen.findByText(/没有找到可重试的失败节点/)).toBeInTheDocument();
  });

  it('非发起人且非管理员不显示取消/重试入口', async () => {
    const api = createFakeApi({
      listRuns: vi.fn(async () =>
        pageOf([makeRun({ status: 'FAILED', requesterUserId: 'user-1' })]),
      ),
    });
    renderPanel(api, { ...REQUESTER_SESSION, user: { ...REQUESTER_SESSION.user, id: 'user-9' } });

    await screen.findByText('市场调研流程');
    expect(screen.queryByRole('button', { name: '取消运行：市场调研流程' })).toBeNull();
    expect(screen.queryByRole('button', { name: '重试失败节点：市场调研流程' })).toBeNull();
  });

  it('租户管理员可以操作他人发起的运行', async () => {
    renderPanel(
      createFakeApi({
        listRuns: vi.fn(async () =>
          pageOf([makeRun({ status: 'FAILED', requesterUserId: 'other-user' })]),
        ),
      }),
      ADMIN_SESSION,
    );

    await screen.findByText('市场调研流程');
    expect(screen.getByRole('button', { name: '重试失败节点：市场调研流程' })).toBeVisible();
  });

  it('流程目录失败时显示告警但仍加载运行历史', async () => {
    authStore.setAuthenticated(REQUESTER_SESSION);
    render(
      withProviders(
        <RunHistoryPanel
          api={createFakeApi()}
          session={REQUESTER_SESSION}
          workflowOptions={[]}
          workflowOptionsError="目录不可用"
          onOpenDetail={vi.fn()}
          onBackToStudio={vi.fn()}
        />,
      ),
    );
    expect(await screen.findByText('流程目录加载失败')).toBeVisible();
    expect(await screen.findByText('市场调研流程')).toBeVisible();
  });

  it('运行状态 Tag 达到 AA 对比度，查询按钮按可见名称可查', async () => {
    renderPanel(createFakeApi());
    await screen.findByText('市场调研流程');

    const tags = Array.from(
      document.querySelectorAll<HTMLElement>('.workflow-history-item .ant-tag-green'),
    );
    expect(tags.length).toBeGreaterThan(0);
    tags.forEach(expectAATagContrast);

    const query = screen.getByRole('button', { name: '查询运行历史' });
    expect(query.textContent).toBe('查询');
    expectLabelInName(query);
  });
});
