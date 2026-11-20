import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import { QuotaClient } from './api';
import type { PlatformQuotaPage, TenantQuota, UserQuotaPage } from './types';

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

/** JSON 响应。 */
function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** ProblemDetail 响应。 */
function problem(status: number, code: string): Response {
  return new Response(
    JSON.stringify({ type: 'about:blank', title: '错误', status, code, traceId: 'trace-1' }),
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  );
}

/** 租户配额。 */
const TENANT_QUOTA: TenantQuota = {
  tenantId: 'tenant-a',
  tenantCode: 'tenant-a',
  tenantName: null,
  windows: [
    {
      window: 'DAILY',
      limit: 1000,
      used: 12,
      resetsAt: '2026-11-22T00:00:00+08:00',
      source: 'EXPLICIT',
    },
    {
      window: 'MONTHLY',
      limit: null,
      used: 40,
      resetsAt: '2026-12-01T00:00:00+08:00',
      source: 'EXPLICIT_UNLIMITED',
    },
  ],
};

/** 用户配额分页。 */
const USER_PAGE: UserQuotaPage = {
  page: 2,
  size: 50,
  total: 1,
  totalPages: 1,
  items: [
    { userId: 'user-1', username: 'demo', displayName: '演示', windows: TENANT_QUOTA.windows },
  ],
};

/** 平台配额分页。 */
const PLATFORM_PAGE: PlatformQuotaPage = {
  page: 1,
  size: 20,
  total: 1,
  totalPages: 1,
  items: [
    {
      tenantId: 'tenant-a',
      tenantCode: 'tenant-a',
      tenantName: '示例企业',
      windows: TENANT_QUOTA.windows,
    },
  ],
};

describe('QuotaClient', () => {
  it('按 OpenAPI 查询平台配额与用户配额分页', async () => {
    const { fn, calls } = createFetch((url) =>
      url.includes('/admin/quotas') ? json(PLATFORM_PAGE) : json(USER_PAGE),
    );
    const client = new QuotaClient({ baseUrl: 'http://api.test', fetchImpl: fn });

    const platform = await client.listPlatformQuotas({ page: 2, size: 50 });
    expect(platform.items[0]?.tenantName).toBe('示例企业');
    const platformUrl = new URL(calls[0].url);
    expect(platformUrl.pathname).toBe('/api/v1/admin/quotas');
    expect(platformUrl.searchParams.get('page')).toBe('2');
    expect(platformUrl.searchParams.get('size')).toBe('50');

    await client.listTenantUserQuotas({ page: 2, size: 50 });
    const userUrl = new URL(calls[1].url);
    expect(userUrl.pathname).toBe('/api/v1/quotas/users');
    expect(userUrl.searchParams.get('page')).toBe('2');
  });

  it('未传分页参数时不发送空查询，读取本租户配额使用固定路径', async () => {
    const { fn, calls } = createFetch(() => json(TENANT_QUOTA));
    const client = new QuotaClient({ baseUrl: 'http://api.test', fetchImpl: fn });

    const quota = await client.getTenantQuota();
    expect(quota.windows).toHaveLength(2);
    expect(calls[0].url).toBe('http://api.test/api/v1/quotas');

    await client.listTenantUserQuotas();
    expect(calls[1].url).toBe('http://api.test/api/v1/quotas/users');
  });

  it('整体替换携带 CSRF 与两个窗口键，null 表示明确不限', async () => {
    const { fn, calls } = createFetch(() => json(TENANT_QUOTA));
    const client = new QuotaClient({
      baseUrl: 'http://api.test',
      fetchImpl: fn,
      csrfTokenProvider: () => 'session-token',
    });

    await client.updateTenantQuota('tenant-a', { limits: { daily: 5000, monthly: null } });
    expect(calls[0].url).toBe('http://api.test/api/v1/admin/tenants/tenant-a/quotas');
    expect(calls[0].init.method).toBe('PUT');
    expect((calls[0].init.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe('session-token');
    expect(JSON.parse(String(calls[0].init.body))).toEqual({
      limits: { daily: 5000, monthly: null },
    });
  });

  it('CSRF 失效时刷新令牌并只重试一次', async () => {
    let puts = 0;
    const { fn, calls } = createFetch((url, init) => {
      if (url.endsWith('/auth/csrf')) {
        return json({ headerName: 'X-CSRF-TOKEN', token: 'fresh-token' });
      }
      if (init.method === 'PUT') {
        puts += 1;
        return puts === 1 ? problem(403, 'RICCON-AUTH-403') : json(TENANT_QUOTA);
      }
      return json({}, 404);
    });
    const client = new QuotaClient({
      baseUrl: 'http://api.test',
      fetchImpl: fn,
      csrfTokenProvider: () => 'stale-token',
    });

    await client.updateTenantQuota('tenant-a', { limits: { daily: null, monthly: null } });
    expect(puts).toBe(2);
    expect(calls.map((call) => new URL(call.url).pathname)).toContain('/api/v1/auth/csrf');
  });

  it('401 触发会话过期回调并抛出 ApiError', async () => {
    const onUnauthorized = vi.fn();
    const { fn } = createFetch(() => problem(401, 'RICCON-AUTH-401'));
    const client = new QuotaClient({
      baseUrl: 'http://api.test',
      fetchImpl: fn,
      onUnauthorized,
    });

    await expect(client.getTenantQuota()).rejects.toMatchObject({
      status: 401,
      code: 'RICCON-AUTH-401',
    } satisfies Partial<ApiError>);
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });
});
