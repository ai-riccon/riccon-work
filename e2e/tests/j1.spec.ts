import { expect, test } from './fixtures';
import { expectNoSeriousA11yViolations } from './a11y';
import { login } from './session';

/**
 * J1 关键旅程 E2E（M02-AC-02/03/04/05/16）：
 * 登录 → 新建会话 → 键盘上传 → 解析成功 → 提问 → 首字 → 引用 → 停止 → 重新打开会话。
 *
 * 请求由真实 ApiClient 发往独立 stub API；不调用真实模型、MCP 或后端。
 */
test.describe('J1 通用问答与文件处理', () => {
  test('上传、流式引用、停止与重开完整闭环 @a11y', async ({ page }) => {
    await login(page);
    await page.getByRole('button', { name: '新建会话' }).click();
    await expect(page.getByText('开始 J1 问答')).toBeVisible();

    // 键盘激活上传按钮，再通过原生文件选择器放入 PDF。
    const fileChooserPromise = page.waitForEvent('filechooser');
    await page.getByRole('button', { name: /上传文件/ }).focus();
    await page.keyboard.press('Enter');
    const fileChooser = await fileChooserPromise;
    await fileChooser.setFiles({
      name: '季度报告.pdf',
      mimeType: 'application/pdf',
      buffer: Buffer.from('%PDF-1.4\n% Riccon stub file\n'),
    });

    // 用精确文本定位文件行状态，避免匹配空态提示中的“等待解析成功”文案而提前发送。
    await expect(
      page.locator('.chat-file-item').first().getByText('解析成功', { exact: true }),
    ).toBeVisible({ timeout: 15_000 });

    const composer = page.getByPlaceholder('输入问题，Enter 发送，Shift+Enter 换行');
    await composer.fill('请总结季度报告');
    await composer.press('Enter');

    await expect(page.getByText(/根据你上传的文件/)).toBeVisible({ timeout: 5_000 });
    const citation = page.getByRole('button', { name: /季度报告\.pdf/ });
    await expect(citation).toBeVisible();

    await page.getByRole('button', { name: '停止生成' }).click();
    await expect(page.getByText('已停止生成')).toBeVisible();
    await expect(page.getByText(/根据你上传的文件/)).toBeVisible();

    // 引用在停止后仍可打开，定位信息来自服务端快照。
    await citation.click();
    const drawer = page.getByRole('dialog');
    await expect(drawer.getByText('引用详情')).toBeVisible();
    await expect(drawer.getByText(/第 3 页/)).toBeVisible();
    await expect(drawer.getByText(/这是|演示引用片段/)).toBeVisible();
    await page.keyboard.press('Escape');

    // 刷新后登录态与会话仍在，重新打开可继续查看已停止内容与引用。
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Riccon 工作空间' })).toBeVisible();
    await expect(page.getByText('示例企业')).toBeVisible();
    const conversation = page.getByRole('button', { name: '请总结季度报告' });
    await expect(conversation).toBeVisible();
    await conversation.click();

    await expect(page.getByText('已停止生成')).toBeVisible();
    await expect(page.getByText(/根据你上传的文件/)).toBeVisible();
    await expect(page.getByRole('button', { name: /季度报告\.pdf/ })).toBeVisible();
    await expectNoSeriousA11yViolations(page);
  });

  test('回答完成后按需查看运行详情与模型调用记录 @a11y', async ({ page }) => {
    await login(page);
    await page.getByRole('button', { name: '新建会话' }).click();
    await expect(page.getByText('开始 J1 问答')).toBeVisible();

    const composer = page.getByPlaceholder('输入问题，Enter 发送，Shift+Enter 换行');
    await composer.fill('请介绍你可以怎样协助办公');
    await composer.press('Enter');

    // 无文件问题会得到合成回答；完成前不出现运行详情入口。
    await expect(page.getByText(/当前知识库未检索到相关资料/)).toBeVisible({ timeout: 20_000 });
    const runDetail = page.getByRole('button', { name: '运行详情' });
    await expect(runDetail).toBeVisible();
    await runDetail.click();

    const drawer = page.getByRole('dialog');
    await expect(drawer.getByText('运行详情')).toBeVisible();
    await expect(drawer.getByText('stub-fake-model')).toBeVisible();
    await expect(drawer.getByRole('columnheader', { name: '首字' })).toBeVisible();
    await expect(drawer.getByRole('columnheader', { name: 'Token（入/出）' })).toBeVisible();
    await expect(drawer.getByText('成功').first()).toBeVisible();
    await expectNoSeriousA11yViolations(page);

    await page.keyboard.press('Escape');
    await expect(drawer).toBeHidden();
  });
});
