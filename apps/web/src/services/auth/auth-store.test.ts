import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import { AuthStore } from './auth-store';
import { DEMO_SESSION } from '@/test/fake-api';

describe('认证 store', () => {
  let store: AuthStore;

  beforeEach(() => {
    store = new AuthStore();
  });

  it('初始为 unknown，成功恢复后为 authenticated', async () => {
    const reader = { getSession: vi.fn(async () => DEMO_SESSION) };

    expect(store.getSnapshot().status).toBe('unknown');
    const snapshot = await store.restore(reader);

    expect(snapshot.status).toBe('authenticated');
    expect(snapshot.session?.tenant.name).toBe('示例企业');
    expect(reader.getSession).toHaveBeenCalledTimes(1);
  });

  it('401 转为 anonymous，不保留过期原因', async () => {
    const reader = {
      getSession: vi.fn(async () => {
        throw new ApiError('未登录', { status: 401, code: 'RICCON-AUTH-401' });
      }),
    };

    const snapshot = await store.restore(reader);

    expect(snapshot.status).toBe('anonymous');
    expect(snapshot.reason).toBeNull();
  });

  it('网络错误转为 error，并在重试后恢复', async () => {
    const reader = {
      getSession: vi
        .fn()
        .mockRejectedValueOnce(new ApiError('网络错误', { status: 0, code: 'NETWORK' }))
        .mockResolvedValueOnce(DEMO_SESSION),
    };

    expect((await store.restore(reader)).status).toBe('error');
    expect((await store.restore(reader)).status).toBe('authenticated');
  });

  it('已登录后不重复请求会话', async () => {
    const reader = { getSession: vi.fn(async () => DEMO_SESSION) };
    await store.restore(reader);
    await store.restore(reader);

    expect(reader.getSession).toHaveBeenCalledTimes(1);
  });

  it('并发 restore 复用同一个 Promise', async () => {
    let resolveSession: (value: typeof DEMO_SESSION) => void = () => undefined;
    const reader = {
      getSession: vi.fn(
        () =>
          new Promise<typeof DEMO_SESSION>((resolve) => {
            resolveSession = resolve;
          }),
      ),
    };

    const first = store.restore(reader);
    const second = store.restore(reader);
    resolveSession(DEMO_SESSION);

    expect((await first).status).toBe('authenticated');
    expect((await second).status).toBe('authenticated');
    expect(reader.getSession).toHaveBeenCalledTimes(1);
  });

  it('markExpired 与 signOut 更新状态并通知订阅者', () => {
    const listener = vi.fn();
    store.subscribe(listener);
    store.setAuthenticated(DEMO_SESSION);

    store.markExpired();
    expect(store.getSnapshot()).toMatchObject({ status: 'expired', reason: 'expired' });

    store.signOut('logout');
    expect(store.getSnapshot()).toMatchObject({ status: 'anonymous', reason: 'logout' });
    expect(listener).toHaveBeenCalledTimes(3);
  });

  it('reset 回到初始状态', () => {
    store.setAuthenticated(DEMO_SESSION);
    store.reset();
    expect(store.getSnapshot()).toEqual({
      status: 'unknown',
      session: null,
      reason: null,
      error: null,
    });
  });
});
