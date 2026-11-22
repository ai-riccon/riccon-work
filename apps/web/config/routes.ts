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
      {
        path: '/admin/tenants',
        component: '@/pages/admin/tenants',
        title: '租户与部门',
      },
      {
        path: '/admin/users',
        component: '@/pages/admin/users',
        title: '用户管理',
      },
      {
        path: '/admin/model-routing',
        component: '@/pages/admin/model-routing',
        title: '模型路由',
      },
      {
        path: '/admin/model-keys',
        component: '@/pages/admin/model-keys',
        title: '模型 key',
      },
      {
        path: '/admin/quotas',
        component: '@/pages/admin/quotas',
        title: '配额',
      },
      {
        path: '/admin/costs',
        component: '@/pages/admin/costs',
        title: '成本',
      },
      {
        path: '/admin/tenants/:tenantId/users',
        component: '@/pages/admin/users',
        title: '租户用户管理',
      },
      {
        path: '/admin/audit',
        component: '@/pages/admin/audit',
        title: '审计日志',
      },
      {
        path: '/admin/audit-exports',
        component: '@/pages/admin/tenant-exports',
        title: '审计导出',
      },
      {
        path: '/admin/tenants/:tenantId/audit-exports',
        component: '@/pages/admin/tenant-exports/platform',
        title: '平台审计导出审批',
      },
      {
        path: '/admin/tenants/:tenantId/departments',
        component: '@/pages/admin/tenants/departments',
        title: '部门管理',
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
  /**
   * 显示该菜单所需的权限码；任一命中即可。
   *
   * 省略表示保持既有入口语义：菜单始终渲染，最终授权由服务端 403 决定。
   * 权限码取值与 `PermissionCodes` 及 `services` 下的 access 常量保持一致。
   */
  permissions?: readonly string[];
}

/**
 * 管理端左侧菜单。
 *
 * 租户管理是平台管理员入口；菜单可见性不代替服务端鉴权，普通账号直接访问仍会得到 403。
 * M10 新增用户管理与审计入口：用户管理只属于租户管理员（平台管理员按租户上下文进入
 * `/admin/tenants/:tenantId/users`）；审计查询对租户/平台管理员可见；审计导出是租户管理员
 * 入口，平台审批页需要租户上下文，无法用静态菜单表达。
 * M04 新增模型路由与模型 key：两者都是平台全局资源，只按 `platform.model.read` 展示；
 * M04 B9b 追加配额/成本入口：同一路径在平台与租户角色下文案不同（租户配额/配额、
 * 成本看板/成本），因此单独声明 `quotaCostNavigation`，由 `filterAdminNavigation` 在
 * 审计入口之前合并，保证“路由 / key / 配额 / 成本”四页在平台管理员菜单中连续可见。
 */
export const adminNavigation: NavigationItem[] = [
  { path: '/admin/overview', label: '管理总览' },
  { path: '/admin/tenants', label: '租户管理' },
  { path: '/admin/users', label: '用户管理', permissions: ['tenant.user.manage'] },
  {
    path: '/admin/model-routing',
    label: '模型路由',
    permissions: ['platform.model.read'],
  },
  {
    path: '/admin/model-keys',
    label: '模型 key',
    permissions: ['platform.model.read'],
  },
  { path: '/admin/audit', label: '审计查询', permissions: ['audit.read'] },
  {
    path: '/admin/audit-exports',
    label: '审计导出',
    permissions: ['audit.export.approve'],
  },
  { path: '/admin/settings', label: '系统设置' },
];

/**
 * M04 配额/成本入口。
 *
 * 同一路径在平台与租户角色下需要不同菜单文案（AC-13：平台管理员看到“租户配额 / 成本看板”，
 * 租户管理员只看到“配额 / 成本”），而 `NavigationItem` 一条记录只有一个 label，
 * 因此按权限分别声明，由 `filterAdminNavigation` 合并。平台管理员与租户管理员的权限集合
 * 互斥（`platform.quota.manage`/`platform.cost.read` 只授平台角色，`quota.read`/`cost.read`
 * 只授租户管理员），正常情况下同一角色只会命中一条。
 */
export const quotaCostNavigation: NavigationItem[] = [
  { path: '/admin/quotas', label: '租户配额', permissions: ['platform.quota.manage'] },
  { path: '/admin/quotas', label: '配额', permissions: ['quota.read'] },
  { path: '/admin/costs', label: '成本看板', permissions: ['platform.cost.read'] },
  { path: '/admin/costs', label: '成本', permissions: ['cost.read'] },
];

/**
 * 把配额/成本入口插入到审计入口之前。
 *
 * 平台管理员期望顺序是“模型路由 → 模型 key → 租户配额 → 成本看板”，租户管理员是
 * “用户管理 → 配额 → 成本 → 审计查询”；把新增项放在审计之前可同时满足两者，
 * 且没有 audit 权限时不臆造位置，直接追加到末尾。
 *
 * @param base 既有可见菜单
 * @param extra 配额/成本可见菜单
 * @returns 合并后的菜单
 */
function mergeQuotaCostNavigation(
  base: NavigationItem[],
  extra: NavigationItem[],
): NavigationItem[] {
  if (extra.length === 0) {
    return base;
  }
  const auditIndex = base.findIndex((item) => item.path === '/admin/audit');
  if (auditIndex < 0) {
    return [...base, ...extra];
  }
  return [...base.slice(0, auditIndex), ...extra, ...base.slice(auditIndex)];
}

/**
 * 按会话权限过滤管理端菜单。
 *
 * 未声明 `permissions` 的既有入口不参与前端过滤，避免改变总览/租户/设置的既有布局语义；
 * 声明了权限的入口要求至少命中一个权限码，因此普通员工看不到审计、用户管理、模型与配额/成本入口。
 *
 * @param permissions 当前会话权限码；会话未加载时传空数组
 * @returns 当前会话可见的菜单项
 */
export function filterAdminNavigation(permissions: readonly string[] = []): NavigationItem[] {
  const visible = adminNavigation.filter(
    (item) =>
      item.permissions === undefined ||
      item.permissions.some((permission) => permissions.includes(permission)),
  );
  const quotaCost = quotaCostNavigation.filter((item) =>
    item.permissions?.some((permission) => permissions.includes(permission)),
  );
  return mergeQuotaCostNavigation(visible, quotaCost);
}

/** 应用标题，集中定义避免多处硬编码。 */
export const APP_TITLE = 'Riccon';
