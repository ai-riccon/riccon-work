import { Tag } from 'antd';
import type { TagProps } from 'antd';
import type { CSSProperties } from 'react';

/**
 * 编排 Studio 可访问状态标签。
 *
 * antd 6 filled Tag 默认使用 preset 色阶 7 作文字、色阶 1 作背景：绿色 3.37:1、
 * 橙色 3.34:1、金色 2.76:1，12px 字号低于 WCAG 2.1 AA 的 4.5:1（T16a
 * E2E-M06-09a color-contrast）。这里统一把文字加深到色阶 8/9、背景保持色阶 1
 * 并保留同色系边框；所有组合均 ≥4.5:1，仍保留 antd preset 类名与语义色层级。
 */

/** 编排界面使用的 Tag 语义色。 */
export type WorkflowTagColor =
  'default' | 'blue' | 'green' | 'orange' | 'gold' | 'red' | 'processing';

/** 每种语义色的可访问前景/背景/边框；对比度由组件单测逐项断言。 */
export const WORKFLOW_TAG_STYLES: Record<WorkflowTagColor, CSSProperties> = {
  default: { color: '#595959', backgroundColor: '#f5f5f5', borderColor: '#d9d9d9' },
  blue: { color: '#0958d9', backgroundColor: '#e6f4ff', borderColor: '#91caff' },
  green: { color: '#237804', backgroundColor: '#f6ffed', borderColor: '#b7eb8f' },
  orange: { color: '#ad4e00', backgroundColor: '#fff7e6', borderColor: '#ffd591' },
  gold: { color: '#874d00', backgroundColor: '#fffbe6', borderColor: '#ffe58f' },
  red: { color: '#a8071a', backgroundColor: '#fff1f0', borderColor: '#ffa39e' },
  processing: { color: '#0958d9', backgroundColor: '#e6f4ff', borderColor: '#91caff' },
};

/**
 * 返回语义色对应的可访问内联样式。
 *
 * @param color 语义色；缺省为灰色
 * @returns 前景、背景与边框样式
 */
export function workflowTagStyle(color: WorkflowTagColor = 'default'): CSSProperties {
  return WORKFLOW_TAG_STYLES[color];
}

/** 可访问状态标签属性。 */
export interface WorkflowTagProps extends Omit<TagProps, 'color'> {
  /** 标签语义色；默认灰色 */
  color?: WorkflowTagColor;
}

/**
 * 渲染满足 WCAG AA 对比度的编排状态标签。
 *
 * 保留 antd preset 颜色类名（如 `ant-tag-green`），仅用内联样式覆盖前景/背景/边框，
 * 因此视觉语义与既有页面一致，同时避免 preset 浅色文字在 12px 字号下低于 4.5:1。
 *
 * @param props 语义色、样式覆盖与内容
 * @returns antd Tag
 */
export function WorkflowTag({ color = 'default', style, children, ...rest }: WorkflowTagProps) {
  const tagColor = color === 'default' ? undefined : color;
  return (
    <Tag color={tagColor} {...rest} style={{ ...workflowTagStyle(color), ...style }}>
      {children}
    </Tag>
  );
}
