import AxeBuilder from '@axe-core/playwright';
import type { Page } from '@playwright/test';
import { expect, test } from './fixtures';
import { expectNoSeriousA11yViolations } from './a11y';
import { login } from './session';

/**
 * 转义正则元字符，供“文件名/定位”拼接成精确匹配的 accessible name 正则。
 *
 * @param value 原始文本
 * @returns 可安全嵌入正则的字面量
 */
function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * 判断 serious/critical 违规是否属于已记录的 primary 实心按钮 hover 对比度问题。
 *
 * Builder 残余（F-003，P2 open）：antd primary 实心按钮 hover 背景由 colorPrimaryHover
 * #2e7ae6 派生，白字对比度仅 4.16:1，低于 WCAG AA 4.5:1；影响“开始新会话/打开原文件”等
 * primary 按钮，不属于 F-002（outlined/link/danger）修复面。
 *
 * @param violation axe 违规
 * @returns 已记录问题返回 true
 */
function isKnownPrimaryHoverContrast(violation: {
  id: string;
  impact?: string | null;
  nodes: Array<{ target: Array<string | string[]> }>;
}): boolean {
  return (
    violation.id === 'color-contrast' &&
    violation.nodes.every((node) =>
      node.target.some((target) => JSON.stringify(target).includes('ant-btn-primary')),
    )
  );
}

/**
 * 断言页面不存在除已记录 primary hover 对比度外的 serious/critical。
 *
 * 只放行目标命中 `.ant-btn-primary` 的 color-contrast 节点，其他 serious/critical 仍失败；
 * F-003 修复后删除本 helper 并改回 `expectNoSeriousA11yViolations`。
 *
 * @param page Playwright 页面
 */
async function expectNoNewSeriousA11yViolations(page: Page): Promise<void> {
  const result = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze();
  const blocking = result.violations.filter(
    (violation) => violation.impact === 'serious' || violation.impact === 'critical',
  );
  const unexpected = blocking.filter((violation) => !isKnownPrimaryHoverContrast(violation));
  const detail = unexpected
    .map((violation) => `${violation.id} (${violation.impact}): ${violation.help}`)
    .join('\n');
  expect(unexpected, `发现新的 serious/critical 可访问性问题：\n${detail}`).toHaveLength(0);
}

/**
 * M02-AC-02 逐格式浏览器用例数据。
 *
 * 每种格式使用独立会话，避免同会话多文件时引用顺序互相干扰；
 * `locator` 为 stub 返回并应显示在引用按钮与抽屉中的定位文案。
 * 解析器本身由 `DocumentParserTest`/`FileParseServiceIntegrationTest` 覆盖，
 * 这里只验证 UI 路径：文件行显示“解析成功”、提问回答引用该文件、引用可打开。
 */
const FORMAT_UI_CASES = [
  {
    format: 'XLSX',
    name: '季度数据.xlsx',
    mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    question: '请概括季度数据里的销售情况',
    locator: '工作表 销售数据（B2:D8）',
  },
  {
    format: 'PNG',
    name: '图表.png',
    mimeType: 'image/png',
    question: '请说明图表中的趋势',
    locator: '第 1 段',
  },
  {
    format: 'JPEG',
    name: '照片.jpeg',
    mimeType: 'image/jpeg',
    question: '请描述照片中的场景',
    locator: '第 1 段',
  },
] as const;

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

  // M02-AC-02 逐格式 UI 证据：XLSX/PNG/JPEG 各上传一个，必须显示“解析成功”并可用于提问；
  // 同时覆盖 F-001/F-002 修复后的 axe：流式态、引用 hover、完成态与上传按钮 hover 均无 serious/critical。
  for (const formatCase of FORMAT_UI_CASES) {
    test(`${formatCase.format} 上传后显示解析成功并可提问引用 @a11y`, async ({ page }) => {
      await login(page);
      await page.getByRole('button', { name: '新建会话' }).click();
      await expect(page.getByText('开始 J1 问答')).toBeVisible();

      const fileChooserPromise = page.waitForEvent('filechooser');
      await page.getByRole('button', { name: /上传文件/ }).click();
      const fileChooser = await fileChooserPromise;
      await fileChooser.setFiles({
        name: formatCase.name,
        mimeType: formatCase.mimeType,
        buffer: Buffer.from(`riccon stub ${formatCase.format} content`),
      });

      // 文件行必须同时显示文件名与“解析成功”，证明该格式的解析状态在浏览器可见。
      const fileRow = page.locator('.chat-file-item').filter({ hasText: formatCase.name });
      await expect(fileRow).toHaveCount(1);
      await expect(fileRow.getByText(formatCase.name, { exact: true })).toBeVisible();
      await expect(fileRow.getByText('解析成功', { exact: true })).toBeVisible({ timeout: 15_000 });

      // F-002 回归：上传按钮 hover 曾为 #2e7ae6（白底 4.16:1），修复后 #0958d9（6.16:1）。
      const uploadButton = page.getByRole('button', { name: /上传文件/ });
      await uploadButton.hover();
      await expect(uploadButton).toHaveCSS('color', 'rgb(9, 88, 217)');
      await expectNoSeriousA11yViolations(page);

      // 提问后回答必须明确引用该文件，证明解析结果已进入可提问上下文。
      const composer = page.getByPlaceholder('输入问题，Enter 发送，Shift+Enter 换行');
      await composer.fill(formatCase.question);
      await composer.press('Enter');

      // 引用按钮先于正文增量出现；等首行正文落地后再扫 updating 气泡，避免只扫到 loading 态。
      const citation = page.getByRole('button', {
        name: new RegExp(`${escapeRegExp(formatCase.name)} · ${escapeRegExp(formatCase.locator)}`),
      });
      await expect(citation).toBeVisible();
      await expect(page.getByText(`根据你上传的文件《${formatCase.name}》`)).toBeVisible({
        timeout: 15_000,
      });

      // 流式态：停止生成按钮、updating 气泡与引用默认色必须在扫描范围内。
      // 同时断言 F-001 修复后的颜色 token，证明 axe 扫到的是修复后样式而不是回退或未 hover。
      const stopButton = page.getByRole('button', { name: '停止生成' });
      await expect(stopButton).toBeVisible();
      await expect(stopButton).toHaveCSS('color', 'rgb(207, 19, 34)');
      // 上传按钮 hover 已在上方单独扫描；这里移开指针，确保引用默认色而非 hover 色被扫描。
      await page.mouse.move(0, 0);
      await expect(citation).toHaveCSS('color', 'rgb(9, 88, 217)');
      await expectNoSeriousA11yViolations(page);

      await expect(
        page.getByText(`文件已完成解析，可用于回答与「${formatCase.question}」相关的问题。`),
      ).toBeVisible();

      // 完成态：success 气泡（#f0f0f0）上的默认 link 色与“运行详情”入口；先移开指针清除 hover。
      await expect(page.getByRole('button', { name: '运行详情' })).toBeVisible({ timeout: 15_000 });
      await page.mouse.move(0, 0);
      await expectNoSeriousA11yViolations(page);

      // 引用 hover 态：F-001 原因为 antd colorLinkHover #579df2 在 #f0f0f0 上仅 2.45:1。
      await citation.hover();
      await expect(citation).toHaveCSS('color', 'rgb(0, 62, 179)');
      await expectNoSeriousA11yViolations(page);

      // 引用按钮同时展示文件名与定位；打开抽屉后再次核对定位与片段。
      await citation.click();
      const drawer = page.getByRole('dialog');
      await expect(drawer.getByText('引用详情')).toBeVisible();
      await expect(drawer.getByRole('heading', { name: formatCase.name })).toBeVisible();
      await expect(drawer.getByText(`定位：${formatCase.locator}`)).toBeVisible();
      await expect(
        drawer.getByText(new RegExp(`演示引用片段：${escapeRegExp(formatCase.name)}`)),
      ).toBeVisible();
      await expect(drawer.getByRole('button', { name: '打开原文件' })).toBeVisible();
      await page.keyboard.press('Escape');
      await expect(drawer).toBeHidden();
    });
  }

  // 失败态 danger 文本与重试入口：覆盖文件解析失败、模型失败与对应 hover 态。
  test('失败态 danger 文本与重试入口 @a11y', async ({ page }) => {
    await login(page);

    // 空态 primary 实心按钮 hover：F-003（P2 open，白字 #2e7ae6 4.16:1）；
    // 用窄 allowlist 放行该已知节点，其他 serious/critical 仍失败。
    const startButton = page.getByRole('button', { name: '开始新会话' });
    await expect(startButton).toBeVisible();
    await startButton.hover();
    await expectNoNewSeriousA11yViolations(page);

    await page.getByRole('button', { name: '新建会话' }).click();
    await expect(page.getByText('开始 J1 问答')).toBeVisible();

    // 文件名含“损坏”触发 stub 解析失败，覆盖文件解析失败 danger 文本。
    const fileChooserPromise = page.waitForEvent('filechooser');
    await page.getByRole('button', { name: /上传文件/ }).click();
    const fileChooser = await fileChooserPromise;
    await fileChooser.setFiles({
      name: '损坏报告.pdf',
      mimeType: 'application/pdf',
      buffer: Buffer.from('%PDF-1.4\n% Riccon stub corrupt file\n'),
    });

    const failedFile = page.locator('.chat-file-item').filter({ hasText: '损坏报告.pdf' });
    await expect(failedFile.getByText('解析失败', { exact: true })).toBeVisible({
      timeout: 15_000,
    });
    await expect(failedFile.getByText(/文件内容无法解析/)).toBeVisible();
    const retryParse = failedFile.getByRole('button', { name: '重试解析 损坏报告.pdf' });
    await expect(retryParse).toBeVisible();

    await page.mouse.move(0, 0);
    await expectNoSeriousA11yViolations(page);

    // 重试解析是 link 按钮，hover 修复为 #003eb3。
    await retryParse.hover();
    await expect(retryParse).toHaveCSS('color', 'rgb(0, 62, 179)');
    await expectNoSeriousA11yViolations(page);

    // 问题含“失败”触发 stub 模型失败，覆盖消息失败 danger 文本与重试入口。
    const composer = page.getByPlaceholder('输入问题，Enter 发送，Shift+Enter 换行');
    await composer.fill('请模拟一次失败回答');
    await composer.press('Enter');

    await expect(page.getByText('合成模型调用失败，请重试。')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText('错误码：RICCON-MODEL-500')).toBeVisible();
    const retry = page.getByRole('button', { name: '重试' });
    await expect(retry).toBeVisible();

    await page.mouse.move(0, 0);
    await expectNoSeriousA11yViolations(page);

    // 重试是 default（outlined）按钮，hover 修复为 #0958d9。
    await retry.hover();
    await expect(retry).toHaveCSS('color', 'rgb(9, 88, 217)');
    await expectNoSeriousA11yViolations(page);
  });
});
