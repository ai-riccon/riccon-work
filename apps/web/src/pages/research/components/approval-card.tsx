import { CheckOutlined, CloseOutlined } from '@ant-design/icons';
import { Alert, Button, Input, Space, Typography } from 'antd';
import { useState } from 'react';
import type { Approval, ApprovalDecision } from '@/services/api/types';
import {
  approvalStatusColor,
  approvalStatusLabel,
  formatDateTime,
  formatParamsPreview,
  formatShortId,
} from '@/services/research/format';
import { StatusTag } from './status-tag';

/** 审批卡片属性。 */
export interface ApprovalCardProps {
  approval: Approval;
  /** 当前登录用户，用于把 UUID 显示为可读姓名。 */
  currentUser: { id: string; displayName: string } | null;
  /** 是否正在提交该审批。 */
  busy: boolean;
  /** 提交决定；页面负责幂等与刷新。 */
  onDecide: (approvalId: string, decision: ApprovalDecision, comment?: string) => void;
}

/**
 * 展示单个审批的动作、工具与数据范围，并提供批准/拒绝入口。
 *
 * 安全边界：
 * - 只展示服务端返回的脱敏入参摘要与 SHA-256 校验值，不推断原始参数；
 * - 已决定的审批不再提供按钮，避免重复提交；
 * - 拒绝后任务立即终止，由页面刷新服务端状态，不在前端伪造终态。
 *
 * @param props 审批、当前用户与回调
 * @returns 审批卡片
 */
export function ApprovalCard({ approval, currentUser, busy, onDecide }: ApprovalCardProps) {
  const [comment, setComment] = useState('');
  const pending = approval.status === 'PENDING';
  const titleId = `approval-title-${approval.id}`;
  const isRequester = currentUser !== null && approval.requestedBy === currentUser.id;

  /**
   * 提交审批决定。
   *
   * @param decision 批准或拒绝
   */
  const handleDecision = (decision: ApprovalDecision): void => {
    onDecide(approval.id, decision, comment);
  };

  return (
    <section aria-labelledby={titleId} className="research-approval">
      <div className="research-approval-header">
        <Typography.Title id={titleId} level={4} style={{ margin: 0 }}>
          {pending ? '待审批：调用外部工具' : '审批记录'}
        </Typography.Title>
        <StatusTag tone={approvalStatusColor(approval.status)}>
          {approvalStatusLabel(approval.status)}
        </StatusTag>
      </div>

      <dl className="research-definition-list">
        <div>
          <dt>动作</dt>
          <dd>
            {approval.actionType === 'MCP_TOOL_CALL' ? '调用外部 MCP 工具' : approval.actionType}
          </dd>
        </div>
        <div>
          <dt>目标工具</dt>
          <dd>
            <Typography.Text code>{approval.toolName}</Typography.Text>
          </dd>
        </div>
        <div>
          <dt>数据范围（入参摘要）</dt>
          <dd>
            <pre className="research-params">{formatParamsPreview(approval.paramsPreview)}</pre>
            <Typography.Text className="research-hint" type="secondary">
              仅包含本次调用的脱敏参数；完整入参以 SHA-256 校验值核对。
            </Typography.Text>
          </dd>
        </div>
        <div>
          <dt>参数校验值</dt>
          <dd>
            <Typography.Text code title={approval.paramsSha256}>
              {approval.paramsSha256}
            </Typography.Text>
          </dd>
        </div>
        <div>
          <dt>发起人</dt>
          <dd>
            {isRequester && currentUser
              ? currentUser.displayName
              : formatShortId(approval.requestedBy)}
          </dd>
        </div>
        <div>
          <dt>请求时间</dt>
          <dd>{formatDateTime(approval.createdAt)}</dd>
        </div>
        {approval.expiresAt && (
          <div>
            <dt>过期时间</dt>
            <dd>{formatDateTime(approval.expiresAt)}</dd>
          </div>
        )}
        {approval.status === 'APPROVED' || approval.status === 'REJECTED' ? (
          <>
            <div>
              <dt>审批人</dt>
              <dd>
                {currentUser && approval.decidedBy === currentUser.id
                  ? currentUser.displayName
                  : formatShortId(approval.decidedBy)}
              </dd>
            </div>
            <div>
              <dt>审批时间</dt>
              <dd>{formatDateTime(approval.decidedAt)}</dd>
            </div>
            <div>
              <dt>审批备注</dt>
              <dd>{approval.decisionComment || '—'}</dd>
            </div>
          </>
        ) : null}
      </dl>

      {pending ? (
        <div className="research-approval-actions">
          <Alert
            showIcon
            description={`批准后任务会调用 ${approval.toolName}，然后继续生成报告；拒绝后任务立即终止且不会产生报告。`}
            style={{ marginBottom: 12 }}
            title="等待你的决定"
            type="info"
          />
          <Space orientation="vertical" size={8} style={{ width: '100%' }}>
            <Input.TextArea
              aria-label="审批备注（可选）"
              autoSize={{ minRows: 2, maxRows: 4 }}
              maxLength={500}
              onChange={(event) => setComment(event.target.value)}
              placeholder="审批备注（可选），最多 500 字"
              value={comment}
            />
            <Space>
              <Button
                disabled={busy}
                icon={<CheckOutlined />}
                loading={busy}
                onClick={() => handleDecision('APPROVE')}
                type="primary"
              >
                批准
              </Button>
              <Button
                danger
                disabled={busy}
                icon={<CloseOutlined />}
                onClick={() => handleDecision('REJECT')}
              >
                拒绝
              </Button>
            </Space>
          </Space>
        </div>
      ) : (
        <Typography.Text type="secondary">该审批已结束，不可重复提交。</Typography.Text>
      )}
    </section>
  );
}
