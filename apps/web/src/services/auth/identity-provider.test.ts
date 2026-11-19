import { describe, expect, it } from 'vitest';
import type { UserSummary } from '@/services/api/types';
import {
  LOCAL_IDENTITY_PROVIDER_LABEL,
  UNKNOWN_IDENTITY_PROVIDER_LABEL,
  identityProviderLabel,
  identityProviderSummary,
} from './identity-provider';

/** 构造只包含来源字段的账号摘要；其余字段不参与展示逻辑。 */
function subject(
  overrides: Partial<Pick<UserSummary, 'authSource' | 'identityProvider'>> = {},
): Pick<UserSummary, 'authSource' | 'identityProvider'> {
  return { authSource: 'LOCAL', identityProvider: null, ...overrides };
}

describe('来源 IdP 展示文案', () => {
  it('LOCAL 固定显示本地账号，不展示任何外部 IdP 值', () => {
    expect(identityProviderLabel(subject())).toBe(LOCAL_IDENTITY_PROVIDER_LABEL);
    expect(identityProviderSummary(subject())).toBe('来源：本地账号');
    // 历史或异常响应即使带 provider 值，LOCAL 也不应被误标成外部 IdP。
    expect(identityProviderLabel(subject({ identityProvider: 'acme.example.com' }))).toBe(
      LOCAL_IDENTITY_PROVIDER_LABEL,
    );
  });

  it('OIDC 展示服务端配置的 providerName', () => {
    expect(
      identityProviderLabel(subject({ authSource: 'OIDC', identityProvider: 'Acme 企业账号' })),
    ).toBe('Acme 企业账号');
    expect(
      identityProviderSummary(subject({ authSource: 'OIDC', identityProvider: 'Acme 企业账号' })),
    ).toBe('来源：Acme 企业账号');
  });

  it('两个 issuer 的脱敏 host 文案可区分', () => {
    const first = identityProviderLabel(
      subject({ authSource: 'OIDC', identityProvider: 'idp-a.example.com' }),
    );
    const second = identityProviderLabel(
      subject({ authSource: 'OIDC', identityProvider: 'idp-b.example.com:8443' }),
    );

    expect(first).toBe('idp-a.example.com');
    expect(second).toBe('idp-b.example.com:8443');
    expect(first).not.toBe(second);
  });

  it('OIDC 缺少 identityProvider 时显示未标注，不猜测具体 IdP', () => {
    expect(identityProviderLabel(subject({ authSource: 'OIDC', identityProvider: null }))).toBe(
      UNKNOWN_IDENTITY_PROVIDER_LABEL,
    );
    expect(
      identityProviderLabel(subject({ authSource: 'OIDC', identityProvider: undefined })),
    ).toBe(UNKNOWN_IDENTITY_PROVIDER_LABEL);
    expect(identityProviderLabel(subject({ authSource: 'OIDC', identityProvider: '   ' }))).toBe(
      UNKNOWN_IDENTITY_PROVIDER_LABEL,
    );
  });

  it('认证来源未知但服务端给了 provider 时照常展示脱敏值', () => {
    expect(
      identityProviderLabel(
        subject({ authSource: undefined, identityProvider: 'idp.example.com' }),
      ),
    ).toBe('idp.example.com');
    expect(identityProviderLabel(subject({ authSource: undefined, identityProvider: null }))).toBe(
      UNKNOWN_IDENTITY_PROVIDER_LABEL,
    );
  });

  it('保留 255 字符上限的 provider 名称并去除首尾空白', () => {
    const longName = `企业身份提供方-${'长'.repeat(240)}`;
    expect(identityProviderLabel(subject({ authSource: 'OIDC', identityProvider: longName }))).toBe(
      longName,
    );
    expect(
      identityProviderLabel(subject({ authSource: 'OIDC', identityProvider: '  Acme  ' })),
    ).toBe('Acme');
  });
});
