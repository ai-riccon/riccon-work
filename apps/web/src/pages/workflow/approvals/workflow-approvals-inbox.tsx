import { ReloadOutlined } from '@ant-design/icons';
import { Button, Empty, Pagination, Result, Skeleton, Space, Typography } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { WorkflowTag } from '@/components/workflow/accessible-tag';
import { ApiError, describeApiError } from '@/services/api/errors';
import { formatOffsetDateTime } from '@/services/api/time';
import type { WorkflowApproval, WorkflowApprovalApi } from './approval-api';
import {
  APPROVAL_EMPTY_MESSAGE,
  APPROVAL_STATUS_COLORS,
  APPROVAL_STATUS_LABELS,
  describeAction,
  describeDataScope,
  formatShortId,
  formatWaitDuration,
} from './format';
import './approvals.css';

/** 待办加载阶段。 */
type InboxPhase = 'loading' | 'ready' | 'empty' | 'error' | 'forbidden' | 'notFound';

/** 审批待办可注入依赖，便于组件测试。 */
export interface ApprovalInboxProps {
  /** 审批 API。 */
  api: WorkflowApprovalApi;
  /** 刷新令牌；父页面在决定成功后递增，触发待办重新加载。 */
  refreshToken?: number;
  /** 当前时间；测试注入固定时钟，生产使用系统时间。 */
  now?: () => Date;
  /** 打开审批详情。 */
  onOpen: (approvalId: string) => void;
  /** 返回编排 Studio；错误态与空态的下一步。 */
  onBackToStudio: () => void;
  /**
   * 返回待办时重新聚焦标题的令牌。
   *
   * 详情返回后列表会重新挂载，浏览器焦点回落到 body；递增令牌让键盘用户从标题继续，
   * 而不是在页面底部重新寻找入口。初始值为 0，首次进入不抢焦点。
   */
  focusToken?: number;
}

/** 每页条数；与审批待办常用批量处理规模一致，符合合同分页范围。 */
const PAGE_SIZE = 20;

/**
 * 审批待办列表。
 *
 * 只展示服务端返回的“分配给当前用户、非本人发起、PENDING”的审批（服务端已按 `created_at ASC`
 * 排序）；页面不重新排序、不推断其他审批是否存在。每项显示流程、节点、发起人、等待时长与
 * 数据范围摘要（M06-AC-05）。
 *
 * 状态覆盖：加载、空、错误、403（权限被撤销）、404（平台管理员/不可见）、成功；375px 使用卡片布局。
 *
 * @param props API、刷新令牌、时钟与回调
 * @returns 待办列表界面
 */
export function ApprovalInbox({
  api,
  refreshToken = 0,
  now = () => new Date(),
  onOpen,
  onBackToStudio,
  focusToken = 0,
}: ApprovalInboxProps) {
  const [phase, setPhase] = useState<InboxPhase>('loading');
  const [items, setItems] = useState<WorkflowApproval[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [error, setError] = useState<string | null>(null);
  const requestSeq = useRef(0);
  const headingRef = useRef<HTMLHeadingElement>(null);

  /**
   * 拉取当前页待办。
   *
   * 使用递增序号丢弃过期响应，避免翻页或刷新时旧请求覆盖新结果。
   */
  const load = useCallback(async (): Promise<void> => {
    const seq = requestSeq.current + 1;
    requestSeq.current = seq;
    setPhase('loading');
    setError(null);
    try {
      const result = await api.listApprovals({ status: 'PENDING', page, size: PAGE_SIZE });
      if (requestSeq.current !== seq) {
        return;
      }
      // 最后一页的最后一项被决定后自动回到第一页，避免误显示“暂无待审批项”。
      if (result.items.length === 0 && page > 1 && result.total > 0) {
        setPage(1);
        return;
      }
      setItems(result.items);
      setTotal(result.total);
      setPhase(result.items.length === 0 ? 'empty' : 'ready');
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
      setError(describeApiError(caught, '审批待办加载失败，请稍后重试。'));
      setPhase('error');
    }
  }, [api, page]);

  // 刷新令牌变化时重新加载当前页；父页面在决定成功后递增令牌。
  useEffect(() => {
    void load();
  }, [load, refreshToken]);

  // 从详情返回待办后聚焦标题，让键盘用户继续从页面开头操作。
  useEffect(() => {
    if (focusToken > 0) {
      headingRef.current?.focus();
    }
  }, [focusToken]);

  const nowDate = now();

  return (
    <div className="workflow-approvals-inbox" data-testid="workflow-approval-inbox">
      <header className="workflow-approvals-heading">
        <h1 className="workflow-approvals-title" ref={headingRef} tabIndex={-1}>
          审批待办
        </h1>
        <Typography.Paragraph type="secondary" className="workflow-approvals-intro">
          这里只显示分配给你的待审批项，按等待时间从早到晚排列。批准后运行会继续执行；拒绝会终止本次运行。
        </Typography.Paragraph>
      </header>

      <div className="workflow-approvals-toolbar">
        <Space wrap size={[8, 8]}>
          <Button
            icon={<ReloadOutlined />}
            onClick={() => void load()}
            loading={phase === 'loading'}
          >
            刷新
          </Button>
          {phase === 'ready' ? (
            <Typography.Text type="secondary">共 {total} 项待审批</Typography.Text>
          ) : null}
        </Space>
      </div>

      {phase === 'loading' ? (
        <div aria-busy="true" aria-label="正在加载审批待办">
          <Skeleton active paragraph={{ rows: 5 }} />
        </div>
      ) : null}

      {phase === 'forbidden' ? (
        <Result
          status="403"
          title="没有审批权限"
          subTitle="你没有审批权限，请联系租户管理员申请。"
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
            <Button key="back" onClick={onBackToStudio}>
              返回编排 Studio
            </Button>,
          ]}
        />
      ) : null}

      {phase === 'notFound' ? (
        <Result
          status="404"
          title="审批内容不存在"
          subTitle="审批内容不存在，或当前账号无权访问。平台管理员不查看租户审批内容。"
          extra={[
            <Button key="back" type="primary" onClick={onBackToStudio}>
              返回编排 Studio
            </Button>,
            <Button
              key="retry"
              aria-label="重试"
              autoInsertSpace={false}
              onClick={() => void load()}
            >
              重试
            </Button>,
          ]}
        />
      ) : null}

      {phase === 'error' ? (
        <Result
          status="error"
          title="审批待办加载失败"
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
            <Button key="back" onClick={onBackToStudio}>
              返回编排 Studio
            </Button>,
          ]}
        />
      ) : null}

      {phase === 'empty' ? (
        <div className="workflow-approvals-empty">
          <Empty description={APPROVAL_EMPTY_MESSAGE} />
          <Space wrap>
            <Button icon={<ReloadOutlined />} onClick={() => void load()}>
              刷新待办
            </Button>
            <Button onClick={onBackToStudio}>返回编排 Studio</Button>
          </Space>
        </div>
      ) : null}

      {phase === 'ready' ? (
        <>
          <ul className="workflow-approvals-list" data-testid="workflow-approval-list">
            {items.map((approval) => {
              const workflowName = approval.workflowName || '未命名流程';
              const waiting = formatWaitDuration(approval.createdAt, nowDate);
              return (
                <li className="workflow-approvals-item" key={approval.id}>
                  <div className="workflow-approvals-item-main">
                    <div className="workflow-approvals-item-title">
                      <Typography.Title level={2} className="workflow-approvals-item-name">
                        {workflowName}
                      </Typography.Title>
                      <WorkflowTag color={APPROVAL_STATUS_COLORS[approval.status]}>
                        {APPROVAL_STATUS_LABELS[approval.status]}
                      </WorkflowTag>
                    </div>
                    <dl className="workflow-approvals-meta">
                      <div>
                        <dt>节点</dt>
                        <dd>{approval.nodeName}</dd>
                      </div>
                      <div>
                        <dt>发起人</dt>
                        <dd title={approval.requestedBy}>{formatShortId(approval.requestedBy)}</dd>
                      </div>
                      <div>
                        <dt>等待时长</dt>
                        <dd title={`创建于 ${formatOffsetDateTime(approval.createdAt)}`}>
                          已等待 {waiting}
                        </dd>
                      </div>
                      <div>
                        <dt>数据范围</dt>
                        <dd>{describeDataScope(approval.dataScopeSummary)}</dd>
                      </div>
                      <div>
                        <dt>将执行</dt>
                        <dd>{describeAction(approval)}</dd>
                      </div>
                    </dl>
                  </div>
                  <div className="workflow-approvals-item-actions">
                    <Button
                      type="primary"
                      aria-label={`查看审批详情：${workflowName} · ${approval.nodeName} 查看详情`}
                      onClick={() => onOpen(approval.id)}
                    >
                      查看详情
                    </Button>
                  </div>
                </li>
              );
            })}
          </ul>
          {total > PAGE_SIZE ? (
            <Pagination
              aria-label="审批待办分页"
              size="small"
              responsive
              showSizeChanger={false}
              current={page}
              pageSize={PAGE_SIZE}
              total={total}
              onChange={setPage}
            />
          ) : null}
        </>
      ) : null}
    </div>
  );
}
