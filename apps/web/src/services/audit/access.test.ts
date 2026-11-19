import { describe, expect, it } from 'vitest';
import { AUDIT_PERMISSIONS, hasPermission } from './access';

describe('审计权限', () => {
  it('权限码与后端稳定词汇一致', () => {
    expect(AUDIT_PERMISSIONS).toEqual({
      read: 'audit.read',
      approve: 'audit.export.approve',
      platformApprove: 'platform.audit.export.approve',
    });
  });

  it('权限缺失时返回 false', () => {
    expect(hasPermission(undefined, AUDIT_PERMISSIONS.read)).toBe(false);
    expect(hasPermission([], AUDIT_PERMISSIONS.read)).toBe(false);
    expect(hasPermission(['workspace.read'], AUDIT_PERMISSIONS.read)).toBe(false);
  });

  it('精确匹配权限码，不做前缀或通配', () => {
    expect(hasPermission([AUDIT_PERMISSIONS.read], AUDIT_PERMISSIONS.read)).toBe(true);
    expect(hasPermission(['audit.read.all'], AUDIT_PERMISSIONS.read)).toBe(false);
  });
});
