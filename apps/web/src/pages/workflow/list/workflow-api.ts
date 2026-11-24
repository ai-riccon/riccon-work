import { authStore } from '@/services/auth/auth-store';
import { API_BASE_URL } from '@/services/api/config';
import { toApiError } from '@/services/api/errors';
import type { Department, McpTool, Page } from '@/services/api/types';
import type { EmployeeModel } from '@/services/model/types';

/**
 * M06 编排 Studio 前端契约类型与 API 客户端。
 *
 * 类型来源：`contracts/openapi.yaml` 的 `WorkflowDefinition`、`WorkflowDefinitionDetail`、
 * `WorkflowGraph`、`WorkflowValidationResult`、`WorkflowVersion` 与相关请求体；只声明页面实际消费的字段。
 *
 * 为什么放在页面目录而不是 `src/services`：TASK-0230 的写入范围只包含
 * `apps/web/src/pages/workflow/list/**` 与 `edit/**`。共享契约类型与请求封装因此落在 list 页面内，
 * 编辑器以只读方式复用；后续任务若把 workflow 服务迁入 `src/services/workflow/**`，
 * 应保持本文件导出的函数签名不变，避免两套契约漂移。
 */

/** 流程级状态，对应 OpenAPI `WorkflowDefinitionStatus`。 */
export type WorkflowDefinitionStatus = 'ACTIVE' | 'DISABLED';

/** 列表视图，对应后端 `WorkflowDefinitionView`。 */
export type WorkflowView = 'MINE' | 'RUNNABLE' | 'ALL';

/** 运行范围，对应 OpenAPI `WorkflowRunScope`。 */
export type WorkflowRunScope = 'ONLY_SELF' | 'DEPARTMENT' | 'TENANT';

/** 节点类型，M06 封闭最小集。 */
export type WorkflowNodeType =
  'START' | 'END' | 'RETRIEVAL' | 'MODEL' | 'MCP_TOOL' | 'APPROVAL' | 'CONDITION';

/** 条件左值来源。 */
export type WorkflowConditionSource = 'RUN_INPUT' | 'NODE_OUTPUT';

/** 条件值类型。 */
export type WorkflowConditionValueType = 'NUMBER' | 'STRING' | 'BOOLEAN';

/** 条件算子，封闭集合。 */
export type WorkflowConditionOperator = 'EQ' | 'NE' | 'GT' | 'GTE' | 'LT' | 'LTE' | 'IN';

/** 条件分支标记；必须是字符串 TRUE/FALSE，不是布尔值。 */
export type WorkflowEdgeBranch = 'TRUE' | 'FALSE';

/** 审批人解析方式。 */
export type WorkflowApprovalAssigneeType = 'USER' | 'ROLE';

/** 审批解锁的动作类型。 */
export type WorkflowApprovalActionKind = 'MCP_TOOL' | 'MODEL' | 'CONTINUE';

/** 模型输出形态。 */
export type WorkflowOutputKind = 'TEXT' | 'CLASSIFICATION';

/** 稳定的校验问题码，与 `WorkflowValidationCode` 枚举一致。 */
export type WorkflowValidationCode =
  | 'GRAPH_CYCLE'
  | 'MISSING_START'
  | 'MULTIPLE_START'
  | 'MISSING_END'
  | 'ORPHAN_NODE'
  | 'INDEGREE_EXCEEDED'
  | 'EDGE_INVALID'
  | 'CONDITION_BRANCH_INVALID'
  | 'REQUIRED_CONFIG'
  | 'NODE_LIMIT'
  | 'EDGE_LIMIT'
  | 'MODEL_UNAVAILABLE'
  | 'TOOL_NOT_AUTHORIZED'
  | 'TOOL_DISABLED'
  | 'APPROVAL_CONFIG'
  | 'NO_APPROVER'
  | 'RESULT_INVALID'
  | 'TEMPLATE_INVALID';

/** 条件左值，对应 OpenAPI `WorkflowConditionSource`。 */
export interface WorkflowConditionSourceConfig {
  source: WorkflowConditionSource;
  nodeId?: string | null;
  path: string;
  valueType: WorkflowConditionValueType;
}

/** 条件右值，对应 OpenAPI `WorkflowConditionRight`。 */
export interface WorkflowConditionRight {
  valueType: WorkflowConditionValueType;
  value?: string | number | boolean | null;
  values?: Array<string | number | boolean>;
}

/** 条件配置，对应 OpenAPI `WorkflowCondition`。 */
export interface WorkflowCondition {
  left: WorkflowConditionSourceConfig;
  operator: WorkflowConditionOperator;
  right: WorkflowConditionRight;
}

/**
 * 节点配置，对应 OpenAPI `WorkflowNodeConfig`。
 *
 * 字段均为可选：不同节点类型只使用其中一部分；服务端在保存/发布时按类型校验必填。
 * 客户端保存前会通过 `normalizeGraphForSave` 去掉空字符串与不相关字段，关闭 REV-TASK-0220-001 F-003。
 */
export interface WorkflowNodeConfig {
  promptTemplate?: string;
  queryTemplate?: string;
  topK?: number;
  modelId?: string | null;
  outputKind?: WorkflowOutputKind;
  labels?: string[];
  toolId?: string | null;
  argsTemplate?: Record<string, unknown>;
  assigneeType?: WorkflowApprovalAssigneeType;
  assigneeRole?: string | null;
  assigneeUserIds?: string[];
  actionKind?: WorkflowApprovalActionKind | null;
  targetNodeKey?: string | null;
  condition?: WorkflowCondition | null;
  resultFrom?: string | null;
  resultTitle?: string | null;
}

/** 节点坐标；保存时必须同时包含 x 与 y。 */
export interface WorkflowGraphPosition {
  x: number;
  y: number;
}

/** 图节点，对应 OpenAPI `WorkflowNode`；`position` 在契约中可选。 */
export interface WorkflowGraphNode {
  id: string;
  type: WorkflowNodeType;
  name: string;
  config: WorkflowNodeConfig;
  position?: WorkflowGraphPosition | null;
}

/** 图连线，对应 OpenAPI `WorkflowEdge`。 */
export interface WorkflowGraphEdge {
  id: string;
  source: string;
  target: string;
  branch?: WorkflowEdgeBranch | null;
}

/** 草稿图，对应 OpenAPI `WorkflowGraph`。 */
export interface WorkflowGraph {
  schemaVersion: 1;
  nodes: WorkflowGraphNode[];
  edges: WorkflowGraphEdge[];
}

/** 流程列表项，对应 OpenAPI `WorkflowDefinition`。 */
export interface WorkflowDefinition {
  id: string;
  name: string;
  description?: string | null;
  ownerUserId: string;
  status: WorkflowDefinitionStatus;
  draftVersion: number;
  publishedVersionId?: string | null;
  publishedVersionNo?: number | null;
  hasUnpublishedChanges: boolean;
  sourceTemplateId?: string | null;
  sourceTemplateType?: 'BUILT_IN' | 'TENANT' | null;
  sourceTemplateVersion?: number | null;
  createdAt: string;
  updatedAt: string;
}

/** 发布版本摘要，对应 OpenAPI `WorkflowVersion`。 */
export interface WorkflowVersion {
  id: string;
  workflowId: string;
  versionNo: number;
  runScope: WorkflowRunScope;
  departmentId?: string | null;
  graphSha256: string;
  nodeCount: number;
  publishedBy: string;
  publishedAt: string;
}

/** 流程详情，对应 OpenAPI `WorkflowDefinitionDetail`。 */
export interface WorkflowDefinitionDetail extends WorkflowDefinition {
  /** 仅所有者与租户管理员返回；其他可见用户拿到的详情不含草稿。 */
  draftGraph?: WorkflowGraph | null;
  latestVersion?: WorkflowVersion | null;
  canEdit: boolean;
  canPublish: boolean;
  canRun: boolean;
}

/** 分页结构，对应 OpenAPI `WorkflowDefinitionPage`。 */
export type WorkflowDefinitionPage = Page<WorkflowDefinition>;

/** 单条校验问题，对应 OpenAPI `WorkflowValidationIssue`。 */
export interface WorkflowValidationIssue {
  code: WorkflowValidationCode;
  nodeId?: string | null;
  field?: string | null;
  message: string;
}

/** 校验结果，对应 OpenAPI `WorkflowValidationResult`。 */
export interface WorkflowValidationResult {
  valid: boolean;
  issues: WorkflowValidationIssue[];
}

/** 创建请求，对应 OpenAPI `WorkflowCreateRequest`。 */
export interface WorkflowCreateRequest {
  name?: string;
  description?: string | null;
  templateId?: string | null;
}

/** 草稿保存请求，对应 OpenAPI `WorkflowDraftUpdateRequest`。 */
export interface WorkflowDraftUpdateRequest {
  version: number;
  name?: string | null;
  description?: string | null;
  graph: WorkflowGraph;
}

/** 副本请求，对应 OpenAPI `WorkflowDuplicateRequest`。 */
export interface WorkflowDuplicateRequest {
  name?: string | null;
}

/** 发布请求，对应 OpenAPI `WorkflowPublishRequest`。 */
export interface WorkflowPublishRequest {
  runScope: WorkflowRunScope;
  departmentId?: string | null;
}

/** 校验范围参数；T04b 起由后端按发布口径复用。 */
export interface WorkflowValidateInput {
  runScope?: WorkflowRunScope;
  departmentId?: string | null;
}

/** 列表查询参数。 */
export interface ListWorkflowsInput {
  view?: WorkflowView;
  status?: WorkflowDefinitionStatus;
  page?: number;
  size?: number;
}

/**
 * 内置市场调研模板的固定 ID。
 *
 * 与 `WorkflowTemplateKeys.MARKET_RESEARCH_ID` 和 V15 种子保持一致（升级不改变）。
 * T14 会提供 `GET /workflow-templates` 列表；在列表端点落地前，T10 用该稳定 ID
 * 完成“从模板创建”的入口，避免依赖尚未实现的模板目录接口。
 */
export const MARKET_RESEARCH_TEMPLATE_ID = '01990000-0000-7000-8000-000000000601';

/** 页面依赖的编排 API 能力集合；测试注入替身，页面不直接依赖 fetch。 */
export interface WorkflowApi {
  listWorkflows(input?: ListWorkflowsInput): Promise<WorkflowDefinitionPage>;
  createWorkflow(input: WorkflowCreateRequest): Promise<WorkflowDefinitionDetail>;
  getWorkflow(workflowId: string): Promise<WorkflowDefinitionDetail>;
  saveDraft(
    workflowId: string,
    input: WorkflowDraftUpdateRequest,
  ): Promise<WorkflowDefinitionDetail>;
  duplicateWorkflow(
    workflowId: string,
    input?: WorkflowDuplicateRequest,
  ): Promise<WorkflowDefinitionDetail>;
  validateWorkflow(
    workflowId: string,
    input?: WorkflowValidateInput,
  ): Promise<WorkflowValidationResult>;
  publishWorkflow(workflowId: string, input: WorkflowPublishRequest): Promise<WorkflowVersion>;
  listModels(): Promise<Page<EmployeeModel>>;
  listMcpTools(): Promise<Page<McpTool>>;
  listDepartments(): Promise<Page<Department>>;
}

/** 构造参数；测试可注入 fetch 与固定 CSRF 令牌。 */
export interface WorkflowApiOptions {
  baseUrl?: string;
  fetchImpl?: typeof fetch;
  /** CSRF 令牌读取；默认从会话或 `/auth/csrf` 获取。 */
  csrfToken?: () => string | Promise<string>;
}

/** 普通 JSON 请求选项。 */
interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT';
  body?: unknown;
  extraHeaders?: Record<string, string>;
}

/**
 * 生成一次性幂等键。
 *
 * M06 发布使用资源级幂等，不依赖键级重放；这里仍提供合法长度（8..128）的键，
 * 兼容 T04 当前要求 `Idempotency-Key` 的控制器与 T04b 的可选键口径。
 *
 * @returns UUID 或时间戳兜底键
 */
function randomId(): string {
  if (typeof globalThis.crypto?.randomUUID === 'function') {
    return globalThis.crypto.randomUUID();
  }
  return `workflow-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

/**
 * 判断 403 是否由 CSRF 令牌失效导致。
 *
 * 与 `services/api/client.ts` 的口径一致：使用 clone 读取，不破坏后续错误解析。
 *
 * @param response 403 响应
 * @returns 是否为 CSRF 错误
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
 * 构造编排 API 客户端。
 *
 * @param options 基础地址、fetch 与 CSRF 读取
 * @returns WorkflowApi 实现
 */
export function createWorkflowApi(options: WorkflowApiOptions = {}): WorkflowApi {
  const baseUrl = (options.baseUrl ?? API_BASE_URL).replace(/\/$/, '');
  const fetchImpl = options.fetchImpl ?? globalThis.fetch.bind(globalThis);
  let cachedToken: string | null = null;

  /**
   * 读取 CSRF 令牌。
   *
   * 已登录会话优先使用内存中的令牌；未恢复会话时回退到 `/auth/csrf`，
   * 与既有 ApiClient 的匿名令牌引导语义一致。令牌不写 Cookie 或 localStorage。
   *
   * @param forceRefresh 是否跳过会话缓存强制重新获取（CSRF 失效重试用）
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
   * 执行 JSON 请求，并在 CSRF 失效时刷新令牌重试一次。
   *
   * @param path 以 `/` 开头的 API 路径
   * @param options 方法、请求体与额外请求头
   * @returns 解析后的响应体
   */
  const request = async <T>(path: string, options: RequestOptions = {}): Promise<T> => {
    const method = options.method ?? 'GET';
    const unsafe = method !== 'GET';

    const doFetch = async (retried: boolean): Promise<Response> => {
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
        return doFetch(true);
      }
      return response;
    };

    const response = await doFetch(false);
    if (!response.ok) {
      throw await toApiError(response);
    }
    return (await response.json()) as T;
  };

  return {
    listWorkflows(input = {}) {
      const params = new URLSearchParams({
        view: input.view ?? 'MINE',
        page: String(input.page ?? 1),
        size: String(input.size ?? 20),
      });
      if (input.status) {
        params.set('status', input.status);
      }
      return request<WorkflowDefinitionPage>(`/api/v1/workflows?${params.toString()}`);
    },

    createWorkflow(input) {
      return request<WorkflowDefinitionDetail>('/api/v1/workflows', {
        method: 'POST',
        body: input,
      });
    },

    getWorkflow(workflowId) {
      return request<WorkflowDefinitionDetail>(
        `/api/v1/workflows/${encodeURIComponent(workflowId)}`,
      );
    },

    saveDraft(workflowId, input) {
      return request<WorkflowDefinitionDetail>(
        `/api/v1/workflows/${encodeURIComponent(workflowId)}/draft`,
        { method: 'PUT', body: input },
      );
    },

    duplicateWorkflow(workflowId, input) {
      return request<WorkflowDefinitionDetail>(
        `/api/v1/workflows/${encodeURIComponent(workflowId)}/duplicate`,
        { method: 'POST', body: input ?? {} },
      );
    },

    validateWorkflow(workflowId, input = {}) {
      const params = new URLSearchParams();
      if (input.runScope) {
        params.set('runScope', input.runScope);
      }
      if (input.departmentId) {
        params.set('departmentId', input.departmentId);
      }
      const query = params.toString();
      return request<WorkflowValidationResult>(
        `/api/v1/workflows/${encodeURIComponent(workflowId)}/validate${query ? `?${query}` : ''}`,
        { method: 'POST', body: {} },
      );
    },

    publishWorkflow(workflowId, input) {
      return request<WorkflowVersion>(
        `/api/v1/workflows/${encodeURIComponent(workflowId)}/publish`,
        {
          method: 'POST',
          body: input,
          extraHeaders: { 'Idempotency-Key': randomId() },
        },
      );
    },

    listModels() {
      return request<Page<EmployeeModel>>('/api/v1/models?page=1&size=100');
    },

    listMcpTools() {
      return request<Page<McpTool>>('/api/v1/mcp-tools?page=1&size=100');
    },

    listDepartments() {
      return request<Page<Department>>('/api/v1/departments');
    },
  };
}

/** 应用默认编排 API；同源部署、真实 fetch，不包含演示分支。 */
export const workflowApi: WorkflowApi = createWorkflowApi();
