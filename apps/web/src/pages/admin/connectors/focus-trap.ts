/**
 * 抽屉焦点陷阱工具。
 *
 * 只依赖 DOM 与标准 API，不引入第三方焦点库。独立成文件是为了让边界条件
 * （隐藏控件、分页、滚动区域、禁用按钮）可以用组件测试直接覆盖。
 */

/** 可聚焦元素的候选选择器；最终是否可 Tab 到达由 `isTabbable` 判定。 */
const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

/**
 * 判断元素是否真正可通过 Tab 到达。
 *
 * 只匹配选择器不够：antd 在窄屏（≤576px）用媒体查询把分页大小选择器
 * `.ant-pagination-options` 设为 `display:none`，其中的 input 仍有 `tabIndex=0`。
 * 若把它当作“最后一个可聚焦元素”，从真正的最后一项按 Tab 时不会命中陷阱，焦点会
 * 逃到页面（V1b F-004）。因此需要同时检查：
 * - `tabIndex >= 0`（排除只能程序化聚焦的 `tabindex=-1`）；
 * - 自身与祖先没有 hidden/inert/aria-hidden/内联 display:none；
 * - 浏览器支持 `checkVisibility` 时用它识别媒体查询等 CSS 隐藏（jsdom 无此 API，
 *   且无布局，退回属性与内联样式检查）。
 *
 * @param element 候选元素
 * @returns 是否可通过 Tab 到达
 */
export function isTabbable(element: HTMLElement): boolean {
  if (element.tabIndex < 0) {
    return false;
  }
  if (element.hasAttribute('disabled') || element.hasAttribute('hidden')) {
    return false;
  }

  const visibilityAware = element as HTMLElement & {
    checkVisibility?: (options?: { checkVisibilityCSS?: boolean }) => boolean;
  };
  if (typeof visibilityAware.checkVisibility === 'function') {
    if (!visibilityAware.checkVisibility({ checkVisibilityCSS: true })) {
      return false;
    }
  } else if (typeof element.getClientRects === 'function') {
    // jsdom 没有布局，body 的 rect 为空；只有真实布局环境才用 rect 判定隐藏。
    const hasLayout = typeof document !== 'undefined' && document.body.getClientRects().length > 0;
    if (hasLayout && element.getClientRects().length === 0) {
      return false;
    }
  }

  // 逐级检查祖先：hidden 属性、inert 子树、aria-hidden 与内联样式隐藏均不可 Tab。
  let current: HTMLElement | null = element;
  while (current) {
    if (
      current.hasAttribute('hidden') ||
      current.hasAttribute('inert') ||
      current.getAttribute('aria-hidden') === 'true' ||
      current.style?.display === 'none' ||
      current.style?.visibility === 'hidden'
    ) {
      return false;
    }
    current = current.parentElement;
  }
  return true;
}

/**
 * 收集容器内可通过 Tab 到达的元素，按 DOM 顺序返回。
 *
 * @param container 焦点陷阱边界（抽屉面板）
 * @returns 可 Tab 到达的元素
 */
export function queryFocusableElements(container: HTMLElement): HTMLElement[] {
  return Array.from(container.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR)).filter(isTabbable);
}

/** Tab 陷阱处理结果。 */
export interface TabTrapResult {
  /** 需要程序化聚焦的目标；null 表示不改变焦点。 */
  target: HTMLElement | null;
  /** 是否阻止浏览器默认 Tab 行为。 */
  preventDefault: boolean;
}

/**
 * 计算 Tab / Shift+Tab 在容器内的循环目标。
 *
 * 只在焦点位于容器内时接管：焦点在 Select 下拉等挂到 body 的浮层时不抢焦点。
 *
 * @param container 焦点陷阱边界（抽屉面板）
 * @param active 当前活动元素
 * @param shiftKey 是否 Shift+Tab
 * @returns 拦截结果；中间元素返回不拦截，让浏览器按正常顺序移动
 */
export function resolveTabTrapTarget(
  container: HTMLElement,
  active: HTMLElement | null,
  shiftKey: boolean,
): TabTrapResult {
  if (!active || !container.contains(active)) {
    return { target: null, preventDefault: false };
  }
  const focusable = queryFocusableElements(container);
  if (focusable.length === 0) {
    // 没有可聚焦元素时也不能让 Tab 逃出抽屉；保持当前焦点。
    return { target: null, preventDefault: true };
  }
  const first = focusable[0];
  const last = focusable[focusable.length - 1];
  if (shiftKey) {
    if (active === first || active === container) {
      return { target: last, preventDefault: true };
    }
    return { target: null, preventDefault: false };
  }
  if (active === last) {
    return { target: first, preventDefault: true };
  }
  return { target: null, preventDefault: false };
}
