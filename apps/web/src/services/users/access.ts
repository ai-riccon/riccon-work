import type { AuthSource, UserSummary } from '@/services/api/types';

/** 账号管理权限码；与平台 domain `PermissionCodes` 保持一致。 */
export const USER_PERMISSIONS = {
  /** 租户管理员管理本租户账号与 OIDC 绑定。 */
  tenantManage: 'tenant.user.manage',
  /** 平台管理员管理目标租户账号元数据与 OIDC 绑定。 */
  platformManage: 'platform.tenant.manage',
} as const;

/** 平台保留租户 ID；平台引导管理员必须保持内置账号（ADR-0019 第 10 节）。 */
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

/**
 * 账号认证来源中文标签。
 *
 * 历史响应可能省略 authSource；这里不猜测来源，明确显示“未标注”。
 *
 * @param source 认证来源
 * @returns 中文标签
 */
export function authSourceLabel(source: AuthSource | undefined | null): string {
  if (source === 'OIDC') {
    return '企业账号';
  }
  if (source === 'LOCAL') {
    return '内置账号';
  }
  return '未标注';
}

/**
 * 认证来源标签颜色。
 *
 * @param source 认证来源
 * @returns antd Tag 颜色
 */
export function authSourceColor(source: AuthSource | undefined | null): string {
  if (source === 'OIDC') {
    return 'blue';
  }
  if (source === 'LOCAL') {
    return 'default';
  }
  return 'orange';
}

/**
 * 账号状态中文标签。
 *
 * @param user 账号摘要
 * @returns 中文标签
 */
export function userStatusLabel(user: Pick<UserSummary, 'status'>): string {
  return user.status === 'ACTIVE' ? '启用' : '已禁用';
}

/**
 * 格式化带偏移的 ISO 8601 时间。
 *
 * UI 统一按北京时间展示；无法解析时返回占位符，不把原始异常文本带到界面。
 *
 * @param value ISO 8601 时间
 * @returns `YYYY-MM-DD HH:mm:ss` 或占位符
 */
export function formatUserTime(value: string | null | undefined): string {
  if (!value) {
    return '—';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '—';
  }
  const parts = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(date);
  const pick = (type: Intl.DateTimeFormatPartTypes): string =>
    parts.find((part) => part.type === type)?.value ?? '';
  return `${pick('year')}-${pick('month')}-${pick('day')} ${pick('hour')}:${pick('minute')}:${pick('second')}`;
}
