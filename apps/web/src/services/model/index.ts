import { api } from '@/services/api';
import type { ModelAdminApi } from './api';

/**
 * 应用默认模型网关管理 API 客户端。
 *
 * 由统一的 `ApiClient` 实现，复用 Session Cookie、CSRF 刷新与 401 会话过期回调；
 * 不包含 mock 或演示成功分支，组件测试通过页面注入替身。
 */
export const modelApi: ModelAdminApi = api;

export type { ModelAdminApi } from './api';
export type { ModelPurpose } from '@/services/api/types';
export * from './types';
export * from './format';
export * from './access';
