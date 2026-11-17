import { authStore } from '@/services/auth/auth-store';
import { ApiClient } from './client';
import type { FetchLike } from './client';
import { API_BASE_URL, IS_MOCK_API } from './config';

/**
 * 应用默认 API 客户端。
 *
 * 真实路径使用同源 fetch；只有显式 `RICCON_API_MOCK=1` 时才在首次请求时
 * 动态加载演示适配层，避免把 mock 代码与演示数据打进生产入口 chunk。
 * 401 统一交给 authStore，页面根据状态跳转登录，避免每个调用点重复处理。
 */
export const api = new ApiClient({
  baseUrl: API_BASE_URL,
  // 生产/未开启 mock：直接使用浏览器 fetch，不加载任何演示代码。
  ...(IS_MOCK_API
    ? {
        fetchProvider: async (): Promise<FetchLike> => {
          const { createMockFetch } = await import('@/mocks/mock-api');
          return createMockFetch();
        },
      }
    : { fetchImpl: globalThis.fetch.bind(globalThis) }),
  onUnauthorized: () => authStore.markExpired(),
});

export { ApiError } from './errors';
export type { RicconApi } from './client';
export type * from './types';
