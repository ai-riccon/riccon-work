/**
 * OIDC 登录失败原因码的界面文案。
 *
 * 原因码来自后端 302 `/login?oidcError=<code>`，是稳定、面向 UI 的枚举；
 * 本模块只做白名单映射，不回显 IdP 原始错误、code/state/token、内部地址或堆栈。
 * 未知值统一显示通用文案，避免把查询参数原样渲染到页面。
 */

/** 后端稳定原因码（ADR-0019 第 4 节）。 */
export type OidcErrorCode =
  | 'idp_unavailable'
  | 'state_invalid'
  | 'access_denied'
  | 'account_not_bound'
  | 'account_disabled'
  | 'tenant_unavailable'
  | 'issuer_mismatch'
  | 'rate_limited'
  | 'login_failed';

/** 登录页错误提示；`type` 直接对应 antd Alert 的语义色。 */
export interface OidcErrorPresentation {
  code: OidcErrorCode | 'unknown';
  title: string;
  description: string;
  /** 面向用户建议的下一步，不包含内部细节。 */
  nextStep: string;
  type: 'error' | 'warning' | 'info';
}

const PRESENTATIONS: Record<OidcErrorCode, OidcErrorPresentation> = {
  idp_unavailable: {
    code: 'idp_unavailable',
    title: '企业账号服务暂时不可用',
    description: '暂时无法连接企业身份服务，未建立登录会话。',
    nextStep: '请稍后重试，或改用内置账号登录。',
    type: 'error',
  },
  state_invalid: {
    code: 'state_invalid',
    title: '企业账号登录已失效',
    description: '本次登录请求已过期或已被使用，未建立登录会话。',
    nextStep: '请重新发起企业账号登录，或改用内置账号登录。',
    type: 'warning',
  },
  access_denied: {
    code: 'access_denied',
    title: '企业账号授权已取消',
    description: '你在企业身份服务中取消了授权，未建立登录会话。',
    nextStep: '可以重新尝试企业账号登录，或改用内置账号登录。',
    type: 'warning',
  },
  account_not_bound: {
    code: 'account_not_bound',
    title: '账号尚未关联企业身份',
    description: '当前企业身份还没有与任何 Riccon 账号绑定。',
    nextStep: '请联系管理员关联账号后再试；系统不会按邮箱自动绑定。',
    type: 'error',
  },
  account_disabled: {
    code: 'account_disabled',
    title: '账号已被禁用',
    description: '该账号当前不可登录，企业身份认证未建立会话。',
    nextStep: '请联系管理员恢复账号后再试。',
    type: 'error',
  },
  tenant_unavailable: {
    code: 'tenant_unavailable',
    title: '租户当前不可用',
    description: '目标租户不存在、已暂停或未开通企业账号登录。',
    nextStep: '请确认租户代码，或联系管理员检查租户状态。',
    type: 'error',
  },
  issuer_mismatch: {
    code: 'issuer_mismatch',
    title: '企业账号配置不匹配',
    description: '企业身份服务与当前租户的配置不一致，未建立登录会话。',
    nextStep: '请联系管理员检查身份提供方配置后再试。',
    type: 'error',
  },
  rate_limited: {
    code: 'rate_limited',
    title: '尝试次数过多',
    description: '企业账号登录尝试过于频繁，请稍后再试。',
    nextStep: '请等待一段时间后重试，或改用内置账号登录。',
    type: 'warning',
  },
  login_failed: {
    code: 'login_failed',
    title: '企业账号登录未完成',
    description: '登录过程未完成，未建立登录会话。',
    nextStep: '请稍后重试，或改用内置账号登录。',
    type: 'error',
  },
};

/** 未知原因码的通用文案；不包含原始查询参数。 */
const UNKNOWN: OidcErrorPresentation = {
  code: 'unknown',
  title: '企业账号登录未完成',
  description: '登录过程未完成，未建立登录会话。',
  nextStep: '请稍后重试，或改用内置账号登录。',
  type: 'error',
};

/**
 * 把 `oidcError` 查询参数映射为可读提示。
 *
 * @param code 后端原因码或任意查询值
 * @returns 提示；未提供时返回 null
 */
export function describeOidcLoginError(
  code: string | null | undefined,
): OidcErrorPresentation | null {
  if (!code) {
    return null;
  }
  return Object.prototype.hasOwnProperty.call(PRESENTATIONS, code)
    ? PRESENTATIONS[code as OidcErrorCode]
    : UNKNOWN;
}
