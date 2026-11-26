import { API_BASE_URL } from '@/services/api/config';
import { toApiError } from '@/services/api/errors';

/**
 * M06 运行限制只读前端契约类型与 API 客户端。
 *
 * 类型来源：`contracts/openapi.yaml`（`1.6.0-m06`）的 `WorkflowRuntimeLimits`；
 * 只声明页面实际消费的字段。限制值由部署配置（`RICCON_WORKFLOW_*`）与租户保留策略决定，
 * 租户只能通过 `GET /workflows/runtime-limits` 读取，不能修改。
 *
 * 为什么放在 `components/workflow/**`：TASK-0251 的写入范围只包含
 * `apps/web/src/pages/workflow/**` 与 `apps/web/src/components/workflow/**`；该能力被
 * Studio 子导航（帮助入口）与运行历史/编辑器共用，放在组件目录可避免页面之间复制契约。
 */

/** 节点超时分组（秒），对应 OpenAPI `WorkflowRuntimeLimits.nodeTimeoutSeconds`。 */
export interface WorkflowNodeTimeoutSeconds {
  /** 模型节点超时 */
  model: number;
  /** 检索节点超时 */
  retrieval: number;
  /** MCP 工具节点超时 */
  mcp: number;
}

/** 运行保留策略来源，对应 OpenAPI `WorkflowRetentionPolicy`。 */
export type WorkflowRetentionPolicy = 'DEPLOYMENT_DEFAULT' | 'TENANT_POLICY';

/**
 * 当前租户生效的编排运行限制，对应 OpenAPI `WorkflowRuntimeLimits`。
 *
 * `disabledReason` 只在 `featureEnabled=false` 时返回；开启状态不返回历史关闭原因，
 * 避免界面误显示“当前仍关闭”。
 */
export interface WorkflowRuntimeLimits {
  /** 编排总开关；关闭时新运行/重试/审批决定 503，历史仍可读。 */
  featureEnabled: boolean;
  /** 关闭原因；开启时为空。 */
  disabledReason?: string | null;
  /** 单流程节点上限 */
  maxNodes: number;
  /** 单运行节点尝试步数上限 */
  maxSteps: number;
  /** 节点超时分组 */
  nodeTimeoutSeconds: WorkflowNodeTimeoutSeconds;
  /** 运行执行超时（秒，审批等待不计） */
  runTimeoutSeconds: number;
  /** 租户运行准入并发（含等待审批） */
  tenantConcurrency: number;
  /** 单流程运行准入并发 */
  workflowConcurrency: number;
  /** 单次运行输出与结果上限（字节） */
  resultMaxBytes: number;
  /** 当前租户生效保留天数 */
  retentionDays: number;
  /** 保留天数来源 */
  retentionPolicy: WorkflowRetentionPolicy;
  /** 生成时间（带偏移的 ISO 8601） */
  generatedAt: string;
}

/** 页面依赖的运行限制读取能力；测试注入替身，页面不直接依赖 fetch。 */
export interface WorkflowRuntimeLimitsApi {
  /** 读取当前租户的生效限制；只读端点不发送 CSRF。 */
  getRuntimeLimits(options?: { signal?: AbortSignal }): Promise<WorkflowRuntimeLimits>;
}

/** 构造参数；测试可注入基础地址与 fetch。 */
export interface WorkflowRuntimeLimitsApiOptions {
  baseUrl?: string;
  fetchImpl?: typeof fetch;
}

/**
 * 构造运行限制 API 客户端。
 *
 * 只读端点使用 GET，不发送 CSRF 令牌；401/403 原样抛出 `ApiError`，由界面按
 * “登录失效/无权限”给出固定中文说明，不吞掉业务状态码。
 *
 * @param options 基础地址与 fetch
 * @returns WorkflowRuntimeLimitsApi 实现
 */
export function createWorkflowRuntimeLimitsApi(
  options: WorkflowRuntimeLimitsApiOptions = {},
): WorkflowRuntimeLimitsApi {
  const baseUrl = (options.baseUrl ?? API_BASE_URL).replace(/\/$/, '');
  const fetchImpl = options.fetchImpl ?? globalThis.fetch.bind(globalThis);

  return {
    async getRuntimeLimits(requestOptions = {}) {
      const response = await fetchImpl(`${baseUrl}/api/v1/workflows/runtime-limits`, {
        method: 'GET',
        headers: { Accept: 'application/json' },
        credentials: 'same-origin',
        signal: requestOptions.signal,
      });
      if (!response.ok) {
        throw await toApiError(response);
      }
      return (await response.json()) as WorkflowRuntimeLimits;
    },
  };
}

/** 应用默认运行限制 API；同源部署、真实 fetch，不包含演示分支。 */
export const workflowRuntimeLimitsApi: WorkflowRuntimeLimitsApi = createWorkflowRuntimeLimitsApi();
