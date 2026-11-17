import { Tag } from 'antd';
import type { CSSProperties, ReactNode } from 'react';

/** 状态标签色调；与 antd 预设色同名，但使用显式高对比配色。 */
export type StatusTone =
  'default' | 'processing' | 'warning' | 'success' | 'error' | 'file' | 'knowledge' | 'mcp';

/**
 * 高对比状态标签配色。
 *
 * antd 6 的预设 success 标签为 #52c41a 文字 + #f6ffed 背景，对比度约 2.2:1，
 * 低于 WCAG AA 4.5:1；这里改用同色系深色文字，保持颜色语义且通过 axe。
 */
const TONE_STYLES: Record<StatusTone, CSSProperties> = {
  success: { color: '#237804', background: '#f6ffed', borderColor: '#b7eb8f' },
  processing: { color: '#0958d9', background: '#e6f4ff', borderColor: '#91caff' },
  warning: { color: '#874d00', background: '#fffbe6', borderColor: '#ffe58f' },
  error: { color: '#a8071a', background: '#fff1f0', borderColor: '#ffa39e' },
  default: { color: '#595959', background: '#fafafa', borderColor: '#d9d9d9' },
  file: { color: '#0958d9', background: '#e6f4ff', borderColor: '#91caff' },
  knowledge: { color: '#531dab', background: '#f9f0ff', borderColor: '#d3adf7' },
  mcp: { color: '#874d00', background: '#fffbe6', borderColor: '#ffe58f' },
};

/** 状态标签属性。 */
export interface StatusTagProps {
  tone: StatusTone;
  children: ReactNode;
  /** 完整 ID 等辅助信息，悬停可见。 */
  title?: string;
}

/**
 * J2 统一状态标签。
 *
 * @param props 色调、内容与可选 title
 * @returns 高对比 Tag
 */
export function StatusTag({ tone, children, title }: StatusTagProps) {
  return (
    <Tag style={TONE_STYLES[tone]} title={title}>
      {children}
    </Tag>
  );
}
