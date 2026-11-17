import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

/**
 * Vitest 全局测试环境准备。
 *
 * jsdom 不实现响应式与滚动相关的浏览器 API，而 antd 6 / Ant Design X 在挂载时
 * 会读取 matchMedia、ResizeObserver、IntersectionObserver 与 scrollTo；这里提供最小替身，
 * 避免测试因环境缺失而失败，同时不改变生产代码行为。
 */
if (typeof window !== 'undefined') {
  if (!window.matchMedia) {
    window.matchMedia = ((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => undefined,
      removeListener: () => undefined,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      dispatchEvent: () => false,
    })) as unknown as typeof window.matchMedia;
  }

  if (!globalThis.ResizeObserver) {
    globalThis.ResizeObserver = class ResizeObserver {
      observe() {
        // 测试中不需要真实尺寸观察，保留空实现避免 antd 组件报错。
      }
      unobserve() {
        // 同上。
      }
      disconnect() {
        // 同上。
      }
    } as unknown as typeof ResizeObserver;
  }

  if (!globalThis.IntersectionObserver) {
    globalThis.IntersectionObserver = class IntersectionObserver {
      observe() {
        // 测试中不需要真实交叉观察。
      }
      unobserve() {
        // 同上。
      }
      disconnect() {
        // 同上。
      }
      takeRecords() {
        return [];
      }
    } as unknown as typeof IntersectionObserver;
  }

  window.scrollTo = () => undefined;
  Element.prototype.scrollTo = () => undefined;
  Element.prototype.scrollIntoView = () => undefined;
}

/**
 * 每个用例后卸载组件，避免 antd 的 portal 与全局 message 状态跨用例污染。
 * Node 环境的纯逻辑测试没有 document，直接跳过 cleanup。
 */
afterEach(() => {
  if (typeof document !== 'undefined') {
    cleanup();
  }
});
