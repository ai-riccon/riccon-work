// @vitest-environment jsdom
import { render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { SessionInfo, UserSummary } from '@/services/api/types';
import { authStore } from '@/services/auth/auth-store';
import { DEMO_SESSION, createFakeApi } from '@/test/fake-api';
import { withProviders } from '@/test/render';
import ChatPage from './index';

/** 构造带指定来源字段的会话。 */
function sessionWithUser(overrides: Partial<UserSummary>): SessionInfo {
  return {
    ...DEMO_SESSION,
    user: { ...DEMO_SESSION.user, ...overrides },
  };
}

/** 渲染工作空间页；测试替身不发起真实网络请求。 */
function renderChat(): ReturnType<typeof render> {
  return render(
    withProviders(<ChatPage api={createFakeApi()} navigate={vi.fn()} pollIntervalMs={10} />),
  );
}

/**
 * 工作空间头部来源 IdP 展示。
 *
 * 覆盖 LOCAL/OIDC/两 issuer/空值/长 provider；375px、键盘与可访问性只做结构基线，
 * 完整 axe 扫描由 M15 verifier 的 E2E 承担。
 */
describe('工作空间头部来源 IdP', () => {
  beforeEach(() => {
    authStore.reset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('LOCAL 显示“来源：本地账号”，不显示邮箱等敏感字段', async () => {
    authStore.setAuthenticated(
      sessionWithUser({
        authSource: 'LOCAL',
        identityProvider: null,
        email: 'employee@example.com',
      }),
    );
    renderChat();

    expect(await screen.findByText('来源：本地账号')).toBeVisible();
    expect(screen.queryByText(/employee@example\.com/)).not.toBeInTheDocument();
  });

  it('OIDC 显示 providerName，两个 issuer 的脱敏 host 可区分', async () => {
    authStore.setAuthenticated(
      sessionWithUser({ authSource: 'OIDC', identityProvider: 'Acme 企业账号' }),
    );
    const first = renderChat();
    expect(await screen.findByText('来源：Acme 企业账号')).toBeVisible();
    first.unmount();

    authStore.setAuthenticated(
      sessionWithUser({ authSource: 'OIDC', identityProvider: 'idp-b.example.com:8443' }),
    );
    renderChat();
    expect(await screen.findByText('来源：idp-b.example.com:8443')).toBeVisible();
    expect(screen.queryByText('来源：Acme 企业账号')).not.toBeInTheDocument();
  });

  it('OIDC 缺少 identityProvider 时显示未标注，不猜测具体 IdP', async () => {
    authStore.setAuthenticated(sessionWithUser({ authSource: 'OIDC', identityProvider: null }));
    renderChat();

    expect(await screen.findByText('来源：未标注')).toBeVisible();
  });

  it('375px 与键盘基线：长 provider 完整展示、可悬停查看且不新增焦点陷阱', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 375 });
    const longName = `企业身份提供方-${'长'.repeat(240)}`;
    authStore.setAuthenticated(sessionWithUser({ authSource: 'OIDC', identityProvider: longName }));
    renderChat();

    const label = await screen.findByText(`来源：${longName}`);
    expect(label).toBeVisible();
    // 窄屏使用 CSS 截断，title 保留完整值供指针用户查看；文本仍是完整可访问名称。
    expect(label).toHaveAttribute('title', `来源：${longName}`);
    expect(label).not.toHaveAttribute('aria-hidden');
    expect(label).not.toHaveAttribute('tabindex');

    const logout = screen.getByRole('button', { name: /退出登录/ });
    logout.focus();
    expect(logout).toHaveFocus();
  });
});
