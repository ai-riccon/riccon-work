import { Link, Outlet, useLocation } from '@umijs/max';
import { useAuth } from '@/services/auth/auth-store';
import { isNavigationActive } from '../../../../config/routes';
import { hasWorkflowPermission, WORKFLOW_PERMISSIONS } from '../list/format';
import './templates.css';

/** Studio 子导航可注入属性，便于组件测试固定路径。 */
export interface WorkflowStudioNavProps {
  /** 当前路径；默认读取 Umi 路由，测试可注入不同深链。 */
  pathname?: string;
}

/** Studio 子导航条目。 */
interface StudioLink {
  /** 目标路径 */
  path: string;
  /** 菜单文案 */
  label: string;
  /** 显示该入口所需的编排权限；省略表示随 Studio 子导航一起展示。 */
  permission?: string;
}

/** Studio 子导航链接；顺序与用户从列表进入模板库、审批待办的习惯一致。 */
const STUDIO_LINKS: readonly StudioLink[] = [
  { path: '/workflows/list', label: '流程列表' },
  { path: '/workflows/templates', label: '模板库' },
  { path: '/workflows/approvals', label: '审批待办', permission: WORKFLOW_PERMISSIONS.approve },
];

/**
 * 判断 Studio 子导航项是否处于选中态。
 *
 * “流程列表”覆盖 Studio 列表、编辑器以及其它 `/workflows/*` 页面（模板库与审批待办除外），
 * “模板库”“审批待办”只覆盖各自路径；三者互斥，避免同一页面出现多个 `aria-current="page"`。
 *
 * @param pathname 当前浏览器路径
 * @param path 子导航路径
 * @returns 是否为当前 Studio 子页面
 */
function isStudioNavActive(pathname: string, path: string): boolean {
  if (path === '/workflows/templates' || path === '/workflows/approvals') {
    return isNavigationActive(pathname, path);
  }
  return (
    isNavigationActive(pathname, '/workflows') &&
    !isNavigationActive(pathname, '/workflows/templates') &&
    !isNavigationActive(pathname, '/workflows/approvals')
  );
}

/**
 * 编排 Studio 路由包装组件。
 *
 * T14b 通过 Umi `wrappers` 把“流程列表 / 模板库”子导航挂到 Studio 的列表、编辑器与模板库
 * 三个页面；T13b 追加“审批待办”，只对持有 `workflow.approve` 的会话展示，用户可从任意
 * Studio 页面直接进入审核队列。外层仍按 `workflow.read` 过滤：无权限时不渲染子导航，
 * 页面自身守卫与服务端 403/404 仍然生效。
 *
 * 放在 `pages/workflow/templates/` 下是 T14b 写入范围（`templates/**`）的约束；
 * 组件本身是 Studio 共用外壳，后续如需独立布局可整体迁移。
 *
 * @param props 可注入的当前路径
 * @returns 子导航与页面出口
 */
export default function WorkflowStudioNav({ pathname: injectedPathname }: WorkflowStudioNavProps) {
  const auth = useAuth();
  const location = useLocation();
  const pathname = injectedPathname ?? location.pathname;
  const canRead = hasWorkflowPermission(auth.session?.permissions, WORKFLOW_PERMISSIONS.read);
  const visibleLinks = STUDIO_LINKS.filter(
    (item) =>
      item.permission === undefined ||
      hasWorkflowPermission(auth.session?.permissions, item.permission),
  );

  return (
    <>
      {canRead ? (
        <nav aria-label="编排 Studio 导航" className="workflow-studio-nav">
          <ol>
            {visibleLinks.map((item) => {
              const active = isStudioNavActive(pathname, item.path);
              return (
                <li key={item.path}>
                  <Link aria-current={active ? 'page' : undefined} to={item.path}>
                    {item.label}
                  </Link>
                </li>
              );
            })}
          </ol>
        </nav>
      ) : null}
      <Outlet />
    </>
  );
}
