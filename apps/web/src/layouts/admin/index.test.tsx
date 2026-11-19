// @vitest-environment jsdom
import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { SessionInfo } from '@/services/api/types';
import { authStore } from '@/services/auth/auth-store';
import { DEMO_SESSION } from '@/test/fake-api';
import AdminLayout from './index';

/** 租户管理员会话：可管理本租户账号并查询/导出审计。 */
const TENANT_ADMIN_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  roles: ['TENANT_ADMIN'],
  permissions: [
    ...DEMO_SESSION.permissions,
    'tenant.user.manage',
    'audit.read',
    'audit.export.approve',
  ],
};

/** 平台管理员会话：只能查询平台租户审计，用户管理按租户上下文进入。 */
const PLATFORM_ADMIN_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  user: { ...DEMO_SESSION.user, id: 'platform-admin', displayName: '赵蕾' },
  tenant: { ...DEMO_SESSION.tenant, id: 'platform', name: '平台' },
  roles: ['PLATFORM_ADMIN'],
  permissions: ['platform.tenant.manage', 'audit.read', 'platform.audit.export.approve'],
};

/**
 * 管理端 ProLayout 布局组件测试。
 *
 * 断言外壳行为：品牌标题、菜单链接地址、权限过滤、当前路由的激活态，以及子路由 Outlet 的挂载，
 * 避免只做“能渲染”的快照断言。
 */
describe('管理端 ProLayout 布局', () => {
  beforeEach(() => {
    authStore.reset();
  });

  it('渲染品牌标题、既有菜单链接与子路由出口', () => {
    // 匿名态避免在测试环境触发真实 /auth/session 请求。
    authStore.signOut(null);
    render(<AdminLayout />);

    expect(screen.getByText('Riccon 管理端')).toBeVisible();
    expect(screen.getByRole('link', { name: /管理总览/ })).toHaveAttribute(
      'href',
      '/admin/overview',
    );
    expect(screen.getByRole('link', { name: /租户管理/ })).toHaveAttribute(
      'href',
      '/admin/tenants',
    );
    expect(screen.getByRole('link', { name: /系统设置/ })).toHaveAttribute(
      'href',
      '/admin/settings',
    );
    expect(screen.queryByRole('link', { name: /用户管理/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /审计查询/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /审计导出/ })).not.toBeInTheDocument();
    expect(screen.getByTestId('umi-outlet')).toBeInTheDocument();
  });

  it('租户管理员看到用户管理、审计查询与审计导出入口', () => {
    authStore.setAuthenticated(TENANT_ADMIN_SESSION);
    render(<AdminLayout />);

    expect(screen.getByRole('link', { name: /用户管理/ })).toHaveAttribute('href', '/admin/users');
    expect(screen.getByRole('link', { name: /审计查询/ })).toHaveAttribute('href', '/admin/audit');
    expect(screen.getByRole('link', { name: /审计导出/ })).toHaveAttribute(
      'href',
      '/admin/audit-exports',
    );
  });

  it('普通员工没有新增管理入口', () => {
    authStore.setAuthenticated(DEMO_SESSION);
    render(<AdminLayout />);

    expect(screen.getByRole('link', { name: /管理总览/ })).toBeVisible();
    expect(screen.queryByRole('link', { name: /用户管理/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /审计查询/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /审计导出/ })).not.toBeInTheDocument();
  });

  it('平台管理员只看到平台审计查询入口', () => {
    authStore.setAuthenticated(PLATFORM_ADMIN_SESSION);
    render(<AdminLayout />);

    expect(screen.getByRole('link', { name: /审计查询/ })).toHaveAttribute('href', '/admin/audit');
    expect(screen.queryByRole('link', { name: /用户管理/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /审计导出/ })).not.toBeInTheDocument();
  });

  it('会话未加载时恢复会话并按权限补齐菜单', async () => {
    const reader = { getSession: vi.fn(async () => TENANT_ADMIN_SESSION) };
    render(<AdminLayout api={reader} />);

    await screen.findByRole('link', { name: /管理总览/ });
    await waitFor(() => expect(authStore.getSnapshot().status).toBe('authenticated'));
    expect(await screen.findByRole('link', { name: /审计查询/ })).toHaveAttribute(
      'href',
      '/admin/audit',
    );
    expect(reader.getSession).toHaveBeenCalledTimes(1);
  });

  it('按当前路径高亮对应菜单项', () => {
    authStore.signOut(null);
    const { container } = render(<AdminLayout />);

    const activeItem = container.querySelector('.ant-menu-item-selected');
    expect(activeItem).not.toBeNull();
    expect(activeItem).toHaveTextContent('管理总览');
  });
});
