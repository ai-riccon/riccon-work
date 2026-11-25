import { authStore } from '@/services/auth/auth-store';
import { API_BASE_URL } from '@/services/api/config';
import { toApiError } from '@/services/api/errors';
import type { Page } from '@/services/api/types';

/**
 * M06 模板库前端契约类型与 API 客户端。
 *
 * 类型来源：`contracts/openapi.yaml` 的 `WorkflowTemplate`、`WorkflowTemplatePage`、
 * `WorkflowTemplateCopyRequest` 与 `WorkflowDefinitionDetail`（复制响应）；只声明页面实际消费的字段。
 *
 * 为什么放在模板页面目录而不是复用 `pages/workflow/list/workflow-api.ts`：TASK-0235 的写入范围只包含
 * `apps/web/src/pages/workflow/templates/**`，T10 的列表/编辑器客户端已验收。这里保持独立客户端，
 * 由页面注入替身测试；后续若把 workflow 服务迁入 `src/services/workflow/**`，应保持本文件导出的函数
 * 签名不变，避免两套契约漂移。
 */

/** 模板来源，对应 OpenAPI `WorkflowTemplateSource`。 */
export type WorkflowTemplateSource = 'BUILT_IN' | 'TENANT';

/** 模板可见范围，对应 OpenAPI `WorkflowTemplateVisibility`。 */
export type WorkflowTemplateVisibility = 'BUILT_IN' | 'PRIVATE' | 'TENANT';

/** 模板列表项，对应 OpenAPI `WorkflowTemplate`。 */
export interface WorkflowTemplate {
  id: string;
  name: string;
  scenario: string;
  description?: string | null;
  source: WorkflowTemplateSource;
  visibility: WorkflowTemplateVisibility;
  ownerUserId?: string | null;
  builtInVersion?: number | null;
  latestBuiltInVersion?: number | null;
  /** M06 恒为 false；升级提示由 M06b 计算（AC-25）。 */
  upgradeAvailable: boolean;
  updatedAt: string;
}

/** 模板分页，对应 OpenAPI `WorkflowTemplatePage`。 */
export type WorkflowTemplatePage = Page<WorkflowTemplate>;

/** 列表查询参数；名称与场景为包含过滤，空值不发送。 */
export interface ListWorkflowTemplatesInput {
  name?: string;
  scenario?: string;
  page?: number;
  size?: number;
}

/** 复制请求，对应 OpenAPI `WorkflowTemplateCopyRequest`。 */
export interface WorkflowTemplateCopyRequest {
  name?: string;
}

/**
 * 复制结果的最小投影。
 *
 * 服务端返回完整的 `WorkflowDefinitionDetail`；页面复制后只需要跳转编辑器，因此只保留 ID 与名称，
 * 避免模板页依赖 T10 编辑器的完整详情类型。
 */
export interface WorkflowTemplateCopyResult {
  id: string;
  name: string;
}

/** 页面依赖的模板 API 能力集合；测试注入替身，页面不直接依赖 fetch。 */
export interface WorkflowTemplateApi {
  listTemplates(input?: ListWorkflowTemplatesInput): Promise<WorkflowTemplatePage>;
  copyTemplate(
    templateId: string,
    input?: WorkflowTemplateCopyRequest,
  ): Promise<WorkflowTemplateCopyResult>;
}

/** 构造参数；测试可注入 fetch、基础地址与固定 CSRF 令牌。 */
export interface WorkflowTemplateApiOptions {
  baseUrl?: string;
  fetchImpl?: typeof fetch;
  /** CSRF 令牌读取；默认从会话或 `/auth/csrf` 获取。 */
  csrfToken?: () => string | Promise<string>;
}

/** 普通 JSON 请求选项。 */
interface RequestOptions {
  method?: 'GET' | 'POST';
  body?: unknown;
}

/**
 * 判断 403 是否由 CSRF 令牌失效导致。
 *
 * 与 `services/api/client.ts`、T10 编排客户端的口径一致：使用 clone 读取，不破坏后续错误解析。
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
 * 构造模板 API 客户端。
 *
 * @param options 基础地址、fetch 与 CSRF 读取
 * @returns WorkflowTemplateApi 实现
 */
export function createWorkflowTemplateApi(
  options: WorkflowTemplateApiOptions = {},
): WorkflowTemplateApi {
  const baseUrl = (options.baseUrl ?? API_BASE_URL).replace(/\/$/, '');
  const fetchImpl = options.fetchImpl ?? globalThis.fetch.bind(globalThis);
  let cachedToken: string | null = null;

  /**
   * 读取 CSRF 令牌。
   *
   * 已登录会话优先使用内存中的令牌；未恢复会话时回退到 `/auth/csrf`。令牌不写 Cookie 或 localStorage。
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
   * 执行 JSON 请求，并在 CSRF 失效时刷新令牌重试一次。
   *
   * @param path 以 `/` 开头的 API 路径
   * @param options 方法与请求体
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
    listTemplates(input = {}) {
      const params = new URLSearchParams({
        page: String(input.page ?? 1),
        size: String(input.size ?? 20),
      });
      const name = input.name?.trim();
      const scenario = input.scenario?.trim();
      if (name) {
        params.set('name', name);
      }
      if (scenario) {
        params.set('scenario', scenario);
      }
      return request<WorkflowTemplatePage>(`/api/v1/workflow-templates?${params.toString()}`);
    },

    copyTemplate(templateId, input) {
      return request<WorkflowTemplateCopyResult>(
        `/api/v1/workflow-templates/${encodeURIComponent(templateId)}/copy`,
        { method: 'POST', body: input ?? {} },
      );
    },
  };
}

/** 应用默认模板 API；同源部署、真实 fetch，不包含演示分支。 */
export const templateApi: WorkflowTemplateApi = createWorkflowTemplateApi();
