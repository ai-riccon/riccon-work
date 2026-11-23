import { ApiError, describeApiError } from '@/services/api/errors';
import { formatOffsetDateTime } from '@/services/api/quota-detail';
import type {
  PlatformQuotaRow,
  QuotaLimitSource,
  QuotaWindowKind,
  QuotaWindowState,
} from './types';

export { formatQuotaResetRelative, formatQuotaResetText } from '@/services/api/quota-detail';

/** 配额窗口中文名；页面统一使用“日/月”，与 AC-11/ADR-0022 文案一致。 */
const WINDOW_LABELS: Record<QuotaWindowKind, string> = {
  DAILY: '日',
  MONTHLY: '月',
};

/** 限额来源中文名。 */
const SOURCE_LABELS: Record<QuotaLimitSource, string> = {
  EXPLICIT: '显式设置',
  EXPLICIT_UNLIMITED: '明确不限',
  DEPLOYMENT_DEFAULT: '部署默认',
};

/** 配额错误文案上下文；同一错误码在读取与保存时的下一步不同。 */
export type QuotaErrorContext = 'platform-load' | 'tenant-load' | 'users-load' | 'limit-save';

/**
 * 返回窗口中文名。
 *
 * @param window 窗口类型
 * @returns `日` 或 `月`
 */
export function quotaWindowLabel(window: QuotaWindowKind): string {
  return WINDOW_LABELS[window];
}

/**
 * 返回限额来源中文名。
 *
 * @param source 来源
 * @returns 中文名
 */
export function quotaLimitSourceLabel(source: QuotaLimitSource): string {
  return SOURCE_LABELS[source];
}

/**
 * 把整数按中文千分位格式化。
 *
 * @param value 非负整数
 * @returns 例如 `10,000`
 */
export function formatQuotaCount(value: number): string {
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 }).format(value);
}

/**
 * 格式化生效限额。
 *
 * 契约把 `null` 定义为不限；0 与 null 等价，历史数据或部署默认可能出现 0，
 * 因此两者都显示为“不限”，避免把“不限”误读成“不允许调用”。
 *
 * @param limit 生效限额
 * @returns `不限` 或千分位数字
 */
export function formatQuotaLimit(limit: number | null | undefined): string {
  if (limit === null || limit === undefined || limit === 0) {
    return '不限';
  }
  return formatQuotaCount(limit);
}

/**
 * 生成窗口用量文案。
 *
 * @param state 窗口状态
 * @returns 例如 `已用 12 / 1,000` 或 `已用 12 / 不限`
 */
export function quotaUsageText(state: QuotaWindowState): string {
  return `已用 ${formatQuotaCount(state.used)} / ${formatQuotaLimit(state.limit)}`;
}

/**
 * 生成窗口剩余次数文案。
 *
 * 不限时返回 `不限`；有限额时最低显示 0，避免用量超出旧限额（限额下调后）出现负数。
 *
 * @param state 窗口状态
 * @returns 例如 `剩余 988` 或 `不限`
 */
export function quotaRemainingText(state: QuotaWindowState): string {
  if (state.limit === null || state.limit === undefined || state.limit === 0) {
    return '不限';
  }
  return `剩余 ${formatQuotaCount(Math.max(0, state.limit - state.used))}`;
}

/**
 * 在窗口列表中查找指定窗口。
 *
 * 服务端固定返回日/月两项且顺序稳定；仍然按类型查找，避免依赖数组下标。
 *
 * @param windows 窗口列表
 * @param window 目标窗口
 * @returns 窗口状态；缺失时为 undefined
 */
export function findQuotaWindow(
  windows: readonly QuotaWindowState[] | undefined,
  window: QuotaWindowKind,
): QuotaWindowState | undefined {
  return windows?.find((state) => state.window === window);
}

/**
 * 返回租户展示名。
 *
 * 平台视图应优先显示名称、退化到租户代码；租户自读时名称可能为空。
 *
 * @param row 租户配额行
 * @returns 展示名或代码
 */
export function quotaTenantLabel(row: Pick<PlatformQuotaRow, 'tenantName' | 'tenantCode'>): string {
  return row.tenantName?.trim() || row.tenantCode;
}

/**
 * 把带偏移的 ISO 8601 时间格式化为北京时间，并显式保留 `+08:00` 偏移。
 *
 * 无法解析时返回占位符，不把原始异常或服务端文本带到界面。
 *
 * @param value 带偏移的 ISO 8601 字符串或空值
 * @returns 例如 `2026-12-01 00:00:00 +08:00`
 */
export function formatQuotaTime(value: string | null | undefined): string {
  return formatOffsetDateTime(value);
}

/**
 * 把配额错误归一为固定中文文案与下一步。
 *
 * 不直接展示服务端 `detail`：配额错误可能包含目标租户/账号标识，管理界面统一使用
 * 固定映射，避免内部细节进入页面（ADR-0022 §3.3/§4）。
 *
 * @param error 任意异常
 * @param context 操作上下文
 * @returns 面向管理员的中文说明
 */
export function describeQuotaError(error: unknown, context: QuotaErrorContext): string {
  if (!(error instanceof ApiError)) {
    return '配额操作未完成，请稍后重试。';
  }

  if (error.status === 401) {
    return '登录状态已失效，请重新登录后再查看配额。';
  }
  if (error.status === 403 || error.code === 'RICCON-AUTH-403') {
    return context === 'limit-save'
      ? '没有修改配额的权限；只有平台管理员可以调整限额。'
      : '没有配额查看权限；租户配额仅向平台管理员、本租户用量仅向租户管理员开放。';
  }
  if (error.status === 404) {
    return context === 'limit-save'
      ? '目标租户不存在或已被移除，本次修改没有保存；请重新加载列表后再试。'
      : '配额数据不存在或已被移除，请稍后重试。';
  }
  if (error.status === 409) {
    return '目标租户已停用，不能修改配额；请先在租户管理中启用该租户。';
  }
  if (error.status === 503 || error.code === 'RICCON-QUOTA-503') {
    return '配额服务暂不可用（fail-closed）；本次没有修改配额，请稍后重试。';
  }
  if (error.status === 400 || error.code === 'RICCON-QUOTA-400') {
    return '限额必须为 0 或正整数；留空表示不限。请检查日/月限额后重试。';
  }

  return describeApiError(error, '配额操作未完成，请稍后重试。');
}
