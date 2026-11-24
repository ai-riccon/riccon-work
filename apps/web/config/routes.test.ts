import { describe, expect, it } from 'vitest';
import {
  adminNavigation,
  buildWorkspaceBreadcrumb,
  filterAdminNavigation,
  filterWorkspaceNavigation,
  findRouteByPath,
  isNavigationActive,
  routes,
  workspaceNavigation,
} from './routes';

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
      '/admin/model-routing',
      '/admin/model-keys',
      '/admin/connectors',
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

describe('工作空间导航与面包屑', () => {
  it('编排 Studio 挂在 workspace layout 下，保留重定向、列表、编辑器与 404', () => {
    const workflows = findRouteByPath('/workflows');
    expect(workflows?.component).toBe('@/layouts/workspace');
    expect(workflows?.routes?.map((route) => route.path)).toEqual([
      '/workflows',
      '/workflows/list',
      '/workflows/:workflowId/edit',
    ]);
    expect(workflows?.routes?.[0]).toEqual({
      path: '/workflows',
      redirect: '/workflows/list',
    });
    expect(findRouteByPath('/workflows/list')?.component).toBe('@/pages/workflow/list');
    expect(findRouteByPath('/workflows/list')?.title).toBe('编排 Studio');
    expect(findRouteByPath('/workflows/:workflowId/edit')?.component).toBe('@/pages/workflow/edit');
    expect(findRouteByPath('/workflows/:workflowId/edit')?.title).toBe('流程编辑');
    expect(findRouteByPath('*')?.component).toBe('@/pages/404');
    expect(findRouteByPath('/chat')?.component).toBe('@/pages/chat');
    expect(findRouteByPath('/research/tasks')?.component).toBe('@/pages/research');
  });

  it('编排 Studio 入口要求 workflow.read，既有员工入口不改变语义', () => {
    const byPath = new Map(workspaceNavigation.map((item) => [item.path, item]));

    expect(byPath.get('/workflows')?.permissions).toEqual(['workflow.read']);
    expect(byPath.get('/chat')?.permissions).toBeUndefined();
    expect(byPath.get('/research')?.permissions).toBeUndefined();
  });

  it('无 workflow.read 时隐藏编排 Studio，有权限时展示', () => {
    const visiblePaths = (permissions?: readonly string[]): string[] =>
      filterWorkspaceNavigation(permissions).map((item) => item.path);

    expect(visiblePaths()).toEqual(['/chat', '/research']);
    expect(visiblePaths(['workspace.read', 'conversation.read'])).toEqual(['/chat', '/research']);
    expect(visiblePaths(['workflow.read'])).toEqual(['/chat', '/research', '/workflows']);
    expect(visiblePaths(['workflow.read', 'workflow.run'])).toEqual([
      '/chat',
      '/research',
      '/workflows',
    ]);
  });

  it('每个工作空间菜单路径都对应已注册路由，避免入口落到 404', () => {
    workspaceNavigation.forEach((item) => {
      expect(findRouteByPath(item.path), `${item.path} 未注册路由`).toBeDefined();
    });
  });

  it('选中态按完整路径段匹配，覆盖列表与编辑器深链', () => {
    expect(isNavigationActive('/workflows', '/workflows')).toBe(true);
    expect(isNavigationActive('/workflows/list', '/workflows')).toBe(true);
    expect(isNavigationActive('/workflows/workflow-1/edit', '/workflows')).toBe(true);
    expect(isNavigationActive('/research/tasks', '/research')).toBe(true);
    expect(isNavigationActive('/research/task-1', '/research')).toBe(true);
    expect(isNavigationActive('/research-archive', '/research')).toBe(false);
    expect(isNavigationActive('/chat', '/workflows')).toBe(false);
    expect(isNavigationActive('/admin/overview', '/workflows')).toBe(false);
  });

  it('面包屑显示“工作空间 → 分区 → 页面”，标题相同时不重复', () => {
    expect(buildWorkspaceBreadcrumb('/workflows/list')).toEqual([
      { label: '工作空间', path: '/chat' },
      { label: '编排 Studio' },
    ]);
    expect(buildWorkspaceBreadcrumb('/workflows/workflow-1/edit')).toEqual([
      { label: '工作空间', path: '/chat' },
      { label: '编排 Studio', path: '/workflows' },
      { label: '流程编辑' },
    ]);
    expect(buildWorkspaceBreadcrumb('/research/tasks')).toEqual([
      { label: '工作空间', path: '/chat' },
      { label: '市场调研报告' },
    ]);
    expect(buildWorkspaceBreadcrumb('/research/task-1')).toEqual([
      { label: '工作空间', path: '/chat' },
      { label: '市场调研报告', path: '/research' },
      { label: '报告任务详情' },
    ]);
    expect(buildWorkspaceBreadcrumb('/chat')).toEqual([{ label: '工作空间' }]);
    expect(buildWorkspaceBreadcrumb('/admin/overview')).toEqual([]);
  });
});

describe('管理端导航权限过滤', () => {
  /** 读取指定权限下的可见菜单路径。 */
  const visiblePaths = (permissions?: readonly string[]): string[] =>
    filterAdminNavigation(permissions).map((item) => item.path);

  it('新增入口声明与后端 PermissionCodes 一致的权限码，既有入口不改变语义', () => {
    const byPath = new Map(adminNavigation.map((item) => [item.path, item]));

    expect(byPath.get('/admin/users')?.permissions).toEqual(['tenant.user.manage']);
    expect(byPath.get('/admin/model-routing')?.permissions).toEqual(['platform.model.read']);
    expect(byPath.get('/admin/model-keys')?.permissions).toEqual(['platform.model.read']);
    expect(byPath.get('/admin/connectors')?.permissions).toEqual(['knowledge.connector.read']);
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

  it('租户管理员看到用户管理、连接器、审计查询与租户审计导出，但看不到平台模型入口', () => {
    expect(
      visiblePaths([
        'tenant.user.manage',
        'knowledge.connector.read',
        'audit.read',
        'audit.export.approve',
      ]),
    ).toEqual([
      '/admin/overview',
      '/admin/tenants',
      '/admin/users',
      '/admin/connectors',
      '/admin/audit',
      '/admin/audit-exports',
      '/admin/settings',
    ]);
    expect(findRouteByPath('/admin/connectors')?.title).toBe('知识库连接器');
  });

  it('平台管理员看到模型路由与模型 key，菜单不落到 404', () => {
    const paths = visiblePaths([
      'platform.tenant.manage',
      'platform.model.read',
      'platform.model.manage',
    ]);
    expect(paths).toContain('/admin/model-routing');
    expect(paths).toContain('/admin/model-keys');
    expect(findRouteByPath('/admin/model-routing')?.title).toBe('模型路由');
    expect(findRouteByPath('/admin/model-keys')?.title).toBe('模型 key');
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
