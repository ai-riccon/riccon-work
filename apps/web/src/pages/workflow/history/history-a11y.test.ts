import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

/**
 * 运行历史/发布页样式可访问性断言。
 *
 * jsdom 不计算 CSS 级联与颜色对比度，axe 的真实浏览器检查归 T16a；这里直接读取页面 CSS
 * 源码，对关键颜色做 WCAG 2.1 AA 样式断言，防止 `#8c8c8c`（白底 3.36:1）等回归
 * （REV-TASK-0233-001 F-001）。
 */

/** 页面样式原文。 */
const HISTORY_CSS = readFileSync(new URL('./history.css', import.meta.url), 'utf8');

/**
 * 提取声明块。
 *
 * 支持以逗号分隔的简单选择器列表；只用于本项目 CSS 的固定类名断言。
 *
 * @param selectors 选择器数组，例如 ['.workflow-history-meta dt', '.workflow-history-version-meta dt']
 * @returns 声明块文本；未找到为 `null`
 */
function declarationBlock(selectors: string[]): string | null {
  const escaped = selectors
    .map((selector) => selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
    .join('\\s*,\\s*');
  const pattern = new RegExp(`${escaped}\\s*\\{([^}]*)\\}`, 's');
  return pattern.exec(HISTORY_CSS)?.[1] ?? null;
}

/**
 * 解析 #rrggbb 颜色为 sRGB 通道。
 *
 * @param color 十六进制颜色
 * @returns 0–255 通道
 */
function rgb(color: string): [number, number, number] {
  const normalized = color.trim();
  expect(normalized).toMatch(/^#[0-9a-fA-F]{6}$/);
  return [
    Number.parseInt(normalized.slice(1, 3), 16),
    Number.parseInt(normalized.slice(3, 5), 16),
    Number.parseInt(normalized.slice(5, 7), 16),
  ];
}

/**
 * 计算 WCAG 相对亮度。
 *
 * @param color 十六进制颜色
 * @returns 相对亮度（0–1）
 */
function relativeLuminance(color: string): number {
  const [r, g, b] = rgb(color).map((value) => {
    const channel = value / 255;
    return channel <= 0.03928 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/**
 * 计算两色对比度。
 *
 * @param first 颜色一
 * @param second 颜色二
 * @returns 对比度（1–21）
 */
function contrastRatio(first: string, second: string): number {
  const [lighter, darker] = [relativeLuminance(first), relativeLuminance(second)].sort(
    (left, right) => right - left,
  );
  return (lighter + 0.05) / (darker + 0.05);
}

describe('workflow history 样式可访问性', () => {
  it('运行历史/版本元信息标签对白底达到 WCAG AA 4.5:1', () => {
    const block = declarationBlock([
      '.workflow-history-meta dt',
      '.workflow-history-version-meta dt',
    ]);
    expect(block, '必须存在运行历史/版本元信息 dt 声明块').not.toBeNull();
    const color = /color:\s*([^;]+);/.exec(block ?? '')?.[1]?.trim() ?? '';
    expect(color).toBe('#595959');
    expect(contrastRatio(color, '#ffffff')).toBeGreaterThanOrEqual(4.5);
  });

  it('不再使用低于 AA 的 #8c8c8c 元信息颜色', () => {
    expect(HISTORY_CSS.toLowerCase()).not.toContain('#8c8c8c');
  });
});
