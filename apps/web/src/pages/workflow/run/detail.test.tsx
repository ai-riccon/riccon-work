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
 * 验证动态段注入与缺省情形；真实 Umi 路由注册不在 TASK-0232 写入范围，归后续路由接入任务。
 */

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
