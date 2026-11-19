// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import {
  FEDERATED_LOGOUT_ATTEMPTED_KEY,
  FEDERATED_LOGOUT_URL_KEY,
} from '@/services/auth/federated-logout';
import { authStore } from '@/services/auth/auth-store';
import { createFakeApi, DEMO_SESSION } from '@/test/fake-api';
import { withProviders } from '@/test/render';
import { createOidcApi } from '../../../tests/m10-oidc/fake';
import LoginPage, { loginErrorMessage } from './index';

/**
 * 登录页组件测试。
 *
 * 覆盖：匿名进入、内置账号登录、企业账号入口与错误态、联合登出落地、
 * 会话过期提示、已登录跳转与状态恢复失败。
 * 不发起真实网络请求，CSRF/会话细节由 ApiClient 单测与 E2E 覆盖。
 */
describe('登录页', () => {
  beforeEach(() => {
    authStore.reset();
    sessionStorage.clear();
  });

  /** 创建匿名会话 API：/auth/session 返回 401。 */
  function anonymousApi() {
    return createFakeApi({
      getSession: vi.fn(async () => {
        throw new ApiError('未登录', { status: 401, code: 'RICCON-AUTH-401' });
      }),
    });
  }

  /** 渲染登录页。 */
  function renderLogin(options: Parameters<typeof LoginPage>[0] = {}) {
    return render(
      withProviders(
        <LoginPage
          api={options.api ?? anonymousApi()}
          assign={options.assign ?? vi.fn()}
          navigate={options.navigate ?? vi.fn()}
          oidcApi={options.oidcApi ?? createOidcApi()}
          search={options.search}
        />,
      ),
    );
  }

  it('匿名访问时展示租户、账号、密码、内置登录与企业账号入口', async () => {
    renderLogin();

    expect(await screen.findByRole('heading', { name: '登录 Riccon' })).toBeVisible();
    expect(screen.getByPlaceholderText('例如 acme')).toBeVisible();
    expect(screen.getByPlaceholderText('请输入账号')).toBeVisible();
    expect(screen.getByPlaceholderText('请输入密码')).toBeVisible();
    expect(screen.getByRole('button', { name: /登\s*录/ })).toBeEnabled();
    expect(screen.getByRole('button', { name: '企业账号' })).toBeEnabled();
  });

  it('内置账号登录成功后写入会话并跳转工作空间', async () => {
    const api = anonymousApi();
    const navigate = vi.fn();
    renderLogin({ api, navigate });

    await screen.findByRole('heading', { name: '登录 Riccon' });
    fireEvent.change(screen.getByPlaceholderText('例如 acme'), { target: { value: 'acme' } });
    fireEvent.change(screen.getByPlaceholderText('请输入账号'), { target: { value: 'demo' } });
    fireEvent.change(screen.getByPlaceholderText('请输入密码'), { target: { value: 'secret' } });
    fireEvent.click(screen.getByRole('button', { name: /登\s*录/ }));

    await waitFor(() => {
      expect(api.login).toHaveBeenCalledWith({
        tenantCode: 'acme',
        username: 'demo',
        password: 'secret',
      });
    });
    await waitFor(() => {
      expect(authStore.getSnapshot().status).toBe('authenticated');
      expect(navigate).toHaveBeenCalledWith('/chat', { replace: true });
    });
  });

  it('凭据错误只显示统一提示，不泄露账号是否存在', async () => {
    const api = createFakeApi({
      getSession: vi.fn(async () => {
        throw new ApiError('未登录', { status: 401, code: 'RICCON-AUTH-401' });
      }),
      login: vi.fn(async () => {
        throw new ApiError('账号不存在', { status: 401, code: 'RICCON-AUTH-001' });
      }),
    });
    renderLogin({ api });

    await screen.findByRole('heading', { name: '登录 Riccon' });
    fireEvent.change(screen.getByPlaceholderText('例如 acme'), { target: { value: 'acme' } });
    fireEvent.change(screen.getByPlaceholderText('请输入账号'), { target: { value: 'nobody' } });
    fireEvent.change(screen.getByPlaceholderText('请输入密码'), { target: { value: 'wrong' } });
    fireEvent.click(screen.getByRole('button', { name: /登\s*录/ }));

    expect(await screen.findByText(/租户代码、账号或密码不正确/)).toBeVisible();
    expect(screen.queryByText(/账号不存在/)).not.toBeInTheDocument();
    expect(authStore.getSnapshot().status).not.toBe('authenticated');
  });

  it('提交期间展示加载态并禁用输入', async () => {
    let resolveLogin: (session: typeof DEMO_SESSION) => void = () => undefined;
    const api = createFakeApi({
      getSession: vi.fn(async () => {
        throw new ApiError('未登录', { status: 401, code: 'RICCON-AUTH-401' });
      }),
      login: vi.fn(
        () =>
          new Promise<typeof DEMO_SESSION>((resolve) => {
            resolveLogin = resolve;
          }),
      ),
    });
    renderLogin({ api });

    await screen.findByRole('heading', { name: '登录 Riccon' });
    fireEvent.change(screen.getByPlaceholderText('例如 acme'), { target: { value: 'acme' } });
    fireEvent.change(screen.getByPlaceholderText('请输入账号'), { target: { value: 'demo' } });
    fireEvent.change(screen.getByPlaceholderText('请输入密码'), { target: { value: 'secret' } });
    fireEvent.click(screen.getByRole('button', { name: /登\s*录/ }));

    await waitFor(() => {
      expect(screen.getByPlaceholderText('例如 acme')).toBeDisabled();
    });
    expect(screen.getByRole('button', { name: /登\s*录/ })).toHaveClass('ant-btn-loading');

    resolveLogin(DEMO_SESSION);
    await waitFor(() => {
      expect(authStore.getSnapshot().status).toBe('authenticated');
    });
  });

  it('企业账号入口按 tenantCode 查询 providers 并顶层跳转授权入口', async () => {
    const oidcApi = createOidcApi();
    const assign = vi.fn();
    renderLogin({ oidcApi, assign });

    await screen.findByRole('heading', { name: '登录 Riccon' });
    fireEvent.change(screen.getByPlaceholderText('例如 acme'), { target: { value: 'acme' } });
    fireEvent.click(screen.getByRole('button', { name: '企业账号' }));

    await waitFor(() => {
      expect(oidcApi.getProvider).toHaveBeenCalledWith('acme');
    });
    await waitFor(() => {
      expect(assign).toHaveBeenCalledWith('/api/v1/auth/oidc/authorize/tenant-acme');
    });
    expect(oidcApi.authorizeUrl).toHaveBeenCalledWith('acme');
  });

  it('租户未开通或未知租户统一提示未开通，不泄露租户存在性', async () => {
    const oidcApi = createOidcApi({
      getProvider: vi.fn(async () => ({ enabled: false, providerName: null })),
    });
    const assign = vi.fn();
    renderLogin({ oidcApi, assign });

    await screen.findByRole('heading', { name: '登录 Riccon' });
    fireEvent.change(screen.getByPlaceholderText('例如 acme'), {
      target: { value: 'unknown-tenant' },
    });
    fireEvent.click(screen.getByRole('button', { name: '企业账号' }));

    expect(await screen.findByText('该租户未开通企业账号登录')).toBeVisible();
    expect(screen.queryByText(/不存在/)).not.toBeInTheDocument();
    expect(assign).not.toHaveBeenCalled();
  });

  it('providers 查询限流时给出可读提示且不跳转', async () => {
    const oidcApi = createOidcApi({
      getProvider: vi.fn(async () => {
        throw new ApiError('rate limited', { status: 429, code: 'RICCON-RATE-429' });
      }),
    });
    const assign = vi.fn();
    renderLogin({ oidcApi, assign });

    await screen.findByRole('heading', { name: '登录 Riccon' });
    fireEvent.change(screen.getByPlaceholderText('例如 acme'), { target: { value: 'acme' } });
    fireEvent.click(screen.getByRole('button', { name: '企业账号' }));

    expect(await screen.findByText('查询过于频繁，请稍后再试。')).toBeVisible();
    expect(assign).not.toHaveBeenCalled();
  });

  it('空租户代码不会发起 providers 查询', async () => {
    const oidcApi = createOidcApi();
    renderLogin({ oidcApi });

    await screen.findByRole('heading', { name: '登录 Riccon' });
    fireEvent.click(screen.getByRole('button', { name: '企业账号' }));

    expect(await screen.findByText('请输入租户代码')).toBeInTheDocument();
    expect(oidcApi.getProvider).not.toHaveBeenCalled();
  });

  it.each([
    ['idp_unavailable', '企业账号服务暂时不可用'],
    ['state_invalid', '企业账号登录已失效'],
    ['access_denied', '企业账号授权已取消'],
    ['account_not_bound', '账号尚未关联企业身份'],
    ['account_disabled', '账号已被禁用'],
    ['tenant_unavailable', '租户当前不可用'],
    ['issuer_mismatch', '企业账号配置不匹配'],
    ['rate_limited', '尝试次数过多'],
    ['login_failed', '企业账号登录未完成'],
  ])('登录失败原因码 %s 显示独立文案且不回显原始错误', async (code, title) => {
    renderLogin({ search: `?oidcError=${code}` });

    expect(await screen.findByText(title)).toBeVisible();
    expect(screen.queryByText(code)).not.toBeInTheDocument();
    expect(screen.queryByText(/stack|token|issuer\//i)).not.toBeInTheDocument();
  });

  it('未知原因码使用通用文案，不回显查询值', async () => {
    renderLogin({ search: '?oidcError=raw_idp_error_with_detail' });

    expect(await screen.findByText('企业账号登录未完成')).toBeVisible();
    expect(screen.queryByText('raw_idp_error_with_detail')).not.toBeInTheDocument();
  });

  it('IdP 已回跳时显示本地与企业会话均已退出，并清理暂存地址', async () => {
    sessionStorage.setItem(FEDERATED_LOGOUT_URL_KEY, 'https://idp.example.com/logout');
    renderLogin({ search: '?loggedOut=1&federated=1' });

    expect(await screen.findByText('已退出本地会话和企业账号')).toBeVisible();
    expect(sessionStorage.getItem(FEDERATED_LOGOUT_URL_KEY)).toBeNull();
  });

  it('仅本地退出时明确企业账号会话可能仍有效', async () => {
    renderLogin({ search: '?loggedOut=1' });

    expect(await screen.findByText('已退出本地会话')).toBeVisible();
    expect(screen.getByText(/企业账号会话（如有）可能仍然有效/)).toBeVisible();
    expect(screen.queryByText(/已退出本地会话和企业账号/)).not.toBeInTheDocument();
  });

  it('联合登出落地自动顶层导航一次，后退后展示手动重试', async () => {
    const url = 'https://idp.example.com/realms/acme/logout?client_id=riccon';
    sessionStorage.setItem(FEDERATED_LOGOUT_URL_KEY, url);
    const assign = vi.fn();
    renderLogin({ search: '?loggedOut=1&federated=pending', assign });

    await waitFor(() => {
      expect(assign).toHaveBeenCalledWith(url);
    });
    expect(assign).toHaveBeenCalledTimes(1);
    expect(sessionStorage.getItem(FEDERATED_LOGOUT_ATTEMPTED_KEY)).toBe('1');
    expect(await screen.findByText(/企业账号中心的会话可能仍然有效/)).toBeVisible();

    fireEvent.click(screen.getByRole('button', { name: '重试退出企业账号' }));
    expect(assign).toHaveBeenCalledTimes(2);
  });

  it('已尝试过联合登出时不再自动跳转，只保留本地退出提示', async () => {
    const url = 'https://idp.example.com/logout';
    sessionStorage.setItem(FEDERATED_LOGOUT_URL_KEY, url);
    sessionStorage.setItem(FEDERATED_LOGOUT_ATTEMPTED_KEY, '1');
    const assign = vi.fn();
    renderLogin({ search: '?loggedOut=1&federated=pending', assign });

    expect(await screen.findByText('已退出本地会话')).toBeVisible();
    expect(assign).not.toHaveBeenCalled();
    expect(screen.queryByText(/企业身份服务已确认会话结束/)).not.toBeInTheDocument();
  });

  it('联合登出没有暂存地址时只声称本地退出', async () => {
    const assign = vi.fn();
    renderLogin({ search: '?loggedOut=1&federated=pending', assign });

    expect(await screen.findByText('已退出本地会话')).toBeVisible();
    expect(screen.getByText(/未能自动结束企业账号会话/)).toBeVisible();
    expect(assign).not.toHaveBeenCalled();
    expect(screen.queryByText(/已退出本地会话和企业账号/)).not.toBeInTheDocument();
  });

  it('IdP 不可达后重新打开登录页仍提示本地已退出', async () => {
    sessionStorage.setItem(FEDERATED_LOGOUT_URL_KEY, 'https://idp.example.com/logout');
    sessionStorage.setItem(FEDERATED_LOGOUT_ATTEMPTED_KEY, '1');
    const assign = vi.fn();
    renderLogin({ assign });

    expect(await screen.findByText('已退出本地会话')).toBeVisible();
    expect(screen.getByText(/企业账号中心的会话可能仍然有效/)).toBeVisible();
    expect(assign).not.toHaveBeenCalled();
  });

  it('内置账号主动退出后只提示本地会话结束', async () => {
    authStore.signOut('logout');
    renderLogin();

    expect(await screen.findByText('已退出本地会话')).toBeVisible();
    expect(screen.getByText(/企业账号会话（如有）可能仍然有效/)).toBeVisible();
  });

  it('会话过期时展示警告，并保留登录表单', async () => {
    authStore.markExpired();
    renderLogin();

    expect(await screen.findByText('会话已过期')).toBeVisible();
    expect(screen.getByText(/已上传文件与历史会话不会丢失/)).toBeVisible();
    expect(screen.getByRole('button', { name: /登\s*录/ })).toBeVisible();
  });

  it('已登录用户自动跳转，不重复展示表单', async () => {
    const navigate = vi.fn();
    authStore.setAuthenticated(DEMO_SESSION);
    renderLogin({ navigate });

    await waitFor(() => {
      expect(navigate).toHaveBeenCalledWith('/chat', { replace: true });
    });
    expect(screen.queryByRole('heading', { name: '登录 Riccon' })).not.toBeInTheDocument();
  });

  it('会话状态加载失败显示错误与重试入口', async () => {
    const api = createFakeApi({
      getSession: vi
        .fn()
        .mockRejectedValueOnce(new ApiError('服务不可用', { status: 503, code: 'RICCON-500' }))
        .mockResolvedValueOnce(DEMO_SESSION),
    });
    const navigate = vi.fn();
    renderLogin({ api, navigate });

    expect(await screen.findByText('无法确认登录状态')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));

    await waitFor(() => {
      expect(navigate).toHaveBeenCalledWith('/chat', { replace: true });
    });
  });
});

describe('登录错误文案', () => {
  it('401 使用统一提示，429/403/网络分别映射', () => {
    expect(loginErrorMessage(new ApiError('x', { status: 401, code: 'A' }))).toContain(
      '租户代码、账号或密码不正确',
    );
    expect(loginErrorMessage(new ApiError('x', { status: 429, code: 'A' }))).toContain('频繁');
    expect(loginErrorMessage(new ApiError('x', { status: 403, code: 'A' }))).toContain('刷新');
    expect(loginErrorMessage(new ApiError('x', { status: 0, code: 'A' }))).toContain('网络');
    expect(loginErrorMessage(new Error('boom'))).toBe('登录失败，请稍后重试。');
  });
});
