import { API_BASE_URL } from '@/services/api/config';
import { authStore } from '@/services/auth/auth-store';
import { QuotaClient } from './api';
import type { QuotaApi } from './api';

/**
 * 应用默认配额 API 客户端。
 *
 * CSRF 令牌优先从当前会话读取；401 统一交给 authStore，页面据此提示重新登录。
 * 不包含任何 mock 或演示成功分支，端到端测试由独立 stub API 承担。
 */
export const quotaApi: QuotaApi = new QuotaClient({
  baseUrl: API_BASE_URL,
  fetchImpl: globalThis.fetch.bind(globalThis),
  csrfTokenProvider: () => authStore.getSnapshot().session?.csrfToken ?? null,
  onUnauthorized: () => authStore.markExpired(),
});

export { QuotaClient } from './api';
export type { QuotaApi, QuotaClientOptions, QuotaFetchLike } from './api';
export * from './types';
export * from './format';
export * from './access';
