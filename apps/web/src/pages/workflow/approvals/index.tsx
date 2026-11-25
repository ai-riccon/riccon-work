import { useNavigate } from '@umijs/max';
import { App, Button, Result, Skeleton } from 'antd';
import { useEffect, useState } from 'react';
import { api as defaultApi } from '@/services/api';
import { authStore, useAuth } from '@/services/auth/auth-store';
import type { SessionReader } from '@/services/auth/auth-store';
import { hasWorkflowPermission, WORKFLOW_PERMISSIONS } from '../list/format';
import {
  workflowApprovalApi,
  type WorkflowApprovalApi,
  type WorkflowApprovalDecision,
} from './approval-api';
import { describeDecisionSuccess } from './format';
import { ApprovalDetail } from './workflow-approvals-detail';
import { ApprovalInbox } from './workflow-approvals-inbox';
import './approvals.css';

/** 页面可注入依赖，便于组件测试。 */
export interface WorkflowApprovalsPageProps {
  /** 审批 API；默认同源真实客户端。 */
  api?: WorkflowApprovalApi;
  /** 会话读取能力；默认复用员工端 ApiClient。 */
  sessionReader?: SessionReader;
  /** 导航；测试注入。 */
  navigate?: (to: string) => void;
  /**
   * 深链打开某个审批详情。
   *
   * 路由接入任务（T13b）在 `/workflows/approvals/:workflowApprovalId` 读取 `useParams` 后传入；
   * 页面本身不依赖 Umi 路由，组件测试可直接注入。
   */
  initialApprovalId?: string;
  /** 当前时间；测试注入固定时钟，用于等待时长。 */
  now?: () => Date;
}

/**
 * M06 审批待办/详情页面。
 *
 * 页面是“待办列表 ↔ 审批详情”的主从视图：打开详情不改变 URL，返回时重新聚焦待办标题并
 * 重新读取待办；决定成功后回到待办并强制刷新，避免把已决定的审批继续显示为待处理。
 * 动态详情路由由后续路由任务接入，本页面只接收审批 ID。
 *
 * 权限：入口要求 `workflow.approve`；前端隐藏入口不代替服务端 assignee/自批校验，详情 404/403
 * 与决定 403 仍按服务端结果展示。
 *
 * @param props API、会话、导航与初始审批 ID
 * @returns 审批页面
 */
export default function WorkflowApprovalsPage({
  api = workflowApprovalApi,
  sessionReader = defaultApi,
  navigate: injectedNavigate,
  initialApprovalId,
  now,
}: WorkflowApprovalsPageProps) {
  const umiNavigate = useNavigate();
  const navigate = injectedNavigate ?? umiNavigate;
  const { message } = App.useApp();
  const auth = useAuth();
  const session = auth.session;
  const canApprove = hasWorkflowPermission(session?.permissions, WORKFLOW_PERMISSIONS.approve);

  const [selectedId, setSelectedId] = useState<string | null>(initialApprovalId ?? null);
  const [refreshToken, setRefreshToken] = useState(0);
  const [focusToken, setFocusToken] = useState(0);

  // 页面可能被独立挂载（测试或深链）；未恢复会话时沿用员工端统一恢复逻辑。
  useEffect(() => {
    if (auth.status === 'unknown') {
      void authStore.restore(sessionReader);
    }
  }, [auth.status, sessionReader]);

  // 路由参数变化（如从待办直接深链到另一个审批）时同步选中项。
  useEffect(() => {
    if (initialApprovalId) {
      setSelectedId(initialApprovalId);
    }
  }, [initialApprovalId]);

  /** 返回编排 Studio；错误态与无权限态的下一步。 */
  const goToStudio = (): void => {
    navigate('/workflows/list');
  };

  /** 从详情返回待办，并把焦点交还待办标题。 */
  const handleBack = (): void => {
    setSelectedId(null);
    setFocusToken((token) => token + 1);
  };

  /**
   * 决定成功后回到待办。
   *
   * 先提示结果再切回列表，列表通过刷新令牌重新读取；不在前端改写状态，避免与服务端不一致。
   *
   * @param decision 已提交的决定
   */
  const handleDecided = (decision: WorkflowApprovalDecision): void => {
    message.success(describeDecisionSuccess(decision));
    setSelectedId(null);
    setRefreshToken((token) => token + 1);
    setFocusToken((token) => token + 1);
  };

  if (!session) {
    return (
      <div className="workflow-approvals-page" data-testid="workflow-approvals-page">
        <div aria-busy="true" aria-label="正在检查登录状态">
          <Skeleton active paragraph={{ rows: 4 }} />
        </div>
      </div>
    );
  }

  if (!canApprove) {
    return (
      <div className="workflow-approvals-page" data-testid="workflow-approvals-page">
        <header className="workflow-approvals-heading">
          <h1 className="workflow-approvals-title">审批待办</h1>
        </header>
        <Result
          status="403"
          title="没有审批权限"
          subTitle="你没有审批权限，请联系租户管理员申请。"
          extra={[
            <Button key="back" type="primary" onClick={goToStudio}>
              返回编排 Studio
            </Button>,
          ]}
        />
      </div>
    );
  }

  return (
    <div className="workflow-approvals-page" data-testid="workflow-approvals-page">
      {selectedId ? (
        <ApprovalDetail
          api={api}
          approvalId={selectedId}
          now={now}
          onBack={handleBack}
          onDecided={handleDecided}
        />
      ) : (
        <ApprovalInbox
          api={api}
          focusToken={focusToken}
          now={now}
          refreshToken={refreshToken}
          onBackToStudio={goToStudio}
          onOpen={setSelectedId}
        />
      )}
    </div>
  );
}
