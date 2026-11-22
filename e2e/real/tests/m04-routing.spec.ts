import { expect, test, type Page } from '@playwright/test';

import { readSeed } from './real-session';
import {
  M04_FIXTURES,
  createChatAndSend,
  expectNoHorizontalScroll,
  expectNoProviderModelKeyLeak,
  loginRealLocal,
  logout,
  queueFakeModelScenario,
  resetFakeModel,
  setFakeModelScenario,
} from '../../support/m04-routing';

/**
 * M04-B11a 真实栈 E2E：主模型失败降级、首字后中断与审计 7 天预设。
 *
 * 数据由 `scripts/real-stack/run.mjs` 启动的真实 API/Worker/Web 与 seed 准备；
 * 假模型通过 `e2e/support/fake-model` 的控制端点注入确定性失败，不使用生产模型。
 * 运行方式（定向）：
 *
 * ```bash
 * REAL_PLAYWRIGHT_GREP='M04-B11a|E2E-M04-0[23]' \
 *   REAL_EVIDENCE_TASK=TASK-0113 node scripts/real-stack/run.mjs --mode=full
 * ```
 */

/** 平台管理员测试口令；由 `scripts/real-stack/run.mjs` 注入，不是生产凭据。 */
const PLATFORM_ADMIN_PASSWORD = 'test-bootstrap-password-12';

/** 真实栈固定报告正文中的首段标记；假模型每次返回同一文本。 */
const REPORT_HEADING = '市场规模与增长';
/** 真实栈固定报告正文中的末段标记。 */
const REPORT_TAIL = '结论与建议';

/**
 * 读取平台管理员账号。
 *
 * @returns 平台管理员用户名
 */
function platformAdminUsername(): string {
  const seed = readSeed();
  return seed.users.platformAdmin;
}

/**
 * 登录平台管理员。
 *
 * @param page Playwright 页面
 */
async function loginPlatformAdminReal(page: Page): Promise<void> {
  await loginRealLocal(page, 'platform', platformAdminUsername(), PLATFORM_ADMIN_PASSWORD);
}

test.describe('M04-B11a 真实栈降级与审计', () => {
  // 假模型是跨 Playwright 用例的外部进程；每个用例结束恢复 ok，避免污染后续
  // real-stack 阶段（J2 后端闭环）或同文件后续用例。
  test.afterEach(async () => {
    await resetFakeModel();
  });

  test('E2E-M04-02 主模型失败自动降级，员工无感且审计可按 7 天预设查询', async ({ page }) => {
    const seed = readSeed();
    await resetFakeModel();
    // 同 key 重试一次后再换模型的规则：两次 5xx 由主 key 消费，第三次由备用模型成功。
    await queueFakeModelScenario('server_error', 2);

    await loginRealLocal(
      page,
      seed.tenants.alpha,
      seed.users.alphaMember,
      seed.passwords.alphaMember,
      'Alpha 员工',
    );
    await createChatAndSend(page, '请总结 2026 年企业协同办公市场规模');

    // 降级成功：员工拿到完整回答，不出现 provider/model/key 或“已切换”提示。
    await expect(page.getByText(REPORT_HEADING, { exact: false }).first()).toBeVisible({
      timeout: 60_000,
    });
    await expect(page.getByText(REPORT_TAIL, { exact: false }).first()).toBeVisible({
      timeout: 60_000,
    });
    await expect(page.getByText(/已切换|已降级/)).toHaveCount(0);
    await expectNoProviderModelKeyLeak(page);

    // 运行详情按 M02 既有要求保留实际提供首字的模型标识。
    await page.getByRole('button', { name: '运行详情' }).last().click();
    const runDrawer = page.getByRole('dialog').filter({ hasText: '运行详情' });
    await expect(runDrawer.getByText('模型调用')).toBeVisible();
    await expect(runDrawer.getByText('fake-chat-secondary').first()).toBeVisible();
    await page.keyboard.press('Escape');

    // 平台侧审计：换租户管理员账号后按“降级事件（最近 7 天）”预设查询。
    await logout(page);
    await loginRealLocal(
      page,
      seed.tenants.alpha,
      seed.users.alphaAdmin,
      seed.passwords.alphaAdmin,
      'Alpha 管理员',
    );
    await page.goto('/admin/audit');
    await expect(page.getByText('保留策略与只读边界')).toBeVisible();
    await expect(page.getByText(/降级事件预设为最近 7 天/)).toBeVisible();

    await page.getByRole('button', { name: '降级事件（最近 7 天）' }).click();
    await expect(page.getByText('已应用降级事件预设')).toBeVisible();
    await expect(page.getByText(/已应用 降级事件（最近 7 天）/)).toBeVisible();
    await expect(page.getByText('model.failover.triggered').first()).toBeVisible({
      timeout: 15_000,
    });

    // 对象类型可继续筛选为“模型”，并保持命中同一降级事件。
    await page.getByRole('combobox', { name: '对象类型' }).click();
    await page
      .locator('.ant-select-dropdown:visible .ant-select-item-option')
      .filter({ hasText: '模型' })
      .first()
      .click();
    await page.getByRole('button', { name: /查\s*询/ }).click();
    await expect(page.getByText('model.failover.triggered').first()).toBeVisible();

    // 详情只包含模型 ID/原因/尝试号，不含 provider 地址、key 或明文。
    await page
      .getByRole('button', { name: /查看详情：事件 model.failover.triggered/ })
      .first()
      .click();
    const detailDrawer = page.getByRole('dialog').filter({ hasText: '审计事件详情' });
    await expect(detailDrawer.getByText('附加详情')).toBeVisible();
    await expect(detailDrawer.getByText('发生时间')).toBeVisible();
    await expect(detailDrawer.getByText('追踪 ID')).toBeVisible();
    const detailText = await detailDrawer.locator('.audit-details-json').innerText();
    expect(detailText).toContain('"fromModelId"');
    expect(detailText).toContain('"toModelId"');
    expect(detailText).toContain('"reason"');
    expect(detailText).toContain('"attempt"');
    expect(detailText).not.toContain('127.0.0.1');
    expect(detailText).not.toContain('fake-chat-');
    await page.keyboard.press('Escape');
  });

  test('E2E-M04-03 首字后中断保留内容、重试成功且审计记录已输出状态', async ({ page }) => {
    const seed = readSeed();
    await resetFakeModel();
    await setFakeModelScenario('stream_break');

    await loginRealLocal(
      page,
      seed.tenants.alpha,
      seed.users.alphaMember,
      seed.passwords.alphaMember,
      'Alpha 员工',
    );
    await createChatAndSend(page, '请总结 2026 年企业协同办公市场');

    // 首字后断流：已输出内容保留，显示统一中断文案与重试入口，不重放。
    await expect(page.getByText(REPORT_HEADING, { exact: false }).first()).toBeVisible({
      timeout: 60_000,
    });
    await expect(page.getByText('回答已中断，已保留已生成内容，可重试。')).toBeVisible({
      timeout: 60_000,
    });
    await expect(page.getByText('错误码：RICCON-MODEL-502')).toBeVisible();
    const retry = page.getByRole('button', { name: /重\s*试/ });
    await expect(retry).toBeVisible();
    // 只定位助手气泡：用户消息同样使用 .message-text，不能用首个子元素判断回答内容。
    const partialBubble = page
      .locator('.chat-message-list .message-text')
      .filter({ hasText: REPORT_HEADING })
      .first();
    const partial = await partialBubble.innerText();
    expect(partial).toContain('市场规模与增长');
    expect(partial).not.toContain(REPORT_TAIL);

    // 重试后新回答完整，旧内容仍在且没有把新旧回答拼成重复段落。
    await setFakeModelScenario('ok');
    await retry.click();
    await expect(page.getByText(REPORT_TAIL, { exact: false })).toBeVisible({ timeout: 60_000 });
    await expect(
      page.locator('.chat-message-list .message-text').filter({ hasText: REPORT_HEADING }),
    ).toHaveCount(2);
    const fullBubble = page
      .locator('.chat-message-list .message-text')
      .filter({ hasText: REPORT_TAIL })
      .first();
    const secondText = await fullBubble.innerText();
    expect(secondText.match(/市场规模与增长/g) ?? []).toHaveLength(1);
    expect(secondText.match(/结论与建议/g) ?? []).toHaveLength(1);

    // 审计：失败记录包含 emitted=true，证明“首字后失败”语义被保留。
    await logout(page);
    await loginRealLocal(
      page,
      seed.tenants.alpha,
      seed.users.alphaAdmin,
      seed.passwords.alphaAdmin,
      'Alpha 管理员',
    );
    const response = await page.request.get(
      '/api/v1/audit-events?action=model.invocation.failed&page=1&size=50',
    );
    expect(response.status()).toBe(200);
    const pageData = (await response.json()) as {
      items: Array<{ action: string; details?: Record<string, unknown> | null }>;
    };
    expect(
      pageData.items.some(
        (item) =>
          item.action === 'model.invocation.failed' &&
          item.details?.emitted === true &&
          item.details?.errorCode === 'RICCON-MODEL-502',
      ),
      '首字后中断审计必须保留 emitted=true 与稳定错误码',
    ).toBe(true);
  });

  test('M04-B11a 真实栈 key 生命周期与路由审计（AC-07 审计段）', async ({ page }) => {
    await resetFakeModel();
    await loginPlatformAdminReal(page);

    // 路由页：真实 API 保存后审计含 purpose/version/modelIds。
    await page.goto('/admin/model-routing');
    await expect(page.getByText('模型路由', { exact: true }).first()).toBeVisible();
    await expect(page.getByText(M04_FIXTURES.endpoints.demoOnly).first()).toBeVisible();
    const chatCard = page.locator('.model-routing-card').filter({ hasText: '对话链' });
    // 真实栈 seed 的模型展示名当前存在字符集乱码（B8 种子问题，已记录）；按稳定 modelKey 定位，
    // 避免把乱码当成 E2E 断言基准。
    const primaryRow = chatCard
      .locator('.model-routing-item')
      .filter({ hasText: 'fake-chat-primary' });
    await expect(primaryRow.getByText('fake-chat-primary')).toBeVisible();
    await primaryRow.getByRole('button', { name: /^下\s*移/ }).click();
    await chatCard.getByRole('button', { name: '保存对话链' }).click();
    await expect(page.getByText(/已保存对话链（版本 v/, { exact: false })).toBeVisible();

    const routingAudit = await page.request.get(
      '/api/v1/audit-events?action=model.routing.updated&page=1&size=20',
    );
    expect(routingAudit.status()).toBe(200);
    const routingData = (await routingAudit.json()) as {
      total: number;
      items: Array<{ details?: Record<string, unknown> | null }>;
    };
    expect(routingData.total).toBeGreaterThan(0);
    expect(
      routingData.items.some(
        (item) =>
          item.details?.purpose === 'CHAT' &&
          Array.isArray(item.details?.modelIds) &&
          (item.details?.modelIds as unknown[]).length === 3,
      ),
    ).toBe(true);

    // key 页：新增/轮换/停用全部写入审计，响应与审计只保留掩码。
    const label = 'E2E 真实栈 key';
    const firstKey = 'e2e-real-stack-key-0001';
    const rotatedKey = 'e2e-real-stack-key-0002';
    await page.goto('/admin/model-keys');
    await expect(page.getByText('模型 key', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('key 生命周期与在途影响')).toBeVisible();

    await page.getByRole('button', { name: '新增 key' }).click();
    const createDialog = page.getByRole('dialog');
    await createDialog.getByLabel('key 名称').fill(label);
    await createDialog.getByLabel('API key').fill(firstKey);
    await createDialog.getByRole('button', { name: '新增 key' }).click();
    await expect(page.getByText(/已新增 key「E2E 真实栈 key」（\*\*\*\*0001）/)).toBeVisible();

    const createdRow = page
      .locator('.model-key-item')
      .filter({ hasText: label })
      .filter({ hasText: '****0001' });
    await createdRow.getByRole('button', { name: `轮换 key ${label}` }).click();
    const rotateDialog = page.getByRole('dialog');
    await expect(rotateDialog.getByText('轮换影响（保存前请确认）')).toBeVisible();
    await expect(
      rotateDialog.getByText(/进行中的流式请求不会被取消，可能以可重试错误结束，且不会重复输出/),
    ).toBeVisible();
    await rotateDialog.getByLabel('API key').fill(rotatedKey);
    await rotateDialog.getByRole('button', { name: '确认轮换' }).click();
    await expect(page.getByText(/已轮换 key「E2E 真实栈 key」（\*\*\*\*0002）/)).toBeVisible();

    const rotatedRow = page
      .locator('.model-key-item')
      .filter({ hasText: label })
      .filter({ hasText: '****0002' });
    await rotatedRow.getByRole('button', { name: `停用 key ${label}` }).click();
    const disableDialog = page.getByRole('dialog').filter({ hasText: `停用 key「${label}」` });
    await expect(
      disableDialog.getByText('停用后新请求不再使用该 key；在途流式请求继续，不会中断。'),
    ).toBeVisible();
    await disableDialog.getByRole('button', { name: /^停\s*用$/ }).click();
    await expect(page.getByText(/已停用 key「E2E 真实栈 key」/)).toBeVisible();

    const bodyText = await page.locator('body').innerText();
    expect(bodyText).not.toContain(firstKey);
    expect(bodyText).not.toContain(rotatedKey);

    for (const action of [
      'model.credential.created',
      'model.credential.rotated',
      'model.credential.disabled',
    ]) {
      const audit = await page.request.get(`/api/v1/audit-events?action=${action}&page=1&size=50`);
      expect(audit.status(), `${action} 审计查询失败`).toBe(200);
      const responseText = await audit.text();
      expect(responseText).not.toContain(firstKey);
      expect(responseText).not.toContain(rotatedKey);
      const data = JSON.parse(responseText) as {
        total: number;
        items: Array<{ details?: Record<string, unknown> | null }>;
      };
      expect(data.total, `${action} 未写入审计`).toBeGreaterThan(0);
      const masks = data.items
        .map((item) => item.details?.maskedHint)
        .filter((value): value is string => typeof value === 'string');
      expect(masks, `${action} 审计必须包含掩码`).toContain(
        action === 'model.credential.created' ? '****0001' : '****0002',
      );
    }
  });

  test('M04-AC-05 全链失败员工侧稳定 503、错误码与中文文案', async ({ page }) => {
    const seed = readSeed();
    await resetFakeModel();
    await setFakeModelScenario('server_error');
    await loginRealLocal(
      page,
      seed.tenants.alpha,
      seed.users.alphaMember,
      seed.passwords.alphaMember,
      'Alpha 员工',
    );

    // 捕获员工提交消息的 HTTP 响应，直接断言 503 ProblemDetail 与稳定错误码/文案。
    const failedResponsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        /\/api\/v1\/conversations\/[^/]+\/messages$/.test(new URL(response.url()).pathname),
      { timeout: 60_000 },
    );
    await createChatAndSend(page, '请总结 2026 年企业协同办公市场');
    const failedResponse = await failedResponsePromise;
    expect(failedResponse.status()).toBe(503);
    const problem = (await failedResponse.json()) as {
      code?: string;
      detail?: string;
      title?: string;
    };
    expect(problem.code).toBe('RICCON-MODEL-503');
    expect(problem.detail ?? problem.title).toBe('模型服务暂不可用，请稍后重试。');

    await expect(page.getByText('模型服务暂不可用，请稍后重试。')).toBeVisible({
      timeout: 60_000,
    });
    await expect(page.getByText('错误码：RICCON-MODEL-503')).toBeVisible();
    await expect(page.getByRole('button', { name: /重\s*试/ })).toBeVisible();
    await expectNoHorizontalScroll(page);
    await expectNoProviderModelKeyLeak(page);
  });
});
