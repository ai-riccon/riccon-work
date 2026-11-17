import { expect, test } from '@playwright/test';
import path from 'node:path';
import { login, waitForJ1Answer } from './real-session';

const DOCX = path.resolve(__dirname, '../fixtures/market.docx');

/**
 * 真实栈首字预算（M02-AC-03，p95 ≤ 3 秒）。
 *
 * 这是验收断言，不是性能探针：在真实 Web + API + Worker + 假模型链路中测量
 * “提交问题 → 浏览器看到第一段回答正文”的时间。若 Worker/AgentScope 把 SSE 缓冲到
 * 模型流结束，该用例会失败并输出实测值。
 */
test.describe('真实栈首字预算', () => {
  test('提交问题后 3 秒内出现首字', async ({ page }) => {
    await login(page);
    await page.getByRole('button', { name: '新建会话' }).click();
    const chooserPromise = page.waitForEvent('filechooser');
    await page.getByRole('button', { name: /上传文件/ }).click();
    const chooser = await chooserPromise;
    await chooser.setFiles(DOCX);
    await expect(page.getByText('解析成功')).toBeVisible({ timeout: 60_000 });

    const composer = page.getByPlaceholder('输入问题，Enter 发送，Shift+Enter 换行');
    await composer.fill('请总结真实栈文件的增长趋势');
    const startedAt = Date.now();
    await composer.press('Enter');
    const firstTokenMs = await waitForJ1Answer(page);
    console.log(`REAL_FIRST_TOKEN_MS=${firstTokenMs} wall=${Date.now() - startedAt}`);
    expect(firstTokenMs, `首字 ${firstTokenMs}ms 超过 3000ms 预算`).toBeLessThanOrEqual(3000);
  });
});
