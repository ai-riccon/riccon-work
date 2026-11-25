import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons';
import { useNavigate } from '@umijs/max';
import { Alert, Button, Result, Skeleton, Space, Typography } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { api as defaultApi } from '@/services/api';
import { ApiError, describeApiError } from '@/services/api/errors';
import { authStore, useAuth } from '@/services/auth/auth-store';
import type { SessionReader } from '@/services/auth/auth-store';
import type { KnowledgeApi } from '@/services/knowledge';
import { isWorkflowUnavailable } from '../approvals/format';
import { hasWorkflowPermission, WORKFLOW_PERMISSIONS } from '../list/format';
import { AUTO_REFRESH_NOTE, isActiveRunStatus } from './format';
import { workflowRunApi, type WorkflowRunApi, type WorkflowRunDetail } from './run-api';
import { WorkflowRunDetailView } from './run-detail';
import './run.css';

/** 运行详情页面阶段。 */
type DetailPhase =
  'loading' | 'ready' | 'error' | 'forbidden' | 'notFound' | 'disabled' | 'missing';

/** 默认轮询间隔；ADR-0024 §7.4 约定 3 秒，且只在 updatedAt 变化时重绘。 */
const DEFAULT_POLL_INTERVAL_MS = 3000;

/** 页面可注入依赖，便于组件测试。 */
export interface WorkflowRunDetailPageProps {
  /** 运行详情 API；默认同源真实客户端。 */
  api?: WorkflowRunApi;
  /** 知识库下载能力；引用详情抽屉复用。测试可注入。 */
  knowledgeApi?: Pick<KnowledgeApi, 'downloadDocument'>;
  /** 会话读取能力；默认复用员工端 ApiClient。 */
  sessionReader?: SessionReader;
  /** 导航；测试注入。 */
  navigate?: (to: string) => void;
  /**
   * 运行 ID。
   *
   * 路由组件从 `/workflows/runs/:workflowRunId` 读取后传入；组件测试直接注入，
   * 页面本身不依赖 Umi 路由上下文。
   */
  runId?: string;
  /** 轮询间隔；测试可注入短间隔或 60000 关闭轮询。 */
  pollIntervalMs?: number;
}

/**
 * 把加载异常归约为页面阶段。
 *
 * 403/404/503 使用固定中文结果页，不展示服务端原始 detail；其余错误交给调用方
 * 生成通用重试文案。403 表示缺少 `workflow.read`，404 统一表示“不可见即不存在”。
 *
 * @param error 任意异常
 * @returns 页面阶段或 null（需要通用错误态）
 */
function phaseForError(error: unknown): DetailPhase | null {
  if (error instanceof ApiError && error.status === 403) {
    return 'forbidden';
  }
  if (error instanceof ApiError && error.status === 404) {
    return 'notFound';
  }
  if (isWorkflowUnavailable(error)) {
    return 'disabled';
  }
  return null;
}

/**
 * M06 运行详情页。
 *
 * 覆盖 M06-AC-04/13/19：节点时间线（含全部重试尝试、状态文字+图标、当前节点高亮、输入/输出摘要）、
 * 聚合引用（按 `sourceStatus` 遮蔽）、审批记录、最终结果、失败原因与重试入口；加载/空/错误/403/404/503
 * 各有中文说明与下一步。
 *
 * 轮询：运行未到终态时每 3 秒读取详情，只有 `updatedAt` 变化才替换页面数据；终态后停止轮询，
 * 最终结果只在终态读取一次（ADR-0024 §7.4）。轮询失败不清空已展示的数据，只提示并继续重试；
 * 时权限撤销或对象不可见则切换到 403/404，避免继续展示旧内容。
 *
 * 权限：入口要求 `workflow.read`；前端守卫不代替服务端判定，详情仍可能因非发起人/非管理员、
 * 到期或跨租户返回 404。
 *
 * @param props API、会话、导航、运行 ID 与轮询间隔
 * @returns 运行详情页面
 */
export default function WorkflowRunDetailPage({
  api = workflowRunApi,
  knowledgeApi,
  sessionReader = defaultApi,
  navigate: injectedNavigate,
  runId = '',
  pollIntervalMs = DEFAULT_POLL_INTERVAL_MS,
}: WorkflowRunDetailPageProps) {
  const umiNavigate = useNavigate();
  const navigate = injectedNavigate ?? umiNavigate;
  const auth = useAuth();
  const session = auth.session;
  const canRead = hasWorkflowPermission(session?.permissions, WORKFLOW_PERMISSIONS.read);

  const [phase, setPhase] = useState<DetailPhase>('loading');
  const [detail, setDetail] = useState<WorkflowRunDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [refreshError, setRefreshError] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const detailRef = useRef<WorkflowRunDetail | null>(null);
  const requestSeq = useRef(0);

  // 页面可能被独立挂载（测试或深链）；未恢复会话时沿用员工端统一恢复逻辑。
  useEffect(() => {
    if (auth.status === 'unknown') {
      void authStore.restore(sessionReader);
    }
  }, [auth.status, sessionReader]);

  /**
   * 读取运行详情。
   *
   * `background` 为 true 时用于手动刷新：保留已展示内容，不显示整页骨架；轮询使用独立路径，
   * 以免刷新按钮与轮询互相覆盖。所有请求用递增序号丢弃过期响应。
   *
   * @param background 是否后台刷新
   */
  const load = useCallback(
    async (background = false): Promise<void> => {
      if (!runId) {
        setPhase('missing');
        return;
      }
      if (!session) {
        return;
      }
      if (!canRead) {
        setPhase('forbidden');
        return;
      }
      const seq = requestSeq.current + 1;
      requestSeq.current = seq;
      if (!background) {
        setPhase('loading');
        setError(null);
      }
      setRefreshError(null);
      if (background) {
        setRefreshing(true);
      }
      try {
        const next = await api.getRunDetail(runId);
        if (requestSeq.current !== seq) {
          return;
        }
        detailRef.current = next;
        setDetail(next);
        setPhase('ready');
      } catch (caught) {
        if (requestSeq.current !== seq) {
          return;
        }
        const mapped = phaseForError(caught);
        if (mapped) {
          setDetail(null);
          detailRef.current = null;
          setPhase(mapped);
          return;
        }
        if (background && detailRef.current) {
          setRefreshError(describeApiError(caught, '状态刷新失败，请稍后重试。'));
          return;
        }
        setError(describeApiError(caught, '运行详情加载失败，请稍后重试。'));
        setPhase('error');
      } finally {
        if (requestSeq.current === seq) {
          setRefreshing(false);
        }
      }
    },
    [api, canRead, runId, session],
  );

  // 会话恢复或权限变化后重新加载；runId 变化时重置旧数据，避免串到另一条运行。
  useEffect(() => {
    detailRef.current = null;
    setDetail(null);
    setPhase('loading');
    if (!session) {
      return;
    }
    void load(false);
  }, [load, session]);

  /**
   * 轮询运行状态。
   *
   * 依赖只包含“是否活跃”的布尔值，避免每次 `updatedAt` 变化重启轮询循环；循环内部用
   * `detailRef` 比较 `updatedAt`，只有变化才 `setDetail`。终态使 `pollingActive` 变为
   * false，effect 清理会取消未触发的定时器，确保终态后不再请求。
   *
   * 轮询遇到 403/404 时移除旧详情并切换结果页，防止继续展示已失去访问权的内容。
   */
  const pollingActive = phase === 'ready' && detail !== null && isActiveRunStatus(detail.status);

  useEffect(() => {
    if (!pollingActive) {
      return;
    }
    let cancelled = false;
    let timer = 0;

    /**
     * 安排下一次轮询。
     *
     * 手动刷新会递增请求序号；若轮询响应因此过期，仍要安排下一次，避免轮询循环被
     * 一次并发刷新意外终止。终态/卸载由外层 effect 清理或 cancelled 标记停止。
     */
    const scheduleNext = (): void => {
      if (!cancelled) {
        timer = window.setTimeout(() => {
          void poll();
        }, pollIntervalMs);
      }
    };

    const poll = async (): Promise<void> => {
      if (cancelled) {
        return;
      }
      const seq = requestSeq.current + 1;
      requestSeq.current = seq;
      try {
        const next = await api.getRunDetail(runId);
        if (cancelled) {
          return;
        }
        if (requestSeq.current !== seq) {
          // 并发手动刷新已经接管本次读取；保持轮询节奏。
          scheduleNext();
          return;
        }
        setRefreshError(null);
        const previous = detailRef.current;
        if (!previous || next.updatedAt !== previous.updatedAt) {
          detailRef.current = next;
          setDetail(next);
        }
        if (!isActiveRunStatus(next.status)) {
          return;
        }
      } catch (caught) {
        if (cancelled) {
          return;
        }
        if (requestSeq.current !== seq) {
          scheduleNext();
          return;
        }
        const mapped = phaseForError(caught);
        if (mapped) {
          setDetail(null);
          detailRef.current = null;
          setPhase(mapped);
          return;
        }
        setRefreshError(describeApiError(caught, '状态刷新失败，页面显示上次成功读取的状态。'));
      }
      scheduleNext();
    };

    timer = window.setTimeout(() => {
      void poll();
    }, pollIntervalMs);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [api, pollingActive, pollIntervalMs, runId]);

  /**
   * 手动刷新。
   *
   * 与轮询共用请求序号；刷新期间保留页面内容，按钮显示 loading。
   */
  const handleRefresh = (): void => {
    void load(true);
  };

  /** 返回流程列表；错误态与无权限态的下一步。 */
  const goBack = (): void => {
    navigate('/workflows/list');
  };

  /** 通用工具栏；错误页只保留返回入口。 */
  const toolbar = (
    <Space wrap className="workflow-run-toolbar">
      <Button icon={<ArrowLeftOutlined aria-hidden="true" />} onClick={goBack}>
        返回流程列表
      </Button>
      {phase === 'ready' ? (
        <Button
          icon={<ReloadOutlined aria-hidden="true" />}
          loading={refreshing}
          onClick={handleRefresh}
          aria-label="刷新运行详情"
        >
          刷新
        </Button>
      ) : null}
    </Space>
  );

  if (phase === 'loading') {
    return (
      <div className="workflow-run-page" data-testid="workflow-run-page">
        {toolbar}
        <div aria-busy="true" aria-label="正在加载运行详情">
          <Skeleton active paragraph={{ rows: 10 }} />
        </div>
      </div>
    );
  }

  if (phase !== 'ready' || !detail) {
    const result =
      phase === 'missing' ? (
        <Result
          status="404"
          title="缺少运行 ID"
          subTitle="请从运行历史打开运行详情。"
          extra={[
            <Button key="back" onClick={goBack}>
              返回流程列表
            </Button>,
          ]}
        />
      ) : phase === 'forbidden' ? (
        <Result
          status="403"
          title="没有访问权限"
          subTitle="你没有查看运行详情的权限，请联系租户管理员申请 workflow.read 权限。"
          extra={[
            <Button key="back" onClick={goBack}>
              返回流程列表
            </Button>,
          ]}
        />
      ) : phase === 'notFound' ? (
        <Result
          status="404"
          title="运行不存在或你无权查看"
          subTitle="运行可能已到期、被清理，或不在你的可见范围。为保护数据，页面不提示对象是否存在。"
          extra={[
            <Button key="back" onClick={goBack}>
              返回流程列表
            </Button>,
          ]}
        />
      ) : phase === 'disabled' ? (
        <Result
          status="warning"
          title="编排功能已关闭"
          subTitle="已有运行记录仍可读取；新运行、重试与审批决定会暂时不可用。请联系运维人员开启编排功能。"
          extra={[
            <Button key="retry" aria-label="重试" onClick={() => void load(false)}>
              重试
            </Button>,
            <Button key="back" onClick={goBack}>
              返回流程列表
            </Button>,
          ]}
        />
      ) : (
        <Result
          status="error"
          title="运行详情加载失败"
          subTitle={error ?? '请稍后重试。'}
          extra={[
            <Button key="retry" type="primary" aria-label="重试" onClick={() => void load(false)}>
              重试
            </Button>,
            <Button key="back" onClick={goBack}>
              返回流程列表
            </Button>,
          ]}
        />
      );
    return (
      <div className="workflow-run-page" data-testid="workflow-run-page">
        {toolbar}
        {result}
      </div>
    );
  }

  return (
    <div className="workflow-run-page" data-testid="workflow-run-page">
      {toolbar}
      <Typography.Text type="secondary" className="workflow-run-refresh-note">
        {AUTO_REFRESH_NOTE}
      </Typography.Text>
      {refreshError ? (
        <Alert
          className="workflow-run-refresh-error"
          type="warning"
          showIcon
          title="状态刷新失败"
          description={`${refreshError} 页面显示上次成功读取的状态，稍后会自动重试。`}
          action={
            <Button size="small" onClick={handleRefresh} loading={refreshing}>
              立即重试
            </Button>
          }
        />
      ) : null}
      <WorkflowRunDetailView detail={detail} knowledgeApi={knowledgeApi} />
    </div>
  );
}
