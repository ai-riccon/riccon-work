import type { Department, Page } from '@/services/api/types';
import { API_BASE_URL } from '@/services/api/config';
import { authStore } from '@/services/auth/auth-store';
import { api } from '@/services/api';
import { ConnectorClient } from './api';
import type { ConnectorApi } from './api';

/**
 * 应用默认连接器 API 客户端。
 *
 * CSRF 令牌优先从当前会话读取；401 统一交给 authStore，页面据此提示重新登录。
 * 不包含 mock 或演示成功分支，组件测试通过页面注入替身。
 */
export const connectorApi: ConnectorApi = new ConnectorClient({
  baseUrl: API_BASE_URL,
  fetchImpl: globalThis.fetch.bind(globalThis),
  csrfTokenProvider: () => authStore.getSnapshot().session?.csrfToken ?? null,
  onUnauthorized: () => authStore.markExpired(),
});

/**
 * 连接器默认可见范围需要的部门目录。
 *
 * 复用现有 `/departments` 只读端点；租户管理员得到本租户全部部门，页面只允许
 * 选择 `ACTIVE` 部门。不新增连接器专用部门端点。
 */
export interface ConnectorDepartmentDirectory {
  /** 读取当前账号可见部门。 */
  listDepartments(): Promise<Page<Department>>;
}

/** 应用默认部门目录。 */
export const connectorDepartmentDirectory: ConnectorDepartmentDirectory = {
  listDepartments: () => api.listDepartments(),
};

export { ConnectorClient } from './api';
export type { ConnectorApi, ConnectorClientOptions, ConnectorFetchLike } from './api';
export * from './types';
export * from './format';
export * from './access';
