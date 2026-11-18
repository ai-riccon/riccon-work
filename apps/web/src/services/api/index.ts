import { authStore } from '@/services/auth/auth-store';
import { ApiClient } from './client';
import { API_BASE_URL } from './config';

/**
 * 应用默认 API 客户端。
 *
 * 始终使用浏览器 fetch 调用真实 OpenAPI 端点；源码与构建产物中不存在
 * mock 适配层或“演示成功”分支。端到端测试由独立的 stub API 承担，
 * 通过 `RICCON_API_BASE_URL` 或开发代理指向。
 * 401 统一交给 authStore，页面根据状态跳转登录，避免每个调用点重复处理。
 */
export const api = new ApiClient({
  baseUrl: API_BASE_URL,
  fetchImpl: globalThis.fetch.bind(globalThis),
  onUnauthorized: () => authStore.markExpired(),
});

export { ApiError } from './errors';
export type { PlatformDepartmentApi, RicconApi, WorkspaceManagementApi } from './client';
export type * from './types';
