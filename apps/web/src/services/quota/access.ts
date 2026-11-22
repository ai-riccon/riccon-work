/** M04 配额权限码；与平台 domain `PermissionCodes` 保持一致。 */
export const QUOTA_PERMISSIONS = {
  /** 租户管理员只读本租户配额与用户用量。 */
  read: 'quota.read',
  /** 平台管理员跨租户查看并整体替换限额；同时代表平台配额入口。 */
  platformManage: 'platform.quota.manage',
} as const;

/**
 * 判断会话权限是否包含指定权限码。
 *
 * 与 `services/model/access` 保持相同语义：权限未加载时按无权限处理，
 * 前端隐藏入口不代替服务端鉴权。
 *
 * @param permissions 会话权限列表；未加载时为空
 * @param permission 目标权限码
 * @returns 是否具备权限
 */
export function hasQuotaPermission(
  permissions: readonly string[] | undefined,
  permission: string,
): boolean {
  return permissions?.includes(permission) ?? false;
}
