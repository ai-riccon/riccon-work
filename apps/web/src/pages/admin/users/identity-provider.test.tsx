// @vitest-environment jsdom
import { fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { authStore } from '@/services/auth/auth-store';
import { withProviders } from '@/test/render';
import {
  PLATFORM_ADMIN_SESSION,
  TENANT_ADMIN_SESSION,
  createUserAdminApi,
  makeUser,
  pageOf,
} from '../../../../tests/m10-oidc/fake';
import AdminUsersPage from './index';

/**
 * 用户管理页来源 IdP 展示。
 *
 * 覆盖租户/平台列表、LOCAL、OIDC、两 issuer、空值与长 provider；375px、键盘与
 * 可访问性只做结构基线，完整 axe 扫描由 M15 verifier 的 E2E 承担。
 */
describe('用户管理来源 IdP', () => {
  beforeEach(() => {
    authStore.reset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  /** 渲染页面。 */
  function renderPage(options: { api?: ReturnType<typeof createUserAdminApi>; tenantId?: string }) {
    return render(
      withProviders(
        <AdminUsersPage api={options.api ?? createUserAdminApi()} tenantId={options.tenantId} />,
      ),
    );
  }

  it('租户列表按账号显示来源：LOCAL 本地账号，两 issuer 可区分且无敏感字段', async () => {
    authStore.setAuthenticated(TENANT_ADMIN_SESSION);
    const api = createUserAdminApi({
      listUsers: vi.fn(async () =>
        pageOf([
          makeUser({
            id: 'user-local',
            displayName: '本地员工',
            username: 'local-staff',
            authSource: 'LOCAL',
            identityProvider: null,
            email: 'local.secret@example.com',
          }),
          makeUser({
            id: 'user-oidc-a',
            displayName: '甲企业员工',
            username: 'oidc-a',
            authSource: 'OIDC',
            identityProvider: 'Acme 企业账号',
            email: 'a.secret@example.com',
          }),
          makeUser({
            id: 'user-oidc-b',
            displayName: '乙企业员工',
            username: 'oidc-b',
            authSource: 'OIDC',
            identityProvider: 'idp-b.example.com:8443',
            email: 'b.secret@example.com',
          }),
        ]),
      ),
    });
    renderPage({ api });

    expect(await screen.findByText('来源：本地账号')).toBeVisible();
    expect(screen.getByText('来源：Acme 企业账号')).toBeVisible();
    expect(screen.getByText('来源：idp-b.example.com:8443')).toBeVisible();
    // 原有认证来源标签保留，来源 IdP 是补充信息，不回退既有行为。
    expect(screen.getAllByText('内置账号')).toHaveLength(1);
    expect(screen.getAllByText('企业账号')).toHaveLength(2);
    // 列表不展示邮箱、完整 issuer 路径或任何令牌类值。
    expect(screen.queryByText(/secret@example\.com/)).not.toBeInTheDocument();
    expect(screen.queryByText(/https:\/\//)).not.toBeInTheDocument();
    expect(screen.queryByText(/eyJ/)).not.toBeInTheDocument();
  });

  it('OIDC 缺少 identityProvider 时显示未标注，不猜测具体 IdP', async () => {
    authStore.setAuthenticated(TENANT_ADMIN_SESSION);
    const api = createUserAdminApi({
      listUsers: vi.fn(async () =>
        pageOf([makeUser({ authSource: 'OIDC', identityProvider: null })]),
      ),
    });
    renderPage({ api });

    expect(await screen.findByText('来源：未标注')).toBeVisible();
  });

  it('平台管理员目标租户列表使用相同来源文案', async () => {
    authStore.setAuthenticated(PLATFORM_ADMIN_SESSION);
    const api = createUserAdminApi({
      listTenantUsers: vi.fn(async () =>
        pageOf([
          makeUser({
            authSource: 'OIDC',
            identityProvider: 'idp.platform-target.example.com',
          }),
        ]),
      ),
    });
    renderPage({ api, tenantId: 'acme' });

    expect(await screen.findByText('来源：idp.platform-target.example.com')).toBeVisible();
    expect(api.listTenantUsers).toHaveBeenCalledWith('acme');
    expect(api.listUsers).not.toHaveBeenCalled();
  });

  it('绑定详情显示当前本地账号来源', async () => {
    authStore.setAuthenticated(TENANT_ADMIN_SESSION);
    const api = createUserAdminApi({
      listUsers: vi.fn(async () =>
        pageOf([
          makeUser({
            id: 'user-local',
            displayName: '本地员工',
            username: 'local-staff',
            authSource: 'LOCAL',
            identityProvider: null,
          }),
        ]),
      ),
    });
    renderPage({ api });

    fireEvent.click(await screen.findByRole('button', { name: '绑定企业账号：本地员工' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('来源：本地账号')).toBeInTheDocument();
  });

  it('解绑详情显示来源 IdP，不显示邮箱或完整 issuer', async () => {
    authStore.setAuthenticated(TENANT_ADMIN_SESSION);
    const api = createUserAdminApi({
      listUsers: vi.fn(async () =>
        pageOf([
          makeUser({
            id: 'user-oidc',
            displayName: '企业员工',
            username: 'oidc-staff',
            authSource: 'OIDC',
            identityProvider: 'Acme 企业账号',
            email: 'detail.secret@example.com',
          }),
        ]),
      ),
    });
    renderPage({ api });

    fireEvent.click(await screen.findByRole('button', { name: '解除绑定：企业员工' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('来源：Acme 企业账号')).toBeInTheDocument();
    expect(within(dialog).queryByText(/detail\.secret@example\.com/)).not.toBeInTheDocument();
    expect(within(dialog).queryByText(/https:\/\//)).not.toBeInTheDocument();
  });

  it('375px 与键盘基线：长 provider 完整展示并提供 title，不新增焦点陷阱', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 375 });
    authStore.setAuthenticated(TENANT_ADMIN_SESSION);
    const longName = `企业身份提供方-${'长'.repeat(240)}`;
    const api = createUserAdminApi({
      listUsers: vi.fn(async () =>
        pageOf([makeUser({ authSource: 'OIDC', identityProvider: longName })]),
      ),
    });
    renderPage({ api });

    const label = await screen.findByText(`来源：${longName}`);
    expect(label).toBeVisible();
    // 窄屏使用 CSS 截断，title 保留完整值；文本仍是完整可访问名称。
    expect(label).toHaveAttribute('title', `来源：${longName}`);
    expect(label).not.toHaveAttribute('aria-hidden');
    expect(label).not.toHaveAttribute('tabindex');

    const action = screen.getByRole('button', { name: '解除绑定：王敏' });
    action.focus();
    expect(action).toHaveFocus();
  });
});
