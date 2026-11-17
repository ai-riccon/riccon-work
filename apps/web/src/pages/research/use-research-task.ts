import { useCallback, useEffect, useRef, useState } from 'react';
import type { RicconApi } from '@/services/api/client';
import { ApiError, describeApiError, isAbortError } from '@/services/api/errors';
import type { ApprovalDecision, ArtifactDetail, ResearchTaskDetail } from '@/services/api/types';
import { isMarkdownContent } from '@/services/research/format';

/** 任务详情加载阶段。 */
export type ResearchDetailPhase = 'loading' | 'ready' | 'forbidden' | 'notFound' | 'error';

/** J2 报告任务详情控制器。 */
export interface ResearchTaskController {
  phase: ResearchDetailPhase;
  detail: ResearchTaskDetail | null;
  error: string | null;
  actionError: string | null;
  decidingApprovalId: string | null;
  retrying: boolean;
  artifactBody: string | null;
  artifactBodyLoading: boolean;
  artifactBodyError: string | null;
  reload: () => Promise<void>;
  loadArtifactBody: () => Promise<void>;
  decide: (approvalId: string, decision: ApprovalDecision, comment?: string) => Promise<boolean>;
  retry: (fromStage?: 'TOOL' | 'GENERATION' | null) => Promise<boolean>;
  dismissActionError: () => void;
}

/** 仍需要轮询的任务状态；终态自动停止。 */
const ACTIVE_STATUSES = new Set<ResearchTaskDetail['status']>([
  'PENDING',
  'RUNNING',
  'WAITING_APPROVAL',
]);

/** 默认轮询间隔；报告阶段以秒级推进，无需更密集的请求。 */
const DEFAULT_POLL_INTERVAL_MS = 900;

/**
 * 生成幂等键。
 *
 * 同一审批的同一决定复用同一个键，确保网络重试不会变成第二次决定；
 * 浏览器不支持 randomUUID 时退化为时间戳 + 随机数，仍满足 8–128 字符约束。
 *
 * @returns 幂等键
 */
function createRequestKey(): string {
  if (typeof globalThis.crypto?.randomUUID === 'function') {
    return globalThis.crypto.randomUUID();
  }
  return `research-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

/**
 * 加载并维护单个报告任务的详情、时间线与产物正文。
 *
 * 数据流：
 * - 详情以 `GET /research-tasks/{id}` 为权威来源，按状态轮询；终态停止。
 * - 审批/重试成功后立即重新读取详情，不乐观修改阶段状态，避免界面与服务端不一致。
 * - 产物正文按版本缓存；换版本或手动重试时才重新读取。
 *
 * @param api API 客户端
 * @param taskId 报告任务 ID
 * @param pollIntervalMs 轮询间隔
 * @returns 状态与动作
 */
export function useResearchTask(
  api: RicconApi,
  taskId: string,
  pollIntervalMs = DEFAULT_POLL_INTERVAL_MS,
): ResearchTaskController {
  const [phase, setPhase] = useState<ResearchDetailPhase>('loading');
  const [detail, setDetail] = useState<ResearchTaskDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [decidingApprovalId, setDecidingApprovalId] = useState<string | null>(null);
  const [retrying, setRetrying] = useState(false);
  const [artifactBody, setArtifactBody] = useState<string | null>(null);
  const [artifactBodyLoading, setArtifactBodyLoading] = useState(false);
  const [artifactBodyError, setArtifactBodyError] = useState<string | null>(null);

  const loadTokenRef = useRef(0);
  const pollInFlightRef = useRef(false);
  const decidingRef = useRef(false);
  const retryingRef = useRef(false);
  const decisionKeysRef = useRef(new Map<string, string>());
  const retryKeyRef = useRef<string | null>(null);
  const bodyKeyRef = useRef<string | null>(null);
  const bodyAbortRef = useRef<AbortController | null>(null);

  /**
   * 读取产物正文；Markdown 才在线预览，其他类型由界面提示下载。
   *
   * @param artifact 产物摘要（含当前版本）
   * @param force 是否忽略缓存强制重新读取
   */
  const loadBody = useCallback(
    async (artifact: ArtifactDetail | null | undefined, force: boolean): Promise<void> => {
      const version = artifact?.currentVersionDetail;
      if (!artifact || !version) {
        bodyKeyRef.current = null;
        setArtifactBody(null);
        setArtifactBodyError(null);
        return;
      }
      if (!isMarkdownContent(version.contentType)) {
        bodyKeyRef.current = `${artifact.id}:${version.version}`;
        setArtifactBody(null);
        setArtifactBodyError(null);
        return;
      }
      const key = `${artifact.id}:${version.version}`;
      if (!force && bodyKeyRef.current === key) {
        return;
      }
      bodyAbortRef.current?.abort();
      const controller = new AbortController();
      bodyAbortRef.current = controller;
      setArtifactBodyLoading(true);
      setArtifactBodyError(null);
      try {
        const text = await api.fetchArtifactContent(
          artifact.id,
          version.version,
          controller.signal,
        );
        if (controller.signal.aborted) {
          return;
        }
        bodyKeyRef.current = key;
        setArtifactBody(text);
      } catch (bodyError) {
        if (isAbortError(bodyError)) {
          return;
        }
        setArtifactBodyError(describeApiError(bodyError, '报告正文加载失败，请重试。'));
      } finally {
        if (!controller.signal.aborted) {
          setArtifactBodyLoading(false);
        }
      }
    },
    [api],
  );

  /**
   * 读取任务详情，并在产物就绪时加载正文。
   *
   * @param silent 是否静默刷新（轮询与动作后刷新不展示整页加载态）
   */
  const load = useCallback(
    async (silent: boolean): Promise<void> => {
      if (!taskId) {
        setPhase('notFound');
        setError('缺少报告任务标识。');
        return;
      }
      if (!silent) {
        setPhase('loading');
        setError(null);
      }
      const token = loadTokenRef.current + 1;
      loadTokenRef.current = token;
      try {
        const next = await api.getResearchTask(taskId);
        if (token !== loadTokenRef.current) {
          return;
        }
        setDetail(next);
        setPhase('ready');
        setError(null);
        void loadBody(next.artifact, false);
      } catch (loadError) {
        if (token !== loadTokenRef.current) {
          return;
        }
        if (loadError instanceof ApiError && loadError.status === 403) {
          setPhase('forbidden');
          setError(describeApiError(loadError, '没有查看该任务的权限。'));
        } else if (loadError instanceof ApiError && loadError.status === 404) {
          setPhase('notFound');
          setError('报告任务不存在或已被删除。');
        } else {
          setPhase('error');
          setError(describeApiError(loadError, '报告任务加载失败，请重试。'));
        }
      }
    },
    [api, loadBody, taskId],
  );

  /**
   * 公开的重新加载；用于错误态与失败后的手动重试。
   */
  const reload = useCallback(async (): Promise<void> => {
    await load(false);
  }, [load]);

  /**
   * 强制重新读取当前产物正文。
   */
  const loadArtifactBody = useCallback(async (): Promise<void> => {
    await loadBody(detail?.artifact, true);
  }, [detail?.artifact, loadBody]);

  // 任务切换时重置状态并加载；卸载时让未完成请求的结果失效。
  useEffect(() => {
    setDetail(null);
    setArtifactBody(null);
    setArtifactBodyError(null);
    bodyKeyRef.current = null;
    decisionKeysRef.current.clear();
    retryKeyRef.current = null;
    setActionError(null);
    void load(false);
    return () => {
      loadTokenRef.current += 1;
      bodyAbortRef.current?.abort();
    };
  }, [load]);

  // 仅活跃任务轮询；终态、无权限与错误态停止。
  useEffect(() => {
    if (!detail || !ACTIVE_STATUSES.has(detail.status)) {
      return;
    }
    const timer = window.setInterval(() => {
      if (pollInFlightRef.current) {
        return;
      }
      pollInFlightRef.current = true;
      void load(true).finally(() => {
        pollInFlightRef.current = false;
      });
    }, pollIntervalMs);
    return () => {
      window.clearInterval(timer);
    };
  }, [detail, load, pollIntervalMs]);

  /**
   * 批准或拒绝审批。
   *
   * 使用 ref 防止双击重复提交；同一审批的同一决定复用幂等键。
   *
   * @param approvalId 审批 ID
   * @param decision 决定
   * @param comment 可选备注
   * @returns 是否成功
   */
  const decide = useCallback(
    async (approvalId: string, decision: ApprovalDecision, comment?: string): Promise<boolean> => {
      if (decidingRef.current) {
        return false;
      }
      decidingRef.current = true;
      setDecidingApprovalId(approvalId);
      setActionError(null);
      const key = `${approvalId}:${decision}`;
      const idempotencyKey = decisionKeysRef.current.get(key) ?? createRequestKey();
      decisionKeysRef.current.set(key, idempotencyKey);
      try {
        await api.decideApproval(
          approvalId,
          { decision, comment: comment?.trim() ? comment.trim() : null },
          idempotencyKey,
        );
        decisionKeysRef.current.delete(key);
        await load(true);
        return true;
      } catch (decisionError) {
        if (decisionError instanceof ApiError && decisionError.status === 409) {
          setActionError('审批状态已变化，已刷新最新结果。');
          await load(true);
        } else {
          setActionError(describeApiError(decisionError, '提交审批决定失败，请重试。'));
        }
        return false;
      } finally {
        decidingRef.current = false;
        setDecidingApprovalId(null);
      }
    },
    [api, load],
  );

  /**
   * 重试失败阶段；幂等键在失败后复用，避免重复触发运行。
   *
   * @param fromStage 起始阶段；为空时由服务端按失败阶段判断
   * @returns 是否成功
   */
  const retry = useCallback(
    async (fromStage?: 'TOOL' | 'GENERATION' | null): Promise<boolean> => {
      if (retryingRef.current) {
        return false;
      }
      retryingRef.current = true;
      setRetrying(true);
      setActionError(null);
      const idempotencyKey = retryKeyRef.current ?? createRequestKey();
      retryKeyRef.current = idempotencyKey;
      try {
        await api.retryResearchTask(taskId, fromStage ? { fromStage } : {}, idempotencyKey);
        retryKeyRef.current = null;
        await load(true);
        return true;
      } catch (retryError) {
        setActionError(describeApiError(retryError, '重试失败阶段失败，请重试。'));
        return false;
      } finally {
        retryingRef.current = false;
        setRetrying(false);
      }
    },
    [api, load, taskId],
  );

  return {
    phase,
    detail,
    error,
    actionError,
    decidingApprovalId,
    retrying,
    artifactBody,
    artifactBodyLoading,
    artifactBodyError,
    reload,
    loadArtifactBody,
    decide,
    retry,
    dismissActionError: () => setActionError(null),
  };
}
