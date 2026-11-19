import { API_BASE_URL } from '@/services/api/config';
import { authStore } from '@/services/auth/auth-store';
import { UserAdminClient } from './api';
import type { UserAdminApi } from './api';

/**
 * 应用默认用户管理 API 客户端。
 *
 * CSRF 令牌优先从当前会话读取；401 统一交给 authStore，页面据此提示重新登录。
 * 不包含任何 mock 或演示成功分支，端到端测试由独立 stub API 承担。
 */
export const userAdminApi: UserAdminApi = new UserAdminClient({
  baseUrl: API_BASE_URL,
  fetchImpl: globalThis.fetch.bind(globalThis),
  csrfTokenProvider: () => authStore.getSnapshot().session?.csrfToken ?? null,
  onUnauthorized: () => authStore.markExpired(),
});

export { UserAdminClient } from './api';
export type {
  PlatformUserAdminApi,
  TenantUserAdminApi,
  UserAdminApi,
  UserAdminClientOptions,
  UserFetchLike,
} from './api';
export * from './types';
export * from './access';
export * from './errors';
