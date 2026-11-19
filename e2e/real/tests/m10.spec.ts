import { expect, test, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { readSeed, type RealSeed } from './real-session';

/**
 * M10 真实栈 E2E：OIDC/SSO、账号绑定、审计查询/导出与可访问性。
 *
 * 覆盖 E2E-M03-03..07 的真实浏览器段：
 * - E2E-M03-03：企业账号登录、无 JWT/token 落地、联合登出与后退防护；
 * - E2E-M03-04：绑定企业账号（管理员 UI）与失败原因文案；
 * - E2E-M03-05：审计过滤、分页、空态、无权限；
 * - E2E-M03-06：导出申请 → 另一管理员批准 → 申请人下载 CSV；
 * - E2E-M03-07：375px、键盘与 axe（登录页/审计/导出/用户管理）。
 *
 * 数据由 `scripts/real-stack` 的 seed 与 m10-checks 准备；本文件只通过公开 HTTP API
 * 创建前置数据，不导入产品代码，也不使用 in-app mock。
 */

/** 测试 IdP 与 M10 前置数据。 */
interface M10Context {
  oidcBase: string;
  oidcRealm: string;
  oidcTenant: string;
  oidcSubject: string;
  binding: { username: string; subject: string; displayName: string };
}

/** 读取 m10-checks 输出。 */
function readM10Context(): M10Context {
  const summaryFile =
    process.env.REAL_EVIDENCE_DIR ?? path.resolve(__dirname, '../../../.riccon/evidence/TASK-0024');
  const summary = JSON.parse(
    readFileSync(path.join(summaryFile, 'real-stack-m10-summary.json'), 'utf8'),
  );
  return {
    oidcBase: process.env.REAL_OIDC_BASE ?? 'http://127.0.0.1:18995',
    oidcRealm: process.env.REAL_OIDC_REALM ?? 'alpha',
    oidcTenant: process.env.REAL_OIDC_TENANT ?? 'tenant-alpha',
    oidcSubject: process.env.REAL_OIDC_SUBJECT ?? 'subject-alpha',
    binding: summary.binding,
  };
}

/**
 * 设置测试 IdP 行为。
 *
 * @param context M10 上下文
 * @param mode login/deny/bad_state/down
 * @param subject 可选 subject
 */
async function setOidcBehavior(context: M10Context, mode: string, subject?: string): Promise<void> {
  const response = await fetch(`${context.oidcBase}/__oidc__/behavior`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ realm: context.oidcRealm, mode, subject }),
  });
  expect(response.status).toBe(200);
}

/**
 * 登录内置账号。
 *
 * @param page 页面
 * @param tenantCode 租户
 * @param username 账号
 * @param password 密码
 * @param expectedText 登录后应可见的文本
 */
async function loginLocal(
  page: Page,
  tenantCode: string,
  username: string,
  password: string,
  expectedText?: string,
): Promise<void> {
  await page.goto('/login');
  await page.getByPlaceholder('例如 acme').fill(tenantCode);
  await page.getByPlaceholder('请输入账号').fill(username);
  await page.getByPlaceholder('请输入密码').fill(password);
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect(page).toHaveURL(/\/chat$/);
  await expect(page.getByRole('heading', { name: 'Riccon 工作空间' })).toBeVisible();
  if (expectedText) {
    await expect(page.getByText(expectedText)).toBeVisible();
  }
}

/**
 * 退出登录并回到登录页。
 *
 * @param page 页面
 */
async function logout(page: Page): Promise<void> {
  await page.goto('/chat');
  await page.getByRole('button', { name: '退出登录' }).click();
  await expect(page).toHaveURL(/\/login/);
}

/**
 * 使用企业账号登录（顶层导航，经过测试 IdP）。
 *
 * @param page 页面
 * @param context M10 上下文
 */
async function loginEnterprise(page: Page, context: M10Context): Promise<void> {
  await page.goto('/login');
  await page.getByPlaceholder('例如 acme').fill(context.oidcTenant);
  await page.getByRole('button', { name: '企业账号' }).click();
  await expect(page).toHaveURL(/\/chat$/, { timeout: 30_000 });
  await expect(page.getByRole('heading', { name: 'Riccon 工作空间' })).toBeVisible();
}

/**
 * 断言当前页面无横向滚动。
 *
 * @param page 页面
 */
async function expectNoHorizontalScroll(page: Page): Promise<void> {
  const metrics = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }));
  expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth);
}

/**
 * 对当前页面执行 axe，断言无 serious/critical。
 *
 * @param page 页面
 */
async function expectNoSeriousA11yViolations(page: Page): Promise<void> {
  const result = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze();
  expect(
    result.violations.filter((violation) =>
      ['serious', 'critical'].includes(violation.impact ?? ''),
    ),
  ).toEqual([]);
}

/**
 * 通过公开 API 创建本地账号；返回账号。
 *
 * @param seed 真实栈 seed
 * @param username 登录名
 * @returns 账号
 */
async function createLocalUser(
  seed: RealSeed,
  username: string,
  displayName: string,
  password: string,
): Promise<{ id: string }> {
  const { ApiClient } = await import('../../../scripts/real-stack/lib/http.mjs');
  const platform = new ApiClient(seed.apiBase);
  await platform.login('platform', seed.users.platformAdmin, 'test-bootstrap-password-12');
  const response = await platform.post(`/api/v1/admin/tenants/${seed.tenants.alpha}/users`, {
    username,
    displayName,
    password,
    roles: ['MEMBER'],
  });
  expect(response.status, response.text).toBe(201);
  return response.data;
}

test.describe('M10 身份与审计', () => {
  test('E2E-M03-03 企业账号登录、无 token 落地与联合登出', async ({ page, context }) => {
    const m10 = readM10Context();
    await setOidcBehavior(m10, 'login', m10.binding.subject);
    await loginEnterprise(page, m10);
    await expect(page.getByText(m10.binding.displayName, { exact: true }).first()).toBeVisible();

    // 浏览器存储与 Cookie 不出现 JWT/access token。
    const cookieText = JSON.stringify(await context.cookies());
    expect(cookieText).not.toContain('eyJ');
    const storage = await page.evaluate(() => ({
      local: JSON.stringify({ ...localStorage }),
      session: JSON.stringify({ ...sessionStorage }),
    }));
    expect(storage.local).not.toContain('eyJ');
    expect(storage.session).not.toContain('eyJ');
    expect(storage.local).not.toContain('access_token');
    expect(storage.session).not.toContain('refresh_token');
    expect(await page.evaluate(() => document.body.innerText)).not.toContain('eyJ');

    // 联合登出：本地先失效，IdP end-session 回跳后明确提示。
    await logout(page);
    await expect(page.getByText('已退出本地会话和企业账号')).toBeVisible({ timeout: 30_000 });
    await page.goto('/chat');
    await expect(page).toHaveURL(/\/login/);
    await expect(page.getByText(m10.binding.displayName)).toHaveCount(0);

    await setOidcBehavior(m10, 'login', m10.oidcSubject);
  });

  test('E2E-M03-04 管理员绑定企业账号与失败原因文案', async ({ page }) => {
    const seed = readSeed();
    const m10 = readM10Context();
    const suffix = Date.now().toString(36);
    const username = `bind-ui-${suffix}`;
    await createLocalUser(seed, username, 'UI 绑定员工', 'initial-password-12');

    await loginLocal(
      page,
      seed.tenants.alpha,
      seed.users.alphaAdmin,
      seed.passwords.alphaAdmin,
      'Alpha 管理员',
    );
    await page.goto('/admin/users');
    await expect(page.getByText('用户管理', { exact: true }).first()).toBeVisible();
    const row = page.locator('.user-admin-item').filter({ hasText: username });
    await expect(row).toBeVisible();
    await expect(row.getByText('内置账号')).toBeVisible();
    await row.getByRole('button', { name: '绑定企业账号：UI 绑定员工' }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: '绑定企业账号' });
    await dialog
      .getByPlaceholder('https://idp.example.com/realms/acme')
      .fill(`${m10.oidcBase}/realms/${m10.oidcRealm}`);
    await dialog.getByPlaceholder('例如 248289761001').fill(`subject-ui-${suffix}`);
    await dialog.getByRole('button', { name: '确认绑定' }).click();
    await expect(page.getByText(/已绑定 UI 绑定员工.*的企业账号/)).toBeVisible();

    // IdP 拒绝授权：登录页显示稳定原因与下一步，不出现 IdP 原始错误或堆栈。
    await logout(page);
    await setOidcBehavior(m10, 'deny');
    await page.goto('/login');
    await page.getByPlaceholder('例如 acme').fill(m10.oidcTenant);
    await page.getByRole('button', { name: '企业账号' }).click();
    await expect(page).toHaveURL(/oidcError=access_denied/);
    await expect(page.getByText('企业账号授权已取消')).toBeVisible();
    await expect(page.getByText(/取消授权/)).toBeVisible();
    await expect(page.getByText(/invalid_grant|client_secret|stack/i)).toHaveCount(0);

    // state 不匹配：不同文案，仍不建立会话。
    await setOidcBehavior(m10, 'bad_state');
    await page.getByPlaceholder('例如 acme').fill(m10.oidcTenant);
    await page.getByRole('button', { name: '企业账号' }).click();
    await expect(page).toHaveURL(/oidcError=state_invalid/);
    await expect(page.getByText('企业账号登录已失效')).toBeVisible();

    // IdP 不可达：稳定原因，可改用内置账号。
    await setOidcBehavior(m10, 'down');
    await page.getByPlaceholder('例如 acme').fill(m10.oidcTenant);
    await page.getByRole('button', { name: '企业账号' }).click();
    await expect(page).toHaveURL(/oidcError=idp_unavailable/);
    await expect(page.getByText('企业账号服务暂时不可用')).toBeVisible();
    await expect(page.getByText(/改用内置账号登录/)).toBeVisible();
    await setOidcBehavior(m10, 'login', m10.oidcSubject);
  });

  test('E2E-M03-05 审计过滤、分页、空态与无权限', async ({ page }) => {
    const seed = readSeed();
    await loginLocal(page, seed.tenants.alpha, seed.users.alphaAdmin, seed.passwords.alphaAdmin);
    await page.goto('/admin/audit');
    await expect(page.getByText('保留策略与只读边界')).toBeVisible();
    await expect(page.getByText(/至少保留 180 天/)).toBeVisible();

    // 对象过滤：模型事件显示 token 指标（避免虚拟列表滚动依赖）。
    await page.getByRole('textbox', { name: '对象 ID' }).fill('=SUM(A1:A2)');
    await page.getByRole('button', { name: /查\s*询/ }).click();
    await expect(page.getByText('model.invocation.completed').first()).toBeVisible();
    await page
      .getByRole('button', { name: /查看事件详情 model.invocation.completed/ })
      .first()
      .click();
    const drawer = page.getByRole('dialog').filter({ hasText: '审计事件详情' });
    await expect(drawer.getByText(/"promptTokens":\s*10/)).toBeVisible();
    await page.keyboard.press('Escape');

    // 分页：按操作者过滤出 >=220 条，翻到第 2 页。
    await page.getByRole('button', { name: /重\s*置/ }).click();
    await page.getByRole('textbox', { name: '操作者 ID' }).fill(seed.userIds.alphaAdmin);
    await page.getByRole('button', { name: /查\s*询/ }).click();
    await expect(page.getByText(/共 \d{3,} 条/).first()).toBeVisible();
    await page.locator('.ant-pagination-item-2').click();
    await expect(page.locator('.ant-pagination-item-active')).toHaveText('2');

    // 空态：合法但无结果的 UUID。
    await page.getByRole('button', { name: /重\s*置/ }).click();
    await page
      .getByRole('textbox', { name: '操作者 ID' })
      .fill('11111111-1111-4111-8111-111111111111');
    await page.getByRole('button', { name: /查\s*询/ }).click();
    await expect(
      page.getByText('当前条件下没有审计事件。可以扩大时间范围或清除过滤条件。'),
    ).toBeVisible();

    // 普通员工：页面无权限、无数据；菜单无审计入口。
    await logout(page);
    await loginLocal(page, seed.tenants.alpha, seed.users.alphaMember, seed.passwords.alphaMember);
    await expect(page.getByRole('link', { name: '审计查询' })).toHaveCount(0);
    await page.goto('/admin/audit');
    await expect(page.getByText('没有访问权限')).toBeVisible();
    await expect(page.locator('.ant-table-row')).toHaveCount(0);
  });

  test('E2E-M03-06 导出申请、他人批准与 CSV 下载', async ({ page }) => {
    const seed = readSeed();
    await loginLocal(page, seed.tenants.alpha, seed.users.alphaAdmin, seed.passwords.alphaAdmin);
    await page.goto('/admin/audit');
    await expect(page.getByText('保留策略与只读边界')).toBeVisible();
    await page.getByRole('button', { name: '申请导出' }).click();
    const modal = page.getByRole('dialog').filter({ hasText: '申请导出审计' });
    await expect(modal.getByText('申请人不能审批自己的请求')).toBeVisible();
    await modal.getByRole('button', { name: '提交申请' }).click();
    await expect(page.getByText(/已提交导出申请/)).toBeVisible();

    await logout(page);
    await loginLocal(page, seed.tenants.alpha, seed.users.alphaAdmin2, seed.passwords.alphaAdmin2);
    await page.goto('/admin/audit-exports');
    const pendingRow = page.locator('.ant-table-row').filter({ hasText: '待审批' }).first();
    await expect(pendingRow).toBeVisible();
    await pendingRow.getByRole('button', { name: /批准导出申请/ }).click();
    const decision = page.getByRole('dialog').filter({ hasText: '批准导出申请' });
    await decision.getByRole('button', { name: '确认批准' }).click();
    await expect(page.getByText(/导出申请已批准/)).toBeVisible();
    await expect(page.getByText('已批准').first()).toBeVisible();

    await logout(page);
    await loginLocal(page, seed.tenants.alpha, seed.users.alphaAdmin, seed.passwords.alphaAdmin);
    await page.goto('/admin/audit-exports');
    const approvedRow = page.locator('.ant-table-row').filter({ hasText: '已批准' }).first();
    await expect(approvedRow).toBeVisible();
    const downloadPromise = page.waitForEvent('download');
    await approvedRow.getByRole('button', { name: /下载导出/ }).click();
    const download = await downloadPromise;
    expect(download.suggestedFilename()).toMatch(/^audit-export-.*\.csv$/);
    const stream = await download.createReadStream();
    const chunks: Buffer[] = [];
    for await (const chunk of stream) {
      chunks.push(Buffer.from(chunk));
    }
    const csv = Buffer.concat(chunks).toString('utf8');
    expect(csv).toContain('# exportId:');
    expect(csv).toContain('# filters:');
    expect(csv).toContain('eventId,occurredAt,tenantId');
    expect(csv).not.toContain('source_ip');
    expect(csv).not.toContain('access_token');
  });

  test('E2E-M03-07 登录/审计/导出/用户管理 375px、键盘与 axe @a11y', async ({ page }) => {
    const seed = readSeed();
    const m10 = readM10Context();
    await page.setViewportSize({ width: 375, height: 812 });

    // 登录页：企业账号入口键盘可达，375px 无横向滚动。
    await page.goto('/login');
    await expect(page.getByRole('button', { name: '企业账号' })).toBeVisible();
    await page.getByRole('button', { name: '企业账号' }).focus();
    await expect(page.getByRole('button', { name: '企业账号' })).toBeFocused();
    await expectNoHorizontalScroll(page);
    await expectNoSeriousA11yViolations(page);

    await loginLocal(page, seed.tenants.alpha, seed.users.alphaAdmin, seed.passwords.alphaAdmin);
    for (const path of ['/admin/audit', '/admin/audit-exports', '/admin/users']) {
      await page.goto(path);
      await expect(page.locator('.ant-skeleton')).toHaveCount(0, { timeout: 15_000 });
      await expectNoHorizontalScroll(page);
      await expectNoSeriousA11yViolations(page);
    }

    // 键盘路径：审计页仅用键盘完成一次查询。
    await page.goto('/admin/audit');
    await expect(page.getByText('保留策略与只读边界')).toBeVisible();
    const queryButton = page.getByRole('button', { name: /查\s*询/ });
    await queryButton.focus();
    await expect(queryButton).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page.getByText(/共 \d+ 条|当前条件下没有审计事件/)).toBeVisible();

    await setOidcBehavior(m10, 'login', m10.oidcSubject);
  });
});
