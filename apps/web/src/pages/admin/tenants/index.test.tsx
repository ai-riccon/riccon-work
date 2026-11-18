// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
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
import AdminTenantsPage from './index';

/** 构造租户。 */
function makeTenant(overrides: Partial<TenantSummary> = {}): TenantSummary {
  return {
    id: 'tenant-a',
    name: '示例企业',
    status: 'ACTIVE',
    retentionDays: 180,
    createdAt: '2026-11-18T08:00:00.000Z',
    ...overrides,
  };
}

/**
 * 构造平台部门 API 替身。
 *
 * 列表页只调用 listTenants；其余方法补齐以满足接口类型。
 *
 * @param overrides 局部覆盖
 * @returns PlatformDepartmentApi
 */
function makePlatformApi(overrides: Partial<PlatformDepartmentApi> = {}): PlatformDepartmentApi {
  const notUsed = async () => {
    throw new Error('当前用例不应调用该端点');
  };
  return {
    listTenants: vi.fn(async () => pageOf([makeTenant()])),
    listTenantDepartments: vi.fn(async () => pageOf<Department>([])),
    createTenantDepartment: vi.fn(notUsed),
    renameTenantDepartment: vi.fn(notUsed),
    disableTenantDepartment: vi.fn(notUsed),
    enableTenantDepartment: vi.fn(notUsed),
    listTenantDepartmentMembers: vi.fn(async () => pageOf<DepartmentMember>([])),
    addTenantDepartmentMember: vi.fn(notUsed),
    removeTenantDepartmentMember: vi.fn(async () => undefined),
    transferTenantDepartmentManager: vi.fn(notUsed),
    listTenantUsers: vi.fn(async () => pageOf<UserSummary>([])),
    ...overrides,
  };
}

describe('平台管理员租户列表页', () => {
  it('展示租户元数据，并为启用租户提供部门管理入口', async () => {
    const api = makePlatformApi({
      listTenants: vi.fn(async () =>
        pageOf([
          makeTenant(),
          makeTenant({ id: 'tenant-b', name: '暂停企业', status: 'SUSPENDED' }),
        ]),
      ),
    });
    render(withProviders(<AdminTenantsPage api={api} />));

    expect(await screen.findByText('示例企业')).toBeVisible();
    expect(screen.getByText('暂停企业')).toBeVisible();
    expect(screen.getByRole('link', { name: '管理部门：示例企业' })).toHaveAttribute(
      'href',
      '/admin/tenants/tenant-a/departments',
    );
    expect(screen.queryByRole('link', { name: '管理部门：暂停企业' })).not.toBeInTheDocument();
    expect(screen.getByText(/租户已暂停，部门管理不可用/)).toBeVisible();
    expect(screen.getAllByText(/审计保留 180 天/)).toHaveLength(2);
  });

  it('空列表展示空态', async () => {
    const api = makePlatformApi({ listTenants: vi.fn(async () => pageOf([])) });
    render(withProviders(<AdminTenantsPage api={api} />));

    expect(await screen.findByText(/还没有租户/)).toBeVisible();
  });

  it('无权限展示 403 与可读说明', async () => {
    const api = makePlatformApi({
      listTenants: vi.fn(async () => {
        throw new ApiError('forbidden', { status: 403, code: 'RICCON-AUTH-403' });
      }),
    });
    render(withProviders(<AdminTenantsPage api={api} />));

    expect(await screen.findByText('没有访问权限')).toBeVisible();
    expect(screen.getByText(/只有平台管理员/)).toBeVisible();
  });

  it('加载失败可重试，恢复后展示列表', async () => {
    let attempts = 0;
    const api = makePlatformApi({
      listTenants: vi.fn(async () => {
        attempts += 1;
        if (attempts === 1) {
          throw new ApiError('network', { status: 0, code: 'RICCON-NETWORK-000' });
        }
        return pageOf([makeTenant()]);
      }),
    });
    render(withProviders(<AdminTenantsPage api={api} />));

    expect(await screen.findByText('租户列表加载失败')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    await waitFor(() => {
      expect(api.listTenants).toHaveBeenCalledTimes(2);
    });
    expect(await screen.findByText('示例企业')).toBeVisible();
  });
});
