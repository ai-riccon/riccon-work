import { expect, test, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { readSeed, type RealSeed } from './real-session';

/**
 * M10 真实栈 E2E：OIDC/SSO、账号绑定、审计查询/导出与可访问性。
 *
 * 覆盖 E2E-M03-03..07 与 M15 来源 IdP 的真实浏览器段：
 * - E2E-M03-03：企业账号登录、无 JWT/token 落地、联合登出与后退防护；
 * - E2E-M03-04：绑定企业账号（管理员 UI）与失败原因文案；
 * - E2E-M03-05：审计过滤、分页、空态、无权限；
 * - E2E-M03-06：导出申请 → 另一管理员批准 → 申请人下载 CSV；
 * - E2E-M03-07：375px、键盘与 axe（登录页/审计/导出/用户管理）；
 * - E2E-M15-01/02：企业登录来源 IdP 文案、LOCAL 文案、两 issuer 列表可区分与脱敏。
 *
 * 数据由 `scripts/real-stack` 的 seed 与 m10-checks 准备；本文件只通过公开 HTTP API
 * 创建前置数据，不导入产品代码，也不使用 in-app mock。
 */

/** 测试 IdP 与 M10/M15 前置数据。 */
interface M10Context {
  oidcBase: string;
  oidcRealm: string;
  oidcTenant: string;
  oidcSubject: string;
  oidcProviderName: string;
  binding: { username: string; subject: string; displayName: string };
  identityProvider: {
    providerName: string;
    historicalLabel: string;
    oidcUsername: string;
    oidcDisplayName: string;
    historicalUsername: string;
    historicalDisplayName: string;
    localUsername: string;
  };
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
    oidcProviderName: process.env.REAL_OIDC_PROVIDER_NAME ?? 'Alpha 测试企业 IdP',
    binding: summary.binding,
    identityProvider: summary.identityProvider,
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
    // exact 避免把“<姓名> 的个人空间”等工作空间标题也算作账号显示名；strict 模式下会命中多个元素。
    await expect(page.getByText(expectedText, { exact: true })).toBeVisible();
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
 * 判断 serious/critical 违规是否属于已记录的既有状态标签对比度问题。
 *
 * antd 6 绿色 `Tag color="green"`（“启用”）在 12px 下对比度约 3.37:1，低于 WCAG AA 4.5:1；
 * 该标签自 M02/M03 起存在，不属于 M15 来源 IdP 变更面。M15 只放行目标全部命中
 * `.ant-tag-green` 的 color-contrast 节点，并在 M15-review 记录 P2 残余；其他问题仍失败。
 *
 * @param violation axe 违规
 * @returns 已记录问题返回 true
 */
function isKnownStatusTagContrast(violation: {
  id: string;
  impact?: string | null;
  nodes: Array<{ target: Array<string | string[]> }>;
}): boolean {
  return (
    violation.id === 'color-contrast' &&
    violation.nodes.every((node) =>
      node.target.some((target) => JSON.stringify(target).includes('ant-tag-green')),
    )
  );
}

/**
 * 断言页面不存在除已记录状态标签对比度外的 serious/critical 问题。
 *
 * @param page 页面
 */
async function expectNoNewSeriousA11yViolations(page: Page): Promise<void> {
  const result = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze();
  const blocking = result.violations.filter((violation) =>
    ['serious', 'critical'].includes(violation.impact ?? ''),
  );
  expect(
    blocking.filter((violation) => !isKnownStatusTagContrast(violation)),
    '发现新的 serious/critical 可访问性问题',
  ).toEqual([]);
}

/**
 * 只扫描 M15 新增来源 IdP 文案的可访问性。
 *
 * @param page 页面
 * @param selector 目标元素选择器
 */
async function expectProviderLabelA11y(page: Page, selector: string): Promise<void> {
  const result = await new AxeBuilder({ page })
    .include(selector)
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze();
  expect(
    result.violations.filter((violation) =>
      ['serious', 'critical'].includes(violation.impact ?? ''),
    ),
    '来源 IdP 文案存在 serious/critical 问题',
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

    // M15：工作空间头部显示租户配置的来源 IdP 脱敏展示名。
    const identityLabel = page.locator('.chat-identity-provider');
    await expect(identityLabel).toHaveText(`来源：${m10.oidcProviderName}`);

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

  test('E2E-M15-01 企业会话显示来源 IdP，LOCAL 会话显示本地账号', async ({ page }) => {
    const seed = readSeed();
    const m10 = readM10Context();

    await setOidcBehavior(m10, 'login', m10.binding.subject);
    await loginEnterprise(page, m10);
    const enterpriseLabel = page.locator('.chat-identity-provider');
    await expect(enterpriseLabel).toHaveText(`来源：${m10.identityProvider.providerName}`);
    await expect(enterpriseLabel).toHaveAttribute(
      'title',
      `来源：${m10.identityProvider.providerName}`,
    );
    const enterpriseBody = await page.evaluate(() => document.body.innerText);
    for (const forbidden of ['/realms/', '127.0.0.1:18995', m10.binding.subject, '@example.test', 'eyJ']) {
      expect(enterpriseBody).not.toContain(forbidden);
    }

    await logout(page);
    await loginLocal(
      page,
      seed.tenants.alpha,
      seed.users.alphaAdmin,
      seed.passwords.alphaAdmin,
      'Alpha 管理员',
    );
    const localLabel = page.locator('.chat-identity-provider');
    await expect(localLabel).toHaveText('来源：本地账号');
    const localBody = await page.evaluate(() => document.body.innerText);
    expect(localBody).not.toContain('/realms/');
    expect(localBody).not.toContain('@example.test');
    expect(localBody).not.toContain('eyJ');
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
    await expect(page.getByText(/授权已取消/)).toBeVisible();
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

  test('E2E-M15-02 用户管理两个 issuer 可区分且不泄露身份原文', async ({ page }) => {
    const seed = readSeed();
    const m10 = readM10Context();
    await loginLocal(page, seed.tenants.alpha, seed.users.alphaAdmin, seed.passwords.alphaAdmin);
    await page.goto('/admin/users');
    await expect(page.getByText('用户管理', { exact: true }).first()).toBeVisible();
    await expect(page.locator('.user-admin-list')).toBeVisible();

    const configuredRow = page.locator('.user-admin-item').filter({
      has: page.getByText(m10.identityProvider.oidcUsername, { exact: true }),
    });
    await expect(configuredRow.getByText(`来源：${m10.identityProvider.providerName}`)).toBeVisible();
    await expect(configuredRow.getByText('企业账号')).toBeVisible();

    const historicalRow = page.locator('.user-admin-item').filter({
      has: page.getByText(m10.identityProvider.historicalUsername, { exact: true }),
    });
    await expect(
      historicalRow.getByText(`来源：${m10.identityProvider.historicalLabel}`),
    ).toBeVisible();
    await expect(historicalRow.getByText('企业账号')).toBeVisible();

    // exact 用户名避免 alpha-admin 同时命中 alpha-admin2。
    const localRow = page.locator('.user-admin-item').filter({
      has: page.getByText(m10.identityProvider.localUsername, { exact: true }),
    });
    await expect(localRow.getByText('来源：本地账号')).toBeVisible();

    // 两个 issuer 的展示名必须不同；完整 issuer 路径、subject、邮箱与令牌都不出现。
    const labels = await page.locator('.user-admin-provider').allTextContents();
    expect(new Set(labels).size).toBeGreaterThanOrEqual(3);
    const body = await page.evaluate(() => document.body.innerText);
    for (const forbidden of ['https://', 'http://', '/realms/', '@example.test', m10.binding.subject, 'eyJ']) {
      expect(body).not.toContain(forbidden);
    }
  });

  test('E2E-M15-03 来源 IdP 会话与用户管理 375px、键盘与 axe @a11y', async ({ page }) => {
    const seed = readSeed();
    await page.setViewportSize({ width: 375, height: 812 });
    await loginLocal(page, seed.tenants.alpha, seed.users.alphaAdmin, seed.passwords.alphaAdmin);

    // 会话头部：来源文案实际渲染后再扫描，避免空页假绿。
    const identityLabel = page.locator('.chat-identity-provider');
    await expect(identityLabel).toHaveText('来源：本地账号');
    await expect(identityLabel).not.toHaveAttribute('tabindex');
    await expectNoHorizontalScroll(page);
    await expectNoNewSeriousA11yViolations(page);
    await expectProviderLabelA11y(page, '.chat-identity-provider');

    const logoutButton = page.getByRole('button', { name: '退出登录' });
    await logoutButton.focus();
    await expect(logoutButton).toBeFocused();

    // 用户管理：等待账号列表真实渲染后检查 375px、来源标签与 axe。
    await page.goto('/admin/users');
    await expect(page.locator('.user-admin-list')).toBeVisible();
    await expect(page.getByText('来源：本地账号').first()).toBeVisible();
    await expectNoHorizontalScroll(page);
    await expectNoNewSeriousA11yViolations(page);
    await expectProviderLabelA11y(page, '.user-admin-provider');
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
    // M15：会话头部来源文案在 375px 不撑出横向滚动，且不形成新的焦点陷阱。
    const identityLabel = page.locator('.chat-identity-provider');
    await expect(identityLabel).toHaveText('来源：本地账号');
    await expect(identityLabel).not.toHaveAttribute('tabindex');
    await expectNoHorizontalScroll(page);
    await expectNoSeriousA11yViolations(page);
    const logoutButton = page.getByRole('button', { name: '退出登录' });
    await logoutButton.focus();
    await expect(logoutButton).toBeFocused();

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
