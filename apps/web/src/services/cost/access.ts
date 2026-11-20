/** M04 成本权限码；与平台 domain `PermissionCodes` 保持一致。 */
export const COST_PERMISSIONS = {
  /** 租户管理员只读本租户成本聚合与调用明细。 */
  read: 'cost.read',
  /** 平台管理员跨租户成本聚合；不提供用户明细。 */
  platformRead: 'platform.cost.read',
} as const;

/**
 * 判断会话权限是否包含指定权限码。
 *
 * 权限未加载时按无权限处理；前端隐藏入口不代替服务端鉴权。
 *
 * @param permissions 会话权限列表；未加载时为空
 * @param permission 目标权限码
 * @returns 是否具备权限
 */
export function hasCostPermission(
  permissions: readonly string[] | undefined,
  permission: string,
): boolean {
  return permissions?.includes(permission) ?? false;
}
