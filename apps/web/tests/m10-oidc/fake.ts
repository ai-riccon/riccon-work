import { vi } from 'vitest';
import type { SessionInfo, UserSummary } from '@/services/api/types';
import type { OidcAuthApi } from '@/services/auth/oidc-api';
import type { UserAdminApi } from '@/services/users/api';
import { DEMO_SESSION } from '@/test/fake-api';

/**
 * M10 OIDC/绑定测试夹具。
 *
 * 放在 `apps/web/tests/m10-oidc` 下，不进入 `src/**` 覆盖率分母；
 * 页面测试与页面共置，保证 Vitest 默认 include 能发现用例。
 */

/** 普通员工会话：没有任何账号管理权限。 */
export const MEMBER_SESSION: SessionInfo = DEMO_SESSION;

/** 租户管理员会话：可管理本租户账号与 OIDC 绑定。 */
export const TENANT_ADMIN_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  roles: ['TENANT_ADMIN'],
  permissions: [...DEMO_SESSION.permissions, 'tenant.user.manage'],
};

/** 平台管理员会话：可管理目标租户账号元数据与 OIDC 绑定。 */
export const PLATFORM_ADMIN_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  user: { ...DEMO_SESSION.user, id: 'platform-admin', displayName: '赵蕾' },
  tenant: { ...DEMO_SESSION.tenant, id: 'platform', name: '平台' },
  roles: ['PLATFORM_ADMIN'],
  permissions: ['platform.tenant.manage'],
};

/** 构造账号摘要。 */
export function makeUser(overrides: Partial<UserSummary> = {}): UserSummary {
  return {
    id: 'user-1',
    username: 'wangmin',
    displayName: '王敏',
    email: 'wangmin@example.com',
    status: 'ACTIVE',
    roles: ['MEMBER'],
    authSource: 'LOCAL',
    lastLoginAt: '2026-11-19T10:00:00+08:00',
    createdAt: '2026-11-01T09:00:00+08:00',
    ...overrides,
  };
}

/** 构造分页。 */
export function pageOf<T>(
  items: T[],
  page = 1,
  size = 100,
): {
  page: number;
  size: number;
  total: number;
  totalPages: number;
  items: T[];
} {
  return {
    page,
    size,
    total: items.length,
    totalPages: items.length > 0 ? 1 : 0,
    items,
  };
}

/**
 * 构造用户管理 API 替身。
 *
 * @param overrides 局部覆盖
 * @returns 全部方法均为 vi.fn 的 UserAdminApi
 */
export function createUserAdminApi(overrides: Partial<UserAdminApi> = {}): UserAdminApi {
  return {
    listUsers: vi.fn(async () => pageOf([makeUser()])),
    bindOidc: vi.fn(async (userId: string) => makeUser({ id: userId, authSource: 'OIDC' })),
    unbindOidc: vi.fn(async (userId: string) => makeUser({ id: userId, authSource: 'LOCAL' })),
    listTenantUsers: vi.fn(async () => pageOf([makeUser()])),
    bindTenantUserOidc: vi.fn(async (tenantId: string, userId: string) => {
      void tenantId;
      return makeUser({ id: userId, authSource: 'OIDC' });
    }),
    unbindTenantUserOidc: vi.fn(async (tenantId: string, userId: string) => {
      void tenantId;
      return makeUser({ id: userId, authSource: 'LOCAL' });
    }),
    ...overrides,
  };
}

/**
 * 构造 OIDC 登录 API 替身。
 *
 * @param overrides 局部覆盖
 * @returns OidcAuthApi
 */
export function createOidcApi(overrides: Partial<OidcAuthApi> = {}): OidcAuthApi {
  return {
    getProvider: vi.fn(async () => ({ enabled: true, providerName: 'Acme 企业账号' })),
    authorizeUrl: vi.fn((tenantCode: string) => `/api/v1/auth/oidc/authorize/tenant-${tenantCode}`),
    ...overrides,
  };
}
