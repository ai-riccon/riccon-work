import { expect, test, type APIRequestContext, type Page } from '@playwright/test';

import { expectNoSeriousA11yViolations } from '../../tests/a11y';
import {
  M04_QUOTA_COST_FIXTURES,
  csrfToken,
  loginByApi,
  realStackQueryValue,
  readPrometheus,
  runRealStackSql,
} from '../../support/m04-quota-cost';
import { expectNoHorizontalScroll, loginRealLocal, logout } from '../../support/m04-routing';
import { readSeed, type RealSeed } from './real-session';

/**
 * M04-B11b 真实栈 E2E：配额 429/拒绝审计/指标、成本快照与边界、四页 375px/a11y。
 *
 * 数据由 `scripts/real-stack/run.mjs` 启动的真实 API/Worker/Web 与 seed 准备；
 * 配额执行开关按 B10 演练口径直接改测试栈 `model_gateway_switch`（M04 API 未提供开关写端点），
 * 成本边界通过测试栈模型价格与 `cost_day` 夹具构造，结束后全部恢复。
 * 运行方式（定向）：
 *
 * ```bash
 * REAL_EVIDENCE_TASK=TASK-0114 REAL_PLAYWRIGHT_GREP='M04-B11b' \
 *   node scripts/real-stack/run.mjs --mode=full
 * ```
 */

/** 平台管理员测试口令；由 `scripts/real-stack/run.mjs` 注入，不是生产凭据。 */
const PLATFORM_ADMIN_PASSWORD = 'test-bootstrap-password-12';

/** 真实栈 Web 基地址；与 `scripts/real-stack/run.mjs` 的 REAL_WEB_PORT 默认值一致。 */
const WEB_BASE = process.env.REAL_WEB_BASE ?? 'http://127.0.0.1:8092';

/** 成本轮询总超时；假模型正常时单次调用在数秒内完成。 */
const COST_POLL_TIMEOUT_MS = 60_000;

/**
 * 读取真实栈 seed 中的平台管理员账号。
 *
 * @returns 平台管理员用户名
 */
function platformAdminUsername(): string {
  return readSeed().users.platformAdmin;
}

/**
 * 以平台管理员登录真实栈 UI。
 *
 * @param page Playwright 页面
 */
async function loginPlatformAdminReal(page: Page): Promise<void> {
  await loginRealLocal(page, 'platform', platformAdminUsername(), PLATFORM_ADMIN_PASSWORD);
}

/**
 * 读取工作空间 ID；员工至少有一个可见工作空间。
 *
 * @param request 已登录的请求上下文
 * @returns 工作空间 ID
 */
async function resolveWorkspaceId(request: APIRequestContext): Promise<string> {
  const response = await request.get('/api/v1/workspaces?page=1&size=100');
  expect(response.status(), '工作空间列表必须 200').toBe(200);
  const body = (await response.json()) as { items?: Array<{ id: string }> };
  expect(body.items?.[0]?.id, '员工没有可见工作空间').toBeTruthy();
  return body.items![0]!.id;
}

/**
 * 提交一次 CHAT run；返回 HTTP 状态与原始响应文本。
 *
 * 消息端点以 SSE 返回回答，正常准入会等待整段流结束后返回 201；超限在建立流之前返回 429 JSON。
 *
 * @param request 已登录的请求上下文
 * @param csrf CSRF 令牌
 * @param workspaceId 工作空间 ID
 * @param tag 幂等键标签
 * @returns 状态、响应文本与会话 ID
 */
async function submitChat(
  request: APIRequestContext,
  csrf: string,
  workspaceId: string,
  tag: string,
): Promise<{ status: number; text: string; conversationId: string }> {
  const conversation = await request.post(`/api/v1/workspaces/${workspaceId}/conversations`, {
    headers: { 'X-CSRF-TOKEN': csrf },
    data: { title: `M04-B11b ${tag}` },
  });
  expect(conversation.status(), `创建会话失败：${await conversation.text()}`).toBe(201);
  const conversationBody = (await conversation.json()) as { id: string };
  const token = `${tag}-${Date.now()}`;
  const response = await request.post(`/api/v1/conversations/${conversationBody.id}/messages`, {
    headers: {
      'X-CSRF-TOKEN': csrf,
      'Idempotency-Key': `m04-b11b-${token}`,
    },
    data: {
      content: `M04-B11b 配额与成本演练（${tag}）：请返回固定演示内容。`,
      clientMessageId: `m04-b11b-${token}`,
    },
  });
  return {
    status: response.status(),
    text: await response.text(),
    conversationId: conversationBody.id,
  };
}

/**
 * 轮询本租户成本报告直到满足断言条件。
 *
 * @param request 租户管理员请求上下文
 * @param predicate 终止条件
 * @returns 满足条件的报告
 */
async function waitForCostReport(
  request: APIRequestContext,
  predicate: (report: {
    totals: Record<string, number | string>;
    items: Array<Record<string, unknown>>;
  }) => boolean,
): Promise<{
  totals: Record<string, number | string>;
  items: Array<Record<string, unknown>>;
  currency: string;
  currencyMinorUnit: number;
  disclaimer: string;
}> {
  const deadline = Date.now() + COST_POLL_TIMEOUT_MS;
  let last: Awaited<ReturnType<typeof waitForCostReport>> | null = null;
  while (Date.now() < deadline) {
    const response = await request.get('/api/v1/costs?groupBy=USER&page=1&size=100');
    expect(response.status(), '租户成本报告必须 200').toBe(200);
    last = (await response.json()) as typeof last;
    if (last && predicate(last)) {
      return last;
    }
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  throw new Error(
    `成本报告未在 ${COST_POLL_TIMEOUT_MS}ms 内满足条件：${JSON.stringify(last?.totals)}`,
  );
}

/**
 * 从 Prometheus 文本中读取配额拒绝计数总和。
 *
 * @param text Prometheus 文本
 * @returns 计数总和
 */
function quotaRejectionCount(text: string): number {
  let total = 0;
  for (const line of text.split('\n')) {
    if (!line.startsWith('riccon_quota_rejections_total{')) {
      continue;
    }
    const value = Number(line.trim().split(/\s+/).pop());
    if (!Number.isNaN(value)) {
      total += value;
    }
  }
  return total;
}

/**
 * 把平台管理员恢复租户限额；失败会抛错，避免污染后续用例。
 *
 * @param request 平台管理员请求上下文
 * @param csrf CSRF 令牌
 * @param seed 真实栈 seed
 * @param limits 原限额
 */
async function restoreTenantQuota(
  request: APIRequestContext,
  csrf: string,
  seed: RealSeed,
  limits: { daily: number | null; monthly: number | null },
): Promise<void> {
  const response = await request.put(`/api/v1/admin/tenants/${seed.tenants.alpha}/quotas`, {
    headers: { 'X-CSRF-TOKEN': csrf },
    data: { limits },
  });
  expect(response.status(), `恢复租户限额失败：${await response.text()}`).toBe(200);
}

test.describe('M04-B11b 真实栈配额与成本', () => {
  test('E2E-M04-05 真实栈配额 429、拒绝审计与指标', async ({ page, request }) => {
    const seed = readSeed();

    // 平台侧通过独立 API 上下文准备限额，避免与 UI 角色切换互相影响。
    const platformCsrf = await loginByApi(
      request,
      'platform',
      seed.users.platformAdmin,
      PLATFORM_ADMIN_PASSWORD,
    );
    const quotaList = await request.get('/api/v1/admin/quotas?page=1&size=100');
    expect(quotaList.status(), '平台配额列表必须 200').toBe(200);
    const quotaBody = (await quotaList.json()) as {
      items: Array<{
        tenantId: string;
        windows: Array<{ window: string; limit: number | null; used: number }>;
      }>;
    };
    const alpha = quotaBody.items.find((item) => item.tenantId === seed.tenants.alpha);
    expect(alpha, '平台配额列表必须包含 Alpha 租户').toBeTruthy();
    const daily = alpha!.windows.find((window) => window.window === 'DAILY');
    const monthly = alpha!.windows.find((window) => window.window === 'MONTHLY');
    expect(daily && monthly, '租户配额必须包含 DAILY/MONTHLY 窗口').toBeTruthy();
    const original = { daily: daily!.limit, monthly: monthly!.limit };

    // 剩余恰好 1 次：已用 + 1；同时打开执行开关（测试栈 SQL 夹具，B10 演练口径）。
    const limitResponse = await request.put(`/api/v1/admin/tenants/${seed.tenants.alpha}/quotas`, {
      headers: { 'X-CSRF-TOKEN': platformCsrf },
      data: { limits: { daily: daily!.used + 1, monthly: monthly!.limit } },
    });
    expect(limitResponse.status(), `设置测试限额失败：${await limitResponse.text()}`).toBe(200);
    const limitBody = (await limitResponse.json()) as {
      windows: Array<{ window: string; limit: number }>;
    };
    expect(limitBody.windows.find((window) => window.window === 'DAILY')?.limit).toBe(
      daily!.used + 1,
    );

    const metricsBefore = quotaRejectionCount(await readPrometheus(request));
    runRealStackSql(
      "UPDATE model_gateway_switch SET switch_value='true' WHERE switch_key='quotaEnforcementEnabled';",
    );

    try {
      // 租户管理员只读页：保存后的限额与 +08:00 重置时间可见，且无写入口。
      await loginRealLocal(
        page,
        seed.tenants.alpha,
        seed.users.alphaAdmin,
        seed.passwords.alphaAdmin,
        'Alpha 管理员',
      );
      await page.goto(M04_QUOTA_COST_FIXTURES.routes.quotas);
      await expect(
        page.getByText('只读视图：本租户日/月限额与用户用量；限额修改只由平台管理员执行。'),
      ).toBeVisible();
      await expect(page.getByText('日窗口', { exact: true })).toBeVisible();
      // 限额展示使用千分位；按同一 Intl 规则格式化，避免用未格式化的原始数字匹配。
      const formattedLimit = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 }).format(
        daily!.used + 1,
      );
      await expect(
        page
          .locator('.quotas-window-card')
          .filter({ hasText: '日窗口' })
          .getByText(formattedLimit, { exact: true }),
      ).toBeVisible();
      await expect(page.getByText(/\+\d{2}:\d{2}$/).first()).toBeVisible();
      await expect(page.getByRole('button', { name: /调整限额/ })).toHaveCount(0);
      await expect(page.getByText('只读', { exact: true })).toBeVisible();

      // 员工提交两次：第一次准入并完成，第二次在建模前被配额拒绝。
      await logout(page);
      await loginRealLocal(
        page,
        seed.tenants.alpha,
        seed.users.alphaMember,
        seed.passwords.alphaMember,
        'Alpha 员工',
      );
      const memberCsrf = await csrfToken(page.request);
      const workspaceId = await resolveWorkspaceId(page.request);
      const first = await submitChat(page.request, memberCsrf, workspaceId, 'quota-first');
      expect([200, 201], `首次 run 应准入：${first.text.slice(0, 400)}`).toContain(first.status);

      const second = await submitChat(page.request, memberCsrf, workspaceId, 'quota-second');
      expect(second.status, `第二次 run 应被配额拒绝：${second.text.slice(0, 400)}`).toBe(429);
      const problem = JSON.parse(second.text) as {
        code: string;
        scopeType: string;
        window: string;
        limit: number;
        used: number;
        resetsAt: string;
        detail: string;
      };
      expect(problem.code).toBe('RICCON-QUOTA-429');
      expect(problem.scopeType).toBe('TENANT');
      expect(problem.window).toBe('DAILY');
      expect(problem.limit).toBe(daily!.used + 1);
      expect(problem.used).toBe(daily!.used + 1);
      expect(problem.resetsAt).toMatch(/\+08:00$/);
      expect(problem.detail).toContain('已达日限额');

      // 拒绝审计：租户管理员可查，details 只含配额口径字段。
      await logout(page);
      await loginRealLocal(
        page,
        seed.tenants.alpha,
        seed.users.alphaAdmin,
        seed.passwords.alphaAdmin,
        'Alpha 管理员',
      );
      const auditResponse = await page.request.get(
        '/api/v1/audit-events?action=quota.exceeded&page=1&size=20',
      );
      expect(auditResponse.status(), '拒绝审计查询必须 200').toBe(200);
      const auditBody = (await auditResponse.json()) as {
        total: number;
        items: Array<{ action: string; details?: Record<string, unknown> | null }>;
      };
      expect(auditBody.total, '拒绝必须写入 quota.exceeded 审计').toBeGreaterThan(0);
      const exceeded = auditBody.items.find((item) => item.action === 'quota.exceeded');
      expect(exceeded?.details?.scopeType).toBe('TENANT');
      expect(exceeded?.details?.window).toBe('DAILY');
      expect(exceeded?.details?.limit).toBe(daily!.used + 1);
      expect(String(exceeded?.details?.resetsAt)).toMatch(/\+08:00$/);

      // 指标：拒绝计数必须增长，标签只有 scope/window。
      const metricsAfter = quotaRejectionCount(await readPrometheus(request));
      expect(metricsAfter, 'riccon_quota_rejections_total 必须随拒绝增长').toBeGreaterThan(
        metricsBefore,
      );
      expect(await readPrometheus(request)).toMatch(
        /riccon_quota_rejections_total\{[^}]*scope="TENANT"[^}]*window="DAILY"[^}]*\}\s+[1-9]/,
      );
    } finally {
      runRealStackSql(
        "UPDATE model_gateway_switch SET switch_value='false' WHERE switch_key='quotaEnforcementEnabled';",
      );
      await restoreTenantQuota(request, platformCsrf, seed, original);
    }
  });

  test('E2E-M04-06 真实栈成本快照、未定价与未分桶边界', async ({ page, playwright }) => {
    const seed = readSeed();
    const tenantAdmin = await playwright.request.newContext({ baseURL: WEB_BASE });
    await loginByApi(
      tenantAdmin,
      seed.tenants.alpha,
      seed.users.alphaAdmin,
      seed.passwords.alphaAdmin,
    );
    const member = await playwright.request.newContext({ baseURL: WEB_BASE });
    const memberCsrf = await loginByApi(
      member,
      seed.tenants.alpha,
      seed.users.alphaMember,
      seed.passwords.alphaMember,
    );
    const platform = await playwright.request.newContext({ baseURL: WEB_BASE });
    await loginByApi(platform, 'platform', seed.users.platformAdmin, PLATFORM_ADMIN_PASSWORD);
    const workspaceId = await resolveWorkspaceId(member);

    // ---------- PRICED：首次调用写入价格快照与 6 位金额 ----------
    const priced = await submitChat(member, memberCsrf, workspaceId, 'cost-priced');
    expect([200, 201], `成本用例首次 run 应准入：${priced.text.slice(0, 400)}`).toContain(
      priced.status,
    );
    const report = await waitForCostReport(
      tenantAdmin,
      (current) => Number(current.totals.pricedCalls) >= 1,
    );
    expect(report.currency).toBe('CNY');
    expect(report.currencyMinorUnit).toBe(2);
    expect(report.disclaimer).toBe(M04_QUOTA_COST_FIXTURES.disclaimer);
    expect(String(report.totals.amount)).toMatch(/^\d+\.\d{6}$/);
    const pricedAmount = String(report.totals.amount);

    const invocations = await tenantAdmin.get('/api/v1/costs/invocations?page=1&size=50');
    expect(invocations.status(), '租户调用明细必须 200').toBe(200);
    const invocationBody = (await invocations.json()) as {
      items: Array<{
        pricingStatus: string;
        inputPricePer1M: string | null;
        outputPricePer1M: string | null;
        amount: string | null;
        startedAt: string;
      }>;
    };
    const pricedInvocation = invocationBody.items.find((item) => item.pricingStatus === 'PRICED');
    expect(pricedInvocation, '必须存在 PRICED 调用').toBeTruthy();
    expect(pricedInvocation!.inputPricePer1M).toBe('1.500000');
    expect(pricedInvocation!.outputPricePer1M).toBe('3.000000');
    expect(pricedInvocation!.amount).toMatch(/^\d+\.\d{6}$/);
    expect(pricedInvocation!.startedAt).toMatch(/\+08:00$/);

    // 平台聚合无用户字段、无用户明细端点数据。
    const platformReport = await platform.get('/api/v1/admin/costs?groupBy=TENANT&page=1&size=100');
    expect(platformReport.status(), '平台成本报告必须 200').toBe(200);
    const platformBody = (await platformReport.json()) as {
      items: Array<Record<string, unknown>>;
      totals: Record<string, number | string>;
    };
    expect(platformBody.items.length).toBeGreaterThan(0);
    expect(
      platformBody.items.every(
        (item) => item.userId === undefined && item.userDisplayName === undefined,
      ),
      '平台成本桶不得包含用户字段',
    ).toBe(true);
    expect(String(platformBody.totals.amount)).toMatch(/^\d+\.\d{6}$/);

    // ---------- UI：免责文案、6 位快照与租户明细展开 ----------
    await loginRealLocal(
      page,
      seed.tenants.alpha,
      seed.users.alphaAdmin,
      seed.passwords.alphaAdmin,
      'Alpha 管理员',
    );
    await page.goto(M04_QUOTA_COST_FIXTURES.routes.costs);
    await expect(page.getByText(M04_QUOTA_COST_FIXTURES.disclaimer).first()).toBeVisible();
    // 整页刷新时会话恢复前 groupBy 初值为 DAY（P2，B9b 后续修复）；显式选择租户 AC-11 默认维度。
    await page.getByText('按用户', { exact: true }).click();
    await expect(page.getByText('合计（按用户）')).toBeVisible();
    await page.getByRole('button', { name: '查看明细：Alpha 员工' }).click();
    const panel = page.locator('.costs-invocation-panel');
    await expect(panel.getByText('1.500000').first()).toBeVisible();
    await expect(panel.getByText('3.000000').first()).toBeVisible();
    await expect(panel.getByText('已定价').first()).toBeVisible();

    // ---------- UNPRICED：模型价格置空后新调用单列，不改变金额合计 ----------
    runRealStackSql(
      "UPDATE model SET input_price_per_1m=NULL, output_price_per_1m=NULL WHERE model_key IN ('fake-chat-primary','fake-chat-secondary');",
    );
    let unbucketedInvocationId = '';
    try {
      const unpriced = await submitChat(member, memberCsrf, workspaceId, 'cost-unpriced');
      expect([200, 201], `未定价 run 应准入并完成：${unpriced.text.slice(0, 400)}`).toContain(
        unpriced.status,
      );
      const unpricedReport = await waitForCostReport(
        tenantAdmin,
        (current) => Number(current.totals.unpricedCalls) >= 1,
      );
      expect(String(unpricedReport.totals.amount)).toBe(pricedAmount);

      // UI：未定价调用单列，明细显示“未定价”。
      await page.reload();
      // 刷新后的初始维度取决于会话恢复时机（P2），显式选择 USER 后再断言。
      await page.getByText('按用户', { exact: true }).click();
      await expect(page.getByText('合计（按用户）')).toBeVisible();
      await expect(
        page
          .locator('.ant-descriptions-item')
          .filter({ hasText: '未定价调用（单列，不计入金额）' }),
      ).toContainText('1');
      await page.getByRole('button', { name: '查看明细：Alpha 员工' }).click();
      await expect(
        page.locator('.costs-invocation-panel').getByText('未定价').first(),
      ).toBeVisible();

      // ---------- UNBUCKETED：回滚窗口旧 Worker 的 cost_day=NULL 行单独计数 ----------
      unbucketedInvocationId = realStackQueryValue(
        `SELECT LOWER(HEX(id)) FROM model_invocation WHERE tenant_id='${seed.tenants.alpha}' AND cost_status='UNPRICED' ORDER BY started_at DESC LIMIT 1;`,
      );
      expect(unbucketedInvocationId).toMatch(/^[0-9a-f]{32}$/);
      runRealStackSql(
        `UPDATE model_invocation SET cost_day = NULL WHERE tenant_id='${seed.tenants.alpha}' AND id = UNHEX('${unbucketedInvocationId}');`,
      );
      const unbucketedReport = await waitForCostReport(
        tenantAdmin,
        (current) => Number(current.totals.unbucketedCalls) >= 1,
      );
      expect(String(unbucketedReport.totals.amount)).toBe(pricedAmount);

      // UI 重载：未分桶单列且仍不计金额；未定价行已转为未分桶，金额合计保持不变。
      await page.reload();
      await page.getByText('按用户', { exact: true }).click();
      await expect(page.getByText('合计（按用户）')).toBeVisible();
      await expect(
        page
          .locator('.ant-descriptions-item')
          .filter({ hasText: '未分桶调用（回滚窗口/未回填，不计入金额）' }),
      ).toContainText('1');
      await expect(
        page.locator('.ant-descriptions-item').filter({ hasText: '合计金额' }),
      ).toContainText(`精确值 ${pricedAmount} CNY`);
    } finally {
      runRealStackSql(
        "UPDATE model SET input_price_per_1m='1.500000', output_price_per_1m='3.000000' WHERE model_key='fake-chat-primary';",
      );
      runRealStackSql(
        "UPDATE model SET input_price_per_1m='2.000000', output_price_per_1m='4.000000' WHERE model_key='fake-chat-secondary';",
      );
      if (unbucketedInvocationId) {
        runRealStackSql(
          `UPDATE model_invocation SET cost_day = DATE(CONVERT_TZ(started_at, '+00:00', '+08:00')) WHERE tenant_id='${seed.tenants.alpha}' AND id = UNHEX('${unbucketedInvocationId}');`,
        );
      }
      await member.dispose();
      await tenantAdmin.dispose();
      await platform.dispose();
    }
  });

  test('M04-B11b 真实栈四页 375px 键盘与 axe @a11y', async ({ page }) => {
    const seed = readSeed();
    await loginPlatformAdminReal(page);
    await page.setViewportSize({ width: 375, height: 820 });

    // ---------- 路由页：键盘调整顺序并保存（随后恢复原顺序） ----------
    await page.goto('/admin/model-routing');
    await expect(page.getByText('路由与端点边界')).toBeVisible();
    const chatCard = page.locator('.model-routing-card').filter({ hasText: '对话链' });
    await chatCard
      .getByRole('button', { name: /^下\s*移/ })
      .first()
      .focus();
    await page.keyboard.press('Enter');
    await chatCard.getByRole('button', { name: '保存对话链' }).focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText(/已保存对话链（版本 v/)).toBeVisible();
    await expectNoHorizontalScroll(page);
    await page.mouse.move(0, 0);
    await expectNoSeriousA11yViolations(page);
    await chatCard
      .getByRole('button', { name: /^上\s*移/ })
      .first()
      .focus();
    await page.keyboard.press('Enter');
    await chatCard.getByRole('button', { name: '保存对话链' }).focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText(/已保存对话链（版本 v/)).toBeVisible();

    // ---------- key 页：键盘打开/关闭弹窗 ----------
    await page.goto('/admin/model-keys');
    await expect(page.getByText('key 生命周期与在途影响')).toBeVisible();
    await page.getByRole('button', { name: '新增 key' }).focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('dialog')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).toBeHidden();
    await expectNoHorizontalScroll(page);
    await page.mouse.move(0, 0);
    await expectNoSeriousA11yViolations(page);

    // ---------- 配额页：键盘修改限额（写入相同值）并保存 ----------
    await page.goto(M04_QUOTA_COST_FIXTURES.routes.quotas);
    await expect(
      page.getByText(
        '平台级视图：跨租户查看日/月限额与用量，并整体替换租户限额；不展示租户内用户明细。',
      ),
    ).toBeVisible();
    const quotaRow = page.getByRole('row').filter({ hasText: seed.tenants.alpha });
    await quotaRow.getByRole('button', { name: /调整限额：/ }).focus();
    await page.keyboard.press('Enter');
    const quotaDialog = page.getByRole('dialog');
    await expect(quotaDialog).toBeVisible();
    const dailyInput = quotaDialog.getByLabel('日限额（次/自然日）');
    const dailyValue = await dailyInput.inputValue();
    await dailyInput.focus();
    await page.keyboard.press('ControlOrMeta+A');
    await page.keyboard.type(dailyValue);
    await quotaDialog.getByRole('button', { name: '保存限额' }).focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText(/已保存租户“.*”的配额/)).toBeVisible();
    await expect(quotaDialog).toBeHidden();
    await expectNoHorizontalScroll(page);
    await page.mouse.move(0, 0);
    await expectNoSeriousA11yViolations(page);

    // ---------- 成本页：键盘切换聚合维度 ----------
    await page.goto(M04_QUOTA_COST_FIXTURES.routes.costs);
    // 同上：显式选择平台默认维度，再验证键盘切换。
    await page.getByText('按租户', { exact: true }).click();
    await expect(page.getByText('合计（按租户）')).toBeVisible();
    const modelRadio = page.getByRole('radio', { name: '按模型' });
    await modelRadio.focus();
    await page.keyboard.press('Space');
    await expect(modelRadio).toBeChecked();
    await expect(page.getByText('合计（按模型）')).toBeVisible();
    await expectNoHorizontalScroll(page);
    await page.mouse.move(0, 0);
    await expectNoSeriousA11yViolations(page);

    // ---------- 租户管理员入口：只有配额/成本 ----------
    // 菜单入口在桌面宽度核对：375px 时管理端侧栏收起，链接不进入可访问性树。
    await page.setViewportSize({ width: 1280, height: 900 });
    await logout(page);
    await loginRealLocal(
      page,
      seed.tenants.alpha,
      seed.users.alphaAdmin,
      seed.passwords.alphaAdmin,
      'Alpha 管理员',
    );
    await page.goto('/admin/overview');
    await expect(page.getByRole('link', { name: '配额', exact: true })).toBeVisible();
    await expect(page.getByRole('link', { name: '成本', exact: true })).toBeVisible();
    await expect(page.getByRole('link', { name: '租户配额', exact: true })).toHaveCount(0);
    await expect(page.getByRole('link', { name: '成本看板', exact: true })).toHaveCount(0);
    await expect(page.getByRole('link', { name: '模型路由', exact: true })).toHaveCount(0);
    await expect(page.getByRole('link', { name: '模型 key', exact: true })).toHaveCount(0);

    // 只读页无写入口（375px）。
    await page.setViewportSize({ width: 375, height: 820 });
    await page.goto(M04_QUOTA_COST_FIXTURES.routes.quotas);
    await expect(
      page.getByText('只读视图：本租户日/月限额与用户用量；限额修改只由平台管理员执行。'),
    ).toBeVisible();
    await expect(page.getByRole('button', { name: /调整限额/ })).toHaveCount(0);
    await page.goto(M04_QUOTA_COST_FIXTURES.routes.costs);
    await expect(
      page.getByText('本租户聚合：按用户、模型、日查看估算成本，可展开分组查看调用价格快照。'),
    ).toBeVisible();
    await expect(page.getByRole('button', { name: /保存|调整|新增|删除|编辑/ })).toHaveCount(0);
  });
});
