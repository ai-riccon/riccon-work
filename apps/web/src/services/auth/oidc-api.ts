import { ApiError, toApiError } from '@/services/api/errors';
import type { OidcProviderAvailability } from '@/services/api/types';

/** 与 globalThis.fetch 兼容的函数类型；测试可注入替身。 */
export type OidcFetchLike = typeof fetch;

/** 企业账号登录 API 客户端构造参数。 */
export interface OidcAuthClientOptions {
  /** API 基础地址；空字符串表示同源。 */
  baseUrl?: string;
  /** fetch 实现；默认使用 globalThis.fetch。 */
  fetchImpl?: OidcFetchLike;
}

/** 登录页需要的最小 OIDC 能力；页面只依赖该接口，测试注入替身。 */
export interface OidcAuthApi {
  /** 查询租户是否开通企业账号登录；未知租户统一 enabled=false。 */
  getProvider(tenantCode: string): Promise<OidcProviderAvailability>;
  /** 构造顶层授权入口地址；不发起请求。 */
  authorizeUrl(tenantCode: string): string;
}

/** 租户代码格式；与 OpenAPI `tenantCode` 参数及后端校验一致。 */
const TENANT_CODE = /^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/;

/**
 * 企业账号登录 API 客户端。
 *
 * 只调用匿名端点：providers 查询与授权入口。不读取、不保存 token；
 * 授权入口必须由浏览器顶层导航，避免在 SPA 内丢失服务端会话中的 state/nonce/PKCE。
 * 不包含 mock 或“演示成功”分支。
 */
export class OidcAuthClient implements OidcAuthApi {
  private readonly baseUrl: string;

  private readonly fetchImpl: OidcFetchLike;

  /**
   * 创建客户端。
   *
   * @param options 基础地址与 fetch 实现
   */
  constructor(options: OidcAuthClientOptions = {}) {
    this.baseUrl = (options.baseUrl ?? '').replace(/\/$/, '');
    this.fetchImpl = options.fetchImpl ?? globalThis.fetch.bind(globalThis);
  }

  /**
   * 查询租户企业账号登录可用性。
   *
   * @param tenantCode 租户代码
   * @returns 可用性；未知租户、未配置与已停用统一为 enabled=false
   */
  async getProvider(tenantCode: string): Promise<OidcProviderAvailability> {
    if (!TENANT_CODE.test(tenantCode)) {
      throw new ApiError('租户代码格式不正确。', {
        status: 400,
        code: 'RICCON-CLIENT-INVALID-TENANT',
        retryable: false,
      });
    }
    const query = new URLSearchParams({ tenantCode });
    try {
      const response = await this.fetchImpl(
        `${this.baseUrl}/api/v1/auth/oidc/providers?${query.toString()}`,
        {
          method: 'GET',
          headers: { Accept: 'application/json' },
          credentials: 'same-origin',
        },
      );
      if (!response.ok) {
        throw await toApiError(response);
      }
      return (await response.json()) as OidcProviderAvailability;
    } catch (error) {
      if (error instanceof ApiError) {
        throw error;
      }
      throw ApiError.network(error);
    }
  }

  /**
   * 构造企业账号授权入口。
   *
   * registrationId 固定为 `tenant-{tenantCode}`，与 ADR-0019 第 2 节一致；
   * 地址必须是绝对/同源 URL 并由顶层导航打开，不能作为 XHR/fetch 调用。
   *
   * @param tenantCode 租户代码
   * @returns 授权入口地址
   */
  authorizeUrl(tenantCode: string): string {
    if (!TENANT_CODE.test(tenantCode)) {
      throw new ApiError('租户代码格式不正确。', {
        status: 400,
        code: 'RICCON-CLIENT-INVALID-TENANT',
        retryable: false,
      });
    }
    return `${this.baseUrl}/api/v1/auth/oidc/authorize/tenant-${encodeURIComponent(tenantCode)}`;
  }
}

/**
 * 把 providers 查询失败映射为不泄露租户存在性的文案。
 *
 * @param error 任意异常
 * @returns 面向用户的中文提示
 */
export function describeOidcProviderError(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return '暂时无法查询企业账号登录状态，请稍后重试。';
  }
  if (error.status === 0) {
    return '无法连接服务器，请检查网络后重试。';
  }
  if (error.status === 429) {
    return '查询过于频繁，请稍后再试。';
  }
  if (error.status === 400) {
    return '租户代码格式不正确，请检查后重试。';
  }
  if (error.status >= 500) {
    return '企业账号登录状态暂时不可用，请稍后重试或使用内置账号登录。';
  }
  return '暂时无法查询企业账号登录状态，请稍后重试。';
}
