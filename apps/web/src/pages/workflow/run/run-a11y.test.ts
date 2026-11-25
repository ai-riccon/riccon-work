import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

/**
 * 运行详情页面样式可访问性断言。
 *
 * jsdom 不计算 CSS 级联与颜色对比度，axe 的真实浏览器检查归 T16a；这里直接读取页面 CSS
 * 源码，对关键颜色做 WCAG 2.1 AA 样式断言，防止 `rgb(0 0 0 / 45%)`（约 3.36:1）等回归
 * （REV-TASK-0232-001 F-001）。
 */

/** 页面样式原文。 */
const RUN_CSS = readFileSync(new URL('./run.css', import.meta.url), 'utf8');

/**
 * 提取简单类选择器的声明块。
 *
 * @param selector CSS 类选择器，例如 `.workflow-run-node-meta`
 * @returns 声明块文本；未找到为 `null`
 */
function declarationBlock(selector: string): string | null {
  const pattern = new RegExp(`${selector.replace('.', '\\.')}\\s*\\{([^}]*)\\}`);
  return pattern.exec(RUN_CSS)?.[1] ?? null;
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

describe('workflow run 样式可访问性', () => {
  it('节点元信息 13px 文本对白底达到 WCAG AA 4.5:1', () => {
    const block = declarationBlock('.workflow-run-node-meta');
    expect(block, '必须存在 .workflow-run-node-meta 声明块').not.toBeNull();
    const color = /color:\s*([^;]+);/.exec(block ?? '')?.[1]?.trim() ?? '';
    expect(color).toBe('#595959');
    expect(contrastRatio(color, '#ffffff')).toBeGreaterThanOrEqual(4.5);
  });
});
