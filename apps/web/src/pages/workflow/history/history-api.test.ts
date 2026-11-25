import { describe, expect, it, vi } from 'vitest';
import { createWorkflowHistoryApi } from './history-api';

/**
 * 运行历史/发布 API 客户端测试。
 *
 * 覆盖端点路径、过滤参数、CSRF/幂等键、202 空响应与 4xx/5xx 错误归一化；
 * 页面测试注入替身，本测试确保默认真实客户端与契约一致。
 */

/** 构造 JSON 响应。 */
function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

/** 构造空响应（202）。 */
function emptyResponse(status = 202): Response {
  return new Response(null, { status });
}

/** 捕获 fetch 调用。 */
function createFetch(handler: (url: string, init?: RequestInit) => Response) {
  const calls: Array<{ url: string; init?: RequestInit }> = [];
  const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, init });
    return handler(url, init);
  });
  return { calls, fetchImpl: fetchImpl as unknown as typeof fetch };
}

/** 读取请求头。 */
function headersOf(init?: RequestInit): Record<string, string> {
  return (init?.headers ?? {}) as Record<string, string>;
}

describe('createWorkflowHistoryApi', () => {
  it('运行历史 GET 只发送读请求头，过滤参数编码完整', async () => {
    const { calls, fetchImpl } = createFetch(() =>
      jsonResponse({ page: 2, size: 20, total: 1, totalPages: 1, items: [] }),
    );
    const api = createWorkflowHistoryApi({
      baseUrl: 'https://example.test/',
      fetchImpl,
      csrfToken: () => 'csrf-token',
    });

    await api.listRuns({
      workflowId: 'workflow-1',
      versionNo: 2,
      status: 'FAILED',
      requesterUserId: 'user-1',
      from: '2026-11-25T09:00:00+08:00',
      to: '2026-11-25T10:00:00+08:00',
      page: 2,
      size: 20,
    });

    expect(calls).toHaveLength(1);
    const url = new URL(calls[0].url);
    expect(url.pathname).toBe('/api/v1/workflow-runs');
    expect(url.searchParams.get('workflowId')).toBe('workflow-1');
    expect(url.searchParams.get('versionNo')).toBe('2');
    expect(url.searchParams.get('status')).toBe('FAILED');
    expect(url.searchParams.get('requesterUserId')).toBe('user-1');
    expect(url.searchParams.get('from')).toBe('2026-11-25T09:00:00+08:00');
    expect(url.searchParams.get('to')).toBe('2026-11-25T10:00:00+08:00');
    expect(url.searchParams.get('page')).toBe('2');
    expect(url.searchParams.get('size')).toBe('20');
    expect(calls[0].init?.method).toBe('GET');
    expect(calls[0].init?.credentials).toBe('same-origin');
    expect(headersOf(calls[0].init)).toEqual({ Accept: 'application/json' });
  });

  it('省略过滤参数时只发送分页默认值', async () => {
    const { calls, fetchImpl } = createFetch(() =>
      jsonResponse({ page: 1, size: 20, total: 0, totalPages: 0, items: [] }),
    );
    const api = createWorkflowHistoryApi({
      baseUrl: 'https://example.test',
      fetchImpl,
      csrfToken: () => 'csrf-token',
    });

    await api.listRuns();

    const url = new URL(calls[0].url);
    expect(url.searchParams.get('page')).toBe('1');
    expect(url.searchParams.get('size')).toBe('20');
    expect(url.searchParams.has('status')).toBe(false);
  });

  it('读取运行详情并编码 ID', async () => {
    const { calls, fetchImpl } = createFetch(() => jsonResponse({ id: 'run-1', status: 'FAILED' }));
    const api = createWorkflowHistoryApi({
      baseUrl: 'https://example.test',
      fetchImpl,
      csrfToken: () => 'csrf-token',
    });

    await api.getRunDetail('run/../other');

    expect(calls[0].url).toBe('https://example.test/api/v1/workflow-runs/run%2F..%2Fother');
    expect(calls[0].init?.method).toBe('GET');
    expect(headersOf(calls[0].init)['X-CSRF-TOKEN']).toBeUndefined();
  });

  it('取消运行发送 CSRF 与幂等键并接受 202 空响应', async () => {
    const { calls, fetchImpl } = createFetch(() => emptyResponse(202));
    const api = createWorkflowHistoryApi({
      baseUrl: 'https://example.test',
      fetchImpl,
      csrfToken: () => 'csrf-token',
      idempotencyKey: () => 'cancel-key-123456',
    });

    await expect(api.cancelRun('run-1')).resolves.toBeUndefined();

    expect(calls[0].url).toBe('https://example.test/api/v1/workflow-runs/run-1/cancel');
    expect(calls[0].init?.method).toBe('POST');
    expect(headersOf(calls[0].init)['X-CSRF-TOKEN']).toBe('csrf-token');
    expect(headersOf(calls[0].init)['Idempotency-Key']).toBe('cancel-key-123456');
    expect(calls[0].init?.body).toBeUndefined();
  });

  it('重试失败节点提交 nodeId 并保留 409 错误', async () => {
    const conflict = createFetch(() =>
      jsonResponse(
        {
          type: 'about:blank',
          title: 'Conflict',
          status: 409,
          detail: 'run is not in FAILED status',
          code: 'RICCON-WF-409',
        },
        409,
      ),
    );
    const api = createWorkflowHistoryApi({
      baseUrl: 'https://example.test',
      fetchImpl: conflict.fetchImpl,
      csrfToken: () => 'csrf-token',
      idempotencyKey: () => 'retry-key-123456',
    });

    await expect(api.retryRunNode('run-1', 'model-1')).rejects.toMatchObject({
      status: 409,
      code: 'RICCON-WF-409',
    });
    expect(conflict.calls[0].url).toBe('https://example.test/api/v1/workflow-runs/run-1/retry');
    expect(conflict.calls[0].init?.method).toBe('POST');
    expect(JSON.parse(String(conflict.calls[0].init?.body))).toEqual({ nodeId: 'model-1' });
    expect(headersOf(conflict.calls[0].init)['Idempotency-Key']).toBe('retry-key-123456');
  });

  it('成功重试接受 202 空响应', async () => {
    const { fetchImpl } = createFetch(() => emptyResponse());
    const api = createWorkflowHistoryApi({
      baseUrl: 'https://example.test',
      fetchImpl,
      csrfToken: () => 'csrf-token',
    });
    await expect(api.retryRunNode('run-1', 'model-1')).resolves.toBeUndefined();
  });

  it('流程列表按视图与状态过滤', async () => {
    const { calls, fetchImpl } = createFetch(() =>
      jsonResponse({ page: 1, size: 100, total: 0, totalPages: 0, items: [] }),
    );
    const api = createWorkflowHistoryApi({
      baseUrl: 'https://example.test',
      fetchImpl,
      csrfToken: () => 'csrf-token',
    });

    await api.listWorkflows({ view: 'RUNNABLE', status: 'ACTIVE', page: 1, size: 50 });

    const url = new URL(calls[0].url);
    expect(url.pathname).toBe('/api/v1/workflows');
    expect(url.searchParams.get('view')).toBe('RUNNABLE');
    expect(url.searchParams.get('status')).toBe('ACTIVE');
    expect(url.searchParams.get('size')).toBe('50');
  });

  it('读取流程详情与版本列表', async () => {
    const { calls, fetchImpl } = createFetch(() =>
      jsonResponse({ page: 1, size: 100, total: 0, totalPages: 0, items: [] }),
    );
    const api = createWorkflowHistoryApi({
      baseUrl: 'https://example.test',
      fetchImpl,
      csrfToken: () => 'csrf-token',
    });

    await api.getWorkflow('workflow-1');
    await api.listVersions('workflow-1', { page: 1, size: 100 });

    expect(calls[0].url).toBe('https://example.test/api/v1/workflows/workflow-1');
    const versionUrl = new URL(calls[1].url);
    expect(versionUrl.pathname).toBe('/api/v1/workflows/workflow-1/versions');
    expect(versionUrl.searchParams.get('size')).toBe('100');
  });

  it('发布仅自己范围时不发送 departmentId，部门范围时发送', async () => {
    const { calls, fetchImpl } = createFetch(() => jsonResponse({ id: 'version-1' }, 201));
    const api = createWorkflowHistoryApi({
      baseUrl: 'https://example.test',
      fetchImpl,
      csrfToken: () => 'csrf-token',
      idempotencyKey: () => 'publish-key-123456',
    });

    await api.publishWorkflow('workflow-1', { runScope: 'ONLY_SELF' });
    await api.publishWorkflow('workflow-1', {
      runScope: 'DEPARTMENT',
      departmentId: 'dept-1',
    });

    expect(JSON.parse(String(calls[0].init?.body))).toEqual({ runScope: 'ONLY_SELF' });
    expect(JSON.parse(String(calls[1].init?.body))).toEqual({
      runScope: 'DEPARTMENT',
      departmentId: 'dept-1',
    });
    expect(headersOf(calls[1].init)['Idempotency-Key']).toBe('publish-key-123456');
  });

  it('发布失败保留 400 与 503 状态', async () => {
    const bad = createFetch(() =>
      jsonResponse(
        {
          type: 'about:blank',
          title: 'Bad Request',
          status: 400,
          detail: 'workflow validation failed',
          code: 'RICCON-WF-400',
        },
        400,
      ),
    );
    const api = createWorkflowHistoryApi({
      baseUrl: 'https://example.test',
      fetchImpl: bad.fetchImpl,
      csrfToken: () => 'csrf-token',
    });
    await expect(
      api.publishWorkflow('workflow-1', { runScope: 'ONLY_SELF' }),
    ).rejects.toMatchObject({ status: 400, code: 'RICCON-WF-400' });

    const unavailable = createFetch(() =>
      jsonResponse(
        {
          type: 'about:blank',
          title: 'Service Unavailable',
          status: 503,
          detail: 'disabled',
          code: 'RICCON-WF-503',
        },
        503,
      ),
    );
    const disabledApi = createWorkflowHistoryApi({
      baseUrl: 'https://example.test',
      fetchImpl: unavailable.fetchImpl,
      csrfToken: () => 'csrf-token',
    });
    await expect(
      disabledApi.publishWorkflow('workflow-1', { runScope: 'ONLY_SELF' }),
    ).rejects.toMatchObject({ status: 503, code: 'RICCON-WF-503' });
  });

  it('停用与启用使用 POST', async () => {
    const { calls, fetchImpl } = createFetch(() => jsonResponse({ id: 'workflow-1' }));
    const api = createWorkflowHistoryApi({
      baseUrl: 'https://example.test',
      fetchImpl,
      csrfToken: () => 'csrf-token',
    });

    await api.disableWorkflow('workflow-1');
    await api.enableWorkflow('workflow-1');

    expect(calls[0].url).toBe('https://example.test/api/v1/workflows/workflow-1/disable');
    expect(calls[1].url).toBe('https://example.test/api/v1/workflows/workflow-1/enable');
    expect(calls[0].init?.method).toBe('POST');
    expect(calls[1].init?.method).toBe('POST');
  });

  it('创建运行发送版本、输入与幂等键', async () => {
    const { calls, fetchImpl } = createFetch(() =>
      jsonResponse({ id: 'run-1', status: 'PENDING' }, 201),
    );
    const api = createWorkflowHistoryApi({
      baseUrl: 'https://example.test',
      fetchImpl,
      csrfToken: () => 'csrf-token',
      idempotencyKey: () => 'run-key-123456',
    });

    await expect(
      api.createRun('workflow-1', { versionId: 'version-2', inputs: { topic: '市场' } }),
    ).resolves.toMatchObject({ id: 'run-1' });

    expect(calls[0].url).toBe('https://example.test/api/v1/workflows/workflow-1/runs');
    expect(calls[0].init?.method).toBe('POST');
    expect(JSON.parse(String(calls[0].init?.body))).toEqual({
      versionId: 'version-2',
      inputs: { topic: '市场' },
    });
    expect(headersOf(calls[0].init)['Idempotency-Key']).toBe('run-key-123456');
  });

  it('读取部门目录', async () => {
    const { calls, fetchImpl } = createFetch(() =>
      jsonResponse({ page: 1, size: 100, total: 0, totalPages: 0, items: [] }),
    );
    const api = createWorkflowHistoryApi({
      baseUrl: 'https://example.test',
      fetchImpl,
      csrfToken: () => 'csrf-token',
    });

    await api.listDepartments();
    expect(calls[0].url).toBe('https://example.test/api/v1/departments');
  });

  it('403 使用统一错误码，客户端刷新令牌后重试一次', async () => {
    let attempt = 0;
    const retry = createFetch(() => {
      attempt += 1;
      if (attempt === 1) {
        return jsonResponse({ code: 'RICCON-AUTH-403', status: 403 }, 403);
      }
      return jsonResponse({ id: 'version-1' }, 201);
    });
    let tokenCalls = 0;
    const retryApi = createWorkflowHistoryApi({
      baseUrl: 'https://example.test',
      fetchImpl: retry.fetchImpl,
      csrfToken: () => {
        tokenCalls += 1;
        return `token-${tokenCalls}`;
      },
    });
    await expect(
      retryApi.publishWorkflow('workflow-1', { runScope: 'ONLY_SELF' }),
    ).resolves.toMatchObject({ id: 'version-1' });
    expect(retry.calls).toHaveLength(2);
    expect(tokenCalls).toBe(2);

    const denied = createFetch(() => jsonResponse({ code: 'RICCON-AUTH-403', status: 403 }, 403));
    const deniedApi = createWorkflowHistoryApi({
      baseUrl: 'https://example.test',
      fetchImpl: denied.fetchImpl,
      csrfToken: () => 'csrf-token',
    });
    await expect(
      deniedApi.publishWorkflow('workflow-1', { runScope: 'ONLY_SELF' }),
    ).rejects.toMatchObject({ status: 403, code: 'RICCON-AUTH-403' });
    expect(denied.calls).toHaveLength(2);
  });
});
