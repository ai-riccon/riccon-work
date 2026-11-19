import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import { OidcAuthClient, describeOidcProviderError } from './oidc-api';

/** 记录 fetch 调用。 */
interface FetchCall {
  url: string;
  init: RequestInit;
}

/**
 * 构造可控 fetch 替身。
 *
 * @param handler 按 URL 返回响应
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

/** JSON 响应。 */
function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('OidcAuthClient', () => {
  it('按 tenantCode 查询 providers 并读取展示名', async () => {
    const { fn, calls } = createFetch(() => json({ enabled: true, providerName: 'Acme 企业账号' }));
    const client = new OidcAuthClient({ baseUrl: 'http://api.test', fetchImpl: fn });

    const provider = await client.getProvider('acme');

    expect(provider).toEqual({ enabled: true, providerName: 'Acme 企业账号' });
    expect(calls[0].url).toBe('http://api.test/api/v1/auth/oidc/providers?tenantCode=acme');
    expect(calls[0].init.credentials).toBe('same-origin');
  });

  it('未知租户与未开通统一返回 enabled=false', async () => {
    const { fn } = createFetch(() => json({ enabled: false }));
    const client = new OidcAuthClient({ fetchImpl: fn });

    await expect(client.getProvider('unknown')).resolves.toEqual({ enabled: false });
  });

  it('非法租户代码不发起网络请求', async () => {
    const { fn } = createFetch(() => json({ enabled: false }));
    const client = new OidcAuthClient({ fetchImpl: fn });

    await expect(client.getProvider('bad tenant')).rejects.toMatchObject({
      status: 400,
      code: 'RICCON-CLIENT-INVALID-TENANT',
    });
    expect(fn).not.toHaveBeenCalled();
  });

  it('限流响应归一为 429 ApiError', async () => {
    const { fn } = createFetch(
      () =>
        new Response(
          JSON.stringify({
            type: 'about:blank',
            title: '请求过于频繁',
            status: 429,
            code: 'RICCON-RATE-429',
            traceId: 'trace-1',
          }),
          { status: 429, headers: { 'Content-Type': 'application/problem+json' } },
        ),
    );
    const client = new OidcAuthClient({ fetchImpl: fn });

    await expect(client.getProvider('acme')).rejects.toMatchObject({
      status: 429,
      code: 'RICCON-RATE-429',
    });
  });

  it('网络异常归一为 status=0', async () => {
    const { fn } = createFetch(() => {
      throw new Error('offline');
    });
    const client = new OidcAuthClient({ fetchImpl: fn });

    await expect(client.getProvider('acme')).rejects.toMatchObject({ status: 0 });
  });

  it('构造 authorize 地址并校验租户代码', () => {
    const client = new OidcAuthClient({ baseUrl: 'http://api.test/', fetchImpl: vi.fn() });
    expect(client.authorizeUrl('acme')).toBe(
      'http://api.test/api/v1/auth/oidc/authorize/tenant-acme',
    );
    expect(() => client.authorizeUrl('acme/evil')).toThrow(ApiError);
  });
});

describe('providers 查询错误文案', () => {
  it('区分网络、限流、参数与服务端错误', () => {
    expect(describeOidcProviderError(new ApiError('x', { status: 0, code: 'A' }))).toContain(
      '无法连接服务器',
    );
    expect(describeOidcProviderError(new ApiError('x', { status: 429, code: 'A' }))).toContain(
      '频繁',
    );
    expect(describeOidcProviderError(new ApiError('x', { status: 400, code: 'A' }))).toContain(
      '格式',
    );
    expect(describeOidcProviderError(new ApiError('x', { status: 503, code: 'A' }))).toContain(
      '不可用',
    );
    expect(describeOidcProviderError(new ApiError('x', { status: 418, code: 'A' }))).toContain(
      '无法查询',
    );
    expect(describeOidcProviderError(new Error('boom'))).toContain('无法查询');
  });
});
