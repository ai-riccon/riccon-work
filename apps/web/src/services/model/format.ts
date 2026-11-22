import { ApiError, describeApiError } from '@/services/api/errors';
import type { ModelPurpose } from '@/services/api/types';
import type {
  EndpointEnvironmentLabel,
  ModelCredential,
  ModelCredentialStatus,
  ModelProvider,
  ModelRouting,
  ModelStatus,
} from './types';

/** 路由用途中文名；CHAT 用于员工问答，REPORT 用于市场调研报告。 */
const PURPOSE_LABELS: Record<ModelPurpose, string> = {
  CHAT: '对话链',
  REPORT: '报告链',
};

/** 模型状态中文名。 */
const MODEL_STATUS_LABELS: Record<ModelStatus, string> = {
  ACTIVE: '启用',
  DISABLED: '已停用',
};

/** 端点部署标签中文名；文案必须让管理员一眼区分生产与演示。 */
const ENDPOINT_LABELS: Record<EndpointEnvironmentLabel, string> = {
  PRODUCTION: '生产可用',
  DEMO_ONLY: '仅演示（非生产）',
};

/** 平台 key 状态中文名。 */
const CREDENTIAL_STATUS_LABELS: Record<ModelCredentialStatus, string> = {
  ACTIVE: '可用',
  DISABLED: '已停用',
  INVALID: '已失效',
};

/** 模型管理错误文案上下文；同一错误码在不同操作下的下一步不同。 */
export type ModelErrorContext =
  | 'routing-load'
  | 'routing-save'
  | 'model-status'
  | 'credentials-load'
  | 'credential-create'
  | 'credential-rotate'
  | 'credential-disable'
  | 'credential-enable';

/**
 * 返回路由用途的中文名。
 *
 * @param purpose 路由用途
 * @returns 中文名
 */
export function purposeLabel(purpose: ModelPurpose): string {
  return PURPOSE_LABELS[purpose];
}

/**
 * 返回模型状态中文名。
 *
 * @param status 模型状态
 * @returns 中文名
 */
export function modelStatusLabel(status: ModelStatus): string {
  return MODEL_STATUS_LABELS[status];
}

/**
 * 返回模型状态标签色。
 *
 * @param status 模型状态
 * @returns antd Tag 颜色
 */
export function modelStatusColor(status: ModelStatus): string {
  return status === 'ACTIVE' ? 'green' : 'default';
}

/**
 * 返回端点部署标签中文名。
 *
 * @param label 端点标签
 * @returns 中文名；未知值返回“未知标签”，不猜测为生产可用
 */
export function endpointLabel(label: EndpointEnvironmentLabel | null | undefined): string {
  if (label === 'PRODUCTION' || label === 'DEMO_ONLY') {
    return ENDPOINT_LABELS[label];
  }
  return '未知标签';
}

/**
 * 返回端点部署标签色。
 *
 * @param label 端点标签
 * @returns antd Tag 颜色
 */
export function endpointColor(label: EndpointEnvironmentLabel | null | undefined): string {
  if (label === 'PRODUCTION') {
    return 'green';
  }
  if (label === 'DEMO_ONLY') {
    return 'orange';
  }
  return 'red';
}

/**
 * 返回平台 key 状态中文名。
 *
 * @param status key 状态
 * @returns 中文名
 */
export function credentialStatusLabel(status: ModelCredentialStatus): string {
  return CREDENTIAL_STATUS_LABELS[status];
}

/**
 * 返回平台 key 状态标签色。
 *
 * @param status key 状态
 * @returns antd Tag 颜色
 */
export function credentialStatusColor(status: ModelCredentialStatus): string {
  if (status === 'ACTIVE') {
    return 'green';
  }
  if (status === 'INVALID') {
    return 'red';
  }
  return 'default';
}

/**
 * 把带偏移的 ISO 8601 时间格式化为北京时间，并保留 `+08:00` 偏移。
 *
 * 无法解析时返回占位符，不把原始异常或服务端文本带到界面。
 *
 * @param value 带偏移的 ISO 8601 字符串或空值
 * @returns 例如 `2026-11-20 10:00:00 +08:00`
 */
export function formatModelTime(value: string | null | undefined): string {
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
 * 防御式生成 key 掩码。
 *
 * 契约只返回 `****a1b2`，但管理界面不应信任服务端一定按掩码下发：
 * 这里最多保留末 4 位并统一加 `****` 前缀，即使误传完整 key 也不会整串回显。
 *
 * @param hint 服务端 `maskedHint`
 * @returns 形如 `****a1b2` 的掩码
 */
export function credentialMask(hint: string | null | undefined): string {
  if (!hint) {
    return '****';
  }
  const withoutPrefix = hint.startsWith('****') ? hint.slice(4) : hint;
  const tail = withoutPrefix.slice(-4);
  return `****${tail}`;
}

/**
 * 判断指定 key 是否为该端点最后一把可用 key。
 *
 * “可用”只统计 ACTIVE；最后一把仍允许停用（AC-07），界面负责说明后果。
 *
 * @param credentials 当前端点 key 列表
 * @param credentialId 目标 key ID
 * @returns 是否为最后一把 ACTIVE key
 */
export function isLastActiveCredential(
  credentials: readonly ModelCredential[],
  credentialId: string,
): boolean {
  const active = credentials.filter((credential) => credential.status === 'ACTIVE');
  return active.length === 1 && active[0]?.id === credentialId;
}

/**
 * 判断草稿顺序是否与服务端链一致。
 *
 * @param chain 服务端路由链
 * @param draft 本地有序模型 ID
 * @returns 是否存在未保存修改
 */
export function isRoutingDraftDirty(chain: ModelRouting, draft: readonly string[]): boolean {
  const saved = chain.entries.map((entry) => entry.modelId);
  return saved.length !== draft.length || saved.some((modelId, index) => modelId !== draft[index]);
}

/**
 * 判断端点当前是否可被路由选择。
 *
 * `allowed=false` 覆盖“未在部署允许列表”和“仅演示端点在当前部署模式不可用”两种情况，
 * 具体原因由部署侧决定；界面只给统一、可读的下一步，不猜测内部地址。
 *
 * @param provider 端点目录行；模型找不到端点时为 undefined
 * @returns 是否可路由
 */
export function isProviderRoutable(provider: ModelProvider | undefined): boolean {
  return provider?.allowed === true;
}

/**
 * 返回不可路由端点对管理员的中文说明。
 *
 * AC-15 要求明确“仅演示端点在生产模式不可用”，同时不得出现内部地址或堆栈。
 *
 * @param provider 端点目录行；缺失时为 undefined
 * @returns 可读原因
 */
export function providerRoutabilityHint(provider: ModelProvider | undefined): string {
  if (!provider) {
    return '模型所属端点不在目录中，可能是部署配置变更；请重新加载模型目录。';
  }
  if (provider.environmentLabel === 'DEMO_ONLY') {
    return '该端点仅用于演示，生产模式仅允许客户内网网关；请改用“生产可用”的端点。';
  }
  return '该端点未被部署允许列表放行；请联系部署管理员更新端点允许列表后重试。';
}

/**
 * 把路由保存的 409 响应中的“当前版本”提取出来。
 *
 * 契约不允许返回 `currentVersion` 属性，当前版本写在 `detail` 文本中；
 * 提取失败时返回 null，界面仍提示刷新，不把它当作可自动重试的信号。
 *
 * @param detail ProblemDetail.detail 文本
 * @returns 当前版本号或 null
 */
export function extractRoutingConflictVersion(detail: string | null | undefined): number | null {
  if (!detail) {
    return null;
  }
  const matched = /current version is (\d+)/i.exec(detail);
  if (!matched) {
    return null;
  }
  const version = Number.parseInt(matched[1], 10);
  return Number.isInteger(version) && version > 0 ? version : null;
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
 * 把模型管理错误归一为固定中文文案与下一步。
 *
 * 不直接展示服务端 `detail`：契约虽然要求脱敏，管理界面仍采用固定映射，
 * 避免上游异常文本把内部地址、堆栈或凭据带进页面（AC-15/SEC-M04-009）。
 *
 * @param error 任意异常
 * @param context 操作上下文
 * @returns 面向管理员的中文说明
 */
export function describeModelError(error: unknown, context: ModelErrorContext): string {
  if (!(error instanceof ApiError)) {
    return '模型管理操作未完成，请稍后重试。';
  }

  if (error.status === 401) {
    return '登录状态已失效，请重新登录后再操作。';
  }
  if (error.status === 403 || error.code === 'RICCON-AUTH-403') {
    return context === 'routing-load' || context === 'credentials-load'
      ? '没有平台模型查看权限；模型路由与 key 仅向平台管理员开放。'
      : '没有平台模型管理权限；只有平台管理员可以保存路由、启停模型或管理 key。';
  }

  const reason = errorReason(error);
  if (error.code === 'RICCON-MODEL-400') {
    switch (reason) {
      case 'CHAIN_EMPTY':
        return '路由链不能为空：请至少保留 1 个模型后再保存。';
      case 'CHAIN_TOO_LONG':
        return '路由链最多只能包含 20 个模型，请先移除多余模型。';
      case 'DUPLICATE_MODEL':
        return '路由链中不能出现重复模型；同一模型只能保留一个位置。';
      case 'UNKNOWN_MODEL':
      case 'INVALID_MODEL_ID':
        return '路由链包含模型目录中不存在的模型；请重新加载模型目录后重试。';
      case 'INVALID_VERSION':
        return '路由版本号无效；请重新加载路由链后再保存。';
      case 'INVALID_PURPOSE':
        return '路由用途只能是对话链（CHAT）或报告链（REPORT）。';
      case 'MODEL_NOT_ROUTABLE':
        return '所选模型所属端点已停用或协议不受支持；请移除该模型后重试。';
      case 'ENDPOINT_NOT_ALLOWED':
        return '所选模型包含未被部署允许的端点，或该端点仅用于演示；生产模式仅允许客户内网网关。请联系部署管理员更新端点允许列表。';
      case 'INVALID_STATUS':
        return '模型状态只能是启用或停用。';
      case 'INVALID_API_KEY':
        return 'API key 为空、长度不在 16–512 字符，或包含首尾空白；请检查后重新提交。';
      case 'INVALID_LABEL':
        return 'key 名称必须为 1–64 个字符。';
      case 'INVALID_PRIORITY':
        return '优先级必须为 1–1000 的整数。';
      case 'PROVIDER_NOT_ALLOWED':
        return '该端点不在部署允许列表或已停用，不能新增 key；已有 key 仍可查看与停用。';
      default:
        return '请求参数不正确，请检查后重试。';
    }
  }

  if (error.code === 'RICCON-MODEL-409') {
    switch (reason) {
      case 'VERSION_CONFLICT': {
        const version = extractRoutingConflictVersion(error.message);
        return version
          ? `路由配置已被其他管理员修改（服务端当前版本 v${version}），你的修改尚未保存。请重新加载最新链后再调整；本页不会自动覆盖。`
          : '路由配置已被其他管理员修改，你的修改尚未保存。请重新加载最新链后再调整；本页不会自动覆盖。';
      }
      case 'DUPLICATE_CREDENTIAL':
        return '相同 key 已存在（可能是重复提交或轮换前的旧 key）；请确认后改用另一把 key。';
      case 'CREDENTIAL_STATE_CONFLICT':
        return '该 key 的状态已被其他管理员更新；请刷新列表后重试。';
      default:
        return '当前状态不允许该操作，请刷新后重试。';
    }
  }

  if (error.status === 404) {
    return context === 'credentials-load' || context === 'credential-create'
      ? '端点不存在，可能已被部署配置移除；请重新加载端点目录。'
      : '模型、端点或 key 不存在，可能已被移除；请刷新后重试。';
  }

  return describeApiError(error, '模型管理操作未完成，请稍后重试。');
}
