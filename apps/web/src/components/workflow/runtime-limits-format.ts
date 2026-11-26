import { ApiError } from '@/services/api/errors';
import type { WorkflowRetentionPolicy } from './runtime-limits-api';

/**
 * 运行限制展示与 429 超限文案的纯逻辑。
 *
 * 集中在此模块的原因：
 * 1. 运行限制面板（只读视图）与运行准入/历史/编辑器的 429 文案必须使用同一套
 *    “限制——当前值——下一步”口径，避免页面各写一份中文；
 * 2. 后端 429 `ProblemDetail.detail` 是稳定可解析文本（`tenant|workflow active run limit
 *    reached (limit=N, current=M)`），解析失败时仍给出含等待/调整流程/联系管理员的兜底，
 *    不退回“请求过于频繁”这类无法行动的空泛提示。
 */

/** 后端未配置关闭原因时的固定英文文案（与 API 的 `DEFAULT_DISABLED_REASON` 一致）。 */
const DEFAULT_DISABLED_REASON = 'workflow orchestration is disabled by deployment configuration';

/** 运行准入 429 的 `detail` 解析规则；只读取 scope/limit/current，不依赖其余措辞。 */
const RUN_LIMIT_DETAIL_PATTERN =
  /^(tenant|workflow)\s+active run limit reached\s*\(limit=(\d+),\s*current=(\d+)\)/i;

/** 运行限制的并发维度。 */
export type WorkflowRunLimitScope = 'tenant' | 'workflow' | 'unknown';

/** 从 429 `detail` 解析出的限制上下文。 */
export interface WorkflowRunLimitExceededDetail {
  /** 超限维度：租户并发、单流程并发，或无法解析的兜底 */
  scope: WorkflowRunLimitScope;
  /** 当前生效上限；无法解析时为 null */
  limit: number | null;
  /** 当前活跃运行数；无法解析时为 null */
  current: number | null;
}

/**
 * 把秒数格式化为中文可读时长，同时保留原始秒数。
 *
 * 限制值如 300 秒、1800 秒直接展示不利于用户判断，因此附加“5 分钟/30 分钟”；
 * 原始秒数保留，便于与部署配置逐字核对。
 *
 * @param seconds 秒数
 * @returns 例如 `300 秒（5 分钟）`；非法值返回占位符
 */
export function formatLimitSeconds(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds <= 0) {
    return '—';
  }
  const whole = Math.floor(seconds);
  const minutes = Math.floor(whole / 60);
  const restSeconds = whole % 60;
  if (minutes === 0) {
    return `${whole} 秒`;
  }
  if (restSeconds === 0 && minutes % 60 === 0) {
    return `${whole} 秒（${minutes / 60} 小时）`;
  }
  if (restSeconds === 0) {
    return `${whole} 秒（${minutes} 分钟）`;
  }
  if (minutes < 60) {
    return `${whole} 秒（${minutes} 分 ${restSeconds} 秒）`;
  }
  return `${whole} 秒（${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分 ${restSeconds} 秒）`;
}

/**
 * 把字节数格式化为 MiB，并保留精确字节数。
 *
 * 契约默认结果上限是 5 MiB；显示一位小数避免非整数上限被四舍五入成误导值，
 * 括号内字节数用于与部署配置核对。
 *
 * @param bytes 字节数
 * @returns 例如 `5 MiB（5,242,880 字节）`；非法值返回占位符
 */
export function formatLimitBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) {
    return '—';
  }
  const whole = Math.floor(bytes);
  const mib = whole / (1024 * 1024);
  const mibText = Number.isInteger(mib) ? String(mib) : mib.toFixed(1);
  return `${mibText} MiB（${whole.toLocaleString('en-US')} 字节）`;
}

/**
 * 保留策略来源的中文标签。
 *
 * @param policy 保留策略来源
 * @returns 中文说明
 */
export function retentionPolicyLabel(policy: WorkflowRetentionPolicy): string {
  return policy === 'TENANT_POLICY' ? '租户策略覆盖' : '部署默认';
}

/**
 * 把关闭原因转成面向用户的中文说明。
 *
 * 后端未配置原因时返回固定英文基线文案；直接展示英文会让用户无法理解，
 * 因此只在原因等于基线时翻译，部署方自定义原因原样展示，避免篡改运维信息。
 *
 * @param reason 后端 `disabledReason`
 * @returns 中文关闭原因
 */
export function describeRuntimeLimitsDisabledReason(reason?: string | null): string {
  const trimmed = reason?.trim();
  if (!trimmed) {
    return '部署配置未提供关闭原因。';
  }
  if (trimmed === DEFAULT_DISABLED_REASON) {
    return '部署配置已关闭编排功能。';
  }
  return trimmed;
}

/**
 * 解析运行准入 429 的后端 `detail`。
 *
 * 只接受 `RICCON-WF-429`：其他 429（登录限流、队列背压、M04 配额）仍走各自既有文案，
 * 不能把配额或限流错误误标成工作流并发超限。
 *
 * @param error 任意异常
 * @returns 解析结果；非 `RICCON-WF-429` 返回 null
 */
export function parseWorkflowRunLimitExceeded(
  error: unknown,
): WorkflowRunLimitExceededDetail | null {
  if (!(error instanceof ApiError) || error.status !== 429 || error.code !== 'RICCON-WF-429') {
    return null;
  }
  const detail = error.problem?.detail || error.message || '';
  const match = RUN_LIMIT_DETAIL_PATTERN.exec(detail.trim());
  if (!match) {
    return { scope: 'unknown', limit: null, current: null };
  }
  return {
    scope: match[1].toLowerCase() as WorkflowRunLimitScope,
    limit: Number(match[2]),
    current: Number(match[3]),
  };
}

/**
 * 生成运行并发超限的中文提示。
 *
 * 必须包含具体限制值、当前值与下一步（等待、调整流程、联系管理员），满足 M06-AC-17；
 * 解析不到数值时仍给出同样的行动建议，不再返回“请求过于频繁，请稍后重试”式空话。
 *
 * @param error 任意异常
 * @returns 中文提示；非 `RICCON-WF-429` 返回 null，由调用方保留原有分支
 */
export function describeWorkflowRunLimitExceeded(error: unknown): string | null {
  const parsed = parseWorkflowRunLimitExceeded(error);
  if (!parsed) {
    return null;
  }
  const numbers =
    parsed.limit != null && parsed.current != null
      ? `（当前 ${parsed.current} 个，上限 ${parsed.limit} 个）`
      : '';
  if (parsed.scope === 'tenant') {
    return `租户并发运行已达上限${numbers}。请等待已有运行结束后重试；若多个流程同时运行，可先调整流程错峰执行；长期不足时请联系租户管理员调整部署配置的租户并发上限。`;
  }
  if (parsed.scope === 'workflow') {
    return `该流程并发运行已达上限${numbers}。请等待该流程的运行结束后重试；也可以调整流程减少同时运行；长期不足时请联系租户管理员调整部署配置的单流程并发上限。`;
  }
  return '并发运行已达上限。请等待已有运行结束后重试；也可以调整流程减少同时运行，或联系租户管理员调整部署配置的运行限制。';
}
