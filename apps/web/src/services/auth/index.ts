import { API_BASE_URL } from '@/services/api/config';
import { OidcAuthClient } from './oidc-api';
import type { OidcAuthApi } from './oidc-api';

/**
 * 应用默认企业账号登录客户端。
 *
 * 只访问匿名端点，不持有 CSRF 或任何 token；授权入口由登录页顶层导航打开。
 * 不包含 mock 或演示分支，E2E 由独立 stub API 承担。
 */
export const oidcAuthApi: OidcAuthApi = new OidcAuthClient({
  baseUrl: API_BASE_URL,
  fetchImpl: globalThis.fetch.bind(globalThis),
});

export { OidcAuthClient, describeOidcProviderError } from './oidc-api';
export type { OidcAuthApi, OidcAuthClientOptions, OidcFetchLike } from './oidc-api';
export {
  LOCAL_IDENTITY_PROVIDER_LABEL,
  UNKNOWN_IDENTITY_PROVIDER_LABEL,
  identityProviderLabel,
  identityProviderSummary,
} from './identity-provider';
export type { IdentityProviderSubject } from './identity-provider';
export * from './oidc-errors';
export * from './federated-logout';
export { AuthStore, authStore, useAuth } from './auth-store';
export type { AuthReason, AuthSnapshot, AuthStatus, SessionReader } from './auth-store';
