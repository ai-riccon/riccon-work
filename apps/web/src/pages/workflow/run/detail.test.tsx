// @vitest-environment jsdom
import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { SessionInfo } from '@/services/api/types';
import { authStore } from '@/services/auth/auth-store';
import { DEMO_SESSION } from '@/test/fake-api';
import { withProviders } from '@/test/render';
import WorkflowRunDetailRoute from './detail';
import type { WorkflowRunApi, WorkflowRunDetail } from './run-api';

/**
 * 运行详情深链路由测试。
 *
 * 验证动态段注入、`useParams` 读取与缺省情形；真实 Umi 路由注册与跨页跳转由
 * `config/routes.test.ts` 与 T16a 的 E2E 覆盖。
 */

/** 模拟 Umi 匹配 `/workflows/runs/:workflowRunId` 后提供的路由参数。 */
const routeParams = vi.hoisted(() => ({ workflowRunId: undefined as string | undefined }));

// 覆盖共享替身：生产环境由 Umi 按 `apps/web/config/routes.ts` 的动态段提供参数，
// 这里用同名 `workflowRunId` 验证路由入口确实从 `useParams` 读取运行 ID。
vi.mock('@umijs/max', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/test/umijs-max')>();
  return { ...actual, useParams: () => routeParams };
});

/** 有 workflow.read 的会话。 */
const READER_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  permissions: ['workflow.read'],
};

/** 运行详情夹具。 */
const DETAIL: WorkflowRunDetail = {
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
  nodes: [],
  approvals: [],
  citations: [],
  result: null,
};

/** 构造 API 替身。 */
function createApi(): WorkflowRunApi {
  return { getRunDetail: vi.fn(async () => DETAIL) };
}

describe('WorkflowRunDetailRoute', () => {
  beforeEach(() => {
    authStore.reset();
    authStore.setAuthenticated(READER_SESSION);
    // 每个用例从“路由未提供动态段”开始，需要时再显式设置。
    routeParams.workflowRunId = undefined;
  });

  it('把注入的动态段传给页面并加载详情', async () => {
    render(
      withProviders(
        <WorkflowRunDetailRoute
          api={createApi()}
          pollIntervalMs={60_000}
          sessionReader={{ getSession: async () => READER_SESSION }}
          workflowRunId="run-1"
        />,
      ),
    );

    expect(await screen.findByRole('heading', { name: '运行详情', level: 1 })).toBeVisible();
  });

  it('未注入运行 ID 时从 useParams 的 workflowRunId 读取并加载详情', async () => {
    routeParams.workflowRunId = 'run-from-params';
    const api = createApi();
    render(
      withProviders(
        <WorkflowRunDetailRoute
          api={api}
          pollIntervalMs={60_000}
          sessionReader={{ getSession: async () => READER_SESSION }}
        />,
      ),
    );

    expect(api.getRunDetail).toHaveBeenCalledWith('run-from-params');
    expect(await screen.findByRole('heading', { name: '运行详情', level: 1 })).toBeVisible();
  });

  it('缺少动态段时展示缺少运行 ID', async () => {
    render(
      withProviders(
        <WorkflowRunDetailRoute
          api={createApi()}
          pollIntervalMs={60_000}
          sessionReader={{ getSession: async () => READER_SESSION }}
        />,
      ),
    );

    expect(await screen.findByText('缺少运行 ID')).toBeVisible();
  });
});
