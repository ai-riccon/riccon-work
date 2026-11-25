// @vitest-environment jsdom
import { render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import type { SessionInfo } from '@/services/api/types';
import { authStore } from '@/services/auth/auth-store';
import { DEMO_SESSION } from '@/test/fake-api';
import { withProviders } from '@/test/render';
import WorkflowStudioNav from './studio-nav';

/**
 * Studio 子导航测试。
 *
 * 覆盖列表/编辑器/模板库入口可见性、选中态互斥、子路由出口与 375px 可聚焦；
 * 真实 Umi 路由匹配与跨页跳转由 T16a 的 E2E 覆盖。
 */

/** 具备编排读取权限的会话。 */
const READER_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  roles: ['MEMBER'],
  permissions: [...DEMO_SESSION.permissions, 'workflow.read'],
};

/** 无编排权限的会话。 */
const NO_PERMISSION_SESSION: SessionInfo = DEMO_SESSION;

/**
 * 渲染 Studio 子导航。
 *
 * @param pathname 当前路径
 * @param session 会话
 * @returns 渲染结果
 */
function renderNav(pathname: string, session: SessionInfo = READER_SESSION) {
  authStore.setAuthenticated(session);
  return render(withProviders(<WorkflowStudioNav pathname={pathname} />));
}

describe('编排 Studio 子导航', () => {
  beforeEach(() => {
    authStore.reset();
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1024 });
  });

  it('流程列表页展示模板库入口，流程列表为当前项', () => {
    renderNav('/workflows/list');

    const nav = screen.getByRole('navigation', { name: '编排 Studio 导航' });
    expect(within(nav).getByRole('link', { name: '流程列表' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    const templates = within(nav).getByRole('link', { name: '模板库' });
    expect(templates).toHaveAttribute('href', '/workflows/templates');
    expect(templates).not.toHaveAttribute('aria-current');
    expect(screen.getByTestId('umi-outlet')).toBeInTheDocument();
  });

  it('编辑器深链仍把流程列表作为当前项，并可切换到模板库', () => {
    renderNav('/workflows/workflow-1/edit');

    const nav = screen.getByRole('navigation', { name: '编排 Studio 导航' });
    expect(within(nav).getByRole('link', { name: '流程列表' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(within(nav).getByRole('link', { name: '模板库' })).toHaveAttribute(
      'href',
      '/workflows/templates',
    );
  });

  it('模板库页只有模板库高亮，流程列表可返回', () => {
    renderNav('/workflows/templates');

    const nav = screen.getByRole('navigation', { name: '编排 Studio 导航' });
    expect(within(nav).getByRole('link', { name: '模板库' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(within(nav).getByRole('link', { name: '流程列表' })).toHaveAttribute(
      'href',
      '/workflows/list',
    );
    expect(within(nav).getByRole('link', { name: '流程列表' })).not.toHaveAttribute('aria-current');
    expect(screen.getByTestId('umi-outlet')).toBeInTheDocument();
  });

  it('无 workflow.read 时不渲染 Studio 子导航，页面守卫仍可显示 403', () => {
    renderNav('/workflows/list', NO_PERMISSION_SESSION);

    expect(screen.queryByRole('navigation', { name: '编排 Studio 导航' })).not.toBeInTheDocument();
    expect(screen.getByTestId('umi-outlet')).toBeInTheDocument();
  });

  it('375px 下子导航换行展示且模板库入口可聚焦', () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 375 });
    renderNav('/workflows/list');

    const nav = screen.getByRole('navigation', { name: '编排 Studio 导航' });
    expect(nav).toBeVisible();
    const templates = within(nav).getByRole('link', { name: '模板库' });
    expect(templates).toBeVisible();
    templates.focus();
    expect(templates).toHaveFocus();
  });
});
