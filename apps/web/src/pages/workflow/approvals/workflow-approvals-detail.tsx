import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons';
import { Alert, Button, Descriptions, Input, Result, Skeleton, Space, Typography } from 'antd';
import { WorkflowTag } from '@/components/workflow/accessible-tag';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { ApiError, describeApiError } from '@/services/api/errors';
import { formatOffsetDateTime } from '@/services/api/time';
import type {
  WorkflowApprovalApi,
  WorkflowApprovalDecision,
  WorkflowApprovalDetail,
} from './approval-api';
import {
  APPROVAL_STATUS_COLORS,
  APPROVAL_STATUS_LABELS,
  APPROVED_RESULT_MESSAGE,
  DECISION_COMMENT_MAX_LENGTH,
  REJECTED_RESULT_MESSAGE,
  REJECT_COMMENT_REQUIRED_MESSAGE,
  SELF_APPROVAL_MESSAGE,
  describeAction,
  describeApprovalError,
  describeDataScope,
  formatParamsPreview,
  formatShortId,
  formatWaitDuration,
} from './format';
import './approvals.css';

/** 详情加载阶段。 */
type DetailPhase = 'loading' | 'ready' | 'error' | 'forbidden' | 'notFound';

/** 审批详情可注入依赖，便于组件测试。 */
export interface ApprovalDetailProps {
  /** 审批 API。 */
  api: WorkflowApprovalApi;
  /** 审批 ID；路由接入时由页面读取参数后传入。 */
  approvalId: string;
  /** 当前时间；测试注入固定时钟。 */
  now?: () => Date;
  /** 返回待办列表。 */
  onBack: () => void;
  /** 决定成功；父页面负责刷新待办与提示。 */
  onDecided: (decision: WorkflowApprovalDecision) => void;
}

/**
 * 审批详情与决定。
 *
 * 展示将要执行的动作、目标工具、数据范围与脱敏参数摘要；只在 PENDING 且当前用户不是发起人时
 * 提供批准/拒绝入口。拒绝理由在前端先做必填校验（服务端仍会再校验），批准理由可选；
 * 409 表示重复/并发决定，页面提示刷新而不是重试提交（M06-AC-05/06，ADR-0024 §5.3/5.4）。
 *
 * 安全边界：页面不展示完整参数、提示词、凭据或文档正文；引用正文需审批人按 M05 文档 ACL
 * 自行访问。所有状态以服务端返回为准，前端不伪造已批准/已拒绝。
 *
 * @param props API、审批 ID、时钟与回调
 * @returns 审批详情界面
 */
export function ApprovalDetail({
  api,
  approvalId,
  now = () => new Date(),
  onBack,
  onDecided,
}: ApprovalDetailProps) {
  const [phase, setPhase] = useState<DetailPhase>('loading');
  const [approval, setApproval] = useState<WorkflowApprovalDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [comment, setComment] = useState('');
  const [formError, setFormError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [pendingDecision, setPendingDecision] = useState<WorkflowApprovalDecision | null>(null);
  const requestSeq = useRef(0);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const reactId = useId();

  /** 意见输入框 ID；用 React 生成的稳定 ID，避免标签与输入框失联。 */
  const commentId = `workflow-approval-comment-${reactId.replace(/[^a-zA-Z0-9_-]/g, '')}`;

  /**
   * 拉取审批详情。
   *
   * 404 表示审批未分配给当前用户、属于其他租户或不存在，统一按“不可见即不存在”展示。
   */
  const load = useCallback(async (): Promise<void> => {
    const seq = requestSeq.current + 1;
    requestSeq.current = seq;
    setPhase('loading');
    setError(null);
    setActionError(null);
    try {
      const detail = await api.getApproval(approvalId);
      if (requestSeq.current !== seq) {
        return;
      }
      setApproval(detail);
      setPhase('ready');
    } catch (caught) {
      if (requestSeq.current !== seq) {
        return;
      }
      if (caught instanceof ApiError && caught.status === 403) {
        setPhase('forbidden');
        return;
      }
      if (caught instanceof ApiError && caught.status === 404) {
        setPhase('notFound');
        return;
      }
      setError(describeApiError(caught, '审批详情加载失败，请稍后重试。'));
      setPhase('error');
    }
  }, [api, approvalId]);

  useEffect(() => {
    void load();
  }, [load]);

  // 详情就绪后把焦点移到标题；键盘用户不必从头 Tab 穿过工具栏才能读到审批内容。
  useEffect(() => {
    if (phase === 'ready') {
      headingRef.current?.focus();
    }
  }, [phase]);

  /**
   * 提交批准或拒绝。
   *
   * 拒绝理由先去空白再校验必填；提交值也去空白，避免把仅空格的理由写入审批记录。
   *
   * @param decision 批准或拒绝
   */
  const handleDecide = async (decision: WorkflowApprovalDecision): Promise<void> => {
    if (pendingDecision) {
      return;
    }
    const trimmed = comment.trim();
    if (decision === 'REJECT' && !trimmed) {
      setFormError(REJECT_COMMENT_REQUIRED_MESSAGE);
      document.getElementById(commentId)?.focus();
      return;
    }
    setFormError(null);
    setActionError(null);
    setPendingDecision(decision);
    try {
      await api.decideApproval(approvalId, trimmed ? { decision, comment: trimmed } : { decision });
      onDecided(decision);
    } catch (caught) {
      setActionError(describeApprovalError(caught));
    } finally {
      setPendingDecision(null);
    }
  };

  const backButton = (
    <Button icon={<ArrowLeftOutlined />} onClick={onBack}>
      返回待办
    </Button>
  );

  if (phase === 'loading') {
    return (
      <div className="workflow-approvals-detail" data-testid="workflow-approval-detail">
        <Space wrap className="workflow-approvals-detail-toolbar">
          {backButton}
        </Space>
        <div aria-busy="true" aria-label="正在加载审批详情">
          <Skeleton active paragraph={{ rows: 8 }} />
        </div>
      </div>
    );
  }

  if (phase !== 'ready' || !approval) {
    const result =
      phase === 'forbidden' ? (
        <Result
          status="403"
          title="没有访问权限"
          subTitle="你没有审批权限，请联系租户管理员申请。"
          extra={[backButton]}
        />
      ) : phase === 'notFound' ? (
        <Result
          status="404"
          title="审批不存在或你无权查看"
          subTitle="只有分配给你的审批才能查看。如果你认为这是误判，请联系租户管理员。"
          extra={[backButton]}
        />
      ) : (
        <Result
          status="error"
          title="审批详情加载失败"
          subTitle={error ?? '请稍后重试。'}
          extra={[
            <Button
              key="retry"
              type="primary"
              aria-label="重试"
              autoInsertSpace={false}
              onClick={() => void load()}
            >
              重试
            </Button>,
            <Button key="back" onClick={onBack}>
              返回待办
            </Button>,
          ]}
        />
      );
    return (
      <div className="workflow-approvals-detail" data-testid="workflow-approval-detail">
        {result}
      </div>
    );
  }

  const statusLabel = APPROVAL_STATUS_LABELS[approval.status];
  const selfBlocked = approval.selfApprovalBlocked === true;
  const pending = approval.status === 'PENDING';
  const referenceCount =
    approval.documentReferenceCount ?? approval.dataScopeSummary.documentReferenceCount;
  const resultMessage =
    approval.status === 'APPROVED'
      ? APPROVED_RESULT_MESSAGE
      : approval.status === 'REJECTED'
        ? REJECTED_RESULT_MESSAGE
        : '该审批已随运行取消，不再需要处理。';

  return (
    <div className="workflow-approvals-detail" data-testid="workflow-approval-detail">
      <Space wrap className="workflow-approvals-detail-toolbar">
        {backButton}
        <Button
          icon={<ReloadOutlined />}
          loading={pendingDecision !== null}
          onClick={() => void load()}
        >
          刷新
        </Button>
      </Space>

      <header className="workflow-approvals-detail-heading">
        <h1 className="workflow-approvals-title" ref={headingRef} tabIndex={-1}>
          审批详情
        </h1>
        <Space wrap size={[8, 8]}>
          <WorkflowTag color={APPROVAL_STATUS_COLORS[approval.status]}>{statusLabel}</WorkflowTag>
          <Typography.Text type="secondary">
            审批 {formatShortId(approval.id)} · 创建于 {formatOffsetDateTime(approval.createdAt)}
          </Typography.Text>
        </Space>
      </header>

      <Descriptions
        className="workflow-approvals-summary"
        bordered
        size="small"
        column={{ xs: 1, sm: 1, md: 2 }}
        items={[
          { key: 'workflow', label: '流程', children: approval.workflowName || '未命名流程' },
          { key: 'node', label: '节点', children: approval.nodeName },
          {
            key: 'status',
            label: '状态',
            children: (
              <WorkflowTag color={APPROVAL_STATUS_COLORS[approval.status]}>
                {statusLabel}
              </WorkflowTag>
            ),
          },
          {
            key: 'requestedBy',
            label: '发起人',
            children: (
              <span title={approval.requestedBy}>{formatShortId(approval.requestedBy)}</span>
            ),
          },
          {
            key: 'createdAt',
            label: '创建时间',
            children: formatOffsetDateTime(approval.createdAt),
          },
          {
            key: 'waiting',
            label: '已等待',
            children: formatWaitDuration(approval.createdAt, now()),
          },
          {
            key: 'scope',
            label: '数据范围',
            children: describeDataScope(approval.dataScopeSummary),
          },
          {
            key: 'department',
            label: '部门',
            children:
              approval.dataScopeSummary.runScope === 'DEPARTMENT' ? (
                <span title={approval.dataScopeSummary.departmentId ?? undefined}>
                  {formatShortId(approval.dataScopeSummary.departmentId)}
                </span>
              ) : (
                '—'
              ),
          },
          { key: 'references', label: '引用文档', children: `${referenceCount} 篇` },
          { key: 'action', label: '将要执行的动作', children: describeAction(approval) },
          { key: 'tool', label: '目标工具', children: approval.targetToolName || '—' },
          {
            key: 'plan',
            label: '参数校验值',
            children: (
              <Typography.Text code title={approval.planSha256}>
                {formatShortId(approval.planSha256, 12)}
              </Typography.Text>
            ),
          },
        ]}
      />

      <section
        aria-labelledby="workflow-approval-params-title"
        className="workflow-approvals-section"
      >
        <h2 id="workflow-approval-params-title" className="workflow-approvals-section-title">
          脱敏参数摘要
        </h2>
        <pre className="workflow-approvals-params">
          {formatParamsPreview(approval.paramsPreview)}
        </pre>
        <Typography.Text type="secondary">
          只显示脱敏字段，不包含完整参数、提示词、凭据或文档正文；引用正文按你的文档权限单独访问。
        </Typography.Text>
      </section>

      {!pending ? (
        <section
          aria-labelledby="workflow-approval-record-title"
          className="workflow-approvals-section"
        >
          <h2 id="workflow-approval-record-title" className="workflow-approvals-section-title">
            审批记录
          </h2>
          <Alert
            type={
              approval.status === 'APPROVED'
                ? 'success'
                : approval.status === 'REJECTED'
                  ? 'warning'
                  : 'info'
            }
            showIcon
            title={`审批${statusLabel}`}
            description={resultMessage}
          />
          <Descriptions
            className="workflow-approvals-record"
            bordered
            size="small"
            column={{ xs: 1, sm: 1, md: 2 }}
            items={[
              {
                key: 'decidedBy',
                label: '审批人',
                children: (
                  <span title={approval.decidedBy ?? undefined}>
                    {formatShortId(approval.decidedBy)}
                  </span>
                ),
              },
              {
                key: 'decidedAt',
                label: '决定时间',
                children: formatOffsetDateTime(approval.decidedAt),
              },
              { key: 'decision', label: '决定', children: statusLabel },
              { key: 'comment', label: '审批理由', children: approval.decisionComment || '—' },
            ]}
          />
        </section>
      ) : null}

      {pending && selfBlocked ? (
        <Alert
          className="workflow-approvals-section"
          type="warning"
          showIcon
          title="不能审批自己发起的运行"
          description={SELF_APPROVAL_MESSAGE}
        />
      ) : null}

      {pending && !selfBlocked ? (
        <section
          aria-labelledby="workflow-approval-decision-title"
          className="workflow-approvals-decision"
        >
          <h2 id="workflow-approval-decision-title" className="workflow-approvals-section-title">
            审批决定
          </h2>
          <Alert
            type="info"
            showIcon
            title="等待你的决定"
            description="批准后运行将继续执行后续节点并产出结果；拒绝会终止本次运行，后续节点标记为跳过、不再执行。"
          />

          {actionError ? (
            <Alert
              className="workflow-approvals-action-error"
              type="error"
              showIcon
              role="alert"
              title="操作未完成"
              description={actionError}
              action={
                <Button size="small" onClick={() => void load()}>
                  刷新审批
                </Button>
              }
            />
          ) : null}

          <div className="workflow-approvals-comment">
            <label htmlFor={commentId}>
              审批意见（拒绝时必填，最多 {DECISION_COMMENT_MAX_LENGTH} 字）
            </label>
            <Input.TextArea
              id={commentId}
              autoSize={{ minRows: 3, maxRows: 6 }}
              disabled={pendingDecision !== null}
              maxLength={DECISION_COMMENT_MAX_LENGTH}
              placeholder="拒绝时必须填写理由，最多 500 字；批准时可选"
              showCount
              value={comment}
              onChange={(event) => {
                setComment(event.target.value);
                if (formError) {
                  setFormError(null);
                }
              }}
            />
            {formError ? (
              <Typography.Text type="danger" role="alert">
                {formError}
              </Typography.Text>
            ) : null}
          </div>

          <Space wrap>
            <Button
              type="primary"
              aria-label="批准"
              autoInsertSpace={false}
              disabled={pendingDecision !== null}
              loading={pendingDecision === 'APPROVE'}
              onClick={() => void handleDecide('APPROVE')}
            >
              批准
            </Button>
            <Button
              danger
              aria-label="拒绝"
              autoInsertSpace={false}
              disabled={pendingDecision !== null}
              loading={pendingDecision === 'REJECT'}
              onClick={() => void handleDecide('REJECT')}
            >
              拒绝
            </Button>
          </Space>
        </section>
      ) : null}
    </div>
  );
}
