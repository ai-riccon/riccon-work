// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiClient } from '@/services/api/client';
import { FEDERATED_LOGOUT_ATTEMPTED_KEY, FEDERATED_LOGOUT_URL_KEY } from './federated-logout';

/**
 * 登出响应头捕获测试。
 *
 * 验证 ApiClient 只暂存联合登出地址，不解析 token，也不在客户端直接导航；
 * 顶层导航由登录页在渲染本地退出提示后执行（ADR-0019 第 5 节）。
 */
describe('ApiClient 联合登出响应头', () => {
  beforeEach(() => {
    sessionStorage.clear();
  });

  /**
   * 构造 fetch：先返回 CSRF，再返回登出响应。
   *
   * @param headerValue 联合登出响应头；null 表示不返回
   * @returns fetch 替身
   */
  function logoutFetch(headerValue: string | null) {
    return vi.fn(async (input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.endsWith('/auth/csrf')) {
        return new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'csrf-1' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      const headers: Record<string, string> = {};
      if (headerValue) {
        headers['X-Riccon-Federated-Logout-Url'] = headerValue;
      }
      return new Response(null, { status: 204, headers });
    }) as unknown as typeof fetch;
  }

  it('收到响应头时只暂存安全地址并重置尝试标记', async () => {
    sessionStorage.setItem(FEDERATED_LOGOUT_ATTEMPTED_KEY, '1');
    const client = new ApiClient({ fetchImpl: logoutFetch('https://idp.example.com/logout') });

    await expect(client.logout()).resolves.toBeUndefined();

    expect(sessionStorage.getItem(FEDERATED_LOGOUT_URL_KEY)).toBe('https://idp.example.com/logout');
    expect(sessionStorage.getItem(FEDERATED_LOGOUT_ATTEMPTED_KEY)).toBeNull();
  });

  it('没有响应头时不写暂存', async () => {
    const client = new ApiClient({ fetchImpl: logoutFetch(null) });

    await client.logout();

    expect(sessionStorage.getItem(FEDERATED_LOGOUT_URL_KEY)).toBeNull();
  });

  it('不接受非 HTTPS/本机地址', async () => {
    const client = new ApiClient({ fetchImpl: logoutFetch('javascript:alert(1)') });

    await client.logout();

    expect(sessionStorage.getItem(FEDERATED_LOGOUT_URL_KEY)).toBeNull();
  });
});
