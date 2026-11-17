// @vitest-environment jsdom
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import AdminLayout from './index';

/**
 * 管理端 ProLayout 布局组件测试。
 *
 * 断言外壳行为：品牌标题、菜单链接地址、当前路由的激活态，以及子路由 Outlet 的挂载，
 * 避免只做“能渲染”的快照断言。
 */
describe('管理端 ProLayout 布局', () => {
  it('渲染品牌标题、菜单链接与子路由出口', () => {
    render(<AdminLayout />);

    expect(screen.getByText('Riccon 管理端')).toBeVisible();
    expect(screen.getByRole('link', { name: /管理总览/ })).toHaveAttribute(
      'href',
      '/admin/overview',
    );
    expect(screen.getByRole('link', { name: /系统设置/ })).toHaveAttribute(
      'href',
      '/admin/settings',
    );
    expect(screen.getByTestId('umi-outlet')).toBeInTheDocument();
  });

  it('按当前路径高亮对应菜单项', () => {
    const { container } = render(<AdminLayout />);

    const activeItem = container.querySelector('.ant-menu-item-selected');
    expect(activeItem).not.toBeNull();
    expect(activeItem).toHaveTextContent('管理总览');
  });
});
