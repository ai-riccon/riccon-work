import { API_BASE_URL } from '@/services/api/config';
import { toApiError } from '@/services/api/errors';
import type { Department, Page } from '@/services/api/types';
import { authStore } from '@/services/auth/auth-store';
import type {
  ListWorkflowsInput,
  WorkflowDefinitionDetail,
  WorkflowDefinitionPage,
  WorkflowPublishRequest,
  WorkflowRunScope,
  WorkflowVersion,
} from '../list/workflow-api';
import type { WorkflowRunDetail, WorkflowRunStatus } from '../run/run-api';

/**
 * M06 运行历史与发布前端契约类型与 API 客户端。
 *
 * 类型来源：`contracts/openapi.yaml`（`1.6.0-m06`）的 `WorkflowRun`、`WorkflowRunPage`、
 * `WorkflowVersionPage`、`WorkflowRetryRequest` 与 `WorkflowRunCreateRequest`；
 * 只声明页面实际消费的字段。
 *
 * 为什么放在页面目录：TASK-0233 的写入范围只包含
 * `apps/web/src/pages/workflow/history/**`。T10 的 `list/workflow-api.ts` 已保存流程/版本类型，
 * T12a 的 `run/run-api.ts` 已保存运行详情类型；本文件只读复用，运行历史、发布与运行动作端点
 * 单独封装，避免修改已验收的客户端。后续若统一迁入 `src/services/workflow/**`，
 * 应保持本文件导出的函数签名不变。
 */

/** 审批状态，对应 OpenAPI `WorkflowApprovalStatus`。 */
export type WorkflowApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED';

/** 结果保存状态，对应 OpenAPI `WorkflowResultStatus`。 */
export type WorkflowResultStatus = 'NONE' | 'SAVED' | 'OVER_LIMIT';

/** 运行稳定错误码，对应 OpenAPI `WorkflowErrorCode`。 */
export type WorkflowErrorCode =
  | 'WF_INVALID_GRAPH'
  | 'WF_NODE_LIMIT'
  | 'WF_STEP_LIMIT'
  | 'WF_RUN_TIMEOUT'
  | 'WF_NODE_TIMEOUT'
  | 'WF_MODEL_UNAVAILABLE'
  | 'WF_MODEL_OUTPUT_INVALID'
  | 'WF_RETRIEVAL_FAILED'
  | 'WF_CONDITION_INVALID'
  | 'WF_TOOL_NOT_AUTHORIZED'
  | 'WF_TOOL_RESULT_LIMIT'
  | 'WF_APPROVAL_REQUIRED'
  | 'WF_NO_APPROVER'
  | 'WF_APPROVAL_REJECTED'
  | 'WF_SIDE_EFFECT_UNKNOWN'
  | 'WF_PLAN_DRIFT'
  | 'WF_RESULT_LIMIT'
  | 'WF_FEATURE_DISABLED'
  | 'WF_CANCELLED'
  | 'WF_INTERNAL';

/**
 * 运行摘要，对应 OpenAPI `WorkflowRun`。
 *
 * 历史列表只返回元数据与审批聚合，不返回节点、结果正文或输入原文；
 * 时间字段是带 `+08:00` 偏移的 ISO 8601。
 */
export interface WorkflowRunSummary {
  id: string;
  workflowId: string;
  workflowName?: string | null;
  workflowVersionId: string;
  versionNo: number;
  requesterUserId: string;
  status: WorkflowRunStatus;
  currentNodeId?: string | null;
  approvalStatus?: WorkflowApprovalStatus | null;
  errorCode?: WorkflowErrorCode | null;
  errorMessage?: string | null;
  resultStatus: WorkflowResultStatus;
  createdAt: string;
  startedAt?: string | null;
  finishedAt?: string | null;
  durationMs?: number | null;
  /** 轮询/刷新判据；变化才重绘。 */
  updatedAt: string;
  pendingApprovalCount: number;
  approvedApprovalCount: number;
  rejectedApprovalCount: number;
  retentionExpiresAt: string;
}

/** 运行分页，对应 OpenAPI `WorkflowRunPage`。 */
export type WorkflowRunPage = Page<WorkflowRunSummary>;

/** 版本分页，对应 OpenAPI `WorkflowVersionPage`。 */
export type WorkflowVersionPage = Page<WorkflowVersion>;

/** 运行历史过滤条件。 */
export interface ListRunsInput {
  workflowId?: string;
  versionNo?: number;
  status?: WorkflowRunStatus;
  requesterUserId?: string;
  /** 运行创建时间起点，带偏移 ISO 8601。 */
  from?: string;
  /** 运行创建时间终点，带偏移 ISO 8601。 */
  to?: string;
  page?: number;
  size?: number;
}

/** 创建运行请求，对应 OpenAPI `WorkflowRunCreateRequest`。 */
export interface WorkflowRunCreateInput {
  /** 默认最近发布版本；传入旧版本时由服务端按该版本范围校验。 */
  versionId?: string;
  /** 运行输入 JSON；必填对象，最多 16 个键。 */
  inputs: Record<string, unknown>;
}

/** 页面依赖的运行历史/发布 API 能力集合；测试注入替身，页面不直接依赖 fetch。 */
export interface WorkflowHistoryApi {
  listRuns(input?: ListRunsInput): Promise<WorkflowRunPage>;
  getRunDetail(runId: string): Promise<WorkflowRunDetail>;
  cancelRun(runId: string): Promise<void>;
  retryRunNode(runId: string, nodeId: string): Promise<void>;
  listWorkflows(input?: ListWorkflowsInput): Promise<WorkflowDefinitionPage>;
  getWorkflow(workflowId: string): Promise<WorkflowDefinitionDetail>;
  listVersions(
    workflowId: string,
    input?: { page?: number; size?: number },
  ): Promise<WorkflowVersionPage>;
  publishWorkflow(workflowId: string, input: WorkflowPublishRequest): Promise<WorkflowVersion>;
  disableWorkflow(workflowId: string): Promise<WorkflowDefinitionDetail>;
  enableWorkflow(workflowId: string): Promise<WorkflowDefinitionDetail>;
  createRun(workflowId: string, input: WorkflowRunCreateInput): Promise<WorkflowRunSummary>;
  listDepartments(): Promise<Page<Department>>;
}

/** 构造参数；测试可注入 fetch、CSRF 令牌与固定幂等键。 */
export interface WorkflowHistoryApiOptions {
  baseUrl?: string;
  fetchImpl?: typeof fetch;
  /** CSRF 令牌读取；默认从会话或 `/auth/csrf` 获取。 */
  csrfToken?: () => string | Promise<string>;
  /** 写操作幂等键生成器；默认每次用户动作生成一个新的合法键。 */
  idempotencyKey?: () => string;
}

/** 普通 JSON 请求选项。 */
interface RequestOptions {
  method?: 'GET' | 'POST';
  body?: unknown;
  extraHeaders?: Record<string, string>;
}

/**
 * 生成一次性幂等键。
 *
 * 契约要求 8–128 可打印字符；M06 不承诺发布键级重放，重复发布由资源级幂等兜底；
 * 运行创建与重试由服务端幂等键/状态机兜底。
 *
 * @returns UUID 或时间戳兜底键
 */
function randomId(): string {
  if (typeof globalThis.crypto?.randomUUID === 'function') {
    return globalThis.crypto.randomUUID();
  }
  return `workflow-history-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

/**
 * 判断 403 是否由 CSRF 令牌失效导致。
 *
 * 与 `services/api/client.ts` 的口径一致：使用 clone 读取，不破坏后续错误解析。
 * 仅写操作会触发该判断，读操作不发送 CSRF 令牌。
 *
 * @param response 403 响应
 * @returns 是否为需要刷新令牌后重试的 CSRF 错误
 */
async function isCsrfError(response: Response): Promise<boolean> {
  try {
    const problem = (await response.clone().json()) as { code?: string };
    return problem.code === 'RICCON-AUTH-403';
  } catch {
    return false;
  }
}

/**
 * 构造运行历史/发布 API 客户端。
 *
 * 安全与错误语义：
 * - GET 只读端点不发送 CSRF；POST 写操作发送 `X-CSRF-TOKEN`；
 * - 服务端把 RBAC 拒绝与 CSRF 失效统一为 `403 RICCON-AUTH-403`，客户端无法区分；
 *   为兼容页面长时间停留后的令牌轮换，写请求遇到该码会刷新令牌重试一次。工作流写端点
 *   都是授权前置或幂等（发布资源级幂等、运行/重试键级幂等、取消状态幂等），重复请求
 *   不会产生重复版本/运行或重复审计；
 * - 400/404/409/429/503 保留状态码与稳定错误码，不吞掉业务状态；
 * - 202 空响应不解析 JSON。
 *
 * @param options 基础地址、fetch、CSRF 与幂等键
 * @returns WorkflowHistoryApi 实现
 */
export function createWorkflowHistoryApi(
  options: WorkflowHistoryApiOptions = {},
): WorkflowHistoryApi {
  const baseUrl = (options.baseUrl ?? API_BASE_URL).replace(/\/$/, '');
  const fetchImpl = options.fetchImpl ?? globalThis.fetch.bind(globalThis);
  let cachedToken: string | null = null;

  /**
   * 读取 CSRF 令牌。
   *
   * 已登录会话优先使用内存中的令牌；未恢复会话时回退到 `/auth/csrf`，与既有 ApiClient
   * 的匿名令牌引导语义一致。令牌不写 Cookie 或 localStorage。
   *
   * @param forceRefresh 是否跳过缓存强制重新获取（CSRF 失效重试用）
   * @returns CSRF 令牌
   */
  const csrfToken = async (forceRefresh = false): Promise<string> => {
    if (options.csrfToken) {
      return options.csrfToken();
    }
    if (!forceRefresh && cachedToken) {
      return cachedToken;
    }
    const session = authStore.getSnapshot().session;
    if (!forceRefresh && session?.csrfToken) {
      cachedToken = session.csrfToken;
      return cachedToken;
    }
    const response = await fetchImpl(`${baseUrl}/api/v1/auth/csrf`, {
      method: 'GET',
      headers: { Accept: 'application/json' },
      credentials: 'same-origin',
    });
    if (!response.ok) {
      throw await toApiError(response);
    }
    const token = (await response.json()) as { token: string };
    cachedToken = token.token;
    return cachedToken;
  };

  /**
   * 执行请求，并在 CSRF 失效时刷新令牌重试一次。
   *
   * @param path 以 `/` 开头的 API 路径
   * @param options 方法、请求体与额外请求头
   * @returns 原始响应
   */
  const doFetch = async (path: string, options: RequestOptions = {}): Promise<Response> => {
    const method = options.method ?? 'GET';
    const unsafe = method !== 'GET';

    const send = async (retried: boolean): Promise<Response> => {
      const headers: Record<string, string> = { Accept: 'application/json' };
      if (unsafe) {
        headers['X-CSRF-TOKEN'] = await csrfToken(retried);
      }
      if (options.body !== undefined) {
        headers['Content-Type'] = 'application/json';
      }
      Object.assign(headers, options.extraHeaders);
      const response = await fetchImpl(`${baseUrl}${path}`, {
        method,
        headers,
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
        credentials: 'same-origin',
      });
      if (unsafe && !retried && response.status === 403 && (await isCsrfError(response))) {
        cachedToken = null;
        return send(true);
      }
      return response;
    };

    return send(false);
  };

  /**
   * 执行 JSON 请求。
   *
   * @param path 以 `/` 开头的 API 路径
   * @param options 方法、请求体与额外请求头
   * @returns 解析后的响应体
   */
  const requestJson = async <T>(path: string, options: RequestOptions = {}): Promise<T> => {
    const response = await doFetch(path, options);
    if (!response.ok) {
      throw await toApiError(response);
    }
    return (await response.json()) as T;
  };

  /**
   * 执行无响应体请求（202）。
   *
   * @param path 以 `/` 开头的 API 路径
   * @param options 方法、请求体与额外请求头
   */
  const requestVoid = async (path: string, options: RequestOptions = {}): Promise<void> => {
    const response = await doFetch(path, options);
    if (!response.ok) {
      throw await toApiError(response);
    }
  };

  return {
    listRuns(input = {}) {
      const params = new URLSearchParams({
        page: String(input.page ?? 1),
        size: String(input.size ?? 20),
      });
      if (input.workflowId) {
        params.set('workflowId', input.workflowId);
      }
      if (input.versionNo != null) {
        params.set('versionNo', String(input.versionNo));
      }
      if (input.status) {
        params.set('status', input.status);
      }
      if (input.requesterUserId) {
        params.set('requesterUserId', input.requesterUserId);
      }
      if (input.from) {
        params.set('from', input.from);
      }
      if (input.to) {
        params.set('to', input.to);
      }
      return requestJson<WorkflowRunPage>(`/api/v1/workflow-runs?${params.toString()}`);
    },

    getRunDetail(runId) {
      return requestJson<WorkflowRunDetail>(`/api/v1/workflow-runs/${encodeURIComponent(runId)}`);
    },

    cancelRun(runId) {
      return requestVoid(`/api/v1/workflow-runs/${encodeURIComponent(runId)}/cancel`, {
        method: 'POST',
        extraHeaders: { 'Idempotency-Key': (options.idempotencyKey ?? randomId)() },
      });
    },

    retryRunNode(runId, nodeId) {
      return requestVoid(`/api/v1/workflow-runs/${encodeURIComponent(runId)}/retry`, {
        method: 'POST',
        body: { nodeId },
        extraHeaders: { 'Idempotency-Key': (options.idempotencyKey ?? randomId)() },
      });
    },

    listWorkflows(input = {}) {
      const params = new URLSearchParams({
        view: input.view ?? 'MINE',
        page: String(input.page ?? 1),
        size: String(input.size ?? 100),
      });
      if (input.status) {
        params.set('status', input.status);
      }
      return requestJson<WorkflowDefinitionPage>(`/api/v1/workflows?${params.toString()}`);
    },

    getWorkflow(workflowId) {
      return requestJson<WorkflowDefinitionDetail>(
        `/api/v1/workflows/${encodeURIComponent(workflowId)}`,
      );
    },

    listVersions(workflowId, input = {}) {
      const params = new URLSearchParams({
        page: String(input.page ?? 1),
        size: String(input.size ?? 20),
      });
      return requestJson<WorkflowVersionPage>(
        `/api/v1/workflows/${encodeURIComponent(workflowId)}/versions?${params.toString()}`,
      );
    },

    publishWorkflow(workflowId, input) {
      return requestJson<WorkflowVersion>(
        `/api/v1/workflows/${encodeURIComponent(workflowId)}/publish`,
        {
          method: 'POST',
          body: {
            runScope: input.runScope,
            ...(input.runScope === 'DEPARTMENT' && input.departmentId
              ? { departmentId: input.departmentId }
              : {}),
          },
          extraHeaders: { 'Idempotency-Key': (options.idempotencyKey ?? randomId)() },
        },
      );
    },

    disableWorkflow(workflowId) {
      return requestJson<WorkflowDefinitionDetail>(
        `/api/v1/workflows/${encodeURIComponent(workflowId)}/disable`,
        { method: 'POST' },
      );
    },

    enableWorkflow(workflowId) {
      return requestJson<WorkflowDefinitionDetail>(
        `/api/v1/workflows/${encodeURIComponent(workflowId)}/enable`,
        { method: 'POST' },
      );
    },

    createRun(workflowId, input) {
      return requestJson<WorkflowRunSummary>(
        `/api/v1/workflows/${encodeURIComponent(workflowId)}/runs`,
        {
          method: 'POST',
          body: {
            ...(input.versionId ? { versionId: input.versionId } : {}),
            inputs: input.inputs,
          },
          extraHeaders: { 'Idempotency-Key': (options.idempotencyKey ?? randomId)() },
        },
      );
    },

    listDepartments() {
      return requestJson<Page<Department>>('/api/v1/departments');
    },
  };
}

/** 应用默认运行历史/发布 API；同源部署、真实 fetch，不包含演示分支。 */
export const workflowHistoryApi: WorkflowHistoryApi = createWorkflowHistoryApi();

/** 运行范围类型再导出，供页面与测试复用同一契约来源。 */
export type { WorkflowRunScope };
