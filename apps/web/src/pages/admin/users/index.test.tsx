// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import { authStore } from '@/services/auth/auth-store';
import { withProviders } from '@/test/render';
import {
  MEMBER_SESSION,
  PLATFORM_ADMIN_SESSION,
  TENANT_ADMIN_SESSION,
  createUserAdminApi,
  makeUser,
  pageOf,
} from '../../../../tests/m10-oidc/fake';
import AdminUsersPage from './index';

/**
 * 用户管理页组件测试。
 *
 * 覆盖：权限守卫、authSource 展示、绑定/解绑状态机、409/400 文案、
 * 平台租户拒绝与平台管理员路径。完整 E2E/a11y 由 V1 独立验证。
 */
describe('用户管理页', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    authStore.reset();
  });

  /** 渲染页面。 */
  function renderPage(options: { api?: ReturnType<typeof createUserAdminApi>; tenantId?: string }) {
    return render(
      withProviders(
        <AdminUsersPage api={options.api ?? createUserAdminApi()} tenantId={options.tenantId} />,
      ),
    );
  }

  it('普通成员没有管理入口，看到 403 且不请求账号列表', async () => {
    authStore.setAuthenticated(MEMBER_SESSION);
    const api = createUserAdminApi();
    renderPage({ api });

    expect(await screen.findByText('没有访问权限')).toBeVisible();
    expect(screen.getByText('只有租户管理员可以管理本租户账号。')).toBeVisible();
    expect(api.listUsers).not.toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: /绑定企业账号/ })).not.toBeInTheDocument();
  });

  it('租户管理员看到 authSource 与对应操作', async () => {
    authStore.setAuthenticated(TENANT_ADMIN_SESSION);
    const api = createUserAdminApi({
      listUsers: vi.fn(async () =>
        pageOf([
          makeUser({ id: 'user-1', displayName: '王敏', authSource: 'LOCAL' }),
          makeUser({
            id: 'user-2',
            displayName: '李强',
            username: 'liqiang',
            authSource: 'OIDC',
          }),
        ]),
      ),
    });
    renderPage({ api });

    expect(await screen.findByText('王敏')).toBeVisible();
    expect(screen.getByText('内置账号')).toBeVisible();
    expect(screen.getByText('企业账号')).toBeVisible();
    expect(screen.getByRole('button', { name: '绑定企业账号：王敏' })).toBeVisible();
    expect(screen.getByRole('button', { name: '解除绑定：李强' })).toBeVisible();
    expect(api.listUsers).toHaveBeenCalledTimes(1);
  });

  it('历史响应缺少 authSource 时显示未标注，不猜测来源', async () => {
    authStore.setAuthenticated(TENANT_ADMIN_SESSION);
    const api = createUserAdminApi({
      listUsers: vi.fn(async () => pageOf([makeUser({ authSource: undefined })])),
    });
    renderPage({ api });

    expect(await screen.findByText('未标注')).toBeVisible();
  });

  it('绑定填 issuer/subject 后调用租户端点并更新列表', async () => {
    authStore.setAuthenticated(TENANT_ADMIN_SESSION);
    const api = createUserAdminApi();
    renderPage({ api });

    fireEvent.click(await screen.findByRole('button', { name: '绑定企业账号：王敏' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('issuer'), {
      target: { value: 'https://idp.example.com/realms/acme' },
    });
    fireEvent.change(within(dialog).getByLabelText('subject'), {
      target: { value: 'sub-123' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: '确认绑定' }));

    await waitFor(() => {
      expect(api.bindOidc).toHaveBeenCalledWith('user-1', {
        issuer: 'https://idp.example.com/realms/acme',
        subject: 'sub-123',
      });
    });
    expect(await screen.findByText(/已绑定 王敏 的企业账号/)).toBeVisible();
    await waitFor(() => {
      expect(screen.getByText('企业账号')).toBeVisible();
    });
  });

  it('绑定 409 在弹窗内显示冲突文案，不泄露英文内部说明', async () => {
    authStore.setAuthenticated(TENANT_ADMIN_SESSION);
    const api = createUserAdminApi({
      bindOidc: vi.fn(async () => {
        throw new ApiError('OIDC identity is already bound to another account', {
          status: 409,
          code: 'RICCON-OIDC-409',
        });
      }),
    });
    renderPage({ api });

    fireEvent.click(await screen.findByRole('button', { name: '绑定企业账号：王敏' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('issuer'), {
      target: { value: 'https://idp.example.com' },
    });
    fireEvent.change(within(dialog).getByLabelText('subject'), {
      target: { value: 'sub-123' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: '确认绑定' }));

    expect(await screen.findByText(/该企业身份已绑定其他账号/)).toBeInTheDocument();
    expect(screen.queryByText(/OIDC identity is already/)).not.toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('绑定表单校验 issuer 绝对 URL 与 subject 首尾空白', async () => {
    authStore.setAuthenticated(TENANT_ADMIN_SESSION);
    renderPage({ api: createUserAdminApi() });

    fireEvent.click(await screen.findByRole('button', { name: '绑定企业账号：王敏' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: '确认绑定' }));

    expect(await screen.findByText('请输入 issuer')).toBeInTheDocument();
    expect(await screen.findByText('请输入 subject')).toBeInTheDocument();

    fireEvent.change(within(dialog).getByLabelText('issuer'), {
      target: { value: 'ftp://idp.example.com' },
    });
    fireEvent.change(within(dialog).getByLabelText('subject'), {
      target: { value: ' sub-123 ' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: '确认绑定' }));

    expect(await screen.findByText('issuer 必须是绝对 http(s) URL')).toBeInTheDocument();
    expect(await screen.findByText(/subject 不能包含首尾空白/)).toBeInTheDocument();
  });

  it('解绑必须设置 12 位以上新密码并二次确认', async () => {
    authStore.setAuthenticated(TENANT_ADMIN_SESSION);
    const api = createUserAdminApi({
      listUsers: vi.fn(async () => pageOf([makeUser({ id: 'user-2', authSource: 'OIDC' })])),
    });
    renderPage({ api });

    fireEvent.click(await screen.findByRole('button', { name: '解除绑定：王敏' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('新的本地密码'), {
      target: { value: 'short' },
    });
    fireEvent.change(within(dialog).getByLabelText('确认新密码'), {
      target: { value: 'short' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: '确认解绑并恢复本地账号' }));

    expect(await screen.findByText('新密码至少 12 个字符')).toBeInTheDocument();

    fireEvent.change(within(dialog).getByLabelText('新的本地密码'), {
      target: { value: 'new-password-123' },
    });
    fireEvent.change(within(dialog).getByLabelText('确认新密码'), {
      target: { value: 'different-password' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: '确认解绑并恢复本地账号' }));

    expect(await screen.findByText('两次输入的密码不一致')).toBeInTheDocument();
    expect(api.unbindOidc).not.toHaveBeenCalled();
  });

  it('解绑成功后恢复内置账号并提示已恢复本地密码', async () => {
    authStore.setAuthenticated(TENANT_ADMIN_SESSION);
    const api = createUserAdminApi({
      listUsers: vi.fn(async () => pageOf([makeUser({ id: 'user-2', authSource: 'OIDC' })])),
    });
    renderPage({ api });

    fireEvent.click(await screen.findByRole('button', { name: '解除绑定：王敏' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('新的本地密码'), {
      target: { value: 'new-password-123' },
    });
    fireEvent.change(within(dialog).getByLabelText('确认新密码'), {
      target: { value: 'new-password-123' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: '确认解绑并恢复本地账号' }));

    await waitFor(() => {
      expect(api.unbindOidc).toHaveBeenCalledWith('user-2', {
        newPassword: 'new-password-123',
      });
    });
    expect(await screen.findByText(/已解除 王敏 的企业账号绑定/)).toBeVisible();
    await waitFor(() => {
      expect(screen.getByText('内置账号')).toBeVisible();
    });
  });

  it('解绑 409 显示状态冲突且保留弹窗', async () => {
    authStore.setAuthenticated(TENANT_ADMIN_SESSION);
    const api = createUserAdminApi({
      listUsers: vi.fn(async () => pageOf([makeUser({ id: 'user-2', authSource: 'OIDC' })])),
      unbindOidc: vi.fn(async () => {
        throw new ApiError('account is not bound', { status: 409, code: 'RICCON-OIDC-409' });
      }),
    });
    renderPage({ api });

    fireEvent.click(await screen.findByRole('button', { name: '解除绑定：王敏' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('新的本地密码'), {
      target: { value: 'new-password-123' },
    });
    fireEvent.change(within(dialog).getByLabelText('确认新密码'), {
      target: { value: 'new-password-123' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: '确认解绑并恢复本地账号' }));

    expect(await screen.findByText(/账号当前未绑定企业身份/)).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('平台管理员使用目标租户端点', async () => {
    authStore.setAuthenticated(PLATFORM_ADMIN_SESSION);
    const api = createUserAdminApi();
    renderPage({ api, tenantId: 'acme' });

    expect(await screen.findByText('王敏')).toBeVisible();
    expect(api.listTenantUsers).toHaveBeenCalledWith('acme');
    expect(api.listUsers).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: '绑定企业账号：王敏' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('issuer'), {
      target: { value: 'https://idp.example.com' },
    });
    fireEvent.change(within(dialog).getByLabelText('subject'), {
      target: { value: 'sub-123' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: '确认绑定' }));

    await waitFor(() => {
      expect(api.bindTenantUserOidc).toHaveBeenCalledWith('acme', 'user-1', {
        issuer: 'https://idp.example.com',
        subject: 'sub-123',
      });
    });
    expect(api.bindOidc).not.toHaveBeenCalled();
  });

  it('平台租户拒绝绑定且不展示操作按钮', async () => {
    authStore.setAuthenticated(PLATFORM_ADMIN_SESSION);
    const api = createUserAdminApi();
    renderPage({ api, tenantId: 'platform' });

    expect(await screen.findByText('平台租户不支持企业账号绑定')).toBeVisible();
    expect(await screen.findByText('王敏')).toBeVisible();
    expect(screen.queryByRole('button', { name: /绑定企业账号/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /解除绑定/ })).not.toBeInTheDocument();
  });

  it('空列表显示空态与刷新入口', async () => {
    authStore.setAuthenticated(TENANT_ADMIN_SESSION);
    const api = createUserAdminApi({ listUsers: vi.fn(async () => pageOf([])) });
    renderPage({ api });

    expect(await screen.findByText(/该范围还没有账号/)).toBeVisible();
    expect(screen.getByRole('button', { name: /刷\s*新/ })).toBeVisible();
  });

  it('列表 403 显示无权限结果', async () => {
    authStore.setAuthenticated(TENANT_ADMIN_SESSION);
    const api = createUserAdminApi({
      listUsers: vi.fn(async () => {
        throw new ApiError('forbidden', { status: 403, code: 'RICCON-AUTH-403' });
      }),
    });
    renderPage({ api });

    expect(await screen.findByText('只有具备账号管理权限的管理员可以查看账号列表。')).toBeVisible();
    expect(screen.getByRole('button', { name: /重\s*试/ })).toBeVisible();
  });
});
