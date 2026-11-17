/**
 * 路由元数据类型。
 * 只描述 Umi 需要的字段，不引入 Umi 运行时类型，保证该模块可被 Vitest 在 Node 环境直接导入测试。
 */
export interface RicconRoute {
  /** 路由路径；父路由可省略 */
  path?: string;
  /** 重定向目标；与 component 互斥 */
  redirect?: string;
  /** 页面组件路径，使用 Umi 的 @/ 别名 */
  component?: string;
  /** 子路由 */
  routes?: RicconRoute[];
  /** 路由标题，用于页面 title 与菜单展示 */
  title?: string;
}

/**
 * Riccon Web 的顶层路由表。
 *
 * 约定：
 * 1. 根路径重定向到员工端 /chat，避免出现空白首页；
 * 2. /chat 与 /admin 各自持有独立布局，员工端不加载管理端菜单；
 * 3. 登录页在 M02 接入认证前只做占位，不包含任何真实凭据逻辑。
 */
export const routes: RicconRoute[] = [
  { path: '/', redirect: '/chat' },
  { path: '/chat', component: '@/pages/chat', title: '工作空间' },
  {
    path: '/research',
    component: '@/layouts/workspace',
    routes: [
      { path: '/research', redirect: '/research/tasks' },
      {
        path: '/research/tasks',
        component: '@/pages/research',
        title: '市场调研报告',
      },
      {
        path: '/research/:researchTaskId',
        component: '@/pages/research/detail',
        title: '报告任务详情',
      },
    ],
  },
  {
    path: '/admin',
    component: '@/layouts/admin',
    routes: [
      { path: '/admin', redirect: '/admin/overview' },
      {
        path: '/admin/overview',
        component: '@/pages/admin/overview',
        title: '管理总览',
      },
      {
        path: '/admin/settings',
        component: '@/pages/admin/settings',
        title: '系统设置',
      },
    ],
  },
  { path: '/login', component: '@/pages/login', title: '登录' },
  { path: '*', component: '@/pages/404', title: '页面不存在' },
];

/**
 * 从路由表中查找指定路径的节点。
 *
 * 仅用于测试与菜单高亮等只读场景，不参与 Umi 运行时路由匹配。
 *
 * @param path 目标路径
 * @returns 命中的路由节点，未命中时返回 undefined
 */
export function findRouteByPath(path: string): RicconRoute | undefined {
  const walk = (nodes: RicconRoute[]): RicconRoute | undefined => {
    for (const node of nodes) {
      if (node.path === path) {
        return node;
      }
      if (node.routes) {
        const matched = walk(node.routes);
        if (matched) {
          return matched;
        }
      }
    }
    return undefined;
  };

  return walk(routes);
}

/** 员工端与管理端共用的导航项类型，供布局与测试复用。 */
export interface NavigationItem {
  /** 菜单跳转路径 */
  path: string;
  /** 菜单文案 */
  label: string;
}

/** 管理端左侧菜单：M01 只有总览与设置两个占位入口。 */
export const adminNavigation: NavigationItem[] = [
  { path: '/admin/overview', label: '管理总览' },
  { path: '/admin/settings', label: '系统设置' },
];

/** 应用标题，集中定义避免多处硬编码。 */
export const APP_TITLE = 'Riccon';
