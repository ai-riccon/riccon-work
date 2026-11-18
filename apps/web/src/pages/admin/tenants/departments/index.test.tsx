// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { PlatformDepartmentApi } from '@/services/api/client';
import { ApiError } from '@/services/api/errors';
import type {
  Department,
  DepartmentMember,
  TenantSummary,
  UserSummary,
} from '@/services/api/types';
import { pageOf } from '@/test/fake-api';
import { withProviders } from '@/test/render';
import TenantDepartmentsPage from './index';

/** 构造部门。 */
function makeDepartment(overrides: Partial<Department> = {}): Department {
  return {
    id: 'dept-sales',
    name: '销售部',
    status: 'ACTIVE',
    managerUserId: 'user-1',
    managerDisplayName: '李强',
    memberCount: 2,
    createdAt: '2026-11-18T08:00:00.000Z',
    updatedAt: '2026-11-18T08:00:00.000Z',
    ...overrides,
  };
}

/** 构造部门成员。 */
function makeMember(overrides: Partial<DepartmentMember> = {}): DepartmentMember {
  return {
    id: 'user-1',
    username: 'liqiang',
    displayName: '李强',
    status: 'ACTIVE',
    ...overrides,
  };
}

/** 构造租户账号。 */
function makeUser(overrides: Partial<UserSummary> = {}): UserSummary {
  return {
    id: 'user-1',
    username: 'liqiang',
    displayName: '李强',
    email: null,
    status: 'ACTIVE',
    roles: ['MEMBER'],
    lastLoginAt: null,
    createdAt: '2026-11-18T08:00:00.000Z',
    ...overrides,
  };
}

/** 构造 409 部门冲突。 */
function conflict(detail: string): ApiError {
  return new ApiError(detail, {
    status: 409,
    code: 'RICCON-DEPT-409',
    problem: {
      type: 'about:blank',
      title: '冲突',
      status: 409,
      detail,
      code: 'RICCON-DEPT-409',
      traceId: 'trace-1',
    },
  });
}

/**
 * 构造平台部门 API 替身。
 *
 * @param overrides 局部覆盖
 * @returns PlatformDepartmentApi
 */
function makeApi(overrides: Partial<PlatformDepartmentApi> = {}): PlatformDepartmentApi {
  const tenant: TenantSummary = {
    id: 'tenant-a',
    name: '示例企业',
    status: 'ACTIVE',
    retentionDays: 180,
    createdAt: '2026-11-18T08:00:00.000Z',
  };
  const notUsed = async () => {
    throw new Error('当前用例不应调用该端点');
  };
  return {
    listTenants: vi.fn(async () => pageOf([tenant])),
    listTenantDepartments: vi.fn(async () => pageOf([makeDepartment()])),
    createTenantDepartment: vi.fn(notUsed),
    renameTenantDepartment: vi.fn(notUsed),
    disableTenantDepartment: vi.fn(notUsed),
    enableTenantDepartment: vi.fn(notUsed),
    listTenantDepartmentMembers: vi.fn(async () => pageOf([makeMember()])),
    addTenantDepartmentMember: vi.fn(notUsed),
    removeTenantDepartmentMember: vi.fn(async () => undefined),
    transferTenantDepartmentManager: vi.fn(notUsed),
    listTenantUsers: vi.fn(async () => pageOf([makeUser()])),
    ...overrides,
  };
}

/** 渲染部门管理页。 */
function renderPage(api: PlatformDepartmentApi) {
  return render(withProviders(<TenantDepartmentsPage api={api} tenantId="tenant-a" />));
}

describe('平台管理员部门管理页', () => {
  it('展示部门列表、成员数、负责人与状态', async () => {
    renderPage(
      makeApi({
        listTenantDepartments: vi.fn(async () =>
          pageOf([
            makeDepartment({ id: 'dept-market', name: '市场部', memberCount: 5 }),
            makeDepartment(),
          ]),
        ),
      }),
    );

    expect(await screen.findByText('销售部')).toBeVisible();
    expect(screen.getByText('市场部')).toBeVisible();
    expect(screen.getByText('成员 2 人')).toBeVisible();
    expect(screen.getAllByText('负责人：李强')).toHaveLength(2);
    expect(screen.getByRole('button', { name: '创建部门' })).toBeEnabled();
    expect(screen.getAllByText('启用')).not.toHaveLength(0);
    expect(screen.getByText(/共 2 个部门，启用 2 个/)).toBeVisible();
  });

  it('空部门展示空态与创建入口', async () => {
    renderPage(makeApi({ listTenantDepartments: vi.fn(async () => pageOf([])) }));

    expect(await screen.findByText(/还没有部门/)).toBeVisible();
    expect(screen.getAllByRole('button', { name: '创建部门' })).toHaveLength(2);
  });

  it('无权限展示 403 与可读说明', async () => {
    const api = makeApi({
      listTenantDepartments: vi.fn(async () => {
        throw new ApiError('forbidden', { status: 403, code: 'RICCON-AUTH-403' });
      }),
    });
    renderPage(api);

    expect(await screen.findByText('没有访问权限')).toBeVisible();
    expect(screen.getByText(/只有平台管理员可以管理部门/)).toBeVisible();
  });

  it('目标租户不存在展示 404', async () => {
    const api = makeApi({
      listTenantDepartments: vi.fn(async () => {
        throw new ApiError('not found', { status: 404, code: 'RICCON-TENANT-404' });
      }),
    });
    renderPage(api);

    expect(await screen.findByText('目标不存在')).toBeVisible();
    expect(screen.getByText(/目标租户或部门不存在/)).toBeVisible();
  });

  it('目标租户暂停展示 409 说明', async () => {
    const api = makeApi({
      listTenantDepartments: vi.fn(async () => {
        throw new ApiError('suspended', { status: 409, code: 'RICCON-CONFLICT-409' });
      }),
    });
    renderPage(api);

    expect(await screen.findByText('目标租户已暂停')).toBeVisible();
    expect(screen.getByText(/请先恢复租户状态/)).toBeVisible();
  });

  it('网络失败展示错误并允许重试', async () => {
    let attempts = 0;
    const api = makeApi({
      listTenantDepartments: vi.fn(async () => {
        attempts += 1;
        if (attempts === 1) {
          throw new ApiError('network', { status: 0, code: 'RICCON-NETWORK-000' });
        }
        return pageOf([makeDepartment()]);
      }),
    });
    renderPage(api);

    expect(await screen.findByText('部门列表加载失败')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(await screen.findByText('销售部')).toBeVisible();
  });

  it('创建部门成功后更新列表并提示', async () => {
    const created = makeDepartment({ id: 'dept-new', name: '新部门', memberCount: 0 });
    const api = makeApi({
      listTenantDepartments: vi.fn(async () => pageOf([])),
      createTenantDepartment: vi.fn(async () => created),
    });
    renderPage(api);

    await screen.findByText(/还没有部门/);
    fireEvent.click(screen.getAllByRole('button', { name: '创建部门' })[0]);
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByPlaceholderText('例如：销售部'), {
      target: { value: '新部门' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: /创建部门/ }));

    await waitFor(() => {
      expect(api.createTenantDepartment).toHaveBeenCalledWith('tenant-a', { name: '新部门' });
    });
    expect(await screen.findByText('部门“新部门”已创建。')).toBeVisible();
    expect(screen.getByText('新部门')).toBeVisible();
  });

  it('创建名称冲突 409 显示稳定中文原因', async () => {
    const api = makeApi({
      listTenantDepartments: vi.fn(async () => pageOf([])),
      createTenantDepartment: vi.fn(async () => {
        throw conflict('department name already exists');
      }),
    });
    renderPage(api);

    await screen.findByText(/还没有部门/);
    fireEvent.click(screen.getAllByRole('button', { name: '创建部门' })[0]);
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByPlaceholderText('例如：销售部'), {
      target: { value: '销售部' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: /创建部门/ }));

    expect(await screen.findByText(/部门名称已存在/)).toBeInTheDocument();
    expect(screen.queryByText(/department name already exists/)).not.toBeInTheDocument();
  });

  it('重命名成功后更新名称', async () => {
    const renamed = makeDepartment({ name: '销售一部' });
    const api = makeApi({
      renameTenantDepartment: vi.fn(async () => renamed),
    });
    renderPage(api);

    await screen.findByText('销售部');
    fireEvent.click(screen.getByRole('button', { name: '重命名：销售部' }));
    const dialog = await screen.findByRole('dialog');
    const input = within(dialog).getByPlaceholderText('例如：销售部');
    fireEvent.change(input, { target: { value: '销售一部' } });
    fireEvent.click(within(dialog).getByRole('button', { name: /保存名称/ }));

    await waitFor(() => {
      expect(api.renameTenantDepartment).toHaveBeenCalledWith('tenant-a', 'dept-sales', {
        name: '销售一部',
      });
    });
    expect(await screen.findByText('部门已重命名为“销售一部”。')).toBeVisible();
    expect(screen.getByText('销售一部')).toBeVisible();
  });

  it('停用与启用部门更新状态，停用后冻结改名', async () => {
    const disabled = makeDepartment({ status: 'DISABLED' });
    const enabled = makeDepartment({ status: 'ACTIVE' });
    const api = makeApi({
      disableTenantDepartment: vi.fn(async () => disabled),
      enableTenantDepartment: vi.fn(async () => enabled),
    });
    renderPage(api);

    await screen.findByText('销售部');
    fireEvent.click(screen.getByRole('button', { name: '停用：销售部' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/停用后成员归属保留/)).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: /停用部门/ }));

    await waitFor(() => {
      expect(api.disableTenantDepartment).toHaveBeenCalledWith('tenant-a', 'dept-sales');
    });
    expect(await screen.findByText('部门“销售部”已停用。')).toBeVisible();
    expect(screen.getByRole('button', { name: '重命名：销售部' })).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: '启用：销售部' }));
    const enableDialog = await waitFor(() => {
      const found = screen
        .getAllByRole('dialog')
        .find((item) => within(item).queryByText(/启用后恢复原成员关系/));
      expect(found).toBeTruthy();
      return found ?? screen.getByRole('dialog');
    });
    fireEvent.click(await within(enableDialog).findByRole('button', { name: /启用部门/ }));

    await waitFor(() => {
      expect(api.enableTenantDepartment).toHaveBeenCalledWith('tenant-a', 'dept-sales');
    });
    expect(await screen.findByText('部门“销售部”已启用。')).toBeVisible();
    expect(screen.getByRole('button', { name: '重命名：销售部' })).toBeEnabled();
  });

  it('成员抽屉支持加入、移交负责人，并保护当前负责人不被移出', async () => {
    const transferred = makeDepartment({
      managerUserId: 'user-2',
      managerDisplayName: '王敏',
    });
    const api = makeApi({
      listTenantDepartmentMembers: vi.fn(async () =>
        pageOf([
          makeMember(),
          makeMember({ id: 'user-2', username: 'wangmin', displayName: '王敏' }),
        ]),
      ),
      listTenantUsers: vi.fn(async () =>
        pageOf([
          makeUser(),
          makeUser({ id: 'user-2', username: 'wangmin', displayName: '王敏' }),
          makeUser({ id: 'user-3', username: 'zhaomin', displayName: '赵敏' }),
        ]),
      ),
      addTenantDepartmentMember: vi.fn(async () =>
        makeMember({ id: 'user-3', username: 'zhaomin', displayName: '赵敏' }),
      ),
      transferTenantDepartmentManager: vi.fn(async () => transferred),
    });
    renderPage(api);

    await screen.findByText('销售部');
    fireEvent.click(screen.getByRole('button', { name: '成员管理：销售部' }));
    const drawer = await screen.findByRole('dialog');
    expect(await within(drawer).findByText('李强')).toBeVisible();
    expect(within(drawer).getByText('负责人')).toBeVisible();
    expect(within(drawer).getByRole('button', { name: '移出成员：李强' })).toBeDisabled();

    fireEvent.mouseDown(within(drawer).getByRole('combobox', { name: '加入目标租户账号' }));
    const option = await screen.findByText('赵敏（zhaomin）');
    fireEvent.click(option);
    fireEvent.click(within(drawer).getByRole('button', { name: '加入部门' }));
    await waitFor(() => {
      expect(api.addTenantDepartmentMember).toHaveBeenCalledWith(
        'tenant-a',
        'dept-sales',
        'user-3',
      );
    });
    expect(await within(drawer).findByText('赵敏')).toBeVisible();
    expect(within(drawer).getByRole('button', { name: '移出成员：赵敏' })).toBeEnabled();

    fireEvent.click(within(drawer).getByRole('button', { name: '设为负责人：王敏' }));
    await waitFor(() => {
      expect(api.transferTenantDepartmentManager).toHaveBeenCalledWith(
        'tenant-a',
        'dept-sales',
        'user-2',
      );
    });
    await waitFor(() => {
      expect(within(drawer).getByRole('button', { name: '移出成员：王敏' })).toBeDisabled();
    });
    expect(within(drawer).getByRole('button', { name: '设为负责人：李强' })).toBeVisible();
  });

  it('成员抽屉 409 显示可读原因', async () => {
    const api = makeApi({
      listTenantDepartmentMembers: vi.fn(async () => pageOf([makeMember()])),
      listTenantUsers: vi.fn(async () =>
        pageOf([makeUser(), makeUser({ id: 'user-3', username: 'zhaomin', displayName: '赵敏' })]),
      ),
      addTenantDepartmentMember: vi.fn(async () => {
        throw conflict('member already belongs to another department');
      }),
    });
    renderPage(api);

    await screen.findByText('销售部');
    fireEvent.click(screen.getByRole('button', { name: '成员管理：销售部' }));
    const drawer = await screen.findByRole('dialog');
    await within(drawer).findByText('李强');
    fireEvent.mouseDown(within(drawer).getByRole('combobox', { name: '加入目标租户账号' }));
    fireEvent.click(await screen.findByText('赵敏（zhaomin）'));
    fireEvent.click(within(drawer).getByRole('button', { name: '加入部门' }));

    expect(await within(drawer).findByText(/已属于其他部门/)).toBeVisible();
  });

  it('成员抽屉加载失败显示错误并可重试', async () => {
    let attempts = 0;
    const api = makeApi({
      listTenantDepartmentMembers: vi.fn(async () => {
        attempts += 1;
        if (attempts === 1) {
          throw new ApiError('forbidden', { status: 403, code: 'RICCON-AUTH-403' });
        }
        return pageOf([makeMember()]);
      }),
    });
    renderPage(api);

    await screen.findByText('销售部');
    fireEvent.click(screen.getByRole('button', { name: '成员管理：销售部' }));
    const drawer = await screen.findByRole('dialog');
    expect(await within(drawer).findByText('成员加载失败')).toBeVisible();
    fireEvent.click(within(drawer).getByRole('button', { name: /重\s*试/ }));
    expect(await within(drawer).findByText('李强')).toBeVisible();
  });
});
