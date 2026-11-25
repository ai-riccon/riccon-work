import { useNavigate, useParams } from '@umijs/max';
import WorkflowRunDetailPage, { type WorkflowRunDetailPageProps } from './index';

/**
 * 运行详情深链路由的可注入属性。
 *
 * 除运行 ID 外与页面属性一致：路由本身不接收 Umi 之外的 props，测试可通过注入 API、
 * 会话读取与导航避免启动真实路由和网络。
 */
export interface WorkflowRunDetailRouteProps extends Omit<WorkflowRunDetailPageProps, 'runId'> {
  /** 路由参数值；省略时读取 Umi 的 `workflowRunId`，测试可注入固定运行 ID。 */
  workflowRunId?: string;
}

/**
 * `/workflows/runs/:workflowRunId` 的路由入口。
 *
 * 动态段交给页面 `runId`，使运行历史链接、刷新与直接访问都能定位到同一条运行。
 * 路由注册在 `apps/web/config/routes.ts`（T12c 接入），与动态段同名 `workflowRunId`；
 * 页面本身仍按默认导出可挂载组件提供，便于测试与将来复用。
 *
 * @param props 运行 ID、API、会话读取、导航与轮询间隔注入
 * @returns 带深链参数解析的运行详情页面
 */
export default function WorkflowRunDetailRoute({
  workflowRunId,
  navigate: injectedNavigate,
  ...pageProps
}: WorkflowRunDetailRouteProps) {
  const params = useParams();
  const umiNavigate = useNavigate();
  const navigate = injectedNavigate ?? umiNavigate;
  // 优先使用注入值，保证组件测试不依赖 Umi 路由上下文；生产环境从动态段读取。
  const runId = workflowRunId ?? params.workflowRunId ?? '';

  return <WorkflowRunDetailPage {...pageProps} runId={runId} navigate={navigate} />;
}
