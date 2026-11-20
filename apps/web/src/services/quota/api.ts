import { ApiError, toApiError } from '@/services/api/errors';
import type {
  PlatformQuotaPage,
  QuotaPageQuery,
  QuotaUpdateRequest,
  TenantQuota,
  UserQuotaPage,
} from './types';

/** 与 globalThis.fetch 兼容的函数类型；测试可注入替身。 */
export type QuotaFetchLike = typeof fetch;

/** 配额 API 客户端构造参数。 */
export interface QuotaClientOptions {
  /** API 基础地址；空字符串表示同源。 */
  baseUrl?: string;
  /** fetch 实现；默认使用 globalThis.fetch。 */
  fetchImpl?: QuotaFetchLike;
  /** 从当前会话读取 CSRF 令牌；未登录时返回 null。 */
  csrfTokenProvider?: () => string | null;
  /** 任意带登录态请求返回 401 时的回调。 */
  onUnauthorized?: () => void;
}

/**
 * M04 配额读取与写入能力。
 *
 * 平台管理员使用目录与整体替换方法；租户管理员只使用本租户读取方法。
 * 页面只依赖该接口，组件测试注入替身。
 */
export interface QuotaApi {
  /** 读取本租户日/月限额、已用与重置时间（`quota.read`）。 */
  getTenantQuota(): Promise<TenantQuota>;
  /** 分页读取本租户用户配额（`quota.read`）。 */
  listTenantUserQuotas(query?: QuotaPageQuery): Promise<UserQuotaPage>;
  /** 平台跨租户配额聚合，只含租户维度、不含用户明细（`platform.quota.manage`）。 */
  listPlatformQuotas(query?: QuotaPageQuery): Promise<PlatformQuotaPage>;
  /** 整体替换目标租户日/月限额（`platform.quota.manage`）。 */
  updateTenantQuota(tenantId: string, input: QuotaUpdateRequest): Promise<TenantQuota>;
}

/** 普通 JSON 请求选项。 */
interface QuotaRequestOptions {
  method?: 'GET' | 'PUT';
  body?: unknown;
}

/**
 * M04 配额 API 客户端。
 *
 * 为什么不直接扩展 `services/api/client.ts`：该文件是 J1/J2 与管理端共享的既有客户端，
 * 本任务写范围不包含它；按 `AuditClient`/`UserAdminClient` 的既有模式独立成客户端，
 * 复用统一 `ApiError` 归一与 CSRF 刷新语义，不包含 mock 或演示成功分支。
 */
export class QuotaClient implements QuotaApi {
  private readonly baseUrl: string;

  private readonly fetchImpl: QuotaFetchLike;

  private readonly csrfTokenProvider?: () => string | null;

  private readonly onUnauthorized?: () => void;

  private csrfToken: string | null = null;

  /**
   * 创建配额客户端。
   *
   * @param options 基础地址、fetch、CSRF 与会话过期回调
   */
  constructor(options: QuotaClientOptions = {}) {
    this.baseUrl = (options.baseUrl ?? '').replace(/\/$/, '');
    this.fetchImpl = options.fetchImpl ?? globalThis.fetch.bind(globalThis);
    this.csrfTokenProvider = options.csrfTokenProvider;
    this.onUnauthorized = options.onUnauthorized;
  }

  /**
   * 读取本租户配额。
   *
   * @returns 本租户日/月窗口
   */
  getTenantQuota(): Promise<TenantQuota> {
    return this.request<TenantQuota>('/api/v1/quotas');
  }

  /**
   * 分页读取本租户用户配额。
   *
   * @param query 页码与每页大小
   * @returns 用户配额分页
   */
  listTenantUserQuotas(query: QuotaPageQuery = {}): Promise<UserQuotaPage> {
    return this.request<UserQuotaPage>(this.withQuery('/api/v1/quotas/users', query));
  }

  /**
   * 平台跨租户配额聚合。
   *
   * @param query 页码与每页大小
   * @returns 租户聚合分页
   */
  listPlatformQuotas(query: QuotaPageQuery = {}): Promise<PlatformQuotaPage> {
    return this.request<PlatformQuotaPage>(this.withQuery('/api/v1/admin/quotas', query));
  }

  /**
   * 整体替换目标租户日/月限额。
   *
   * 请求体固定携带 `daily` 与 `monthly` 两个键，值可为 null（明确不限）；
   * 服务端在同一事务写 `quota.limit.changed`，审计失败则整体失败。
   *
   * @param tenantId 目标租户代码
   * @param input 日/月限额
   * @returns 更新后的租户配额
   */
  updateTenantQuota(tenantId: string, input: QuotaUpdateRequest): Promise<TenantQuota> {
    return this.request<TenantQuota>(
      `/api/v1/admin/tenants/${encodeURIComponent(tenantId)}/quotas`,
      { method: 'PUT', body: input },
    );
  }

  /**
   * 执行 JSON 请求并读取响应体。
   *
   * @param path API 路径
   * @param options 方法与请求体
   * @returns 解析后的响应体
   */
  private async request<T>(path: string, options: QuotaRequestOptions = {}): Promise<T> {
    const method = options.method ?? 'GET';
    try {
      const response = await this.performRequest(path, options, method === 'PUT');
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
   * @param unsafe 是否需要 CSRF（仅 PUT）
   * @returns fetch Response
   */
  private async performRequest(
    path: string,
    options: QuotaRequestOptions,
    unsafe: boolean,
  ): Promise<Response> {
    const doFetch = async (retried: boolean): Promise<Response> => {
      const headers: Record<string, string> = { Accept: 'application/json' };
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
   * 拼接查询参数；空值与空字符串不发送，避免服务端把空条件当成有效过滤。
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
