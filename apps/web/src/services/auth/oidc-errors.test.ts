import { describe, expect, it } from 'vitest';
import { describeOidcLoginError } from './oidc-errors';

describe('OIDC 登录错误文案', () => {
  it.each([
    ['idp_unavailable', '企业账号服务暂时不可用', 'error'],
    ['state_invalid', '企业账号登录已失效', 'warning'],
    ['access_denied', '企业账号授权已取消', 'warning'],
    ['account_not_bound', '账号尚未关联企业身份', 'error'],
    ['account_disabled', '账号已被禁用', 'error'],
    ['tenant_unavailable', '租户当前不可用', 'error'],
    ['issuer_mismatch', '企业账号配置不匹配', 'error'],
    ['rate_limited', '尝试次数过多', 'warning'],
    ['login_failed', '企业账号登录未完成', 'error'],
  ] as const)('原因码 %s 映射为独立标题与颜色', (code, title, type) => {
    const presentation = describeOidcLoginError(code);
    expect(presentation).toMatchObject({ code, title, type });
    expect(presentation?.nextStep.length).toBeGreaterThan(0);
  });

  it('未知原因码返回通用文案，不包含原始值', () => {
    const presentation = describeOidcLoginError('issuer://secret-host');
    expect(presentation).toMatchObject({ code: 'unknown', title: '企业账号登录未完成' });
    expect(JSON.stringify(presentation)).not.toContain('secret-host');
  });

  it('未提供原因码时不显示提示', () => {
    expect(describeOidcLoginError(null)).toBeNull();
    expect(describeOidcLoginError(undefined)).toBeNull();
    expect(describeOidcLoginError('')).toBeNull();
  });
});
