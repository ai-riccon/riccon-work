import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { login } from './real-session';

/**
 * 真实栈可访问性基线（M02-AC-16）：J1/J2 关键路径 axe 无 serious/critical，375px 无横向滚动。
 *
 * 页面由真实 API 数据驱动；J2 工具加载失败的 warning 也属于真实态，不能从 axe 范围排除。
 */
test.describe('真实栈可访问性', () => {
  test('登录页与 J1 工作空间无 serious/critical，375px 无横向滚动', async ({ page }) => {
    await page.goto('/login');
    const loginResult = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze();
    expect(
      loginResult.violations.filter((violation) =>
        ['serious', 'critical'].includes(violation.impact ?? ''),
      ),
    ).toEqual([]);

    await login(page);
    const chatResult = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze();
    expect(
      chatResult.violations.filter((violation) =>
        ['serious', 'critical'].includes(violation.impact ?? ''),
      ),
    ).toEqual([]);

    await page.setViewportSize({ width: 375, height: 812 });
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Riccon 工作空间' })).toBeVisible();
    const metrics = await page.evaluate(() => ({
      clientWidth: document.documentElement.clientWidth,
      scrollWidth: document.documentElement.scrollWidth,
    }));
    expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth);
  });

  test('J2 报告页无 serious/critical，375px 无横向滚动', async ({ page }) => {
    await login(page);
    await page.goto('/research/tasks');
    await expect(page.getByRole('heading', { name: '市场调研报告' })).toBeVisible();
    const result = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze();
    expect(
      result.violations.filter((violation) =>
        ['serious', 'critical'].includes(violation.impact ?? ''),
      ),
    ).toEqual([]);

    await page.setViewportSize({ width: 375, height: 812 });
    await page.reload();
    await expect(page.getByRole('heading', { name: '市场调研报告' })).toBeVisible();
    const metrics = await page.evaluate(() => ({
      clientWidth: document.documentElement.clientWidth,
      scrollWidth: document.documentElement.scrollWidth,
    }));
    expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth);
  });
});
