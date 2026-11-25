import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import type { WorkflowRunDetail } from './run-api';
import { createWorkflowRunApi } from './run-api';

/**
 * 运行详情 API 客户端测试。
 *
 * 覆盖端点路径、只读请求头（不发送 CSRF）、AbortSignal 透传与 4xx/5xx 错误归一化；
 * 页面测试注入替身，本测试确保默认真实客户端与契约一致。
 */

/** 运行详情夹具。 */
const DETAIL: WorkflowRunDetail = {
  id: 'run-1',
  workflowId: 'workflow-1',
  workflowName: '市场调研流程',
  workflowVersionId: 'version-1',
  versionNo: 2,
  requesterUserId: 'user-1',
  status: 'RUNNING',
  resultStatus: 'NONE',
  createdAt: '2026-11-25T08:59:00+08:00',
  updatedAt: '2026-11-25T09:00:02+08:00',
  retentionExpiresAt: '2027-05-24T09:00:00+08:00',
  pendingApprovalCount: 1,
  approvedApprovalCount: 0,
  rejectedApprovalCount: 0,
  nodes: [],
  approvals: [],
  citations: [],
  result: null,
};

/** 构造 JSON 响应。 */
function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

/**
 * 创建捕获 fetch。
 *
 * @param response 返回值或异常
 * @returns fetch 替身与调用记录
 */
function createFetch(response: Response | Error) {
  const calls: Array<{ url: string; init?: RequestInit }> = [];
  const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    calls.push({ url: String(input), init });
    if (response instanceof Error) {
      throw response;
    }
    return response;
  });
  return { calls, fetchImpl: fetchImpl as unknown as typeof fetch };
}

describe('createWorkflowRunApi', () => {
  it('读取运行详情并传递 AbortSignal，不发送 CSRF 令牌', async () => {
    const controller = new AbortController();
    const { calls, fetchImpl } = createFetch(jsonResponse(DETAIL));
    const api = createWorkflowRunApi({ baseUrl: 'https://example.test/', fetchImpl });

    await expect(api.getRunDetail('run-1', { signal: controller.signal })).resolves.toMatchObject({
      id: 'run-1',
      versionNo: 2,
    });

    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe('https://example.test/api/v1/workflow-runs/run-1');
    expect(calls[0].init?.method).toBe('GET');
    expect(calls[0].init?.credentials).toBe('same-origin');
    expect(calls[0].init?.signal).toBe(controller.signal);
    expect(calls[0].init?.headers).toEqual({ Accept: 'application/json' });
  });

  it('编码运行 ID，避免路径注入', async () => {
    const { calls, fetchImpl } = createFetch(jsonResponse(DETAIL));
    const api = createWorkflowRunApi({ baseUrl: 'https://example.test', fetchImpl });

    await api.getRunDetail('run/../other');

    expect(calls[0].url).toBe('https://example.test/api/v1/workflow-runs/run%2F..%2Fother');
  });

  it('保留 403/404 状态与稳定错误码', async () => {
    const forbidden = createFetch(
      jsonResponse(
        {
          type: 'about:blank',
          title: 'Forbidden',
          status: 403,
          detail: '没有访问权限。',
          code: 'RICCON-AUTH-403',
        },
        403,
      ),
    );
    const forbiddenApi = createWorkflowRunApi({
      baseUrl: 'https://example.test',
      fetchImpl: forbidden.fetchImpl,
    });
    await expect(forbiddenApi.getRunDetail('run-1')).rejects.toMatchObject({
      status: 403,
      code: 'RICCON-AUTH-403',
    });

    const notFound = createFetch(
      jsonResponse(
        {
          type: 'about:blank',
          title: 'Not Found',
          status: 404,
          detail: '资源不存在。',
          code: 'RICCON-TENANT-404',
        },
        404,
      ),
    );
    const notFoundApi = createWorkflowRunApi({
      baseUrl: 'https://example.test',
      fetchImpl: notFound.fetchImpl,
    });
    await expect(notFoundApi.getRunDetail('run-1')).rejects.toMatchObject({
      status: 404,
      code: 'RICCON-TENANT-404',
    });
  });

  it('把 503 归一为可重试的 ApiError', async () => {
    const { fetchImpl } = createFetch(
      jsonResponse(
        {
          type: 'about:blank',
          title: 'Service Unavailable',
          status: 503,
          detail: '编排功能已关闭。',
          code: 'RICCON-WF-503',
        },
        503,
      ),
    );
    const api = createWorkflowRunApi({ baseUrl: 'https://example.test', fetchImpl });

    try {
      await api.getRunDetail('run-1');
      throw new Error('应当抛出 ApiError');
    } catch (error) {
      expect(error).toBeInstanceOf(ApiError);
      const apiError = error as ApiError;
      expect(apiError.status).toBe(503);
      expect(apiError.code).toBe('RICCON-WF-503');
      expect(apiError.message).toBe('编排功能已关闭。');
      expect(apiError.retryable).toBe(true);
    }
  });
});
