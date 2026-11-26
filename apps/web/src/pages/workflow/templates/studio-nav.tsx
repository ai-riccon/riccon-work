import { InfoCircleOutlined } from '@ant-design/icons';
import { Link, Outlet, useLocation } from '@umijs/max';
import { Button } from 'antd';
import { useState } from 'react';
import { WorkflowRuntimeLimitsDrawer } from '@/components/workflow/runtime-limits';
import type { WorkflowRuntimeLimitsApi } from '@/components/workflow/runtime-limits-api';
import { useAuth } from '@/services/auth/auth-store';
import { isNavigationActive } from '../../../../config/routes';
import { hasWorkflowPermission, WORKFLOW_PERMISSIONS } from '../list/format';
import './templates.css';

/** Studio 子导航可注入属性，便于组件测试固定路径。 */
export interface WorkflowStudioNavProps {
  /** 当前路径；默认读取 Umi 路由，测试可注入不同深链。 */
  pathname?: string;
  /** 运行限制 API；默认同源真实客户端，仅测试注入替身。 */
  runtimeLimitsApi?: WorkflowRuntimeLimitsApi;
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

/** Studio 子导航链接；顺序与用户从列表进入模板库、运行历史与审批待办的习惯一致。 */
const STUDIO_LINKS: readonly StudioLink[] = [
  { path: '/workflows/list', label: '流程列表' },
  { path: '/workflows/templates', label: '模板库' },
  // 运行历史需要 workflow.read，与子导航本身同一权限；发布/运行等动作仍按各自权限在页面内显隐。
  { path: '/workflows/runs', label: '运行历史' },
  { path: '/workflows/approvals', label: '审批待办', permission: WORKFLOW_PERMISSIONS.approve },
];

/** 拥有独立入口的 Studio 子页面路径，用于让“流程列表”与它们互斥。 */
const STUDIO_SUBPAGE_PATHS: readonly string[] = STUDIO_LINKS.filter(
  (item) => item.path !== '/workflows/list',
).map((item) => item.path);

/**
 * 判断 Studio 子导航项是否处于选中态。
 *
 * 除“流程列表”外的入口只覆盖各自路径及其下级深链（例如 `/workflows/runs/:id` 仍高亮运行历史）；
 * “流程列表”覆盖 Studio 其余路径并排除这些子页面，保证任意页面最多一个 `aria-current="page"`。
 *
 * @param pathname 当前浏览器路径
 * @param path 子导航路径
 * @returns 是否为当前 Studio 子页面
 */
function isStudioNavActive(pathname: string, path: string): boolean {
  if (path !== '/workflows/list') {
    return isNavigationActive(pathname, path);
  }
  return (
    isNavigationActive(pathname, '/workflows') &&
    !STUDIO_SUBPAGE_PATHS.some((subpage) => isNavigationActive(pathname, subpage))
  );
}

/**
 * 编排 Studio 路由包装组件。
 *
 * T14b 通过 Umi `wrappers` 把“流程列表 / 模板库”子导航挂到 Studio 的列表、编辑器与模板库
 * 三个页面；T13b 追加“审批待办”，只对持有 `workflow.approve` 的会话展示，用户可从任意
 * Studio 页面直接进入审核队列；T12c 追加“运行历史”，挂载到历史页与运行详情深链，方便在
 * 查看单条运行后切换流程或进入审核队列。外层仍按 `workflow.read` 过滤：无权限时不渲染子导航，
 * 页面自身守卫与服务端 403/404 仍然生效。
 *
 * 放在 `pages/workflow/templates/` 下是 T14b 写入范围（`templates/**`）的约束；
 * 组件本身是 Studio 共用外壳，后续如需独立布局可整体迁移。
 *
 * @param props 可注入的当前路径
 * @returns 子导航与页面出口
 */
export default function WorkflowStudioNav({
  pathname: injectedPathname,
  runtimeLimitsApi,
}: WorkflowStudioNavProps) {
  const auth = useAuth();
  const location = useLocation();
  const pathname = injectedPathname ?? location.pathname;
  const canRead = hasWorkflowPermission(auth.session?.permissions, WORKFLOW_PERMISSIONS.read);
  const [limitsOpen, setLimitsOpen] = useState(false);
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
          <div className="workflow-studio-help">
            <Button
              type="link"
              size="small"
              icon={<InfoCircleOutlined aria-hidden="true" />}
              onClick={() => setLimitsOpen(true)}
            >
              运行限制
            </Button>
          </div>
        </nav>
      ) : null}
      <WorkflowRuntimeLimitsDrawer
        open={limitsOpen}
        onClose={() => setLimitsOpen(false)}
        api={runtimeLimitsApi}
      />
      <Outlet />
    </>
  );
}
