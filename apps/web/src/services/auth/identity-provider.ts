import type { UserSummary } from '@/services/api/types';

/** 本地账号来源文案；LOCAL 没有外部 IdP。 */
export const LOCAL_IDENTITY_PROVIDER_LABEL = '本地账号';

/** 无法确定来源时的文案；不猜测具体 IdP。 */
export const UNKNOWN_IDENTITY_PROVIDER_LABEL = '未标注';

/**
 * 展示来源 IdP 所需的最小账号字段。
 *
 * 只依赖 `authSource` 与 `identityProvider`，当前会话与用户管理列表可以共用。
 */
export type IdentityProviderSubject = Pick<UserSummary, 'authSource' | 'identityProvider'>;

/**
 * 解析来源 IdP 展示文案。
 *
 * `identityProvider` 已经是服务端脱敏后的展示名：OIDC 为配置的 providerName 或 issuer host，
 * 前端只负责原样展示，不拼接完整 issuer、subject、token 或邮箱，也不根据邮箱推断来源。
 *
 * - LOCAL：固定显示“本地账号”；
 * - OIDC：显示服务端下发的 provider 名；缺省时显示“未标注”，避免把未知 IdP 误标成某个来源；
 * - 认证来源未知但服务端给了 provider 名：仍展示该脱敏值，不猜测认证类型。
 *
 * @param user 账号或当前会话用户
 * @returns 来源 IdP 展示文案
 */
export function identityProviderLabel(user: IdentityProviderSubject): string {
  if (user.authSource === 'LOCAL') {
    return LOCAL_IDENTITY_PROVIDER_LABEL;
  }
  const provider = user.identityProvider?.trim();
  if (provider) {
    return provider;
  }
  return UNKNOWN_IDENTITY_PROVIDER_LABEL;
}

/**
 * 生成带“来源：”前缀的完整文案。
 *
 * 会话/工作空间头部与用户管理统一使用该格式，保证两处文案一致。
 *
 * @param user 账号或当前会话用户
 * @returns 形如 `来源：Acme 企业账号` 的文案
 */
export function identityProviderSummary(user: IdentityProviderSubject): string {
  return `来源：${identityProviderLabel(user)}`;
}
