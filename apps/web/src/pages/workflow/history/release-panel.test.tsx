// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import type { Department, SessionInfo } from '@/services/api/types';
import { authStore } from '@/services/auth/auth-store';
import { DEMO_SESSION, pageOf } from '@/test/fake-api';
import { withProviders } from '@/test/render';
import type {
  WorkflowDefinition,
  WorkflowDefinitionDetail,
  WorkflowVersion,
} from '../list/workflow-api';
import type { WorkflowHistoryApi, WorkflowRunSummary } from './history-api';
import { ReleasePanel } from './release-panel';

/**
 * 发布与版本面板测试。
 *
 * 覆盖流程选择、版本列表、发布成功/400/409/503、停用启用、用旧版本运行、部门目录失败、
 * DEPARTMENT 范围保留与租户管理员范围阻断（REV-TASK-0230-001 F-001/F-005）；
 * 真实浏览器 E2E/axe/375px 归 T16a。
 */

/** 流程编辑者会话（非租户管理员）。 */
const EDITOR_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  user: { ...DEMO_SESSION.user, id: 'editor-1' },
  roles: ['WORKFLOW_EDITOR'],
  permissions: [
    'workflow.read',
    'workflow.edit',
    'workflow.publish',
    'workflow.run',
    'department.read',
  ],
};

/** 租户管理员会话。 */
const ADMIN_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  user: { ...DEMO_SESSION.user, id: 'admin-1' },
  roles: ['TENANT_ADMIN'],
  permissions: [
    'workflow.read',
    'workflow.edit',
    'workflow.publish',
    'workflow.run',
    'workflow.manage',
    'department.read',
  ],
};

/** 只读会话。 */
const VIEWER_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  user: { ...DEMO_SESSION.user, id: 'viewer-1' },
  permissions: ['workflow.read'],
};

/** 流程下拉候选。 */
const WORKFLOWS: WorkflowDefinition[] = [
  {
    id: 'workflow-1',
    name: '市场调研流程',
    ownerUserId: 'editor-1',
    status: 'ACTIVE',
    draftVersion: 2,
    publishedVersionId: 'version-1',
    publishedVersionNo: 1,
    hasUnpublishedChanges: true,
    createdAt: '2026-11-25T08:00:00+08:00',
    updatedAt: '2026-11-25T08:30:00+08:00',
  },
];

/** 部门夹具。 */
const DEPARTMENT: Department = {
  id: 'dept-1',
  name: '销售部',
  status: 'ACTIVE',
  managerUserId: 'editor-1',
  managerDisplayName: '李强',
  memberCount: 8,
  createdAt: '2026-11-25T08:00:00+08:00',
  updatedAt: '2026-11-25T08:00:00+08:00',
};

/** 构造发布版本。 */
function makeVersion(overrides: Partial<WorkflowVersion> = {}): WorkflowVersion {
  return {
    id: 'version-1',
    workflowId: 'workflow-1',
    versionNo: 1,
    runScope: 'ONLY_SELF',
    graphSha256: 'a'.repeat(64),
    nodeCount: 4,
    publishedBy: 'editor-1',
    publishedAt: '2026-11-25T09:00:00+08:00',
    ...overrides,
  };
}

/** 构造流程详情。 */
function makeDetail(overrides: Partial<WorkflowDefinitionDetail> = {}): WorkflowDefinitionDetail {
  return {
    ...WORKFLOWS[0],
    draftGraph: null,
    latestVersion: makeVersion(),
    canEdit: true,
    canPublish: true,
    canRun: true,
    ...overrides,
  };
}

/** 构造运行摘要。 */
function makeRun(overrides: Partial<WorkflowRunSummary> = {}): WorkflowRunSummary {
  return {
    id: 'run-9',
    workflowId: 'workflow-1',
    workflowName: '市场调研流程',
    workflowVersionId: 'version-1',
    versionNo: 1,
    requesterUserId: 'editor-1',
    status: 'PENDING',
    resultStatus: 'NONE',
    createdAt: '2026-11-25T09:00:00+08:00',
    updatedAt: '2026-11-25T09:00:00+08:00',
    pendingApprovalCount: 0,
    approvedApprovalCount: 0,
    rejectedApprovalCount: 0,
    retentionExpiresAt: '2027-05-24T09:00:00+08:00',
    ...overrides,
  };
}

/** 构造 API 替身。 */
function createFakeApi(overrides: Partial<WorkflowHistoryApi> = {}): WorkflowHistoryApi {
  return {
    listRuns: vi.fn(async () => pageOf([])),
    getRunDetail: vi.fn(),
    cancelRun: vi.fn(),
    retryRunNode: vi.fn(),
    listWorkflows: vi.fn(async () => pageOf(WORKFLOWS)),
    getWorkflow: vi.fn(async () => makeDetail()),
    listVersions: vi.fn(async () =>
      pageOf([
        makeVersion(),
        makeVersion({
          id: 'version-2',
          versionNo: 2,
          publishedAt: '2026-11-25T10:00:00+08:00',
        }),
      ]),
    ),
    publishWorkflow: vi.fn(async () => makeVersion({ id: 'version-2', versionNo: 2 })),
    disableWorkflow: vi.fn(async () => makeDetail({ status: 'DISABLED', canRun: false })),
    enableWorkflow: vi.fn(async () => makeDetail()),
    createRun: vi.fn(async () => makeRun()),
    listDepartments: vi.fn(async () => pageOf([DEPARTMENT])),
    ...overrides,
  };
}

/** 构造 API 错误。 */
function apiError(status: number, code = `RICCON-HTTP-${status}`): ApiError {
  return new ApiError('服务端原文', { status, code });
}

/**
 * 渲染发布面板。
 *
 * @param api API 替身
 * @param options 会话、初始流程与回调
 * @returns 回调替身
 */
function renderPanel(
  api: WorkflowHistoryApi,
  options: {
    session?: SessionInfo;
    initialWorkflowId?: string;
    workflowOptions?: WorkflowDefinition[];
    workflowOptionsError?: string | null;
    onReloadOptions?: () => void;
  } = {},
) {
  const session = options.session ?? EDITOR_SESSION;
  const onEditWorkflow = vi.fn();
  const onOpenDetail = vi.fn();
  const onBackToStudio = vi.fn();
  const onWorkflowUpdated = vi.fn();
  authStore.setAuthenticated(session);
  render(
    withProviders(
      <ReleasePanel
        api={api}
        session={session}
        workflowOptions={options.workflowOptions ?? WORKFLOWS}
        workflowOptionsError={options.workflowOptionsError ?? null}
        onReloadOptions={options.onReloadOptions}
        initialWorkflowId={options.initialWorkflowId ?? 'workflow-1'}
        onEditWorkflow={onEditWorkflow}
        onOpenDetail={onOpenDetail}
        onBackToStudio={onBackToStudio}
        onWorkflowUpdated={onWorkflowUpdated}
      />,
    ),
  );
  return { onEditWorkflow, onOpenDetail, onBackToStudio, onWorkflowUpdated };
}

/** 等待详情与版本加载完成。 */
async function waitForDetail(): Promise<void> {
  expect(await screen.findByText('当前草稿')).toBeVisible();
  expect(await screen.findByText('已发布版本')).toBeVisible();
}

describe('ReleasePanel', () => {
  beforeEach(() => {
    authStore.reset();
  });

  it('未选择流程时提示选择，不发起详情请求', () => {
    const api = createFakeApi();
    renderPanel(api, { initialWorkflowId: '' });
    expect(screen.getByText(/选择流程后可查看已发布版本/)).toBeVisible();
    expect(api.getWorkflow).not.toHaveBeenCalled();
  });

  it('加载详情与版本，标出当前发布版本与范围', async () => {
    renderPanel(createFakeApi());
    await waitForDetail();

    expect(screen.getByText('v2（有未发布修改）')).toBeVisible();
    expect(screen.getByText('当前发布')).toBeVisible();
    expect(screen.getAllByText('v1').length).toBeGreaterThan(0);
    expect(screen.getAllByText('仅自己').length).toBeGreaterThan(0);
  });

  it('发布当前草稿：二次确认后提交仅自己范围并刷新', async () => {
    const publishWorkflow = vi.fn(async () => makeVersion({ id: 'version-2', versionNo: 2 }));
    const api = createFakeApi({ publishWorkflow });
    renderPanel(api);
    await waitForDetail();

    fireEvent.click(screen.getByRole('button', { name: '发布版本' }));
    expect(
      await screen.findByText('确认发布', { selector: '.ant-modal-title' }),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '确认发布版本' }));

    await waitFor(() =>
      expect(publishWorkflow).toHaveBeenCalledWith('workflow-1', { runScope: 'ONLY_SELF' }),
    );
    expect(await screen.findByText(/已发布 v2（仅自己）/)).toBeInTheDocument();
    await waitFor(() => expect(api.getWorkflow).toHaveBeenCalledTimes(2));
  });

  it('发布 400 显示编辑器修正提示，弹窗不关闭', async () => {
    const publishWorkflow = vi.fn(async () => {
      throw apiError(400, 'RICCON-WF-400');
    });
    renderPanel(createFakeApi({ publishWorkflow }));
    await waitForDetail();

    fireEvent.click(screen.getByRole('button', { name: '发布版本' }));
    fireEvent.click(await screen.findByRole('button', { name: '确认发布版本' }));

    expect(
      await screen.findByText(
        '发布校验未通过：请在编辑器执行校验并修正结构、配置、审批人或运行范围后再发布。',
      ),
    ).toBeInTheDocument();
    expect(screen.getByText('确认发布', { selector: '.ant-modal-title' })).toBeInTheDocument();
  });

  it('发布 409/503 给出刷新与关闭提示', async () => {
    const publishWorkflow = vi
      .fn<WorkflowHistoryApi['publishWorkflow']>()
      .mockRejectedValueOnce(apiError(409, 'RICCON-WF-409'))
      .mockRejectedValueOnce(apiError(503, 'RICCON-WF-503'));
    renderPanel(createFakeApi({ publishWorkflow }));
    await waitForDetail();

    fireEvent.click(screen.getByRole('button', { name: '发布版本' }));
    fireEvent.click(await screen.findByRole('button', { name: '确认发布版本' }));
    expect(await screen.findByText('流程已被其他操作修改，请刷新后重试。')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '确认发布版本' }));
    expect(
      await screen.findByText('编排功能已关闭，暂时不能发布；已有版本仍可查看。'),
    ).toBeInTheDocument();
  });

  it('REV F-001：部门目录未加载时保留最近发布的本部门范围并提示二次确认', async () => {
    const never = new Promise<never>(() => undefined);
    const api = createFakeApi({
      listDepartments: vi.fn(() => never),
      getWorkflow: vi.fn(async () =>
        makeDetail({
          latestVersion: makeVersion({ runScope: 'DEPARTMENT', departmentId: 'dept-1' }),
        }),
      ),
    });
    renderPanel(api);
    await waitForDetail();

    expect(
      await screen.findByText(
        '部门目录仍在加载；已保留最近发布版本的本部门范围，发布前会再次确认。',
      ),
    ).toBeVisible();
    const publishButton = screen.getByRole('button', { name: '发布版本' });
    expect(publishButton).toBeEnabled();
    fireEvent.click(publishButton);
    expect(
      await screen.findByText('确认发布', { selector: '.ant-modal-title' }),
    ).toBeInTheDocument();
    expect(screen.getAllByText(/本部门/).length).toBeGreaterThan(0);
  });

  it('REV F-005：租户管理员不能选择本部门，发布按钮不可用', async () => {
    const api = createFakeApi({
      getWorkflow: vi.fn(async () =>
        makeDetail({
          latestVersion: makeVersion({ runScope: 'DEPARTMENT', departmentId: 'dept-1' }),
        }),
      ),
    });
    renderPanel(api, { session: ADMIN_SESSION });
    await waitForDetail();

    expect(await screen.findByText(/租户管理员不能发布“本部门”范围/)).toBeVisible();
    expect(screen.getByRole('button', { name: '发布版本' })).toBeDisabled();
  });

  it('REV F-005：部门目录失败显示告警并可重试，不静默忽略', async () => {
    const listDepartments = vi
      .fn<WorkflowHistoryApi['listDepartments']>()
      .mockRejectedValueOnce(apiError(403, 'RICCON-AUTH-403'))
      .mockResolvedValueOnce(pageOf([DEPARTMENT]));
    renderPanel(createFakeApi({ listDepartments }));
    await waitForDetail();

    expect(await screen.findByText('部门目录加载失败')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '重试加载' }));
    await waitFor(() => expect(listDepartments).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.queryByText('部门目录加载失败')).not.toBeInTheDocument());
  });

  it('无发布权限时只读版本并说明下一步', async () => {
    const api = createFakeApi({
      getWorkflow: vi.fn(async () => makeDetail({ canPublish: false })),
    });
    renderPanel(api, { session: VIEWER_SESSION });
    await waitForDetail();

    expect(await screen.findByText(/你没有发布权限，只能查看已发布版本/)).toBeVisible();
    expect(screen.getByRole('button', { name: '发布版本' })).toBeDisabled();
  });

  it('停用流程：确认后调用接口并刷新', async () => {
    const disableWorkflow = vi.fn(async () => makeDetail({ status: 'DISABLED', canRun: false }));
    const api = createFakeApi({ disableWorkflow });
    renderPanel(api);
    await waitForDetail();

    fireEvent.click(screen.getByRole('button', { name: '停用流程' }));
    expect(await screen.findByText('确认停用流程')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '确认停用流程' }));

    await waitFor(() => expect(disableWorkflow).toHaveBeenCalledWith('workflow-1'));
    expect(await screen.findByText(/流程已停用：不能创建新运行/)).toBeInTheDocument();
    await waitFor(() => expect(api.getWorkflow).toHaveBeenCalledTimes(2));
  });

  it('停用 409 时保留弹窗并提示刷新', async () => {
    const disableWorkflow = vi.fn(async () => {
      throw apiError(409, 'RICCON-WF-409');
    });
    renderPanel(createFakeApi({ disableWorkflow }));
    await waitForDetail();

    fireEvent.click(screen.getByRole('button', { name: '停用流程' }));
    fireEvent.click(await screen.findByRole('button', { name: '确认停用流程' }));
    expect(await screen.findByText('流程状态已被其他操作改变，请刷新后重试。')).toBeInTheDocument();
  });

  it('用旧版本运行：版本选择器提交指定 versionId 并导航到详情', async () => {
    const createRun = vi.fn(async () => makeRun());
    const { onOpenDetail } = renderPanel(createFakeApi({ createRun }));
    await waitForDetail();

    fireEvent.click(screen.getByRole('button', { name: '用版本 v1 运行流程' }));
    expect(
      await screen.findByText('运行流程', { selector: '.ant-modal-title' }),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '确认运行流程' }));

    await waitFor(() =>
      expect(createRun).toHaveBeenCalledWith('workflow-1', {
        versionId: 'version-1',
        inputs: {},
      }),
    );
    expect(onOpenDetail).toHaveBeenCalledWith('run-9');
  });

  it('运行输入非法 JSON 时客户端阻断', async () => {
    const createRun = vi.fn(async () => makeRun());
    renderPanel(createFakeApi({ createRun }));
    await waitForDetail();

    fireEvent.click(screen.getByRole('button', { name: '用版本 v1 运行流程' }));
    fireEvent.change(await screen.findByRole('textbox', { name: '运行输入 JSON' }), {
      target: { value: '[' },
    });
    fireEvent.click(screen.getByRole('button', { name: '确认运行流程' }));

    expect(await screen.findByText('运行输入必须是合法的 JSON 对象。')).toBeInTheDocument();
    expect(createRun).not.toHaveBeenCalled();
  });

  it('运行 429/503 给出可执行提示', async () => {
    const createRun = vi
      .fn<WorkflowHistoryApi['createRun']>()
      .mockRejectedValueOnce(apiError(429, 'RICCON-WF-429'));
    renderPanel(createFakeApi({ createRun }));
    await waitForDetail();

    fireEvent.click(screen.getByRole('button', { name: '用版本 v1 运行流程' }));
    fireEvent.click(await screen.findByRole('button', { name: '确认运行流程' }));
    expect(await screen.findByText(/同时运行的流程已达上限/)).toBeInTheDocument();
  });

  it('版本列表失败时仍可用最近发布版本运行', async () => {
    const createRun = vi.fn(async () => makeRun());
    const { onOpenDetail } = renderPanel(
      createFakeApi({
        createRun,
        listVersions: vi.fn(async () => {
          throw apiError(500);
        }),
      }),
    );
    await waitForDetail();

    fireEvent.click(screen.getByRole('button', { name: '运行最近发布版本' }));
    expect(
      await screen.findByText('运行流程', { selector: '.ant-modal-title' }),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '确认运行流程' }));

    await waitFor(() =>
      expect(createRun).toHaveBeenCalledWith('workflow-1', {
        versionId: 'version-1',
        inputs: {},
      }),
    );
    expect(onOpenDetail).toHaveBeenCalledWith('run-9');
  });

  it('没有发布版本时运行按钮禁用', async () => {
    renderPanel(
      createFakeApi({
        getWorkflow: vi.fn(async () =>
          makeDetail({
            publishedVersionId: null,
            publishedVersionNo: null,
            latestVersion: null,
            canRun: false,
          }),
        ),
        listVersions: vi.fn(async () => pageOf([])),
      }),
    );
    await waitForDetail();
    expect(screen.getByRole('button', { name: '运行最近发布版本' })).toBeDisabled();
  });

  it('版本列表失败显示告警并支持重试', async () => {
    const listVersions = vi
      .fn<WorkflowHistoryApi['listVersions']>()
      .mockRejectedValueOnce(apiError(500))
      .mockResolvedValueOnce(pageOf([makeVersion()]));
    renderPanel(createFakeApi({ listVersions }));
    await waitForDetail();

    expect(await screen.findByText('版本列表加载失败')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '重试加载版本列表' }));
    await waitFor(() => expect(listVersions).toHaveBeenCalledTimes(2));
  });

  it('没有发布版本时提示先发布', async () => {
    renderPanel(
      createFakeApi({
        getWorkflow: vi.fn(async () =>
          makeDetail({
            publishedVersionId: null,
            publishedVersionNo: null,
            latestVersion: null,
            canRun: false,
          }),
        ),
        listVersions: vi.fn(async () => pageOf([])),
      }),
    );
    await waitForDetail();
    expect(await screen.findByText(/该流程还没有发布版本/)).toBeVisible();
  });

  it('流程详情 403/404/503 显示对应结果页', async () => {
    for (const [status, code, title] of [
      [403, 'RICCON-AUTH-403', '没有访问权限'],
      [404, 'RICCON-TENANT-404', '流程不存在或你无权查看'],
      [503, 'RICCON-WF-503', '编排功能已关闭'],
    ] as const) {
      const { unmount } = render(
        withProviders(
          <ReleasePanel
            api={createFakeApi({
              getWorkflow: vi.fn(async () => {
                throw apiError(status, code);
              }),
            })}
            session={EDITOR_SESSION}
            workflowOptions={WORKFLOWS}
            initialWorkflowId="workflow-1"
            onEditWorkflow={vi.fn()}
            onOpenDetail={vi.fn()}
            onBackToStudio={vi.fn()}
          />,
        ),
      );
      expect(await screen.findByText(title)).toBeVisible();
      unmount();
    }
  });

  it('流程目录失败时显示告警并可重试', async () => {
    const onReloadOptions = vi.fn();
    renderPanel(createFakeApi(), {
      workflowOptions: [],
      workflowOptionsError: '流程目录不可用',
      onReloadOptions,
    });
    expect(screen.getByText('流程目录加载失败')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '重试加载流程目录' }));
    expect(onReloadOptions).toHaveBeenCalled();
  });

  it('打开编辑器与返回 Studio 使用注入导航', async () => {
    const { onEditWorkflow, onBackToStudio } = renderPanel(createFakeApi());
    await waitForDetail();
    fireEvent.click(screen.getByRole('button', { name: '打开编辑器' }));
    expect(onEditWorkflow).toHaveBeenCalledWith('workflow-1');
    expect(onBackToStudio).not.toHaveBeenCalled();
  });
});
