import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { loginAs, M03_CREDENTIALS } from './session';

/**
 * 断言当前页面无横向滚动。
 *
 * @param page Playwright 页面
 */
async function expectNoHorizontalScroll(page: Page): Promise<void> {
  const metrics = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }));
  expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth);
}

/**
 * 判断 serious/critical 违规是否属于已记录的既有状态标签对比度问题。
 *
 * antd 6 的绿色 `Tag color="green"` 在 12px 下对比度 3.37:1，低于 WCAG AA 4.5:1；该标签自 M02/M03
 * 起就存在，不属于 M15 来源 IdP 变更面。M15 不静默忽略整页可访问性：只放行目标全部命中
 * `.ant-tag-green` 的 color-contrast 节点，其他 serious/critical 仍失败；并在 M15-review 记录 P2 残余。
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
 * @param page Playwright 页面
 */
async function expectNoNewSeriousA11yViolations(page: Page): Promise<void> {
  const result = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze();
  const blocking = result.violations.filter(
    (violation) => violation.impact === 'serious' || violation.impact === 'critical',
  );
  const unexpected = blocking.filter((violation) => !isKnownStatusTagContrast(violation));
  expect(unexpected, `发现新的 serious/critical 可访问性问题：${JSON.stringify(unexpected)}`).toEqual(
    [],
  );
}

/**
 * 只扫描 M15 新增来源 IdP 文案的对比度与语义。
 *
 * @param page Playwright 页面
 * @param selector 目标元素选择器
 */
async function expectProviderLabelA11y(page: Page, selector: string): Promise<void> {
  const result = await new AxeBuilder({ page })
    .include(selector)
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze();
  const blocking = result.violations.filter(
    (violation) => violation.impact === 'serious' || violation.impact === 'critical',
  );
  expect(blocking, `来源 IdP 文案存在 serious/critical 问题：${JSON.stringify(blocking)}`).toEqual(
    [],
  );
}

/**
 * M15 来源 IdP 展示 E2E（合同 stub 套件）。
 *
 * 覆盖 R-M10-01 的用户可见结果在合同 `pnpm -w e2e` 中可回归：
 * - 会话/工作空间头部显示“来源：本地账号”，不显示“未标注”或完整 issuer；
 * - 用户管理列表按账号显示服务端脱敏 `identityProvider`，两个 issuer 可区分；
 * - 375px、键盘结构基线与 axe 无 serious/critical。
 *
 * 注意：stub 只模拟服务端响应；真实企业账号登录链路由真实栈 E2E-M15-01 与测试 IdP 覆盖，
 * 本文件不把本地密码登录冒充成 OIDC 登录。
 */
test.describe('M15 来源 IdP 展示', () => {
  test('E2E-M15-01 本地会话显示来源：本地账号', async ({ page }) => {
    await loginAs(page, M03_CREDENTIALS.tenantAdmin, '王芳');

    const header = page.locator('.chat-identity-provider');
    await expect(header).toHaveText('来源：本地账号');
    await expect(header).toHaveAttribute('title', '来源：本地账号');
    // 不把 LOCAL 标成未知来源，也不泄露邮箱、issuer 路径或令牌。
    await expect(page.getByText('来源：未标注')).toHaveCount(0);
    const body = await page.evaluate(() => document.body.innerText);
    expect(body).not.toContain('/realms/');
    expect(body).not.toContain('@example');
    expect(body).not.toContain('eyJ');
  });

  test('E2E-M15-02 用户管理按账号显示脱敏来源 IdP，两个 issuer 可区分', async ({ page }) => {
    await loginAs(page, M03_CREDENTIALS.tenantAdmin, '王芳');
    await page.goto('/admin/users');
    await expect(page.getByText('用户管理', { exact: true }).first()).toBeVisible();
    await expect(page.locator('.user-admin-list')).toBeVisible();

    const localRow = page.locator('.user-admin-item').filter({ hasText: '王敏' });
    await expect(localRow.getByText('来源：本地账号')).toBeVisible();

    const firstRow = page.locator('.user-admin-item').filter({ hasText: '甲企业员工' });
    await expect(firstRow.getByText('来源：Acme 企业 IdP')).toBeVisible();
    await expect(firstRow.getByText('企业账号')).toBeVisible();

    const secondRow = page.locator('.user-admin-item').filter({ hasText: '乙企业员工' });
    await expect(secondRow.getByText('来源：idp-b.example.com:8443')).toBeVisible();
    await expect(secondRow.getByText('企业账号')).toBeVisible();

    // 两个来源文案必须不同；列表不出现完整 issuer、subject、邮箱或令牌。
    const labels = await page.locator('.user-admin-provider').allTextContents();
    expect(new Set(labels).size).toBeGreaterThanOrEqual(3);
    const body = await page.evaluate(() => document.body.innerText);
    expect(body).not.toContain('https://');
    expect(body).not.toContain('/realms/');
    expect(body).not.toContain('@example');
    expect(body).not.toContain('eyJ');

    // 长标题的完整值保留在 title 中，窄屏用 CSS 截断。
    await expect(firstRow.locator('.user-admin-provider')).toHaveAttribute(
      'title',
      '来源：Acme 企业 IdP',
    );
  });

  test('E2E-M15-03 会话与用户管理 375px、键盘与 axe @a11y', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await loginAs(page, M03_CREDENTIALS.tenantAdmin, '王芳');

    // 会话头部：来源文案可见，长文本不撑出横向滚动；新文本不进入 Tab 焦点链。
    const header = page.locator('.chat-identity-provider');
    await expect(header).toHaveText('来源：本地账号');
    await expect(header).not.toHaveAttribute('tabindex');
    await expectNoHorizontalScroll(page);
    await expectNoNewSeriousA11yViolations(page);
    await expectProviderLabelA11y(page, '.chat-identity-provider');

    // 键盘路径保持可用：退出登录按钮可由焦点触发，来源文本不形成焦点陷阱。
    const logoutButton = page.getByRole('button', { name: '退出登录' });
    await logoutButton.focus();
    await expect(logoutButton).toBeFocused();

    await page.goto('/admin/users');
    await expect(page.locator('.user-admin-list')).toBeVisible();
    await expect(page.getByText('来源：本地账号').first()).toBeVisible();
    await expectNoHorizontalScroll(page);
    await expectNoNewSeriousA11yViolations(page);
    await expectProviderLabelA11y(page, '.user-admin-provider');
  });
});
