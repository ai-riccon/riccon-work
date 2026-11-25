// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { SessionInfo } from '@/services/api/types';
import { authStore } from '@/services/auth/auth-store';
import { DEMO_SESSION, pageOf } from '@/test/fake-api';
import { withProviders } from '@/test/render';
import type { WorkflowApproval, WorkflowApprovalApi, WorkflowApprovalDetail } from './approval-api';
import WorkflowApprovalDetailRoute from './detail';

/**
 * 审批详情深链路由测试。
 *
 * 覆盖动态段转 `initialApprovalId`、详情渲染与退出详情导航；真实 Umi 路由匹配与深链刷新
 * 由 T16a 的 E2E 覆盖。
 */

/** 固定时钟。 */
const NOW = () => new Date('2026-11-25T10:00:00+08:00');

/** 审批人会话（MEMBER 在生产迁移中持有 read/run/approve）。 */
const APPROVER_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  user: { ...DEMO_SESSION.user, id: 'approver-1' },
  roles: ['MEMBER'],
  permissions: ['workflow.read', 'workflow.run', 'workflow.approve'],
};

/** 审批详情夹具；只含页面实际消费字段。 */
function makeDetail(overrides: Partial<WorkflowApprovalDetail> = {}): WorkflowApprovalDetail {
  return {
    id: 'approval-1',
    workflowRunId: 'run-1',
    workflowId: 'workflow-1',
    workflowName: '市场调研流程',
    nodeId: 'approval-node',
    nodeName: '人工审批',
    status: 'PENDING',
    assigneeType: 'ROLE',
    assigneeRole: 'MEMBER',
    actionKind: 'MCP_TOOL',
    dataScopeSummary: { runScope: 'TENANT', departmentId: null, documentReferenceCount: 1 },
    targetToolName: 'market_data',
    paramsPreview: { symbol: '600519' },
    planSha256: 'a'.repeat(64),
    requestedBy: 'user-0001',
    createdAt: '2026-11-25T08:00:00+08:00',
    selfApprovalBlocked: false,
    documentReferenceCount: 1,
    ...overrides,
  };
}

/** 构造审批 API 替身。 */
function createFakeApi(): WorkflowApprovalApi {
  return {
    listApprovals: vi.fn(async () => pageOf<WorkflowApproval>([])),
    getApproval: vi.fn(async () => makeDetail()),
    decideApproval: vi.fn(async () => makeDetail({ status: 'APPROVED' })),
  };
}

describe('审批详情深链路由', () => {
  beforeEach(() => {
    authStore.reset();
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1024 });
  });

  it('把动态审批 ID 作为 initialApprovalId 直接打开详情，不先请求待办', async () => {
    const api = createFakeApi();
    authStore.setAuthenticated(APPROVER_SESSION);

    render(
      withProviders(
        <WorkflowApprovalDetailRoute api={api} now={NOW} workflowApprovalId="approval-1" />,
      ),
    );

    expect(await screen.findByRole('heading', { name: '审批详情', level: 1 })).toBeVisible();
    expect(api.getApproval).toHaveBeenCalledWith('approval-1');
    expect(api.listApprovals).not.toHaveBeenCalled();
  });

  it('深链返回待办时导航回待办路由，避免 URL 停留在已关闭详情', async () => {
    const api = createFakeApi();
    const navigate = vi.fn();
    authStore.setAuthenticated(APPROVER_SESSION);

    render(
      withProviders(
        <WorkflowApprovalDetailRoute
          api={api}
          navigate={navigate}
          now={NOW}
          workflowApprovalId="approval-1"
        />,
      ),
    );

    fireEvent.click(await screen.findByRole('button', { name: /返回待办/ }));
    expect(navigate).toHaveBeenCalledWith('/workflows/approvals');
  });

  it('深链决定成功后同样回到待办路由', async () => {
    const api = createFakeApi();
    const navigate = vi.fn();
    authStore.setAuthenticated(APPROVER_SESSION);

    render(
      withProviders(
        <WorkflowApprovalDetailRoute
          api={api}
          navigate={navigate}
          now={NOW}
          workflowApprovalId="approval-1"
        />,
      ),
    );

    fireEvent.click(await screen.findByRole('button', { name: '批准' }));
    await waitFor(() => expect(navigate).toHaveBeenCalledWith('/workflows/approvals'));
  });
});
