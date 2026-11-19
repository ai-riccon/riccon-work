import { ApiError, describeApiError } from '@/services/api/errors';
import type {
  AuditActorType,
  AuditExportRequest,
  AuditExportStatus,
  AuditFilter,
  AuditOutcome,
  AuditPolicy,
  AuditTargetType,
} from './types';

/** 表单日期控件的最小结构；只依赖 `toDate`，避免页面直接引入 dayjs 运行时。 */
export interface DayjsLike {
  toDate(): Date;
}

/** 审计过滤表单值；页面把 antd DatePicker 返回值原样交给转换函数。 */
export interface AuditFilterFormValues {
  occurredFrom?: DayjsLike | null;
  occurredTo?: DayjsLike | null;
  action?: string;
  actorId?: string;
  targetType?: AuditTargetType;
  targetId?: string;
  outcome?: AuditOutcome;
}

/** 操作者类型中文名。 */
const ACTOR_TYPE_LABELS: Record<AuditActorType, string> = {
  USER: '账号',
  SERVICE: '服务',
  SYSTEM: '系统',
};

/** 事件结果中文名。 */
const OUTCOME_LABELS: Record<AuditOutcome, string> = {
  SUCCESS: '成功',
  FAILURE: '失败',
  DENIED: '拒绝',
};

/** 事件结果对应的 antd Tag 颜色。 */
const OUTCOME_COLORS: Record<AuditOutcome, string> = {
  SUCCESS: 'green',
  FAILURE: 'red',
  DENIED: 'orange',
};

/** 审计对象类型中文名。 */
const TARGET_TYPE_LABELS: Record<AuditTargetType, string> = {
  SESSION: '会话',
  WORKSPACE: '工作空间',
  DEPARTMENT: '部门',
  FILE: '文件',
  CONVERSATION: '对话',
  KNOWLEDGE_DOCUMENT: '知识库文档',
  RESEARCH_TASK: '报告任务',
  RUN: '运行',
  ARTIFACT: '产物',
  MODEL: '模型',
  MCP_TOOL: 'MCP 工具',
  USER: '账号',
  ROLE: '角色',
  TENANT: '租户',
  AUDIT: '审计',
  AUDIT_EXPORT: '审计导出',
};

/** 导出状态中文名。 */
const EXPORT_STATUS_LABELS: Record<AuditExportStatus, string> = {
  PENDING_APPROVAL: '待审批',
  APPROVED: '已批准',
  REJECTED: '已拒绝',
  EXPIRED: '已过期',
};

/** 导出状态对应的 antd Tag 颜色。 */
const EXPORT_STATUS_COLORS: Record<AuditExportStatus, string> = {
  PENDING_APPROVAL: 'gold',
  APPROVED: 'green',
  REJECTED: 'red',
  EXPIRED: 'default',
};

/** 错误文案上下文；同一错误码在不同操作下的用户下一步不同。 */
export type AuditErrorContext =
  'query' | 'create' | 'decide' | 'download' | 'platform-list' | 'platform-decide';

/**
 * 把任意时间格式化为北京时间（Asia/Shanghai）并附上 `+08:00` 偏移。
 *
 * 界面与合同约定统一使用带偏移的 ISO 8601 语义；表格空间有限，采用
 * `YYYY-MM-DD HH:mm:ss +08:00` 的形式，完整原文在详情抽屉展示。
 *
 * @param value 带偏移的 ISO 8601 字符串或空值
 * @returns 可读时间；无法解析时保留原值或返回占位符
 */
export function formatAuditTime(value: string | null | undefined): string {
  if (!value) {
    return '—';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return `${formatBeijingIso(date)
    .replace('T', ' ')
    .replace(/\+08:00$/, '')} +08:00`;
}

/**
 * 把 Date 格式化为北京时间、带 `+08:00` 偏移的 ISO 8601 字符串。
 *
 * @param date 任意时刻
 * @returns 例如 `2026-11-19T13:00:00+08:00`
 */
export function formatBeijingIso(date: Date): string {
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
  return `${pick('year')}-${pick('month')}-${pick('day')}T${pick('hour')}:${pick(
    'minute',
  )}:${pick('second')}+08:00`;
}

/**
 * 把表单值转换为 OpenAPI `AuditFilter`。
 *
 * 空字符串与空选择一律省略，避免把 `action=` 当成有效过滤条件；
 * 时间通过 Date 转换为北京时间 `+08:00`，保证服务端按合同解析。
 *
 * @param values 表单值
 * @returns 规范化过滤器
 */
export function toAuditFilter(values: AuditFilterFormValues): AuditFilter {
  const filter: AuditFilter = {};
  const from = values.occurredFrom?.toDate();
  const to = values.occurredTo?.toDate();
  if (from) {
    filter.occurredFrom = formatBeijingIso(from);
  }
  if (to) {
    filter.occurredTo = formatBeijingIso(to);
  }
  const action = values.action?.trim();
  if (action) {
    filter.action = action;
  }
  const actorId = values.actorId?.trim();
  if (actorId) {
    filter.actorId = actorId;
  }
  if (values.targetType) {
    filter.targetType = values.targetType;
  }
  const targetId = values.targetId?.trim();
  if (targetId) {
    filter.targetId = targetId;
  }
  if (values.outcome) {
    filter.outcome = values.outcome;
  }
  return filter;
}

/**
 * 判断过滤器是否为空；空过滤器按服务端默认最近 24 小时处理。
 *
 * @param filter 查询/导出过滤器
 * @returns 是否没有有效条件
 */
export function isAuditFilterEmpty(filter: AuditFilter): boolean {
  return Object.values(filter).every(
    (value) => value === undefined || value === null || value === '',
  );
}

/**
 * 生成过滤器摘要，用于审计页、导出申请与导出列表。
 *
 * @param filter OpenAPI `AuditFilter`
 * @returns 中文条件摘要；空条件明确写出服务端默认窗口
 */
export function describeAuditFilter(filter: AuditFilter): string {
  const parts: string[] = [];
  if (filter.occurredFrom || filter.occurredTo) {
    const from = filter.occurredFrom ? formatAuditTime(filter.occurredFrom) : '最早可查时间';
    const to = filter.occurredTo ? formatAuditTime(filter.occurredTo) : '当前时间';
    parts.push(`时间 ${from} 至 ${to}`);
  } else {
    parts.push('时间 最近 24 小时（服务端默认）');
  }
  if (filter.action) {
    parts.push(`动作 ${filter.action}`);
  }
  if (filter.actorId) {
    parts.push(`操作者 ${filter.actorId}`);
  }
  if (filter.targetType || filter.targetId) {
    const type = filter.targetType ? targetTypeLabel(filter.targetType) : '对象';
    parts.push(`对象 ${filter.targetId ? `${type} / ${filter.targetId}` : type}`);
  }
  if (filter.outcome) {
    parts.push(`结果 ${outcomeLabel(filter.outcome)}`);
  }
  return parts.join('；');
}

/**
 * 返回保留策略文案；保留天数来自服务端，不在界面写死。
 *
 * @param policy 审计策略
 * @returns 固定口径的保留与只读说明
 */
export function auditPolicyNotice(policy: AuditPolicy): string {
  return `审计至少保留 ${policy.retentionDays} 天，可查最近 ${policy.maxRangeDays} 天；无删除入口。`;
}

/**
 * 把预计行数格式化为带千分位的数字。
 *
 * @param value 行数
 * @returns 中文环境数字
 */
export function formatRowCount(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return '—';
  }
  return value.toLocaleString('zh-CN');
}

/**
 * 返回操作者展示文本；有展示名时同时保留 ID，便于核对。
 *
 * @param displayName 当前展示名
 * @param actorId 操作者 ID
 * @returns 展示文本
 */
export function formatActor(displayName: string | null | undefined, actorId: string): string {
  return displayName ? `${displayName}（${actorId}）` : actorId;
}

/**
 * 返回对象展示文本。
 *
 * @param targetType 对象类型
 * @param targetId 对象 ID
 * @returns 展示文本
 */
export function formatAuditTarget(
  targetType: AuditTargetType | null | undefined,
  targetId: string | null | undefined,
): string {
  if (targetType && targetId) {
    return `${targetTypeLabel(targetType)} / ${targetId}`;
  }
  if (targetType) {
    return targetTypeLabel(targetType);
  }
  return targetId ?? '—';
}

/**
 * 操作者类型中文名。
 *
 * @param actorType 操作者类型
 * @returns 中文名
 */
export function actorTypeLabel(actorType: AuditActorType): string {
  return ACTOR_TYPE_LABELS[actorType];
}

/**
 * 事件结果中文名。
 *
 * @param outcome 结果
 * @returns 中文名
 */
export function outcomeLabel(outcome: AuditOutcome): string {
  return OUTCOME_LABELS[outcome];
}

/**
 * 事件结果 Tag 颜色。
 *
 * @param outcome 结果
 * @returns antd Tag color
 */
export function outcomeColor(outcome: AuditOutcome): string {
  return OUTCOME_COLORS[outcome];
}

/**
 * 对象类型中文名。
 *
 * @param targetType 对象类型
 * @returns 中文名
 */
export function targetTypeLabel(targetType: AuditTargetType): string {
  return TARGET_TYPE_LABELS[targetType];
}

/**
 * 导出状态中文名。
 *
 * @param status 导出状态
 * @returns 中文名
 */
export function exportStatusLabel(status: AuditExportStatus): string {
  return EXPORT_STATUS_LABELS[status];
}

/**
 * 导出状态 Tag 颜色。
 *
 * @param status 导出状态
 * @returns antd Tag color
 */
export function exportStatusColor(status: AuditExportStatus): string {
  return EXPORT_STATUS_COLORS[status];
}

/**
 * 判断导出是否已过期。
 *
 * 服务端会返回派生状态 `EXPIRED`；同时按 `expiresAt` 做一次本地兜底，
 * 避免列表在下载有效期的边界上仍显示“可下载”。
 *
 * @param request 导出请求
 * @param now 当前毫秒时间戳；测试可注入固定时间
 * @returns 是否过期
 */
export function isExportExpired(request: AuditExportRequest, now = Date.now()): boolean {
  if (request.status === 'EXPIRED') {
    return true;
  }
  if (request.status !== 'APPROVED' || !request.expiresAt) {
    return false;
  }
  const expiresAt = new Date(request.expiresAt).getTime();
  return Number.isFinite(expiresAt) && expiresAt <= now;
}

/** 下载可用性说明。 */
export interface ExportDownloadState {
  canDownload: boolean;
  reason: string;
}

/**
 * 计算下载入口状态；只有申请人本人、已批准且未过期才可下载。
 *
 * @param request 导出请求
 * @param currentUserId 当前账号 ID
 * @param now 当前毫秒时间戳
 * @returns 是否显示下载入口与对应说明
 */
export function exportDownloadState(
  request: AuditExportRequest,
  currentUserId: string | null | undefined,
  now = Date.now(),
): ExportDownloadState {
  if (isExportExpired(request, now)) {
    return { canDownload: false, reason: '已过期，请重新提交导出申请。' };
  }
  if (request.status === 'REJECTED') {
    return {
      canDownload: false,
      reason: request.decisionComment ? `已拒绝：${request.decisionComment}` : '已拒绝，不可下载。',
    };
  }
  if (request.status === 'PENDING_APPROVAL') {
    return { canDownload: false, reason: '待审批，批准后可下载。' };
  }
  if (!currentUserId || request.requestedBy !== currentUserId) {
    return { canDownload: false, reason: '仅申请人本人可下载。' };
  }
  return { canDownload: true, reason: '已批准，可在到期前下载 CSV。' };
}

/**
 * 判断当前账号是否显示审批入口。
 *
 * 自批在客户端直接隐藏入口；服务端仍会以 `RICCON-AUDIT-403` 兜底。
 *
 * @param request 导出请求
 * @param currentUserId 当前账号 ID
 * @param permissions 会话权限
 * @param permission 审批所需权限码
 * @returns 是否可审批与不可审批原因
 */
export function canDecideExport(
  request: AuditExportRequest,
  currentUserId: string | null | undefined,
  permissions: readonly string[],
  permission = 'audit.export.approve',
): { allowed: boolean; reason: string } {
  if (request.status !== 'PENDING_APPROVAL') {
    return { allowed: false, reason: '该申请已处理，不能再审批。' };
  }
  if (!currentUserId || request.requestedBy === currentUserId) {
    return { allowed: false, reason: '申请人不能审批自己的导出申请。' };
  }
  if (!permissions.includes(permission)) {
    return { allowed: false, reason: '当前账号没有导出审批权限。' };
  }
  return { allowed: true, reason: '待审批' };
}

/**
 * 把审计相关异常映射为稳定的中文下一步提示。
 *
 * 服务端 `detail` 是英文内部说明，不直接展示；按错误码与上下文给出可执行文案。
 *
 * @param error 任意异常
 * @param context 触发操作
 * @returns 中文提示
 */
export function describeAuditError(error: unknown, context: AuditErrorContext): string {
  if (!(error instanceof ApiError)) {
    return '操作未完成，请稍后重试。';
  }

  switch (error.code) {
    case 'RICCON-AUDIT-400':
      return context === 'create'
        ? '查询条件不合法，或预计行数超过导出上限；请缩小时间范围或调整过滤器。'
        : '查询条件不合法：请检查时间范围（最大 180 天且必须带时区）与分页范围。';
    case 'RICCON-AUDIT-403':
      if (context === 'download') {
        return '只有申请人本人可以下载已批准的导出；平台租户导出在 M10 不支持。';
      }
      if (context === 'platform-list' || context === 'platform-decide') {
        return '平台租户导出在 M10 不支持，或当前租户不满足平台审批条件。';
      }
      if (context === 'decide') {
        return '申请人不能审批自己的请求，或当前账号无权执行该审批。';
      }
      return '当前账号有登录权限，但不允许执行该审计操作。';
    case 'RICCON-AUDIT-409':
      if (context === 'platform-list' || context === 'platform-decide') {
        return '目标租户有多个启用的租户管理员，必须由租户内另一位管理员审批；平台不能代为审批。';
      }
      if (context === 'download') {
        return '导出状态已变化，或下载时实际行数超过上限；请刷新后重试或收窄条件。';
      }
      if (context === 'decide') {
        return '导出状态已变化（可能已被其他管理员审批），请刷新后重试。';
      }
      return '导出请求与当前状态冲突，请刷新后重试。';
    case 'RICCON-AUDIT-410':
      return '导出已过期，请重新提交导出申请。';
    case 'RICCON-AUDIT-500':
      return '审计数据校验失败，已中止本次查询；请联系管理员。';
    case 'RICCON-TENANT-404':
      return '导出请求或目标租户不存在，可能已被清理。';
    case 'RICCON-AUTH-403':
      return '没有审计访问权限；该功能仅向租户管理员与平台管理员开放。';
    default:
      if (error.status === 401) {
        return '登录状态已失效，请重新登录。';
      }
      if (error.status === 404) {
        return '导出请求或目标租户不存在，可能已被清理。';
      }
      return describeApiError(error, '操作未完成，请稍后重试。');
  }
}
