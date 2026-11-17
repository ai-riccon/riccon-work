// @vitest-environment jsdom
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import NotFoundPage from './404';

/**
 * 404 兜底页组件测试。
 *
 * 关键行为：用户必须看到可读的错误说明，并能一键回到工作空间，而不是停在空白页。
 */
describe('404 兜底页', () => {
  it('展示错误说明与返回工作空间的入口', () => {
    render(<NotFoundPage />);

    expect(screen.getByText('页面不存在')).toBeVisible();
    expect(screen.getByText('该地址不存在，或对应模块尚未交付。')).toBeVisible();

    const backLink = screen.getByRole('link', { name: '返回工作空间' });
    expect(backLink).toHaveAttribute('href', '/chat');
    expect(screen.getByRole('button', { name: '返回工作空间' })).toBeVisible();
  });
});
