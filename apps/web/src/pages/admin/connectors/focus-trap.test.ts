// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest';
import { queryFocusableElements, resolveTabTrapTarget } from './focus-trap';

/**
 * 构造焦点陷阱测试容器。
 *
 * 结构模拟条目抽屉的边界：关闭按钮、筛选输入、可聚焦滚动区域、分页“页 1”，
 * 以及被 antd 窄屏媒体查询隐藏的分页大小选择器（隐藏祖先内仍有 tabIndex=0 的 input）。
 *
 * @returns 容器与关键元素
 */
function renderTrapContainer() {
  const container = document.createElement('div');
  container.innerHTML = `
    <button type="button" aria-label="关闭">x</button>
    <input aria-label="同步条目状态筛选" />
    <div tabindex="0" role="region" aria-label="同步条目表格（可横向滚动）"></div>
    <ul class="ant-pagination">
      <li class="ant-pagination-item ant-pagination-item-1">
        <a tabindex="0" aria-label="页 1">1</a>
      </li>
      <li class="ant-pagination-options" style="display: none">
        <input class="ant-select-input" aria-label="页码" />
      </li>
      <li><button type="button" disabled>下一页</button></li>
      <li><span tabindex="-1" aria-label="程序化焦点">不可 Tab</span></li>
    </ul>
  `;
  document.body.append(container);
  const close = container.querySelector<HTMLButtonElement>('button[aria-label="关闭"]')!;
  const tableRegion = container.querySelector<HTMLElement>('[role="region"]')!;
  const pageOne = container.querySelector<HTMLAnchorElement>('[aria-label="页 1"]')!;
  return { container, close, tableRegion, pageOne };
}

describe('抽屉焦点陷阱工具', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('收集可 Tab 元素时排除隐藏祖先、禁用按钮与 tabindex=-1', () => {
    const { container } = renderTrapContainer();
    const elements = queryFocusableElements(container);

    const labels = elements.map((element) => element.getAttribute('aria-label'));
    expect(labels).toEqual(['关闭', '同步条目状态筛选', '同步条目表格（可横向滚动）', '页 1']);
    // 分页大小选择器在窄屏被隐藏，其中的 input 不能算作最后一个可聚焦元素。
    expect(labels).not.toContain('页码');
    expect(labels).not.toContain('程序化焦点');
  });

  it('从真正最后一个可聚焦元素 Tab 时循环到第一个', () => {
    const { container, close, pageOne } = renderTrapContainer();
    const result = resolveTabTrapTarget(container, pageOne, false);
    expect(result).toEqual({ target: close, preventDefault: true });
  });

  it('从第一个可聚焦元素 Shift+Tab 时循环到最后一个', () => {
    const { container, close, pageOne } = renderTrapContainer();
    const result = resolveTabTrapTarget(container, close, true);
    expect(result).toEqual({ target: pageOne, preventDefault: true });
  });

  it('中间元素不拦截，保持浏览器原生 Tab 顺序', () => {
    const { container, tableRegion } = renderTrapContainer();
    expect(resolveTabTrapTarget(container, tableRegion, false)).toEqual({
      target: null,
      preventDefault: false,
    });
    expect(resolveTabTrapTarget(container, tableRegion, true)).toEqual({
      target: null,
      preventDefault: false,
    });
  });

  it('焦点不在容器内时不抢焦点，避免干扰 Select 下拉等浮层', () => {
    const { container } = renderTrapContainer();
    const outside = document.createElement('button');
    document.body.append(outside);
    expect(resolveTabTrapTarget(container, outside, false)).toEqual({
      target: null,
      preventDefault: false,
    });
    expect(resolveTabTrapTarget(container, document.body, true)).toEqual({
      target: null,
      preventDefault: false,
    });
  });

  it('没有可 Tab 元素时阻止默认行为，焦点不逃出容器', () => {
    const container = document.createElement('div');
    const hiddenButton = document.createElement('button');
    hiddenButton.style.display = 'none';
    container.append(hiddenButton);
    document.body.append(container);

    // 焦点在容器内但没有可 Tab 元素时，阻止默认行为，避免焦点逃到页面。
    expect(resolveTabTrapTarget(container, container, false)).toEqual({
      target: null,
      preventDefault: true,
    });
    container.tabIndex = 0;
    container.focus();
    expect(resolveTabTrapTarget(container, container, false)).toEqual({
      target: null,
      preventDefault: true,
    });
  });
});
