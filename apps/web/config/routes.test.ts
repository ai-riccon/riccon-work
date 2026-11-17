import { describe, expect, it } from 'vitest';
import { adminNavigation, findRouteByPath, routes } from './routes';

describe('路由表', () => {
  it('根路径重定向到员工端工作空间', () => {
    const root = findRouteByPath('/');
    expect(root?.redirect).toBe('/chat');
  });

  it('员工端与管理端都有独立页面入口', () => {
    expect(findRouteByPath('/chat')?.component).toBe('@/pages/chat');
    expect(findRouteByPath('/admin')?.component).toBe('@/layouts/admin');
  });

  it('管理端默认进入总览页，且至少有两个占位菜单', () => {
    const admin = findRouteByPath('/admin');
    expect(admin?.routes?.[0]).toEqual({
      path: '/admin',
      redirect: '/admin/overview',
    });
    expect(adminNavigation.map((item) => item.path)).toEqual([
      '/admin/overview',
      '/admin/settings',
    ]);
  });

  it('登录页与 404 兜底页存在', () => {
    expect(findRouteByPath('/login')?.component).toBe('@/pages/login');
    expect(findRouteByPath('*')?.component).toBe('@/pages/404');
  });

  it('页面路由不重复，避免 Umi 构建时静默覆盖', () => {
    const pagePaths: string[] = [];
    const collect = (nodes: typeof routes) => {
      nodes.forEach((node) => {
        // 父级布局与同路径的 index 重定向允许共存，这里只检查真正渲染页面的路由。
        if (node.path && node.component) {
          pagePaths.push(node.path);
        }
        if (node.routes) {
          collect(node.routes);
        }
      });
    };
    collect(routes);
    expect(new Set(pagePaths).size).toBe(pagePaths.length);
  });
});
