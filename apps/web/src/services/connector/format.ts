import { ApiError, describeApiError } from '@/services/api/errors';
import type {
  Connector,
  ConnectorItemStatus,
  ConnectorStatus,
  ConnectorSyncStatus,
  ConnectorTestCategory,
  ConnectorVisibility,
} from './types';

/** 连接器状态中文名。 */
const CONNECTOR_STATUS_LABELS: Record<ConnectorStatus, string> = {
  ACTIVE: '启用',
  PAUSED: '暂停',
  ERROR: '同步失败',
};

/** 默认可见范围中文名。 */
const VISIBILITY_LABELS: Record<ConnectorVisibility, string> = {
  TENANT: '全租户',
  DEPT: '指定部门',
};

/** 最近同步结果中文名。 */
const SYNC_STATUS_LABELS: Record<ConnectorSyncStatus, string> = {
  SUCCESS: '成功',
  PARTIAL: '部分成功',
  ERROR: '失败',
};

/** 同步条目状态中文名。 */
const ITEM_STATUS_LABELS: Record<ConnectorItemStatus, string> = {
  PENDING: '待同步',
  SYNCED: '已同步',
  SKIPPED: '已跳过',
  FAILED: '失败',
  DELETED: '已删除',
};

/** 连接测试失败分类中文名。 */
const TEST_CATEGORY_LABELS: Record<ConnectorTestCategory, string> = {
  AUTH: '凭据被拒绝',
  NETWORK: '网络不可达',
  ENDPOINT: '端点不可用',
  CONFIG: '配置不可用',
};

/**
 * 连接器失败码中文说明。
 *
 * 码来自 ADR-0023 §6.6 与 Worker 同步实现；未知码保留“同步失败”并原样展示错误码，
 * 不在前端猜原因。
 */
const ERROR_CODE_LABELS: Record<string, string> = {
  KB_CONNECTOR_AUTH: '源端认证失败',
  KB_CONNECTOR_CONFIG: '连接器配置不可用',
  KB_CONNECTOR_CREDENTIAL: '凭据不可用',
  KB_CONNECTOR_DOCUMENT_MISSING: '关联文档缺失',
  KB_CONNECTOR_EXTERNAL_ID_TOO_LONG: '对象路径过长',
  KB_CONNECTOR_INTERNAL: '内部错误',
  KB_CONNECTOR_OBJECT_FAILED: '对象处理失败',
  KB_CONNECTOR_OBJECT_LIMIT: '对象数量超限',
  KB_CONNECTOR_OBJECT_TOO_LARGE: '单对象超过 100 MB',
  KB_CONNECTOR_TIMEOUT: '同步超时',
  KB_CONNECTOR_TOTAL_BYTES_LIMIT: '同步总量超过 2 GB',
  KB_CONNECTOR_TRUNCATED: '列表被截断（未传播删除）',
  KB_CONNECTOR_TYPE_UNSUPPORTED: '文件类型不支持',
  KB_CONNECTOR_UNREACHABLE: '端点不可达',
};

/** 连接器管理错误文案上下文；同一错误码在不同操作下的下一步不同。 */
export type ConnectorErrorContext =
  'list' | 'create' | 'update' | 'credential' | 'test' | 'sync' | 'items' | 'delete';

/**
 * 返回连接器状态中文名。
 *
 * @param status 连接器状态
 * @returns 中文名
 */
export function connectorStatusLabel(status: ConnectorStatus): string {
  return CONNECTOR_STATUS_LABELS[status];
}

/**
 * 返回连接器状态 Tag 颜色。
 *
 * @param status 连接器状态
 * @returns antd Tag 颜色
 */
export function connectorStatusColor(status: ConnectorStatus): string {
  if (status === 'ACTIVE') {
    return 'green';
  }
  if (status === 'ERROR') {
    return 'red';
  }
  return 'default';
}

/**
 * 返回默认可见范围中文名。
 *
 * @param visibility 可见范围
 * @returns 中文名
 */
export function connectorVisibilityLabel(visibility: ConnectorVisibility): string {
  return VISIBILITY_LABELS[visibility];
}

/**
 * 返回最近同步结果中文名。
 *
 * @param status 同步结果；未同步为 null
 * @returns 中文名
 */
export function connectorSyncStatusLabel(status: ConnectorSyncStatus | null | undefined): string {
  return status ? SYNC_STATUS_LABELS[status] : '尚未同步';
}

/**
 * 返回最近同步结果 Tag 颜色。
 *
 * @param status 同步结果；未同步为 null
 * @returns antd Tag 颜色
 */
export function connectorSyncStatusColor(status: ConnectorSyncStatus | null | undefined): string {
  if (status === 'SUCCESS') {
    return 'green';
  }
  if (status === 'PARTIAL') {
    return 'gold';
  }
  if (status === 'ERROR') {
    return 'red';
  }
  return 'default';
}

/**
 * 返回同步条目状态中文名。
 *
 * @param status 条目状态
 * @returns 中文名
 */
export function connectorItemStatusLabel(status: ConnectorItemStatus): string {
  return ITEM_STATUS_LABELS[status];
}

/**
 * 返回同步条目状态 Tag 颜色。
 *
 * @param status 条目状态
 * @returns antd Tag 颜色
 */
export function connectorItemStatusColor(status: ConnectorItemStatus): string {
  switch (status) {
    case 'SYNCED':
      return 'green';
    case 'PENDING':
      return 'blue';
    case 'SKIPPED':
      return 'gold';
    case 'FAILED':
      return 'red';
    default:
      return 'default';
  }
}

/**
 * 返回连接测试失败分类中文名。
 *
 * @param category 分类；成功时为 null
 * @returns 中文名；成功时返回空字符串
 */
export function connectorTestCategoryLabel(
  category: ConnectorTestCategory | null | undefined,
): string {
  return category ? TEST_CATEGORY_LABELS[category] : '';
}

/**
 * 把失败码转换为中文说明。
 *
 * @param code 失败码
 * @returns 中文说明；未知码返回“同步失败”
 */
export function describeConnectorErrorCode(code: string | null | undefined): string {
  if (!code) {
    return '—';
  }
  return ERROR_CODE_LABELS[code] ?? '同步失败';
}

/**
 * 把带偏移的 ISO 8601 时间格式化为北京时间，并保留 `+08:00` 偏移。
 *
 * 无法解析时返回占位符，不把原始异常或服务端文本带到界面。
 *
 * @param value 带偏移的 ISO 8601 字符串或空值
 * @returns 例如 `2026-11-24 10:00:00 +08:00`
 */
export function formatConnectorTime(value: string | null | undefined): string {
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
  return `${pick('year')}-${pick('month')}-${pick('day')} ${pick('hour')}:${pick(
    'minute',
  )}:${pick('second')} +08:00`;
}

/**
 * 防御式展示源对象 ID。
 *
 * 契约要求只返回末段（最后 2 个路径段或 32 字符），但界面不信任服务端一定截断：
 * 这里再次取末两段并限制长度，避免把完整对象 key 或 bucket 路径展示给管理员
 * （ADR-0023 §6.5）。
 *
 * @param externalId 契约返回的展示值
 * @returns 末两段或 32 字符以内的安全展示值
 */
export function formatConnectorExternalId(externalId: string | null | undefined): string {
  if (!externalId) {
    return '—';
  }
  const segments = externalId.split('/').filter((segment) => segment.length > 0);
  const tail = segments.length > 0 ? segments.slice(-2).join('/') : externalId;
  return tail.length > 32 ? `…${tail.slice(-32)}` : tail;
}

/**
 * 防御式展示凭据掩码。
 *
 * 契约只返回 `credentialMaskedHint`（如 `AKIA…7F2`），但管理界面不应信任服务端
 * 一定按掩码下发：已带省略号的掩码原样展示；疑似完整凭据只保留首 4 末 3 位，
 * 避免整串回显（SEC-CONN-001）。
 *
 * @param hint 服务端 `credentialMaskedHint`
 * @returns 掩码或“未配置”
 */
export function formatCredentialHint(hint: string | null | undefined): string {
  if (!hint) {
    return '未配置';
  }
  const normalized = hint.trim();
  if (normalized.length === 0) {
    return '未配置';
  }
  if (normalized.includes('…') || normalized.includes('...')) {
    return normalized.length > 64 ? `${normalized.slice(0, 64)}…` : normalized;
  }
  if (normalized.length <= 7) {
    return `${normalized.slice(0, 1)}…${normalized.slice(-1)}`;
  }
  return `${normalized.slice(0, 4)}…${normalized.slice(-3)}`;
}

/**
 * 判断连接器是否正在同步。
 *
 * 服务端受理同步后写 `lastSyncStartedAt` 并清空 `lastSyncFinishedAt`；Worker 完成
 * （含失败）后写 finish。因此 started 非空且 finish 为空即“排队/运行中”，此时
 * 禁止重复触发手动同步，避免 409。
 *
 * @param connector 连接器
 * @returns 是否同步中
 */
export function isConnectorSyncInProgress(connector: Connector): boolean {
  return Boolean(connector.lastSyncStartedAt) && !connector.lastSyncFinishedAt;
}

/**
 * 读取 ProblemDetail 的稳定原因码。
 *
 * @param error API 错误
 * @returns reason 或 undefined
 */
function errorReason(error: ApiError): string | undefined {
  return error.problem?.reason ?? undefined;
}

/**
 * 把连接器管理错误归一为固定中文文案与下一步。
 *
 * 服务端连接器 400 的 `detail` 是英文字段说明，不直接展示；这里按关键短语映射为
 * 中文，并保留稳定错误码，便于管理员报障时对日志与后端排查。
 *
 * @param error 任意异常
 * @param context 操作上下文
 * @returns 面向管理员的中文说明
 */
export function describeConnectorError(error: unknown, context: ConnectorErrorContext): string {
  if (!(error instanceof ApiError)) {
    return '连接器操作未完成，请稍后重试。';
  }

  if (error.status === 401) {
    return '登录状态已失效，请重新登录后再管理连接器。';
  }
  if (error.status === 403 || error.code === 'RICCON-AUTH-403') {
    return context === 'list' || context === 'items'
      ? '没有连接器查看权限（RICCON-AUTH-403）；连接器仅向租户管理员开放。'
      : '没有连接器管理权限（RICCON-AUTH-403）；只有租户管理员可以配置、测试、同步或删除连接器。';
  }

  if (error.status === 404) {
    return '连接器不存在或不属于当前租户（RICCON-TENANT-404）。为避免泄露其他租户数据，页面不会区分“不存在”和“无权访问”。';
  }

  if (error.code === 'RICCON-KB-400') {
    const detail = error.message;
    if (detail.includes('defaultVisibility is required')) {
      return '必须显式选择默认可见范围（TENANT 或 DEPT）；缺省会被服务端以 400 RICCON-KB-400 拒绝。';
    }
    if (detail.includes('defaultDepartmentId is required')) {
      return '默认可见范围为 DEPT 时必须选择启用部门（400 RICCON-KB-400）。';
    }
    if (detail.includes('defaultDepartmentId must be an active department')) {
      return '所选部门不存在或已停用；请选择启用部门后重试（400 RICCON-KB-400）。';
    }
    if (detail.includes('endpointId is not in the deployment allowlist')) {
      return 'endpointId 不在部署允许列表（RICCON_CONNECTOR_ENDPOINTS）中；请填写部署管理员提供的端点 ID，不能填写 URL（400 RICCON-KB-400）。';
    }
    if (detail.includes('unknown field')) {
      return '请求包含当前后端不认识的字段；请刷新页面后重试（400 RICCON-KB-400）。';
    }
    if (detail.includes('credential must not contain')) {
      return '访问键或密钥包含首尾空白；请删除首尾空白后重新提交（400 RICCON-KB-400）。';
    }
    if (detail.includes('at least one field is required')) {
      return '至少需要修改一个字段后再保存（400 RICCON-KB-400）。';
    }
    if (detail.includes('documents must be KEEP or SOFT_DELETE')) {
      return '文档处置只能选择“保留”或“一并软删除”（400 RICCON-KB-400）。';
    }
    if (detail.includes('type must be S3')) {
      return '连接器类型只能是 S3 兼容目录（400 RICCON-KB-400）。';
    }
    return '请求参数不符合连接器契约（RICCON-KB-400）；请检查名称、端点 ID、bucket、默认可见范围与配置后重试。';
  }

  if (error.code === 'RICCON-KB-409') {
    if (context === 'sync' || error.message.includes('sync is already')) {
      return '该连接器已有排队或运行中的同步（409 RICCON-KB-409）；请等待当前同步完成并刷新后再试。';
    }
    if (context === 'delete') {
      return '连接器状态已变化，删除未完成；请刷新列表后重试（409 RICCON-KB-409）。';
    }
    if (error.message.includes('connector name already exists') || context === 'create') {
      return '连接器名称已存在（409 RICCON-KB-409）；软删除后同名也不能重建，请改名后重试。';
    }
    return '当前状态不允许该连接器操作，请刷新后重试（409 RICCON-KB-409）。';
  }

  if (error.status === 503 && error.code === 'RICCON-KB-503') {
    return '连接器功能未启用或同步队列不可用（RICCON-KB-503）；请联系部署管理员检查 RICCON_CONNECTOR_ENABLED 与同步队列。';
  }

  // 保留 reason 读取，后端未来补充稳定原因码时无需改调用点。
  const reason = errorReason(error);
  if (reason) {
    return `${describeApiError(error, '连接器操作未完成，请稍后重试。')}（${reason}）`;
  }

  return describeApiError(error, '连接器操作未完成，请稍后重试。');
}
