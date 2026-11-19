/**
 * OIDC 联合登出的浏览器侧协作逻辑。
 *
 * 边界（ADR-0019 第 5 节、DEC-0017 A）：
 * 1. 本地登出由服务端先完成且是权威结果；本模块不调用 IdP，也不回滚本地登出；
 * 2. 服务端仅在“账号为 OIDC + 租户配置了 end-session”时返回
 *    `X-Riccon-Federated-Logout-Url`；前端只保存该绝对地址，不保存 token；
 * 3. 自动导航只尝试一次，IdP 不可达或浏览器阻止跳转时保留“已退出本地会话”提示与手动重试入口，
 *    且不把页面改写成 `federated=1`，避免声称企业会话已经结束。
 */

/** 服务端返回联合登出地址的响应头，与 OidcLogoutService 保持一致。 */
export const FEDERATED_LOGOUT_HEADER = 'X-Riccon-Federated-Logout-Url';

/** sessionStorage 中暂存联合登出地址的键；只存 URL，不含任何凭据。 */
export const FEDERATED_LOGOUT_URL_KEY = 'riccon.federated-logout-url';

/** 是否已经自动尝试过导航；用于浏览器后退时避免无限跳转。 */
export const FEDERATED_LOGOUT_ATTEMPTED_KEY = 'riccon.federated-logout-attempted';

/** 登录页“正在结束企业账号会话”的落地地址，与 ADR-0019 约定一致。 */
export const FEDERATED_LOGOUT_PENDING_PATH = '/login?loggedOut=1&federated=pending';

/** 顶层导航函数；测试可注入替身，避免 jsdom 真实跳转。 */
export type TopLevelNavigator = (url: string) => void;

/** 登录页需要区分的退出落地状态。 */
export interface LogoutLandingState {
  /** URL 带 `loggedOut=1`。 */
  loggedOut: boolean;
  /**
   * 联合登出进度：
   * - `pending`：本地已退出，正在（或曾经尝试）前往 IdP 结束企业会话；
   * - `done`：IdP 已回跳并确认企业会话结束；
   * - null：仅本地退出，未经过联合登出回跳。
   */
  federated: 'pending' | 'done' | null;
}

/**
 * 判断服务端下发的联合登出地址是否安全。
 *
 * 只接受绝对 http(s) 地址；生产要求 HTTPS，HTTP 仅允许本机回环地址，
 * 避免响应头被污染时把用户带到 `javascript:` 或其他协议。
 *
 * @param raw 响应头原文
 * @returns 是否允许顶层导航
 */
export function isSafeFederatedLogoutUrl(raw: string): boolean {
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return false;
  }
  if (url.protocol === 'https:') {
    return url.hostname.length > 0;
  }
  if (url.protocol !== 'http:') {
    return false;
  }
  return ['localhost', '127.0.0.1', '[::1]', '::1'].includes(url.hostname);
}

/**
 * 读取 sessionStorage；隐私模式或测试环境不可用时退化为不记忆状态。
 *
 * @returns Storage 或 null
 */
function sessionStore(): Storage | null {
  try {
    return typeof globalThis.sessionStorage === 'undefined' ? null : globalThis.sessionStorage;
  } catch {
    // 部分浏览器在禁用存储时访问 sessionStorage 会抛异常；退化为仅当前页面内提示，不影响登出结果。
    return null;
  }
}

/**
 * 暂存联合登出地址，并重置“已尝试”标记。
 *
 * 新的一次登出必须重新允许自动导航；旧标记只用于同一次落地的后退保护。
 *
 * @param raw 服务端响应头
 * @returns 是否成功保存（地址不安全或存储不可用时为 false）
 */
export function rememberFederatedLogoutUrl(raw: string | null | undefined): boolean {
  if (!raw || !isSafeFederatedLogoutUrl(raw)) {
    return false;
  }
  const store = sessionStore();
  if (!store) {
    return false;
  }
  store.setItem(FEDERATED_LOGOUT_URL_KEY, raw);
  store.removeItem(FEDERATED_LOGOUT_ATTEMPTED_KEY);
  return true;
}

/**
 * 读取当前暂存的联合登出地址。
 *
 * @returns 安全地址；不存在或不合格时返回 null
 */
export function peekFederatedLogoutUrl(): string | null {
  const store = sessionStore();
  const raw = store?.getItem(FEDERATED_LOGOUT_URL_KEY) ?? null;
  return raw && isSafeFederatedLogoutUrl(raw) ? raw : null;
}

/**
 * 判断本次落地是否已经自动导航过。
 *
 * @returns 是否已尝试
 */
export function hasFederatedLogoutAttempted(): boolean {
  return sessionStore()?.getItem(FEDERATED_LOGOUT_ATTEMPTED_KEY) === '1';
}

/**
 * 标记本次落地已经自动导航过，供浏览器后退时展示“重试”而不是再次自动跳转。
 */
export function markFederatedLogoutAttempted(): void {
  sessionStore()?.setItem(FEDERATED_LOGOUT_ATTEMPTED_KEY, '1');
}

/**
 * 清理联合登出暂存；IdP 已回跳或用户主动重新登录后调用。
 */
export function clearFederatedLogout(): void {
  const store = sessionStore();
  store?.removeItem(FEDERATED_LOGOUT_URL_KEY);
  store?.removeItem(FEDERATED_LOGOUT_ATTEMPTED_KEY);
}

/**
 * 默认顶层导航：使用 `window.location.assign`，保证离开 SPA 路由。
 *
 * 跨域导航失败由浏览器处理，应用无法在同页捕获；调用方必须在此之前渲染本地退出提示。
 *
 * @param url 目标地址
 */
export function defaultTopLevelNavigator(url: string): void {
  if (typeof window !== 'undefined' && typeof window.location?.assign === 'function') {
    window.location.assign(url);
  }
}

/**
 * 把当前登录页地址改写为“正在结束企业会话”的落地地址。
 *
 * 仅在已经位于 `/login` 且尚未带 `federated` 参数时改写；这样 IdP 不可达、
 * 浏览器后退时地址仍是 `?loggedOut=1&federated=pending`，用户能看到本地退出提示。
 *
 * @returns 是否发生了改写
 */
export function replaceWithPendingLanding(): boolean {
  if (typeof window === 'undefined' || window.location.pathname !== '/login') {
    return false;
  }
  const params = new URLSearchParams(window.location.search);
  if (params.get('loggedOut') === '1' && params.get('federated') === 'pending') {
    return false;
  }
  window.history.replaceState(null, '', FEDERATED_LOGOUT_PENDING_PATH);
  return true;
}

/**
 * 在同一次登出落地中只自动导航一次。
 *
 * @param assign 顶层导航函数，测试可注入
 * @returns 是否触发了导航
 */
export function beginFederatedLogoutOnce(
  assign: TopLevelNavigator = defaultTopLevelNavigator,
): boolean {
  const url = peekFederatedLogoutUrl();
  if (!url || hasFederatedLogoutAttempted()) {
    return false;
  }
  markFederatedLogoutAttempted();
  assign(url);
  return true;
}

/**
 * 解析登录页地址中的退出状态参数。
 *
 * 只识别固定值；其他值视为未提供，页面不会回显任意查询内容。
 *
 * @param search location.search 或测试注入值
 * @returns 退出落地状态
 */
export function parseLogoutLanding(search: string): LogoutLandingState {
  const normalized = search.startsWith('?') ? search.slice(1) : search;
  const params = new URLSearchParams(normalized);
  const federated = params.get('federated');
  return {
    loggedOut: params.get('loggedOut') === '1',
    federated: federated === '1' ? 'done' : federated === 'pending' ? 'pending' : null,
  };
}
