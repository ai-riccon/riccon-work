// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import { pageOf } from '@/test/fake-api';
import { withProviders } from '@/test/render';
import type { WorkflowApproval, WorkflowApprovalApi, WorkflowApprovalPage } from './approval-api';
import { APPROVAL_EMPTY_MESSAGE } from './format';
import { ApprovalInbox } from './workflow-approvals-inbox';

/**
 * 审批待办列表组件测试。
 *
 * 覆盖加载/空/错误/403/404/503 状态、字段展示、分页、打开详情、返回聚焦与 375px 卡片布局；
 * 键盘全流程与 axe 由 T16a 的 E2E-M06-09/@a11y 在真实浏览器覆盖。
 */

/** 固定时钟；与夹具创建时间相差 2 小时。 */
const NOW = () => new Date('2026-11-25T10:00:00+08:00');

/** 审计夹具。 */
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
    dataScopeSummary: {
      runScope: 'DEPARTMENT',
      departmentId: 'dept-0001-aaaa',
      documentReferenceCount: 2,
    },
    targetToolName: 'market_data',
    planSha256: 'a'.repeat(64),
    requestedBy: 'user-0001',
    createdAt: '2026-11-25T08:00:00+08:00',
    ...overrides,
  };
}

/** 构造审批 API 替身。 */
function createFakeApi(overrides: Partial<WorkflowApprovalApi> = {}): WorkflowApprovalApi {
  return {
    listApprovals: vi.fn(async () => pageOf<WorkflowApproval>([makeApproval()])),
    getApproval: vi.fn(async () => makeApproval()),
    decideApproval: vi.fn(async () => makeApproval({ status: 'APPROVED' })),
    ...overrides,
  };
}

/**
 * 渲染待办列表。
 *
 * @param api API 替身
 * @param options 回调与渲染参数
 * @returns 导航与回调替身
 */
function renderInbox(
  api: WorkflowApprovalApi,
  options: { focusToken?: number; onOpen?: (id: string) => void } = {},
) {
  const onBackToStudio = vi.fn();
  const onOpen = options.onOpen ?? vi.fn();
  render(
    withProviders(
      <ApprovalInbox
        api={api}
        focusToken={options.focusToken}
        now={NOW}
        onBackToStudio={onBackToStudio}
        onOpen={onOpen}
      />,
    ),
  );
  return { onBackToStudio, onOpen };
}

describe('ApprovalInbox', () => {
  beforeEach(() => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1024 });
  });

  it('展示流程、节点、发起人、等待时长、数据范围与将执行动作', async () => {
    const api = createFakeApi();
    renderInbox(api);

    expect(await screen.findByRole('heading', { name: '市场调研流程', level: 2 })).toBeVisible();
    expect(screen.getByText('人工审批')).toBeVisible();
    expect(screen.getByText('user-000…')).toBeVisible();
    expect(screen.getByText('已等待 2 小时')).toBeVisible();
    expect(screen.getByText('本部门 · 部门 dept-000… · 引用文档 2 篇')).toBeVisible();
    expect(screen.getByText('调用 MCP 工具：market_data')).toBeVisible();
    expect(screen.getByText('共 1 项待审批')).toBeVisible();
    expect(api.listApprovals).toHaveBeenCalledWith({ status: 'PENDING', page: 1, size: 20 });
  });

  it('空态显示 PRODUCT 固定文案与刷新入口', async () => {
    const api = createFakeApi({
      listApprovals: vi.fn(async () => pageOf<WorkflowApproval>([])),
    });
    renderInbox(api);

    expect(await screen.findByText(APPROVAL_EMPTY_MESSAGE)).toBeVisible();
    expect(screen.getByRole('button', { name: /刷新待办/ })).toBeVisible();

    fireEvent.click(screen.getByRole('button', { name: /刷新待办/ }));
    await waitFor(() => expect(api.listApprovals).toHaveBeenCalledTimes(2));
  });

  it('加载中提供 aria-busy 说明', () => {
    const api = createFakeApi({
      listApprovals: vi.fn<WorkflowApprovalApi['listApprovals']>(
        () => new Promise<WorkflowApprovalPage>(() => undefined),
      ),
    });
    renderInbox(api);

    expect(screen.getByLabelText('正在加载审批待办')).toBeInTheDocument();
  });

  it('403 显示联系租户管理员并可重试', async () => {
    const listApprovals = vi
      .fn<WorkflowApprovalApi['listApprovals']>()
      .mockRejectedValueOnce(new ApiError('forbidden', { status: 403, code: 'RICCON-AUTH-403' }))
      .mockResolvedValueOnce(pageOf<WorkflowApproval>([makeApproval()]));
    const api = createFakeApi({ listApprovals });
    renderInbox(api);

    expect(await screen.findByText('没有审批权限')).toBeVisible();
    expect(screen.getByText('你没有审批权限，请联系租户管理员申请。')).toBeVisible();

    fireEvent.click(screen.getByRole('button', { name: '重试' }));
    expect(await screen.findByRole('heading', { name: '市场调研流程', level: 2 })).toBeVisible();
  });

  it('404 说明不可见并提供返回 Studio 的下一步', async () => {
    const api = createFakeApi({
      listApprovals: vi.fn(async () => {
        throw new ApiError('not found', { status: 404, code: 'RICCON-TENANT-404' });
      }),
    });
    const { onBackToStudio } = renderInbox(api);

    expect(await screen.findByText('审批内容不存在')).toBeVisible();
    expect(
      screen.getByText('审批内容不存在，或当前账号无权访问。平台管理员不查看租户审批内容。'),
    ).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '返回编排 Studio' }));
    expect(onBackToStudio).toHaveBeenCalled();
  });

  it('503 显示服务不可用并可重试', async () => {
    const api = createFakeApi({
      listApprovals: vi.fn(async () => {
        throw new ApiError('down', { status: 503, code: 'RICCON-INTERNAL-503' });
      }),
    });
    renderInbox(api);

    expect(await screen.findByText('审批待办加载失败')).toBeVisible();
    expect(screen.getByText('服务暂时不可用，请稍后重试。')).toBeVisible();
    expect(screen.getByRole('button', { name: '重试' })).toBeVisible();
  });

  it('分页请求下一页，并在当前页为空时回到第一页', async () => {
    const firstPage = pageOf<WorkflowApproval>([makeApproval()]);
    const listApprovals = vi.fn(
      async (input?: { page?: number }): Promise<WorkflowApprovalPage> => {
        if (input?.page === 2) {
          return { page: 2, size: 20, total: 21, totalPages: 2, items: [] };
        }
        return { ...firstPage, total: 21, totalPages: 2 };
      },
    );
    const api = createFakeApi({ listApprovals });
    renderInbox(api);

    await screen.findByRole('heading', { name: '市场调研流程', level: 2 });
    fireEvent.click(within(screen.getByTitle('2')).getByText('2'));

    await waitFor(() =>
      expect(listApprovals).toHaveBeenLastCalledWith({ status: 'PENDING', page: 1, size: 20 }),
    );
    expect(await screen.findByRole('heading', { name: '市场调研流程', level: 2 })).toBeVisible();
    expect(listApprovals).toHaveBeenCalledWith({ status: 'PENDING', page: 2, size: 20 });
  });

  it('点击查看详情回传审批 ID', async () => {
    const api = createFakeApi();
    const { onOpen } = renderInbox(api);

    fireEvent.click(
      await screen.findByRole('button', { name: '查看审批详情：市场调研流程 · 人工审批' }),
    );
    expect(onOpen).toHaveBeenCalledWith('approval-1');
  });

  it('返回待办时聚焦标题，键盘用户可从页面开头继续', async () => {
    const api = createFakeApi();
    renderInbox(api, { focusToken: 1 });

    const heading = await screen.findByRole('heading', { name: '审批待办', level: 1 });
    await waitFor(() => expect(heading).toHaveFocus());
  });

  it('375px 使用卡片布局且查看详情按钮可达', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 375 });
    const api = createFakeApi();
    renderInbox(api);

    expect(await screen.findByTestId('workflow-approval-list')).toBeVisible();
    const button = screen.getByRole('button', {
      name: '查看审批详情：市场调研流程 · 人工审批',
    });
    expect(button).toBeVisible();
    button.focus();
    expect(button).toHaveFocus();
  });
});
