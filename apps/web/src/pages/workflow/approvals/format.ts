import { ApiError, describeApiError } from '@/services/api/errors';
import { runScopeLabel } from '../list/format';
import type { WorkflowApprovalActionKind } from '../list/workflow-api';
import type {
  WorkflowApproval,
  WorkflowApprovalDecision,
  WorkflowApprovalStatus,
  WorkflowDataScopeSummary,
} from './approval-api';

/**
 * 审批待办/详情的展示规则与 PRODUCT 固定文案。
 *
 * 页面只消费这里的映射与格式化函数；服务端契约或 PRODUCT §12.4 固定文案变化时只改一处，
 * 避免待办、详情与测试出现不一致的中文口径。
 */

/** 审批状态中文名。 */
export const APPROVAL_STATUS_LABELS: Record<WorkflowApprovalStatus, string> = {
  PENDING: '待审批',
  APPROVED: '已批准',
  REJECTED: '已拒绝',
  CANCELLED: '已取消',
};

/** 审批状态标签颜色；同时配合文字，不只靠颜色表达状态（a11y 基线）。 */
export const APPROVAL_STATUS_COLORS: Record<WorkflowApprovalStatus, string> = {
  PENDING: 'gold',
  APPROVED: 'green',
  REJECTED: 'red',
  CANCELLED: 'default',
};

/** 解锁动作中文说明；审批人据此判断批准后会发生什么。 */
export const ACTION_KIND_LABELS: Record<WorkflowApprovalActionKind, string> = {
  MCP_TOOL: '调用 MCP 工具',
  MODEL: '调用模型生成内容',
  CONTINUE: '继续执行后续节点',
};

/** PRODUCT §12.4 固定文案：审批待办空态。 */
export const APPROVAL_EMPTY_MESSAGE = '暂无待审批项，新的审批请求会显示在这里。';

/** PRODUCT §12.4 固定文案：自批。 */
export const SELF_APPROVAL_MESSAGE = '你不能审批自己发起的运行。';

/** PRODUCT §12.4 固定文案：拒绝结果。 */
export const REJECTED_RESULT_MESSAGE = '审批已拒绝，本次运行终止，后续节点未执行。';

/** 批准后的结果说明（PRODUCT 未给固定文案，按审批等待语义补齐）。 */
export const APPROVED_RESULT_MESSAGE = '审批已批准，运行将继续执行后续节点并产出结果。';

/** 编排关闭时的决定说明；关闭原因只来自部署配置，界面不展示内部细节。 */
export const WORKFLOW_UNAVAILABLE_MESSAGE =
  '编排功能已关闭，已有审批仍可查看，但暂时不能做出决定。请联系运维人员开启编排功能。';

/** 拒绝理由校验失败文案。 */
export const REJECT_COMMENT_REQUIRED_MESSAGE = '拒绝时必须填写理由（1–500 字）。';

/** 拒绝理由最大长度，与契约 `maxLength: 500` 一致。 */
export const DECISION_COMMENT_MAX_LENGTH = 500;

/**
 * 截断 UUID 或哈希用于展示。
 *
 * 完整值放在元素的 `title` 中，便于核对但不撑破 375px 布局。
 *
 * @param value 原始标识
 * @param length 保留字符数
 * @returns 截断文本；空值返回占位符
 */
export function formatShortId(value: string | null | undefined, length = 8): string {
  if (!value) {
    return '—';
  }
  return value.length <= length ? value : `${value.slice(0, length)}…`;
}

/**
 * 把创建时间到当前时间的等待时长格式化为中文。
 *
 * 审批按 `created_at ASC` 展示，“等待越久越先处理”必须可读；负值（时钟回拨）按不足一分钟处理，
 * 不显示负数。精度到分钟：秒级抖动不影响判断。
 *
 * @param createdAt 审批创建时间（带偏移 ISO 8601）
 * @param now 当前时间；测试注入固定时钟
 * @returns 例如 `2 天 3 小时`、`35 分钟`、`不足 1 分钟`
 */
export function formatWaitDuration(createdAt: string, now: Date = new Date()): string {
  const start = new Date(createdAt).getTime();
  if (Number.isNaN(start)) {
    return '—';
  }
  const diffMillis = now.getTime() - start;
  if (!Number.isFinite(diffMillis) || diffMillis < 60_000) {
    return '不足 1 分钟';
  }
  const totalMinutes = Math.floor(diffMillis / 60_000);
  if (totalMinutes < 60) {
    return `${totalMinutes} 分钟`;
  }
  const totalHours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  if (totalHours < 24) {
    return minutes > 0 ? `${totalHours} 小时 ${minutes} 分钟` : `${totalHours} 小时`;
  }
  const days = Math.floor(totalHours / 24);
  const hours = totalHours % 24;
  return hours > 0 ? `${days} 天 ${hours} 小时` : `${days} 天`;
}

/**
 * 描述数据范围摘要。
 *
 * 只使用契约字段（运行范围/部门 ID/引用计数）；契约不提供文档标题或正文，
 * 因此这里不推断任何文档内容。
 *
 * @param summary 数据范围摘要
 * @returns 例如 `本部门 · 部门 dept-123… · 引用文档 2 篇`
 */
export function describeDataScope(summary: WorkflowDataScopeSummary): string {
  const parts = [runScopeLabel(summary.runScope)];
  if (summary.runScope === 'DEPARTMENT') {
    parts.push(summary.departmentId ? `部门 ${formatShortId(summary.departmentId)}` : '部门未指定');
  }
  parts.push(`引用文档 ${summary.documentReferenceCount} 篇`);
  return parts.join(' · ');
}

/**
 * 描述批准/拒绝后将要执行的动作。
 *
 * @param approval 审批项（只需动作字段）
 * @returns 中文动作说明；MCP 工具带目标工具名
 */
export function describeAction(
  approval: Pick<WorkflowApproval, 'actionKind' | 'targetToolName'>,
): string {
  if (approval.actionKind === 'MCP_TOOL') {
    return approval.targetToolName
      ? `${ACTION_KIND_LABELS.MCP_TOOL}：${approval.targetToolName}`
      : ACTION_KIND_LABELS.MCP_TOOL;
  }
  return ACTION_KIND_LABELS[approval.actionKind];
}

/**
 * 格式化单个脱敏参数值。
 *
 * 服务端已按契约脱敏；这里只做长度截断，防止长文本撑破界面，不解析或补全原始参数。
 *
 * @param value 参数值
 * @returns 展示文本
 */
function formatPreviewValue(value: unknown): string {
  if (value === null || value === undefined) {
    return '—';
  }
  if (typeof value === 'string') {
    return value.length > 200 ? `${value.slice(0, 200)}…` : value;
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }
  try {
    const text = JSON.stringify(value);
    if (text === undefined) {
      return '—';
    }
    return text.length > 200 ? `${text.slice(0, 200)}…` : text;
  } catch {
    return '[无法展示的值]';
  }
}

/**
 * 把脱敏参数摘要格式化为逐行文本。
 *
 * @param preview 服务端返回的脱敏摘要
 * @returns 多行文本；空对象与空值有明确占位
 */
export function formatParamsPreview(preview: Record<string, unknown> | null | undefined): string {
  if (!preview) {
    return '—';
  }
  const entries = Object.entries(preview);
  if (entries.length === 0) {
    return '（无参数）';
  }
  return entries.map(([key, value]) => `${key}: ${formatPreviewValue(value)}`).join('\n');
}

/**
 * 判断是否为“编排已关闭”的 503。
 *
 * @param error 任意异常
 * @returns 是否为 `RICCON-WF-503`
 */
export function isWorkflowUnavailable(error: unknown): boolean {
  return error instanceof ApiError && error.status === 503 && error.code === 'RICCON-WF-503';
}

/**
 * 把审批相关异常归一为面向用户的中文说明。
 *
 * 403/404 使用固定文案，避免暴露对象是否存在或账号状态；409 明确提示并发决定；
 * 503 区分编排关闭与通用服务不可用。
 *
 * @param error 任意异常
 * @param fallback 未识别异常的兜底文案
 * @returns 中文说明
 */
export function describeApprovalError(error: unknown, fallback = '操作失败，请稍后重试。'): string {
  if (!(error instanceof ApiError)) {
    return fallback;
  }
  switch (error.status) {
    case 400:
      return `审批请求不合法，请检查后重试。${REJECT_COMMENT_REQUIRED_MESSAGE}`;
    case 403:
      return `无法完成审批：${SELF_APPROVAL_MESSAGE}或当前账号不在该审批的指定范围内。`;
    case 404:
      return '审批不存在，或你没有该审批的查看权限。';
    case 409:
      return '该审批已被处理（可能由其他审批人完成），请刷新后查看最新状态。';
    case 503:
      return isWorkflowUnavailable(error)
        ? WORKFLOW_UNAVAILABLE_MESSAGE
        : '服务暂时不可用，请稍后重试。';
    default:
      return describeApiError(error, fallback);
  }
}

/**
 * 决定成功后的提示文案。
 *
 * @param decision 已提交的决定
 * @returns 成功提示
 */
export function describeDecisionSuccess(decision: WorkflowApprovalDecision): string {
  return decision === 'APPROVE' ? APPROVED_RESULT_MESSAGE : REJECTED_RESULT_MESSAGE;
}
