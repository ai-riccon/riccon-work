import { describe, expect, it, vi } from 'vitest';
import type { UserSummary } from '@/services/api/types';
import { ACL_MAX_USERS, KnowledgeUserDirectoryClient } from './user-directory';

/** 构造账号。 */
function makeUser(id: string): UserSummary {
  return {
    id,
    username: id,
    displayName: id,
    email: null,
    status: 'ACTIVE',
    roles: ['MEMBER'],
    lastLoginAt: null,
    createdAt: '2026-11-18T08:00:00+08:00',
  };
}

/** 构造分页响应。 */
function pageResponse(items: UserSummary[], total = items.length): Response {
  return new Response(
    JSON.stringify({
      page: 1,
      size: 100,
      total,
      totalPages: Math.ceil(total / 100),
      items,
    }),
    { status: 200, headers: { 'Content-Type': 'application/json' } },
  );
}

describe('ACL 用户目录', () => {
  it('少于 100 个账号只请求一页', async () => {
    const fetchImpl = vi.fn(async () => pageResponse([makeUser('u1')]));
    const directory = new KnowledgeUserDirectoryClient({
      baseUrl: '',
      fetchImpl: fetchImpl as unknown as typeof fetch,
    });

    await expect(directory.listUsers()).resolves.toHaveLength(1);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    expect(fetchImpl).toHaveBeenCalledWith('/api/v1/users?page=1&size=100', expect.any(Object));
  });

  it('超过 100 个账号继续读取第二页，最多 200 个', async () => {
    const first = Array.from({ length: 100 }, (_value, index) => makeUser(`u${index}`));
    const second = Array.from({ length: 100 }, (_value, index) => makeUser(`v${index}`));
    const fetchImpl = vi
      .fn()
      .mockResolvedValueOnce(pageResponse(first, 250))
      .mockResolvedValueOnce(pageResponse(second, 250));
    const directory = new KnowledgeUserDirectoryClient({
      baseUrl: '',
      fetchImpl: fetchImpl as unknown as typeof fetch,
    });

    const users = await directory.listUsers();
    expect(users).toHaveLength(ACL_MAX_USERS);
    expect(fetchImpl).toHaveBeenCalledTimes(2);
    expect(fetchImpl).toHaveBeenLastCalledWith('/api/v1/users?page=2&size=100', expect.any(Object));
  });

  it('服务端错误归一为 ApiError，403 不误触发会话过期回调', async () => {
    const onUnauthorized = vi.fn();
    const directory = new KnowledgeUserDirectoryClient({
      baseUrl: '',
      fetchImpl: (async () =>
        new Response(
          JSON.stringify({
            type: 'about:blank',
            title: 'forbidden',
            status: 403,
            code: 'RICCON-AUTH-403',
            traceId: 't',
          }),
          { status: 403, headers: { 'Content-Type': 'application/problem+json' } },
        )) as unknown as typeof fetch,
      onUnauthorized,
    });

    await expect(directory.listUsers()).rejects.toMatchObject({ status: 403 });
    expect(onUnauthorized).not.toHaveBeenCalled();
  });
});
