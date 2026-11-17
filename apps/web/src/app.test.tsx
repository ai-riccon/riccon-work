// @vitest-environment jsdom
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { rootContainer } from './app';

/**
 * Umi 根容器测试。
 *
 * 验证 rootContainer 会把路由树放进全局 Provider 中渲染，而不是返回空节点；
 * 具体主题与语言包属于配置读取，不在组件测试里重复断言。
 */
describe('Umi 根容器', () => {
  it('在全局 Provider 内渲染路由树', () => {
    render(<>{rootContainer(<span>路由内容</span>)}</>);

    expect(screen.getByText('路由内容')).toBeVisible();
  });
});
