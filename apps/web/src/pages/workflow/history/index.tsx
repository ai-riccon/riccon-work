import { useNavigate } from '@umijs/max';
import { Button, Result, Skeleton, Typography } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { api as defaultApi } from '@/services/api';
import { ApiError, describeApiError } from '@/services/api/errors';
import { authStore, useAuth } from '@/services/auth/auth-store';
import type { SessionReader } from '@/services/auth/auth-store';
import { hasWorkflowPermission, WORKFLOW_PERMISSIONS } from '../list/format';
import type { WorkflowDefinition } from '../list/workflow-api';
import { ReleasePanel } from './release-panel';
import { RunHistoryPanel } from './run-history';
import { workflowHistoryApi, type WorkflowHistoryApi } from './history-api';
import { isTenantAdmin, isWorkflowUnavailable, toWorkflowOptions } from './format';
import './history.css';

/** 流程候选加载阶段。 */
type OptionsPhase = 'idle' | 'loading' | 'ready' | 'error';

/** 页面可注入依赖，便于组件测试。 */
export interface WorkflowHistoryPageProps {
  /** 运行历史/发布 API；默认同源真实客户端。 */
  api?: WorkflowHistoryApi;
  /** 会话读取能力；默认复用员工端 ApiClient。 */
  sessionReader?: SessionReader;
  /** 导航；测试注入。 */
  navigate?: (to: string) => void;
  /**
   * 初始流程（深链）。
   *
   * T12c 路由接入后可来自查询参数；当前组件允许直接注入，页面本身不依赖 Umi 路由上下文。
   */
  initialWorkflowId?: string;
}

/**
 * M06 运行历史与发布页。
 *
 * 页面由两块组成：
 * 1. 发布与版本：选择流程后查看版本、发布当前草稿、停用/启用、用指定版本创建运行；
 * 2. 运行历史：按流程/版本/状态/时间/发起人过滤，倒序分页，取消运行与重试失败节点。
 *
 * 可见性完全由服务端按租户/范围/发起人过滤；页面不展示“不可见对象的名称或数量”，
 * 404 统一表示“不存在或不可见”。入口要求 `workflow.read`；发布/运行/取消/重试的按钮
 * 仍按各自权限显隐，并保留服务端 403/404/409/503 的中文兜底。
 *
 * @param props API、会话读取、导航与深链流程注入
 * @returns 运行历史与发布界面
 */
export default function WorkflowHistoryPage({
  api = workflowHistoryApi,
  sessionReader = defaultApi,
  navigate: injectedNavigate,
  initialWorkflowId,
}: WorkflowHistoryPageProps) {
  const umiNavigate = useNavigate();
  const navigate = injectedNavigate ?? umiNavigate;
  const auth = useAuth();
  const session = auth.session;
  const canRead = hasWorkflowPermission(session?.permissions, WORKFLOW_PERMISSIONS.read);
  const tenantAdmin = isTenantAdmin(session);

  const [workflowOptions, setWorkflowOptions] = useState<WorkflowDefinition[]>([]);
  const [optionsPhase, setOptionsPhase] = useState<OptionsPhase>('idle');
  const [optionsError, setOptionsError] = useState<string | null>(null);
  const optionsSeq = useRef(0);

  // 页面可能被独立挂载（测试或深链）；未恢复会话时沿用员工端统一恢复逻辑。
  useEffect(() => {
    if (auth.status === 'unknown') {
      void authStore.restore(sessionReader);
    }
  }, [auth.status, sessionReader]);

  /**
   * 加载流程下拉候选。
   *
   * 租户管理员使用 `ALL` 视图；其他用户合并 `MINE` 与 `RUNNABLE`，避免流程筛选中出现
   * 自己无权查看的流程。任一视图失败但另一视图成功时仍可用；全部失败才展示错误。
   * 候选只用于下拉，不决定运行可见性——历史接口仍按主体可见范围过滤。
   */
  const loadWorkflowOptions = useCallback(async (): Promise<void> => {
    if (!session || !canRead) {
      return;
    }
    const seq = optionsSeq.current + 1;
    optionsSeq.current = seq;
    setOptionsPhase('loading');
    setOptionsError(null);
    try {
      if (tenantAdmin) {
        const page = await api.listWorkflows({ view: 'ALL', page: 1, size: 100 });
        if (optionsSeq.current !== seq) {
          return;
        }
        setWorkflowOptions(toWorkflowOptions(page.items));
        setOptionsPhase('ready');
        return;
      }
      const results = await Promise.allSettled([
        api.listWorkflows({ view: 'MINE', page: 1, size: 100 }),
        api.listWorkflows({ view: 'RUNNABLE', page: 1, size: 100 }),
      ]);
      if (optionsSeq.current !== seq) {
        return;
      }
      const fulfilled = results.filter(
        (
          result,
        ): result is PromiseFulfilledResult<
          Awaited<ReturnType<WorkflowHistoryApi['listWorkflows']>>
        > => result.status === 'fulfilled',
      );
      if (fulfilled.length === 0) {
        const first = results[0];
        throw first.status === 'rejected' ? first.reason : new Error('workflow options failed');
      }
      setWorkflowOptions(toWorkflowOptions(fulfilled.flatMap((result) => result.value.items)));
      setOptionsPhase('ready');
    } catch (caught) {
      if (optionsSeq.current !== seq) {
        return;
      }
      setWorkflowOptions([]);
      setOptionsError(
        isWorkflowUnavailable(caught)
          ? '编排功能已关闭，暂时不能加载流程目录；运行历史仍会按可见范围加载。'
          : caught instanceof ApiError && caught.status === 403
            ? '没有流程读取权限，无法加载流程筛选候选。'
            : describeApiError(caught, '流程目录加载失败，请稍后重试。'),
      );
      setOptionsPhase('error');
    }
  }, [api, canRead, session, tenantAdmin]);

  useEffect(() => {
    if (!session || !canRead) {
      return;
    }
    void loadWorkflowOptions();
  }, [canRead, loadWorkflowOptions, session]);

  /** 返回编排 Studio 列表；错误态与返回链接的下一步。 */
  const goToList = (): void => {
    navigate('/workflows/list');
  };

  /** 打开 T12a 运行详情。 */
  const openRunDetail = (runId: string): void => {
    navigate(`/workflows/runs/${encodeURIComponent(runId)}`);
  };

  /** 打开流程编辑器。 */
  const openEditor = (workflowId: string): void => {
    navigate(`/workflows/${encodeURIComponent(workflowId)}/edit`);
  };

  if (auth.status === 'unknown' || auth.status === 'checking') {
    return (
      <div className="workflow-history-page" data-testid="workflow-history-page">
        <div aria-busy="true" aria-label="正在检查登录状态">
          <Skeleton active paragraph={{ rows: 8 }} />
        </div>
      </div>
    );
  }

  if (auth.status === 'anonymous' || auth.status === 'expired') {
    return (
      <div className="workflow-history-page" data-testid="workflow-history-page">
        <Result
          status="warning"
          title="登录状态已失效"
          subTitle="请重新登录后再查看运行历史与发布版本。"
        />
      </div>
    );
  }

  if (auth.status === 'error') {
    return (
      <div className="workflow-history-page" data-testid="workflow-history-page">
        <Result
          status="error"
          title="无法确认登录状态"
          subTitle={auth.error ?? '无法确认登录状态，请检查网络后重试。'}
          extra={[
            <Button
              key="retry"
              type="primary"
              aria-label="重试"
              autoInsertSpace={false}
              onClick={() => void authStore.restore(sessionReader)}
            >
              重试
            </Button>,
          ]}
        />
      </div>
    );
  }

  if (!session || !canRead) {
    return (
      <div className="workflow-history-page" data-testid="workflow-history-page">
        <Result
          status="403"
          title="没有访问权限"
          subTitle="运行历史需要 workflow.read 权限，请联系租户管理员申请。"
          extra={[
            <Button key="back" type="primary" onClick={goToList}>
              返回编排 Studio
            </Button>,
          ]}
        />
      </div>
    );
  }

  return (
    <div className="workflow-history-page" data-testid="workflow-history-page">
      <header className="workflow-history-heading">
        <Typography.Title level={1} className="workflow-history-title">
          运行历史
        </Typography.Title>
        <Typography.Paragraph type="secondary" className="workflow-history-intro">
          查看你可访问的运行记录，发布流程版本，并按版本范围运行、取消或重试失败节点。
          列表只展示服务端按可见范围过滤后的元数据，不展示结果正文或输入原文。
        </Typography.Paragraph>
      </header>

      <ReleasePanel
        api={api}
        session={session}
        workflowOptions={workflowOptions}
        workflowOptionsLoading={optionsPhase === 'loading'}
        workflowOptionsError={optionsError}
        onReloadOptions={() => void loadWorkflowOptions()}
        initialWorkflowId={initialWorkflowId}
        onEditWorkflow={openEditor}
        onOpenDetail={openRunDetail}
        onBackToStudio={goToList}
        onWorkflowUpdated={() => void loadWorkflowOptions()}
      />

      <RunHistoryPanel
        api={api}
        session={session}
        workflowOptions={workflowOptions}
        workflowOptionsLoading={optionsPhase === 'loading'}
        workflowOptionsError={optionsError}
        initialWorkflowId={initialWorkflowId}
        onOpenDetail={openRunDetail}
        onBackToStudio={goToList}
      />
    </div>
  );
}
