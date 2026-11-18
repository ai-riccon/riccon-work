// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import { authStore } from '@/services/auth/auth-store';
import { DEMO_SESSION, createFakeApi, pageOf } from '@/test/fake-api';
import type { WorkspaceManagementApi } from '@/services/api/client';
import type { Department } from '@/services/api/types';
import { withProviders } from '@/test/render';
import WorkspaceLayout from './index';

/** 构造部门夹具。 */
function makeDepartment(overrides: Partial<Department> = {}): Department {
  return {
    id: 'dept-sales',
    name: '销售部',
    status: 'ACTIVE',
    managerUserId: 'user-1',
    managerDisplayName: '演示员工',
    memberCount: 2,
    createdAt: '2026-11-19T08:00:00.000Z',
    updatedAt: '2026-11-19T08:00:00.000Z',
    ...overrides,
  };
}

/**
 * 构造只提供部门读取的 API 替身。
 *
 * @param departments 当前账号可见部门
 * @returns WorkspaceManagementApi
 */
function makeManagementApi(departments: Department[]): WorkspaceManagementApi {
  const notUsed = vi.fn(async () => {
    throw new Error('当前用例不应调用该端点');
  });
  return {
    listDepartments: vi.fn(async () => pageOf(departments)),
    listTeamWorkspaces: vi.fn(async () => pageOf([])),
    listDepartmentMembers: notUsed,
    listDepartmentWorkspaces: notUsed,
    createDepartmentWorkspace: notUsed,
    createWorkspace: notUsed,
    updateWorkspace: notUsed,
    listWorkspaceMembers: notUsed,
    addWorkspaceMember: notUsed,
    removeWorkspaceMember: notUsed,
  };
}

/**
 * J2 工作空间布局测试。
 *
 * 覆盖登录态守卫、导航、租户信息与退出登录；业务页面由独立测试覆盖。
 */
describe('报告任务工作空间布局', () => {
  beforeEach(() => {
    authStore.reset();
  });

  it('已登录时展示租户、员工导航与子路由出口', () => {
    authStore.setAuthenticated(DEMO_SESSION);
    const api = createFakeApi();

    render(withProviders(<WorkspaceLayout api={api} navigate={vi.fn()} />));

    expect(screen.getByRole('heading', { name: 'Riccon 工作空间' })).toBeVisible();
    expect(screen.getByText('示例企业')).toBeVisible();
    expect(screen.getByRole('link', { name: '问答工作空间' })).toHaveAttribute('href', '/chat');
    expect(screen.getByRole('link', { name: '市场调研报告' })).toHaveAttribute('href', '/research');
    expect(screen.getByText('演示员工')).toBeVisible();
    expect(screen.getByTestId('umi-outlet')).toBeInTheDocument();
  });

  it('显示当前账号主部门；停用部门仍显示名称与停用标签', async () => {
    authStore.setAuthenticated(DEMO_SESSION);
    const api = createFakeApi();

    const { unmount } = render(
      withProviders(
        <WorkspaceLayout
          api={api}
          managementApi={makeManagementApi([makeDepartment()])}
          navigate={vi.fn()}
        />,
      ),
    );
    expect(await screen.findByText('所属部门：')).toBeVisible();
    expect(screen.getByText('销售部')).toBeVisible();
    unmount();

    authStore.setAuthenticated(DEMO_SESSION);
    render(
      withProviders(
        <WorkspaceLayout
          api={createFakeApi()}
          managementApi={makeManagementApi([makeDepartment({ status: 'DISABLED' })])}
          navigate={vi.fn()}
        />,
      ),
    );
    expect(await screen.findByText('销售部')).toBeVisible();
    expect(screen.getByText('已停用')).toBeVisible();
  });

  it('匿名访问时恢复会话失败并跳转登录', async () => {
    const navigate = vi.fn();
    const api = createFakeApi({
      getSession: vi.fn(async () => {
        throw new ApiError('未登录', { status: 401, code: 'RICCON-AUTH-401' });
      }),
    });

    render(withProviders(<WorkspaceLayout api={api} navigate={navigate} />));

    expect(screen.getByText('正在检查登录状态…')).toBeVisible();
    await waitFor(() => {
      expect(navigate).toHaveBeenCalledWith('/login', { replace: true });
    });
    expect(authStore.getSnapshot().status).toBe('anonymous');
  });

  it('会话检查失败时提供重试与前往登录', async () => {
    const navigate = vi.fn();
    const api = createFakeApi({
      getSession: vi.fn(async () => {
        throw new ApiError('服务不可用', { status: 500, code: 'RICCON-INTERNAL-500' });
      }),
    });

    render(withProviders(<WorkspaceLayout api={api} navigate={navigate} />));

    expect(await screen.findByText('无法确认登录状态')).toBeVisible();
    expect(screen.getByText('服务不可用')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '前往登录' }));
    expect(navigate).toHaveBeenCalledWith('/login', { replace: true });
  });

  it('退出登录成功后清除本地会话并跳转', async () => {
    authStore.setAuthenticated(DEMO_SESSION);
    const navigate = vi.fn();
    const api = createFakeApi();

    render(withProviders(<WorkspaceLayout api={api} navigate={navigate} />));
    fireEvent.click(screen.getByRole('button', { name: /退\s*出\s*登\s*录/ }));

    await waitFor(() => {
      expect(api.logout).toHaveBeenCalledTimes(1);
    });
    expect(authStore.getSnapshot().status).toBe('anonymous');
    expect(navigate).toHaveBeenCalledWith('/login', { replace: true });
  });

  it('退出登录失败时保留会话并显示原因', async () => {
    authStore.setAuthenticated(DEMO_SESSION);
    const navigate = vi.fn();
    const api = createFakeApi({
      logout: vi.fn(async () => {
        throw new ApiError('服务不可用', { status: 500, code: 'RICCON-INTERNAL-500' });
      }),
    });

    render(withProviders(<WorkspaceLayout api={api} navigate={navigate} />));
    fireEvent.click(screen.getByRole('button', { name: /退\s*出\s*登\s*录/ }));

    expect(await screen.findByText('退出登录失败')).toBeVisible();
    expect(screen.getByText('服务暂时不可用，请稍后重试。')).toBeVisible();
    expect(authStore.getSnapshot().status).toBe('authenticated');
    expect(navigate).not.toHaveBeenCalledWith('/login', expect.anything());
  });
});
