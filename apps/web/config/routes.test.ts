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

  it('嵌套路由可递归查找，未命中路径返回 undefined', () => {
    expect(findRouteByPath('/admin/settings')?.title).toBe('系统设置');
    expect(findRouteByPath('/admin/overview')?.component).toBe('@/pages/admin/overview');
    expect(findRouteByPath('/not-exist')).toBeUndefined();
  });

  it('每个页面路由都有标题，便于浏览器标题与菜单展示', () => {
    const pageRoutes: typeof routes = [];
    const collect = (nodes: typeof routes) => {
      nodes.forEach((node) => {
        // 布局路由不直接渲染页面，标题由子路由提供；这里只检查 @/pages/ 下的页面。
        if (node.path && node.component?.startsWith('@/pages/')) {
          pageRoutes.push(node);
        }
        if (node.routes) {
          collect(node.routes);
        }
      });
    };
    collect(routes);

    pageRoutes.forEach((route) => {
      expect(route.title, `${route.path} 缺少 title`).toBeTruthy();
    });
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
