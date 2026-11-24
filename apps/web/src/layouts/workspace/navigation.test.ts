import { describe, expect, it } from 'vitest';
import {
  buildWorkspaceBreadcrumb,
  filterWorkspaceNavigation,
  findRouteByPath,
  isNavigationActive,
  workspaceNavigation,
} from '../../../config/routes';

/**
 * 工作空间路由与导航元数据测试。
 *
 * 路由注册与导航过滤都在 `config/routes.ts` 中定义，但 T10b 的写入范围只覆盖
 * `config/routes.ts` 本体与工作空间布局；因此从布局目录断言同一份元数据，
 * 既避免复制路由表，也不修改已有的 `config/routes.test.ts`。
 */
describe('工作空间路由与导航', () => {
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
