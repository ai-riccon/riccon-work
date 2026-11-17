// @vitest-environment jsdom
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import LoginPage from './index';

/**
 * 登录页组件测试。
 *
 * 骨架阶段的验收点是“不可误用”：凭据输入与提交入口必须保持禁用，
 * 同时向用户说明认证何时可用、如何返回工作空间。
 */
describe('登录页', () => {
  it('展示未开放提示，且账号、密码与提交按钮不可用', () => {
    render(<LoginPage />);

    expect(screen.getByRole('heading', { name: '登录 Riccon' })).toBeVisible();
    expect(screen.getByText('登录尚未开放')).toBeVisible();
    expect(screen.getByText(/M02 将接入内置账号与 OIDC/)).toBeVisible();

    const credentialInputs = screen.getAllByPlaceholderText('M02 起可用');
    expect(credentialInputs).toHaveLength(2);
    credentialInputs.forEach((input) => {
      expect(input).toBeDisabled();
    });
    expect(screen.getByRole('button', { name: /登\s*录/ })).toBeDisabled();
  });

  it('提供返回工作空间的链接', () => {
    render(<LoginPage />);

    expect(screen.getByRole('link', { name: '返回工作空间' })).toHaveAttribute('href', '/chat');
  });
});
