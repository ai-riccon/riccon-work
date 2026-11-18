import type { RicconApi, WorkspaceManagementApi } from '@/services/api/client';

/**
 * 判断 API 客户端是否实现空间管理能力。
 *
 * J1/J2 的最小接口 `RicconApi` 不包含管理方法；真实 ApiClient 同时实现两者，
 * 测试替身只实现最小接口，因此不会触发管理请求或暴露管理入口。
 *
 * @param api API 客户端
 * @returns 是否支持空间管理
 */
export function supportsWorkspaceManagement(
  api: RicconApi,
): api is RicconApi & WorkspaceManagementApi {
  const candidate = api as Partial<WorkspaceManagementApi>;
  return (
    typeof candidate.listDepartments === 'function' &&
    typeof candidate.listTeamWorkspaces === 'function'
  );
}
