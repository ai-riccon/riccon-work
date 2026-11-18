import { describe, expect, it } from 'vitest';
import type { Department, ManagedWorkspace } from '@/services/api/types';
import {
  canManageWorkspace,
  isReadOnlyWorkspace,
  isTenantAdminSession,
  manageableDepartments,
  workspaceVisibilityLabel,
} from './visibility';

/** 构造部门。 */
function makeDepartment(overrides: Partial<Department> = {}): Department {
  return {
    id: 'dept-sales',
    name: '销售部',
    status: 'ACTIVE',
    managerUserId: 'user-manager',
    managerDisplayName: '李强',
    memberCount: 3,
    createdAt: '2026-11-18T08:00:00.000Z',
    updatedAt: '2026-11-18T08:00:00.000Z',
    ...overrides,
  };
}

/** 构造团队空间。 */
function makeSpace(overrides: Partial<ManagedWorkspace> = {}): ManagedWorkspace {
  return {
    id: 'workspace-team',
    kind: 'TEAM',
    name: '销售部项目空间',
    description: null,
    ownerUserId: 'user-owner',
    departmentId: 'dept-sales',
    departmentName: '销售部',
    visibility: 'DEPT',
    status: 'ACTIVE',
    createdAt: '2026-11-18T08:00:00.000Z',
    updatedAt: '2026-11-18T08:00:00.000Z',
    ...overrides,
  };
}

describe('空间可见性辅助', () => {
  it('把四种可见性映射为明确中文标签，历史 TENANT 标注只读', () => {
    expect(workspaceVisibilityLabel('PRIVATE')).toContain('仅本人');
    expect(workspaceVisibilityLabel('TENANT')).toContain('全租户可见');
    expect(workspaceVisibilityLabel('TENANT')).toContain('只读');
    expect(workspaceVisibilityLabel('DEPT')).toBe('本部门成员');
    expect(workspaceVisibilityLabel('CUSTOM')).toBe('指定成员');
  });

  it('历史 TENANT 与个人空间都视为只读', () => {
    expect(isReadOnlyWorkspace(makeSpace({ visibility: 'TENANT', departmentId: null }))).toBe(true);
    expect(isReadOnlyWorkspace(makeSpace({ visibility: 'PRIVATE', departmentId: null }))).toBe(
      true,
    );
    expect(isReadOnlyWorkspace(makeSpace({ visibility: 'DEPT' }))).toBe(false);
    expect(isReadOnlyWorkspace(makeSpace({ visibility: 'CUSTOM' }))).toBe(false);
  });

  it('只有可管理部门的空间允许管理，跨部门与无部门空间返回 false', () => {
    const manageable = new Set(['dept-sales']);
    expect(canManageWorkspace(makeSpace(), manageable)).toBe(true);
    expect(canManageWorkspace(makeSpace({ visibility: 'CUSTOM' }), manageable)).toBe(true);
    expect(canManageWorkspace(makeSpace({ departmentId: 'dept-marketing' }), manageable)).toBe(
      false,
    );
    expect(canManageWorkspace(makeSpace({ departmentId: null }), manageable)).toBe(false);
    expect(
      canManageWorkspace(makeSpace({ visibility: 'TENANT', departmentId: null }), manageable),
    ).toBe(false);
  });

  it('租户管理员可管理全部启用部门，负责人只管理自己的部门，普通成员为空', () => {
    const departments = [
      makeDepartment(),
      makeDepartment({ id: 'dept-marketing', name: '市场部', managerUserId: 'user-other' }),
      makeDepartment({ id: 'dept-disabled', name: '已停用部门', status: 'DISABLED' }),
    ];

    expect(manageableDepartments(departments, 'user-manager', true).map((item) => item.id)).toEqual(
      ['dept-sales', 'dept-marketing'],
    );
    expect(
      manageableDepartments(departments, 'user-manager', false).map((item) => item.id),
    ).toEqual(['dept-sales']);
    expect(manageableDepartments(departments, 'user-member', false)).toEqual([]);
  });

  it('会话角色或 workspace.write 任一命中即视为租户管理员', () => {
    expect(isTenantAdminSession(['TENANT_ADMIN'], [])).toBe(true);
    expect(isTenantAdminSession(['MEMBER'], ['workspace.write'])).toBe(true);
    expect(isTenantAdminSession(['MEMBER'], ['workspace.read'])).toBe(false);
  });
});
