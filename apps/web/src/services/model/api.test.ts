import { describe, expect, it, vi } from 'vitest';
import { ApiClient } from '@/services/api/client';
import { DEMO_SESSION } from '@/test/fake-api';

/** 记录 fetch 调用。 */
interface FetchCall {
  url: string;
  init: RequestInit;
}

/**
 * 构造可控 fetch 替身。
 *
 * @param handler 按 URL 与方法返回响应
 * @returns fetch 函数与调用记录
 */
function createFetch(handler: (url: string, init: RequestInit) => Response | Promise<Response>) {
  const calls: FetchCall[] = [];
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url =
      typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
    const requestInit = init ?? {};
    calls.push({ url, init: requestInit });
    return handler(url, requestInit);
  });
  return { fn: fn as unknown as typeof fetch, calls };
}

/**
 * 构造 JSON 响应。
 *
 * @param data 响应体
 * @param status 状态码
 * @returns Response
 */
function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** 从调用中读取 JSON 请求体。 */
function bodyOf(call: FetchCall): unknown {
  return call.init.body ? JSON.parse(String(call.init.body)) : undefined;
}

describe('模型网关 API 客户端', () => {
  it('三个模型端点使用合同路径与只读 GET', async () => {
    const { fn, calls } = createFetch((url) => {
      if (url.endsWith('/auth/session')) {
        return json(DEMO_SESSION);
      }
      return json({ page: 1, size: 100, total: 0, totalPages: 0, items: [] });
    });
    const client = new ApiClient({ fetchImpl: fn });
    await client.getSession();

    await client.listEmployeeModels();
    await client.listAdminModels();
    await client.listModelProviders();

    expect(calls.map((call) => call.url)).toEqual(
      expect.arrayContaining([
        '/api/v1/models?page=1&size=100',
        '/api/v1/admin/models?page=1&size=100',
        '/api/v1/admin/model-providers?page=1&size=100',
      ]),
    );
    for (const call of calls.slice(1)) {
      expect(call.init.method).toBe('GET');
    }
  });

  it('启停模型使用 PATCH 且只提交 status', async () => {
    const { fn, calls } = createFetch((url) => {
      if (url.endsWith('/auth/session')) {
        return json(DEMO_SESSION);
      }
      return json({ id: 'model-1', status: 'DISABLED' });
    });
    const client = new ApiClient({ fetchImpl: fn });
    await client.getSession();

    await client.updateModelStatus('model-1', 'DISABLED');

    const call = calls.at(-1);
    expect(call?.url).toBe('/api/v1/admin/models/model-1');
    expect(call?.init.method).toBe('PATCH');
    expect(bodyOf(call!)).toEqual({ status: 'DISABLED' });
    expect((call?.init.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe(
      DEMO_SESSION.csrfToken,
    );
  });

  it('路由读取与整链保存携带版本与有序模型 ID', async () => {
    const { fn, calls } = createFetch((url) => {
      if (url.endsWith('/auth/session')) {
        return json(DEMO_SESSION);
      }
      return json({
        purpose: 'CHAT',
        version: 4,
        entries: [],
        updatedAt: '2026-11-20T10:00:00+08:00',
      });
    });
    const client = new ApiClient({ fetchImpl: fn });
    await client.getSession();

    await client.getModelRouting();
    expect(calls.at(-1)?.url).toBe('/api/v1/admin/model-routing');

    await client.updateModelRouting('CHAT', { version: 3, modelIds: ['m1', 'm2'] });
    const call = calls.at(-1);
    expect(call?.url).toBe('/api/v1/admin/model-routing/CHAT');
    expect(call?.init.method).toBe('PUT');
    expect(bodyOf(call!)).toEqual({ version: 3, modelIds: ['m1', 'm2'] });
  });

  it('key 列表、新增、轮换、停用与恢复使用合同路径', async () => {
    const { fn, calls } = createFetch((url) => {
      if (url.endsWith('/auth/session')) {
        return json(DEMO_SESSION);
      }
      return json({ id: 'cred-1', label: '主 key', maskedHint: '****a1b2', status: 'ACTIVE' });
    });
    const client = new ApiClient({ fetchImpl: fn });
    await client.getSession();

    await client.listModelCredentials('provider-1');
    expect(calls.at(-1)?.url).toBe(
      '/api/v1/admin/model-providers/provider-1/credentials?page=1&size=100',
    );

    await client.createModelCredential('provider-1', {
      label: '主 key',
      apiKey: 'test-key-1234567890',
      priority: 10,
    });
    const createCall = calls.at(-1);
    expect(createCall?.url).toBe('/api/v1/admin/model-providers/provider-1/credentials');
    expect(createCall?.init.method).toBe('POST');
    expect(bodyOf(createCall!)).toEqual({
      label: '主 key',
      apiKey: 'test-key-1234567890',
      priority: 10,
    });

    await client.rotateModelCredential('provider-1', 'cred-1', {
      apiKey: 'test-key-0987654321',
      label: '轮换后',
    });
    const rotateCall = calls.at(-1);
    expect(rotateCall?.url).toBe(
      '/api/v1/admin/model-providers/provider-1/credentials/cred-1/rotate',
    );
    expect(rotateCall?.init.method).toBe('POST');
    expect(bodyOf(rotateCall!)).toEqual({ apiKey: 'test-key-0987654321', label: '轮换后' });

    await client.disableModelCredential('provider-1', 'cred-1');
    expect(calls.at(-1)?.url).toBe(
      '/api/v1/admin/model-providers/provider-1/credentials/cred-1/disable',
    );
    expect(calls.at(-1)?.init.method).toBe('POST');

    await client.enableModelCredential('provider-1', 'cred-1');
    expect(calls.at(-1)?.url).toBe(
      '/api/v1/admin/model-providers/provider-1/credentials/cred-1/enable',
    );
    expect(calls.at(-1)?.init.method).toBe('POST');
  });
});
