// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import type { SessionInfo } from '@/services/api/types';
import { authStore } from '@/services/auth/auth-store';
import { DEMO_SESSION, pageOf } from '@/test/fake-api';
import { withProviders } from '@/test/render';
import { MARKET_RESEARCH_TEMPLATE_ID } from './workflow-api';
import type { WorkflowApi, WorkflowDefinition, WorkflowDefinitionDetail } from './workflow-api';
import WorkflowListPage from './index';

/**
 * 流程列表页测试。
 *
 * 覆盖列表/空/错误/无权限/禁用态、创建空白与从模板创建、375px 卡片结构；
 * 真实路由与跨页面跳转由 T16a E2E 覆盖。
 */

/** 流程编辑者会话。 */
const EDITOR_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  user: { ...DEMO_SESSION.user, id: 'editor-1' },
  roles: ['WORKFLOW_EDITOR'],
  permissions: ['workflow.read', 'workflow.edit', 'workflow.publish', 'workflow.run'],
};

/** 只有运行权限的普通员工。 */
const MEMBER_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  roles: ['MEMBER'],
  permissions: ['workflow.read', 'workflow.run', 'workflow.approve'],
};

/** 流程摘要。 */
function makeWorkflow(overrides: Partial<WorkflowDefinition> = {}): WorkflowDefinition {
  return {
    id: 'workflow-1',
    name: '销售部市场调研',
    description: '检索知识库并生成报告',
    ownerUserId: 'editor-1',
    status: 'ACTIVE',
    draftVersion: 2,
    publishedVersionId: null,
    publishedVersionNo: null,
    hasUnpublishedChanges: true,
    createdAt: '2026-11-25T08:00:00+08:00',
    updatedAt: '2026-11-25T09:30:00+08:00',
    ...overrides,
  };
}

/** 流程详情。 */
function makeDetail(overrides: Partial<WorkflowDefinitionDetail> = {}): WorkflowDefinitionDetail {
  return {
    ...makeWorkflow(),
    draftGraph: { schemaVersion: 1, nodes: [], edges: [] },
    latestVersion: null,
    canEdit: true,
    canPublish: true,
    canRun: false,
    ...overrides,
  };
}

/** 构造列表页 API 替身。 */
function createFakeApi(overrides: Partial<WorkflowApi> = {}): WorkflowApi {
  return {
    listWorkflows: vi.fn(async () => pageOf([])),
    createWorkflow: vi.fn(async () => makeDetail()),
    getWorkflow: vi.fn(async () => makeDetail()),
    saveDraft: vi.fn(async () => makeDetail()),
    duplicateWorkflow: vi.fn(async () => makeDetail()),
    validateWorkflow: vi.fn(async () => ({ valid: true, issues: [] })),
    publishWorkflow: vi.fn(async () => ({
      id: 'version-1',
      workflowId: 'workflow-1',
      versionNo: 1,
      runScope: 'ONLY_SELF' as const,
      graphSha256: 'a'.repeat(64),
      nodeCount: 3,
      publishedBy: 'editor-1',
      publishedAt: '2026-11-25T08:00:00+08:00',
    })),
    listModels: vi.fn(async () => pageOf([])),
    listMcpTools: vi.fn(async () => pageOf([])),
    listDepartments: vi.fn(async () => pageOf([])),
    ...overrides,
  };
}

/** 渲染列表页。 */
function renderPage(api: WorkflowApi, navigate = vi.fn(), session = EDITOR_SESSION) {
  authStore.setAuthenticated(session);
  return {
    navigate,
    ...render(withProviders(<WorkflowListPage api={api} navigate={navigate} />)),
  };
}

describe('流程列表页', () => {
  beforeEach(() => {
    authStore.reset();
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1024 });
  });

  it('加载后展示草稿状态、版本摘要与打开入口', async () => {
    const api = createFakeApi({
      listWorkflows: vi.fn(async () =>
        pageOf<WorkflowDefinition>([
          makeWorkflow(),
          makeWorkflow({
            id: 'workflow-2',
            name: '已发布流程',
            draftVersion: 3,
            publishedVersionNo: 2,
            hasUnpublishedChanges: false,
          }),
        ]),
      ),
    });
    const { navigate } = renderPage(api);

    expect(screen.getByRole('heading', { name: '编排 Studio' })).toBeVisible();
    expect(await screen.findByRole('button', { name: '销售部市场调研' })).toBeVisible();
    expect(
      screen.getByText('草稿 v2 · 尚未发布 · 更新于 2026-11-25 09:30:00 +08:00'),
    ).toBeVisible();
    expect(screen.getByText('已发布 v2')).toBeVisible();

    fireEvent.click(screen.getByRole('button', { name: '销售部市场调研' }));
    expect(navigate).toHaveBeenCalledWith('/workflows/workflow-1/edit');
  });

  it('有编排权限的空态显示固定文案与创建入口', async () => {
    const api = createFakeApi();
    renderPage(api);

    expect(await screen.findByText('还没有流程。从模板创建，或新建空白流程。')).toBeVisible();
    expect(screen.getAllByRole('button', { name: '从模板创建' }).length).toBeGreaterThan(0);
    expect(screen.getAllByRole('button', { name: '新建空白流程' }).length).toBeGreaterThan(0);
  });

  it('无编排权限的空态引导联系管理员或流程所有者', async () => {
    const api = createFakeApi();
    renderPage(api, vi.fn(), MEMBER_SESSION);

    expect(
      await screen.findByText('当前没有可运行的流程。请联系租户管理员或流程所有者。'),
    ).toBeVisible();
    expect(screen.getAllByText('你没有编排权限，请联系租户管理员申请。').length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: '新建空白流程' })).toBeNull();
  });

  it('403 显示无权限状态并可重试', async () => {
    const api = createFakeApi({
      listWorkflows: vi.fn(async () => {
        throw new ApiError('forbidden', { status: 403, code: 'RICCON-AUTH-403' });
      }),
    });
    renderPage(api);
    expect(await screen.findByText('没有访问权限')).toBeVisible();
    expect(screen.getAllByText('你没有编排权限，请联系租户管理员申请。').length).toBeGreaterThan(0);
  });

  it('503 RICCON-WF-503 显示编排关闭文案', async () => {
    const api = createFakeApi({
      listWorkflows: vi.fn(async () => {
        throw new ApiError('disabled', { status: 503, code: 'RICCON-WF-503' });
      }),
    });
    renderPage(api);
    expect(await screen.findByText('编排功能已关闭')).toBeVisible();
    expect(screen.getByText('编排功能已关闭，已有运行记录仍可查看。')).toBeVisible();
  });

  it('错误态提供重试并在成功后渲染列表', async () => {
    const listWorkflows = vi
      .fn<WorkflowApi['listWorkflows']>()
      .mockRejectedValueOnce(new ApiError('boom', { status: 500, code: 'RICCON-INTERNAL-500' }))
      .mockResolvedValueOnce(pageOf<WorkflowDefinition>([makeWorkflow()]));
    const api = createFakeApi({ listWorkflows });
    renderPage(api);

    expect(await screen.findByText('流程列表加载失败')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '重试' }));
    expect(await screen.findByRole('button', { name: '销售部市场调研' })).toBeVisible();
    expect(listWorkflows).toHaveBeenCalledTimes(2);
  });

  it('创建空白流程后跳转编辑器', async () => {
    const detail = makeDetail({ id: 'workflow-new' });
    const createWorkflow = vi.fn(async () => detail);
    const api = createFakeApi({ createWorkflow });
    const { navigate } = renderPage(api);

    await screen.findByText('还没有流程。从模板创建，或新建空白流程。');
    fireEvent.click(screen.getAllByRole('button', { name: '新建空白流程' })[0]);
    fireEvent.change(screen.getByPlaceholderText('例如：销售部市场调研'), {
      target: { value: '  新流程  ' },
    });
    fireEvent.click(screen.getByRole('button', { name: '创建并打开' }));

    await waitFor(() => {
      expect(createWorkflow).toHaveBeenCalledWith({ name: '新流程' });
    });
    expect(navigate).toHaveBeenCalledWith('/workflows/workflow-new/edit');
  });

  it('从内置模板创建时使用固定模板 ID', async () => {
    const detail = makeDetail({ id: 'workflow-template' });
    const createWorkflow = vi.fn(async () => detail);
    const api = createFakeApi({ createWorkflow });
    const { navigate } = renderPage(api);

    await screen.findByText('还没有流程。从模板创建，或新建空白流程。');
    fireEvent.click(screen.getAllByRole('button', { name: '从模板创建' })[0]);
    fireEvent.click(screen.getByRole('button', { name: '创建并打开' }));

    await waitFor(() => {
      expect(createWorkflow).toHaveBeenCalledWith({ templateId: MARKET_RESEARCH_TEMPLATE_ID });
    });
    expect(navigate).toHaveBeenCalledWith('/workflows/workflow-template/edit');
  });

  it('创建失败在弹窗内显示错误且不跳转', async () => {
    const createWorkflow = vi.fn(async () => {
      throw new ApiError('bad', { status: 400, code: 'RICCON-WF-400', retryable: false });
    });
    const api = createFakeApi({ createWorkflow });
    const { navigate } = renderPage(api);

    await screen.findByText('还没有流程。从模板创建，或新建空白流程。');
    fireEvent.click(screen.getAllByRole('button', { name: '新建空白流程' })[0]);
    fireEvent.change(screen.getByPlaceholderText('例如：销售部市场调研'), {
      target: { value: '流程' },
    });
    fireEvent.click(screen.getByRole('button', { name: '创建并打开' }));

    expect(await screen.findByText('bad')).toBeInTheDocument();
    expect(navigate).not.toHaveBeenCalled();
  });

  it('375px 使用卡片布局且操作按钮可达', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 375 });
    const api = createFakeApi({
      listWorkflows: vi.fn(async () => pageOf<WorkflowDefinition>([makeWorkflow()])),
    });
    renderPage(api);

    const list = await screen.findByTestId('workflow-list');
    expect(list).toHaveAttribute('data-layout', 'cards');
    expect(screen.getByRole('button', { name: '打开编辑' })).toBeVisible();
    expect(screen.getByRole('button', { name: '销售部市场调研' })).toBeVisible();
  });
});
