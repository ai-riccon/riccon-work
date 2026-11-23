import { citationLocatorText } from '@/services/knowledge/citation';
import type {
  Approval,
  ApprovalStatus,
  ArtifactCitation,
  ArtifactSourceType,
  Citation,
  McpInvocation,
  McpInvocationStatus,
  ResearchTaskDetail,
  ResearchTaskStatus,
  RunStage,
  RunStageRecord,
  StageStatus,
} from '@/services/api/types';

/**
 * J2 报告任务的展示规则。
 *
 * 这里集中处理状态文案、时间格式、阶段时间线与引用定位等纯逻辑，
 * 目的有两个：
 * 1. 页面只负责布局与交互，状态判断可被单元测试穷举；
 * 2. 服务端契约不变时，展示口径只有一份，避免列表页与详情页各写一套。
 */

/** 产品定义的阶段顺序：检索 → 工具取数 → 审批 → 生成。 */
export const RESEARCH_STAGE_ORDER: RunStage[] = ['RETRIEVAL', 'TOOL', 'APPROVAL', 'GENERATION'];

/** 阶段中文名。 */
export const STAGE_LABELS: Record<RunStage, string> = {
  RETRIEVAL: '检索',
  TOOL: '工具取数',
  APPROVAL: '审批',
  GENERATION: '生成报告',
};

/** 报告任务状态中文名。 */
const RESEARCH_STATUS_LABELS: Record<ResearchTaskStatus, string> = {
  PENDING: '排队中',
  RUNNING: '运行中',
  WAITING_APPROVAL: '等待审批',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  CANCELLED: '已终止',
};

/** 阶段状态中文名；PENDING 是前端补充的“尚未开始”。 */
const STAGE_STATUS_LABELS: Record<StageStatus | 'PENDING', string> = {
  RUNNING: '进行中',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  SKIPPED: '已跳过',
  PENDING: '等待中',
};

/**
 * 判断报告任务是否已进入终态。
 *
 * 终态到达后不再轮询；终态任务的阶段不允许继续显示为“进行中”。
 *
 * @param status 任务状态
 * @returns 是否为 SUCCEEDED/FAILED/CANCELLED
 */
export function isTerminalResearchStatus(status: ResearchTaskStatus): boolean {
  return status === 'SUCCEEDED' || status === 'FAILED' || status === 'CANCELLED';
}

/** 审批状态中文名。 */
const APPROVAL_STATUS_LABELS: Record<ApprovalStatus, string> = {
  PENDING: '待审批',
  APPROVED: '已批准',
  REJECTED: '已拒绝',
  EXPIRED: '已过期',
  CANCELLED: '已取消',
};

/** MCP 调用状态中文名。 */
const MCP_STATUS_LABELS: Record<McpInvocationStatus, string> = {
  STARTED: '调用中',
  SUCCEEDED: '成功',
  FAILED: '失败',
  DENIED: '已拒绝',
};

/** 引用来源中文名。 */
const ARTIFACT_SOURCE_LABELS: Record<ArtifactSourceType, string> = {
  FILE: '文件',
  KNOWLEDGE: '知识库',
  MCP: 'MCP 工具',
};

/**
 * 返回报告任务状态文案。
 *
 * @param status 任务状态
 * @returns 中文文案
 */
export function researchStatusLabel(status: ResearchTaskStatus): string {
  return RESEARCH_STATUS_LABELS[status];
}

/**
 * 返回任务状态对应的 antd Tag 预设色。
 *
 * @param status 任务状态
 * @returns antd Tag 的 color 值
 */
export function researchStatusColor(
  status: ResearchTaskStatus,
): 'default' | 'processing' | 'warning' | 'success' | 'error' {
  switch (status) {
    case 'PENDING':
      return 'default';
    case 'RUNNING':
      return 'processing';
    case 'WAITING_APPROVAL':
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
 * 返回阶段状态文案。
 *
 * @param status 阶段状态或前端补充的 PENDING
 * @returns 中文文案
 */
export function stageStatusLabel(status: StageStatus | 'PENDING'): string {
  return STAGE_STATUS_LABELS[status];
}

/**
 * 把阶段状态映射为 ThoughtChain 的状态。
 *
 * ThoughtChain 只支持 loading/success/error/abort；PENDING 返回 undefined，
 * 表示不显示状态图标，避免“等待中”被误读为正在执行。
 *
 * @param status 阶段状态
 * @returns ThoughtChain 状态或 undefined
 */
export function stageThoughtChainStatus(
  status: StageStatus | 'PENDING',
): 'loading' | 'success' | 'error' | 'abort' | undefined {
  switch (status) {
    case 'RUNNING':
      return 'loading';
    case 'SUCCEEDED':
      return 'success';
    case 'FAILED':
      return 'error';
    case 'SKIPPED':
      return 'abort';
    case 'PENDING':
    default:
      return undefined;
  }
}

/**
 * 返回审批状态文案。
 *
 * @param status 审批状态
 * @returns 中文文案
 */
export function approvalStatusLabel(status: ApprovalStatus): string {
  return APPROVAL_STATUS_LABELS[status];
}

/**
 * 返回审批状态对应的 Tag 预设色。
 *
 * @param status 审批状态
 * @returns antd Tag 的 color 值
 */
export function approvalStatusColor(
  status: ApprovalStatus,
): 'default' | 'processing' | 'warning' | 'success' | 'error' {
  switch (status) {
    case 'PENDING':
      return 'warning';
    case 'APPROVED':
      return 'success';
    case 'REJECTED':
      return 'error';
    case 'EXPIRED':
      return 'error';
    case 'CANCELLED':
    default:
      return 'default';
  }
}

/**
 * 返回 MCP 调用状态文案。
 *
 * @param status 调用状态
 * @returns 中文文案
 */
export function mcpInvocationStatusLabel(status: McpInvocationStatus): string {
  return MCP_STATUS_LABELS[status];
}

/**
 * 返回 MCP 调用状态对应的 Tag 预设色。
 *
 * @param status 调用状态
 * @returns antd Tag 的 color 值
 */
export function mcpInvocationStatusColor(
  status: McpInvocationStatus,
): 'default' | 'processing' | 'warning' | 'success' | 'error' {
  switch (status) {
    case 'STARTED':
      return 'processing';
    case 'SUCCEEDED':
      return 'success';
    case 'DENIED':
      return 'warning';
    case 'FAILED':
    default:
      return 'error';
  }
}

/**
 * 返回引用来源文案。
 *
 * @param sourceType 引用来源
 * @returns 中文文案
 */
export function artifactSourceLabel(sourceType: ArtifactSourceType): string {
  return ARTIFACT_SOURCE_LABELS[sourceType];
}

/**
 * 格式化后端时间。
 *
 * M02 尚未引入租户时区，统一按北京时间展示（合同与运维手册均以 UTC 存储）；
 * 这样测试与跨时区浏览器看到的时间一致。M03 引入租户时区后再替换。
 *
 * @param value ISO 时间或空值
 * @returns `YYYY-MM-DD HH:mm:ss` 或 `—`
 */
export function formatDateTime(value: string | null | undefined): string {
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

/**
 * 格式化耗时。
 *
 * @param durationMs 毫秒；空值或负数返回 `—`
 * @returns 易读耗时
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
 * 缩短 UUID，便于界面阅读；完整值通过 title 或审计记录查看。
 *
 * @param id 原始 ID
 * @param length 保留字符数
 * @returns 短 ID 或 `—`
 */
export function formatShortId(id: string | null | undefined, length = 8): string {
  if (!id) {
    return '—';
  }
  return id.length <= length ? id : `${id.slice(0, length)}…`;
}

/**
 * 把审批入参摘要格式化为可读文本。
 *
 * 服务端已按契约脱敏，这里只做展示截断；不解析、不推断原始参数。
 *
 * @param preview 入参摘要对象
 * @returns 每行一个键值对的文本
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
 * 格式化单个参数值；对象和数组序列化后截断，避免撑破界面。
 *
 * @param value 参数值
 * @returns 展示文本
 */
function formatPreviewValue(value: unknown): string {
  if (value === null || value === undefined) {
    return '—';
  }
  if (typeof value === 'string') {
    return truncate(value, 120);
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }
  try {
    return truncate(JSON.stringify(value), 120);
  } catch {
    return '（无法展示）';
  }
}

/**
 * 截断长文本并追加省略号。
 *
 * @param value 原文
 * @param maxLength 最大字符数
 * @returns 截断后的文本
 */
function truncate(value: string, maxLength: number): string {
  return value.length <= maxLength ? value : `${value.slice(0, maxLength)}…`;
}

/**
 * 解析失败任务的阶段与可重试性。
 *
 * 只允许重试 TOOL 或 GENERATION（与 OpenAPI `ResearchTaskRetryRequest` 一致）；
 * 检索无结果需要用户调整主题并重新创建任务，不能原地重试。
 *
 * @param detail 任务详情
 * @returns 失败阶段、可读原因与是否可重试
 */
export function describeResearchFailure(
  detail: Pick<ResearchTaskDetail, 'status' | 'failureCode' | 'failureMessage' | 'stages'>,
): { stage: RunStage | null; message: string; retryable: boolean } {
  if (detail.status !== 'FAILED') {
    return { stage: null, message: '', retryable: false };
  }
  const failedRecords = detail.stages.filter((record) => record.status === 'FAILED');
  const stage = failedRecords.length > 0 ? failedRecords[failedRecords.length - 1].stage : null;
  const fallback = defaultFailureMessage(stage);
  return {
    stage,
    message: detail.failureMessage || fallback,
    retryable: stage === 'TOOL' || stage === 'GENERATION',
  };
}

/**
 * 按失败阶段给出兜底文案。
 *
 * @param stage 失败阶段
 * @returns 中文说明
 */
function defaultFailureMessage(stage: RunStage | null): string {
  switch (stage) {
    case 'RETRIEVAL':
      return '未检索到相关资料，请调整主题后重新创建任务。';
    case 'TOOL':
      return '外部工具调用失败，请重试工具阶段。';
    case 'APPROVAL':
      return '审批被拒绝，任务已终止。';
    case 'GENERATION':
      return '报告生成失败，请重试生成阶段。';
    default:
      return '任务执行失败，请重试。';
  }
}

/** 阶段时间线的一项；页面用它渲染 ThoughtChain 节点。 */
export interface StageTimelineItem {
  stage: RunStage;
  status: StageStatus | 'PENDING';
  attempts: RunStageRecord[];
  approval: Approval | null;
  invocation: McpInvocation | null;
  startedAt: string | null;
  finishedAt: string | null;
  errorCode: string | null;
}

/**
 * 根据任务详情派生四个阶段的时间线。
 *
 * 规则：
 * - 每个阶段可能有多条尝试记录，全部保留；聚合状态取最后一次尝试。
 * - 审批在数据库里归属 TOOL 阶段，但产品要求时间线展示独立的“审批”节点，
 *   因此 APPROVAL 节点由审批记录派生，并同时挂到 TOOL 节点用于说明“等待审批后执行”。
 * - 没有记录且任务仍在当前阶段时显示“进行中”，其余显示“等待中”。
 *
 * @param detail 任务详情
 * @returns 固定顺序的四阶段时间线
 */
export function buildStageTimeline(detail: ResearchTaskDetail): StageTimelineItem[] {
  const approvals = [...(detail.approvals ?? [])].sort(
    (left, right) => Date.parse(left.createdAt) - Date.parse(right.createdAt),
  );
  const latestApproval = approvals.length > 0 ? approvals[approvals.length - 1] : null;
  const invocations = [...detail.mcpInvocations].sort(
    (left, right) => Date.parse(left.startedAt) - Date.parse(right.startedAt),
  );
  const latestInvocation = invocations.length > 0 ? invocations[invocations.length - 1] : null;
  const failed = describeResearchFailure(detail);

  return RESEARCH_STAGE_ORDER.map((stage) => {
    const records = detail.stages
      .filter((record) => record.stage === stage)
      .sort((left, right) => left.attempt - right.attempt);
    const latestRecord = records.length > 0 ? records[records.length - 1] : null;
    const approval = stage === 'TOOL' || stage === 'APPROVAL' ? latestApproval : null;
    const invocation = stage === 'TOOL' ? latestInvocation : null;
    const resolved = resolveStageStatus(detail, stage, latestRecord, latestApproval, failed.stage);
    const status = settleTerminalStageStatus(detail, stage, resolved, latestApproval);

    return {
      stage,
      status,
      attempts: records,
      approval,
      invocation,
      startedAt:
        latestRecord?.startedAt ??
        (stage === 'APPROVAL' ? latestApproval?.createdAt : null) ??
        null,
      finishedAt:
        latestRecord?.finishedAt ??
        (stage === 'APPROVAL' ? latestApproval?.decidedAt : null) ??
        null,
      errorCode:
        latestRecord?.errorCode ?? (failed.stage === stage ? detail.failureCode : null) ?? null,
    };
  });
}

/**
 * 计算单个阶段的聚合状态。
 *
 * @param detail 任务详情
 * @param stage 阶段
 * @param latestRecord 最后一次阶段记录
 * @param latestApproval 最新审批
 * @param failedStage 已确认的失败阶段
 * @returns 聚合状态
 */
function resolveStageStatus(
  detail: ResearchTaskDetail,
  stage: RunStage,
  latestRecord: RunStageRecord | null,
  latestApproval: Approval | null,
  failedStage: RunStage | null,
): StageStatus | 'PENDING' {
  if (latestRecord) {
    return latestRecord.status;
  }
  if (stage === 'APPROVAL' && latestApproval) {
    return approvalToStageStatus(latestApproval.status);
  }
  if (stage === 'TOOL' && latestApproval?.status === 'REJECTED') {
    return 'SKIPPED';
  }
  if (stage === 'TOOL' && latestApproval?.status === 'PENDING') {
    // 审批未完成时工具尚未执行；真实执行状态在审批通过后由 TOOL 记录体现。
    return 'PENDING';
  }
  if (failedStage === stage && detail.status === 'FAILED') {
    return 'FAILED';
  }
  if (detail.currentStage === stage && detail.status === 'RUNNING') {
    return 'RUNNING';
  }
  if (detail.status === 'SUCCEEDED') {
    return 'SUCCEEDED';
  }
  return 'PENDING';
}

/**
 * 修正终态任务中尚未收尾的阶段状态。
 *
 * 任务终态可能先于阶段收尾返回（例如审批拒绝后取消事件仍由 Worker 异步处理）；
 * 此时后端记录可能短暂保留 RUNNING。任务已是终态时阶段不可能继续运行，
 * 因此把陈旧的 RUNNING 按最终语义展示，避免用户看到“进行中”却不再轮询。
 *
 * @param detail 任务详情
 * @param stage 阶段
 * @param status 聚合状态
 * @param latestApproval 最新审批
 * @returns 修正后的状态
 */
function settleTerminalStageStatus(
  detail: Pick<ResearchTaskDetail, 'status'>,
  stage: RunStage,
  status: StageStatus | 'PENDING',
  latestApproval: Approval | null,
): StageStatus | 'PENDING' {
  if (status !== 'RUNNING' || !isTerminalResearchStatus(detail.status)) {
    return status;
  }
  if (detail.status === 'SUCCEEDED') {
    return 'SUCCEEDED';
  }
  if (stage === 'APPROVAL' && latestApproval?.status === 'REJECTED') {
    return 'FAILED';
  }
  return 'SKIPPED';
}

/**
 * 把审批状态映射为阶段状态。
 *
 * @param status 审批状态
 * @returns 阶段状态
 */
function approvalToStageStatus(status: ApprovalStatus): StageStatus {
  switch (status) {
    case 'PENDING':
      return 'RUNNING';
    case 'APPROVED':
      return 'SUCCEEDED';
    case 'REJECTED':
      return 'FAILED';
    case 'EXPIRED':
      return 'FAILED';
    case 'CANCELLED':
    default:
      return 'SKIPPED';
  }
}

/** 产物正文中的一段。 */
export type ArtifactBlock =
  { type: 'paragraph'; text: string } | { type: 'bullets'; items: string[] };

/** 产物正文中的一节。 */
export interface ArtifactSection {
  heading: string | null;
  blocks: ArtifactBlock[];
}

/** 解析后的产物正文。 */
export interface ArtifactDocument {
  title: string | null;
  sections: ArtifactSection[];
}

/**
 * 解析报告 Markdown 的标题、分节与段落。
 *
 * 只支持报告实际使用的三种结构：一级标题、二级/三级标题、无序列表与段落；
 * 不支持内联 Markdown 语法，避免引入额外依赖或使用不安全的 HTML 渲染。
 *
 * @param markdown 报告正文
 * @returns 文档标题与分节
 */
export function parseArtifactMarkdown(markdown: string): ArtifactDocument {
  const lines = markdown.replace(/\r\n?/g, '\n').split('\n');
  let title: string | null = null;
  let currentHeading: string | null = null;
  let currentBlocks: ArtifactBlock[] = [];
  let paragraph: string[] = [];
  let bullets: string[] = [];
  const sections: ArtifactSection[] = [];

  const flushParagraph = (): void => {
    if (paragraph.length > 0) {
      currentBlocks.push({ type: 'paragraph', text: paragraph.join(' ') });
      paragraph = [];
    }
  };
  const flushBullets = (): void => {
    if (bullets.length > 0) {
      currentBlocks.push({ type: 'bullets', items: bullets });
      bullets = [];
    }
  };
  const flushSection = (): void => {
    flushParagraph();
    flushBullets();
    if (currentHeading !== null || currentBlocks.length > 0) {
      sections.push({ heading: currentHeading, blocks: currentBlocks });
      currentBlocks = [];
    }
  };

  for (const rawLine of lines) {
    const line = rawLine.trim();
    if (line.startsWith('## ')) {
      flushSection();
      currentHeading = line.slice(3).trim();
      continue;
    }
    if (line.startsWith('### ')) {
      // 三级标题作为段落强调，保持“分节正文”不碎成过深层级。
      flushParagraph();
      flushBullets();
      currentBlocks.push({ type: 'paragraph', text: line.slice(4).trim() });
      continue;
    }
    if (line.startsWith('# ') && title === null && sections.length === 0) {
      title = line.slice(2).trim();
      continue;
    }
    if (line === '') {
      flushParagraph();
      flushBullets();
      continue;
    }
    if (line.startsWith('- ') || line.startsWith('* ')) {
      flushParagraph();
      bullets.push(line.slice(2).trim());
      continue;
    }
    flushBullets();
    paragraph.push(line);
  }
  flushSection();

  return { title, sections };
}

/**
 * 返回引用在时间线中的定位目标。
 *
 * FILE/KNOWLEDGE 来源对应检索阶段；MCP 来源必须能找到具体调用记录，
 * 找不到时回退到工具阶段并标记为未精确匹配，避免界面给出虚假定位。
 *
 * @param citation 产物引用
 * @param detail 任务详情
 * @returns 目标锚点、文案与是否精确匹配
 */
export function artifactCitationTarget(
  citation: ArtifactCitation,
  detail: Pick<ResearchTaskDetail, 'mcpInvocations'>,
): { anchorId: string; label: string; exact: boolean } {
  if (citation.sourceType !== 'MCP') {
    return { anchorId: 'timeline-RETRIEVAL', label: '检索阶段', exact: true };
  }
  const invocation = detail.mcpInvocations.find((item) => item.id === citation.mcpInvocationId);
  if (invocation) {
    return {
      anchorId: `timeline-invocation-${invocation.id}`,
      label: `工具调用：${invocation.toolName}`,
      exact: true,
    };
  }
  return { anchorId: 'timeline-TOOL', label: '工具阶段（未找到具体调用）', exact: false };
}

/**
 * 格式化引用定位。
 *
 * @param citation 引用或产物引用
 * @returns 页码/工作表/段落文案
 */
export function citationLocatorLabel(citation: {
  locatorType?: 'PAGE' | 'SHEET' | 'PARAGRAPH' | null;
  pageNo?: number | null;
  sheetName?: string | null;
  cellRange?: string | null;
  paragraphNo?: number | null;
  sourceStatus?: 'AVAILABLE' | 'NO_ACCESS' | 'DELETED' | 'UPDATED' | null;
}): string {
  return citationLocatorText({
    ...citation,
    sourceStatus: citation.sourceStatus ?? 'AVAILABLE',
  });
}

/**
 * 判断正文类型是否支持在线预览。
 *
 * @param contentType 产物 MIME 类型
 * @returns 是否为 Markdown/纯文本
 */
export function isMarkdownContent(contentType: string): boolean {
  const normalized = contentType.toLowerCase();
  return normalized.includes('markdown') || normalized.startsWith('text/plain');
}

/**
 * 生成报告下载文件名。
 *
 * 去掉路径分隔符与 Windows 非法字符，限制长度，避免下载文件名污染。
 *
 * @param title 报告标题
 * @returns 以 `.md` 结尾的文件名
 */
export function reportDownloadFilename(title: string): string {
  const sanitized = title
    .replace(/[\\/:*?"<>|]/g, '-')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 80)
    .trim();
  return `${sanitized || '市场调研报告'}.md`;
}

/**
 * 把检索来源格式化为一句定位文本。
 *
 * @param source 检索命中
 * @returns 来源类型与定位
 */
export function sourceLocatorLabel(source: Citation): string {
  return `${source.sourceType === 'FILE' ? '文件' : '知识库'} · ${citationLocatorLabel(source)}`;
}
