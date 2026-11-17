// @vitest-environment jsdom
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import AdminSettingsPage from './index';

/**
 * 管理端系统设置页组件测试。
 *
 * 该页在认证与 RBAC 落地前只展示能力归属，必须没有可提交入口，避免形成绕过权限的配置面。
 */
describe('管理端系统设置页', () => {
  it('按模块列出配置归属，且不包含可操作表单', () => {
    render(<AdminSettingsPage />);

    expect(screen.getAllByText('系统设置').length).toBeGreaterThan(0);
    expect(screen.getByText(/当前页面不包含可提交表单/)).toBeVisible();

    expect(screen.getByText('模型网关')).toBeVisible();
    expect(screen.getByText('知识库')).toBeVisible();
    expect(screen.getByText('MCP 工具')).toBeVisible();
    expect(screen.getByText('组织与权限')).toBeVisible();

    expect(screen.queryAllByRole('button')).toHaveLength(0);
    expect(screen.queryAllByRole('textbox')).toHaveLength(0);
  });
});
