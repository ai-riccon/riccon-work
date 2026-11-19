import { ApiError, toApiError } from '@/services/api/errors';
import type {
  AuditEventPage,
  AuditEventQuery,
  AuditExportDownload,
  AuditExportListQuery,
  AuditExportRequest,
  AuditExportRequestPage,
  AuditExportDecisionRequest,
  AuditFilter,
  AuditPolicy,
} from './types';

/** 与 globalThis.fetch 兼容的函数类型；测试可注入替身。 */
export type AuditFetchLike = typeof fetch;

/** 审计 API 客户端构造参数。 */
export interface AuditClientOptions {
  /** API 基础地址；空字符串表示同源。 */
  baseUrl?: string;
  /** fetch 实现；默认使用 globalThis.fetch。 */
  fetchImpl?: AuditFetchLike;
  /** 从当前会话读取 CSRF 令牌；未登录时返回 null。 */
  csrfTokenProvider?: () => string | null;
  /** 任意带登录态请求返回 401 时的回调。 */
  onUnauthorized?: () => void;
}

/** 审计查询与导出能力；页面只依赖该接口，测试注入替身。 */
export interface AuditApi {
  /** 读取保留、分页、动作与导出限制。 */
  getPolicy(): Promise<AuditPolicy>;
  /** 查询审计事件；排序固定由服务端决定。 */
  listEvents(query: AuditEventQuery): Promise<AuditEventPage>;
  /** 列出本租户导出请求。 */
  listExports(query?: AuditExportListQuery): Promise<AuditExportRequestPage>;
  /** 发起导出申请；预计行数由服务端计算。 */
  createExport(filter: AuditFilter): Promise<AuditExportRequest>;
  /** 读取单个导出请求详情。 */
  getExport(exportId: string): Promise<AuditExportRequest>;
  /** 同租户审批导出请求。 */
  decideExport(exportId: string, input: AuditExportDecisionRequest): Promise<AuditExportRequest>;
  /** 下载 CSV；只有申请人本人且已批准未过期时成功。 */
  downloadExport(exportId: string): Promise<AuditExportDownload>;
  /** 平台管理员列出单管理员租户的导出申请元数据。 */
  listPlatformExports(
    tenantId: string,
    query?: AuditExportListQuery,
  ): Promise<AuditExportRequestPage>;
  /** 平台管理员审批单管理员租户的导出申请。 */
  decidePlatformExport(
    tenantId: string,
    exportId: string,
    input: AuditExportDecisionRequest,
  ): Promise<AuditExportRequest>;
}

/** 普通 JSON 请求选项。 */
interface AuditRequestOptions {
  method?: 'GET' | 'POST';
  body?: unknown;
  /** Accept 头；CSV 下载使用 `text/csv`。 */
  accept?: string;
}

/**
 * M10 审计 API 客户端。
 *
 * 为什么不直接复用 `services/api/client.ts`：该文件不在 B5 写范围内，
 * 且审计导出需要独立的 CSV 流与文件名解析。本客户端复用统一的
 * `ApiError` 归一与 CSRF 重试语义，不包含任何 mock 或演示成功分支。
 */
export class AuditClient implements AuditApi {
  private readonly baseUrl: string;

  private readonly fetchImpl: AuditFetchLike;

  private readonly csrfTokenProvider?: () => string | null;

  private readonly onUnauthorized?: () => void;

  private csrfToken: string | null = null;

  /**
   * 创建审计 API 客户端。
   *
   * @param options 基础地址、fetch、CSRF 与会话过期回调
   */
  constructor(options: AuditClientOptions = {}) {
    this.baseUrl = (options.baseUrl ?? '').replace(/\/$/, '');
    this.fetchImpl = options.fetchImpl ?? globalThis.fetch.bind(globalThis);
    this.csrfTokenProvider = options.csrfTokenProvider;
    this.onUnauthorized = options.onUnauthorized;
  }

  /**
   * 获取审计策略。
   *
   * @returns 审计策略
   */
  getPolicy(): Promise<AuditPolicy> {
    return this.request<AuditPolicy>('/api/v1/audit-events/policy');
  }

  /**
   * 查询审计事件。
   *
   * @param query 过滤与分页参数
   * @returns 审计分页
   */
  listEvents(query: AuditEventQuery): Promise<AuditEventPage> {
    return this.request<AuditEventPage>(this.withQuery('/api/v1/audit-events', query));
  }

  /**
   * 列出本租户导出请求。
   *
   * @param query 状态与分页参数
   * @returns 导出请求分页
   */
  listExports(query: AuditExportListQuery = {}): Promise<AuditExportRequestPage> {
    return this.request<AuditExportRequestPage>(this.withQuery('/api/v1/audit-exports', query));
  }

  /**
   * 发起导出申请。
   *
   * @param filter 查询条件
   * @returns 新建导出请求
   */
  createExport(filter: AuditFilter): Promise<AuditExportRequest> {
    return this.request<AuditExportRequest>('/api/v1/audit-exports', {
      method: 'POST',
      body: { filter },
    });
  }

  /**
   * 读取导出请求详情。
   *
   * @param exportId 导出请求 ID
   * @returns 导出请求
   */
  getExport(exportId: string): Promise<AuditExportRequest> {
    return this.request<AuditExportRequest>(
      `/api/v1/audit-exports/${encodeURIComponent(exportId)}`,
    );
  }

  /**
   * 同租户审批导出请求。
   *
   * @param exportId 导出请求 ID
   * @param input 审批决定与可选意见
   * @returns 审批后的导出请求
   */
  decideExport(exportId: string, input: AuditExportDecisionRequest): Promise<AuditExportRequest> {
    return this.request<AuditExportRequest>(
      `/api/v1/audit-exports/${encodeURIComponent(exportId)}/decision`,
      {
        method: 'POST',
        body: { decision: input.decision, comment: input.comment ?? null },
      },
    );
  }

  /**
   * 下载导出 CSV。
   *
   * 读取原始响应而不是 JSON；错误仍通过 `toApiError` 归一，页面可区分
   * 403/409/410 并给出下一步。
   *
   * @param exportId 导出请求 ID
   * @returns CSV Blob 与服务端文件名
   */
  async downloadExport(exportId: string): Promise<AuditExportDownload> {
    const path = `/api/v1/audit-exports/${encodeURIComponent(exportId)}/download`;
    let response: Response;
    try {
      response = await this.performRequest(path, { accept: 'text/csv' }, false);
      if (!response.ok) {
        throw await toApiError(response);
      }
    } catch (error) {
      if (error instanceof ApiError) {
        throw error;
      }
      throw ApiError.network(error);
    }
    const blob = await response.blob();
    return {
      blob,
      filename: parseFilename(response.headers.get('content-disposition'), exportId),
    };
  }

  /**
   * 平台管理员列出目标租户导出请求元数据。
   *
   * @param tenantId 目标租户 ID
   * @param query 状态与分页参数
   * @returns 导出请求分页
   */
  listPlatformExports(
    tenantId: string,
    query: AuditExportListQuery = {},
  ): Promise<AuditExportRequestPage> {
    return this.request<AuditExportRequestPage>(
      this.withQuery(`/api/v1/admin/tenants/${encodeURIComponent(tenantId)}/audit-exports`, query),
    );
  }

  /**
   * 平台管理员审批目标租户导出请求。
   *
   * @param tenantId 目标租户 ID
   * @param exportId 导出请求 ID
   * @param input 审批决定与可选意见
   * @returns 审批后的导出请求
   */
  decidePlatformExport(
    tenantId: string,
    exportId: string,
    input: AuditExportDecisionRequest,
  ): Promise<AuditExportRequest> {
    return this.request<AuditExportRequest>(
      `/api/v1/admin/tenants/${encodeURIComponent(tenantId)}/audit-exports/${encodeURIComponent(
        exportId,
      )}/decision`,
      {
        method: 'POST',
        body: { decision: input.decision, comment: input.comment ?? null },
      },
    );
  }

  /**
   * 执行 JSON 请求并读取响应体。
   *
   * @param path API 路径
   * @param options 方法与请求体
   * @returns 解析后的响应体
   */
  private async request<T>(path: string, options: AuditRequestOptions = {}): Promise<T> {
    const method = options.method ?? 'GET';
    try {
      const response = await this.performRequest(path, options, method === 'POST');
      if (!response.ok) {
        throw await toApiError(response);
      }
      return await readJson<T>(response);
    } catch (error) {
      if (error instanceof ApiError) {
        throw error;
      }
      throw ApiError.network(error);
    }
  }

  /**
   * 发起 fetch，并在 CSRF 令牌失效时刷新一次后重试。
   *
   * @param path API 路径
   * @param options 请求选项
   * @param unsafe 是否需要 CSRF（仅 POST）
   * @returns fetch Response
   */
  private async performRequest(
    path: string,
    options: AuditRequestOptions,
    unsafe: boolean,
  ): Promise<Response> {
    const doFetch = async (retried: boolean): Promise<Response> => {
      const headers: Record<string, string> = {
        Accept: options.accept ?? 'application/json',
      };
      if (unsafe) {
        headers['X-CSRF-TOKEN'] = await this.ensureCsrfToken(retried);
      }
      let body: string | undefined;
      if (options.body !== undefined) {
        headers['Content-Type'] = 'application/json';
        body = JSON.stringify(options.body);
      }

      const response = await this.fetchImpl(`${this.baseUrl}${path}`, {
        method: options.method ?? 'GET',
        headers,
        body,
        credentials: 'same-origin',
      });

      if (response.status === 401) {
        this.onUnauthorized?.();
      }

      // 令牌可能随会话轮换失效；只对写请求刷新一次，避免无限重试。
      if (unsafe && !retried && response.status === 403 && (await isCsrfError(response))) {
        this.csrfToken = null;
        return doFetch(true);
      }

      return response;
    };

    return doFetch(false);
  }

  /**
   * 获取 CSRF 令牌；优先使用当前会话令牌，失败后走 `/auth/csrf` 重新获取。
   *
   * @param forceRefresh 是否忽略会话令牌与本地缓存
   * @returns CSRF 令牌
   */
  private async ensureCsrfToken(forceRefresh = false): Promise<string> {
    if (!forceRefresh) {
      const fromSession = this.csrfTokenProvider?.();
      if (fromSession) {
        return fromSession;
      }
      if (this.csrfToken) {
        return this.csrfToken;
      }
    }

    const response = await this.fetchImpl(`${this.baseUrl}/api/v1/auth/csrf`, {
      method: 'GET',
      headers: { Accept: 'application/json' },
      credentials: 'same-origin',
    });
    if (!response.ok) {
      throw await toApiError(response);
    }
    const payload = (await response.json()) as { token: string };
    this.csrfToken = payload.token;
    return payload.token;
  }

  /**
   * 拼接查询参数；空值与空字符串不发送，避免服务端把空过滤当成有效条件。
   *
   * @param path API 路径
   * @param query 查询参数对象
   * @returns 带查询字符串的路径
   */
  private withQuery(path: string, query: object): string {
    const search = new URLSearchParams();
    Object.entries(query as Record<string, unknown>).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== '') {
        search.set(key, String(value));
      }
    });
    const serialized = search.toString();
    return serialized ? `${path}?${serialized}` : path;
  }
}

/**
 * 读取 JSON 响应；204 或空响应返回 undefined。
 *
 * @param response fetch 响应
 * @returns 解析后的 JSON
 */
async function readJson<T>(response: Response): Promise<T> {
  if (response.status === 204) {
    return undefined as T;
  }
  const text = await response.text();
  if (!text) {
    return undefined as T;
  }
  try {
    return JSON.parse(text) as T;
  } catch {
    throw new ApiError('服务端返回了无法识别的响应。', {
      status: response.status,
      code: 'RICCON-CLIENT-BAD-JSON',
    });
  }
}

/**
 * 从 Content-Disposition 解析下载文件名。
 *
 * @param header 响应头
 * @param exportId 导出请求 ID，用于兜底
 * @returns 安全文件名
 */
export function parseFilename(header: string | null, exportId: string): string {
  if (header) {
    const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(header);
    if (match?.[1]) {
      try {
        return decodeURIComponent(match[1]);
      } catch {
        return match[1];
      }
    }
  }
  return `audit-export-${exportId}.csv`;
}

/**
 * 判断 403 是否由 CSRF 令牌失效导致。
 *
 * 使用 clone 读取，保证原响应仍可交给 `toApiError`。
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
