import { expect, type Page } from '@playwright/test';

/** 默认演示调研主题；与契约示例一致，便于人工复现。 */
export const RESEARCH_TOPIC = '2026 年中国企业协同办公市场规模与竞争格局';

/**
 * 通过 J1 工作空间头部进入报告任务页。
 *
 * @param page Playwright 页面
 */
export async function openResearch(page: Page): Promise<void> {
  await page.getByRole('button', { name: '市场调研报告' }).click();
  await expect(page).toHaveURL(/\/research\/tasks$/);
  await expect(page.getByRole('heading', { name: '市场调研报告' })).toBeVisible();
}

/**
 * 创建报告任务并等待进入详情页。
 *
 * @param page Playwright 页面
 * @param topic 调研主题
 */
export async function createResearchTask(page: Page, topic: string): Promise<void> {
  await page.getByPlaceholder('例如：2026 年中国企业协同办公市场规模与竞争格局').fill(topic);
  await page.getByRole('combobox', { name: '数据工具' }).click();
  await page.getByText('market_data_lookup · 只读 · 需审批').click();
  await page.getByRole('button', { name: /创建\s*报告任务/ }).click();
  await expect(page).toHaveURL(/\/research\/(?!tasks$)[^/]+$/);
  await expect(page.getByRole('heading', { name: topic, exact: true })).toBeVisible();
}

/**
 * 用键盘批准当前待审批项。
 *
 * @param page Playwright 页面
 */
export async function approveWithKeyboard(page: Page): Promise<void> {
  const approve = page.getByRole('button', { name: /批\s*准/ });
  await expect(approve).toBeEnabled();
  await approve.focus();
  await page.keyboard.press('Enter');
}
