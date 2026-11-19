import { PageContainer } from '@ant-design/pro-components';
import { Link, useParams } from '@umijs/max';
import { Alert, Button, Empty, Result, Skeleton, Space, Tag, Typography } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { api as defaultApi } from '@/services/api';
import { ApiError } from '@/services/api/errors';
import type { OidcBindingRequest, OidcUnbindRequest, UserSummary } from '@/services/api/types';
import { authStore, useAuth } from '@/services/auth/auth-store';
import type { UserAdminApi } from '@/services/users/api';
import {
  PLATFORM_TENANT_ID,
  USER_PERMISSIONS,
  authSourceColor,
  authSourceLabel,
  formatUserTime,
  hasPermission,
  userStatusLabel,
} from '@/services/users/access';
import {
  describeBindError,
  describeUnbindError,
  describeUserListError,
} from '@/services/users/errors';
import { userAdminApi as defaultUserAdminApi } from '@/services/users';
import { OidcBindingModal } from './binding-modal';
import { OidcUnbindModal } from './unbind-modal';
import './users.css';

/** 用户管理页参数。 */
export interface AdminUsersPageProps {
  /** 可注入的 API，便于组件测试。 */
  api?: UserAdminApi;
  /**
   * 平台管理员的目标租户 ID；正常运行时从路由参数 `:tenantId` 读取。
   * 未提供时按租户管理员模式管理当前租户。
   */
  tenantId?: string;
}

/** 页面主阶段。 */
type UsersPhase = 'loading' | 'ready' | 'empty' | 'forbidden' | 'error';

/**
 * 用户管理与 OIDC 绑定页面。
 *
 * 两种模式：
 * - 租户管理员（无 `:tenantId`）：`tenant.user.manage`，只管理当前租户；
 * - 平台管理员（路由带 `:tenantId`）：`platform.tenant.manage`，只读取目标租户账号元数据。
 *
 * 普通成员没有页面入口且会看到 403；平台保留租户禁止 OIDC 绑定（平台引导管理员必须保持内置账号）。
 *
 * @param props API 与目标租户注入
 * @returns 用户管理页面
 */
export default function AdminUsersPage({
  api = defaultUserAdminApi,
  tenantId: injectedTenantId,
}: AdminUsersPageProps) {
  const auth = useAuth();
  const params = useParams();
  const targetTenantId = injectedTenantId ?? params.tenantId;
  const platformMode = Boolean(targetTenantId);
  const session = auth.session;
  const requiredPermission = platformMode
    ? USER_PERMISSIONS.platformManage
    : USER_PERMISSIONS.tenantManage;
  const allowed = session ? hasPermission(session.permissions, requiredPermission) : false;
  const [phase, setPhase] = useState<UsersPhase>('loading');
  const [users, setUsers] = useState<UserSummary[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [bindingUser, setBindingUser] = useState<UserSummary | null>(null);
  const [unbindingUser, setUnbindingUser] = useState<UserSummary | null>(null);
  const [actionSubmitting, setActionSubmitting] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const platformTenant =
    targetTenantId === PLATFORM_TENANT_ID ||
    (!platformMode && session?.tenant.id === PLATFORM_TENANT_ID);

  // 管理端布局不恢复会话；直接刷新 /admin/users 时由页面补一次会话恢复。
  useEffect(() => {
    if (auth.status === 'unknown') {
      void authStore.restore(defaultApi);
    }
  }, [auth.status]);

  /**
   * 读取目标范围账号列表。
   *
   * 只有具备对应权限时才请求；服务端仍会独立鉴权，前端隐藏不代替授权。
   */
  const load = useCallback(async (): Promise<void> => {
    if (!allowed || !session) {
      return;
    }
    setPhase('loading');
    setError(null);
    try {
      const page =
        platformMode && targetTenantId
          ? await api.listTenantUsers(targetTenantId)
          : await api.listUsers();
      setUsers(page.items);
      setPhase(page.items.length === 0 ? 'empty' : 'ready');
    } catch (loadError) {
      setError(describeUserListError(loadError));
      setPhase(
        loadError instanceof ApiError && (loadError.status === 403 || loadError.status === 401)
          ? 'forbidden'
          : 'error',
      );
    }
  }, [allowed, api, platformMode, session, targetTenantId]);

  useEffect(() => {
    void load();
  }, [load]);

  /**
   * 绑定企业账号。
   *
   * @param input issuer 与 subject
   */
  const handleBind = async (input: OidcBindingRequest): Promise<void> => {
    if (!bindingUser) {
      return;
    }
    setActionSubmitting(true);
    setActionError(null);
    try {
      const updated =
        platformMode && targetTenantId
          ? await api.bindTenantUserOidc(targetTenantId, bindingUser.id, input)
          : await api.bindOidc(bindingUser.id, input);
      setUsers((current) => current.map((user) => (user.id === updated.id ? updated : user)));
      setBindingUser(null);
      setNotice(`已绑定 ${updated.displayName} 的企业账号；该账号的本地密码已删除。`);
    } catch (bindError) {
      setActionError(describeBindError(bindError));
    } finally {
      setActionSubmitting(false);
    }
  };

  /**
   * 解除绑定并恢复本地密码。
   *
   * @param input 新本地密码
   */
  const handleUnbind = async (input: OidcUnbindRequest): Promise<void> => {
    if (!unbindingUser) {
      return;
    }
    setActionSubmitting(true);
    setActionError(null);
    try {
      const updated =
        platformMode && targetTenantId
          ? await api.unbindTenantUserOidc(targetTenantId, unbindingUser.id, input)
          : await api.unbindOidc(unbindingUser.id, input);
      setUsers((current) => current.map((user) => (user.id === updated.id ? updated : user)));
      setUnbindingUser(null);
      setNotice(`已解除 ${updated.displayName} 的企业账号绑定，并恢复本地密码。`);
    } catch (unbindError) {
      setActionError(describeUnbindError(unbindError));
    } finally {
      setActionSubmitting(false);
    }
  };

  const header = (
    <PageContainer
      subTitle={
        platformMode
          ? `平台管理员维护目标租户 ${targetTenantId ?? ''} 的账号元数据与认证来源`
          : '租户管理员维护账号认证来源与企业身份绑定'
      }
      title={platformMode ? '租户用户管理' : '用户管理'}
    >
      {platformMode && (
        <Typography.Paragraph>
          <Link to="/admin/tenants">返回租户列表</Link>
        </Typography.Paragraph>
      )}

      {platformTenant && (
        <Alert
          description="平台引导管理员必须保持内置账号，防止平台租户被外部 IdP 锁死。请到具体业务租户中管理企业账号绑定。"
          showIcon
          style={{ marginBottom: 16 }}
          title="平台租户不支持企业账号绑定"
          type="warning"
        />
      )}

      <Alert
        description="绑定后该账号的本地密码会被删除，只能通过企业身份提供方登录；解绑时必须设置新的本地密码。系统不会按邮箱自动绑定或合并账号。"
        showIcon
        style={{ marginBottom: 16 }}
        title="认证来源边界"
        type="info"
      />

      {notice && (
        <Alert
          aria-live="polite"
          closable
          description={notice}
          onClose={() => setNotice(null)}
          showIcon
          style={{ marginBottom: 16 }}
          title="操作已完成"
          type="success"
        />
      )}

      {auth.status === 'unknown' || auth.status === 'checking' ? (
        <Skeleton active paragraph={{ rows: 6 }} />
      ) : auth.status === 'error' ? (
        <Result
          extra={
            <Button onClick={() => void authStore.restore(defaultApi)} type="primary">
              重试
            </Button>
          }
          status="error"
          subTitle={auth.error ?? '请检查网络后重试。'}
          title="无法确认登录状态"
        />
      ) : !session ? (
        <Result status="403" subTitle="请先登录后再访问用户管理。" title="没有访问权限" />
      ) : !allowed ? (
        <Result
          status="403"
          subTitle={
            platformMode
              ? '只有平台管理员可以管理目标租户账号。'
              : '只有租户管理员可以管理本租户账号。'
          }
          title="没有访问权限"
        />
      ) : phase === 'loading' ? (
        <Skeleton active paragraph={{ rows: 6 }} />
      ) : phase === 'forbidden' ? (
        <Result
          extra={
            <Button onClick={() => void load()} type="primary">
              重试
            </Button>
          }
          status="403"
          subTitle={error ?? '当前账号无权管理账号。'}
          title="没有访问权限"
        />
      ) : phase === 'error' ? (
        <Result
          extra={
            <Button onClick={() => void load()} type="primary">
              重试
            </Button>
          }
          status="error"
          subTitle={error ?? '请检查网络后重试。'}
          title="账号列表加载失败"
        />
      ) : phase === 'empty' ? (
        <Empty description="该范围还没有账号；账号由管理员创建或由平台开通流程创建。">
          <Button onClick={() => void load()}>刷新</Button>
        </Empty>
      ) : (
        <ul aria-label="账号列表" className="user-admin-list">
          {users.map((user) => (
            <li className="user-admin-item" key={user.id}>
              <div className="user-admin-main">
                <Space size={[8, 4]} wrap>
                  <Typography.Text strong>{user.displayName}</Typography.Text>
                  <Typography.Text type="secondary">{user.username}</Typography.Text>
                  <Tag color={user.status === 'ACTIVE' ? 'green' : 'orange'}>
                    {userStatusLabel(user)}
                  </Tag>
                  <Tag color={authSourceColor(user.authSource)}>
                    {authSourceLabel(user.authSource)}
                  </Tag>
                  {user.roles.map((role) => (
                    <Tag key={role}>{role}</Tag>
                  ))}
                </Space>
                <Typography.Text type="secondary">
                  最近登录 {formatUserTime(user.lastLoginAt)}
                </Typography.Text>
              </div>
              <div className="user-admin-actions">
                {platformTenant ? (
                  <Typography.Text type="secondary">
                    平台租户账号不支持企业身份绑定。
                  </Typography.Text>
                ) : user.authSource === 'OIDC' ? (
                  <Button
                    aria-label={`解除绑定：${user.displayName}`}
                    onClick={() => {
                      setActionError(null);
                      setUnbindingUser(user);
                    }}
                  >
                    解除绑定
                  </Button>
                ) : (
                  <Button
                    aria-label={`绑定企业账号：${user.displayName}`}
                    onClick={() => {
                      setActionError(null);
                      setBindingUser(user);
                    }}
                    type="primary"
                  >
                    绑定企业账号
                  </Button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}

      <OidcBindingModal
        error={actionError}
        onCancel={() => {
          setBindingUser(null);
          setActionError(null);
        }}
        onSubmit={(input) => {
          void handleBind(input);
        }}
        open={bindingUser !== null}
        submitting={actionSubmitting}
        user={bindingUser}
      />
      <OidcUnbindModal
        error={actionError}
        onCancel={() => {
          setUnbindingUser(null);
          setActionError(null);
        }}
        onSubmit={(input) => {
          void handleUnbind(input);
        }}
        open={unbindingUser !== null}
        submitting={actionSubmitting}
        user={unbindingUser}
      />
    </PageContainer>
  );

  return header;
}
