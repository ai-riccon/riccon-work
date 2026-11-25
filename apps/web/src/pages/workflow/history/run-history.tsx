import { ReloadOutlined } from '@ant-design/icons';
import {
  Alert,
  App,
  Button,
  Card,
  Checkbox,
  DatePicker,
  Descriptions,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Pagination,
  Radio,
  Result,
  Select,
  Skeleton,
  Space,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, describeApiError } from '@/services/api/errors';
import { formatOffsetDateTime } from '@/services/api/time';
import type { SessionInfo } from '@/services/api/types';
import { formatShortId } from '../approvals/format';
import { hasWorkflowPermission, WORKFLOW_PERMISSIONS } from '../list/format';
import type { WorkflowDefinition } from '../list/workflow-api';
import { approvalStatusColor, approvalStatusLabel, describeFailure } from '../run/format';
import type { WorkflowNodeRun } from '../run/run-api';
import type { ListRunsInput, WorkflowHistoryApi, WorkflowRunSummary } from './history-api';
import {
  buildRunFilter,
  canCancelRun,
  canOperateRun,
  describeRunApproval,
  describeRunControlFailure,
  describeRunFilter,
  isWorkflowUnavailable,
  latestFailedNodes,
  PAGE_SIZE,
  RUN_HISTORY_EMPTY_MESSAGE,
  RUN_HISTORY_SORT_NOTE,
  RUN_STATUS_COLORS,
  RUN_STATUS_LABELS,
  RUN_STATUS_OPTIONS,
  runDurationText,
  type DayjsLike,
  type RunFilterValues,
} from './format';
import './history.css';

/** 运行历史加载阶段。 */
type RunHistoryPhase =
  'loading' | 'ready' | 'empty' | 'error' | 'forbidden' | 'notFound' | 'disabled';

/** 重试弹窗阶段。 */
type RetryPhase = 'loading' | 'ready' | 'error';

/** 运行历史面板属性。 */
export interface RunHistoryPanelProps {
  /** 运行历史 API；页面注入默认同源客户端。 */
  api: WorkflowHistoryApi;
  /** 当前会话；用于权限与“只看我发起的”。 */
  session: SessionInfo;
  /** 流程下拉候选；页面统一加载并复用。 */
  workflowOptions: WorkflowDefinition[];
  /** 流程候选加载中。 */
  workflowOptionsLoading?: boolean;
  /** 流程候选加载失败说明。 */
  workflowOptionsError?: string | null;
  /** 深链初始流程筛选。 */
  initialWorkflowId?: string;
  /** 打开 T12a 运行详情路由。 */
  onOpenDetail: (runId: string) => void;
  /** 返回编排 Studio；无权限/不存在/错误态的下一步。 */
  onBackToStudio: () => void;
}

/**
 * 运行历史列表与取消/重试动作。
 *
 * 覆盖 M06-AC-12/13/19：
 * - 列表显示流程、版本、状态、发起人、开始/结束时间、耗时与审批聚合；
 * - 支持流程/版本/状态/时间/发起人过滤，分页并固定开始时间倒序（服务端排序）；
 * - 空态提供清除筛选入口；403/404/503/通用错误各有中文说明与下一步；
 * - 只有发起人或租户管理员看到取消/重试入口；取消只对活跃运行，重试只对失败运行；
 * - 重试前读取运行详情，只列出“最新一次尝试仍失败”的节点，避免旧尝试被误重试。
 *
 * 页面不复制服务端状态：动作成功后重新读取当前页，若当前页因数据变化为空则回到第一页。
 *
 * @param props API、会话、流程候选与导航回调
 * @returns 运行历史卡片
 */
export function RunHistoryPanel({
  api,
  session,
  workflowOptions,
  workflowOptionsLoading = false,
  workflowOptionsError = null,
  initialWorkflowId,
  onOpenDetail,
  onBackToStudio,
}: RunHistoryPanelProps) {
  const { message } = App.useApp();
  const [form] = Form.useForm<RunFilterValues>();
  const [onlyMine, setOnlyMine] = useState(false);
  const canRead = hasWorkflowPermission(session.permissions, WORKFLOW_PERMISSIONS.read);
  const canRun = hasWorkflowPermission(session.permissions, WORKFLOW_PERMISSIONS.run);
  const [phase, setPhase] = useState<RunHistoryPhase>('loading');
  const [items, setItems] = useState<WorkflowRunSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [appliedFilter, setAppliedFilter] = useState<ListRunsInput>(() =>
    initialWorkflowId ? { workflowId: initialWorkflowId } : {},
  );
  const [error, setError] = useState<string | null>(null);
  const requestSeq = useRef(0);

  const [cancelTarget, setCancelTarget] = useState<WorkflowRunSummary | null>(null);
  const [cancelBusy, setCancelBusy] = useState(false);
  const [cancelError, setCancelError] = useState<string | null>(null);

  const [retryTarget, setRetryTarget] = useState<WorkflowRunSummary | null>(null);
  const [retryPhase, setRetryPhase] = useState<RetryPhase>('loading');
  const [retryNodes, setRetryNodes] = useState<WorkflowNodeRun[]>([]);
  const [retryNodeId, setRetryNodeId] = useState<string | undefined>(undefined);
  const [retryBusy, setRetryBusy] = useState(false);
  const [retryError, setRetryError] = useState<string | null>(null);

  // 深链参数变化时同步流程筛选；只影响表单和已应用条件，不触发重复加载的竞态。
  useEffect(() => {
    if (!initialWorkflowId) {
      return;
    }
    form.setFieldsValue({ workflowId: initialWorkflowId });
    setAppliedFilter((current) =>
      current.workflowId === initialWorkflowId
        ? current
        : { ...current, workflowId: initialWorkflowId },
    );
    setPage(1);
  }, [form, initialWorkflowId]);

  /**
   * 读取当前页运行历史。
   *
   * 使用递增序号丢弃过期响应；最后一页因动作导致为空且总数不为 0 时回到第一页，
   * 避免误显示“当前筛选条件下没有运行”。
   */
  const load = useCallback(async (): Promise<void> => {
    if (!canRead) {
      setPhase('forbidden');
      return;
    }
    const seq = requestSeq.current + 1;
    requestSeq.current = seq;
    setPhase('loading');
    setError(null);
    try {
      const result = await api.listRuns({ ...appliedFilter, page, size: PAGE_SIZE });
      if (requestSeq.current !== seq) {
        return;
      }
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
      if (isWorkflowUnavailable(caught)) {
        setPhase('disabled');
        return;
      }
      setError(describeApiError(caught, '运行历史加载失败，请稍后重试。'));
      setPhase('error');
    }
  }, [api, appliedFilter, canRead, page]);

  useEffect(() => {
    void load();
  }, [load]);

  /**
   * 应用筛选并回到第一页。
   *
   * @param values 表单值
   */
  const handleSearch = async (values: RunFilterValues): Promise<void> => {
    const built = buildRunFilter(values, session.user.id);
    if (built.error) {
      setError(built.error);
      setPhase('error');
      return;
    }
    setAppliedFilter(built.filter);
    setPage(1);
  };

  /**
   * 清除全部筛选。
   *
   * 重置表单与已应用条件，并回到第一页；空态的“清除筛选”入口复用同一逻辑。
   */
  const handleReset = (): void => {
    form.resetFields();
    setOnlyMine(false);
    setAppliedFilter({});
    setPage(1);
    setError(null);
  };

  /**
   * 打开取消确认弹窗。
   *
   * @param run 目标运行
   */
  const openCancel = (run: WorkflowRunSummary): void => {
    setCancelTarget(run);
    setCancelError(null);
  };

  /**
   * 提交取消请求。
   *
   * 成功后关闭弹窗并刷新当前页；409/403/404/503 保留在弹窗内，便于用户按提示下一步。
   */
  const handleCancel = async (): Promise<void> => {
    if (!cancelTarget) {
      return;
    }
    setCancelBusy(true);
    setCancelError(null);
    try {
      await api.cancelRun(cancelTarget.id);
      setCancelTarget(null);
      message.success('已请求取消运行，后续节点不再执行；已产生的节点记录与结果保留。');
      await load();
    } catch (caught) {
      setCancelError(describeRunControlFailure(caught, 'cancel'));
    } finally {
      setCancelBusy(false);
    }
  };

  /**
   * 打开重试弹窗并读取运行详情。
   *
   * 只把最新一次尝试仍失败的节点放入选择器；没有失败节点或运行状态已变化时显示明确原因，
   * 不提交请求。
   *
   * @param run 目标运行
   */
  const openRetry = async (run: WorkflowRunSummary): Promise<void> => {
    setRetryTarget(run);
    setRetryPhase('loading');
    setRetryNodes([]);
    setRetryNodeId(undefined);
    setRetryError(null);
    try {
      const detail = await api.getRunDetail(run.id);
      if (detail.status !== 'FAILED') {
        setRetryError('运行已不在失败状态，请刷新列表后查看最新状态。');
        setRetryPhase('error');
        return;
      }
      const failed = latestFailedNodes(detail.nodes ?? []);
      setRetryNodes(failed);
      setRetryNodeId(failed[0]?.nodeId);
      if (failed.length === 0) {
        setRetryError('没有找到可重试的失败节点，请刷新运行状态后重试。');
        setRetryPhase('error');
        return;
      }
      setRetryPhase('ready');
    } catch (caught) {
      setRetryError(describeRunControlFailure(caught, 'retry'));
      setRetryPhase('error');
    }
  };

  /**
   * 提交重试请求。
   *
   * 只提交所选失败节点；成功后关闭弹窗并刷新列表，失败信息保留在弹窗中。
   */
  const handleRetry = async (): Promise<void> => {
    if (!retryTarget || !retryNodeId || retryPhase !== 'ready') {
      return;
    }
    setRetryBusy(true);
    setRetryError(null);
    try {
      await api.retryRunNode(retryTarget.id, retryNodeId);
      setRetryTarget(null);
      message.success('已提交重试，运行将重新排队；上游成功节点不会重跑。');
      await load();
    } catch (caught) {
      setRetryError(describeRunControlFailure(caught, 'retry'));
    } finally {
      setRetryBusy(false);
    }
  };

  const workflowFilterOptions = workflowOptions.map((workflow) => ({
    value: workflow.id,
    label: workflow.name,
  }));

  return (
    <Card
      className="workflow-history-card"
      title="运行历史"
      extra={
        <Button
          icon={<ReloadOutlined aria-hidden="true" />}
          onClick={() => void load()}
          loading={phase === 'loading'}
          aria-label="刷新运行历史"
        >
          刷新
        </Button>
      }
    >
      <Typography.Paragraph type="secondary" className="workflow-history-note">
        {RUN_HISTORY_SORT_NOTE}
      </Typography.Paragraph>

      <Form<RunFilterValues>
        form={form}
        layout="vertical"
        className="workflow-history-filter"
        initialValues={{ onlyMine: false, status: 'ALL' }}
        onFinish={(values) => void handleSearch(values)}
      >
        <div className="workflow-history-filter-grid">
          <Form.Item label="流程" name="workflowId">
            <Select
              allowClear
              aria-label="流程筛选"
              loading={workflowOptionsLoading}
              options={workflowFilterOptions}
              placeholder={workflowOptionsError ? '流程目录加载失败，可重试' : '全部可见流程'}
              showSearch
              optionFilterProp="label"
            />
          </Form.Item>

          <Form.Item label="版本" name="versionNo">
            <InputNumber
              aria-label="版本筛选"
              min={1}
              precision={0}
              placeholder="全部版本"
              style={{ width: '100%' }}
            />
          </Form.Item>

          <Form.Item label="状态" name="status">
            <Select
              allowClear
              aria-label="状态筛选"
              options={[{ value: 'ALL', label: '全部状态' }, ...RUN_STATUS_OPTIONS]}
              placeholder="全部状态"
            />
          </Form.Item>

          <Form.Item label="发起人用户 ID" name="requesterUserId">
            <Input
              allowClear
              aria-label="发起人用户 ID"
              disabled={onlyMine === true}
              placeholder={onlyMine ? '已限定为当前账号' : '全部发起人（UUID）'}
            />
          </Form.Item>

          <Form.Item label="开始时间" name="from">
            <DatePicker
              aria-label="运行开始时间下限"
              allowClear
              format="YYYY-MM-DD HH:mm:ss"
              placeholder="最早"
              showTime={{ format: 'HH:mm:ss' }}
              style={{ width: '100%' }}
            />
          </Form.Item>

          <Form.Item
            dependencies={['from']}
            label="结束时间"
            name="to"
            rules={[
              ({ getFieldValue }) => ({
                validator: async (_rule, value: DayjsLike | null | undefined) => {
                  const from = getFieldValue('from') as DayjsLike | null | undefined;
                  if (from && value && from.toDate().getTime() > value.toDate().getTime()) {
                    throw new Error('结束时间不能早于开始时间。');
                  }
                },
              }),
            ]}
          >
            <DatePicker
              aria-label="运行开始时间上限"
              allowClear
              format="YYYY-MM-DD HH:mm:ss"
              placeholder="当前"
              showTime={{ format: 'HH:mm:ss' }}
              style={{ width: '100%' }}
            />
          </Form.Item>
        </div>

        <div className="workflow-history-filter-actions">
          <Form.Item name="onlyMine" valuePropName="checked" noStyle>
            <Checkbox onChange={(event) => setOnlyMine(event.target.checked)}>
              只看我发起的
            </Checkbox>
          </Form.Item>
          <Space wrap size={[8, 8]}>
            <Button htmlType="submit" type="primary" aria-label="查询运行历史">
              查询
            </Button>
            <Button onClick={handleReset}>清除筛选</Button>
          </Space>
        </div>
      </Form>

      {workflowOptionsError ? (
        <Alert
          className="workflow-history-alert"
          type="warning"
          showIcon
          title="流程目录加载失败"
          description={`${workflowOptionsError} 仍可按时间、状态或发起人筛选运行；请刷新页面重试流程目录。`}
        />
      ) : null}

      {phase === 'loading' ? (
        <div aria-busy="true" aria-label="正在加载运行历史">
          <Skeleton active paragraph={{ rows: 6 }} />
        </div>
      ) : null}

      {phase === 'forbidden' ? (
        <Result
          status="403"
          title="没有访问权限"
          subTitle="运行历史需要 workflow.read 权限，请联系租户管理员申请。"
          extra={[
            <Button key="retry" type="primary" aria-label="重试" onClick={() => void load()}>
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
          title="没有可查看的运行"
          subTitle="运行不存在、已到期，或当前账号无权访问；为保护数据，页面不提示对象是否存在。"
          extra={[
            <Button key="retry" aria-label="重试" onClick={() => void load()}>
              重试
            </Button>,
            <Button key="back" type="primary" onClick={onBackToStudio}>
              返回编排 Studio
            </Button>,
          ]}
        />
      ) : null}

      {phase === 'disabled' ? (
        <Result
          status="warning"
          title="编排功能已关闭"
          subTitle="已有运行记录仍可查看；新运行、重试与取消会暂时不可用。请联系运维人员开启编排功能。"
          extra={[
            <Button key="retry" type="primary" aria-label="重试" onClick={() => void load()}>
              重新加载
            </Button>,
            <Button key="back" onClick={onBackToStudio}>
              返回编排 Studio
            </Button>,
          ]}
        />
      ) : null}

      {phase === 'error' ? (
        <Result
          status="error"
          title="运行历史加载失败"
          subTitle={error ?? '请稍后重试。'}
          extra={[
            <Button key="retry" type="primary" aria-label="重试" onClick={() => void load()}>
              重试
            </Button>,
            <Button key="back" onClick={onBackToStudio}>
              返回编排 Studio
            </Button>,
          ]}
        />
      ) : null}

      {phase === 'empty' ? (
        <div className="workflow-history-empty">
          <Empty description={RUN_HISTORY_EMPTY_MESSAGE} />
          <Typography.Text type="secondary">
            当前筛选：{describeRunFilter(appliedFilter)}
          </Typography.Text>
          <Space wrap>
            <Button onClick={handleReset}>清除全部筛选</Button>
            <Button
              icon={<ReloadOutlined aria-hidden="true" />}
              onClick={() => void load()}
              aria-label="刷新运行历史"
            >
              刷新
            </Button>
          </Space>
        </div>
      ) : null}

      {phase === 'ready' ? (
        <>
          <Typography.Text type="secondary" className="workflow-history-count">
            共 {total} 条运行 · 当前筛选：{describeRunFilter(appliedFilter)}
          </Typography.Text>
          <ul className="workflow-history-list" data-testid="workflow-run-list">
            {items.map((run) => {
              const workflowName = run.workflowName || '未命名流程';
              const operable = canRun && canOperateRun(run, session);
              return (
                <li className="workflow-history-item" key={run.id}>
                  <div className="workflow-history-item-main">
                    <div className="workflow-history-item-title">
                      <Typography.Title level={2} className="workflow-history-item-name">
                        {workflowName}
                      </Typography.Title>
                      <Tag color={RUN_STATUS_COLORS[run.status]}>
                        {RUN_STATUS_LABELS[run.status]}
                      </Tag>
                      <Tag>版本 v{run.versionNo}</Tag>
                      {run.approvalStatus ? (
                        <Tag color={approvalStatusColor(run.approvalStatus)}>
                          审批：{approvalStatusLabel(run.approvalStatus)}
                        </Tag>
                      ) : null}
                    </div>
                    <dl className="workflow-history-meta">
                      <div>
                        <dt>发起人</dt>
                        <dd title={run.requesterUserId}>
                          {formatShortId(run.requesterUserId, 12)}
                        </dd>
                      </div>
                      <div>
                        <dt>开始时间</dt>
                        <dd>{formatOffsetDateTime(run.startedAt ?? run.createdAt)}</dd>
                      </div>
                      <div>
                        <dt>结束时间</dt>
                        <dd>{formatOffsetDateTime(run.finishedAt)}</dd>
                      </div>
                      <div>
                        <dt>耗时</dt>
                        <dd>{runDurationText(run)}</dd>
                      </div>
                      <div>
                        <dt>审批状态</dt>
                        <dd>{describeRunApproval(run)}</dd>
                      </div>
                    </dl>
                    {run.status === 'FAILED' ? (
                      <Typography.Text type="danger" className="workflow-history-failure">
                        {describeFailure(run.errorCode, run.errorMessage) ??
                          '运行以失败结束，请打开详情查看失败节点与错误码。'}
                      </Typography.Text>
                    ) : null}
                  </div>
                  <div className="workflow-history-item-actions">
                    <Button
                      type="link"
                      aria-label={`查看运行详情：${workflowName}`}
                      onClick={() => onOpenDetail(run.id)}
                    >
                      查看详情
                    </Button>
                    {operable && canCancelRun(run.status) ? (
                      <Button
                        danger
                        aria-label={`取消运行：${workflowName}`}
                        onClick={() => openCancel(run)}
                      >
                        取消运行
                      </Button>
                    ) : null}
                    {operable && run.status === 'FAILED' ? (
                      <Button
                        danger
                        aria-label={`重试失败节点：${workflowName}`}
                        onClick={() => void openRetry(run)}
                      >
                        重试失败节点
                      </Button>
                    ) : null}
                  </div>
                </li>
              );
            })}
          </ul>
          {total > PAGE_SIZE ? (
            <Pagination
              aria-label="运行历史分页"
              simple
              current={page}
              pageSize={PAGE_SIZE}
              total={total}
              onChange={(nextPage) => setPage(nextPage)}
            />
          ) : null}
        </>
      ) : null}

      <Modal
        title="确认取消运行"
        open={cancelTarget != null}
        okText="取消运行"
        cancelText="返回"
        okButtonProps={{ danger: true, 'aria-label': '确认取消运行' }}
        confirmLoading={cancelBusy}
        onOk={() => void handleCancel()}
        onCancel={() => {
          if (!cancelBusy) {
            setCancelTarget(null);
          }
        }}
      >
        <Typography.Paragraph>
          取消后后续节点不再执行，已完成节点与结果保留；若运行正在等待审批，待审批项会同时取消。
          只有发起人或租户管理员可以取消。
        </Typography.Paragraph>
        {cancelTarget ? (
          <Descriptions
            column={1}
            size="small"
            items={[
              {
                key: 'workflow',
                label: '流程',
                children: cancelTarget.workflowName || '未命名流程',
              },
              { key: 'version', label: '版本', children: `v${cancelTarget.versionNo}` },
              {
                key: 'run',
                label: '运行 ID',
                children: (
                  <Typography.Text code>{formatShortId(cancelTarget.id, 12)}</Typography.Text>
                ),
              },
            ]}
          />
        ) : null}
        {cancelError ? <Alert type="error" showIcon title={cancelError} /> : null}
      </Modal>

      <Modal
        title="重试失败节点"
        open={retryTarget != null}
        okText="提交重试"
        cancelText="返回"
        okButtonProps={{
          'aria-label': '确认重试失败节点',
          disabled: retryPhase !== 'ready' || !retryNodeId,
        }}
        confirmLoading={retryBusy}
        onOk={() => void handleRetry()}
        onCancel={() => {
          if (!retryBusy) {
            setRetryTarget(null);
          }
        }}
      >
        <Typography.Paragraph>
          只重试所选失败节点；上游已成功节点不会重跑。写工具重试前必须重新审批，系统不会自动无限重试。
        </Typography.Paragraph>
        {retryPhase === 'loading' ? (
          <div aria-busy="true" aria-label="正在读取失败节点">
            <Skeleton active paragraph={{ rows: 3 }} />
          </div>
        ) : null}
        {retryPhase === 'ready' ? (
          <Radio.Group
            aria-label="选择失败节点"
            value={retryNodeId}
            onChange={(event) => setRetryNodeId(String(event.target.value))}
          >
            <Space direction="vertical" size={8} className="workflow-history-retry-list">
              {retryNodes.map((node) => (
                <Radio key={`${node.nodeId}-${node.attempt}`} value={node.nodeId}>
                  <span>
                    {node.name}（{node.nodeId} · 第 {node.attempt} 次尝试）
                    {node.errorMessage ? `：${node.errorMessage}` : ''}
                  </span>
                </Radio>
              ))}
            </Space>
          </Radio.Group>
        ) : null}
        {retryError ? <Alert type="error" showIcon title={retryError} /> : null}
      </Modal>
    </Card>
  );
}
