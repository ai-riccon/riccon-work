import { describe, expect, it } from 'vitest';
import { filterAdminNavigation, findRouteByPath } from '../../../../config/routes';

/** 平台管理员 M04 权限集合（与 V11 种子一致）。 */
const PLATFORM_PERMISSIONS = [
  'platform.tenant.manage',
  'platform.model.read',
  'platform.model.manage',
  'platform.quota.manage',
  'platform.cost.read',
  'audit.read',
];

/** 租户管理员 M04 权限集合（与 V11 种子一致）。 */
const TENANT_PERMISSIONS = [
  'tenant.user.manage',
  'quota.read',
  'cost.read',
  'audit.read',
  'audit.export.approve',
];

/** 员工权限：没有配额/成本/模型入口。 */
const EMPLOYEE_PERMISSIONS = ['workspace.read', 'conversation.read', 'conversation.write'];

/**
 * 读取指定权限下的可见菜单。
 *
 * @param permissions 权限码
 * @returns 菜单项
 */
function visibleMenu(permissions: readonly string[]) {
  return filterAdminNavigation(permissions);
}

describe('M04 四页菜单联动', () => {
  it('平台管理员看到“模型路由/模型 key/租户配额/成本看板”四个连续入口', () => {
    const menu = visibleMenu(PLATFORM_PERMISSIONS);
    const paths = menu.map((item) => item.path);
    const labels = menu.filter((item) => ['/admin/quotas', '/admin/costs'].includes(item.path));

    expect(labels.map((item) => item.label)).toEqual(['租户配额', '成本看板']);
    expect(paths).toContain('/admin/model-routing');
    expect(paths).toContain('/admin/model-keys');
    expect(paths.indexOf('/admin/model-routing')).toBeLessThan(paths.indexOf('/admin/model-keys'));
    expect(paths.indexOf('/admin/model-keys')).toBeLessThan(paths.indexOf('/admin/quotas'));
    expect(paths.indexOf('/admin/quotas')).toBeLessThan(paths.indexOf('/admin/costs'));
  });

  it('租户管理员只看到“配额/成本”，看不到平台模型入口', () => {
    const menu = visibleMenu(TENANT_PERMISSIONS);
    const paths = menu.map((item) => item.path);
    const quotaCost = menu.filter((item) => ['/admin/quotas', '/admin/costs'].includes(item.path));

    expect(quotaCost.map((item) => item.label)).toEqual(['配额', '成本']);
    expect(paths).not.toContain('/admin/model-routing');
    expect(paths).not.toContain('/admin/model-keys');
    // 配额/成本位于用户管理之后、审计之前，符合租户管理员的阅读顺序。
    expect(paths.indexOf('/admin/users')).toBeLessThan(paths.indexOf('/admin/quotas'));
    expect(paths.indexOf('/admin/quotas')).toBeLessThan(paths.indexOf('/admin/audit'));
  });

  it('员工没有任何配额、成本或模型入口', () => {
    const paths = visibleMenu(EMPLOYEE_PERMISSIONS).map((item) => item.path);
    expect(paths).not.toContain('/admin/quotas');
    expect(paths).not.toContain('/admin/costs');
    expect(paths).not.toContain('/admin/model-routing');
    expect(paths).not.toContain('/admin/model-keys');
  });

  it('同一角色不会同时看到平台与租户两套文案', () => {
    const platformLabels = visibleMenu(PLATFORM_PERMISSIONS)
      .filter((item) => ['/admin/quotas', '/admin/costs'].includes(item.path))
      .map((item) => item.label);
    const tenantLabels = visibleMenu(TENANT_PERMISSIONS)
      .filter((item) => ['/admin/quotas', '/admin/costs'].includes(item.path))
      .map((item) => item.label);

    expect(platformLabels).not.toContain('配额');
    expect(platformLabels).not.toContain('成本');
    expect(tenantLabels).not.toContain('租户配额');
    expect(tenantLabels).not.toContain('成本看板');
  });

  it('配额/成本菜单都对应已注册路由，入口不会落到 404', () => {
    expect(findRouteByPath('/admin/quotas')?.component).toBe('@/pages/admin/quotas');
    expect(findRouteByPath('/admin/quotas')?.title).toBe('配额');
    expect(findRouteByPath('/admin/costs')?.component).toBe('@/pages/admin/costs');
    expect(findRouteByPath('/admin/costs')?.title).toBe('成本');
    visibleMenu(PLATFORM_PERMISSIONS)
      .concat(visibleMenu(TENANT_PERMISSIONS))
      .forEach((item) => {
        expect(findRouteByPath(item.path), `${item.path} 未注册路由`).toBeDefined();
      });
  });
});
