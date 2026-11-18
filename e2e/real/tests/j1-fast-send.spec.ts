import { expect, test } from '@playwright/test';
import path from 'node:path';
import { login, waitForJ1Answer } from './real-session';

const DOCX = path.resolve(__dirname, '../fixtures/market.docx');

/**
 * 真实栈 filesRef 快速发送（REV-M02-001 F-006）。
 *
 * 解析成功提示刚出现时立即发送，不得依赖 effect 同步的 ref；请求体必须携带 fileIds，
 * 回答必须出现来自该文件的引用。旧实现需要等待 2–5 秒才能稳定带上 fileIds。
 */
test.describe('真实栈 J1 快速发送', () => {
  test('解析成功后立即发送仍携带 fileIds 并产生引用', async ({ page }) => {
    await login(page);
    await page.getByRole('button', { name: '新建会话' }).click();
    const chooserPromise = page.waitForEvent('filechooser');
    await page.getByRole('button', { name: /上传文件/ }).click();
    const chooser = await chooserPromise;
    await chooser.setFiles(DOCX);
    await expect(page.getByText('解析成功')).toBeVisible({ timeout: 60_000 });

    let requestBody = '';
    page.on('request', (request) => {
      if (request.method() === 'POST' && request.url().endsWith('/messages')) {
        requestBody = request.postData() ?? '';
      }
    });

    const composer = page.getByPlaceholder('输入问题，Enter 发送，Shift+Enter 换行');
    await composer.fill('快速发送验证：文件的增长趋势');
    await composer.press('Enter');
    await waitForJ1Answer(page);

    console.log(`REAL_J1_FAST_REQUEST_BODY=${requestBody}`);
    expect(requestBody, '发送请求体必须携带 fileIds').toContain('"fileIds"');
    const parsed = JSON.parse(requestBody) as { fileIds?: string[] };
    expect(Array.isArray(parsed.fileIds)).toBe(true);
    expect(parsed.fileIds?.length ?? 0).toBeGreaterThanOrEqual(1);

    await expect(page.getByRole('button', { name: /market\.docx/ }).first()).toBeVisible({
      timeout: 30_000,
    });
    await expect(page.getByText('未检索到相关资料', { exact: false })).toHaveCount(0);
  });
});
