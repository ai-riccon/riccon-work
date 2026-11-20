import { API_BASE_URL } from '@/services/api/config';
import { authStore } from '@/services/auth/auth-store';
import { CostClient } from './api';
import type { CostApi } from './api';

/**
 * 应用默认成本 API 客户端。
 *
 * 401 统一交给 authStore，页面据此提示重新登录；成本查看是只读请求，不需要 CSRF。
 * 不包含任何 mock 或演示成功分支，端到端测试由独立 stub API 承担。
 */
export const costApi: CostApi = new CostClient({
  baseUrl: API_BASE_URL,
  fetchImpl: globalThis.fetch.bind(globalThis),
  onUnauthorized: () => authStore.markExpired(),
});

export { CostClient } from './api';
export type { CostApi, CostClientOptions, CostFetchLike } from './api';
export * from './types';
export * from './format';
export * from './access';
