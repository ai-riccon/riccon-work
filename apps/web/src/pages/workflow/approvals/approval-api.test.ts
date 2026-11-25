import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import { authStore } from '@/services/auth/auth-store';
import { DEMO_SESSION } from '@/test/fake-api';
import type { WorkflowApproval } from './approval-api';
import { createWorkflowApprovalApi } from './approval-api';

/**
 * 审批 API 客户端测试。
 *
 * 覆盖待办查询参数、详情路径、决定请求体/CSRF/幂等键、CSRF 引导与错误归一化；
 * 特别锁定“决定 403 不自动重试”，避免自批拒绝被重复提交并写两条 DENIED 审计。
 */

/** 构造 JSON 响应。 */
function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

/** 审批夹具。 */
function makeApproval(overrides: Partial<WorkflowApproval> = {}): WorkflowApproval {
  return {
    id: 'approval-1',
    workflowRunId: 'run-1',
    workflowId: 'workflow-1',
    workflowName: '市场调研流程',
    nodeId: 'approval-1',
    nodeName: '人工审批',
    status: 'PENDING',
    assigneeType: 'USER',
    assigneeUserIds: ['approver-1'],
    actionKind: 'MCP_TOOL',
    dataScopeSummary: { runScope: 'TENANT', departmentId: null, documentReferenceCount: 1 },
    targetToolName: 'market_data',
    planSha256: 'a'.repeat(64),
    requestedBy: 'requester-1',
    createdAt: '2026-11-25T08:00:00+08:00',
    ...overrides,
  };
}

/** 捕获调用。 */
interface CapturedCall {
  url: string;
  init?: RequestInit;
}

/**
 * 创建捕获 fetch。
 *
 * @param handler 响应处理器
 * @returns fetch 替身与调用记录
 */
function createFetch(handler: (call: CapturedCall) => Response | Promise<Response>) {
  const calls: CapturedCall[] = [];
  const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const call = { url: String(input), init };
    calls.push(call);
    return handler(call);
  });
  return { calls, fetchImpl: fetchImpl as unknown as typeof fetch };
}

describe('createWorkflowApprovalApi', () => {
  beforeEach(() => {
    authStore.reset();
  });

  it('待办请求带状态与分页参数，并使用同源地址', async () => {
    const { calls, fetchImpl } = createFetch(() =>
      jsonResponse({ page: 1, size: 20, total: 0, totalPages: 0, items: [] }),
    );
    const api = createWorkflowApprovalApi({
      baseUrl: 'https://api.test',
      fetchImpl,
      csrfToken: () => 'csrf',
    });

    await api.listApprovals({ status: 'PENDING', page: 2, size: 10 });

    expect(calls[0].url).toBe(
      'https://api.test/api/v1/workflow-approvals?page=2&size=10&status=PENDING',
    );
    expect(calls[0].init?.method).toBe('GET');
    expect(calls[0].init?.headers).toEqual({ Accept: 'application/json' });
  });

  it('详情路径对审批 ID 编码', async () => {
    const { calls, fetchImpl } = createFetch(() => jsonResponse(makeApproval()));
    const api = createWorkflowApprovalApi({
      baseUrl: 'https://api.test',
      fetchImpl,
      csrfToken: () => 'csrf',
    });

    await api.getApproval('approval 1/2');

    expect(calls[0].url).toBe('https://api.test/api/v1/workflow-approvals/approval%201%2F2');
  });

  it('决定请求发送 CSRF、幂等键与请求体', async () => {
    authStore.setAuthenticated(DEMO_SESSION);
    const { calls, fetchImpl } = createFetch(() => jsonResponse(makeApproval()));
    const api = createWorkflowApprovalApi({
      baseUrl: 'https://api.test',
      fetchImpl,
      idempotencyKey: () => 'idem-key-12345678',
    });

    await api.decideApproval('approval-1', { decision: 'REJECT', comment: '数据来源不可信' });

    expect(calls[0].url).toBe('https://api.test/api/v1/workflow-approvals/approval-1/decision');
    expect(calls[0].init?.method).toBe('POST');
    expect(calls[0].init?.headers).toMatchObject({
      'X-CSRF-TOKEN': DEMO_SESSION.csrfToken,
      'Idempotency-Key': 'idem-key-12345678',
      'Content-Type': 'application/json',
    });
    expect(JSON.parse(String(calls[0].init?.body))).toEqual({
      decision: 'REJECT',
      comment: '数据来源不可信',
    });
  });

  it('会话缺少 CSRF 时先引导令牌，再提交决定', async () => {
    const { calls, fetchImpl } = createFetch((call) => {
      if (call.url.endsWith('/api/v1/auth/csrf')) {
        return jsonResponse({ headerName: 'X-CSRF-TOKEN', token: 'bootstrapped-token' });
      }
      return jsonResponse(makeApproval());
    });
    const api = createWorkflowApprovalApi({
      baseUrl: 'https://api.test',
      fetchImpl,
      idempotencyKey: () => 'idem-key-12345678',
    });

    await api.decideApproval('approval-1', { decision: 'APPROVE' });

    expect(calls).toHaveLength(2);
    expect(calls[0].url).toBe('https://api.test/api/v1/auth/csrf');
    expect(calls[0].init?.method).toBe('GET');
    expect(calls[1].init?.headers).toMatchObject({ 'X-CSRF-TOKEN': 'bootstrapped-token' });
  });

  it('决定 403 不自动重试，原样抛出业务错误', async () => {
    authStore.setAuthenticated(DEMO_SESSION);
    const { calls, fetchImpl } = createFetch(() =>
      jsonResponse(
        {
          type: 'about:blank',
          title: 'Forbidden',
          status: 403,
          code: 'RICCON-AUTH-403',
          detail: 'requester cannot approve own run',
        },
        403,
      ),
    );
    const api = createWorkflowApprovalApi({ baseUrl: 'https://api.test', fetchImpl });

    const error = await api
      .decideApproval('approval-1', { decision: 'APPROVE' })
      .catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 403, code: 'RICCON-AUTH-403' });
    expect(calls).toHaveLength(1);
  });

  it('并发决定 409 透传状态码与错误码', async () => {
    authStore.setAuthenticated(DEMO_SESSION);
    const { fetchImpl } = createFetch(() =>
      jsonResponse(
        {
          type: 'about:blank',
          title: 'Conflict',
          status: 409,
          code: 'RICCON-WF-409',
          detail: 'workflow approval is already decided',
        },
        409,
      ),
    );
    const api = createWorkflowApprovalApi({ baseUrl: 'https://api.test', fetchImpl });

    const error = await api
      .decideApproval('approval-1', { decision: 'APPROVE' })
      .catch((caught: unknown) => caught);

    expect(error).toMatchObject({ status: 409, code: 'RICCON-WF-409' });
  });

  it('CSRF 引导失败时返回归一化错误', async () => {
    const { fetchImpl } = createFetch(() =>
      jsonResponse(
        { type: 'about:blank', title: 'Unauthorized', status: 401, code: 'RICCON-AUTH-401' },
        401,
      ),
    );
    const api = createWorkflowApprovalApi({ baseUrl: 'https://api.test', fetchImpl });

    const error = await api
      .decideApproval('approval-1', { decision: 'APPROVE' })
      .catch((caught: unknown) => caught);

    expect(error).toMatchObject({ status: 401, code: 'RICCON-AUTH-401' });
  });
});
