import { expect, test, type Page } from '@playwright/test';
import path from 'node:path';
import { login, waitForJ1Answer } from './real-session';

const DOCX = path.resolve(__dirname, '../fixtures/market.docx');

/**
 * 读取最后一条助手气泡的可见正文长度。
 *
 * @param page Playwright 页面
 * @returns 去掉流式光标后的字符数
 */
async function assistantTextLength(page: Page): Promise<number> {
  const text = (await page.locator('.message-text').last().textContent()) ?? '';
  return text.replace(/▍/g, '').trim().length;
}

/**
 * 真实栈首字预算（M02-AC-03，p95 ≤ 3 秒）。
 *
 * 这是验收断言，不是性能探针：在真实 Web + API + Worker + 假模型链路中测量
 * “提交问题 → 浏览器看到第一段回答正文”的时间，并采样正文长度证明增量逐段到达，
 * 而不是在模型流结束后一次性刷出。若 Worker/AgentScope 把 SSE 缓冲到模型流结束，
 * 首字断言会失败；若整段一次性到达，增长断言会失败。
 */
test.describe('真实栈首字预算', () => {
  test('提交问题后 3 秒内出现首字，且正文逐段增长', async ({ page }) => {
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
    const firstLength = await assistantTextLength(page);
    console.log(
      `REAL_FIRST_TOKEN_MS=${firstTokenMs} wall=${Date.now() - startedAt} firstLength=${firstLength}`,
    );
    expect(firstTokenMs, `首字 ${firstTokenMs}ms 超过 3000ms 预算`).toBeLessThanOrEqual(3000);

    // 逐段增长：假模型每 180ms 推送一个增量；连续采样必须看到至少 3 次严格增长。
    const samples: number[] = [firstLength];
    for (let index = 0; index < 8; index += 1) {
      await page.waitForTimeout(250);
      samples.push(await assistantTextLength(page));
    }
    let growthSteps = 0;
    for (let index = 1; index < samples.length; index += 1) {
      if (samples[index] > samples[index - 1]) {
        growthSteps += 1;
      }
    }
    console.log(`REAL_GROWTH_SAMPLES=${JSON.stringify(samples)}`);
    expect(
      growthSteps,
      `正文未逐段增长（采样 ${JSON.stringify(samples)}）；增量可能被缓冲到流结束`,
    ).toBeGreaterThanOrEqual(3);
    expect(samples[samples.length - 1]).toBeGreaterThan(firstLength);
  });
});
