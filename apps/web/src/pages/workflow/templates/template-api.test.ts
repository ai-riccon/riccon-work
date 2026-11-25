import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import { authStore } from '@/services/auth/auth-store';
import { DEMO_SESSION } from '@/test/fake-api';
import { createWorkflowTemplateApi } from './template-api';

/**
 * 模板 API 客户端测试。
 *
 * 覆盖列表查询参数、复制路径/CSRF、CSRF 失效重试与错误归一化；页面测试注入替身，
 * 本测试确保默认真实客户端与契约 `1.6.0-m06` 一致。
 */

/** 构造 JSON 响应。 */
function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

/** 模板分页夹具。 */
const TEMPLATE_PAGE = {
  page: 1,
  size: 20,
  total: 1,
  totalPages: 1,
  items: [
    {
      id: '01990000-0000-7000-8000-000000000601',
      name: '市场调研报告',
      scenario: '市场调研',
      description: '检索知识库并生成带引用的报告',
      source: 'BUILT_IN',
      visibility: 'BUILT_IN',
      builtInVersion: 1,
      upgradeAvailable: false,
      updatedAt: '2026-11-25T08:00:00+08:00',
    },
  ],
};

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

describe('createWorkflowTemplateApi', () => {
  beforeEach(() => {
    authStore.reset();
  });

  it('列表请求带分页与名称/场景过滤，空过滤不发送', async () => {
    const { calls, fetchImpl } = createFetch(() => jsonResponse(TEMPLATE_PAGE));
    const api = createWorkflowTemplateApi({ baseUrl: 'http://api.test', fetchImpl });

    const result = await api.listTemplates({ name: ' 调研 ', scenario: '', page: 2, size: 10 });
    expect(result.total).toBe(1);
    expect(calls).toHaveLength(1);
    const url = new URL(calls[0].url);
    expect(url.pathname).toBe('/api/v1/workflow-templates');
    expect(url.searchParams.get('page')).toBe('2');
    expect(url.searchParams.get('size')).toBe('10');
    expect(url.searchParams.get('name')).toBe('调研');
    expect(url.searchParams.has('scenario')).toBe(false);

    await api.listTemplates();
    const defaults = new URL(calls[1].url);
    expect(defaults.searchParams.get('page')).toBe('1');
    expect(defaults.searchParams.get('size')).toBe('20');
    expect(defaults.searchParams.has('name')).toBe(false);
  });

  it('复制请求使用编码路径、CSRF 头与 JSON 请求体', async () => {
    const { calls, fetchImpl } = createFetch(() =>
      jsonResponse({ id: 'draft-1', name: '市场调研报告' }, 201),
    );
    const api = createWorkflowTemplateApi({
      baseUrl: 'http://api.test',
      fetchImpl,
      csrfToken: () => 'csrf-token-123456',
    });

    const result = await api.copyTemplate('template/with space', { name: '我的调研' });
    expect(result).toEqual({ id: 'draft-1', name: '市场调研报告' });
    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe(
      'http://api.test/api/v1/workflow-templates/template%2Fwith%20space/copy',
    );
    expect(calls[0].init?.method).toBe('POST');
    expect((calls[0].init?.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe(
      'csrf-token-123456',
    );
    expect(calls[0].init?.body).toBe(JSON.stringify({ name: '我的调研' }));
  });

  it('复制默认发送空对象；CSRF 失效时刷新令牌并重试一次', async () => {
    authStore.setAuthenticated({ ...DEMO_SESSION, csrfToken: 'stale-token' });
    let copyAttempts = 0;
    const { calls, fetchImpl } = createFetch((call) => {
      if (call.url.endsWith('/api/v1/auth/csrf')) {
        return jsonResponse({ headerName: 'X-CSRF-TOKEN', token: 'fresh-token' });
      }
      copyAttempts += 1;
      if (copyAttempts === 1) {
        return jsonResponse(
          { type: 'about:blank', title: 'Forbidden', status: 403, code: 'RICCON-AUTH-403' },
          403,
        );
      }
      return jsonResponse({ id: 'draft-2', name: '市场调研报告' }, 201);
    });
    const api = createWorkflowTemplateApi({ baseUrl: 'http://api.test', fetchImpl });

    const result = await api.copyTemplate('template-1');
    expect(result.id).toBe('draft-2');
    expect(calls.map((call) => new URL(call.url).pathname)).toEqual([
      '/api/v1/workflow-templates/template-1/copy',
      '/api/v1/auth/csrf',
      '/api/v1/workflow-templates/template-1/copy',
    ]);
    expect((calls[0].init?.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe('stale-token');
    expect((calls[2].init?.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe('fresh-token');
    expect(calls[0].init?.body).toBe('{}');
  });

  it('非 2xx 统一归一化为 ApiError，保留状态码与错误码', async () => {
    const { fetchImpl } = createFetch(() =>
      jsonResponse(
        { type: 'about:blank', title: 'Not Found', status: 404, code: 'RICCON-TENANT-404' },
        404,
      ),
    );
    const api = createWorkflowTemplateApi({ baseUrl: 'http://api.test', fetchImpl });

    await expect(api.listTemplates()).rejects.toMatchObject({
      status: 404,
      code: 'RICCON-TENANT-404',
    });
    await expect(api.copyTemplate('unknown')).rejects.toBeInstanceOf(ApiError);
  });
});
