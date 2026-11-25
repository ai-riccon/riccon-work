import { API_BASE_URL } from '@/services/api/config';
import { toApiError } from '@/services/api/errors';
import type { Citation } from '@/services/api/types';
import type {
  WorkflowApprovalActionKind,
  WorkflowNodeType,
  WorkflowRunScope,
} from '../list/workflow-api';

/**
 * M06 运行详情前端契约类型与 API 客户端。
 *
 * 类型来源：`contracts/openapi.yaml`（`1.6.0-m06`）的 `WorkflowRunDetail`、
 * `WorkflowNodeRun`、`WorkflowApproval`、`WorkflowResult` 与 `Citation`；只声明页面实际消费的字段。
 *
 * 为什么放在页面目录：TASK-0232 的写入范围只包含 `apps/web/src/pages/workflow/run/**`。
 * 流程/图/运行范围类型复用 T10 的 `list/workflow-api.ts`；运行详情端点单独封装，
 * 避免修改 T10 已验收的客户端。后续若统一迁入 `src/services/workflow/**`，应保持本文件
 * 导出的函数签名不变，避免两套契约漂移。
 */

/** 运行状态，对应 OpenAPI `WorkflowRunStatus`。 */
export type WorkflowRunStatus =
  'PENDING' | 'RUNNING' | 'WAITING_APPROVAL' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';

/** 节点运行状态，对应 OpenAPI `WorkflowNodeStatus`。 */
export type WorkflowNodeStatus =
  'PENDING' | 'RUNNING' | 'WAITING_APPROVAL' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED' | 'CANCELLED';

/** 审批状态，对应 OpenAPI `WorkflowApprovalStatus`。 */
export type WorkflowApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED';

/** 结果保存状态，对应 OpenAPI `WorkflowResultStatus`。 */
export type WorkflowResultStatus = 'NONE' | 'SAVED' | 'OVER_LIMIT';

/** 结果格式，对应 OpenAPI `WorkflowResult.format`。 */
export type WorkflowResultFormat = 'TEXT' | 'MARKDOWN' | 'JSON';

/**
 * 运行/节点异步稳定错误码，对应 OpenAPI `WorkflowErrorCode`。
 *
 * 前端只做中文兜底展示，不根据错误码推断未返回的内部状态；HTTP 层错误仍使用
 * `RICCON-WF-400/409/429/503`，由 `ApiError.status/code` 判断。
 */
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
 * 数据范围摘要，对应 OpenAPI `WorkflowDataScopeSummary`。
 *
 * 只含范围/部门/引用计数，不含文档标题、片段或正文；审批人需按 M05 ACL 自行访问。
 */
export interface WorkflowDataScopeSummary {
  runScope: WorkflowRunScope;
  departmentId?: string | null;
  documentReferenceCount: number;
}

/** 审批人解析方式，对应 OpenAPI `WorkflowApprovalAssigneeType`。 */
export type WorkflowApprovalAssigneeType = 'USER' | 'ROLE';

/** 审批记录，对应 OpenAPI `WorkflowApproval`。 */
export interface WorkflowRunApproval {
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
  /**
   * 脱敏参数摘要；仅当前账号被指派且非运行发起人时由服务端返回。
   *
   * 页面不得为其他审批推断参数，也不得展示完整工具参数或凭据。
   */
  paramsPreview?: Record<string, unknown> | null;
  planSha256: string;
  requestedBy: string;
  decidedBy?: string | null;
  decidedAt?: string | null;
  decisionComment?: string | null;
  createdAt: string;
}

/**
 * 节点运行时间线条目，对应 OpenAPI `WorkflowNodeRun`。
 *
 * 同一节点的每次重试都是一条独立记录（`attempt` 递增）；`inputSummary`/`outputSummary`
 * 由服务端生成，不包含完整提示词或工具参数。
 */
export interface WorkflowNodeRun {
  id: string;
  nodeId: string;
  nodeType: WorkflowNodeType;
  name: string;
  status: WorkflowNodeStatus;
  attempt: number;
  startedAt?: string | null;
  finishedAt?: string | null;
  durationMs?: number | null;
  inputSummary?: string | null;
  outputSummary?: string | null;
  errorCode?: WorkflowErrorCode | null;
  errorMessage?: string | null;
  approvalId?: string | null;
  invocationId?: string | null;
}

/** 最终结果，对应 OpenAPI `WorkflowResult`。 */
export interface WorkflowResult {
  format: WorkflowResultFormat;
  /** 结果文本；超过部署上限时不保存（`resultStatus=OVER_LIMIT`）。 */
  text?: string | null;
  sizeBytes: number;
  generatedAt?: string | null;
}

/** 运行详情，对应 OpenAPI `WorkflowRunDetail`。 */
export interface WorkflowRunDetail {
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
  /** 轮询判据；变化才重绘。 */
  updatedAt: string;
  pendingApprovalCount: number;
  approvedApprovalCount: number;
  rejectedApprovalCount: number;
  retentionExpiresAt: string;
  nodes?: WorkflowNodeRun[];
  approvals?: WorkflowRunApproval[];
  citations?: Citation[];
  /** 最终结果；非成功或无结果时为空。 */
  result?: WorkflowResult | null;
}

/** 页面依赖的运行详情 API 能力集合；测试注入替身，页面不直接依赖 fetch。 */
export interface WorkflowRunApi {
  /** 读取运行详情；轮询与手动刷新共用。 */
  getRunDetail(runId: string, options?: { signal?: AbortSignal }): Promise<WorkflowRunDetail>;
}

/** 构造参数；测试可注入 fetch。 */
export interface WorkflowRunApiOptions {
  baseUrl?: string;
  fetchImpl?: typeof fetch;
}

/**
 * 构造运行详情 API 客户端。
 *
 * 只读端点不发送 CSRF 令牌；401/403/404/503 原样抛出 `ApiError`，由页面按固定中文文案
 * 区分“登录失效/无权限/不可见/编排关闭”，不吞掉业务状态码。
 *
 * @param options 基础地址与 fetch
 * @returns WorkflowRunApi 实现
 */
export function createWorkflowRunApi(options: WorkflowRunApiOptions = {}): WorkflowRunApi {
  const baseUrl = (options.baseUrl ?? API_BASE_URL).replace(/\/$/, '');
  const fetchImpl = options.fetchImpl ?? globalThis.fetch.bind(globalThis);

  return {
    async getRunDetail(runId, requestOptions = {}) {
      const response = await fetchImpl(
        `${baseUrl}/api/v1/workflow-runs/${encodeURIComponent(runId)}`,
        {
          method: 'GET',
          headers: { Accept: 'application/json' },
          credentials: 'same-origin',
          signal: requestOptions.signal,
        },
      );
      if (!response.ok) {
        throw await toApiError(response);
      }
      return (await response.json()) as WorkflowRunDetail;
    },
  };
}

/** 应用默认运行详情 API；同源部署、真实 fetch，不包含演示分支。 */
export const workflowRunApi: WorkflowRunApi = createWorkflowRunApi();
