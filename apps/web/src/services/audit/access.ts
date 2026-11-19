/** M10 审计权限码；与平台 domain `PermissionCodes` 保持一致。 */
export const AUDIT_PERMISSIONS = {
  /** 查询审计、申请/下载本人导出。 */
  read: 'audit.read',
  /** 同租户审批导出。 */
  approve: 'audit.export.approve',
  /** 平台管理员审批单管理员租户导出（仅元数据）。 */
  platformApprove: 'platform.audit.export.approve',
} as const;

/** 平台租户 ID；平台租户自身导出在 M10 显式不支持。 */
export const PLATFORM_TENANT_ID = 'platform';

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
