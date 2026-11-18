import { expect, test } from '@playwright/test';
import path from 'node:path';
import { login, waitForJ1Answer } from './real-session';

const DOCX = path.resolve(__dirname, '../fixtures/market.docx');

/**
 * 真实栈 J1 运行详情（TASK-0028 / REV-M02-001 F-004）。
 *
 * PRODUCT J1 要求会话运行详情可见模型调用记录（模型标识、首字、耗时与结果状态）。
 * 本用例在真实 Web 生产构建中打开运行详情抽屉，确认数据来自 `GET /api/v1/runs/{runId}`
 * 而不是前端伪造的 message.modelId 快照。
 */
test.describe('真实栈 J1 运行详情', () => {
  test('回答完成后可打开运行详情并看到模型调用记录', async ({ page }) => {
    await login(page);
    await page.getByRole('button', { name: '新建会话' }).click();
    const chooserPromise = page.waitForEvent('filechooser');
    await page.getByRole('button', { name: /上传文件/ }).click();
    const chooser = await chooserPromise;
    await chooser.setFiles(DOCX);
    await expect(page.getByText('解析成功')).toBeVisible({ timeout: 60_000 });

    const composer = page.getByPlaceholder('输入问题，Enter 发送，Shift+Enter 换行');
    await composer.fill('请总结真实栈文件的增长趋势');
    await composer.press('Enter');
    await waitForJ1Answer(page);

    // 流结束后才显示“运行详情”；等待按钮出现即代表终态已落库。
    const detailButton = page.getByRole('button', { name: '运行详情' });
    await expect(detailButton).toBeVisible({ timeout: 30_000 });
    await detailButton.click();

    const drawer = page.getByRole('dialog', { name: '运行详情' });
    await expect(drawer).toBeVisible();
    await expect(drawer.getByText('模型调用')).toBeVisible();
    await expect(drawer.getByText('fake-chat')).toBeVisible();
    await expect(drawer.getByText('首字')).toBeVisible();
    await expect(drawer.getByText('耗时')).toBeVisible();
    await expect(drawer.locator('tbody').getByText('成功').first()).toBeVisible();

    // 首字必须体现真实增量时间，而不是整段流耗时（F-002/F-008 的直接回归点）。
    const firstTokenCell = drawer.locator('tbody tr').first().locator('td').nth(3);
    await expect(firstTokenCell).toBeVisible();
    const firstTokenText = (await firstTokenCell.textContent()) ?? '';
    const firstTokenMs = firstTokenText.includes(' s')
      ? Number.parseFloat(firstTokenText) * 1000
      : Number(firstTokenText.replace(/[^\d.]/g, ''));
    console.log(`REAL_RUN_DETAIL_FIRST_TOKEN_MS=${firstTokenMs}`);
    expect(firstTokenMs).toBeGreaterThanOrEqual(0);
    expect(firstTokenMs).toBeLessThan(3000);

    await page.keyboard.press('Escape');
    await expect(drawer).toBeHidden();
  });
});
