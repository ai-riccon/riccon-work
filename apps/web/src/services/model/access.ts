/** 模型网关权限码；与平台 domain `PermissionCodes` 保持一致。 */
export const MODEL_PERMISSIONS = {
  /** 读取平台模型目录、路由链与平台 key 掩码。 */
  read: 'platform.model.read',
  /** 保存路由、启停模型与新增/轮换/停用/恢复平台 key。 */
  manage: 'platform.model.manage',
} as const;

/**
 * 判断会话权限是否包含指定权限码。
 *
 * @param permissions 会话权限列表；未加载时为空
 * @param permission 目标权限码
 * @returns 是否具备权限
 */
export function hasPermission(
  permissions: readonly string[] | undefined,
  permission: string,
): boolean {
  return permissions?.includes(permission) ?? false;
}
