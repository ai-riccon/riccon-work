import { expect, test } from '@playwright/test';
import path from 'node:path';
import { login, waitForJ1Answer } from './real-session';

/** 真实 DOCX fixture；由验证者生成并提交，解析器与浏览器都读取同一文件。 */
const DOCX = path.resolve(__dirname, '../fixtures/market.docx');
const QUESTION = '请总结真实栈文件中的市场规模与竞争格局';

/**
 * 真实栈 J1 旅程：登录 → 新建会话 → 上传真实 DOCX → Worker 解析 → 提问 → 引用 → 重开。
 *
 * 本用例不依赖 stub；所有请求经 Web 生产构建同源代理到真实 API，再经 Worker 调用假模型。
 */
test.describe('真实栈 J1', () => {
  test('上传、解析、提问、引用与重开', async ({ page }) => {
    await login(page);
    await page.getByRole('button', { name: '新建会话' }).click();
    await expect(page.getByText('开始 J1 问答')).toBeVisible();

    const chooserPromise = page.waitForEvent('filechooser');
    await page.getByRole('button', { name: /上传文件/ }).click();
    const chooser = await chooserPromise;
    await chooser.setFiles(DOCX);
    await expect(
      page.locator('.chat-file-item').first().getByText('解析成功', { exact: true }),
    ).toBeVisible({
      timeout: 60_000,
    });

    const composer = page.getByPlaceholder('输入问题，Enter 发送，Shift+Enter 换行');
    await composer.fill(QUESTION);

    // 记录浏览器实际发送的消息体，区分“前端未附带 fileIds”与“后端未返回引用”。
    page.on('request', (request) => {
      if (request.method() === 'POST' && request.url().endsWith('/messages')) {
        console.log(`REAL_J1_REQUEST_BODY=${request.postData()}`);
      }
    });

    await composer.press('Enter');

    await waitForJ1Answer(page);
    const citation = page.getByRole('button', { name: /market\.docx/ }).first();
    await expect(citation).toBeVisible({ timeout: 30_000 });

    // 引用抽屉必须来自服务端快照，且包含真实定位（DOCX 段落）。
    await citation.click();
    const drawer = page.getByRole('dialog');
    await expect(drawer.getByText('引用详情')).toBeVisible();
    await expect(drawer.getByText(/第 1 段/)).toBeVisible();
    await page.keyboard.press('Escape');

    // 刷新后重新打开同一会话，消息与引用仍在。
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Riccon 工作空间' })).toBeVisible();
    const conversation = page.getByRole('button', { name: QUESTION }).first();
    await expect(conversation).toBeVisible({ timeout: 30_000 });
    await conversation.click();
    await expect(page.getByText('市场规模与增长', { exact: false }).first()).toBeVisible({
      timeout: 30_000,
    });
    await expect(page.getByRole('button', { name: /market\.docx/ }).first()).toBeVisible();
  });
});
