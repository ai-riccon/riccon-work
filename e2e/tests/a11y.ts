import AxeBuilder from '@axe-core/playwright';
import { expect, type Page } from '@playwright/test';

/**
 * 对当前页面执行 axe 扫描，并断言不存在 serious/critical 级别的可访问性问题。
 *
 * 只拦截 serious/critical，与合同 constraints.accessibility 的 WCAG 2.1 AA 基线一致；
 * moderate/minor 问题记录在报告中由后续模块修复，避免骨架期被风格类规则长期阻塞。
 *
 * @param page Playwright 页面对象
 */
export async function expectNoSeriousA11yViolations(page: Page): Promise<void> {
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze();

  const blocking = results.violations.filter(
    (violation) => violation.impact === 'serious' || violation.impact === 'critical',
  );

  const detail = blocking
    .map((violation) => {
      const targets = violation.nodes.map((node) => node.target.join(' ')).join(' | ');
      return `${violation.id} (${violation.impact}): ${violation.help} -> ${targets}`;
    })
    .join('\n');

  expect(blocking, `发现 serious/critical 可访问性问题：\n${detail}`).toHaveLength(0);
}
