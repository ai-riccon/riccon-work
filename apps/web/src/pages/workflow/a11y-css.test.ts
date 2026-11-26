import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

/**
 * 编排 Studio 页面级颜色对比度回归测试。
 *
 * jsdom 不计算 CSS 级联，真实 axe 由 T16a 的 E2E-M06-09a 在浏览器运行；这里读取页面
 * CSS 源码，断言 T16a 暴露的 danger 文本按钮（默认 #ff4d4f 对白底约 3.3:1）以及保存
 * 400 后校验面板焦点环满足 WCAG 2.1 AA，防止样式回退。
 */

/** 参与断言的工作流页面 CSS 原文。 */
const EDITOR_CSS = readFileSync(new URL('./edit/editor.css', import.meta.url), 'utf8');
const RUN_CSS = readFileSync(new URL('./run/run.css', import.meta.url), 'utf8');
const HISTORY_CSS = readFileSync(new URL('./history/history.css', import.meta.url), 'utf8');
const APPROVALS_CSS = readFileSync(new URL('./approvals/approvals.css', import.meta.url), 'utf8');
const RUNTIME_LIMITS_CSS = readFileSync(
  new URL('../../components/workflow/runtime-limits.css', import.meta.url),
  'utf8',
);
const TEMPLATES_CSS = readFileSync(new URL('./templates/templates.css', import.meta.url), 'utf8');

/**
 * 提取简单选择器的声明块。
 *
 * @param css CSS 原文
 * @param selector 选择器（含伪类）
 * @returns 声明块文本；未找到为 `null`
 */
function declarationBlock(css: string, selector: string): string | null {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(`${escaped}\\s*\\{([^}]*)\\}`, 's').exec(css)?.[1] ?? null;
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

describe('编排页面危险按钮颜色对比度', () => {
  it.each([
    ['编辑器删除按钮', EDITOR_CSS, '.workflow-editor-card .ant-btn-dangerous:not(:disabled)'],
    ['运行详情重试按钮', RUN_CSS, '.workflow-run-detail .ant-btn-dangerous:not(:disabled)'],
    [
      '运行历史取消/重试按钮',
      HISTORY_CSS,
      '.workflow-history-card .ant-btn-dangerous:not(:disabled)',
    ],
    ['审批拒绝按钮', APPROVALS_CSS, '.workflow-approvals-page .ant-btn-dangerous:not(:disabled)'],
  ])('%s 文字对白底达到 WCAG AA 4.5:1', (_label, css, selector) => {
    const block = declarationBlock(css, selector);
    expect(block, `必须存在 ${selector} 声明块`).not.toBeNull();
    const color = /color:\s*(#[0-9a-fA-F]{6});/.exec(block ?? '')?.[1] ?? '';
    expect(contrastRatio(color, '#ffffff')).toBeGreaterThanOrEqual(4.5);
  });

  it('保存 400 后校验面板提供可见焦点环', () => {
    const scroll = declarationBlock(EDITOR_CSS, '.workflow-validation-panel');
    expect(scroll).toContain('scroll-margin-top');
    const focus = declarationBlock(EDITOR_CSS, '.workflow-validation-panel:focus-visible');
    expect(focus, '必须存在校验面板焦点样式').not.toBeNull();
    const outline = /outline:\s*2px solid (#[0-9a-fA-F]{6});/.exec(focus ?? '')?.[1] ?? '';
    expect(contrastRatio(outline, '#ffffff')).toBeGreaterThanOrEqual(3);
  });

  it('审批待办元信息标签对白底达到 WCAG AA 4.5:1', () => {
    const block = declarationBlock(APPROVALS_CSS, '.workflow-approvals-meta dt');
    expect(block, '必须存在审批元信息 dt 声明块').not.toBeNull();
    const color = /color:\s*(#[0-9a-fA-F]{6});/.exec(block ?? '')?.[1] ?? '';
    expect(contrastRatio(color, '#ffffff')).toBeGreaterThanOrEqual(4.5);
    expect(APPROVALS_CSS.toLowerCase()).not.toContain('#8c8c8c');
  });

  it('运行限制抽屉 375px 不超出视口，长字段可换行', () => {
    expect(RUNTIME_LIMITS_CSS).toContain('@media (max-width: 575px)');
    const wrapper = declarationBlock(
      RUNTIME_LIMITS_CSS,
      '.workflow-runtime-limits-drawer .ant-drawer-content-wrapper',
    );
    expect(wrapper).toContain('max-width: 100vw');
    const body = declarationBlock(
      RUNTIME_LIMITS_CSS,
      '.workflow-runtime-limits-drawer .ant-drawer-body',
    );
    expect(body).toContain('overflow-x: hidden');
    const content = declarationBlock(RUNTIME_LIMITS_CSS, '.workflow-runtime-limits');
    expect(content).toContain('min-width: 0');
    expect(RUNTIME_LIMITS_CSS).toContain('overflow-wrap: anywhere');
  });

  it('Studio 运行限制入口在 375px 下左对齐换行', () => {
    const block = declarationBlock(TEMPLATES_CSS, '.workflow-studio-help');
    expect(block).not.toBeNull();
    expect(TEMPLATES_CSS).toContain('@media (max-width: 575px)');
  });
});
