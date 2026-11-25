import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  MinusCircleOutlined,
  ReloadOutlined,
  StopOutlined,
  SyncOutlined,
} from '@ant-design/icons';
import { Alert, Button, Descriptions, Empty, Space, Tag, Typography } from 'antd';
import { useEffect, useRef, useState } from 'react';
import type { Citation } from '@/services/api/types';
import { formatOffsetDateTime } from '@/services/api/time';
import { CitationDetailDrawer } from '@/pages/knowledge/components/citation-detail-drawer';
import {
  citationAvailabilityDescription,
  citationAvailabilityLabel,
  citationListLabel,
  citationLocatorText,
  citationStatusTagStyle,
  isCitationMasked,
  toCitationView,
} from '@/services/knowledge';
import type { CitationView, KnowledgeApi } from '@/services/knowledge';
import type { WorkflowNodeStatus } from './run-api';
import type { WorkflowRunDetail } from './run-api';
import {
  CITATION_MASK_NOTE,
  NODE_STATUS_COLORS,
  RESULT_PREVIEW_LIMIT,
  RESULT_FORMAT_LABELS,
  RESULT_STATUS_LABELS,
  RETRY_T12B_NOTICE,
  RUN_STATUS_COLORS,
  SECURITY_NOTICE,
  approvalCountsText,
  approvalStatusColor,
  approvalStatusLabel,
  describeApprovalAction,
  describeApprovalAssignee,
  describeApprovalDataScope,
  describeFailure,
  findCurrentNodeName,
  formatApprovalParams,
  formatBytes,
  formatDuration,
  formatResultText,
  formatRunShortId,
  hasRetryableFailure,
  isCurrentNodeAttempt,
  isTerminalRunStatus,
  nodeStatusLabel,
  nodeTypeLabel,
  previewResultText,
  runStatusLabel,
} from './format';
import './run.css';

/** 运行详情展示组件可注入依赖。 */
export interface WorkflowRunDetailViewProps {
  /** 运行详情；终态后包含结果。 */
  detail: WorkflowRunDetail;
  /** 知识库下载能力；引用详情抽屉复用。测试可注入。 */
  knowledgeApi?: Pick<KnowledgeApi, 'downloadDocument'>;
  /** T12b 接入后的重试动作；未提供时按钮只展示说明，不提交请求。 */
  onRetry?: () => void;
  /** 重试动作提交中；未提供动作时不生效。 */
  retryPending?: boolean;
}

/**
 * 按节点状态返回图标。
 *
 * 图标全部 `aria-hidden`，状态由紧邻文字表达，避免只靠颜色/图形传递信息（a11y 基线）。
 *
 * @param status 节点状态
 * @returns 状态图标
 */
function NodeStatusIcon({ status }: { status: WorkflowNodeStatus }) {
  switch (status) {
    case 'SUCCEEDED':
      return <CheckCircleOutlined aria-hidden="true" />;
    case 'FAILED':
      return <CloseCircleOutlined aria-hidden="true" />;
    case 'RUNNING':
      return <SyncOutlined aria-hidden="true" spin />;
    case 'SKIPPED':
      return <MinusCircleOutlined aria-hidden="true" />;
    case 'CANCELLED':
      return <StopOutlined aria-hidden="true" />;
    case 'WAITING_APPROVAL':
    case 'PENDING':
    default:
      return <ClockCircleOutlined aria-hidden="true" />;
  }
}

/**
 * 运行详情：节点时间线、引用、审批记录与最终结果。
 *
 * 安全边界：
 * 1. 只渲染服务端返回的 `inputSummary`/`outputSummary`、聚合引用与最终结果，不请求或推断
 *    完整提示词、完整工具参数、凭据或节点原始输出；
 * 2. 引用在 `NO_ACCESS`/`DELETED` 时由前端再次强制遮蔽，即使异常响应带标题/片段/定位也不展示；
 * 3. 重试入口在 T12a 只展示；未注入回调时点击只提示“将在运行历史页接入”，不发请求。
 *
 * @param props 运行详情、下载能力与可选重试动作
 * @returns 运行详情界面
 */
export function WorkflowRunDetailView({
  detail,
  knowledgeApi,
  onRetry,
  retryPending = false,
}: WorkflowRunDetailViewProps) {
  const headingRef = useRef<HTMLHeadingElement>(null);
  const [activeCitation, setActiveCitation] = useState<CitationView | null>(null);
  const [resultExpanded, setResultExpanded] = useState(false);
  const [retryNotice, setRetryNotice] = useState<string | null>(null);

  const nodes = detail.nodes ?? [];
  const approvals = detail.approvals ?? [];
  const citations = detail.citations ?? [];
  const currentNodeName = findCurrentNodeName(detail);
  const failureMessage = describeFailure(detail.errorCode, detail.errorMessage);
  const failed = detail.status === 'FAILED';
  const cancelled = detail.status === 'CANCELLED';
  const terminal = isTerminalRunStatus(detail.status);

  // 详情就绪后聚焦标题；轮询更新不重新挂载，焦点保留在用户当前位置。
  useEffect(() => {
    headingRef.current?.focus();
  }, []);

  /**
   * 处理重试入口点击。
   *
   * T12b 注入 `onRetry` 后才提交真实请求；否则只显示说明，确保当前页面不产生副作用。
   */
  const handleRetry = (): void => {
    if (onRetry) {
      onRetry();
      return;
    }
    setRetryNotice(RETRY_T12B_NOTICE);
  };

  const resultText = formatResultText(detail.result);
  const preview = previewResultText(resultText, resultExpanded);

  return (
    <div className="workflow-run-detail" data-testid="workflow-run-detail">
      <header className="workflow-run-heading">
        <h1 className="workflow-run-title" ref={headingRef} tabIndex={-1}>
          运行详情
        </h1>
        <Typography.Paragraph className="workflow-run-workflow-name">
          {detail.workflowName || '未命名流程'}
        </Typography.Paragraph>
        <Space wrap size={[8, 8]} className="workflow-run-heading-tags">
          <Tag color={RUN_STATUS_COLORS[detail.status]}>{runStatusLabel(detail.status)}</Tag>
          <Tag>版本 v{detail.versionNo}</Tag>
          {currentNodeName ? <Tag color="processing">当前节点：{currentNodeName}</Tag> : null}
          {detail.approvalStatus ? (
            <Tag color={approvalStatusColor(detail.approvalStatus)}>
              审批：{approvalStatusLabel(detail.approvalStatus)}
            </Tag>
          ) : null}
        </Space>
      </header>

      <Descriptions
        className="workflow-run-summary"
        bordered
        size="small"
        column={{ xs: 1, sm: 1, md: 2 }}
        items={[
          {
            key: 'status',
            label: '运行状态',
            children: (
              <Tag color={RUN_STATUS_COLORS[detail.status]}>{runStatusLabel(detail.status)}</Tag>
            ),
          },
          { key: 'version', label: '绑定版本', children: `v${detail.versionNo}` },
          {
            key: 'requester',
            label: '发起人',
            children: (
              <span title={detail.requesterUserId}>{formatRunShortId(detail.requesterUserId)}</span>
            ),
          },
          {
            key: 'approval',
            label: '审批状态',
            children: `${detail.approvalStatus ? `${approvalStatusLabel(detail.approvalStatus)} · ` : ''}${approvalCountsText(detail)}`,
          },
          {
            key: 'createdAt',
            label: '创建时间',
            children: formatOffsetDateTime(detail.createdAt),
          },
          {
            key: 'startedAt',
            label: '开始时间',
            children: formatOffsetDateTime(detail.startedAt),
          },
          {
            key: 'finishedAt',
            label: '结束时间',
            children: formatOffsetDateTime(detail.finishedAt),
          },
          { key: 'duration', label: '执行耗时', children: formatDuration(detail.durationMs) },
          {
            key: 'updatedAt',
            label: '最近更新',
            children: formatOffsetDateTime(detail.updatedAt),
          },
          {
            key: 'retention',
            label: '保留到期',
            children: formatOffsetDateTime(detail.retentionExpiresAt),
          },
          {
            key: 'runId',
            label: '运行 ID',
            children: (
              <Typography.Text code title={detail.id}>
                {formatRunShortId(detail.id, 12)}
              </Typography.Text>
            ),
          },
        ]}
      />

      {failed ? (
        <Alert
          className="workflow-run-failure"
          type="error"
          showIcon
          title="运行失败"
          description={failureMessage ?? '运行以失败结束，请查看节点时间线中的失败节点与错误码。'}
          action={
            hasRetryableFailure(detail) ? (
              <Button
                danger
                icon={<ReloadOutlined aria-hidden="true" />}
                loading={retryPending}
                onClick={handleRetry}
              >
                重试失败节点
              </Button>
            ) : undefined
          }
        />
      ) : null}

      {cancelled ? (
        <Alert
          className="workflow-run-failure"
          type="warning"
          showIcon
          title="运行已取消"
          description="取消后后续节点不再执行，已产生的节点记录与结果保留。"
        />
      ) : null}

      {retryNotice ? (
        <Alert
          className="workflow-run-retry-notice"
          type="info"
          showIcon
          title="重试入口暂未接入"
          description={retryNotice}
          action={
            <Button size="small" onClick={() => setRetryNotice(null)}>
              知道了
            </Button>
          }
        />
      ) : null}

      <section aria-labelledby="workflow-run-timeline-title" className="workflow-run-section">
        <h2 id="workflow-run-timeline-title" className="workflow-run-section-title">
          节点时间线
        </h2>
        {nodes.length === 0 ? (
          <Empty
            description={
              terminal ? '本次运行没有节点记录。' : '运行尚未产生节点记录；开始后会自动刷新。'
            }
          />
        ) : (
          <ol className="workflow-run-timeline" aria-label="节点时间线">
            {nodes.map((node, index) => {
              const current = isCurrentNodeAttempt(detail, node);
              const nodeFailure = describeFailure(node.errorCode, node.errorMessage);
              return (
                <li
                  key={node.id}
                  className={
                    current ? 'workflow-run-node workflow-run-node-current' : 'workflow-run-node'
                  }
                  data-current={current ? 'true' : undefined}
                  data-testid={`workflow-run-node-${node.nodeId}-${node.attempt}`}
                  aria-current={current ? 'step' : undefined}
                >
                  <div className="workflow-run-node-header">
                    <Typography.Text strong>
                      {index + 1}. {node.name}
                    </Typography.Text>
                    <Tag>{nodeTypeLabel(node)}</Tag>
                    <Tag
                      color={NODE_STATUS_COLORS[node.status]}
                      icon={<NodeStatusIcon status={node.status} />}
                    >
                      {nodeStatusLabel(node.status)}
                    </Tag>
                    <Tag>第 {node.attempt} 次尝试</Tag>
                    {current ? <Tag color="processing">当前节点</Tag> : null}
                  </div>

                  <div className="workflow-run-node-meta">
                    <span>开始：{formatOffsetDateTime(node.startedAt)}</span>
                    <span>结束：{formatOffsetDateTime(node.finishedAt)}</span>
                    <span>耗时：{formatDuration(node.durationMs)}</span>
                  </div>

                  {node.inputSummary ? (
                    <details className="workflow-run-summary-block">
                      <summary>输入摘要</summary>
                      <pre tabIndex={0} role="region" aria-label={`节点${node.name}输入摘要`}>
                        {node.inputSummary}
                      </pre>
                    </details>
                  ) : null}

                  {node.outputSummary ? (
                    <details className="workflow-run-summary-block">
                      <summary>输出摘要</summary>
                      <pre tabIndex={0} role="region" aria-label={`节点${node.name}输出摘要`}>
                        {node.outputSummary}
                      </pre>
                    </details>
                  ) : null}

                  {nodeFailure ? (
                    <Alert type="error" showIcon title="节点失败" description={nodeFailure} />
                  ) : null}

                  {node.approvalId || node.invocationId ? (
                    <Typography.Text type="secondary" className="workflow-run-node-refs">
                      关联：
                      {node.approvalId ? (
                        <span title={node.approvalId}>
                          审批 {formatRunShortId(node.approvalId)}
                        </span>
                      ) : null}
                      {node.invocationId ? (
                        <span title={node.invocationId}>
                          工具调用 {formatRunShortId(node.invocationId)}
                        </span>
                      ) : null}
                    </Typography.Text>
                  ) : null}
                </li>
              );
            })}
          </ol>
        )}
      </section>

      <section aria-labelledby="workflow-run-citations-title" className="workflow-run-section">
        <h2 id="workflow-run-citations-title" className="workflow-run-section-title">
          引用
        </h2>
        {citations.length === 0 ? (
          <Alert
            type="info"
            showIcon
            title="本次运行没有知识库引用"
            description="运行未产生引用，或相关引用已在服务端按权限过滤。"
          />
        ) : (
          <>
            <ul className="workflow-run-citations" aria-label="运行引用">
              {citations.map((citation: Citation, index) => {
                const view = toCitationView(citation);
                const masked = isCitationMasked(view);
                const updated = view.sourceStatus === 'UPDATED';
                return (
                  <li key={`${citation.ordinal}-${index}`} className="workflow-run-citation">
                    <Button
                      type="text"
                      className="workflow-run-citation-button"
                      onClick={() => setActiveCitation(view)}
                    >
                      <span className="workflow-run-citation-header">
                        <Tag style={citationStatusTagStyle(view.sourceStatus)}>
                          {citationAvailabilityLabel(view)}
                        </Tag>
                        <Typography.Text strong>
                          {masked ? `[${view.ordinal}] 内容不可见` : citationListLabel(view)}
                        </Typography.Text>
                        {!masked ? (
                          <Typography.Text type="secondary">
                            {citationLocatorText(view)}
                          </Typography.Text>
                        ) : null}
                      </span>
                    </Button>
                    {!masked && view.snippet ? (
                      <Typography.Paragraph className="workflow-run-citation-snippet">
                        {view.snippet}
                      </Typography.Paragraph>
                    ) : null}
                    {masked || updated ? (
                      <Typography.Text type="secondary">
                        {citationAvailabilityDescription(view)}
                      </Typography.Text>
                    ) : null}
                  </li>
                );
              })}
            </ul>
            <Typography.Text type="secondary">{CITATION_MASK_NOTE}</Typography.Text>
            <CitationDetailDrawer
              citation={activeCitation}
              knowledgeApi={knowledgeApi}
              onClose={() => setActiveCitation(null)}
            />
          </>
        )}
      </section>

      <section aria-labelledby="workflow-run-approvals-title" className="workflow-run-section">
        <h2 id="workflow-run-approvals-title" className="workflow-run-section-title">
          审批记录
        </h2>
        {approvals.length === 0 ? (
          <Alert
            type="info"
            showIcon
            title="本次运行没有审批节点"
            description="需要人工审批的流程会在节点时间线与这里显示审批状态、决定人与理由。"
          />
        ) : (
          <ol className="workflow-run-approvals" aria-label="审批记录">
            {approvals.map((approval) => (
              <li key={approval.id} className="workflow-run-approval">
                <div className="workflow-run-approval-header">
                  <Typography.Text strong>{approval.nodeName}</Typography.Text>
                  <Tag color={approvalStatusColor(approval.status)}>
                    {approvalStatusLabel(approval.status)}
                  </Tag>
                  <Typography.Text type="secondary">
                    {describeApprovalAction(approval)}
                  </Typography.Text>
                </div>
                <Descriptions
                  bordered
                  column={1}
                  size="small"
                  items={[
                    {
                      key: 'assignee',
                      label: '审批人',
                      children: describeApprovalAssignee(approval),
                    },
                    {
                      key: 'scope',
                      label: '数据范围',
                      children: describeApprovalDataScope(approval.dataScopeSummary),
                    },
                    {
                      key: 'requestedBy',
                      label: '发起人',
                      children: (
                        <span title={approval.requestedBy}>
                          {formatRunShortId(approval.requestedBy)}
                        </span>
                      ),
                    },
                    {
                      key: 'createdAt',
                      label: '创建时间',
                      children: formatOffsetDateTime(approval.createdAt),
                    },
                    {
                      key: 'decidedBy',
                      label: '决定人',
                      children: (
                        <span title={approval.decidedBy ?? undefined}>
                          {formatRunShortId(approval.decidedBy)}
                        </span>
                      ),
                    },
                    {
                      key: 'decidedAt',
                      label: '决定时间',
                      children: formatOffsetDateTime(approval.decidedAt),
                    },
                    {
                      key: 'comment',
                      label: '决定理由',
                      children: approval.decisionComment || '—',
                    },
                  ]}
                />
                {approval.paramsPreview ? (
                  <details className="workflow-run-summary-block">
                    <summary>脱敏参数摘要（仅审批人可见）</summary>
                    {/* 审批参数可能超过 240px 并滚动；可聚焦区域让键盘用户能滚动查看（axe scrollable-region-focusable）。 */}
                    <pre
                      tabIndex={0}
                      role="region"
                      aria-label={`审批参数摘要：${approval.nodeName}`}
                    >
                      {formatApprovalParams(approval.paramsPreview)}
                    </pre>
                  </details>
                ) : null}
              </li>
            ))}
          </ol>
        )}
        <Typography.Text type="secondary">
          审批摘要只包含服务端脱敏后的字段、数据范围与决定记录，不包含完整工具参数、提示词或凭据。
        </Typography.Text>
      </section>

      <section aria-labelledby="workflow-run-result-title" className="workflow-run-section">
        <h2 id="workflow-run-result-title" className="workflow-run-section-title">
          最终结果
        </h2>
        <Descriptions
          className="workflow-run-result-summary"
          bordered
          size="small"
          column={{ xs: 1, sm: 1, md: 2 }}
          items={[
            {
              key: 'resultStatus',
              label: '保存状态',
              children: RESULT_STATUS_LABELS[detail.resultStatus],
            },
            {
              key: 'format',
              label: '格式',
              children: detail.result ? RESULT_FORMAT_LABELS[detail.result.format] : '—',
            },
            {
              key: 'size',
              label: '大小',
              children: detail.result ? formatBytes(detail.result.sizeBytes) : '—',
            },
            {
              key: 'generatedAt',
              label: '生成时间',
              children: formatOffsetDateTime(detail.result?.generatedAt),
            },
          ]}
        />

        {detail.resultStatus === 'OVER_LIMIT' ? (
          <Alert
            type="error"
            showIcon
            title="结果超过大小上限，未保存完整内容"
            description={`本次运行结果超过部署配置的单次结果上限（默认 5 MiB），运行以 WF_RESULT_LIMIT 结束；系统不会静默截断。请精简结果后重试，或联系管理员调整限制。`}
          />
        ) : null}

        {detail.resultStatus === 'SAVED' && resultText ? (
          <>
            {/* 结果区是 max-height + overflow:auto 的滚动容器；tabIndex/role 让键盘用户可聚焦并滚动查看。 */}
            <pre
              className="workflow-run-result-text"
              data-testid="workflow-run-result-text"
              tabIndex={0}
              role="region"
              aria-label="运行结果全文"
            >
              {preview.text}
            </pre>
            {preview.truncated ? (
              <Alert
                type="info"
                showIcon
                title={`结果较长，当前仅显示前 ${RESULT_PREVIEW_LIMIT} 个字符`}
                description={`完整结果共 ${preview.totalChars} 个字符；展开后在本区域内滚动查看，不影响服务端保存的内容。`}
                action={
                  <Button size="small" onClick={() => setResultExpanded(true)}>
                    展开全文
                  </Button>
                }
              />
            ) : null}
            {resultExpanded && resultText.length > RESULT_PREVIEW_LIMIT ? (
              <Button size="small" onClick={() => setResultExpanded(false)}>
                收起结果
              </Button>
            ) : null}
          </>
        ) : null}

        {detail.resultStatus === 'SAVED' && !resultText ? (
          <Alert
            type="info"
            showIcon
            title="结果为空"
            description="结束节点成功，但没有可展示的结果文本。"
          />
        ) : null}

        {detail.resultStatus === 'NONE' ? (
          <Alert
            type="info"
            showIcon
            title="本次运行没有最终结果"
            description={
              failed || cancelled
                ? '失败或取消的运行不会生成最终结果，已完成的节点记录与引用仍保留在上方。'
                : '只有结束节点成功执行后才会生成最终结果；运行完成前结果为空。'
            }
          />
        ) : null}

        <Typography.Text type="secondary">{SECURITY_NOTICE}</Typography.Text>
      </section>
    </div>
  );
}
