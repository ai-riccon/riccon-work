import { useSyncExternalStore } from 'react';
import { ApiError } from '@/services/api/errors';
import type { SessionInfo } from '@/services/api/types';

/** 会话状态机。 */
export type AuthStatus =
  'unknown' | 'checking' | 'authenticated' | 'anonymous' | 'expired' | 'error';

/** 登录页需要区分的退出原因。 */
export type AuthReason = 'expired' | 'logout' | null;

/** 认证快照；不可变对象，供 useSyncExternalStore 比较。 */
export interface AuthSnapshot {
  status: AuthStatus;
  session: SessionInfo | null;
  reason: AuthReason;
  error: string | null;
}

type Listener = () => void;

/** 允许注入的会话读取能力，避免 store 依赖具体 ApiClient 实现。 */
export interface SessionReader {
  getSession(): Promise<SessionInfo>;
}

const INITIAL_SNAPSHOT: AuthSnapshot = {
  status: 'unknown',
  session: null,
  reason: null,
  error: null,
};

/**
 * 极简认证 store。
 *
 * 不引入第二套状态库：使用 React 内置的 useSyncExternalStore 订阅，
 * 让登录页、工作空间页和 API 客户端的 401 回调共享同一份会话状态。
 * store 不持久化任何令牌，刷新后通过 `/auth/session` 恢复。
 */
export class AuthStore {
  private snapshot: AuthSnapshot = INITIAL_SNAPSHOT;

  private listeners = new Set<Listener>();

  private restorePromise: Promise<AuthSnapshot> | null = null;

  /** 订阅快照变化。 */
  subscribe = (listener: Listener): (() => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  /** 读取当前快照。 */
  getSnapshot = (): AuthSnapshot => this.snapshot;

  /**
   * 从服务端恢复会话。
   *
   * 只处理初始未知态与加载失败后的重试；已登录、已过期和匿名状态不会自动重查，
   * 避免登录页在用户刚退出后又把状态改回已登录。
   *
   * @param reader 会话读取能力
   * @returns 恢复后的快照
   */
  restore = async (reader: SessionReader): Promise<AuthSnapshot> => {
    if (this.snapshot.status === 'checking' && this.restorePromise) {
      return this.restorePromise;
    }
    if (this.snapshot.status !== 'unknown' && this.snapshot.status !== 'error') {
      return this.snapshot;
    }

    this.update({ status: 'checking', error: null });
    this.restorePromise = (async () => {
      try {
        const session = await reader.getSession();
        this.update({
          status: 'authenticated',
          session,
          reason: null,
          error: null,
        });
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) {
          this.update({ status: 'anonymous', session: null, error: null });
        } else {
          this.update({
            status: 'error',
            session: null,
            error:
              error instanceof ApiError ? error.message : '无法确认登录状态，请检查网络后重试。',
          });
        }
      } finally {
        this.restorePromise = null;
      }
      return this.snapshot;
    })();

    return this.restorePromise;
  };

  /** 登录成功或会话刷新后写入。 */
  setAuthenticated = (session: SessionInfo): void => {
    this.update({ status: 'authenticated', session, reason: null, error: null });
  };

  /** 任意带登录态请求返回 401 时调用。 */
  markExpired = (): void => {
    this.update({
      status: 'expired',
      session: null,
      reason: 'expired',
      error: null,
    });
  };

  /** 主动退出或清除本地会话。 */
  signOut = (reason: AuthReason = 'logout'): void => {
    this.update({ status: 'anonymous', session: null, reason, error: null });
  };

  /**
   * 重置为初始状态；仅用于测试隔离，生产代码不应调用。
   */
  reset = (): void => {
    this.update(INITIAL_SNAPSHOT);
    this.restorePromise = null;
  };

  /**
   * 合并快照并通知订阅者。
   *
   * @param patch 局部状态
   */
  private update(patch: Partial<AuthSnapshot>): void {
    this.snapshot = { ...this.snapshot, ...patch };
    this.listeners.forEach((listener) => listener());
  }
}

/** 全局认证 store。 */
export const authStore = new AuthStore();

/**
 * 订阅认证状态。
 *
 * @returns 当前认证快照
 */
export function useAuth(): AuthSnapshot {
  return useSyncExternalStore(authStore.subscribe, authStore.getSnapshot, authStore.getSnapshot);
}
