import type { Page } from '@playwright/test';
import { expect, test } from './fixtures';
import { expectNoSeriousA11yViolations } from './a11y';
import {
  delayRoute,
  expectNoHorizontalScroll,
  loginMember,
  loginPlatformAdmin,
  loginTenantAdmin,
  seedM04,
} from '../support/m04-routing';
import {
  M04_QUOTA_COST_FIXTURES,
  costBucketRow,
  costTotalsItem,
  patchStubQuotaSaveUsage,
  quotaTenantRow,
} from '../support/m04-quota-cost';

/**
 * M04 配额/成本两页合同桩 E2E。
 *
 * 覆盖范围：
 * - E2E-M04-05：平台跨租户配额、租户只读视图、限额保存/明确不限、重置时间（+08:00）、
 *   加载/空/错误/403/成功五态与员工无入口；
 * - E2E-M04-06：平台 TENANT/MODEL/DAY 与租户 USER/MODEL/DAY 聚合、6 位小数与币种最小单位、
 *   未定价/用量未知/币种不一致/未分桶单列、免责文案、租户明细价格快照展开；
 * - E2E-M04-07b：路由/key/配额/成本四页五态、375px 无横向滚动、键盘路径
 *   （路由调整顺序→保存、配额修改限额→保存、成本切换聚合维度）、axe 无 serious/critical、
 *   平台/租户/员工四入口可见性与配额/成本页无写入口（AC-02/AC-13/AC-14）。
 *
 * 数据全部来自 B8 合同桩的 `POST /__stub__/m04-seed`，错误/空态由用例内页面路由注入；
 * 真实栈的 429、拒绝审计、指标与成本边界由 `e2e/real/tests/m04-quota-cost.spec.ts` 覆盖。
 */

/** 配额平台列表的 API 路径。 */
const PLATFORM_QUOTA_API = /\/api\/v1\/admin\/quotas(\?.*)?$/;
/** 配额租户只读 API 路径。 */
const TENANT_QUOTA_API = /\/api\/v1\/quotas$/;
/** 成本平台聚合 API 路径。 */
const PLATFORM_COST_API = /\/api\/v1\/admin\/costs(\?.*)?$/;
/** 成本租户聚合 API 路径。 */
const TENANT_COST_API = /\/api\/v1\/costs(\?.*)?$/;

/**
 * 注入一次 ProblemDetail 错误响应。
 *
 * 只拦截指定路径的 GET；其他请求（如静态资源）继续走真实网络。
 *
 * @param page Playwright 页面
 * @param pattern 请求路径
 * @param status HTTP 状态
 * @param code 稳定错误码
 */
async function injectProblem(
  page: Page,
  pattern: RegExp,
  status: number,
  code: string,
): Promise<void> {
  await page.route(pattern, async (route) => {
    if (route.request().method() !== 'GET') {
      await route.continue();
      return;
    }
    await route.fulfill({
      status,
      contentType: 'application/problem+json; charset=utf-8',
      body: JSON.stringify({
        type: 'about:blank',
        title: '服务暂不可用',
        status,
        detail: 'E2E 注入的 M04 错误态。',
        code,
      }),
    });
  });
}

/**
 * 断言金额单元格同时携带 6 位小数快照（title）与币种最小单位展示。
 *
 * @param page Playwright 页面
 * @param exact 6 位小数金额，例如 `0.012200`
 * @param display 按最小单位展示的金额，例如 `CNY 0.01`
 */
async function expectAmountCell(page: Page, exact: string, display: string): Promise<void> {
  const cell = page.locator(`[title="${exact} CNY"]`).first();
  await expect(cell).toBeVisible();
  await expect(cell).toContainText(display);
}

test.describe('M04 配额页', () => {
  test('E2E-M04-05 平台/租户配额视图、重置时间、限额保存、只读与五态', async ({
    page,
    request,
    consoleGuard,
  }) => {
    // ---------- 平台加载态：延迟只读接口，保留骨架屏 ----------
    await seedM04(request);
    await loginPlatformAdmin(page);
    await delayRoute(page, PLATFORM_QUOTA_API, 1500);
    await page.goto(M04_QUOTA_COST_FIXTURES.routes.quotas);
    await expect(page.getByLabel('正在加载配额')).toBeVisible();
    await expect(
      page.getByText(
        '平台级视图：跨租户查看日/月限额与用量，并整体替换租户限额；不展示租户内用户明细。',
      ),
    ).toBeVisible({
      timeout: 15_000,
    });
    await page.unroute(PLATFORM_QUOTA_API);

    // ---------- 平台成功态：限额/用量/重置时间/来源，且无用户明细 ----------
    const acmeRow = quotaTenantRow(page, M04_QUOTA_COST_FIXTURES.tenants.acmeCode);
    await expect(acmeRow.getByText('已用 120 / 1,000')).toBeVisible();
    await expect(acmeRow.getByText('已用 3,400 / 20,000')).toBeVisible();
    await expect(acmeRow.getByText(M04_QUOTA_COST_FIXTURES.resets.daily)).toBeVisible();
    await expect(acmeRow.getByText(M04_QUOTA_COST_FIXTURES.resets.monthly)).toBeVisible();
    await expect(acmeRow.getByText('来源：显式设置').first()).toBeVisible();
    await expect(
      page.getByText('列表只含租户维度；平台不展示租户内用户明细。共 3 个租户。'),
    ).toBeVisible();
    await expect(page.getByText('王敏')).toHaveCount(0);

    // ---------- 保存租户限额：日限额 1,500，提示审计与生效时机 ----------
    await patchStubQuotaSaveUsage(page);
    await page.getByRole('button', { name: '调整限额：示例企业' }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: '调整租户配额：示例企业' });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByText('整体替换与不限语义')).toBeVisible();
    await dialog.getByLabel('日限额（次/自然日）').fill('1500');
    await dialog.getByRole('button', { name: '保存限额' }).click();
    await expect(
      page.getByText(/已保存租户“示例企业”的配额；下一次新建运行准入时生效，审计记录已写入。/),
    ).toBeVisible();
    await expect(acmeRow.getByText('已用 120 / 1,500')).toBeVisible();

    // ---------- 明确不限：清空日限额后展示“不限/明确不限”，不是禁止调用 ----------
    await page.getByRole('button', { name: '调整限额：示例企业' }).click();
    await dialog.getByLabel('日限额（次/自然日）').fill('');
    await dialog.getByRole('button', { name: '保存限额' }).click();
    await expect(acmeRow.getByText('已用 120 / 不限')).toBeVisible();
    await expect(acmeRow.getByText('来源：明确不限').first()).toBeVisible();
    await expect(acmeRow.getByText('已用 3,400 / 20,000')).toBeVisible();
    await page.unroute(/\/api\/v1\/admin\/tenants\/[^/]+\/quotas$/);

    // ---------- 租户只读成功态：只含本租户、无任何写入口 ----------
    // 保存响应的 used 由用例补丁维持；重新播种恢复完整 seed，避免桩状态缺少 used 影响只读断言。
    await seedM04(request);
    await page.context().clearCookies();
    await loginTenantAdmin(page);
    await page.goto(M04_QUOTA_COST_FIXTURES.routes.quotas);
    await expect(
      page.getByText('只读视图：本租户日/月限额与用户用量；限额修改只由平台管理员执行。'),
    ).toBeVisible();
    await expect(page.getByText('日窗口', { exact: true })).toBeVisible();
    await expect(page.getByText('月窗口', { exact: true })).toBeVisible();
    const dailyCard = page.locator('.quotas-window-card').filter({ hasText: '日窗口' });
    await expect(dailyCard.getByText('1,000', { exact: true })).toBeVisible();
    await expect(dailyCard.getByText('120', { exact: true })).toBeVisible();
    await expect(dailyCard.getByText('剩余 880')).toBeVisible();
    await expect(dailyCard.getByText('显式设置', { exact: true })).toBeVisible();
    await expect(dailyCard.getByText(M04_QUOTA_COST_FIXTURES.resets.daily)).toBeVisible();
    await expect(page.getByText('只读', { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: /调整限额/ })).toHaveCount(0);
    const userRow = quotaTenantRow(page, 'wangmin');
    await expect(userRow.getByText('王敏')).toBeVisible();
    await expect(userRow.getByText('已用 3 / 50')).toBeVisible();
    await expect(page.getByText(M04_QUOTA_COST_FIXTURES.resets.daily).first()).toBeVisible();

    // ---------- 租户服务端 403：固定中文说明与重试入口 ----------
    consoleGuard.allowNetworkError(TENANT_QUOTA_API, 403);
    await injectProblem(page, TENANT_QUOTA_API, 403, 'RICCON-AUTH-403');
    await page.reload();
    await expect(page.getByText('没有访问权限')).toBeVisible();
    await expect(
      page.getByText('没有配额查看权限；租户配额仅向平台管理员、本租户用量仅向租户管理员开放。'),
    ).toBeVisible();
    await expect(page.getByRole('button', { name: /重\s*试/ })).toBeVisible();
    await page.unroute(TENANT_QUOTA_API);

    // ---------- 租户错误态：错误结果与重试，不出现内部细节 ----------
    consoleGuard.allowNetworkError(TENANT_QUOTA_API, 500);
    await injectProblem(page, TENANT_QUOTA_API, 500, 'RICCON-QUOTA-500');
    await page.reload();
    await expect(page.getByText('配额数据加载失败')).toBeVisible();
    await expect(page.getByRole('button', { name: /重\s*试/ })).toBeVisible();
    await page.unroute(TENANT_QUOTA_API);

    // ---------- 租户空态：服务端未返回窗口时的中文引导 ----------
    await page.route(TENANT_QUOTA_API, async (route) => {
      if (route.request().method() !== 'GET') {
        await route.continue();
        return;
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          tenantId: M04_QUOTA_COST_FIXTURES.tenants.acmeCode,
          tenantCode: M04_QUOTA_COST_FIXTURES.tenants.acmeCode,
          tenantName: null,
          windows: [],
        }),
      });
    });
    await page.reload();
    await expect(
      page.getByText('服务端没有返回本租户的日/月窗口；请稍后重试或联系平台管理员检查配额配置。'),
    ).toBeVisible();
    await page.unroute(TENANT_QUOTA_API);

    // ---------- 平台空态：引导先创建租户 ----------
    await page.context().clearCookies();
    await seedM04(request, { empty: true });
    await loginPlatformAdmin(page);
    await page.goto(M04_QUOTA_COST_FIXTURES.routes.quotas);
    await expect(
      page.getByText('平台还没有可展示的租户；请先在“租户管理”创建租户，再回到本页设置限额。'),
    ).toBeVisible();
    await expect(page.getByRole('link', { name: '前往租户管理' })).toBeVisible();
    await expect(page.getByRole('button', { name: '重新加载配额' })).toBeVisible();

    // ---------- 员工 403：无入口且不请求任何配额数据 ----------
    await page.context().clearCookies();
    await seedM04(request);
    await loginMember(page);
    const quotaRequests: string[] = [];
    page.on('request', (outgoing) => {
      if (/\/api\/v1\/(admin\/)?quotas/.test(outgoing.url())) {
        quotaRequests.push(outgoing.url());
      }
    });
    await page.goto(M04_QUOTA_COST_FIXTURES.routes.quotas);
    await expect(page.getByText('没有访问权限')).toBeVisible();
    await expect(
      page.getByText(
        '配额页仅向平台管理员与租户管理员开放；员工没有入口，也不会请求任何配额数据。',
      ),
    ).toBeVisible();
    await expect(page.getByRole('button', { name: /调整限额/ })).toHaveCount(0);
    expect(quotaRequests, '员工访问配额页不得发起配额数据请求').toEqual([]);
  });
});

test.describe('M04 成本页', () => {
  test('E2E-M04-06 平台/租户聚合、金额精度、边界单列、免责文案与明细快照展开', async ({
    page,
    request,
    consoleGuard,
  }) => {
    // ---------- 平台成功态：默认 TENANT 维度 ----------
    await seedM04(request);
    await loginPlatformAdmin(page);
    await page.goto(M04_QUOTA_COST_FIXTURES.routes.costs);
    await expect(
      page.getByText('跨租户聚合：按租户、模型、日查看估算成本；平台不展示用户维度明细。'),
    ).toBeVisible();
    await expect(page.getByText(M04_QUOTA_COST_FIXTURES.disclaimer)).toBeVisible();
    await expect(
      page.getByText(/金额按 6 位小数计算，展示按币种最小单位（当前 CNY 2 位）四舍五入一次/),
    ).toBeVisible();

    const platformAmount = costTotalsItem(page, '合计金额');
    await expect(platformAmount.getByText(M04_QUOTA_COST_FIXTURES.platform.amount)).toBeVisible();
    await expect(
      platformAmount.getByText(`精确值 ${M04_QUOTA_COST_FIXTURES.platform.exactAmount}`),
    ).toBeVisible();
    await expect(costTotalsItem(page, '已定价调用')).toContainText(
      M04_QUOTA_COST_FIXTURES.platform.pricedCalls,
    );
    await expect(costTotalsItem(page, '未定价调用（单列，不计入金额）')).toContainText(
      M04_QUOTA_COST_FIXTURES.platform.unpricedCalls,
    );
    await expect(costTotalsItem(page, '用量未知调用（单列，不计入金额）')).toContainText(
      M04_QUOTA_COST_FIXTURES.platform.usageUnknownCalls,
    );
    await expect(costTotalsItem(page, '币种不一致调用（不计入金额）')).toContainText('0');
    await expect(costTotalsItem(page, '未分桶调用（回滚窗口/未回填，不计入金额）')).toContainText(
      '0',
    );

    // 平台聚合只到租户/模型/日：无用户列、无明细展开、无用户姓名。
    await expect(page.getByRole('columnheader', { name: '租户' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '用户' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /查看明细/ })).toHaveCount(0);
    await expect(costBucketRow(page, M04_QUOTA_COST_FIXTURES.tenants.betaCode)).toBeVisible();
    await expect(page.getByText(M04_QUOTA_COST_FIXTURES.tenants.betaName)).toHaveCount(0);
    await expect(page.getByText('王敏')).toHaveCount(0);
    await expectAmountCell(page, '0.008000', 'CNY 0.01');

    // ---------- 平台切换 MODEL：按模型聚合，未定价模型单列调用量 ----------
    await page.getByText('按模型', { exact: true }).click();
    await expect(page.getByText('合计（按模型）')).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '模型' })).toBeVisible();
    await expect(costBucketRow(page, '假模型-主')).toContainText('CNY 0.01');
    const unpricedModelRow = costBucketRow(page, '假模型-未定价示例');
    await expect(unpricedModelRow).toContainText('CNY 0.00');
    await expect(unpricedModelRow).toContainText('1');

    // ---------- 平台切换 DAY：日桶按 Asia/Shanghai 自然日 ----------
    await page.getByText('按日', { exact: true }).click();
    await expect(page.getByText('合计（按日）')).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '日（Asia/Shanghai）' })).toBeVisible();
    await expect(costBucketRow(page, '2026-11-20')).toContainText('CNY 0.01');
    await expect(costBucketRow(page, '2026-11-19')).toContainText('CNY 0.00');

    // ---------- 币种不一致/未分桶：只单列计数，不改变金额合计 ----------
    await page.route(PLATFORM_COST_API, async (route) => {
      if (route.request().method() !== 'GET') {
        await route.continue();
        return;
      }
      const response = await route.fetch();
      const payload = (await response.json()) as {
        totals: Record<string, unknown>;
      };
      payload.totals = {
        ...payload.totals,
        currencyMismatchCalls: 2,
        unbucketedCalls: 3,
      };
      await route.fulfill({ response, json: payload });
    });
    await page.reload();
    await expect(page.getByText('合计（按日）')).toBeVisible();
    await expect(costTotalsItem(page, '币种不一致调用（不计入金额）')).toContainText('2');
    await expect(costTotalsItem(page, '未分桶调用（回滚窗口/未回填，不计入金额）')).toContainText(
      '3',
    );
    await expect(costTotalsItem(page, '合计金额')).toContainText(
      M04_QUOTA_COST_FIXTURES.platform.amount,
    );
    // 边界计数只进入调用量统计，不参与金额合计。
    await expect(costTotalsItem(page, '已定价调用')).toContainText(
      M04_QUOTA_COST_FIXTURES.platform.pricedCalls,
    );
    await page.unroute(PLATFORM_COST_API);

    // ---------- 租户成功态：USER 维度、只看本租户、6 位快照与 2 位展示 ----------
    await page.context().clearCookies();
    await loginTenantAdmin(page);
    await page.goto(M04_QUOTA_COST_FIXTURES.routes.costs);
    await expect(
      page.getByText('本租户聚合：按用户、模型、日查看估算成本，可展开分组查看调用价格快照。'),
    ).toBeVisible();
    await expect(page.getByText('合计（按用户）')).toBeVisible();
    const tenantAmount = costTotalsItem(page, '合计金额');
    await expect(tenantAmount.getByText(M04_QUOTA_COST_FIXTURES.tenant.amount)).toBeVisible();
    await expect(
      tenantAmount.getByText(`精确值 ${M04_QUOTA_COST_FIXTURES.tenant.exactAmount}`),
    ).toBeVisible();
    await expect(costTotalsItem(page, '未定价调用（单列，不计入金额）')).toContainText('1');
    await expect(costTotalsItem(page, '用量未知调用（单列，不计入金额）')).toContainText('1');
    // 不出现 Beta 租户任何数据。
    await expect(page.getByText(M04_QUOTA_COST_FIXTURES.tenants.betaCode)).toHaveCount(0);
    await expect(page.getByText(M04_QUOTA_COST_FIXTURES.tenants.betaName)).toHaveCount(0);
    await expect(page.getByText('0.008000')).toHaveCount(0);

    // ---------- AC-11 明细展开：价格快照、金额与状态 ----------
    await page.getByRole('button', { name: '查看明细：演示员工' }).click();
    const demoPanel = page.locator('.costs-invocation-panel');
    await expect(
      demoPanel.getByText('明细展示调用时价格快照；金额按 6 位小数计算，此处按币种最小单位展示。'),
    ).toBeVisible();
    await expect(demoPanel.getByText('假模型-主').first()).toBeVisible();
    await expect(demoPanel.getByText('1.500000').first()).toBeVisible();
    await expect(demoPanel.getByText('3.000000').first()).toBeVisible();
    await expect(demoPanel.getByText('已定价').first()).toBeVisible();
    await expect(demoPanel.getByText('用量未知').first()).toBeVisible();
    await expect(demoPanel.getByText('未返回').first()).toBeVisible();
    await expect(demoPanel.locator('[title="0.004200 CNY"]').first()).toBeVisible();
    await expect(demoPanel.getByRole('columnheader', { name: '开始时间（+08:00）' })).toBeVisible();
    await page.getByRole('button', { name: '收起明细：演示员工' }).click();

    // 未定价用户：价格为空、金额“不计入金额”、状态“未定价”。
    await page.getByRole('button', { name: '查看明细：王敏' }).click();
    const memberPanel = page.locator('.costs-invocation-panel');
    await expect(memberPanel.getByText('假模型-未定价示例').first()).toBeVisible();
    await expect(memberPanel.getByText('未定价').first()).toBeVisible();
    await expect(memberPanel.getByText('不计入金额').first()).toBeVisible();
    await expect(memberPanel.getByText('—').first()).toBeVisible();
    await page.getByRole('button', { name: '收起明细：王敏' }).click();

    // 按日维度仍可展开明细；用量未知调用保留“未返回”token 语义。
    await page.getByText('按日', { exact: true }).click();
    await expect(page.getByText('合计（按日）')).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '日（Asia/Shanghai）' })).toBeVisible();
    await expect(costBucketRow(page, '2026-11-19')).toContainText('CNY 0.00');
    await page.getByRole('button', { name: '查看明细：2026-11-19' }).click();
    await expect(
      page.locator('.costs-invocation-panel').getByText('用量未知').first(),
    ).toBeVisible();

    // ---------- 租户空态 ----------
    await seedM04(request, { empty: true });
    await page.goto(M04_QUOTA_COST_FIXTURES.routes.costs);
    await expect(page.getByText(/所选时间范围内没有调用记录。可扩大时间范围/)).toBeVisible();
    await expect(page.getByRole('button', { name: '重新查询' })).toBeVisible();

    // ---------- 租户错误态 ----------
    await seedM04(request);
    consoleGuard.allowNetworkError(TENANT_COST_API, 500);
    await injectProblem(page, TENANT_COST_API, 500, 'RICCON-COST-500');
    await page.reload();
    await expect(page.getByText('成本报告加载失败')).toBeVisible();
    await expect(page.getByRole('button', { name: /重\s*试/ })).toBeVisible();
    await page.unroute(TENANT_COST_API);

    // ---------- 租户服务端 403：固定可见性说明 ----------
    consoleGuard.allowNetworkError(TENANT_COST_API, 403);
    await injectProblem(page, TENANT_COST_API, 403, 'RICCON-AUTH-403');
    await page.reload();
    await expect(page.getByText('没有访问权限')).toBeVisible();
    await expect(
      page.getByText('没有成本查看权限；成本看板仅向平台管理员与租户管理员开放，员工无入口。'),
    ).toBeVisible();
    await page.unroute(TENANT_COST_API);

    // ---------- 员工 403：无入口且不请求任何成本数据 ----------
    await page.context().clearCookies();
    await loginMember(page);
    const costRequests: string[] = [];
    page.on('request', (outgoing) => {
      if (/\/api\/v1\/(admin\/)?costs/.test(outgoing.url())) {
        costRequests.push(outgoing.url());
      }
    });
    await page.goto(M04_QUOTA_COST_FIXTURES.routes.costs);
    await expect(page.getByText('没有访问权限')).toBeVisible();
    await expect(
      page.getByText(
        '成本看板仅向平台管理员与租户管理员开放；员工没有入口，也不会请求任何金额数据。',
      ),
    ).toBeVisible();
    expect(costRequests, '员工访问成本页不得发起成本数据请求').toEqual([]);
  });
});

test.describe('M04 四页可访问性与入口', () => {
  /**
   * 断言页面在 375px 下无横向滚动，并通过 axe serious/critical 检查。
   *
   * @param page Playwright 页面
   */
  async function expectPageA11y(page: Page): Promise<void> {
    await expectNoHorizontalScroll(page);
    await page.mouse.move(0, 0);
    await expectNoSeriousA11yViolations(page);
  }

  /**
   * 断言当前页出现错误结果与重试入口。
   *
   * @param page Playwright 页面
   * @param title 错误标题
   */
  async function expectPageError(page: Page, title: string): Promise<void> {
    await expect(page.getByText(title, { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: /重\s*试/ })).toBeVisible();
    await expectNoHorizontalScroll(page);
  }

  /**
   * 定位路由页单条链卡片。
   *
   * @param page Playwright 页面
   * @param purposeLabel 链标题
   * @returns 链卡片 locator
   */
  function chainCard(page: Page, purposeLabel: string) {
    return page.locator('.model-routing-card').filter({ hasText: purposeLabel });
  }

  test('E2E-M04-07b 四页五态、375px、键盘路径、axe 与按角色入口 @a11y', async ({
    page,
    request,
    consoleGuard,
  }) => {
    // ---------- 平台四入口：模型路由 → 模型 key → 租户配额 → 成本看板 ----------
    await seedM04(request);
    await loginPlatformAdmin(page);
    await page.goto('/admin/overview');
    for (const label of ['模型路由', '模型 key', '租户配额', '成本看板']) {
      await expect(page.getByRole('link', { name: label, exact: true })).toBeVisible();
    }

    await page.setViewportSize({ width: 375, height: 820 });

    // ---------- 路由页：加载态 ----------
    await delayRoute(page, /\/api\/v1\/admin\/model-routing$/, 1500);
    await page.goto('/admin/model-routing');
    await expect(page.getByLabel('正在加载模型路由')).toBeVisible();
    await expect(page.getByText('路由与端点边界')).toBeVisible({ timeout: 15_000 });
    await page.unroute(/\/api\/v1\/admin\/model-routing$/);

    // ---------- 路由页：成功态（键盘调整顺序 → 保存，375px，axe） ----------
    const chatCard = chainCard(page, '对话链');
    const moveDown = chatCard.getByRole('button', { name: '下移 假模型-主（对话链）' });
    await moveDown.focus();
    await expect(moveDown).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(chatCard.getByText('未保存')).toBeVisible();
    const saveChain = chatCard.getByRole('button', { name: '保存对话链' });
    await saveChain.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText(/已保存对话链（版本 v5）/)).toBeVisible();
    await expectPageA11y(page);

    // ---------- key 页：加载态/成功态（键盘打开与关闭弹窗，axe） ----------
    await delayRoute(page, /\/api\/v1\/admin\/model-providers(\?.*)?$/, 1500);
    await page.goto('/admin/model-keys');
    await expect(page.getByLabel('正在加载模型端点')).toBeVisible();
    await expect(page.getByText('key 生命周期与在途影响')).toBeVisible({ timeout: 15_000 });
    await page.unroute(/\/api\/v1\/admin\/model-providers(\?.*)?$/);
    const addKey = page.getByRole('button', { name: '新增 key' });
    await addKey.focus();
    await page.keyboard.press('Enter');
    const keyDialog = page.getByRole('dialog');
    await expect(keyDialog).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(keyDialog).toBeHidden();
    // 密钥列只显示掩码，不出现合同桩 seed 明文。
    await expect(page.getByText('****0001').first()).toBeVisible();
    expect(await page.locator('body').innerText()).not.toContain('stub-primary-key-0001');
    await expectPageA11y(page);

    // ---------- 配额页：加载态/成功态（键盘修改限额 → 保存，axe） ----------
    await delayRoute(page, PLATFORM_QUOTA_API, 1500);
    await page.goto(M04_QUOTA_COST_FIXTURES.routes.quotas);
    await expect(page.getByLabel('正在加载配额')).toBeVisible();
    await expect(
      page.getByText(
        '平台级视图：跨租户查看日/月限额与用量，并整体替换租户限额；不展示租户内用户明细。',
      ),
    ).toBeVisible({ timeout: 15_000 });
    await page.unroute(PLATFORM_QUOTA_API);
    const adjust = page.getByRole('button', { name: '调整限额：示例企业' });
    await patchStubQuotaSaveUsage(page);
    await adjust.focus();
    await expect(adjust).toBeFocused();
    await page.keyboard.press('Enter');
    const quotaDialog = page.getByRole('dialog').filter({ hasText: '调整租户配额：示例企业' });
    await expect(quotaDialog).toBeVisible();
    const dailyInput = quotaDialog.getByLabel('日限额（次/自然日）');
    await dailyInput.focus();
    await page.keyboard.press('ControlOrMeta+A');
    await page.keyboard.type('900');
    const saveQuota = quotaDialog.getByRole('button', { name: '保存限额' });
    await saveQuota.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText(/已保存租户“示例企业”的配额/)).toBeVisible();
    await expect(quotaTenantRow(page, 'tenant-acme').getByText('已用 120 / 900')).toBeVisible();
    await expect(quotaDialog).toBeHidden();
    await expectPageA11y(page);

    // ---------- 成本页：加载态/成功态（键盘切换聚合维度，axe） ----------
    await delayRoute(page, PLATFORM_COST_API, 1500);
    await page.goto(M04_QUOTA_COST_FIXTURES.routes.costs);
    await expect(page.getByLabel('正在加载成本报告')).toBeVisible();
    await expect(page.getByText('合计（按租户）')).toBeVisible({ timeout: 15_000 });
    await page.unroute(PLATFORM_COST_API);
    const modelRadio = page.getByRole('radio', { name: '按模型' });
    await modelRadio.focus();
    await expect(modelRadio).toBeFocused();
    await page.keyboard.press('Space');
    await expect(modelRadio).toBeChecked();
    await expect(page.getByText('合计（按模型）')).toBeVisible();
    await expectPageA11y(page);

    // ---------- 四页空态：中文引导与下一步 ----------
    await seedM04(request, { empty: true });
    await page.goto('/admin/model-routing');
    await expect(
      page.getByText(/模型目录为空，无法配置路由。请先由部署管理员在端点允许列表和部署种子中配置/),
    ).toBeVisible();
    await expectNoHorizontalScroll(page);
    await page.goto('/admin/model-keys');
    await expect(
      page.getByText(/部署尚未配置任何模型端点。请由部署管理员在端点允许列表与部署种子中配置/),
    ).toBeVisible();
    await expectNoHorizontalScroll(page);
    await page.goto(M04_QUOTA_COST_FIXTURES.routes.quotas);
    await expect(page.getByText(/平台还没有可展示的租户；请先在“租户管理”创建租户/)).toBeVisible();
    await expectNoHorizontalScroll(page);
    await page.goto(M04_QUOTA_COST_FIXTURES.routes.costs);
    await expect(page.getByText(/所选时间范围内没有调用记录。可扩大时间范围/)).toBeVisible();
    await expectNoHorizontalScroll(page);

    // ---------- 四页错误态：中文错误与重试入口 ----------
    await seedM04(request);
    consoleGuard.allowNetworkError(/\/api\/v1\/admin\/model-routing$/, 500);
    await injectProblem(page, /\/api\/v1\/admin\/model-routing$/, 500, 'RICCON-MODEL-500');
    await page.goto('/admin/model-routing');
    await expectPageError(page, '模型路由加载失败');
    await page.unroute(/\/api\/v1\/admin\/model-routing$/);

    consoleGuard.allowNetworkError(/\/api\/v1\/admin\/model-providers(\?.*)?$/, 500);
    await injectProblem(page, /\/api\/v1\/admin\/model-providers(\?.*)?$/, 500, 'RICCON-MODEL-500');
    await page.goto('/admin/model-keys');
    await expectPageError(page, '端点目录加载失败');
    await page.unroute(/\/api\/v1\/admin\/model-providers(\?.*)?$/);

    consoleGuard.allowNetworkError(PLATFORM_QUOTA_API, 500);
    await injectProblem(page, PLATFORM_QUOTA_API, 500, 'RICCON-QUOTA-500');
    await page.goto(M04_QUOTA_COST_FIXTURES.routes.quotas);
    await expectPageError(page, '配额数据加载失败');
    await page.unroute(PLATFORM_QUOTA_API);

    consoleGuard.allowNetworkError(PLATFORM_COST_API, 500);
    await injectProblem(page, PLATFORM_COST_API, 500, 'RICCON-COST-500');
    await page.goto(M04_QUOTA_COST_FIXTURES.routes.costs);
    await expectPageError(page, '成本报告加载失败');
    await page.unroute(PLATFORM_COST_API);

    // ---------- 租户管理员：只看到配额/成本；路由/key 403；配额只读 ----------
    await page.context().clearCookies();
    await seedM04(request);
    await loginTenantAdmin(page);
    await page.goto('/admin/overview');
    await expect(page.getByRole('link', { name: '配额', exact: true })).toBeVisible();
    await expect(page.getByRole('link', { name: '成本', exact: true })).toBeVisible();
    await expect(page.getByRole('link', { name: '租户配额', exact: true })).toHaveCount(0);
    await expect(page.getByRole('link', { name: '成本看板', exact: true })).toHaveCount(0);
    await expect(page.getByRole('link', { name: '模型路由', exact: true })).toHaveCount(0);
    await expect(page.getByRole('link', { name: '模型 key', exact: true })).toHaveCount(0);

    await page.goto('/admin/model-routing');
    await expect(page.getByText('没有访问权限')).toBeVisible();
    await expect(
      page.getByText('模型路由仅向平台管理员开放；租户管理员与员工无权查看或修改。'),
    ).toBeVisible();
    await page.goto('/admin/model-keys');
    await expect(page.getByText('没有访问权限')).toBeVisible();
    await expect(
      page.getByText('模型 key 仅向平台管理员开放；租户管理员与员工无权查看或修改。'),
    ).toBeVisible();

    await page.goto(M04_QUOTA_COST_FIXTURES.routes.quotas);
    await expect(
      page.getByText('只读视图：本租户日/月限额与用户用量；限额修改只由平台管理员执行。'),
    ).toBeVisible();
    await expect(page.getByRole('button', { name: /调整限额/ })).toHaveCount(0);
    await expect(page.getByText('只读', { exact: true })).toBeVisible();
    await expectNoHorizontalScroll(page);

    await page.goto(M04_QUOTA_COST_FIXTURES.routes.costs);
    await expect(
      page.getByText('本租户聚合：按用户、模型、日查看估算成本，可展开分组查看调用价格快照。'),
    ).toBeVisible();
    await expect(page.getByRole('button', { name: /保存|调整|新增|删除|编辑/ })).toHaveCount(0);
    await expectNoHorizontalScroll(page);

    // ---------- 员工：配额/成本 403，无写入口 ----------
    await page.context().clearCookies();
    await loginMember(page);
    await page.goto(M04_QUOTA_COST_FIXTURES.routes.quotas);
    await expect(
      page.getByText(
        '配额页仅向平台管理员与租户管理员开放；员工没有入口，也不会请求任何配额数据。',
      ),
    ).toBeVisible();
    await page.goto(M04_QUOTA_COST_FIXTURES.routes.costs);
    await expect(
      page.getByText(
        '成本看板仅向平台管理员与租户管理员开放；员工没有入口，也不会请求任何金额数据。',
      ),
    ).toBeVisible();
    await expect(page.getByRole('button', { name: /调整限额|保存/ })).toHaveCount(0);
    await expectNoHorizontalScroll(page);
    await page.mouse.move(0, 0);
    await expectNoSeriousA11yViolations(page);
  });
});
