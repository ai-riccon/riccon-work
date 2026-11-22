import type { APIRequestContext, Page } from '@playwright/test';
import { expect } from '@playwright/test';

import { loginAs, M03_CREDENTIALS } from '../tests/session';

/** M04 合同桩使用的稳定合成数据；与 `e2e/stub/server.mjs` 的 seed 保持一致。 */
export const M04_FIXTURES = {
  routes: {
    routing: '/admin/model-routing',
    keys: '/admin/model-keys',
  },
  providers: {
    primaryName: '假模型主供应商',
    primaryCode: 'fake-primary',
    secondaryName: '假模型备用供应商',
    secondaryCode: 'fake-secondary',
    primaryBaseUrl: 'http://127.0.0.1:18990/v1',
    secondaryBaseUrl: 'http://127.0.0.1:18990/v1-secondary',
  },
  models: {
    primaryKey: 'fake-chat-primary',
    secondaryKey: 'fake-chat-secondary',
    disabledKey: 'fake-chat-disabled',
    primaryName: '假模型-主',
    secondaryName: '假模型-备用',
  },
  endpoints: {
    demoOnly: '仅演示（非生产）',
    production: '生产可用',
  },
} as const;

/** 真实栈默认假模型端口；与 `scripts/real-stack/run.mjs` 一致。 */
const FAKE_MODEL_BASE = process.env.REAL_MODEL_BASE ?? 'http://127.0.0.1:18990';

/**
 * 播种 M04 合同桩数据；`empty` 只保留空目录/空列表，用于空态断言。
 *
 * @param request Playwright 请求上下文
 * @param options 空态开关
 */
export async function seedM04(
  request: APIRequestContext,
  options: { empty?: boolean } = {},
): Promise<void> {
  const response = await request.post(`${stubBaseUrl()}/__stub__/m04-seed`, {
    data: { empty: options.empty ?? false },
  });
  expect(response.ok(), 'M04 合同桩播种失败').toBe(true);
}

/**
 * 合同桩地址；Playwright 配置用同一端口启动 `e2e/stub/server.mjs`。
 *
 * @returns 合同桩基础地址
 */
export function stubBaseUrl(): string {
  return `http://127.0.0.1:${Number(process.env.E2E_STUB_PORT ?? 8124)}`;
}

/**
 * 以平台管理员登录；该角色拥有 `platform.model.read/manage`。
 *
 * @param page Playwright 页面
 */
export async function loginPlatformAdmin(page: Page): Promise<void> {
  await loginAs(page, M03_CREDENTIALS.platformAdmin, '赵蕾');
}

/**
 * 以租户管理员登录；该角色没有平台模型权限，用于 403 与无写入口断言。
 *
 * @param page Playwright 页面
 */
export async function loginTenantAdmin(page: Page): Promise<void> {
  await loginAs(page, M03_CREDENTIALS.tenantAdmin, '王芳');
}

/**
 * 以普通员工登录；该角色没有任何管理权限。
 *
 * @param page Playwright 页面
 */
export async function loginMember(page: Page): Promise<void> {
  await loginAs(page, M03_CREDENTIALS.member, '王敏');
}

/**
 * 断言当前页面没有横向滚动。
 *
 * @param page Playwright 页面
 */
export async function expectNoHorizontalScroll(page: Page): Promise<void> {
  const metrics = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }));
  expect(
    metrics.scrollWidth,
    `页面出现横向滚动：scrollWidth=${metrics.scrollWidth} clientWidth=${metrics.clientWidth}`,
  ).toBeLessThanOrEqual(metrics.clientWidth);
}

/**
 * 延迟匹配的请求，用于稳定观察加载态；不改变响应内容。
 *
 * @param page Playwright 页面
 * @param urlPattern 请求匹配
 * @param delayMs 延迟毫秒
 */
export async function delayRoute(
  page: Page,
  urlPattern: string | RegExp,
  delayMs = 1200,
): Promise<void> {
  await page.route(urlPattern, async (route) => {
    await new Promise((resolve) => setTimeout(resolve, delayMs));
    // 页面跳转/超时可能已处理同一路由，延迟后继续失败不应掩盖真实响应。
    await route.continue().catch(() => undefined);
  });
}

/**
 * 断言页面正文没有泄露 provider、model、key 或端点地址。
 *
 * 运行详情按 M02 既有要求保留模型标识，因此调用方应在打开运行详情前执行本断言。
 *
 * @param page Playwright 页面
 */
export async function expectNoProviderModelKeyLeak(page: Page): Promise<void> {
  const body = await page.locator('body').innerText();
  const forbidden = [
    M04_FIXTURES.providers.primaryCode,
    M04_FIXTURES.providers.secondaryCode,
    M04_FIXTURES.providers.primaryName,
    M04_FIXTURES.providers.secondaryName,
    M04_FIXTURES.models.primaryKey,
    M04_FIXTURES.models.secondaryKey,
    M04_FIXTURES.providers.primaryBaseUrl,
    M04_FIXTURES.providers.secondaryBaseUrl,
    '127.0.0.1:18990',
    '已切换',
    '降级到',
  ];
  for (const token of forbidden) {
    expect(body, `员工界面泄露了模型网关内部信息：${token}`).not.toContain(token);
  }
}

/**
 * 真实栈内置账号登录；不预置 Cookie。
 *
 * @param page Playwright 页面
 * @param tenantCode 租户代码
 * @param username 账号
 * @param password 密码
 * @param expectedText 登录后应可见的文本
 */
export async function loginRealLocal(
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
    await expect(page.getByText(expectedText, { exact: true })).toBeVisible();
  }
}

/**
 * 调用假模型控制端点设置全局场景。
 *
 * @param scenario 场景名
 */
export async function setFakeModelScenario(scenario: string): Promise<void> {
  const response = await fetch(`${FAKE_MODEL_BASE}/__mode__`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ mode: scenario }),
  });
  expect(response.status, `假模型场景切换失败：${scenario}`).toBe(200);
}

/**
 * 调用假模型控制端点设置按到达顺序消费的队列场景。
 *
 * @param scenario 场景名
 * @param count 次数
 */
export async function queueFakeModelScenario(scenario: string, count: number): Promise<void> {
  const response = await fetch(`${FAKE_MODEL_BASE}/__scenario__`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ scenario, count }),
  });
  expect(response.status, `假模型队列场景设置失败：${scenario}x${count}`).toBe(200);
}

/**
 * 清空假模型日志、队列与场景覆盖。
 */
export async function resetFakeModel(): Promise<void> {
  const response = await fetch(`${FAKE_MODEL_BASE}/__reset__`, { method: 'POST' });
  expect(response.status, '假模型重置失败').toBe(200);
}

/**
 * 在员工会话中新建会话并发送一条问题。
 *
 * @param page Playwright 页面
 * @param question 问题
 */
export async function createChatAndSend(page: Page, question: string): Promise<void> {
  await page.getByRole('button', { name: '新建会话' }).click();
  await expect(page.getByText('开始 J1 问答')).toBeVisible();
  const composer = page.getByPlaceholder('输入问题，Enter 发送，Shift+Enter 换行');
  await composer.fill(question);
  await composer.press('Enter');
}

/**
 * 退出当前账号并回到登录页。
 *
 * @param page Playwright 页面
 */
export async function logout(page: Page): Promise<void> {
  await page.goto('/chat');
  const button = page.getByRole('button', { name: '退出登录' });
  if ((await button.count()) > 0) {
    await button.click();
    await expect(page).toHaveURL(/\/login/);
  }
  await page.context().clearCookies();
}

/**
 * 断言管理端菜单不包含平台模型入口。
 *
 * @param page Playwright 页面
 */
export async function expectNoPlatformModelMenu(page: Page): Promise<void> {
  await expect(page.getByRole('link', { name: '模型路由' })).toHaveCount(0);
  await expect(page.getByRole('link', { name: '模型 key' })).toHaveCount(0);
}
