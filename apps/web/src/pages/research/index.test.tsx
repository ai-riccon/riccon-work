// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import type { Workspace } from '@/services/api/types';
import { authStore } from '@/services/auth/auth-store';
import {
  DEMO_SESSION,
  DEMO_WORKSPACE,
  GRANTED_MCP_TOOL,
  createFakeApi,
  makeResearchTask,
  pageOf,
} from '@/test/fake-api';
import { withProviders } from '@/test/render';
import ResearchListPage from './index';

/**
 * J2 报告任务列表与创建页测试。
 *
 * 覆盖加载、空态、无权限、工具不可用、表单校验、创建成功与失败；
 * 不发起真实网络请求，实际路由由 E2E 覆盖。
 */
describe('报告任务列表页', () => {
  beforeEach(() => {
    authStore.reset();
    authStore.setAuthenticated(DEMO_SESSION);
  });

  /** 渲染列表页，统一注入 fake API 与导航。 */
  function renderPage(
    api: ReturnType<typeof createFakeApi>,
    navigate = vi.fn(),
    initialWorkspaceId?: string,
  ) {
    return {
      navigate,
      ...render(
        withProviders(
          <ResearchListPage
            api={api}
            initialWorkspaceId={initialWorkspaceId}
            navigate={navigate}
          />,
        ),
      ),
    };
  }

  /** 在 antd Select 中选择唯一的演示工具。 */
  async function selectGrantedTool() {
    fireEvent.mouseDown(screen.getByRole('combobox', { name: '数据工具' }));
    const option = await screen.findByText('market_data_lookup · 只读 · 需审批');
    fireEvent.click(option);
  }

  it('加载任务与授权工具后展示创建表单和历史任务', async () => {
    const api = createFakeApi({
      listResearchTasks: vi.fn(async () =>
        pageOf([
          makeResearchTask({
            id: 'task-existing',
            topic: '已有市场调研任务',
            status: 'FAILED',
            currentStage: null,
            failureMessage: '报告生成失败，请重试生成阶段。',
          }),
        ]),
      ),
    });
    const { navigate } = renderPage(api);

    expect(screen.getByRole('heading', { name: '市场调研报告' })).toBeVisible();
    expect(await screen.findByRole('button', { name: '已有市场调研任务' })).toBeVisible();
    expect(screen.getByText('报告生成失败，请重试生成阶段。')).toBeVisible();
    expect(screen.getByRole('combobox', { name: '数据工具' })).toBeEnabled();
    expect(screen.getByRole('button', { name: /创建报告任务/ })).toBeEnabled();

    fireEvent.click(screen.getByRole('button', { name: '打开任务' }));
    expect(navigate).toHaveBeenCalledWith('/research/task-existing');
  }, 15_000);

  it('校验空主题与过短主题，不调用创建接口', async () => {
    const api = createFakeApi();
    render(withProviders(<ResearchListPage api={api} navigate={vi.fn()} />));

    await screen.findByText('还没有报告任务；在左侧输入主题创建第一个任务。');
    fireEvent.click(screen.getByRole('button', { name: /创建报告任务/ }));
    expect(await screen.findByText('请输入调研主题。')).toBeInTheDocument();

    fireEvent.change(
      screen.getByPlaceholderText('例如：2026 年中国企业协同办公市场规模与竞争格局'),
      { target: { value: 'abc' } },
    );
    fireEvent.click(screen.getByRole('button', { name: /创建报告任务/ }));
    expect(await screen.findByText('调研主题至少 4 个字符。')).toBeInTheDocument();
    expect(api.createResearchTask).not.toHaveBeenCalled();
  });

  it('提交主题与工具后创建任务并跳转详情', async () => {
    const api = createFakeApi();
    const { navigate } = renderPage(api);

    await screen.findByText('还没有报告任务；在左侧输入主题创建第一个任务。');
    fireEvent.change(
      screen.getByPlaceholderText('例如：2026 年中国企业协同办公市场规模与竞争格局'),
      { target: { value: '  2026 年市场调研  ' } },
    );
    await selectGrantedTool();
    fireEvent.click(screen.getByRole('button', { name: /创建报告任务/ }));

    await waitFor(() => {
      expect(api.createResearchTask).toHaveBeenCalledTimes(1);
    });
    expect(api.createResearchTask).toHaveBeenCalledWith(
      'workspace-1',
      { topic: '2026 年市场调研', mcpToolId: GRANTED_MCP_TOOL.id },
      expect.any(String),
    );
    expect(navigate).toHaveBeenCalledWith('/research/research-task-new');
  });

  it('创建失败时保留表单并显示错误', async () => {
    const api = createFakeApi({
      createResearchTask: vi.fn(async () => {
        throw new ApiError('服务不可用', { status: 500, code: 'RICCON-INTERNAL-500' });
      }),
    });
    const { navigate } = renderPage(api);

    await screen.findByText('还没有报告任务；在左侧输入主题创建第一个任务。');
    fireEvent.change(
      screen.getByPlaceholderText('例如：2026 年中国企业协同办公市场规模与竞争格局'),
      { target: { value: '2026 年市场调研' } },
    );
    await selectGrantedTool();
    fireEvent.click(screen.getByRole('button', { name: /创建报告任务/ }));

    expect(await screen.findByText('操作未完成')).toBeVisible();
    expect(screen.getByText('服务暂时不可用，请稍后重试。')).toBeVisible();
    expect(navigate).not.toHaveBeenCalled();
  });

  it('创建报告遇到 RICCON-QUOTA-429 时透传限额类型与重置时间（M05-AC-17）', async () => {
    const resetsAt = new Date(Date.now() + 3 * 3_600_000).toISOString();
    const api = createFakeApi({
      createResearchTask: vi.fn(async () => {
        throw new ApiError('quota exceeded', {
          status: 429,
          code: 'RICCON-QUOTA-429',
          problem: {
            type: 'about:blank',
            title: '配额超限',
            status: 429,
            code: 'RICCON-QUOTA-429',
            traceId: 'trace-quota',
            detail: '已达日限额，请稍后恢复。',
            scopeType: 'TENANT',
            window: 'DAILY',
            resetsAt,
          },
        });
      }),
    });
    const { navigate } = renderPage(api);

    await screen.findByText('还没有报告任务；在左侧输入主题创建第一个任务。');
    fireEvent.change(
      screen.getByPlaceholderText('例如：2026 年中国企业协同办公市场规模与竞争格局'),
      { target: { value: '2026 年配额测试调研' } },
    );
    await selectGrantedTool();
    fireEvent.click(screen.getByRole('button', { name: /创建报告任务/ }));

    expect(await screen.findByText(/已达日限额/)).toBeVisible();
    expect(screen.getByText(/还有 [23] 小时/)).toBeVisible();
    expect(screen.getByText(/\+08:00/)).toBeVisible();
    expect(screen.queryByText('请求过于频繁，请稍后重试。')).not.toBeInTheDocument();
    expect(navigate).not.toHaveBeenCalled();
  });

  it('创建失败后修改主题会更换幂等键，避免返回旧任务', async () => {
    let attempts = 0;
    const api = createFakeApi({
      createResearchTask: vi.fn(async (_workspaceId: string, input) => {
        attempts += 1;
        if (attempts === 1) {
          throw new ApiError('服务不可用', { status: 500, code: 'RICCON-INTERNAL-500' });
        }
        return makeResearchTask({ id: 'task-after-retry', topic: input.topic });
      }),
    });
    const { navigate } = renderPage(api);

    await screen.findByText('还没有报告任务；在左侧输入主题创建第一个任务。');
    fireEvent.change(
      screen.getByPlaceholderText('例如：2026 年中国企业协同办公市场规模与竞争格局'),
      { target: { value: '第一次主题' } },
    );
    await selectGrantedTool();
    fireEvent.click(screen.getByRole('button', { name: /创建报告任务/ }));
    expect(await screen.findByText('操作未完成')).toBeVisible();

    fireEvent.change(
      screen.getByPlaceholderText('例如：2026 年中国企业协同办公市场规模与竞争格局'),
      { target: { value: '第二次主题已调整' } },
    );
    fireEvent.click(screen.getByRole('button', { name: /创建报告任务/ }));

    await waitFor(() => {
      expect(api.createResearchTask).toHaveBeenCalledTimes(2);
    });
    const firstKey = vi.mocked(api.createResearchTask).mock.calls[0][2];
    const secondKey = vi.mocked(api.createResearchTask).mock.calls[1][2];
    expect(firstKey).toBeTruthy();
    expect(secondKey).toBeTruthy();
    expect(secondKey).not.toBe(firstKey);
    expect(navigate).toHaveBeenCalledWith('/research/task-after-retry');
  });

  it('授权工具加载失败时提示并可重新加载', async () => {
    const api = createFakeApi({
      listGrantedMcpTools: vi.fn(async () => {
        throw new ApiError('服务不可用', { status: 500, code: 'RICCON-INTERNAL-500' });
      }),
    });
    renderPage(api);

    expect(await screen.findByText('授权工具加载失败')).toBeVisible();
    expect(screen.getByRole('button', { name: /创建报告任务/ })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: /重新加载/ }));
    await waitFor(() => {
      expect(api.listGrantedMcpTools).toHaveBeenCalledTimes(2);
    });
  });

  it('工具接口 403 时展示权限态并禁用创建', async () => {
    const api = createFakeApi({
      listGrantedMcpTools: vi.fn(async () => {
        throw new ApiError('没有权限', { status: 403, code: 'RICCON-AUTH-403' });
      }),
    });
    renderPage(api);

    expect(await screen.findByText('无权使用数据工具')).toBeVisible();
    expect(
      screen.getByText('当前账号没有使用 MCP 数据工具的权限，请联系管理员授权后再创建任务。'),
    ).toBeVisible();
    expect(screen.getByRole('button', { name: /创建报告任务/ })).toBeDisabled();
    expect(screen.queryByText('没有可用工具')).not.toBeInTheDocument();
  });

  it('已禁用工具不进入下拉，但明确提示数量', async () => {
    const api = createFakeApi({
      listGrantedMcpTools: vi.fn(async () =>
        pageOf([
          GRANTED_MCP_TOOL,
          {
            ...GRANTED_MCP_TOOL,
            id: 'mcp-tool-disabled',
            name: 'disabled_lookup',
            status: 'DISABLED' as const,
          },
        ]),
      ),
    });
    renderPage(api);

    expect(await screen.findByText(/有 1 个已授权工具当前被禁用/)).toBeVisible();
    expect(screen.getByRole('button', { name: /创建报告任务/ })).toBeEnabled();
    fireEvent.mouseDown(screen.getByRole('combobox', { name: '数据工具' }));
    await waitFor(() => {
      // 禁用工具被过滤，下拉只剩一个可选项；不依赖 antd 内部文本节点结构。
      expect(screen.getAllByRole('option')).toHaveLength(1);
    });
    expect(screen.queryByText(/disabled_lookup/)).not.toBeInTheDocument();
  });

  it('没有已授权工具时禁止创建', async () => {
    const api = createFakeApi({
      listGrantedMcpTools: vi.fn(async () => pageOf([])),
    });
    renderPage(api);

    expect(await screen.findByText('没有可用工具')).toBeVisible();
    expect(screen.getByRole('button', { name: /创建报告任务/ })).toBeDisabled();
  });

  it('无权限时展示 403 与重试', async () => {
    const api = createFakeApi({
      listResearchTasks: vi.fn(async () => {
        throw new ApiError('没有权限', { status: 403, code: 'RICCON-AUTH-403' });
      }),
    });
    renderPage(api);

    expect(await screen.findByText('没有访问权限')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    await waitFor(() => {
      expect(api.listResearchTasks).toHaveBeenCalledTimes(2);
    });
  });

  it('工作空间为空或加载失败时给出明确下一步', async () => {
    const emptyApi = createFakeApi({
      listWorkspaces: vi.fn(async () => pageOf([])),
    });
    const { unmount } = renderPage(emptyApi);
    expect(await screen.findByText('暂无可用工作空间')).toBeVisible();
    unmount();

    const errorApi = createFakeApi({
      listWorkspaces: vi.fn(async () => {
        throw new ApiError('服务不可用', { status: 500, code: 'RICCON-INTERNAL-500' });
      }),
    });
    renderPage(errorApi);
    expect(await screen.findByText('报告任务加载失败')).toBeVisible();
  });
});

/** 部门共享空间夹具。 */
const TEAM_WORKSPACE: Workspace = {
  id: 'workspace-team',
  kind: 'TEAM',
  name: '销售部项目空间',
  description: '销售部共享空间',
  ownerUserId: 'user-1',
  status: 'ACTIVE',
  createdAt: '2026-11-19T08:00:00.000Z',
  updatedAt: '2026-11-19T08:00:00.000Z',
};

describe('报告任务空间选择', () => {
  it('通过 workspaceId 打开部门空间并加载该空间报告', async () => {
    const api = createFakeApi({
      listWorkspaces: vi.fn(async () => pageOf([DEMO_WORKSPACE, TEAM_WORKSPACE])),
      listResearchTasks: vi.fn(async (workspaceId: string) =>
        workspaceId === TEAM_WORKSPACE.id
          ? pageOf([
              makeResearchTask({
                id: 'team-task',
                workspaceId: TEAM_WORKSPACE.id,
                topic: '销售部部门报告',
              }),
            ])
          : pageOf([]),
      ),
    });
    render(
      withProviders(
        <ResearchListPage api={api} initialWorkspaceId={TEAM_WORKSPACE.id} navigate={vi.fn()} />,
      ),
    );

    expect(await screen.findByRole('button', { name: '销售部部门报告' })).toBeVisible();
    expect(api.listResearchTasks).toHaveBeenCalledWith(TEAM_WORKSPACE.id);
  });

  it('可在研究页直接切换空间并加载目标空间任务', async () => {
    const api = createFakeApi({
      listWorkspaces: vi.fn(async () => pageOf([DEMO_WORKSPACE, TEAM_WORKSPACE])),
      listResearchTasks: vi.fn(async (workspaceId: string) =>
        workspaceId === TEAM_WORKSPACE.id
          ? pageOf([
              makeResearchTask({
                id: 'team-task',
                workspaceId: TEAM_WORKSPACE.id,
                topic: '销售部部门报告',
              }),
            ])
          : pageOf([]),
      ),
    });
    render(withProviders(<ResearchListPage api={api} navigate={vi.fn()} />));

    await screen.findByText('还没有报告任务；在左侧输入主题创建第一个任务。');
    fireEvent.mouseDown(screen.getByRole('combobox', { name: '切换工作空间' }));
    fireEvent.click(await screen.findByText('销售部项目空间'));

    await waitFor(() => {
      expect(api.listResearchTasks).toHaveBeenLastCalledWith(TEAM_WORKSPACE.id);
    });
    expect(await screen.findByRole('button', { name: '销售部部门报告' })).toBeVisible();
  });
});
