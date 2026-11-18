import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { login } from './real-session';

/**
 * 在真实报告页创建任务并进入详情，返回任务 ID。
 *
 * @param page Playwright 页面
 * @param topic 调研主题
 * @returns 详情路径中的任务 ID
 */
async function createResearchTask(page: Page, topic: string): Promise<string> {
  await page.goto('/research/tasks');
  await expect(page.getByRole('heading', { name: '市场调研报告' })).toBeVisible();
  await expect(page.getByText('授权工具加载失败')).toHaveCount(0);

  const toolSelect = page.getByRole('combobox', { name: '数据工具' });
  await expect(toolSelect).toBeEnabled({ timeout: 30_000 });
  await toolSelect.click();
  await page.getByText('market_data · 只读 · 需审批').click();

  await page.getByPlaceholder('例如：2026 年中国企业协同办公市场规模与竞争格局').fill(topic);
  await page.getByRole('button', { name: /创建\s*报告任务/ }).click();
  await expect(page).toHaveURL(/\/research\/(?!tasks$)[^/]+$/, { timeout: 30_000 });
  const match = page.url().match(/\/research\/([^/?#]+)$/);
  if (!match) {
    throw new Error(`cannot parse research task id from ${page.url()}`);
  }
  return match[1];
}

/**
 * 断言检索阶段的来源列表可展示：至少一条、标题与片段非空、片段不是标题或 UUID 回声。
 *
 * @param page Playwright 页面
 * @returns 首条片段文本
 */
async function expectRetrievalSources(page: Page): Promise<string> {
  const sourceList = page.getByRole('list', { name: '检索命中来源' });
  await expect(sourceList).toBeVisible({ timeout: 90_000 });
  const snippetLocator = page.locator('.research-source-snippet').first();
  await expect(snippetLocator).toBeVisible();
  const snippet = ((await snippetLocator.textContent()) ?? '').trim();
  const title = (
    (await sourceList.locator('.research-source-header strong').first().textContent()) ?? ''
  ).trim();
  console.log(`REAL_J2_SNIPPET_LENGTH=${snippet.length} REAL_J2_SNIPPET=${snippet.slice(0, 80)}`);
  expect(snippet.length).toBeGreaterThan(10);
  expect(snippet.length).toBeLessThanOrEqual(200);
  expect(snippet).not.toBe(title);
  expect(snippet).not.toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i);
  return snippet;
}

/**
 * 真实栈 J2 用户旅程（M02-AC-06..10）。
 *
 * 全部操作通过真实 Web 生产构建完成；不直接调用 API 伪造任务或审批。
 * 检索来源必须在审批前出现；审批时间线必须显示审批人、审批时间与决定；
 * 报告产物必须包含引用清单与数据来源。
 */
test.describe('真实栈 J2', () => {
  test('创建→检索来源→审批→产物与引用', async ({ page }) => {
    await login(page);
    await createResearchTask(page, '2026 年中国企业协同办公市场规模与竞争格局');

    await expect(page.getByRole('heading', { name: '任务时间线' })).toBeVisible();
    const snippet = await expectRetrievalSources(page);
    expect(snippet.length).toBeGreaterThan(0);

    // 审批前不得调用工具、不得生成报告。
    await expect(page.getByText('待审批：调用外部工具')).toBeVisible({ timeout: 60_000 });
    await expect(page.getByText('报告已生成')).toHaveCount(0);

    await page.getByRole('button', { name: '批准' }).click();
    await expect(page.getByText('报告已生成').first()).toBeVisible({ timeout: 120_000 });

    // 审批时间线：审批人、审批时间与决定。
    const approvalSection = page.locator('section.research-approval').first();
    await expect(approvalSection.getByText('已批准')).toBeVisible();
    await expect(approvalSection.getByText('审批人', { exact: true })).toBeVisible();
    await expect(approvalSection.getByText('Alpha 员工').first()).toBeVisible();
    await expect(approvalSection.getByText('审批时间', { exact: true })).toBeVisible();

    // 产物：报告正文、引用清单与数据来源。
    await expect(page.getByRole('heading', { name: '报告产物' })).toBeVisible();
    await expect(page.getByRole('heading', { name: '引用清单' })).toBeVisible();
    await expect(page.getByRole('button', { name: '定位检索阶段' }).first()).toBeVisible();
    await expect(page.getByText('MCP 工具 market_data').first()).toBeVisible();
    const download = page.getByRole('button', { name: '下载 Markdown' });
    await expect(download).toBeEnabled();

    // 正常 J2 详情页的可访问性与 375px 基线（REV-M02-001 F-010）。
    const a11y = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze();
    expect(
      a11y.violations.filter((violation) =>
        ['serious', 'critical'].includes(violation.impact ?? ''),
      ),
    ).toEqual([]);
    await page.setViewportSize({ width: 375, height: 812 });
    await page.reload();
    await expect(page.getByRole('heading', { name: '报告产物' })).toBeVisible();
    const metrics = await page.evaluate(() => ({
      clientWidth: document.documentElement.clientWidth,
      scrollWidth: document.documentElement.scrollWidth,
    }));
    expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth);
  });

  test('拒绝审批后任务终止、阶段不再运行、无产物', async ({ page }) => {
    await login(page);
    await createResearchTask(page, '拒绝路径：2026 年企业协同办公市场进入策略');

    await expectRetrievalSources(page);
    await expect(page.getByText('待审批：调用外部工具')).toBeVisible({ timeout: 60_000 });
    await page.getByRole('button', { name: '拒绝' }).click();

    await expect(page.getByText('任务已终止').first()).toBeVisible({ timeout: 60_000 });
    await expect(page.getByText('任务已终止，未生成报告。')).toBeVisible();
    const timeline = page.locator('section[aria-labelledby="research-timeline-title"]');
    await expect(timeline.getByText('进行中')).toHaveCount(0);
    await expect(timeline.getByText('已跳过').first()).toBeVisible();

    const approvalSection = page.locator('section.research-approval').first();
    await expect(approvalSection.getByText('已拒绝')).toBeVisible();
    await expect(approvalSection.getByText('审批人', { exact: true })).toBeVisible();
    await expect(approvalSection.getByText('审批时间', { exact: true })).toBeVisible();
  });
});
