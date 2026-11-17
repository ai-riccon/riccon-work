import { Button, Typography } from 'antd';
import { ThoughtChain } from '@ant-design/x';
import type { ThoughtChainItemType } from '@ant-design/x';
import type { ApprovalDecision, ResearchTaskDetail } from '@/services/api/types';
import {
  buildStageTimeline,
  describeResearchFailure,
  formatDateTime,
  STAGE_LABELS,
  stageStatusLabel,
  stageThoughtChainStatus,
} from '@/services/research/format';
import type { StageTimelineItem } from '@/services/research/format';
import { ApprovalCard } from './approval-card';
import { McpInvocationView } from './mcp-invocation-view';
import { SourceList } from './source-list';
import { StatusTag } from './status-tag';
import type { StatusTone } from './status-tag';

/** 阶段时间线属性。 */
export interface StageTimelineProps {
  detail: ResearchTaskDetail;
  currentUser: { id: string; displayName: string } | null;
  decidingApprovalId: string | null;
  highlightedAnchor: string | null;
  onDecide: (approvalId: string, decision: ApprovalDecision, comment?: string) => void;
  onViewArtifact: () => void;
}

/**
 * 报告任务四阶段时间线：检索 → 工具取数 → 审批 → 生成。
 *
 * 使用 Ant Design X 的 ThoughtChain 呈现代理执行链；每个节点内容常驻展开，
 * 让审批按钮、工具摘要与来源列表都能被键盘直接访问，而不是藏在折叠面板里。
 *
 * @param props 任务详情与交互回调
 * @returns 阶段时间线
 */
export function StageTimeline({
  detail,
  currentUser,
  decidingApprovalId,
  highlightedAnchor,
  onDecide,
  onViewArtifact,
}: StageTimelineProps) {
  const timeline = buildStageTimeline(detail);
  const failure = describeResearchFailure(detail);
  const items: ThoughtChainItemType[] = timeline.map((item) => ({
    key: item.stage,
    title: <StageTitle item={item} />,
    description: <StageDescription item={item} />,
    content: (
      <div
        className="research-stage-content"
        data-highlighted={highlightedAnchor === `timeline-${item.stage}` ? 'true' : undefined}
        id={`timeline-${item.stage}`}
      >
        <StageContent
          currentUser={currentUser}
          decidingApprovalId={decidingApprovalId}
          detail={detail}
          failure={failure}
          highlightedAnchor={highlightedAnchor}
          item={item}
          onDecide={onDecide}
          onViewArtifact={onViewArtifact}
        />
      </div>
    ),
    status: stageThoughtChainStatus(item.status),
    blink: item.status === 'RUNNING',
    collapsible: false,
  }));

  return (
    <section aria-labelledby="research-timeline-title">
      <Typography.Title id="research-timeline-title" level={2}>
        任务时间线
      </Typography.Title>
      <Typography.Paragraph type="secondary">
        阶段按“检索 → 工具取数 → 审批 → 生成”展示；审批未完成时不会调用外部工具，也不会生成报告。
      </Typography.Paragraph>
      <ThoughtChain aria-label="任务阶段时间线" items={items} />
    </section>
  );
}

/**
 * 阶段标题：名称、状态与尝试次数。
 *
 * @param props 时间线项
 * @returns 标题节点
 */
function StageTitle({ item }: { item: StageTimelineItem }) {
  return (
    <span className="research-stage-title">
      <Typography.Text strong>{STAGE_LABELS[item.stage]}</Typography.Text>
      <StatusTag tone={stageTagColor(item.status)}>{stageStatusLabel(item.status)}</StatusTag>
      {item.attempts.length > 1 && (
        <Typography.Text type="secondary">共 {item.attempts.length} 次尝试</Typography.Text>
      )}
    </span>
  );
}

/**
 * 阶段描述：时间范围与失败错误码。
 *
 * @param props 时间线项
 * @returns 描述节点
 */
function StageDescription({ item }: { item: StageTimelineItem }) {
  const hasTime = Boolean(item.startedAt || item.finishedAt);
  return (
    <span className="research-stage-description">
      {hasTime ? (
        <>
          {item.startedAt ? `开始 ${formatDateTime(item.startedAt)}` : '尚未开始'}
          {item.finishedAt ? ` · 结束 ${formatDateTime(item.finishedAt)}` : ''}
        </>
      ) : (
        '等待前序阶段'
      )}
      {item.errorCode ? ` · 错误码 ${item.errorCode}` : ''}
    </span>
  );
}

/**
 * 阶段状态对应的 Tag 颜色。
 *
 * @param status 阶段状态
 * @returns antd Tag 预设色
 */
function stageTagColor(status: StageTimelineItem['status']): StatusTone {
  switch (status) {
    case 'RUNNING':
      return 'processing';
    case 'SUCCEEDED':
      return 'success';
    case 'FAILED':
      return 'error';
    case 'SKIPPED':
    case 'PENDING':
    default:
      return 'default';
  }
}

/** 阶段内容属性。 */
interface StageContentProps {
  detail: ResearchTaskDetail;
  item: StageTimelineItem;
  failure: ReturnType<typeof describeResearchFailure>;
  currentUser: { id: string; displayName: string } | null;
  decidingApprovalId: string | null;
  highlightedAnchor: string | null;
  onDecide: (approvalId: string, decision: ApprovalDecision, comment?: string) => void;
  onViewArtifact: () => void;
}

/**
 * 按阶段渲染正文。
 *
 * @param props 阶段与回调
 * @returns 阶段内容
 */
function StageContent({
  detail,
  item,
  failure,
  currentUser,
  decidingApprovalId,
  highlightedAnchor,
  onDecide,
  onViewArtifact,
}: StageContentProps) {
  switch (item.stage) {
    case 'RETRIEVAL':
      if (item.status === 'PENDING') {
        return <Typography.Text type="secondary">尚未开始检索。</Typography.Text>;
      }
      return (
        <>
          {typeof item.attempts[0]?.details?.query === 'string' && (
            <Typography.Paragraph type="secondary">
              检索主题：{item.attempts[0].details.query}
            </Typography.Paragraph>
          )}
          {item.status === 'RUNNING' ? (
            <Typography.Paragraph type="secondary">正在检索知识库…</Typography.Paragraph>
          ) : (
            <SourceList sources={detail.sources} />
          )}
        </>
      );

    case 'TOOL': {
      if (detail.mcpInvocations.length === 0) {
        return (
          <Typography.Text type="secondary">
            {item.approval?.status === 'PENDING'
              ? '审批通过后才会执行外部工具调用。'
              : '尚未执行外部工具调用。'}
          </Typography.Text>
        );
      }
      const invocations = [...detail.mcpInvocations].sort(
        (left, right) => Date.parse(left.startedAt) - Date.parse(right.startedAt),
      );
      return (
        <div className="research-invocation-list">
          {invocations.map((invocation, index) => {
            const attempt = resolveAttempt(detail, invocation.id, index);
            const record =
              detail.stages.find((stage) => stage.stage === 'TOOL' && stage.attempt === attempt) ??
              null;
            const approval =
              detail.approvals?.find((itemApproval) => itemApproval.attempt === attempt) ??
              item.approval;
            return (
              <McpInvocationView
                approval={approval}
                attempt={attempt}
                highlightedAnchor={highlightedAnchor}
                invocation={invocation}
                key={invocation.id}
                record={record}
              />
            );
          })}
        </div>
      );
    }

    case 'APPROVAL': {
      const approvals = [...(detail.approvals ?? [])].sort(
        (left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt),
      );
      if (approvals.length === 0) {
        return (
          <Typography.Text type="secondary">
            工具调用前会在这里请求审批；M02 的只读工具同样需要人工确认。
          </Typography.Text>
        );
      }
      return (
        <div className="research-approval-list">
          {approvals.map((approval) => (
            <ApprovalCard
              approval={approval}
              busy={decidingApprovalId === approval.id}
              currentUser={currentUser}
              key={approval.id}
              onDecide={onDecide}
            />
          ))}
        </div>
      );
    }

    case 'GENERATION': {
      if (item.status === 'FAILED') {
        return (
          <Typography.Paragraph type="danger">
            {detail.failureMessage || failure.message || '报告生成失败，请重试生成阶段。'}
            {failure.stage === 'GENERATION' ? '已完成的阶段记录会保留；重试不会产生重复产物。' : ''}
          </Typography.Paragraph>
        );
      }
      if (item.status === 'SUCCEEDED') {
        return (
          <div className="research-generation-success">
            <Typography.Paragraph>
              报告已生成并保存到工作空间
              {detail.artifact ? `（v${detail.artifact.currentVersion}）` : ''}。
            </Typography.Paragraph>
            <Button onClick={onViewArtifact} type="primary">
              查看报告
            </Button>
          </div>
        );
      }
      if (item.status === 'SKIPPED') {
        return (
          <Typography.Text type="secondary">
            审批被拒绝或前序阶段失败，未进入报告生成。
          </Typography.Text>
        );
      }
      if (item.status === 'RUNNING') {
        return (
          <Typography.Paragraph type="secondary">
            正在生成报告…已完成阶段记录保持不变。
          </Typography.Paragraph>
        );
      }
      return <Typography.Text type="secondary">报告尚未开始生成。</Typography.Text>;
    }

    default:
      return null;
  }
}

/**
 * 找到 MCP 调用对应的阶段尝试号。
 *
 * 优先使用阶段 details 中显式记录的 invocationId；否则按调用顺序回退。
 *
 * @param detail 任务详情
 * @param invocationId 调用 ID
 * @param index 调用在列表中的下标
 * @returns 尝试号
 */
function resolveAttempt(detail: ResearchTaskDetail, invocationId: string, index: number): number {
  const explicit = detail.stages.find(
    (stage) =>
      stage.stage === 'TOOL' && stage.details && stage.details.invocationId === invocationId,
  );
  if (explicit) {
    return explicit.attempt;
  }
  const toolAttempts = detail.stages
    .filter((stage) => stage.stage === 'TOOL')
    .sort((left, right) => left.attempt - right.attempt);
  return toolAttempts[index]?.attempt ?? index + 1;
}
