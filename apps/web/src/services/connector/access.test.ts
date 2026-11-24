import { describe, expect, it } from 'vitest';
import type { SessionInfo } from '@/services/api/types';
import { hasConnectorPermission, isPlatformAdminSession } from './access';

/** 构造最小会话。 */
function session(overrides: Partial<SessionInfo> = {}): SessionInfo {
  return {
    authenticated: true,
    user: {
      id: 'user-1',
      username: 'demo',
      displayName: '演示用户',
      email: null,
      status: 'ACTIVE',
      roles: ['TENANT_ADMIN'],
      lastLoginAt: null,
      createdAt: '2026-11-20T10:00:00+08:00',
    },
    tenant: {
      id: 'tenant-a',
      name: '示例企业',
      status: 'ACTIVE',
      retentionDays: 180,
      createdAt: '2026-11-20T10:00:00+08:00',
    },
    roles: ['TENANT_ADMIN'],
    permissions: ['knowledge.connector.read', 'knowledge.connector.manage'],
    csrfToken: 'csrf-1',
    expiresAt: '2026-11-20T11:00:00+08:00',
    ...overrides,
  };
}

describe('连接器权限', () => {
  it('按权限码判断读/写权限，未加载按无权限处理', () => {
    expect(hasConnectorPermission(['knowledge.connector.read'], 'knowledge.connector.read')).toBe(
      true,
    );
    expect(hasConnectorPermission(['knowledge.connector.read'], 'knowledge.connector.manage')).toBe(
      false,
    );
    expect(hasConnectorPermission(undefined, 'knowledge.connector.read')).toBe(false);
  });

  it('平台管理员识别用于 404 文案，不依赖连接器权限', () => {
    expect(
      isPlatformAdminSession(
        session({ roles: ['PLATFORM_ADMIN'], permissions: ['platform.tenant.manage'] }),
      ),
    ).toBe(true);
    expect(isPlatformAdminSession(session())).toBe(false);
    expect(isPlatformAdminSession(null)).toBe(false);
  });
});
