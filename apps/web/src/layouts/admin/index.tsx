import {
  ApartmentOutlined,
  AuditOutlined,
  DashboardOutlined,
  ExportOutlined,
  SettingOutlined,
  TeamOutlined,
} from '@ant-design/icons';
import { ProLayout } from '@ant-design/pro-components';
import { Link, Outlet, useLocation } from '@umijs/max';
import { useEffect } from 'react';
import type { ReactNode } from 'react';
import { api as defaultApi } from '@/services/api';
import type { SessionReader } from '@/services/auth/auth-store';
import { authStore, useAuth } from '@/services/auth/auth-store';
import { filterAdminNavigation } from '../../../config/routes';

/** 管理端菜单图标映射，路径与 config/routes.ts 中的 adminNavigation 保持一致。 */
const MENU_ICONS: Record<string, ReactNode> = {
  '/admin/overview': <DashboardOutlined />,
  '/admin/tenants': <ApartmentOutlined />,
  '/admin/users': <TeamOutlined />,
  '/admin/audit': <AuditOutlined />,
  '/admin/audit-exports': <ExportOutlined />,
  '/admin/settings': <SettingOutlined />,
};

/** 管理端布局可注入依赖，便于组件测试；生产由默认 API 恢复会话。 */
export interface AdminLayoutProps {
  /** 会话读取能力；只读取 `/auth/session`，不涉及其他管理接口。 */
  api?: SessionReader;
}

/**
 * 管理端 ProLayout 布局。
 *
 * 职责：只负责外壳（侧边菜单、品牌区、内容区），页面内容由子路由 Outlet 渲染。
 * 权限：菜单按会话权限过滤，普通员工看不到审计与用户管理入口；前端隐藏不代替服务端鉴权，
 * 直接访问仍由页面守卫与服务端 403 兜底。
 *
 * @param props 会话读取能力注入
 * @returns 带 ProLayout 外壳的管理端页面
 */
export default function AdminLayout({ api = defaultApi }: AdminLayoutProps) {
  const auth = useAuth();
  const location = useLocation();

  // 管理端各页面也会按需恢复会话；这里先恢复一次，保证侧边菜单能按最新权限过滤。
  // authStore 对并发 restore 去重，页面与布局同时挂载时只会产生一次会话请求。
  useEffect(() => {
    if (auth.status === 'unknown') {
      void authStore.restore(api);
    }
  }, [api, auth.status]);

  const navigation = filterAdminNavigation(auth.session?.permissions);

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
        // 使用 children 而不是 routes：pro-components 3.x 的菜单 useMemo 依赖 route.children，
        // 会话恢复后菜单项变化才能触发重算（routes 只在首帧生效）。
        children: navigation.map((item) => ({
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
