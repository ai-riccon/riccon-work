// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import type { SessionInfo } from '@/services/api/types';
import { authStore } from '@/services/auth/auth-store';
import type { SessionReader } from '@/services/auth/auth-store';
import { DEMO_SESSION } from '@/test/fake-api';
import { withProviders } from '@/test/render';
import WorkflowRunDetailPage from './index';
import type { WorkflowRunApi, WorkflowRunDetail } from './run-api';

/**
 * 运行详情页面测试。
 *
 * 覆盖加载、成功、403/404/503/通用错误、缺少运行 ID、无权限、刷新与轮询（updatedAt 变化才重绘、
 * 终态停止、轮询失败保留数据、不可见后清空）；真实浏览器 E2E/axe/375px 归 T16a。
 */

/** 有 workflow.read 的会话。 */
const READER_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  roles: ['MEMBER'],
  permissions: ['workflow.read'],
};

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
    currentNodeId: 'start',
    resultStatus: 'NONE',
    createdAt: '2026-11-25T08:59:00+08:00',
    startedAt: '2026-11-25T09:00:00+08:00',
    updatedAt: '2026-11-25T09:00:02+08:00',
    pendingApprovalCount: 0,
    approvedApprovalCount: 0,
    rejectedApprovalCount: 0,
    retentionExpiresAt: '2027-05-24T09:00:00+08:00',
    nodes: [
      {
        id: 'node-run-start',
        nodeId: 'start',
        nodeType: 'START',
        name: '开始',
        status: 'SUCCEEDED',
        attempt: 1,
        startedAt: '2026-11-25T09:00:00+08:00',
        finishedAt: '2026-11-25T09:00:01+08:00',
        durationMs: 1000,
      },
    ],
    approvals: [],
    citations: [],
    result: null,
    ...overrides,
  };
}

/** 构造 API 错误。 */
function apiError(status: number, code: string, message = '请求失败。'): ApiError {
  return new ApiError(message, { status, code });
}

/**
 * 构造运行详情 API 替身。
 *
 * @param impl 首次与后续调用的实现
 * @returns API 与可断言替身
 */
function createFakeApi(impl: WorkflowRunApi['getRunDetail']) {
  const getRunDetail = vi.fn(impl);
  return { api: { getRunDetail } satisfies WorkflowRunApi, getRunDetail };
}

/** 会话读取替身；默认返回有读权限的会话。 */
function readerOf(session: SessionInfo = READER_SESSION): SessionReader {
  return { getSession: async () => session };
}

/** 渲染页面；默认关闭轮询避免干扰状态测试。 */
function renderPage(
  api: WorkflowRunApi,
  options: {
    runId?: string;
    session?: SessionInfo;
    sessionReader?: SessionReader;
    pollIntervalMs?: number;
    navigate?: (to: string) => void;
  } = {},
) {
  const {
    runId = 'run-1',
    session = READER_SESSION,
    sessionReader = readerOf(session),
    pollIntervalMs = 60_000,
    navigate = vi.fn(),
  } = options;
  return render(
    withProviders(
      <WorkflowRunDetailPage
        api={api}
        navigate={navigate}
        pollIntervalMs={pollIntervalMs}
        runId={runId}
        sessionReader={sessionReader}
      />,
    ),
  );
}

describe('WorkflowRunDetailPage', () => {
  beforeEach(() => {
    authStore.reset();
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1024 });
  });

  it('恢复会话后加载详情、聚焦标题并支持手动刷新', async () => {
    authStore.reset();
    const { api, getRunDetail } = createFakeApi(async () => makeDetail());
    const navigate = vi.fn();
    renderPage(api, { sessionReader: readerOf(), navigate });

    const heading = await screen.findByRole('heading', { name: '运行详情', level: 1 });
    await waitFor(() => expect(heading).toHaveFocus());
    expect(getRunDetail).toHaveBeenCalledTimes(1);
    expect(screen.getByText('市场调研流程')).toBeVisible();

    fireEvent.click(screen.getByRole('button', { name: '刷新运行详情' }));
    await waitFor(() => expect(getRunDetail).toHaveBeenCalledTimes(2));

    fireEvent.click(screen.getByRole('button', { name: '返回流程列表' }));
    expect(navigate).toHaveBeenCalledWith('/workflows/list');
  });

  it('轮询仅在 updatedAt 变化时重绘，终态后停止', async () => {
    authStore.setAuthenticated(READER_SESSION);
    const running = makeDetail();
    const progressed = makeDetail({
      updatedAt: '2026-11-25T09:00:04+08:00',
      nodes: [
        ...(running.nodes ?? []),
        {
          id: 'node-run-model',
          nodeId: 'model',
          nodeType: 'MODEL',
          name: '生成报告',
          status: 'RUNNING',
          attempt: 1,
          startedAt: '2026-11-25T09:00:03+08:00',
        },
      ],
      currentNodeId: 'model',
    });
    const succeeded = makeDetail({
      status: 'SUCCEEDED',
      updatedAt: '2026-11-25T09:00:06+08:00',
      currentNodeId: null,
      resultStatus: 'SAVED',
      result: {
        format: 'TEXT',
        text: '最终报告',
        sizeBytes: 12,
        generatedAt: '2026-11-25T09:00:06+08:00',
      },
    });
    let calls = 0;
    const { api, getRunDetail } = createFakeApi(async () => {
      calls += 1;
      if (calls === 1 || calls === 2) {
        return running;
      }
      if (calls === 3) {
        return progressed;
      }
      return succeeded;
    });

    renderPage(api, { pollIntervalMs: 10 });

    expect(await screen.findByTestId('workflow-run-result-text')).toHaveTextContent('最终报告');
    await waitFor(() => expect(getRunDetail).toHaveBeenCalledTimes(4));
    expect(screen.getAllByText('成功').length).toBeGreaterThanOrEqual(1);

    // 终态后不再安排下一次轮询。
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(getRunDetail).toHaveBeenCalledTimes(4);
  });

  it('轮询失败时保留已展示内容并提示自动重试', async () => {
    authStore.setAuthenticated(READER_SESSION);
    const running = makeDetail();
    const { api, getRunDetail } = createFakeApi(async () => running);
    getRunDetail
      .mockResolvedValueOnce(running)
      .mockRejectedValue(apiError(500, 'RICCON-INTERNAL-500', '内部错误。'));

    renderPage(api, { pollIntervalMs: 10 });

    expect(await screen.findByText('状态刷新失败')).toBeVisible();
    expect(screen.getByText('市场调研流程')).toBeVisible();
    await waitFor(() => expect(getRunDetail.mock.calls.length).toBeGreaterThanOrEqual(3));
    // 轮询持续失败时页面仍保留上次成功读取的内容。
    expect(screen.getByText('状态刷新失败')).toBeVisible();
    expect(screen.getByText('市场调研流程')).toBeVisible();
  });

  it('轮询遇到 404 时切换到不可见结果页，不继续展示旧内容', async () => {
    authStore.setAuthenticated(READER_SESSION);
    const running = makeDetail();
    const { api, getRunDetail } = createFakeApi(async () => running);
    getRunDetail
      .mockResolvedValueOnce(running)
      .mockRejectedValueOnce(apiError(404, 'RICCON-TENANT-404'));

    renderPage(api, { pollIntervalMs: 10 });

    expect(await screen.findByText('运行不存在或你无权查看')).toBeVisible();
    expect(screen.queryByText('市场调研流程')).toBeNull();
  });

  it('403 展示无权限说明', async () => {
    authStore.setAuthenticated(READER_SESSION);
    const { api } = createFakeApi(async () => {
      throw apiError(403, 'RICCON-AUTH-403');
    });

    renderPage(api);

    expect(await screen.findByText('没有访问权限')).toBeVisible();
    expect(screen.getByText(/workflow\.read/)).toBeVisible();
  });

  it('404 统一说明运行不存在或不可见', async () => {
    authStore.setAuthenticated(READER_SESSION);
    const { api } = createFakeApi(async () => {
      throw apiError(404, 'RICCON-TENANT-404');
    });

    renderPage(api);

    expect(await screen.findByText('运行不存在或你无权查看')).toBeVisible();
    expect(screen.getByText(/不提示对象是否存在/)).toBeVisible();
  });

  it('编排关闭 503 展示禁用态并允许重试', async () => {
    authStore.setAuthenticated(READER_SESSION);
    const { api, getRunDetail } = createFakeApi(async () => {
      throw apiError(503, 'RICCON-WF-503', '编排功能已关闭。');
    });

    renderPage(api);

    expect(await screen.findByText('编排功能已关闭')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '重试' }));
    await waitFor(() => expect(getRunDetail).toHaveBeenCalledTimes(2));
  });

  it('通用错误展示可重试的结果页', async () => {
    authStore.setAuthenticated(READER_SESSION);
    const { api, getRunDetail } = createFakeApi(async () => makeDetail());
    getRunDetail.mockRejectedValueOnce(apiError(500, 'RICCON-INTERNAL-500', '内部错误。'));

    renderPage(api);

    expect(await screen.findByText('运行详情加载失败')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '重试' }));
    expect(await screen.findByRole('heading', { name: '运行详情', level: 1 })).toBeVisible();
    expect(getRunDetail).toHaveBeenCalledTimes(2);
  });

  it('缺少 workflow.read 时直接 403，不调用详情接口', async () => {
    authStore.setAuthenticated({ ...READER_SESSION, permissions: [] });
    const { api, getRunDetail } = createFakeApi(async () => makeDetail());

    renderPage(api, { session: { ...READER_SESSION, permissions: [] } });

    expect(await screen.findByText('没有访问权限')).toBeVisible();
    expect(getRunDetail).not.toHaveBeenCalled();
  });

  it('缺少运行 ID 时展示 404 与返回入口', async () => {
    authStore.setAuthenticated(READER_SESSION);
    const { api, getRunDetail } = createFakeApi(async () => makeDetail());

    renderPage(api, { runId: '' });

    expect(await screen.findByText('缺少运行 ID')).toBeVisible();
    expect(getRunDetail).not.toHaveBeenCalled();
  });

  it('会话尚未恢复时保持加载骨架', async () => {
    const pending: SessionReader = { getSession: () => new Promise(() => undefined) };

    renderPage(createFakeApi(async () => makeDetail()).api, { sessionReader: pending });

    expect(screen.getByLabelText('正在加载运行详情')).toBeInTheDocument();
  });
});
