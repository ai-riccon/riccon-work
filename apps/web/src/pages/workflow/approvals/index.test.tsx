// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { SessionInfo } from '@/services/api/types';
import { authStore } from '@/services/auth/auth-store';
import { DEMO_SESSION, pageOf } from '@/test/fake-api';
import { withProviders } from '@/test/render';
import type { WorkflowApproval, WorkflowApprovalApi, WorkflowApprovalDetail } from './approval-api';
import { APPROVED_RESULT_MESSAGE, REJECTED_RESULT_MESSAGE } from './format';
import WorkflowApprovalsPage from './index';

/**
 * 审批页面集成测试。
 *
 * 覆盖无审批权限、待办↔详情切换、决定成功后返回并刷新、深链详情与返回焦点；
 * 真实路由、键盘全流程与 axe 由 T16a 覆盖。
 */

/** 固定时钟。 */
const NOW = () => new Date('2026-11-25T10:00:00+08:00');

/** 审批人会话。 */
const APPROVER_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  user: { ...DEMO_SESSION.user, id: 'approver-1' },
  roles: ['WORKFLOW_EDITOR'],
  permissions: ['workflow.read', 'workflow.run', 'workflow.approve'],
};

/** 无审批权限的会话。 */
const READER_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  permissions: ['workflow.read', 'workflow.run'],
};

/** 待办夹具。 */
function makeApproval(overrides: Partial<WorkflowApproval> = {}): WorkflowApproval {
  return {
    id: 'approval-1',
    workflowRunId: 'run-1',
    workflowId: 'workflow-1',
    workflowName: '市场调研流程',
    nodeId: 'approval-node',
    nodeName: '人工审批',
    status: 'PENDING',
    assigneeType: 'ROLE',
    assigneeRole: 'WORKFLOW_EDITOR',
    actionKind: 'MCP_TOOL',
    dataScopeSummary: { runScope: 'TENANT', departmentId: null, documentReferenceCount: 1 },
    targetToolName: 'market_data',
    planSha256: 'a'.repeat(64),
    requestedBy: 'user-0001',
    createdAt: '2026-11-25T08:00:00+08:00',
    ...overrides,
  };
}

/** 详情夹具。 */
function makeDetail(overrides: Partial<WorkflowApprovalDetail> = {}): WorkflowApprovalDetail {
  return {
    ...makeApproval(),
    paramsPreview: { symbol: '600519' },
    selfApprovalBlocked: false,
    documentReferenceCount: 1,
    ...overrides,
  };
}

/** 构造审批 API 替身。 */
function createFakeApi(overrides: Partial<WorkflowApprovalApi> = {}): WorkflowApprovalApi {
  return {
    listApprovals: vi.fn(async () => pageOf<WorkflowApproval>([makeApproval()])),
    getApproval: vi.fn(async () => makeDetail()),
    decideApproval: vi.fn(async () => makeApproval({ status: 'APPROVED' })),
    ...overrides,
  };
}

/**
 * 渲染审批页面。
 *
 * @param api API 替身
 * @param options 会话、导航与深链参数
 * @returns 导航替身
 */
function renderPage(
  api: WorkflowApprovalApi,
  options: {
    session?: SessionInfo;
    initialApprovalId?: string;
    navigate?: (to: string) => void;
  } = {},
) {
  const navigate = options.navigate ?? vi.fn();
  authStore.setAuthenticated(options.session ?? APPROVER_SESSION);
  render(
    withProviders(
      <WorkflowApprovalsPage
        api={api}
        initialApprovalId={options.initialApprovalId}
        navigate={navigate}
        now={NOW}
      />,
    ),
  );
  return { navigate };
}

describe('WorkflowApprovalsPage', () => {
  beforeEach(() => {
    authStore.reset();
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1024 });
  });

  it('没有审批权限时不请求待办并说明联系租户管理员', async () => {
    const api = createFakeApi();
    const { navigate } = renderPage(api, { session: READER_SESSION });

    expect(await screen.findByText('没有审批权限')).toBeVisible();
    expect(screen.getByText('你没有审批权限，请联系租户管理员申请。')).toBeVisible();
    expect(api.listApprovals).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: '返回编排 Studio' }));
    expect(navigate).toHaveBeenCalledWith('/workflows/list');
  });

  it('从待办打开详情并返回后重新聚焦标题', async () => {
    const api = createFakeApi();
    renderPage(api);

    fireEvent.click(
      await screen.findByRole('button', { name: '查看审批详情：市场调研流程 · 人工审批' }),
    );
    expect(await screen.findByRole('heading', { name: '审批详情', level: 1 })).toBeVisible();
    expect(api.getApproval).toHaveBeenCalledWith('approval-1');

    fireEvent.click(screen.getByRole('button', { name: /返回待办/ }));
    const heading = await screen.findByRole('heading', { name: '审批待办', level: 1 });
    await waitFor(() => expect(heading).toHaveFocus());
    expect(api.listApprovals).toHaveBeenCalledTimes(2);
  });

  it('决定成功后提示结果、返回待办并刷新列表', async () => {
    const api = createFakeApi();
    renderPage(api);

    fireEvent.click(
      await screen.findByRole('button', { name: '查看审批详情：市场调研流程 · 人工审批' }),
    );
    fireEvent.click(await screen.findByRole('button', { name: '批准' }));

    expect(await screen.findByText(APPROVED_RESULT_MESSAGE)).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: '审批待办', level: 1 })).toBeVisible();
    await waitFor(() => expect(api.listApprovals).toHaveBeenCalledTimes(2));
  });

  it('拒绝成功后使用 PRODUCT 固定结果文案', async () => {
    const api = createFakeApi({
      decideApproval: vi.fn(async () => makeApproval({ status: 'REJECTED' })),
    });
    renderPage(api);

    fireEvent.click(
      await screen.findByRole('button', { name: '查看审批详情：市场调研流程 · 人工审批' }),
    );
    fireEvent.change(await screen.findByLabelText(/审批意见/), {
      target: { value: '引用资料不足' },
    });
    fireEvent.click(screen.getByRole('button', { name: '拒绝' }));

    expect(await screen.findByText(REJECTED_RESULT_MESSAGE)).toBeInTheDocument();
    expect(api.decideApproval).toHaveBeenCalledWith('approval-1', {
      decision: 'REJECT',
      comment: '引用资料不足',
    });
  });

  it('深链审批 ID 直接渲染详情，返回后进入待办', async () => {
    const api = createFakeApi();
    renderPage(api, { initialApprovalId: 'approval-1' });

    expect(await screen.findByRole('heading', { name: '审批详情', level: 1 })).toBeVisible();
    expect(api.listApprovals).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: /返回待办/ }));
    expect(await screen.findByRole('heading', { name: '市场调研流程', level: 2 })).toBeVisible();
  });
});
