import { describe, expect, it } from 'vitest';
import { ApiError } from '@/services/api/errors';
import {
  PLATFORM_TENANT_ID,
  USER_PERMISSIONS,
  authSourceColor,
  authSourceLabel,
  formatUserTime,
  hasPermission,
  userStatusLabel,
} from './access';
import { describeBindError, describeUnbindError, describeUserListError } from './errors';

describe('用户管理权限与展示', () => {
  it('权限码与平台租户常量稳定', () => {
    expect(USER_PERMISSIONS).toEqual({
      tenantManage: 'tenant.user.manage',
      platformManage: 'platform.tenant.manage',
    });
    expect(PLATFORM_TENANT_ID).toBe('platform');
    expect(hasPermission(['tenant.user.manage'], USER_PERMISSIONS.tenantManage)).toBe(true);
    expect(hasPermission(['tenant.user.manage'], USER_PERMISSIONS.platformManage)).toBe(false);
    expect(hasPermission(undefined, USER_PERMISSIONS.tenantManage)).toBe(false);
  });

  it('认证来源不猜测缺失值', () => {
    expect(authSourceLabel('OIDC')).toBe('企业账号');
    expect(authSourceLabel('LOCAL')).toBe('内置账号');
    expect(authSourceLabel(undefined)).toBe('未标注');
    expect(authSourceColor('OIDC')).toBe('blue');
    expect(authSourceColor('LOCAL')).toBe('default');
    expect(authSourceColor(undefined)).toBe('orange');
  });

  it('账号状态标签', () => {
    expect(userStatusLabel({ status: 'ACTIVE' })).toBe('启用');
    expect(userStatusLabel({ status: 'DISABLED' })).toBe('已禁用');
  });

  it('时间按北京时间格式化，非法值不泄露原文', () => {
    expect(formatUserTime('2026-11-19T02:00:00Z')).toBe('2026-11-19 10:00:00');
    expect(formatUserTime(null)).toBe('—');
    expect(formatUserTime('not-a-date')).toBe('—');
  });
});

describe('用户管理错误文案', () => {
  it('列表错误区分权限、租户与网络', () => {
    expect(describeUserListError(new ApiError('x', { status: 403, code: 'A' }))).toContain(
      '账号管理权限',
    );
    expect(describeUserListError(new ApiError('x', { status: 404, code: 'A' }))).toContain(
      '租户不存在',
    );
    expect(describeUserListError(new ApiError('x', { status: 0, code: 'A' }))).toContain('网络');
    expect(describeUserListError(new ApiError('x', { status: 429, code: 'A' }))).toContain('频繁');
    expect(describeUserListError(new ApiError('x', { status: 401, code: 'A' }))).toContain(
      '登录状态',
    );
    expect(describeUserListError(new ApiError('x', { status: 503, code: 'A' }))).toContain(
      '暂时不可用',
    );
    expect(describeUserListError(new ApiError('x', { status: 418, code: 'A' }))).toContain(
      '加载失败',
    );
    expect(describeUserListError(new Error('boom'))).toContain('加载失败');
  });

  it('绑定错误区分 409/400/404/403/500，且不回显英文 detail', () => {
    const conflict = new ApiError('English internal detail', {
      status: 409,
      code: 'RICCON-OIDC-409',
    });
    const message = describeBindError(conflict);
    expect(message).toContain('已绑定');
    expect(message).not.toContain('English internal detail');

    expect(
      describeBindError(new ApiError('x', { status: 400, code: 'RICCON-OIDC-400' })),
    ).toContain('issuer');
    expect(describeBindError(new ApiError('x', { status: 400, code: 'A' }))).toContain('issuer');
    expect(describeBindError(new ApiError('x', { status: 403, code: 'A' }))).toContain('权限');
    expect(describeBindError(new ApiError('x', { status: 404, code: 'A' }))).toContain('不存在');
    expect(describeBindError(new ApiError('x', { status: 0, code: 'A' }))).toContain('网络');
    expect(describeBindError(new ApiError('x', { status: 401, code: 'A' }))).toContain('登录状态');
    expect(describeBindError(new ApiError('x', { status: 503, code: 'A' }))).toContain(
      '暂时不可用',
    );
    expect(describeBindError(new ApiError('x', { status: 418, code: 'A' }))).toContain('绑定');
    expect(describeBindError(new Error('boom'))).toContain('绑定');
  });

  it('解绑错误提示新密码规则和状态冲突', () => {
    expect(
      describeUnbindError(new ApiError('x', { status: 409, code: 'RICCON-OIDC-409' })),
    ).toContain('未绑定');
    expect(describeUnbindError(new ApiError('x', { status: 400, code: 'A' }))).toContain('12–200');
    expect(describeUnbindError(new ApiError('x', { status: 404, code: 'A' }))).toContain('不存在');
    expect(describeUnbindError(new ApiError('x', { status: 403, code: 'A' }))).toContain('权限');
    expect(describeUnbindError(new ApiError('x', { status: 0, code: 'A' }))).toContain('网络');
    expect(describeUnbindError(new ApiError('x', { status: 503, code: 'A' }))).toContain(
      '暂时不可用',
    );
    expect(describeUnbindError(new ApiError('x', { status: 418, code: 'A' }))).toContain(
      '解除绑定',
    );
    expect(describeUnbindError(new Error('boom'))).toContain('解除绑定');
  });
});
