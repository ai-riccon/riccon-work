import { describe, expect, it, vi } from 'vitest';
import type { UserSummary } from '@/services/api/types';
import { UserAdminClient } from './api';

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
    JSON.stringify({
      type: 'about:blank',
      title: '错误',
      status,
      code,
      traceId: 'trace-1',
    }),
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  );
}

/** 测试账号。 */
const USER: UserSummary = {
  id: 'user-1',
  username: 'wangmin',
  displayName: '王敏',
  email: null,
  status: 'ACTIVE',
  roles: ['MEMBER'],
  authSource: 'LOCAL',
  lastLoginAt: null,
  createdAt: '2026-11-01T09:00:00+08:00',
};

/** 测试分页。 */
const PAGE = { page: 1, size: 100, total: 1, totalPages: 1, items: [USER] };

describe('UserAdminClient', () => {
  it('租户管理员读取本租户账号列表', async () => {
    const { fn, calls } = createFetch(() => json(PAGE));
    const client = new UserAdminClient({
      baseUrl: 'http://api.test',
      fetchImpl: fn,
      csrfTokenProvider: () => 'session-token',
    });

    const page = await client.listUsers();

    expect(page.items[0].username).toBe('wangmin');
    expect(calls[0].url).toBe('http://api.test/api/v1/users?page=1&size=100');
    expect(calls[0].init.method).toBe('GET');
    expect(calls[0].init.headers).not.toMatchObject({ 'X-CSRF-TOKEN': 'session-token' });
  });

  it('绑定使用 PUT + CSRF + issuer/subject；解绑使用 POST + newPassword', async () => {
    const { fn, calls } = createFetch((url) => {
      if (url.endsWith('/oidc-binding')) {
        return json({ ...USER, authSource: 'OIDC' });
      }
      return json({ ...USER, authSource: 'LOCAL' });
    });
    const client = new UserAdminClient({
      baseUrl: 'http://api.test',
      fetchImpl: fn,
      csrfTokenProvider: () => 'session-token',
    });

    const bound = await client.bindOidc('user-1', {
      issuer: 'https://idp.example.com/realms/acme',
      subject: 'sub-1',
    });
    expect(bound.authSource).toBe('OIDC');
    expect(calls[0].url).toBe('http://api.test/api/v1/users/user-1/oidc-binding');
    expect(calls[0].init.method).toBe('PUT');
    expect(calls[0].init.headers).toMatchObject({ 'X-CSRF-TOKEN': 'session-token' });
    expect(JSON.parse(String(calls[0].init.body))).toEqual({
      issuer: 'https://idp.example.com/realms/acme',
      subject: 'sub-1',
    });

    const unbound = await client.unbindOidc('user-1', { newPassword: 'new-password-123' });
    expect(unbound.authSource).toBe('LOCAL');
    expect(calls[1].url).toBe('http://api.test/api/v1/users/user-1/oidc-binding/remove');
    expect(calls[1].init.method).toBe('POST');
    expect(JSON.parse(String(calls[1].init.body))).toEqual({ newPassword: 'new-password-123' });
  });

  it('平台管理员使用目标租户路径', async () => {
    const { fn, calls } = createFetch(() => json(PAGE));
    const client = new UserAdminClient({
      baseUrl: 'http://api.test',
      fetchImpl: fn,
      csrfTokenProvider: () => 'session-token',
    });

    await client.listTenantUsers('acme');
    await client.bindTenantUserOidc('acme', 'user-1', {
      issuer: 'https://idp.example.com',
      subject: 'sub-1',
    });
    await client.unbindTenantUserOidc('acme', 'user-1', { newPassword: 'new-password-123' });

    expect(calls.map((call) => call.url)).toEqual([
      'http://api.test/api/v1/admin/tenants/acme/users?page=1&size=100',
      'http://api.test/api/v1/admin/tenants/acme/users/user-1/oidc-binding',
      'http://api.test/api/v1/admin/tenants/acme/users/user-1/oidc-binding/remove',
    ]);
  });

  it('没有会话令牌时先读取 /auth/csrf 再写入', async () => {
    const { fn, calls } = createFetch((url) => {
      if (url.endsWith('/auth/csrf')) {
        return json({ headerName: 'X-CSRF-TOKEN', token: 'anonymous-token' });
      }
      return json({ ...USER, authSource: 'OIDC' });
    });
    const client = new UserAdminClient({ baseUrl: 'http://api.test', fetchImpl: fn });

    await client.bindOidc('user-1', { issuer: 'https://idp.example.com', subject: 'sub-1' });

    expect(calls[0].url).toBe('http://api.test/api/v1/auth/csrf');
    expect(calls[1].url).toBe('http://api.test/api/v1/users/user-1/oidc-binding');
    expect(calls[1].init.headers).toMatchObject({ 'X-CSRF-TOKEN': 'anonymous-token' });
  });

  it('CSRF 403 时刷新令牌并只重试一次', async () => {
    let attempts = 0;
    const { fn, calls } = createFetch((url) => {
      if (url.endsWith('/auth/csrf')) {
        return json({ headerName: 'X-CSRF-TOKEN', token: 'fresh-token' });
      }
      attempts += 1;
      if (attempts === 1) {
        return problem(403, 'RICCON-AUTH-403');
      }
      return json({ ...USER, authSource: 'OIDC' });
    });
    const client = new UserAdminClient({
      baseUrl: 'http://api.test',
      fetchImpl: fn,
      csrfTokenProvider: () => 'stale-token',
    });

    await client.bindOidc('user-1', { issuer: 'https://idp.example.com', subject: 'sub-1' });

    expect(calls.map((call) => call.url)).toEqual([
      'http://api.test/api/v1/users/user-1/oidc-binding',
      'http://api.test/api/v1/auth/csrf',
      'http://api.test/api/v1/users/user-1/oidc-binding',
    ]);
    expect(calls[2].init.headers).toMatchObject({ 'X-CSRF-TOKEN': 'fresh-token' });
  });

  it('401 通知会话过期；409 保留稳定错误码', async () => {
    const onUnauthorized = vi.fn();
    const { fn } = createFetch((url) => {
      if (url.includes('/oidc-binding')) {
        return problem(409, 'RICCON-OIDC-409');
      }
      return problem(401, 'RICCON-AUTH-401');
    });
    const client = new UserAdminClient({
      baseUrl: 'http://api.test',
      fetchImpl: fn,
      csrfTokenProvider: () => 'session-token',
      onUnauthorized,
    });

    await expect(client.listUsers()).rejects.toMatchObject({ status: 401 });
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
    await expect(
      client.bindOidc('user-1', { issuer: 'https://idp.example.com', subject: 'sub-1' }),
    ).rejects.toMatchObject({ status: 409, code: 'RICCON-OIDC-409' });
  });

  it('网络异常归一为 status=0', async () => {
    const { fn } = createFetch(() => {
      throw new Error('offline');
    });
    const client = new UserAdminClient({ baseUrl: 'http://api.test', fetchImpl: fn });

    await expect(client.listUsers()).rejects.toMatchObject({ status: 0 });
  });
});
