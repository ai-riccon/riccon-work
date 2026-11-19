// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  FEDERATED_LOGOUT_ATTEMPTED_KEY,
  FEDERATED_LOGOUT_URL_KEY,
  beginFederatedLogoutOnce,
  clearFederatedLogout,
  hasFederatedLogoutAttempted,
  isSafeFederatedLogoutUrl,
  markFederatedLogoutAttempted,
  parseLogoutLanding,
  peekFederatedLogoutUrl,
  rememberFederatedLogoutUrl,
  replaceWithPendingLanding,
} from './federated-logout';

describe('联合登出浏览器逻辑', () => {
  beforeEach(() => {
    sessionStorage.clear();
  });

  it('只接受 HTTPS 与本机 HTTP 的绝对地址', () => {
    expect(isSafeFederatedLogoutUrl('https://idp.example.com/logout')).toBe(true);
    expect(isSafeFederatedLogoutUrl('http://localhost:8080/logout')).toBe(true);
    expect(isSafeFederatedLogoutUrl('http://127.0.0.1/logout')).toBe(true);
    expect(isSafeFederatedLogoutUrl('http://idp.example.com/logout')).toBe(false);
    expect(isSafeFederatedLogoutUrl('javascript:alert(1)')).toBe(false);
    expect(isSafeFederatedLogoutUrl('not-a-url')).toBe(false);
    expect(isSafeFederatedLogoutUrl('')).toBe(false);
  });

  it('只暂存安全地址，并在新登出时重置已尝试标记', () => {
    sessionStorage.setItem(FEDERATED_LOGOUT_ATTEMPTED_KEY, '1');

    expect(rememberFederatedLogoutUrl('https://idp.example.com/logout')).toBe(true);
    expect(peekFederatedLogoutUrl()).toBe('https://idp.example.com/logout');
    expect(hasFederatedLogoutAttempted()).toBe(false);

    expect(rememberFederatedLogoutUrl('javascript:alert(1)')).toBe(false);
    expect(peekFederatedLogoutUrl()).toBe('https://idp.example.com/logout');
    expect(rememberFederatedLogoutUrl(null)).toBe(false);
  });

  it('清理函数同时移除地址与尝试标记', () => {
    sessionStorage.setItem(FEDERATED_LOGOUT_URL_KEY, 'https://idp.example.com/logout');
    sessionStorage.setItem(FEDERATED_LOGOUT_ATTEMPTED_KEY, '1');

    clearFederatedLogout();

    expect(sessionStorage.getItem(FEDERATED_LOGOUT_URL_KEY)).toBeNull();
    expect(sessionStorage.getItem(FEDERATED_LOGOUT_ATTEMPTED_KEY)).toBeNull();
  });

  it('一次落地只自动导航一次，后退后返回 false', () => {
    sessionStorage.setItem(FEDERATED_LOGOUT_URL_KEY, 'https://idp.example.com/logout');
    const assign = vi.fn();

    expect(beginFederatedLogoutOnce(assign)).toBe(true);
    expect(assign).toHaveBeenCalledWith('https://idp.example.com/logout');
    expect(hasFederatedLogoutAttempted()).toBe(true);
    expect(beginFederatedLogoutOnce(assign)).toBe(false);
    expect(assign).toHaveBeenCalledTimes(1);
  });

  it('没有地址时不导航', () => {
    const assign = vi.fn();
    expect(beginFederatedLogoutOnce(assign)).toBe(false);
    expect(assign).not.toHaveBeenCalled();
  });

  it('解析固定退出状态参数，忽略未知值', () => {
    expect(parseLogoutLanding('?loggedOut=1&federated=pending')).toEqual({
      loggedOut: true,
      federated: 'pending',
    });
    expect(parseLogoutLanding('loggedOut=1&federated=1')).toEqual({
      loggedOut: true,
      federated: 'done',
    });
    expect(parseLogoutLanding('?federated=other')).toEqual({
      loggedOut: false,
      federated: null,
    });
    expect(parseLogoutLanding('')).toEqual({ loggedOut: false, federated: null });
  });

  it('登录页地址改写为 pending 后再尝试导航', () => {
    window.history.replaceState(null, '', '/login');

    expect(replaceWithPendingLanding()).toBe(true);
    expect(window.location.pathname).toBe('/login');
    expect(new URLSearchParams(window.location.search).get('loggedOut')).toBe('1');
    expect(new URLSearchParams(window.location.search).get('federated')).toBe('pending');
    expect(replaceWithPendingLanding()).toBe(false);
  });

  it('非登录页不改写地址', () => {
    window.history.replaceState(null, '', '/chat');
    expect(replaceWithPendingLanding()).toBe(false);
    expect(window.location.pathname).toBe('/chat');
  });

  it('标记函数幂等', () => {
    markFederatedLogoutAttempted();
    markFederatedLogoutAttempted();
    expect(hasFederatedLogoutAttempted()).toBe(true);
  });
});
