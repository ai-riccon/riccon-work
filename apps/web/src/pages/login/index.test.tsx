// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import { authStore } from '@/services/auth/auth-store';
import { createFakeApi, DEMO_SESSION } from '@/test/fake-api';
import { withProviders } from '@/test/render';
import LoginPage, { loginErrorMessage } from './index';

/**
 * 登录页组件测试。
 *
 * 覆盖：匿名进入、凭据失败统一提示、加载态、会话过期提示、已登录跳转与状态恢复失败。
 * 不发起真实网络请求，CSRF/会话细节由 ApiClient 单测与 E2E 覆盖。
 */
describe('登录页', () => {
  beforeEach(() => {
    authStore.reset();
  });

  /** 创建匿名会话 API：/auth/session 返回 401。 */
  function anonymousApi() {
    return createFakeApi({
      getSession: vi.fn(async () => {
        throw new ApiError('未登录', { status: 401, code: 'RICCON-AUTH-401' });
      }),
    });
  }

  it('匿名访问时展示租户、账号、密码与登录按钮', async () => {
    const api = anonymousApi();
    render(withProviders(<LoginPage api={api} navigate={vi.fn()} />));

    expect(await screen.findByRole('heading', { name: '登录 Riccon' })).toBeVisible();
    expect(screen.getByPlaceholderText('例如 acme')).toBeVisible();
    expect(screen.getByPlaceholderText('请输入账号')).toBeVisible();
    expect(screen.getByPlaceholderText('请输入密码')).toBeVisible();
    expect(screen.getByRole('button', { name: /登\s*录/ })).toBeEnabled();
  });

  it('登录成功后写入会话并跳转工作空间', async () => {
    const api = anonymousApi();
    const navigate = vi.fn();
    render(withProviders(<LoginPage api={api} navigate={navigate} />));

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
    render(withProviders(<LoginPage api={api} navigate={vi.fn()} />));

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
    render(withProviders(<LoginPage api={api} navigate={vi.fn()} />));

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

  it('会话过期时展示警告，并保留登录表单', async () => {
    const api = anonymousApi();
    authStore.markExpired();
    render(withProviders(<LoginPage api={api} navigate={vi.fn()} />));

    expect(await screen.findByText('会话已过期')).toBeVisible();
    expect(screen.getByText(/已上传文件与历史会话不会丢失/)).toBeVisible();
    expect(screen.getByRole('button', { name: /登\s*录/ })).toBeVisible();
  });

  it('主动退出后展示成功提示', async () => {
    const api = anonymousApi();
    authStore.signOut('logout');
    render(withProviders(<LoginPage api={api} navigate={vi.fn()} />));

    expect(await screen.findByText('已安全退出')).toBeVisible();
  });

  it('已登录用户自动跳转，不重复展示表单', async () => {
    const api = anonymousApi();
    const navigate = vi.fn();
    authStore.setAuthenticated(DEMO_SESSION);
    render(withProviders(<LoginPage api={api} navigate={navigate} />));

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
    render(withProviders(<LoginPage api={api} navigate={navigate} />));

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
