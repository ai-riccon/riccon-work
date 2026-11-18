import { ApartmentOutlined, DashboardOutlined, SettingOutlined } from '@ant-design/icons';
import { ProLayout } from '@ant-design/pro-components';
import { Link, Outlet, useLocation } from '@umijs/max';
import type { ReactNode } from 'react';
import { adminNavigation } from '../../../config/routes';

/** 管理端菜单图标映射，路径与 config/routes.ts 中的 adminNavigation 保持一致。 */
const MENU_ICONS: Record<string, ReactNode> = {
  '/admin/overview': <DashboardOutlined />,
  '/admin/tenants': <ApartmentOutlined />,
  '/admin/settings': <SettingOutlined />,
};

/**
 * 管理端 ProLayout 布局。
 *
 * 职责：只负责外壳（侧边菜单、品牌区、内容区），页面内容由子路由 Outlet 渲染。
 * 权限：M03 接入 RBAC 后，菜单与路由守卫必须由服务端权限结果驱动，不得仅靠前端隐藏。
 *
 * @returns 带 ProLayout 外壳的管理端页面
 */
export default function AdminLayout() {
  const location = useLocation();

  return (
    <ProLayout
      // ProLayout 3.1.15-9 的折叠按钮是带 aria-label 的 div（无 role），axe 判为 serious；
      // 骨架期先关闭折叠按钮，待 Pro 修复或 M02 自绘侧边栏时恢复。
      collapsedButtonRender={false}
      fixSiderbar
      layout="side"
      location={{ pathname: location.pathname }}
      logo={false}
      menuItemRender={(item, dom) => (item.path ? <Link to={item.path}>{dom}</Link> : dom)}
      route={{
        path: '/admin',
        routes: adminNavigation.map((item) => ({
          path: item.path,
          name: item.label,
          icon: MENU_ICONS[item.path],
        })),
      }}
      title="Riccon 管理端"
    >
      <Outlet />
    </ProLayout>
  );
}
