import { describe, expect, it } from 'vitest';
import { adminNavigation, filterAdminNavigation, findRouteByPath, routes } from './routes';

describe('路由表', () => {
  it('根路径重定向到员工端工作空间', () => {
    const root = findRouteByPath('/');
    expect(root?.redirect).toBe('/chat');
  });

  it('员工端与管理端都有独立页面入口', () => {
    expect(findRouteByPath('/chat')?.component).toBe('@/pages/chat');
    expect(findRouteByPath('/admin')?.component).toBe('@/layouts/admin');
  });

  it('管理端默认进入总览页，并提供总览、租户、用户、审计与设置入口', () => {
    const admin = findRouteByPath('/admin');
    expect(admin?.routes?.[0]).toEqual({
      path: '/admin',
      redirect: '/admin/overview',
    });
    expect(adminNavigation.map((item) => item.path)).toEqual([
      '/admin/overview',
      '/admin/tenants',
      '/admin/users',
      '/admin/audit',
      '/admin/audit-exports',
      '/admin/settings',
    ]);
  });

  it('报告任务列表与详情使用独立布局', () => {
    expect(findRouteByPath('/research')?.component).toBe('@/layouts/workspace');
    expect(findRouteByPath('/research/tasks')?.component).toBe('@/pages/research');
    expect(findRouteByPath('/research/:researchTaskId')?.component).toBe('@/pages/research/detail');
    expect(findRouteByPath('/research/tasks')?.title).toBe('市场调研报告');
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

describe('管理端导航权限过滤', () => {
  /** 读取指定权限下的可见菜单路径。 */
  const visiblePaths = (permissions?: readonly string[]): string[] =>
    filterAdminNavigation(permissions).map((item) => item.path);

  it('新增入口声明与后端 PermissionCodes 一致的权限码，既有入口不改变语义', () => {
    const byPath = new Map(adminNavigation.map((item) => [item.path, item]));

    expect(byPath.get('/admin/users')?.permissions).toEqual(['tenant.user.manage']);
    expect(byPath.get('/admin/audit')?.permissions).toEqual(['audit.read']);
    expect(byPath.get('/admin/audit-exports')?.permissions).toEqual(['audit.export.approve']);
    expect(byPath.get('/admin/overview')?.permissions).toBeUndefined();
    expect(byPath.get('/admin/tenants')?.permissions).toBeUndefined();
    expect(byPath.get('/admin/settings')?.permissions).toBeUndefined();
  });

  it('会话未加载时只保留既有总览、租户与设置入口', () => {
    expect(visiblePaths()).toEqual(['/admin/overview', '/admin/tenants', '/admin/settings']);
  });

  it('普通员工没有审计或账号管理权限时看不到新增入口', () => {
    expect(visiblePaths(['workspace.read', 'conversation.read'])).toEqual([
      '/admin/overview',
      '/admin/tenants',
      '/admin/settings',
    ]);
  });

  it('租户管理员看到用户管理、审计查询与租户审计导出', () => {
    expect(visiblePaths(['tenant.user.manage', 'audit.read', 'audit.export.approve'])).toEqual([
      '/admin/overview',
      '/admin/tenants',
      '/admin/users',
      '/admin/audit',
      '/admin/audit-exports',
      '/admin/settings',
    ]);
  });

  it('平台管理员只看到平台范围内可用的审计查询', () => {
    expect(
      visiblePaths(['platform.tenant.manage', 'audit.read', 'platform.audit.export.approve']),
    ).toEqual(['/admin/overview', '/admin/tenants', '/admin/audit', '/admin/settings']);
  });

  it('每个菜单路径都对应已注册路由，避免入口落到 404', () => {
    adminNavigation.forEach((item) => {
      expect(findRouteByPath(item.path), `${item.path} 未注册路由`).toBeDefined();
    });
  });
});
