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
        // 父级布局可与同路径页面（如 /workflows/templates）共存，这里只检查真正渲染页面的路由。
        if (node.path && node.component?.startsWith('@/pages/')) {
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

describe('编排 Studio 模板库路由与入口', () => {
  /** Studio 子导航路由包装路径。 */
  const STUDIO_NAV = '@/pages/workflow/templates/studio-nav';

  it('模板库挂 workspace layout，既有 /workflows 重定向、列表、编辑器与 404 保持不变', () => {
    const workflows = findRouteByPath('/workflows');
    expect(workflows?.component).toBe('@/layouts/workspace');
    // T10b 已验收的父子结构保持不变；模板库使用独立布局节点，避免影响既有深链与回归。
    expect(workflows?.routes?.map((route) => route.path)).toEqual([
      '/workflows',
      '/workflows/list',
      '/workflows/:workflowId/edit',
    ]);
    expect(workflows?.routes?.[0]).toEqual({ path: '/workflows', redirect: '/workflows/list' });
    expect(findRouteByPath('/workflows/list')?.component).toBe('@/pages/workflow/list');
    expect(findRouteByPath('/workflows/:workflowId/edit')?.component).toBe('@/pages/workflow/edit');
    expect(findRouteByPath('/login')?.component).toBe('@/pages/login');
    expect(findRouteByPath('*')?.component).toBe('@/pages/404');
    expect(findRouteByPath('/chat')?.component).toBe('@/pages/chat');
    expect(findRouteByPath('/research/tasks')?.component).toBe('@/pages/research');
  });

  it('模板库页面注册到 workspace layout，并提供标题与异步页面组件', () => {
    const layout = findRouteByPath('/workflows/templates');
    expect(layout?.component).toBe('@/layouts/workspace');
    const page = layout?.routes?.[0];
    expect(page?.path).toBe('/workflows/templates');
    expect(page?.component).toBe('@/pages/workflow/templates');
    expect(page?.title).toBe('模板库');
  });

  it('列表、编辑器与模板库通过同一 Studio 子导航包装提供模板库入口', () => {
    expect(findRouteByPath('/workflows/list')?.wrappers).toEqual([STUDIO_NAV]);
    expect(findRouteByPath('/workflows/:workflowId/edit')?.wrappers).toEqual([STUDIO_NAV]);
    expect(findRouteByPath('/workflows/templates')?.routes?.[0]?.wrappers).toEqual([STUDIO_NAV]);
  });

  it('模板库归入编排 Studio 分区：入口要求 workflow.read，选中态与面包屑正确', () => {
    const studio = workspaceNavigation.find((item) => item.path === '/workflows');
    expect(studio?.permissions).toEqual(['workflow.read']);
    // 模板库不新增顶层导航项，避免与“编排 Studio”重复高亮；仍由 Studio 分区承载选中态。
    expect(workspaceNavigation.map((item) => item.path)).toEqual([
      '/chat',
      '/research',
      '/workflows',
    ]);
    expect(isNavigationActive('/workflows/templates', '/workflows')).toBe(true);
    expect(filterWorkspaceNavigation(['workflow.read']).map((item) => item.path)).toEqual([
      '/chat',
      '/research',
      '/workflows',
    ]);
    expect(filterWorkspaceNavigation(['workspace.read']).map((item) => item.path)).toEqual([
      '/chat',
      '/research',
    ]);
    expect(buildWorkspaceBreadcrumb('/workflows/templates')).toEqual([
      { label: '工作空间', path: '/chat' },
      { label: '编排 Studio', path: '/workflows' },
      { label: '模板库' },
    ]);
  });
});

describe('编排 Studio 审批待办路由与入口', () => {
  /** Studio 子导航路由包装路径。 */
  const STUDIO_NAV = '@/pages/workflow/templates/studio-nav';

  it('审批待办挂 workspace layout，待办与详情路由使用同一 Studio 子导航', () => {
    const layout = findRouteByPath('/workflows/approvals');
    expect(layout?.component).toBe('@/layouts/workspace');
    expect(layout?.routes?.map((route) => route.path)).toEqual([
      '/workflows/approvals',
      '/workflows/approvals/:workflowApprovalId',
    ]);

    const inbox = layout?.routes?.[0];
    expect(inbox?.component).toBe('@/pages/workflow/approvals');
    expect(inbox?.title).toBe('审批待办');
    expect(inbox?.wrappers).toEqual([STUDIO_NAV]);

    const detail = layout?.routes?.[1];
    expect(detail?.component).toBe('@/pages/workflow/approvals/detail');
    expect(detail?.title).toBe('审批详情');
    expect(detail?.wrappers).toEqual([STUDIO_NAV]);
  });

  it('既有列表、编辑器、模板库、问答、调研、管理端、登录与 404 兜底保持注册', () => {
    // T10b 的 /workflows 父子结构不因审批待办而变化，避免影响已验收深链。
    expect(findRouteByPath('/workflows')?.routes?.map((route) => route.path)).toEqual([
      '/workflows',
      '/workflows/list',
      '/workflows/:workflowId/edit',
    ]);
    expect(findRouteByPath('/workflows/list')?.component).toBe('@/pages/workflow/list');
    expect(findRouteByPath('/workflows/:workflowId/edit')?.component).toBe('@/pages/workflow/edit');
    expect(findRouteByPath('/workflows/templates')?.component).toBe('@/layouts/workspace');
    expect(findRouteByPath('/workflows/templates')?.routes?.[0]?.component).toBe(
      '@/pages/workflow/templates',
    );
    expect(findRouteByPath('/chat')?.component).toBe('@/pages/chat');
    expect(findRouteByPath('/research/tasks')?.component).toBe('@/pages/research');
    expect(findRouteByPath('/research/:researchTaskId')?.component).toBe('@/pages/research/detail');
    expect(findRouteByPath('/admin/overview')?.component).toBe('@/pages/admin/overview');
    expect(findRouteByPath('/login')?.component).toBe('@/pages/login');
    expect(findRouteByPath('*')?.component).toBe('@/pages/404');
  });

  it('审批待办归入编排 Studio 分区：不新增顶层导航，选中态与面包屑正确', () => {
    // 顶层导航仍只有问答/调研/编排三项，审批待办由 Studio 子导航承载，避免重复高亮。
    expect(workspaceNavigation.map((item) => item.path)).toEqual([
      '/chat',
      '/research',
      '/workflows',
    ]);
    expect(isNavigationActive('/workflows/approvals', '/workflows')).toBe(true);
    expect(isNavigationActive('/workflows/approvals/approval-1', '/workflows')).toBe(true);
    expect(buildWorkspaceBreadcrumb('/workflows/approvals')).toEqual([
      { label: '工作空间', path: '/chat' },
      { label: '编排 Studio', path: '/workflows' },
      { label: '审批待办' },
    ]);
    expect(buildWorkspaceBreadcrumb('/workflows/approvals/approval-1')).toEqual([
      { label: '工作空间', path: '/chat' },
      { label: '编排 Studio', path: '/workflows' },
      { label: '审批详情' },
    ]);
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
