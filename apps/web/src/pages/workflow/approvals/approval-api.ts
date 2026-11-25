import { API_BASE_URL } from '@/services/api/config';
import { toApiError } from '@/services/api/errors';
import type { Page } from '@/services/api/types';
import { authStore } from '@/services/auth/auth-store';
import type {
  WorkflowApprovalActionKind,
  WorkflowApprovalAssigneeType,
  WorkflowRunScope,
} from '../list/workflow-api';

/**
 * M06 审批前端契约类型与 API 客户端。
 *
 * 类型来源：`contracts/openapi.yaml`（`1.6.0-m06`）的 `WorkflowApproval`、
 * `WorkflowApprovalDetail`、`WorkflowApprovalPage` 与 `WorkflowApprovalDecisionRequest`；
 * 只声明页面实际消费的字段。
 *
 * 为什么放在页面目录：TASK-0234 的写入范围只包含
 * `apps/web/src/pages/workflow/approvals/**`。T10 的 `workflow-api.ts` 已保存流程/图/运行范围类型，
 * 本文件只读复用；审批端点单独封装，避免修改 T10 已验收的客户端。后续若统一迁入
 * `src/services/workflow/**`，应保持本文件导出的函数签名不变。
 */

/** 审批状态，对应 OpenAPI `WorkflowApprovalStatus`。 */
export type WorkflowApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED';

/** 审批决定，对应 OpenAPI `WorkflowApprovalDecision`。 */
export type WorkflowApprovalDecision = 'APPROVE' | 'REJECT';

/** 数据范围摘要，对应 OpenAPI `WorkflowDataScopeSummary`；只含范围/部门/引用计数，不含任何正文。 */
export interface WorkflowDataScopeSummary {
  runScope: WorkflowRunScope;
  departmentId?: string | null;
  documentReferenceCount: number;
}

/**
 * 审批项，对应 OpenAPI `WorkflowApproval`。
 *
 * `paramsPreview` 只在审批详情（assignee）返回；列表响应省略该字段，页面不得为列表项推断参数。
 * 时间字段是带 `+08:00` 偏移的 ISO 8601。
 */
export interface WorkflowApproval {
  id: string;
  workflowRunId: string;
  workflowId: string;
  workflowName?: string | null;
  nodeId: string;
  nodeName: string;
  status: WorkflowApprovalStatus;
  assigneeType: WorkflowApprovalAssigneeType;
  assigneeRole?: string | null;
  assigneeUserIds?: string[] | null;
  actionKind: WorkflowApprovalActionKind;
  dataScopeSummary: WorkflowDataScopeSummary;
  targetToolName?: string | null;
  paramsPreview?: Record<string, unknown> | null;
  planSha256: string;
  requestedBy: string;
  decidedBy?: string | null;
  decidedAt?: string | null;
  decisionComment?: string | null;
  createdAt: string;
  /** 当前用户是运行发起人时为 true；仅详情返回。界面据此隐藏批准/拒绝入口。 */
  selfApprovalBlocked?: boolean | null;
  /** 审批涉及的引用数量；仅详情返回。 */
  documentReferenceCount?: number | null;
}

/** 审批详情，对应 OpenAPI `WorkflowApprovalDetail`；当前字段是列表项的超集。 */
export type WorkflowApprovalDetail = WorkflowApproval;

/** 审批分页，对应 OpenAPI `WorkflowApprovalPage`。 */
export type WorkflowApprovalPage = Page<WorkflowApproval>;

/** 待办查询参数；`status` 省略时由服务端按待办口径返回。 */
export interface ListApprovalsInput {
  status?: WorkflowApprovalStatus;
  page?: number;
  size?: number;
}

/** 决定请求体，对应 OpenAPI `WorkflowApprovalDecisionRequest`。 */
export interface WorkflowApprovalDecisionInput {
  decision: WorkflowApprovalDecision;
  /** 拒绝时必填（1–500 字符）；批准时可选。 */
  comment?: string;
}

/** 页面依赖的审批 API 能力集合；测试注入替身，页面不直接依赖 fetch。 */
export interface WorkflowApprovalApi {
  listApprovals(input?: ListApprovalsInput): Promise<WorkflowApprovalPage>;
  getApproval(approvalId: string): Promise<WorkflowApprovalDetail>;
  decideApproval(
    approvalId: string,
    input: WorkflowApprovalDecisionInput,
  ): Promise<WorkflowApproval>;
}

/** 构造参数；测试可注入 fetch、CSRF 令牌与固定幂等键。 */
export interface WorkflowApprovalApiOptions {
  baseUrl?: string;
  fetchImpl?: typeof fetch;
  /** CSRF 令牌读取；默认从会话或 `/auth/csrf` 获取。 */
  csrfToken?: () => string | Promise<string>;
  /** 决定请求的幂等键生成器；默认每次用户动作生成一个新的合法键。 */
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
 * 契约要求 8–128 可打印字符；M06 不承诺键级重放，重复/并发决定仍由服务端条件更新返回 409。
 *
 * @returns UUID 或时间戳兜底键
 */
function randomId(): string {
  if (typeof globalThis.crypto?.randomUUID === 'function') {
    return globalThis.crypto.randomUUID();
  }
  return `workflow-approval-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

/**
 * 构造审批 API 客户端。
 *
 * 安全与错误语义：
 * - 决定请求不做 403 自动重试。服务端把自批/范围外与 CSRF 失效都归一为
 *   `403 RICCON-AUTH-403`，盲目重试会给自批再写一条 `DENIED` 审计。令牌来自当前会话，
 *   只有会话本身失效时才需要重新登录；页面会提示用户刷新后再试。
 * - 429/409/503 原样抛出 `ApiError`，由页面按固定中文文案区分，不吞掉业务状态码。
 *
 * @param options 基础地址、fetch、CSRF 与幂等键
 * @returns WorkflowApprovalApi 实现
 */
export function createWorkflowApprovalApi(
  options: WorkflowApprovalApiOptions = {},
): WorkflowApprovalApi {
  const baseUrl = (options.baseUrl ?? API_BASE_URL).replace(/\/$/, '');
  const fetchImpl = options.fetchImpl ?? globalThis.fetch.bind(globalThis);
  let cachedToken: string | null = null;

  /**
   * 读取 CSRF 令牌。
   *
   * 已登录会话优先使用内存中的令牌；未恢复会话时回退到 `/auth/csrf`，与既有 ApiClient
   * 的引导语义一致。令牌不写 Cookie 或 localStorage。
   *
   * @returns CSRF 令牌
   */
  const csrfToken = async (): Promise<string> => {
    if (options.csrfToken) {
      return options.csrfToken();
    }
    if (cachedToken) {
      return cachedToken;
    }
    const session = authStore.getSnapshot().session;
    if (session?.csrfToken) {
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
   * 执行 JSON 请求。
   *
   * @param path 以 `/` 开头的 API 路径
   * @param options 方法、请求体与额外请求头
   * @returns 解析后的响应体
   */
  const request = async <T>(path: string, options: RequestOptions = {}): Promise<T> => {
    const method = options.method ?? 'GET';
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (method !== 'GET') {
      headers['X-CSRF-TOKEN'] = await csrfToken();
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
    if (!response.ok) {
      throw await toApiError(response);
    }
    return (await response.json()) as T;
  };

  return {
    listApprovals(input = {}) {
      const params = new URLSearchParams({
        page: String(input.page ?? 1),
        size: String(input.size ?? 20),
      });
      if (input.status) {
        params.set('status', input.status);
      }
      return request<WorkflowApprovalPage>(`/api/v1/workflow-approvals?${params.toString()}`);
    },

    getApproval(approvalId) {
      return request<WorkflowApprovalDetail>(
        `/api/v1/workflow-approvals/${encodeURIComponent(approvalId)}`,
      );
    },

    decideApproval(approvalId, input) {
      return request<WorkflowApproval>(
        `/api/v1/workflow-approvals/${encodeURIComponent(approvalId)}/decision`,
        {
          method: 'POST',
          body: input,
          extraHeaders: {
            'Idempotency-Key': (options.idempotencyKey ?? randomId)(),
          },
        },
      );
    },
  };
}

/** 应用默认审批 API；同源部署、真实 fetch，不包含演示分支。 */
export const workflowApprovalApi: WorkflowApprovalApi = createWorkflowApprovalApi();
