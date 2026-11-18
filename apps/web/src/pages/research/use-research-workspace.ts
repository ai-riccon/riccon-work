import { useCallback, useEffect, useRef, useState } from 'react';
import type { RicconApi } from '@/services/api/client';
import { ApiError, describeApiError } from '@/services/api/errors';
import type { McpTool, ResearchTask, SessionInfo, Workspace } from '@/services/api/types';

/** 报告任务列表页的加载阶段。 */
export type ResearchWorkspacePhase = 'loading' | 'ready' | 'empty' | 'forbidden' | 'error';

/** 授权工具加载失败类别；403 需要与普通服务错误区分展示。 */
export type ToolsErrorKind = 'forbidden' | 'error';

/** J2 报告任务列表控制器。 */
export interface ResearchWorkspaceController {
  phase: ResearchWorkspacePhase;
  error: string | null;
  workspace: Workspace | null;
  /** 当前账号可访问的启用工作空间；用于研究页切换器。 */
  workspaces: Workspace[];
  /** 切换报告任务所属空间并重新加载任务列表。 */
  selectWorkspace: (workspaceId: string) => Promise<void>;
  tasks: ResearchTask[];
  tasksLoading: boolean;
  tools: McpTool[];
  disabledTools: McpTool[];
  toolsLoading: boolean;
  toolsError: string | null;
  toolsErrorKind: ToolsErrorKind | null;
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
 * @param initialWorkspaceId 可选的初始空间 ID；来自聊天页 `workspaceId` query
 * @returns 状态与动作
 */
export function useResearchWorkspace(
  api: RicconApi,
  session: SessionInfo | null,
  initialWorkspaceId?: string,
): ResearchWorkspaceController {
  const [phase, setPhase] = useState<ResearchWorkspacePhase>('loading');
  const [error, setError] = useState<string | null>(null);
  const [workspace, setWorkspace] = useState<Workspace | null>(null);
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [tasks, setTasks] = useState<ResearchTask[]>([]);
  const [tasksLoading, setTasksLoading] = useState(false);
  const [tools, setTools] = useState<McpTool[]>([]);
  const [disabledTools, setDisabledTools] = useState<McpTool[]>([]);
  const [toolsLoading, setToolsLoading] = useState(false);
  const [toolsError, setToolsError] = useState<string | null>(null);
  const [toolsErrorKind, setToolsErrorKind] = useState<ToolsErrorKind | null>(null);
  const [creating, setCreating] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const workspaceRef = useRef<Workspace | null>(null);
  const initializedForRef = useRef<string | null>(null);
  const taskLoadTokenRef = useRef(0);

  /**
   * 加载工作空间、任务列表与授权工具。
   *
   * 工具列表失败不阻塞任务列表：页面仍可查看历史任务，但会禁用创建并提示原因。
   */
  const initialize = useCallback(async (): Promise<void> => {
    setPhase('loading');
    setError(null);
    setTasks([]);
    setWorkspaces([]);
    setTools([]);
    setDisabledTools([]);
    setToolsError(null);
    setToolsErrorKind(null);
    const previousWorkspaceId = workspaceRef.current?.id;
    workspaceRef.current = null;
    setWorkspace(null);
    try {
      const page = await api.listWorkspaces();
      const activeWorkspaces = page.items.filter((item) => item.status === 'ACTIVE');
      setWorkspaces(activeWorkspaces);
      // 重试时保留当前选择；首次加载优先使用查询参数指定的可访问空间，否则沿用第一个 ACTIVE 空间。
      const preferredId = previousWorkspaceId ?? initialWorkspaceId;
      const nextWorkspace =
        activeWorkspaces.find((item) => item.id === preferredId) ?? activeWorkspaces[0] ?? null;
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
        const reason = toolResult.reason;
        if (reason instanceof ApiError && reason.status === 403) {
          // 403 是权限问题，重试通常无效；文案引导联系管理员，而不是笼统的“加载失败”。
          setToolsErrorKind('forbidden');
          setToolsError('当前账号没有使用 MCP 数据工具的权限，请联系管理员授权后再创建任务。');
        } else {
          setToolsErrorKind('error');
          setToolsError(describeApiError(reason, '授权工具加载失败，请重试。'));
        }
      } else {
        setTools(toolResult.value.items.filter((tool) => tool.status === 'ACTIVE'));
        setDisabledTools(toolResult.value.items.filter((tool) => tool.status === 'DISABLED'));
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
  }, [api, initialWorkspaceId]);

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

  /**
   * 切换报告任务所属工作空间。
   *
   * 只接受服务端返回的可访问空间；工具列表是租户级授权，不随空间切换重新加载。
   * 切换时清空旧任务，避免不同空间的任务短暂混排。
   *
   * @param workspaceId 目标工作空间 ID
   */
  const selectWorkspace = useCallback(
    async (workspaceId: string): Promise<void> => {
      const target = workspaces.find((item) => item.id === workspaceId) ?? null;
      if (!target || target.id === workspaceRef.current?.id) {
        return;
      }
      workspaceRef.current = target;
      setWorkspace(target);
      setTasks([]);
      setActionError(null);
      setPhase('ready');
      setTasksLoading(true);
      const token = taskLoadTokenRef.current + 1;
      taskLoadTokenRef.current = token;
      try {
        const page = await api.listResearchTasks(target.id);
        if (token !== taskLoadTokenRef.current) {
          return;
        }
        setTasks(page.items);
      } catch (loadError) {
        if (token !== taskLoadTokenRef.current) {
          return;
        }
        if (loadError instanceof ApiError && loadError.status === 403) {
          setPhase('forbidden');
          setError(describeApiError(loadError, '没有查看该空间报告任务的权限。'));
        } else {
          setPhase('error');
          setError(describeApiError(loadError, '报告任务加载失败，请重试。'));
        }
      } finally {
        if (token === taskLoadTokenRef.current) {
          setTasksLoading(false);
        }
      }
    },
    [api, workspaces],
  );

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
    workspaces,
    selectWorkspace,
    tasks,
    tasksLoading,
    tools,
    disabledTools,
    toolsLoading,
    toolsError,
    toolsErrorKind,
    creating,
    actionError,
    initialize,
    refreshTasks,
    createTask,
    dismissActionError: () => setActionError(null),
  };
}
