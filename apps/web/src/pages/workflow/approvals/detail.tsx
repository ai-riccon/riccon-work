import { useNavigate, useParams } from '@umijs/max';
import WorkflowApprovalsPage, { type WorkflowApprovalsPageProps } from './index';

/**
 * 审批详情深链路由的可注入属性。
 *
 * 除审批 ID 外与页面属性一致：路由本身不接收 Umi 之外的 props，测试可通过注入
 * API、导航与时钟避免启动真实路由和网络。
 */
export interface WorkflowApprovalDetailRouteProps extends Omit<
  WorkflowApprovalsPageProps,
  'initialApprovalId'
> {
  /** 路由参数值；省略时读取 Umi 的 `workflowApprovalId`，测试可注入固定审批 ID。 */
  workflowApprovalId?: string;
}

/**
 * `/workflows/approvals/:workflowApprovalId` 的路由入口。
 *
 * 审批页面是“待办 ↔ 详情”的主从视图：待办路由不改变 URL，详情深链则由本组件把动态段
 * 转成 `initialApprovalId`，让通知链接、刷新与直接访问都定位到同一条审批。退出详情时
 * 导航回待办路由，避免 URL 停留在已关闭的详情上。
 *
 * @param props 审批 ID、API、会话读取、导航与时钟注入
 * @returns 带详情深链的审批页面
 */
export default function WorkflowApprovalDetailRoute({
  workflowApprovalId,
  navigate: injectedNavigate,
  ...pageProps
}: WorkflowApprovalDetailRouteProps) {
  const params = useParams();
  const umiNavigate = useNavigate();
  const navigate = injectedNavigate ?? umiNavigate;
  // 优先使用注入值，保证组件测试不依赖 Umi 路由上下文；生产环境从动态段读取。
  const approvalId = workflowApprovalId ?? params.workflowApprovalId ?? '';

  return (
    <WorkflowApprovalsPage
      {...pageProps}
      initialApprovalId={approvalId}
      navigate={navigate}
      onLeaveDetail={() => navigate('/workflows/approvals')}
    />
  );
}
