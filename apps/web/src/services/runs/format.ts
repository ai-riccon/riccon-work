import type {
  InvocationStatus,
  ModelPurpose,
  RunKind,
  RunStatus,
  RunStage,
} from '@/services/api/types';

/**
 * J1 运行详情的展示规则。
 *
 * 状态映射集中在这里，保证运行详情抽屉与后续其他运行视图使用同一套文案；
 * 页面只负责布局，不重复判断契约枚举。
 */

/** 运行状态中文名。 */
const RUN_STATUS_LABELS: Record<RunStatus, string> = {
  PENDING: '排队中',
  RUNNING: '运行中',
  WAITING_APPROVAL: '等待审批',
  RETRY_WAIT: '等待重试',
  SUCCEEDED: '成功',
  FAILED: '失败',
  CANCELLED: '已取消',
};

/** 运行类别中文名。 */
const RUN_KIND_LABELS: Record<RunKind, string> = {
  CHAT: '对话回答',
  FILE_PARSE: '文件解析',
  KNOWLEDGE_INDEX: '知识库索引',
  RESEARCH_REPORT: '调研报告',
};

/** 模型调用状态中文名。 */
const INVOCATION_STATUS_LABELS: Record<InvocationStatus, string> = {
  STARTED: '调用中',
  SUCCEEDED: '成功',
  FAILED: '失败',
  CANCELLED: '已取消',
};

/** 模型用途中文名。 */
const MODEL_PURPOSE_LABELS: Record<ModelPurpose, string> = {
  CHAT: '对话',
  REPORT: '报告生成',
};

/** 阶段中文名；与 J2 时间线保持一致。 */
const STAGE_LABELS: Record<RunStage, string> = {
  RETRIEVAL: '检索',
  TOOL: '工具取数',
  APPROVAL: '审批',
  GENERATION: '生成',
};

/**
 * 返回运行状态文案。
 *
 * @param status 运行状态
 * @returns 中文文案
 */
export function runStatusLabel(status: RunStatus): string {
  return RUN_STATUS_LABELS[status];
}

/**
 * 返回运行状态对应的 antd Tag 颜色。
 *
 * @param status 运行状态
 * @returns Tag color
 */
export function runStatusColor(
  status: RunStatus,
): 'default' | 'processing' | 'warning' | 'success' | 'error' {
  switch (status) {
    case 'PENDING':
      return 'default';
    case 'RUNNING':
      return 'processing';
    case 'WAITING_APPROVAL':
    case 'RETRY_WAIT':
      return 'warning';
    case 'SUCCEEDED':
      return 'success';
    case 'CANCELLED':
      return 'default';
    case 'FAILED':
    default:
      return 'error';
  }
}

/**
 * 返回运行类别文案。
 *
 * @param kind 运行类别
 * @returns 中文文案
 */
export function runKindLabel(kind: RunKind): string {
  return RUN_KIND_LABELS[kind];
}

/**
 * 返回模型调用状态文案。
 *
 * @param status 调用状态
 * @returns 中文文案
 */
export function invocationStatusLabel(status: InvocationStatus): string {
  return INVOCATION_STATUS_LABELS[status];
}

/**
 * 返回模型调用状态对应的 antd Tag 颜色。
 *
 * @param status 调用状态
 * @returns Tag color
 */
export function invocationStatusColor(
  status: InvocationStatus,
): 'default' | 'processing' | 'success' | 'error' {
  switch (status) {
    case 'STARTED':
      return 'processing';
    case 'SUCCEEDED':
      return 'success';
    case 'CANCELLED':
      return 'default';
    case 'FAILED':
    default:
      return 'error';
  }
}

/**
 * 返回模型用途文案。
 *
 * @param purpose 调用用途
 * @returns 中文文案
 */
export function modelPurposeLabel(purpose: ModelPurpose): string {
  return MODEL_PURPOSE_LABELS[purpose];
}

/**
 * 返回阶段文案。
 *
 * @param stage 阶段或空值
 * @returns 中文文案或 `—`
 */
export function runStageLabel(stage: RunStage | null | undefined): string {
  return stage ? STAGE_LABELS[stage] : '—';
}

/**
 * 格式化输入/输出 token。
 *
 * 任一值缺失时用 `—` 占位，避免把 0 与“未知”混为一谈。
 *
 * @param promptTokens 输入 token
 * @param completionTokens 输出 token
 * @returns 例如 `128 / 96`
 */
export function formatTokenUsage(
  promptTokens: number | null | undefined,
  completionTokens: number | null | undefined,
): string {
  const prompt = typeof promptTokens === 'number' ? String(promptTokens) : '—';
  const completion = typeof completionTokens === 'number' ? String(completionTokens) : '—';
  return `${prompt} / ${completion}`;
}
