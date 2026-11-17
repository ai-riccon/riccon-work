import { Alert, Typography } from 'antd';
import type { Approval, McpInvocation, RunStageRecord } from '@/services/api/types';
import {
  formatDateTime,
  formatDuration,
  formatParamsPreview,
  formatShortId,
  mcpInvocationStatusColor,
  mcpInvocationStatusLabel,
} from '@/services/research/format';
import { StatusTag } from './status-tag';

/** 单次 MCP 调用摘要属性。 */
export interface McpInvocationViewProps {
  invocation: McpInvocation;
  /** 对应的阶段记录；用于读取白名单 details 中的入参/结果摘要。 */
  record?: RunStageRecord | null;
  /** 同一阶段的审批记录；入参摘要缺失时作为兜底。 */
  approval?: Approval | null;
  /** 第几次尝试，从 1 开始。 */
  attempt: number;
  /** 当前高亮的锚点。 */
  highlightedAnchor: string | null;
}

/**
 * 展示 1 次 MCP 调用记录：工具名、入参摘要、结果摘要、耗时与状态。
 *
 * 契约只保证 SHA-256 校验值；入参/结果摘要来自阶段记录的白名单 details。
 * 缺失时不猜测内容，明确提示“仅保留校验摘要”，让审批与审计仍可核对。
 *
 * @param props 调用记录与上下文
 * @returns 调用摘要卡片
 */
export function McpInvocationView({
  invocation,
  record,
  approval,
  attempt,
  highlightedAnchor,
}: McpInvocationViewProps) {
  const anchorId = `timeline-invocation-${invocation.id}`;
  const details = record?.details;
  const preview = details?.paramsPreview ?? approval?.paramsPreview ?? null;
  const resultSummary = typeof details?.resultSummary === 'string' ? details.resultSummary : null;
  const failed = invocation.status === 'FAILED' || invocation.status === 'DENIED';

  return (
    <div
      className="research-invocation"
      data-highlighted={highlightedAnchor === anchorId ? 'true' : undefined}
      id={anchorId}
    >
      <div className="research-invocation-header">
        <Typography.Text strong>
          MCP 调用 · {invocation.toolName}（第 {attempt} 次尝试）
        </Typography.Text>
        <StatusTag tone={mcpInvocationStatusColor(invocation.status)}>
          {mcpInvocationStatusLabel(invocation.status)}
        </StatusTag>
      </div>

      <dl className="research-definition-list">
        <div>
          <dt>工具名</dt>
          <dd>
            <Typography.Text code>{invocation.toolName}</Typography.Text>
          </dd>
        </div>
        <div>
          <dt>入参摘要</dt>
          <dd>
            <pre className="research-params">
              {preview ? formatParamsPreview(preview) : '仅保留入参校验摘要'}
            </pre>
          </dd>
        </div>
        <div>
          <dt>结果摘要</dt>
          <dd>
            {resultSummary ??
              (invocation.status === 'SUCCEEDED'
                ? '结果已脱敏，仅保留校验摘要。'
                : '尚未产生结果。')}
          </dd>
        </div>
        <div>
          <dt>耗时</dt>
          <dd>{formatDuration(invocation.durationMs)}</dd>
        </div>
        <div>
          <dt>开始时间</dt>
          <dd>{formatDateTime(invocation.startedAt)}</dd>
        </div>
        <div>
          <dt>结束时间</dt>
          <dd>{formatDateTime(invocation.finishedAt)}</dd>
        </div>
        <div>
          <dt>入参校验值</dt>
          <dd>
            <Typography.Text code title={invocation.paramsSha256}>
              {formatShortId(invocation.paramsSha256, 16)}
            </Typography.Text>
          </dd>
        </div>
        <div>
          <dt>结果校验值</dt>
          <dd>
            {invocation.resultSha256 ? (
              <Typography.Text code title={invocation.resultSha256}>
                {formatShortId(invocation.resultSha256, 16)}
              </Typography.Text>
            ) : (
              '—'
            )}
          </dd>
        </div>
      </dl>

      {failed && (
        <Alert
          showIcon
          description={`错误码：${invocation.errorCode ?? '未知错误'}。可重试该阶段；重试会生成新的审批请求，不复用旧审批。`}
          title="工具调用失败"
          type="error"
        />
      )}
    </div>
  );
}
