import { LogoutOutlined } from '@ant-design/icons';
import { Link, Outlet, useLocation, useNavigate } from '@umijs/max';
import { Alert, Button, Flex, Result, Spin, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { api as defaultApi } from '@/services/api';
import type { RicconApi, WorkspaceManagementApi } from '@/services/api/client';
import { describeApiError } from '@/services/api/errors';
import { authStore, useAuth } from '@/services/auth/auth-store';
import { CurrentDepartmentBadge } from '@/services/workspaces/current-department-badge';
import { supportsWorkspaceManagement } from '@/services/workspaces/management-api';
import { useWorkspaceManagement } from '@/services/workspaces/use-workspace-management';
import {
  buildWorkspaceBreadcrumb,
  filterWorkspaceNavigation,
  isNavigationActive,
} from '../../../config/routes';
import './workspace.css';

/** 报告任务工作空间外壳的可注入依赖，便于组件测试。 */
export interface WorkspaceLayoutProps {
  api?: RicconApi;
  /** 空间管理能力；未注入时从 api 能力检测。 */
  managementApi?: WorkspaceManagementApi;
  navigate?: (to: string, options?: { replace?: boolean }) => void;
  /** 当前路径；默认读取 Umi 路由，测试可注入不同深链。 */
  pathname?: string;
}

/**
 * J2 报告任务工作空间布局。
 *
 * 职责：登录态守卫、租户信息、员工端导航与退出登录；业务页面由 Outlet 渲染。
 * 与 J1 工作空间共享同一个 authStore，但保持独立布局，避免 J2 改动影响 J1 的
 * 会话列表、文件上传与流式区域。
 *
 * M06 起该布局同时承载编排 Studio；导航按 `workflow.read` 过滤，选中态与面包屑
 * 都从 `config/routes.ts` 的同一份路由/导航元数据推导，避免菜单、标题与深链漂移。
 *
 * @param props API、导航与路径注入
 * @returns 工作空间外壳
 */
export default function WorkspaceLayout({
  api = defaultApi,
  managementApi: injectedManagementApi,
  navigate: injectedNavigate,
  pathname: injectedPathname,
}: WorkspaceLayoutProps) {
  const auth = useAuth();
  const managementApi = injectedManagementApi ?? (supportsWorkspaceManagement(api) ? api : null);
  const management = useWorkspaceManagement(managementApi, auth.session);
  const location = useLocation();
  const pathname = injectedPathname ?? location.pathname;
  const umiNavigate = useNavigate();
  const navigate = injectedNavigate ?? umiNavigate;
  const [loggingOut, setLoggingOut] = useState(false);
  const [logoutError, setLogoutError] = useState<string | null>(null);

  // 首次进入时恢复会话；401 由 authStore 转为匿名，再由下面的 effect 跳转登录。
  useEffect(() => {
    if (auth.status === 'unknown') {
      void authStore.restore(api);
    }
  }, [api, auth.status]);

  useEffect(() => {
    if (auth.status === 'anonymous' || auth.status === 'expired') {
      navigate('/login', { replace: true });
    }
  }, [auth.status, navigate]);

  /**
   * 退出登录；只有服务端确认后才清除本地会话。
   */
  const handleLogout = async (): Promise<void> => {
    setLoggingOut(true);
    setLogoutError(null);
    try {
      await api.logout();
      authStore.signOut('logout');
      navigate('/login', { replace: true });
    } catch (error) {
      setLogoutError(describeApiError(error, '退出登录失败，请重试。'));
    } finally {
      setLoggingOut(false);
    }
  };

  if (auth.status === 'unknown' || auth.status === 'checking') {
    return <FullPageSpin text="正在检查登录状态…" />;
  }

  if (auth.status === 'anonymous' || auth.status === 'expired') {
    return <FullPageSpin text="正在跳转登录…" />;
  }

  if (auth.status === 'error') {
    return (
      <Flex align="center" justify="center" style={{ minHeight: '100vh', padding: 16 }}>
        <Result
          status="error"
          title="无法确认登录状态"
          subTitle={auth.error ?? '无法连接服务器，请检查网络后重试。'}
          extra={[
            <Button key="retry" type="primary" onClick={() => void authStore.restore(api)}>
              重试
            </Button>,
            <Button
              key="login"
              onClick={() => {
                authStore.signOut(null);
                navigate('/login', { replace: true });
              }}
            >
              前往登录
            </Button>,
          ]}
        />
      </Flex>
    );
  }

  if (!auth.session) {
    return <FullPageSpin text="正在加载会话…" />;
  }

  const navigation = filterWorkspaceNavigation(auth.session.permissions);
  const breadcrumbs = buildWorkspaceBreadcrumb(pathname);

  return (
    <div className="workspace-shell">
      <header className="workspace-shell-header">
        <div className="workspace-shell-inner">
          <div className="workspace-shell-brand">
            <Typography.Title className="workspace-shell-title" level={1}>
              Riccon 工作空间
            </Typography.Title>
            <div className="workspace-shell-meta">
              <Typography.Text type="secondary">{auth.session.tenant.name}</Typography.Text>
              <CurrentDepartmentBadge
                department={management.currentDepartment}
                error={management.error}
                isTenantAdmin={management.isTenantAdmin}
                loading={management.loading}
              />
            </div>
          </div>

          <nav aria-label="主导航" className="workspace-shell-nav">
            {navigation.map((item) => (
              <Link
                aria-current={isNavigationActive(pathname, item.path) ? 'page' : undefined}
                key={item.path}
                to={item.path}
              >
                {item.label}
              </Link>
            ))}
          </nav>

          <div className="workspace-shell-actions">
            <Typography.Text>{auth.session.user.displayName}</Typography.Text>
            <Button
              icon={<LogoutOutlined />}
              loading={loggingOut}
              onClick={() => {
                void handleLogout();
              }}
            >
              退出登录
            </Button>
          </div>
        </div>
        {logoutError && (
          <Alert
            showIcon
            closable
            description={logoutError}
            onClose={() => setLogoutError(null)}
            style={{ margin: '0 24px 12px' }}
            title="退出登录失败"
            type="error"
          />
        )}
      </header>

      <main className="workspace-shell-main">
        {breadcrumbs.length > 1 ? (
          <nav aria-label="面包屑" className="workspace-shell-breadcrumb">
            <ol>
              {breadcrumbs.map((crumb, index) => {
                const current = index === breadcrumbs.length - 1;
                return (
                  <li key={`${crumb.label}-${index}`}>
                    {crumb.path && !current ? (
                      <Link to={crumb.path}>{crumb.label}</Link>
                    ) : (
                      <span aria-current={current ? 'page' : undefined}>{crumb.label}</span>
                    )}
                  </li>
                );
              })}
            </ol>
          </nav>
        ) : null}
        <Outlet />
      </main>
    </div>
  );
}

/**
 * 全屏加载提示。
 *
 * @param props 文案
 * @returns 居中加载态
 */
function FullPageSpin({ text }: { text: string }) {
  return (
    <Flex align="center" gap={16} justify="center" style={{ minHeight: '100vh', padding: 24 }}>
      <Spin size="large" />
      <Typography.Text>{text}</Typography.Text>
    </Flex>
  );
}
