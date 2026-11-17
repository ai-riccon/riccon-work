import { useCallback, useEffect, useRef, useState } from 'react';
import type { RicconApi } from '@/services/api/client';
import { ApiError, describeApiError } from '@/services/api/errors';
import type { McpTool, ResearchTask, SessionInfo, Workspace } from '@/services/api/types';

/** 报告任务列表页的加载阶段。 */
export type ResearchWorkspacePhase = 'loading' | 'ready' | 'empty' | 'forbidden' | 'error';

/** J2 报告任务列表控制器。 */
export interface ResearchWorkspaceController {
  phase: ResearchWorkspacePhase;
  error: string | null;
  workspace: Workspace | null;
  tasks: ResearchTask[];
  tasksLoading: boolean;
  tools: McpTool[];
  toolsLoading: boolean;
  toolsError: string | null;
  creating: boolean;
  actionError: string | null;
  initialize: () => Promise<void>;
  refreshTasks: () => Promise<void>;
  createTask: (
    topic: string,
    mcpToolId: string,
    idempotencyKey: string,
  ) => Promise<ResearchTask | null>;
  dismissActionError: () => void;
}

/**
 * 加载报告任务列表页所需的数据：工作空间、任务列表与已授权 MCP 工具。
 *
 * 与 J1 的 useJ1Workspace 分离：J2 不涉及会话、上传或 SSE，
 * 避免为了复用而改动 J1 的既有行为。列表为空是正常空态，不是错误。
 *
 * @param api API 客户端
 * @param session 当前会话；null 时不加载
 * @returns 状态与动作
 */
export function useResearchWorkspace(
  api: RicconApi,
  session: SessionInfo | null,
): ResearchWorkspaceController {
  const [phase, setPhase] = useState<ResearchWorkspacePhase>('loading');
  const [error, setError] = useState<string | null>(null);
  const [workspace, setWorkspace] = useState<Workspace | null>(null);
  const [tasks, setTasks] = useState<ResearchTask[]>([]);
  const [tasksLoading, setTasksLoading] = useState(false);
  const [tools, setTools] = useState<McpTool[]>([]);
  const [toolsLoading, setToolsLoading] = useState(false);
  const [toolsError, setToolsError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const workspaceRef = useRef<Workspace | null>(null);
  const initializedForRef = useRef<string | null>(null);

  /**
   * 加载工作空间、任务列表与授权工具。
   *
   * 工具列表失败不阻塞任务列表：页面仍可查看历史任务，但会禁用创建并提示原因。
   */
  const initialize = useCallback(async (): Promise<void> => {
    setPhase('loading');
    setError(null);
    setTasks([]);
    setTools([]);
    setToolsError(null);
    workspaceRef.current = null;
    setWorkspace(null);
    try {
      const page = await api.listWorkspaces();
      const nextWorkspace = page.items.find((item) => item.status === 'ACTIVE') ?? null;
      if (!nextWorkspace) {
        setPhase('empty');
        return;
      }
      workspaceRef.current = nextWorkspace;
      setWorkspace(nextWorkspace);

      setTasksLoading(true);
      setToolsLoading(true);
      const [taskResult, toolResult] = await Promise.allSettled([
        api.listResearchTasks(nextWorkspace.id),
        api.listGrantedMcpTools(),
      ]);

      if (taskResult.status === 'rejected') {
        const reason = taskResult.reason;
        if (reason instanceof ApiError && reason.status === 403) {
          setPhase('forbidden');
          setError(describeApiError(reason, '没有查看报告任务的权限。'));
        } else {
          setPhase('error');
          setError(describeApiError(reason, '报告任务加载失败，请重试。'));
        }
        return;
      }
      setTasks(taskResult.value.items);

      if (toolResult.status === 'rejected') {
        setToolsError(describeApiError(toolResult.reason, '授权工具加载失败，请重试。'));
      } else {
        setTools(toolResult.value.items.filter((tool) => tool.status === 'ACTIVE'));
      }
      setPhase('ready');
    } catch (loadError) {
      if (loadError instanceof ApiError && loadError.status === 403) {
        setPhase('forbidden');
        setError(describeApiError(loadError, '没有访问工作空间的权限。'));
      } else {
        setPhase('error');
        setError(describeApiError(loadError, '工作空间加载失败，请重试。'));
      }
    } finally {
      setTasksLoading(false);
      setToolsLoading(false);
    }
  }, [api]);

  // 登录态确定后只初始化一次，避免每次渲染重复请求。
  useEffect(() => {
    if (!session) {
      return;
    }
    const key = `${session.tenant.id}:${session.user.id}`;
    if (initializedForRef.current === key) {
      return;
    }
    initializedForRef.current = key;
    void initialize();
  }, [initialize, session]);

  /** 刷新任务列表；用于重试或从详情返回后同步状态。 */
  const refreshTasks = useCallback(async (): Promise<void> => {
    const current = workspaceRef.current;
    if (!current) {
      return;
    }
    setTasksLoading(true);
    try {
      const page = await api.listResearchTasks(current.id);
      setTasks(page.items);
    } catch (refreshError) {
      setActionError(describeApiError(refreshError, '刷新报告任务失败，请重试。'));
    } finally {
      setTasksLoading(false);
    }
  }, [api]);

  /**
   * 创建报告任务。
   *
   * 幂等键由页面持有并在失败重试时复用，避免响应丢失后产生重复任务。
   */
  const createTask = useCallback(
    async (
      topic: string,
      mcpToolId: string,
      idempotencyKey: string,
    ): Promise<ResearchTask | null> => {
      const current = workspaceRef.current;
      if (!current) {
        setActionError('尚未加载工作空间，请刷新页面后重试。');
        return null;
      }
      setCreating(true);
      setActionError(null);
      try {
        const task = await api.createResearchTask(current.id, { topic, mcpToolId }, idempotencyKey);
        setTasks((previous) => [task, ...previous.filter((item) => item.id !== task.id)]);
        return task;
      } catch (createError) {
        setActionError(describeApiError(createError, '创建报告任务失败，请重试。'));
        return null;
      } finally {
        setCreating(false);
      }
    },
    [api],
  );

  return {
    phase,
    error,
    workspace,
    tasks,
    tasksLoading,
    tools,
    toolsLoading,
    toolsError,
    creating,
    actionError,
    initialize,
    refreshTasks,
    createTask,
    dismissActionError: () => setActionError(null),
  };
}
