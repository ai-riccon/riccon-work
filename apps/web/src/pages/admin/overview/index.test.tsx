// @vitest-environment jsdom
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import AdminOverviewPage from './index';

/**
 * 管理端总览页组件测试。
 *
 * 断言用户可观察的内容：占位提示、三个指标卡与跳转员工端工作空间的入口。
 */
describe('管理端总览页', () => {
  it('展示骨架提示与占位指标', () => {
    render(<AdminOverviewPage />);

    expect(screen.getAllByText('管理总览').length).toBeGreaterThan(0);
    expect(screen.getByText('骨架阶段提示')).toBeVisible();
    expect(screen.getByText(/租户、模型、知识库与审计能力将在 M02–M07 分模块接入/)).toBeVisible();

    expect(screen.getByText('已接入租户（占位）')).toBeVisible();
    expect(screen.getByText('可用模型（占位）')).toBeVisible();
    expect(screen.getByText('今日事件（占位）')).toBeVisible();
  });

  it('提供跳转员工端工作空间的链接', () => {
    render(<AdminOverviewPage />);

    expect(screen.getByRole('link', { name: '/chat' })).toHaveAttribute('href', '/chat');
  });
});
