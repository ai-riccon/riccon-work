import { expect, test } from './fixtures';
import { expectNoSeriousA11yViolations } from './a11y';
import { approveWithKeyboard, createResearchTask, openResearch } from './research';
import { login } from './session';

/** 演示调研主题；与查看/重试场景保持可读且足够长。 */
const TOPIC_SUCCESS = '2026 年中国企业协同办公市场规模与竞争格局';

/**
 * J2 市场调研报告关键旅程（M02-AC-06..11/16）：
 * 创建 → 检索 → 审批前暂停 → 批准 → MCP 取数 → 生成 → 引用/下载/重开。
 *
 * 全部请求由真实 ApiClient 发往独立 stub API（`e2e/stub/server.mjs`），
 * 不使用应用内 mock；stub 只提供合成数据与受控失败路径。
 */
test.describe('J2 市场调研报告', () => {
  test('创建、审批、工具取数与报告产物完整闭环 @a11y', async ({ page }) => {
    const artifactRequests: string[] = [];
    page.on('request', (request) => {
      const url = request.url();
      if (url.includes('/artifacts/') && url.includes('/versions/')) {
        artifactRequests.push(url);
      }
    });
    await login(page);
    await openResearch(page);
    await expectNoSeriousA11yViolations(page);

    await createResearchTask(page, TOPIC_SUCCESS);

    // 检索完成后任务停在审批前，不生成报告。
    await expect(page.getByText('任务正在等待审批', { exact: true })).toBeVisible();
    await expect(
      page.getByText('知识库：2025 中国企业协同办公市场研究', { exact: true }),
    ).toBeVisible();
    await expect(page.getByText('待审批：调用外部工具', { exact: true })).toBeVisible();
    await expect(page.getByText('market_data_lookup').first()).toBeVisible();
    await expect(page.getByText(/market: 2026 年中国企业协同办公市场规模/)).toBeVisible();
    await expect(page.getByText('审批通过并完成生成后，报告会显示在这里。')).toBeVisible();

    await approveWithKeyboard(page);

    // 工具阶段记录与报告产物。
    await expect(page.getByText('MCP 调用 · market_data_lookup（第 1 次尝试）')).toBeVisible();
    await expect(page.getByText('返回 3 条市场规模与增速记录（演示数据）。')).toBeVisible();
    await expect(page.getByText('报告已生成', { exact: true })).toBeVisible({ timeout: 20_000 });
    await expect(
      page.getByRole('heading', { name: `${TOPIC_SUCCESS} · 市场调研报告`, exact: true }),
    ).toBeVisible();
    await expect(page.getByRole('heading', { name: '市场规模与增长' })).toBeVisible();
    await expect(
      page.getByText(
        '报告基于知识库检索与经审批的市场数据工具生成，覆盖市场规模、竞争格局与建议。',
      ),
    ).toBeVisible();
    await expect(page.getByText('引用清单', { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: /定位检索阶段/ }).first()).toBeVisible();
    await expect(page.getByText('[1] 知识库：2025 中国企业协同办公市场研究')).toBeVisible();

    // 引用可定位到任务时间线。
    await page.getByRole('button', { name: /在时间线中查看引用 1/ }).click();
    await expect(page.locator('#timeline-RETRIEVAL')).toHaveAttribute('data-highlighted', 'true');

    // 审批人、时间与决定可见。
    await expect(page.getByText('已批准')).toBeVisible();
    await expect(page.getByText('审批人', { exact: true })).toBeVisible();
    await expect(page.getByText('审批时间', { exact: true })).toBeVisible();
    await expect(page.getByText('演示员工').first()).toBeVisible();

    // 下载 Markdown 报告。
    const downloadPromise = page.waitForEvent('download');
    await page.getByRole('button', { name: /下载 Markdown/ }).click();
    const download = await downloadPromise;
    expect(download.suggestedFilename()).toMatch(/\.md$/);

    // 正文与下载都走 API 同源 content 端点，不再直连对象存储预签名地址。
    expect(
      artifactRequests.some((url) =>
        /\/api\/v1\/artifacts\/[^/]+\/versions\/\d+\/content$/.test(url),
      ),
    ).toBe(true);
    expect(artifactRequests.some((url) => url.includes('/objects/'))).toBe(false);

    await expectNoSeriousA11yViolations(page);

    // 刷新后再次打开任务与产物。
    await page.reload();
    await expect(page.getByRole('heading', { name: TOPIC_SUCCESS, exact: true })).toBeVisible();
    await expect(
      page.getByRole('heading', { name: `${TOPIC_SUCCESS} · 市场调研报告`, exact: true }),
    ).toBeVisible();
    await expect(page.locator('.research-detail-header').getByText('已完成')).toBeVisible();

    await page.getByRole('button', { name: '返回报告任务列表' }).click();
    await expect(page).toHaveURL(/\/research\/tasks$/);
    await page.getByRole('button', { name: TOPIC_SUCCESS }).click();
    await expect(page).toHaveURL(/\/research\/(?!tasks$)[^/]+$/);
    await expect(
      page.getByRole('heading', { name: `${TOPIC_SUCCESS} · 市场调研报告`, exact: true }),
    ).toBeVisible();
  });

  test('拒绝审批后任务终止且不产生报告', async ({ page }) => {
    await login(page);
    await openResearch(page);
    await createResearchTask(page, '审批拒绝演示：行业进入策略');

    await expect(page.getByText('待审批：调用外部工具', { exact: true })).toBeVisible();
    await page.getByLabel('审批备注（可选）').fill('数据范围超出本次调研需要');
    await page.getByRole('button', { name: /拒\s*绝/ }).click();

    await expect(page.getByText('任务已终止', { exact: true })).toBeVisible();
    await expect(page.getByText('已拒绝', { exact: true })).toBeVisible();
    await expect(
      page.getByText('审批被拒绝，任务已终止，未生成报告。', { exact: true }),
    ).toBeVisible();
    await expect(page.getByText('数据范围超出本次调研需要')).toBeVisible();
    await expect(page.getByText('任务已终止，未生成报告。', { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: /下载 Markdown/ })).toHaveCount(0);
    await expect(page.getByRole('heading', { name: /市场调研报告$/ })).toHaveCount(0);
  });

  test('MCP 超时后重试工具阶段即可产出报告', async ({ page }) => {
    await login(page);
    await openResearch(page);
    await createResearchTask(page, '工具失败演示：区域市场数据');

    await expect(page.getByText('待审批：调用外部工具', { exact: true })).toBeVisible();
    await approveWithKeyboard(page);

    await expect(page.getByText('任务失败', { exact: true })).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText(/RICCON-MCP-504/).first()).toBeVisible();
    await expect(page.getByRole('button', { name: '重试工具阶段' })).toBeVisible();

    // 重试会创建新的审批；旧失败调用仍留在时间线。
    const retry = page.getByRole('button', { name: '重试工具阶段' });
    await retry.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText('待审批：调用外部工具', { exact: true })).toBeVisible();
    await expect(page.getByText('MCP 调用 · market_data_lookup（第 1 次尝试）')).toBeVisible();
    await approveWithKeyboard(page);

    await expect(page.getByText('报告已生成', { exact: true })).toBeVisible({ timeout: 20_000 });
    await expect(
      page.getByRole('heading', { name: /工具失败演示：区域市场数据 · 市场调研报告/ }),
    ).toBeVisible();
    // 重试复用同一任务与产物，不出现第二份报告标题。
    await expect(
      page.getByRole('heading', { name: /工具失败演示：区域市场数据 · 市场调研报告/ }),
    ).toHaveCount(1);
  });
});
