import type { SessionInfo } from '@/services/api/types';

/** M05b 连接器权限码；与平台 domain `PermissionCodes` 保持一致。 */
export const CONNECTOR_PERMISSIONS = {
  /** 读取连接器列表、配置与同步条目；当前仅 TENANT_ADMIN。 */
  read: 'knowledge.connector.read',
  /** 创建/编辑/删除连接器、写入凭据、测试与同步；当前仅 TENANT_ADMIN。 */
  manage: 'knowledge.connector.manage',
} as const;

/**
 * 判断会话权限是否包含指定连接器权限。
 *
 * 权限未加载时按无权限处理；前端隐藏入口不代替服务端鉴权。
 *
 * @param permissions 会话权限
 * @param permission 目标权限码
 * @returns 是否具备
 */
export function hasConnectorPermission(
  permissions: readonly string[] | undefined,
  permission: string,
): boolean {
  return permissions?.includes(permission) ?? false;
}

/**
 * 判断会话是否平台管理员。
 *
 * 平台管理员没有 `knowledge.connector.*`；服务端对连接器端点统一返回
 * 404 `RICCON-TENANT-404`，界面必须使用与后端一致的“不存在/不属于当前租户”文案，
 * 不能泄露“该租户有连接器”或展示 403 让管理员误以为可以申请权限。
 *
 * @param session 当前会话
 * @returns 是否平台管理员
 */
export function isPlatformAdminSession(session: SessionInfo | null | undefined): boolean {
  return session?.roles.includes('PLATFORM_ADMIN') ?? false;
}
