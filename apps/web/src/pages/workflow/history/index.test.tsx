// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import type { SessionInfo } from '@/services/api/types';
import { authStore } from '@/services/auth/auth-store';
import { DEMO_SESSION, pageOf } from '@/test/fake-api';
import { withProviders } from '@/test/render';
import type {
  WorkflowDefinition,
  WorkflowDefinitionDetail,
  WorkflowVersion,
} from '../list/workflow-api';
import type { WorkflowHistoryApi, WorkflowRunSummary } from './history-api';
import WorkflowHistoryPage from './index';

/**
 * 运行历史/发布页集成测试。
 *
 * 覆盖会话恢复、无权限、流程候选加载（管理员 ALL / 其他人 MINE+RUNNABLE）、目录失败降级、
 * 深链初始流程与 T12a/T10 导航；面板内部交互由各自测试覆盖。
 */

/** 只读员工会话。 */
const READER_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  roles: ['MEMBER'],
  permissions: ['workflow.read', 'workflow.run'],
};

/** 租户管理员会话。 */
const ADMIN_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  roles: ['TENANT_ADMIN'],
  permissions: ['workflow.read', 'workflow.run', 'workflow.manage', 'workflow.publish'],
};

/** 无读取权限会话。 */
const NO_READ_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  permissions: [],
};

/** 流程夹具。 */
const WORKFLOW: WorkflowDefinition = {
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
};

/** 版本夹具。 */
const VERSION: WorkflowVersion = {
  id: 'version-1',
  workflowId: 'workflow-1',
  versionNo: 1,
  runScope: 'ONLY_SELF',
  graphSha256: 'a'.repeat(64),
  nodeCount: 4,
  publishedBy: 'user-1',
  publishedAt: '2026-11-25T09:00:00+08:00',
};

/** 运行夹具。 */
const RUN: WorkflowRunSummary = {
  id: 'run-1',
  workflowId: 'workflow-1',
  workflowName: '市场调研流程',
  workflowVersionId: 'version-1',
  versionNo: 1,
  requesterUserId: 'user-1',
  status: 'SUCCEEDED',
  resultStatus: 'SAVED',
  createdAt: '2026-11-25T09:00:00+08:00',
  startedAt: '2026-11-25T09:00:01+08:00',
  finishedAt: '2026-11-25T09:00:20+08:00',
  durationMs: 19_000,
  updatedAt: '2026-11-25T09:00:20+08:00',
  pendingApprovalCount: 0,
  approvedApprovalCount: 0,
  rejectedApprovalCount: 0,
  retentionExpiresAt: '2027-05-24T09:00:00+08:00',
};

/** 流程详情夹具。 */
const DETAIL: WorkflowDefinitionDetail = {
  ...WORKFLOW,
  draftGraph: null,
  latestVersion: VERSION,
  canEdit: false,
  canPublish: false,
  canRun: true,
};

/** 构造 API 替身。 */
function createFakeApi(overrides: Partial<WorkflowHistoryApi> = {}): WorkflowHistoryApi {
  return {
    listRuns: vi.fn(async () => pageOf([RUN])),
    getRunDetail: vi.fn(),
    cancelRun: vi.fn(),
    retryRunNode: vi.fn(),
    listWorkflows: vi.fn(async () => pageOf([WORKFLOW])),
    getWorkflow: vi.fn(async () => DETAIL),
    listVersions: vi.fn(async () => pageOf([VERSION])),
    publishWorkflow: vi.fn(),
    disableWorkflow: vi.fn(),
    enableWorkflow: vi.fn(),
    createRun: vi.fn(),
    listDepartments: vi.fn(async () => pageOf([])),
    ...overrides,
  };
}

/** 构造会话读取替身。 */
function readerOf(session: SessionInfo) {
  return { getSession: vi.fn(async () => session) };
}

/**
 * 渲染页面。
 *
 * @param api API 替身
 * @param options 会话、读取器、导航与深链
 * @returns 导航替身
 */
function renderPage(
  api: WorkflowHistoryApi,
  options: {
    session?: SessionInfo;
    reader?: { getSession: () => Promise<SessionInfo> };
    navigate?: (to: string) => void;
    initialWorkflowId?: string;
  } = {},
) {
  const navigate = options.navigate ?? vi.fn();
  const session = options.session ?? READER_SESSION;
  authStore.setAuthenticated(session);
  render(
    withProviders(
      <WorkflowHistoryPage
        api={api}
        navigate={navigate}
        sessionReader={options.reader ?? readerOf(session)}
        initialWorkflowId={options.initialWorkflowId}
      />,
    ),
  );
  return { navigate };
}

describe('WorkflowHistoryPage', () => {
  beforeEach(() => {
    authStore.reset();
  });

  it('恢复未知会话后加载流程候选与运行历史', async () => {
    authStore.reset();
    const api = createFakeApi();
    const reader = readerOf(READER_SESSION);
    render(
      withProviders(<WorkflowHistoryPage api={api} sessionReader={reader} navigate={vi.fn()} />),
    );

    expect(await screen.findByRole('heading', { name: '运行历史', level: 1 })).toBeVisible();
    await waitFor(() => expect(api.listRuns).toHaveBeenCalled());
    expect(api.listWorkflows).toHaveBeenCalledWith({ view: 'MINE', page: 1, size: 100 });
    expect(api.listWorkflows).toHaveBeenCalledWith({
      view: 'RUNNABLE',
      page: 1,
      size: 100,
    });
  });

  it('无 workflow.read 时展示 403 且不请求任何编排数据', () => {
    const api = createFakeApi();
    renderPage(api, { session: NO_READ_SESSION });

    expect(screen.getByText('没有访问权限')).toBeVisible();
    expect(api.listRuns).not.toHaveBeenCalled();
    expect(api.listWorkflows).not.toHaveBeenCalled();
  });

  it('匿名或过期会话提示重新登录', () => {
    authStore.setAuthenticated(READER_SESSION);
    authStore.signOut('expired');
    render(
      withProviders(
        <WorkflowHistoryPage
          api={createFakeApi()}
          sessionReader={readerOf(READER_SESSION)}
          navigate={vi.fn()}
        />,
      ),
    );
    expect(screen.getByText('登录状态已失效')).toBeVisible();
  });

  it('租户管理员使用 ALL 视图加载流程候选', async () => {
    const api = createFakeApi({
      getWorkflow: vi.fn(async () => ({ ...DETAIL, canPublish: true, canEdit: true })),
    });
    renderPage(api, { session: ADMIN_SESSION });

    await waitFor(() =>
      expect(api.listWorkflows).toHaveBeenCalledWith({
        view: 'ALL',
        page: 1,
        size: 100,
      }),
    );
    expect(api.listWorkflows).not.toHaveBeenCalledWith(expect.objectContaining({ view: 'MINE' }));
  });

  it('流程目录失败时仍加载运行历史，并在两个面板提示', async () => {
    const api = createFakeApi({
      listWorkflows: vi.fn(async () => {
        throw new ApiError('服务端原文', { status: 500, code: 'RICCON-HTTP-500' });
      }),
    });
    renderPage(api);

    await waitFor(() => expect(api.listRuns).toHaveBeenCalled());
    expect((await screen.findAllByText('流程目录加载失败')).length).toBeGreaterThanOrEqual(1);
    expect(await screen.findByText('市场调研流程')).toBeVisible();
  });

  it('深链初始流程同时用于运行筛选与发布详情', async () => {
    const api = createFakeApi();
    renderPage(api, { initialWorkflowId: 'workflow-1' });

    await waitFor(() =>
      expect(api.listRuns).toHaveBeenCalledWith(
        expect.objectContaining({ workflowId: 'workflow-1' }),
      ),
    );
    expect(api.getWorkflow).toHaveBeenCalledWith('workflow-1');
  });

  it('查看详情导航到 T12a 运行详情路由', async () => {
    const api = createFakeApi();
    const navigate = vi.fn();
    renderPage(api, { navigate });

    await screen.findByText('市场调研流程');
    fireEvent.click(screen.getByRole('button', { name: '查看详情' }));
    expect(navigate).toHaveBeenCalledWith('/workflows/runs/run-1');
  });

  it('打开编辑器导航到流程编辑路由', async () => {
    const api = createFakeApi({
      getWorkflow: vi.fn(async () => ({ ...DETAIL, canEdit: true, canPublish: true })),
    });
    const navigate = vi.fn();
    renderPage(api, { navigate, initialWorkflowId: 'workflow-1' });

    fireEvent.click(await screen.findByRole('button', { name: '打开编辑器' }));
    expect(navigate).toHaveBeenCalledWith('/workflows/workflow-1/edit');
  });

  it('会话错误显示重试并可重新恢复', async () => {
    authStore.reset();
    const reader = {
      getSession: vi
        .fn<() => Promise<SessionInfo>>()
        .mockRejectedValueOnce(new ApiError('服务不可用', { status: 500, code: 'RICCON-HTTP-500' }))
        .mockResolvedValueOnce(READER_SESSION),
    };
    render(
      withProviders(
        <WorkflowHistoryPage api={createFakeApi()} sessionReader={reader} navigate={vi.fn()} />,
      ),
    );

    expect(await screen.findByText('无法确认登录状态')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '重试' }));
    expect(await screen.findByRole('heading', { name: '运行历史', level: 1 })).toBeVisible();
  });
});
