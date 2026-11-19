import { ApiError, toApiError } from '@/services/api/errors';
import type { OidcBindingRequest, OidcUnbindRequest, UserSummary } from '@/services/api/types';
import type { UserPage } from './types';

/** 与 globalThis.fetch 兼容的函数类型；测试可注入替身。 */
export type UserFetchLike = typeof fetch;

/** 用户管理 API 客户端构造参数。 */
export interface UserAdminClientOptions {
  /** API 基础地址；空字符串表示同源。 */
  baseUrl?: string;
  /** fetch 实现；默认使用 globalThis.fetch。 */
  fetchImpl?: UserFetchLike;
  /** 从当前会话读取 CSRF 令牌；未登录时返回 null。 */
  csrfTokenProvider?: () => string | null;
  /** 任意带登录态请求返回 401 时的回调。 */
  onUnauthorized?: () => void;
}

/**
 * 租户管理员账号管理能力。
 *
 * 只操作当前登录租户；租户范围由服务端从会话派生，界面不提供 tenantId。
 */
export interface TenantUserAdminApi {
  /** 列出租户账号；管理端需要认证来源与状态。 */
  listUsers(): Promise<UserPage>;
  /** 把账号显式绑定到 OIDC 身份；成功后本地密码被删除。 */
  bindOidc(userId: string, input: OidcBindingRequest): Promise<UserSummary>;
  /** 解除 OIDC 绑定并设置新的本地密码。 */
  unbindOidc(userId: string, input: OidcUnbindRequest): Promise<UserSummary>;
}

/**
 * 平台管理员目标租户账号管理能力。
 *
 * 目标租户来自路径参数；只读取账号元数据，不读取业务内容。
 */
export interface PlatformUserAdminApi {
  /** 列出目标租户账号。 */
  listTenantUsers(tenantId: string): Promise<UserPage>;
  /** 平台管理员绑定目标租户账号。 */
  bindTenantUserOidc(
    tenantId: string,
    userId: string,
    input: OidcBindingRequest,
  ): Promise<UserSummary>;
  /** 平台管理员解除目标租户账号绑定并恢复本地密码。 */
  unbindTenantUserOidc(
    tenantId: string,
    userId: string,
    input: OidcUnbindRequest,
  ): Promise<UserSummary>;
}

/** 页面依赖的完整用户管理能力；测试可只实现实际使用的方法。 */
export interface UserAdminApi extends TenantUserAdminApi, PlatformUserAdminApi {}

/** 普通 JSON 请求选项。 */
interface UserRequestOptions {
  method?: 'GET' | 'PUT' | 'POST';
  body?: unknown;
}

/**
 * M10 用户管理 API 客户端。
 *
 * 为什么不直接扩展 `services/api/client.ts`：该文件属于 J1/J2 页面共享的既有客户端，
 * B4 写范围只允许在其必须时做联合登出响应头捕获；用户管理端点独立成客户端，
 * 复用统一 `ApiError` 归一与 CSRF 重试语义，不包含 mock 或演示成功分支。
 */
export class UserAdminClient implements UserAdminApi {
  private readonly baseUrl: string;

  private readonly fetchImpl: UserFetchLike;

  private readonly csrfTokenProvider?: () => string | null;

  private readonly onUnauthorized?: () => void;

  private csrfToken: string | null = null;

  /**
   * 创建用户管理客户端。
   *
   * @param options 基础地址、fetch、CSRF 与会话过期回调
   */
  constructor(options: UserAdminClientOptions = {}) {
    this.baseUrl = (options.baseUrl ?? '').replace(/\/$/, '');
    this.fetchImpl = options.fetchImpl ?? globalThis.fetch.bind(globalThis);
    this.csrfTokenProvider = options.csrfTokenProvider;
    this.onUnauthorized = options.onUnauthorized;
  }

  /**
   * 列出租户账号。
   *
   * @returns 账号分页，取前 100 个
   */
  listUsers(): Promise<UserPage> {
    return this.request<UserPage>('/api/v1/users?page=1&size=100');
  }

  /**
   * 把租户账号显式绑定到 OIDC 身份。
   *
   * @param userId 目标账号 ID
   * @param input issuer 与 subject
   * @returns 更新后的账号
   */
  bindOidc(userId: string, input: OidcBindingRequest): Promise<UserSummary> {
    return this.request<UserSummary>(`/api/v1/users/${encodeURIComponent(userId)}/oidc-binding`, {
      method: 'PUT',
      body: input,
    });
  }

  /**
   * 解除绑定并恢复本地密码。
   *
   * @param userId 目标账号 ID
   * @param input 新本地密码
   * @returns 更新后的账号
   */
  unbindOidc(userId: string, input: OidcUnbindRequest): Promise<UserSummary> {
    return this.request<UserSummary>(
      `/api/v1/users/${encodeURIComponent(userId)}/oidc-binding/remove`,
      { method: 'POST', body: input },
    );
  }

  /**
   * 平台管理员列出目标租户账号。
   *
   * @param tenantId 目标租户 ID
   * @returns 账号分页，取前 100 个
   */
  listTenantUsers(tenantId: string): Promise<UserPage> {
    return this.request<UserPage>(
      `/api/v1/admin/tenants/${encodeURIComponent(tenantId)}/users?page=1&size=100`,
    );
  }

  /**
   * 平台管理员绑定目标租户账号。
   *
   * @param tenantId 目标租户 ID
   * @param userId 目标账号 ID
   * @param input issuer 与 subject
   * @returns 更新后的账号
   */
  bindTenantUserOidc(
    tenantId: string,
    userId: string,
    input: OidcBindingRequest,
  ): Promise<UserSummary> {
    return this.request<UserSummary>(
      `/api/v1/admin/tenants/${encodeURIComponent(tenantId)}/users/${encodeURIComponent(
        userId,
      )}/oidc-binding`,
      { method: 'PUT', body: input },
    );
  }

  /**
   * 平台管理员解除目标租户账号绑定。
   *
   * @param tenantId 目标租户 ID
   * @param userId 目标账号 ID
   * @param input 新本地密码
   * @returns 更新后的账号
   */
  unbindTenantUserOidc(
    tenantId: string,
    userId: string,
    input: OidcUnbindRequest,
  ): Promise<UserSummary> {
    return this.request<UserSummary>(
      `/api/v1/admin/tenants/${encodeURIComponent(tenantId)}/users/${encodeURIComponent(
        userId,
      )}/oidc-binding/remove`,
      { method: 'POST', body: input },
    );
  }

  /**
   * 执行请求并读取 JSON 响应。
   *
   * @param path API 路径
   * @param options 方法、请求体、鉴权与取消信号
   * @returns 解析后的响应体
   */
  private async request<T>(path: string, options: UserRequestOptions = {}): Promise<T> {
    const method = options.method ?? 'GET';
    try {
      const response = await this.performRequest(path, method, options, method !== 'GET');
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
   * @param method HTTP 方法
   * @param options 请求选项
   * @param unsafe 是否需要 CSRF（非 GET）
   * @returns fetch Response
   */
  private async performRequest(
    path: string,
    method: string,
    options: UserRequestOptions,
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
        method,
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

    const response = await doFetch(false);
    if (!response.ok) {
      throw await toApiError(response);
    }
    return response;
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
