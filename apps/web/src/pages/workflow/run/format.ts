import {
  APPROVAL_STATUS_COLORS,
  APPROVAL_STATUS_LABELS,
  describeAction,
  describeDataScope,
  formatParamsPreview,
  formatShortId,
} from '../approvals/format';
import type { WorkflowTagColor } from '@/components/workflow/accessible-tag';
import { NODE_TYPE_LABELS } from '../list/format';
import type {
  WorkflowApprovalStatus,
  WorkflowErrorCode,
  WorkflowNodeRun,
  WorkflowNodeStatus,
  WorkflowResult,
  WorkflowResultFormat,
  WorkflowResultStatus,
  WorkflowRunApproval,
  WorkflowRunDetail,
  WorkflowRunStatus,
} from './run-api';

/**
 * 运行详情页的展示规则与固定文案。
 *
 * 页面只消费这里的映射与格式化函数；契约枚举或中文口径变化时只改一处。审批状态、动作与
 * 数据范围摘要复用审批页（T13）的同一套映射，避免运行详情与审批待办出现两套文案。
 */

/** 运行状态中文名。 */
export const RUN_STATUS_LABELS: Record<WorkflowRunStatus, string> = {
  PENDING: '排队中',
  RUNNING: '运行中',
  WAITING_APPROVAL: '等待审批',
  SUCCEEDED: '成功',
  FAILED: '失败',
  CANCELLED: '已取消',
};

/** 运行状态标签颜色；同时配合文字与图标，不只靠颜色表达状态。 */
export const RUN_STATUS_COLORS: Record<WorkflowRunStatus, WorkflowTagColor> = {
  PENDING: 'default',
  RUNNING: 'processing',
  WAITING_APPROVAL: 'gold',
  SUCCEEDED: 'green',
  FAILED: 'red',
  CANCELLED: 'default',
};

/** 节点状态中文名。 */
export const NODE_STATUS_LABELS: Record<WorkflowNodeStatus, string> = {
  PENDING: '等待',
  RUNNING: '运行中',
  WAITING_APPROVAL: '等待审批',
  SUCCEEDED: '成功',
  FAILED: '失败',
  SKIPPED: '跳过',
  CANCELLED: '已取消',
};

/** 节点状态标签颜色。 */
export const NODE_STATUS_COLORS: Record<WorkflowNodeStatus, WorkflowTagColor> = {
  PENDING: 'default',
  RUNNING: 'processing',
  WAITING_APPROVAL: 'gold',
  SUCCEEDED: 'green',
  FAILED: 'red',
  SKIPPED: 'default',
  CANCELLED: 'default',
};

/** 结果保存状态中文名。 */
export const RESULT_STATUS_LABELS: Record<WorkflowResultStatus, string> = {
  NONE: '无结果',
  SAVED: '已保存',
  OVER_LIMIT: '超过大小上限未保存',
};

/** 结果格式中文名。 */
export const RESULT_FORMAT_LABELS: Record<WorkflowResultFormat, string> = {
  TEXT: '纯文本',
  MARKDOWN: 'Markdown',
  JSON: 'JSON',
};

/** 稳定错误码的中文兜底文案；服务端返回的 `errorMessage` 优先展示。 */
export const ERROR_CODE_LABELS: Record<WorkflowErrorCode, string> = {
  WF_INVALID_GRAPH: '流程结构无效',
  WF_NODE_LIMIT: '节点数超过限制',
  WF_STEP_LIMIT: '运行步数超过限制',
  WF_RUN_TIMEOUT: '运行超时',
  WF_NODE_TIMEOUT: '节点超时',
  WF_MODEL_UNAVAILABLE: '模型不可用',
  WF_MODEL_OUTPUT_INVALID: '模型输出不符合要求',
  WF_RETRIEVAL_FAILED: '知识库检索失败',
  WF_CONDITION_INVALID: '条件无法求值',
  WF_TOOL_NOT_AUTHORIZED: '工具未授权或已停用',
  WF_TOOL_RESULT_LIMIT: '工具结果超过限制',
  WF_APPROVAL_REQUIRED: '需要重新审批',
  WF_NO_APPROVER: '没有可用审批人',
  WF_APPROVAL_REJECTED: '审批被拒绝',
  WF_SIDE_EFFECT_UNKNOWN: '外部副作用状态未知',
  WF_PLAN_DRIFT: '审批参数与计划不一致',
  WF_RESULT_LIMIT: '结果超过大小上限',
  WF_FEATURE_DISABLED: '编排功能已关闭',
  WF_CANCELLED: '运行已取消',
  WF_INTERNAL: '内部错误',
};

/** 审批人角色中文名；与编辑器角色审批选项保持一致。 */
const APPROVAL_ROLE_LABELS: Record<string, string> = {
  TENANT_ADMIN: '租户管理员',
  WORKFLOW_EDITOR: '流程编辑者',
  MEMBER: '普通成员',
};

/** 页面固定说明：轮询策略。 */
export const AUTO_REFRESH_NOTE =
  '页面每 3 秒检查一次运行状态；只有 updatedAt 变化才重绘，运行结束后停止轮询。';

/** 页面固定说明：内容最小化。 */
export const SECURITY_NOTICE =
  '页面只展示服务端生成的摘要与结果，不包含提示词、完整工具参数、凭据或审批人无权访问的正文。';

/** T12b 之前的重试入口说明；点击不提交任何请求。 */
export const RETRY_T12B_NOTICE =
  '重试与取消操作将在运行历史页接入；当前只展示入口，不会提交任何请求。';

/** 引用遮蔽说明。 */
export const CITATION_MASK_NOTE =
  '引用按当前账号的文档权限重新校验；不可访问时只显示状态，不显示标题、片段或定位。';

/** 结果预览字符上限；超过时明确提示并提供展开全文。 */
export const RESULT_PREVIEW_LIMIT = 2000;

/** 结果 JSON 美化上限；超过时直接展示原文，避免大文本解析卡顿。 */
const RESULT_JSON_PRETTY_LIMIT = 100_000;

/** 结果预览结构。 */
export interface ResultPreview {
  /** 当前展示文本 */
  text: string;
  /** 是否被预览截断 */
  truncated: boolean;
  /** 原始字符数 */
  totalChars: number;
}

/**
 * 判断运行是否已进入终态。
 *
 * 终态后前端停止轮询；最终结果只在终态读取一次（ADR-0024 §7.4）。
 *
 * @param status 运行状态
 * @returns 是否终态
 */
export function isTerminalRunStatus(status: WorkflowRunStatus): boolean {
  return status === 'SUCCEEDED' || status === 'FAILED' || status === 'CANCELLED';
}

/**
 * 判断运行是否仍在推进（含等待审批）。
 *
 * @param status 运行状态
 * @returns 是否活跃
 */
export function isActiveRunStatus(status: WorkflowRunStatus): boolean {
  return status === 'PENDING' || status === 'RUNNING' || status === 'WAITING_APPROVAL';
}

/**
 * 返回运行状态文案。
 *
 * @param status 运行状态
 * @returns 中文文案
 */
export function runStatusLabel(status: WorkflowRunStatus): string {
  return RUN_STATUS_LABELS[status];
}

/**
 * 返回节点状态文案。
 *
 * @param status 节点状态
 * @returns 中文文案
 */
export function nodeStatusLabel(status: WorkflowNodeStatus): string {
  return NODE_STATUS_LABELS[status];
}

/**
 * 返回审批状态文案。
 *
 * @param status 审批状态
 * @returns 中文文案
 */
export function approvalStatusLabel(status: WorkflowApprovalStatus): string {
  return APPROVAL_STATUS_LABELS[status];
}

/**
 * 返回审批状态标签颜色。
 *
 * @param status 审批状态
 * @returns Tag 颜色
 */
export function approvalStatusColor(status: WorkflowApprovalStatus): WorkflowTagColor {
  return APPROVAL_STATUS_COLORS[status];
}

/**
 * 返回稳定错误码的中文兜底文案。
 *
 * @param code 错误码或空值
 * @returns 中文说明；空值返回“未知错误”
 */
export function describeErrorCode(code: WorkflowErrorCode | null | undefined): string {
  return code ? ERROR_CODE_LABELS[code] : '未知错误';
}

/**
 * 组合失败原因：中文错误码 + 服务端脱敏消息。
 *
 * 服务端消息优先于兜底文案；两者都没有时返回 null，由页面决定是否展示失败区块。
 *
 * @param errorCode 稳定错误码
 * @param errorMessage 服务端脱敏消息
 * @returns 展示文本或 null
 */
export function describeFailure(
  errorCode: WorkflowErrorCode | null | undefined,
  errorMessage: string | null | undefined,
): string | null {
  if (!errorCode && !errorMessage) {
    return null;
  }
  const label = describeErrorCode(errorCode);
  return errorMessage ? `${label}：${errorMessage}` : label;
}

/**
 * 格式化毫秒耗时。
 *
 * @param durationMs 毫秒；空值或负数返回占位符
 * @returns 例如 `850 ms`、`1.2 s`、`2 分 5 秒`
 */
export function formatDuration(durationMs: number | null | undefined): string {
  if (durationMs === null || durationMs === undefined || Number.isNaN(durationMs)) {
    return '—';
  }
  if (durationMs < 0) {
    return '—';
  }
  if (durationMs < 1000) {
    return `${Math.round(durationMs)} ms`;
  }
  if (durationMs < 60_000) {
    return `${(durationMs / 1000).toFixed(1)} s`;
  }
  const minutes = Math.floor(durationMs / 60_000);
  const seconds = Math.round((durationMs % 60_000) / 1000);
  return `${minutes} 分 ${seconds} 秒`;
}

/**
 * 格式化结果字节数。
 *
 * @param bytes 字节数
 * @returns 例如 `512 B`、`1.5 KB`、`3.20 MB`；非法值为占位符
 */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || Number.isNaN(bytes) || bytes < 0) {
    return '—';
  }
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}

/**
 * 规范化结果文本：JSON 在可控大小内美化，其余原样返回。
 *
 * 只处理展示，不改变服务端保存的原始结果，也不截断。
 *
 * @param result 最终结果
 * @returns 展示文本；无结果返回空串
 */
export function formatResultText(result: WorkflowResult | null | undefined): string {
  const text = result?.text ?? '';
  if (!text) {
    return '';
  }
  if (result?.format !== 'JSON' || text.length > RESULT_JSON_PRETTY_LIMIT) {
    return text;
  }
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
}

/**
 * 生成结果预览。
 *
 * 超过上限时明确截断并返回总字符数；页面据此显示“仅显示前 N 个字符”，
 * 不静默截断用户可查看的结果。
 *
 * @param text 结果文本
 * @param expanded 是否已展开全文
 * @returns 预览文本、截断标记与总字符数
 */
export function previewResultText(
  text: string | null | undefined,
  expanded: boolean,
): ResultPreview {
  const full = text ?? '';
  if (expanded || full.length <= RESULT_PREVIEW_LIMIT) {
    return { text: full, truncated: false, totalChars: full.length };
  }
  return {
    text: full.slice(0, RESULT_PREVIEW_LIMIT),
    truncated: true,
    totalChars: full.length,
  };
}

/**
 * 返回审批计数摘要。
 *
 * @param detail 运行详情
 * @returns 例如 `待审批 1 · 已批准 1 · 已拒绝 0`；无审批节点返回“无审批节点”
 */
export function approvalCountsText(
  detail: Pick<
    WorkflowRunDetail,
    'approvalStatus' | 'pendingApprovalCount' | 'approvedApprovalCount' | 'rejectedApprovalCount'
  >,
): string {
  if (
    !detail.approvalStatus &&
    detail.pendingApprovalCount === 0 &&
    detail.approvedApprovalCount === 0 &&
    detail.rejectedApprovalCount === 0
  ) {
    return '无审批节点';
  }
  return `待审批 ${detail.pendingApprovalCount} · 已批准 ${detail.approvedApprovalCount} · 已拒绝 ${detail.rejectedApprovalCount}`;
}

/**
 * 描述审批人解析方式。
 *
 * 只使用契约元数据；不展示账号名称、邮箱或完整 ID 列表，避免泄露审批人目录。
 *
 * @param approval 审批记录
 * @returns 中文说明
 */
export function describeApprovalAssignee(
  approval: Pick<WorkflowRunApproval, 'assigneeType' | 'assigneeRole' | 'assigneeUserIds'>,
): string {
  if (approval.assigneeType === 'ROLE') {
    const role = approval.assigneeRole
      ? (APPROVAL_ROLE_LABELS[approval.assigneeRole] ?? '指定角色')
      : '指定角色';
    return `角色审批：${role}`;
  }
  const count = approval.assigneeUserIds?.length ?? 0;
  return count > 0 ? `指定用户审批（${count} 人）` : '指定用户审批';
}

/** 审批动作说明；复用审批页同一映射，保证运行详情与审批待办文案一致。 */
export const describeApprovalAction = describeAction;

/** 审批数据范围摘要文案；复用审批页同一映射。 */
export const describeApprovalDataScope = describeDataScope;

/** 审批脱敏参数摘要格式化；复用审批页同一实现。 */
export const formatApprovalParams = formatParamsPreview;

/** 短 ID 展示；完整值由调用方放入 title。 */
export const formatRunShortId = formatShortId;

/**
 * 返回节点类型中文名。
 *
 * @param nodeType 节点类型
 * @returns 中文名
 */
export function nodeTypeLabel(node: Pick<WorkflowNodeRun, 'nodeType'>): string {
  return NODE_TYPE_LABELS[node.nodeType];
}

/**
 * 查找当前节点名。
 *
 * `currentNodeId` 是图内节点 key；同名节点可能有多条尝试记录，取最后一条的展示名。
 * 运行终态后不再标记当前节点。
 *
 * @param detail 运行详情
 * @returns 当前节点名；非活跃或未返回时为空
 */
export function findCurrentNodeName(detail: WorkflowRunDetail): string | null {
  if (!isActiveRunStatus(detail.status) || !detail.currentNodeId) {
    return null;
  }
  const candidates = (detail.nodes ?? []).filter((node) => node.nodeId === detail.currentNodeId);
  const latest = candidates[candidates.length - 1];
  return latest?.name ?? detail.currentNodeId;
}

/**
 * 判断某条节点尝试是否为当前节点。
 *
 * 同一节点重试会产生多条记录，只高亮最后一条，避免时间线出现多个“当前节点”。
 *
 * @param detail 运行详情
 * @param node 节点尝试
 * @returns 是否为当前节点
 */
export function isCurrentNodeAttempt(detail: WorkflowRunDetail, node: WorkflowNodeRun): boolean {
  if (!isActiveRunStatus(detail.status) || !detail.currentNodeId) {
    return false;
  }
  if (node.nodeId !== detail.currentNodeId) {
    return false;
  }
  const candidates = (detail.nodes ?? []).filter((item) => item.nodeId === node.nodeId);
  return candidates[candidates.length - 1]?.id === node.id;
}

/**
 * 判断运行是否包含失败节点，用于展示重试入口。
 *
 * @param detail 运行详情
 * @returns 运行失败且至少一个节点失败时为 true
 */
export function hasRetryableFailure(detail: WorkflowRunDetail): boolean {
  return (
    detail.status === 'FAILED' && (detail.nodes ?? []).some((node) => node.status === 'FAILED')
  );
}
