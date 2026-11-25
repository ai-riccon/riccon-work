// @vitest-environment jsdom
import { cleanup, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import type { SessionInfo } from '@/services/api/types';
import { authStore } from '@/services/auth/auth-store';
import { DEMO_SESSION } from '@/test/fake-api';
import { withProviders } from '@/test/render';
import WorkflowStudioNav from './studio-nav';

/**
 * Studio 子导航测试。
 *
 * 覆盖列表/编辑器/模板库/运行历史/审批待办入口可见性、选中态互斥、子路由出口与 375px 可聚焦；
 * 真实 Umi 路由匹配与跨页跳转由 T16a 的 E2E 覆盖。
 */

/** 具备编排读取权限的会话。 */
const READER_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  roles: ['MEMBER'],
  permissions: [...DEMO_SESSION.permissions, 'workflow.read'],
};

/** 具备编排读取与审批待办权限的会话（MEMBER 在生产迁移中即持有 read/run/approve）。 */
const APPROVER_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  roles: ['MEMBER'],
  permissions: [...DEMO_SESSION.permissions, 'workflow.read', 'workflow.approve'],
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
    // 只有 workflow.read 时审批入口必须隐藏，避免无审批权限用户看到死链。
    expect(within(nav).queryByRole('link', { name: '审批待办' })).not.toBeInTheDocument();
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

  it('有 workflow.approve 时展示审批待办入口，列表页仍高亮流程列表', () => {
    renderNav('/workflows/list', APPROVER_SESSION);

    const nav = screen.getByRole('navigation', { name: '编排 Studio 导航' });
    const approvals = within(nav).getByRole('link', { name: '审批待办' });
    expect(approvals).toHaveAttribute('href', '/workflows/approvals');
    expect(approvals).not.toHaveAttribute('aria-current');
    expect(within(nav).getByRole('link', { name: '流程列表' })).toHaveAttribute(
      'aria-current',
      'page',
    );
  });

  it('审批待办页只有审批待办高亮，详情深链同样归属该入口', () => {
    renderNav('/workflows/approvals', APPROVER_SESSION);

    let nav = screen.getByRole('navigation', { name: '编排 Studio 导航' });
    expect(within(nav).getByRole('link', { name: '审批待办' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(within(nav).getByRole('link', { name: '流程列表' })).not.toHaveAttribute('aria-current');
    expect(within(nav).getByRole('link', { name: '模板库' })).not.toHaveAttribute('aria-current');
    expect(screen.getByTestId('umi-outlet')).toBeInTheDocument();

    cleanup();
    renderNav('/workflows/approvals/approval-1', APPROVER_SESSION);
    nav = screen.getByRole('navigation', { name: '编排 Studio 导航' });
    expect(within(nav).getByRole('link', { name: '审批待办' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(within(nav).getByRole('link', { name: '流程列表' })).not.toHaveAttribute('aria-current');
  });

  it('无 workflow.read 时不渲染 Studio 子导航，页面守卫仍可显示 403', () => {
    renderNav('/workflows/list', NO_PERMISSION_SESSION);

    expect(screen.queryByRole('navigation', { name: '编排 Studio 导航' })).not.toBeInTheDocument();
    expect(screen.getByTestId('umi-outlet')).toBeInTheDocument();
  });

  it('运行历史页高亮运行历史，流程列表与模板库不重复高亮', () => {
    renderNav('/workflows/runs');

    const nav = screen.getByRole('navigation', { name: '编排 Studio 导航' });
    const runs = within(nav).getByRole('link', { name: '运行历史' });
    expect(runs).toHaveAttribute('href', '/workflows/runs');
    expect(runs).toHaveAttribute('aria-current', 'page');
    expect(within(nav).getByRole('link', { name: '流程列表' })).not.toHaveAttribute('aria-current');
    expect(within(nav).getByRole('link', { name: '模板库' })).not.toHaveAttribute('aria-current');
    expect(screen.getByTestId('umi-outlet')).toBeInTheDocument();
  });

  it('运行详情深链仍归属运行历史入口，且只有一项 aria-current', () => {
    renderNav('/workflows/runs/run-1');

    const nav = screen.getByRole('navigation', { name: '编排 Studio 导航' });
    expect(within(nav).getByRole('link', { name: '运行历史' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(within(nav).getByRole('link', { name: '流程列表' })).not.toHaveAttribute('aria-current');
    expect(within(nav).getByRole('link', { name: '模板库' })).not.toHaveAttribute('aria-current');
    // 选中态互斥：深链页面上不应再出现第二个 aria-current。
    expect(
      within(nav)
        .getAllByRole('link')
        .filter((link) => link.hasAttribute('aria-current')),
    ).toHaveLength(1);
  });

  it('流程列表与模板库把运行历史作为普通入口，不进入选中态', () => {
    renderNav('/workflows/list');

    let nav = screen.getByRole('navigation', { name: '编排 Studio 导航' });
    const runsOnList = within(nav).getByRole('link', { name: '运行历史' });
    expect(runsOnList).toHaveAttribute('href', '/workflows/runs');
    expect(runsOnList).not.toHaveAttribute('aria-current');
    expect(within(nav).getByRole('link', { name: '流程列表' })).toHaveAttribute(
      'aria-current',
      'page',
    );

    cleanup();
    renderNav('/workflows/templates');
    nav = screen.getByRole('navigation', { name: '编排 Studio 导航' });
    expect(within(nav).getByRole('link', { name: '运行历史' })).not.toHaveAttribute('aria-current');
    expect(within(nav).getByRole('link', { name: '模板库' })).toHaveAttribute(
      'aria-current',
      'page',
    );
  });

  it('无 workflow.read 时运行历史入口随子导航一起隐藏，深链由页面守卫兜底', () => {
    renderNav('/workflows/runs/run-1', NO_PERMISSION_SESSION);

    expect(screen.queryByRole('navigation', { name: '编排 Studio 导航' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '运行历史' })).not.toBeInTheDocument();
    expect(screen.getByTestId('umi-outlet')).toBeInTheDocument();
  });

  it('375px 下子导航换行展示且运行历史、审批待办入口可聚焦', () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 375 });
    renderNav('/workflows/runs', APPROVER_SESSION);

    const nav = screen.getByRole('navigation', { name: '编排 Studio 导航' });
    expect(nav).toBeVisible();
    const templates = within(nav).getByRole('link', { name: '模板库' });
    expect(templates).toBeVisible();
    const runs = within(nav).getByRole('link', { name: '运行历史' });
    expect(runs).toBeVisible();
    expect(runs).toHaveAttribute('aria-current', 'page');
    runs.focus();
    expect(runs).toHaveFocus();
    const approvals = within(nav).getByRole('link', { name: '审批待办' });
    expect(approvals).toBeVisible();
    approvals.focus();
    expect(approvals).toHaveFocus();
  });
});
