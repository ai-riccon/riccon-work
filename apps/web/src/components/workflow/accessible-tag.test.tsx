// @vitest-environment jsdom
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { withProviders } from '@/test/render';
import {
  WORKFLOW_TAG_STYLES,
  WorkflowTag,
  workflowTagStyle,
  type WorkflowTagColor,
} from './accessible-tag';

/**
 * 编排状态标签可访问性测试。
 *
 * 对应 axe `color-contrast`：antd 6 filled 绿色/橙色/金色 Tag 默认文字低于
 * WCAG 2.1 AA 4.5:1；这里逐色断言组件固化的前景/背景对比度，并验证 preset 类名。
 */

/**
 * 解析 hex/rgb 颜色为 sRGB 通道。
 *
 * @param color 浏览器样式或十六进制颜色
 * @returns 0–255 通道
 */
function channels(color: string): [number, number, number] {
  if (color.startsWith('rgb')) {
    const values = (color.match(/\d+/g) ?? []).slice(0, 3).map(Number);
    expect(values).toHaveLength(3);
    return values as [number, number, number];
  }
  expect(color).toMatch(/^#[0-9a-fA-F]{6}$/);
  return [
    Number.parseInt(color.slice(1, 3), 16),
    Number.parseInt(color.slice(3, 5), 16),
    Number.parseInt(color.slice(5, 7), 16),
  ];
}

/**
 * 计算 WCAG 相对亮度。
 *
 * @param color 前景或背景色
 * @returns 相对亮度（0–1）
 */
function relativeLuminance(color: string): number {
  const [r, g, b] = channels(color).map((value) => {
    const channel = value / 255;
    return channel <= 0.03928 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/**
 * 计算两色对比度。
 *
 * @param foreground 前景色
 * @param background 背景色
 * @returns 对比度（1–21）
 */
function contrastRatio(foreground: string, background: string): number {
  const [lighter, darker] = [relativeLuminance(foreground), relativeLuminance(background)].sort(
    (left, right) => right - left,
  );
  return (lighter + 0.05) / (darker + 0.05);
}

describe('WorkflowTag', () => {
  it.each(Object.keys(WORKFLOW_TAG_STYLES) as WorkflowTagColor[])(
    '%s 前景/背景对比度达到 WCAG AA 4.5:1',
    (color) => {
      const style = workflowTagStyle(color);
      expect(
        contrastRatio(style.color as string, style.backgroundColor as string),
      ).toBeGreaterThanOrEqual(4.5);
    },
  );

  it.each([
    ['green', 'ant-tag-green', '已发布'],
    ['blue', 'ant-tag-blue', '内置模板'],
    ['orange', 'ant-tag-orange', '有未发布修改'],
    ['gold', 'ant-tag-gold', '待审批'],
    ['red', 'ant-tag-red', '失败'],
  ] as const)('%s 标签保留 preset 类名并覆盖高对比样式', (color, className, label) => {
    render(withProviders(<WorkflowTag color={color}>{label}</WorkflowTag>));
    const tag = screen.getByText(label);
    expect(tag).toHaveClass(className);
    expect(contrastRatio(tag.style.color, tag.style.backgroundColor)).toBeGreaterThanOrEqual(4.5);
  });

  it('默认标签使用中性深色文字', () => {
    render(withProviders(<WorkflowTag>未发布</WorkflowTag>));
    const tag = screen.getByText('未发布');
    expect(contrastRatio(tag.style.color, tag.style.backgroundColor)).toBeGreaterThanOrEqual(4.5);
  });

  it('processing 与 blue 一样满足对比度', () => {
    render(withProviders(<WorkflowTag color="processing">运行中</WorkflowTag>));
    const tag = screen.getByText('运行中');
    expect(tag).toHaveClass('ant-tag-processing');
    expect(contrastRatio(tag.style.color, tag.style.backgroundColor)).toBeGreaterThanOrEqual(4.5);
  });
});
