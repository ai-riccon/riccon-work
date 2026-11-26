import { describe, expect, it, vi } from 'vitest';
import type { WorkflowRuntimeLimits } from './runtime-limits-api';
import { createWorkflowRuntimeLimitsApi } from './runtime-limits-api';

/**
 * 运行限制 API 客户端测试。
 *
 * 覆盖端点路径、只读请求头（GET、不发送 CSRF）、AbortSignal 透传与 403 错误归一化；
 * 页面测试注入替身，本测试确保默认真实客户端与 M06 契约一致。
 */

/** 运行限制夹具。 */
const LIMITS: WorkflowRuntimeLimits = {
  featureEnabled: true,
  disabledReason: null,
  maxNodes: 50,
  maxSteps: 200,
  nodeTimeoutSeconds: { model: 300, retrieval: 120, mcp: 60 },
  runTimeoutSeconds: 1800,
  tenantConcurrency: 10,
  workflowConcurrency: 5,
  resultMaxBytes: 5 * 1024 * 1024,
  retentionDays: 180,
  retentionPolicy: 'DEPLOYMENT_DEFAULT',
  generatedAt: '2026-11-26T10:30:00+08:00',
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
 * @param response 返回值
 * @returns fetch 替身与调用记录
 */
function createFetch(response: Response) {
  const calls: Array<{ url: string; init?: RequestInit }> = [];
  const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    calls.push({ url: String(input), init });
    return response;
  });
  return { calls, fetchImpl: fetchImpl as unknown as typeof fetch };
}

describe('createWorkflowRuntimeLimitsApi', () => {
  it('读取运行限制并传递 AbortSignal，不发送 CSRF 令牌', async () => {
    const controller = new AbortController();
    const { calls, fetchImpl } = createFetch(jsonResponse(LIMITS));
    const api = createWorkflowRuntimeLimitsApi({
      baseUrl: 'https://example.test/',
      fetchImpl,
    });

    await expect(api.getRuntimeLimits({ signal: controller.signal })).resolves.toMatchObject({
      maxNodes: 50,
      maxSteps: 200,
      tenantConcurrency: 10,
      resultMaxBytes: 5 * 1024 * 1024,
    });

    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe('https://example.test/api/v1/workflows/runtime-limits');
    expect(calls[0].init?.method).toBe('GET');
    expect(calls[0].init?.credentials).toBe('same-origin');
    expect(calls[0].init?.signal).toBe(controller.signal);
    expect(calls[0].init?.headers).toEqual({ Accept: 'application/json' });
  });

  it('403 保留状态码与稳定错误码', async () => {
    const { fetchImpl } = createFetch(
      jsonResponse(
        {
          type: 'about:blank',
          title: 'Forbidden',
          status: 403,
          detail: '没有访问权限。',
          code: 'RICCON-AUTH-403',
          traceId: 'trace-1',
        },
        403,
      ),
    );
    const api = createWorkflowRuntimeLimitsApi({ baseUrl: 'https://example.test', fetchImpl });

    await expect(api.getRuntimeLimits()).rejects.toMatchObject({
      status: 403,
      code: 'RICCON-AUTH-403',
    });
  });
});
