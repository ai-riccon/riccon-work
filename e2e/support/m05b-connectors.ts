import { expect, type Page } from '@playwright/test';

import { loginAs, M03_CREDENTIALS } from '../tests/session';

/**
 * M05b 连接器合同桩固定数据；与 `e2e/stub/server.mjs` 的 `seedM05bState` 对齐。
 *
 * 名称、ID、端点与断言文案保持稳定，避免 E2E 依赖随机 UUID 或真实部署配置。
 */
export const M05B_FIXTURES = {
  routes: {
    connectors: '/admin/connectors',
    overview: '/admin/overview',
  },
  connectors: {
    contractsId: 'd5d5d5d5-d5d5-45d5-85d5-000000000001',
    policyId: 'd5d5d5d5-d5d5-45d5-85d5-000000000002',
    financeId: 'd5d5d5d5-d5d5-45d5-85d5-000000000003',
    backupId: 'd5d5d5d5-d5d5-45d5-85d5-000000000004',
    otherTenantId: 'd5d5d5d5-d5d5-45d5-85d5-000000000005',
    contractsName: '销售合同归档',
    policyName: '制度归档（已同步）',
    financeName: '财务凭证归档',
    backupName: '年度备份（连接失败）',
    otherTenantName: 'Beta 租户连接器',
    contractsMask: 'AKIA…7F2',
    backupMask: 'AKIA…9K1',
  },
  endpoints: {
    allowed: 'internal-s3',
    finance: 'finance-s3',
    notAllowed: 'not-allowed-s3',
  },
  secrets: {
    createdAccessKeyId: 'M05BKEY2026',
    createdSecret: 'm05b-super-secret-value',
    rotatedAccessKeyId: 'ROTATEDKEY2026',
    rotatedSecret: 'rotated-super-secret-value',
    createdMask: 'M05B…026',
    rotatedMask: 'ROTA…026',
  },
} as const;

/** 连接器列表/表单使用的稳定中文文案；与页面实现一致。 */
export const M05B_COPY = {
  pageTitle: '知识库连接器',
  menuLabel: '知识库连接器',
  empty: '还没有连接器。新建后请先写入凭据并完成连接测试，再启用或立即同步。',
  forbiddenMember: '知识库连接器仅向租户管理员开放（RICCON-AUTH-403）；普通员工无权查看或修改。',
  notFoundPlatform: '连接器属于租户范围，平台管理员不能查看或管理租户连接器（RICCON-TENANT-404）。',
  allowlistError:
    'endpointId 不在部署允许列表（RICCON_CONNECTOR_ENDPOINTS）中；请填写部署管理员提供的端点 ID，不能填写 URL（400 RICCON-KB-400）。',
  duplicateName: '连接器名称已存在（409 RICCON-KB-409）；软删除后同名也不能重建，请改名后重试。',
  visibilityRequired: '请显式选择 TENANT 或 DEPT；缺省会被服务端 400 拒绝。',
  departmentRequired: '默认可见范围为 DEPT 时必须选择启用部门。',
  syncConflict:
    '该连接器已有排队或运行中的同步（409 RICCON-KB-409）；请等待当前同步完成并刷新后再试。',
  disabled: '连接器功能未启用或同步队列不可用（RICCON-KB-503）',
  itemsEmpty: '该连接器还没有同步条目。请先完成一次同步。',
  updatedNotice: '源内容已更新',
} as const;

/**
 * 以租户管理员登录；该角色是 M05b 连接器唯一可读可写的角色。
 *
 * @param page Playwright 页面
 */
export async function loginTenantAdmin(page: Page): Promise<void> {
  await loginAs(page, M03_CREDENTIALS.tenantAdmin, '王芳');
}

/**
 * 以普通员工登录；用于验证无入口、403 与直接调用被拒。
 *
 * @param page Playwright 页面
 */
export async function loginMember(page: Page): Promise<void> {
  await loginAs(page, M03_CREDENTIALS.member, '王敏');
}

/**
 * 以平台管理员登录；用于验证统一 404 与无入口。
 *
 * @param page Playwright 页面
 */
export async function loginPlatformAdmin(page: Page): Promise<void> {
  await loginAs(page, M03_CREDENTIALS.platformAdmin, '赵蕾');
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
 * 读取 CSRF 令牌；与浏览器共享 Cookie 会话。
 *
 * @param page Playwright 页面
 * @returns CSRF 令牌
 */
export async function csrfToken(page: Page): Promise<string> {
  const response = await page.request.get('/api/v1/auth/csrf');
  expect(response.ok(), '读取 CSRF 失败').toBe(true);
  return ((await response.json()) as { token: string }).token;
}

/**
 * 通过浏览器同源请求直接调用连接器 API；用于验证负向路径与幂等。
 *
 * @param page Playwright 页面
 * @param method HTTP 方法
 * @param path API 路径（含查询串）
 * @param options 是否携带 CSRF、请求体
 * @returns 状态码与响应文本
 */
export async function connectorRequest(
  page: Page,
  method: 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE',
  path: string,
  options: { data?: unknown; csrf?: boolean } = {},
): Promise<{ status: number; text: string }> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (method !== 'GET') {
    if (options.csrf !== false) {
      headers['X-CSRF-TOKEN'] = await csrfToken(page);
    }
    headers['Content-Type'] = 'application/json';
  }
  const response = await page.request.fetch(path, {
    method,
    headers,
    ...(options.data === undefined ? {} : { data: options.data }),
  });
  return { status: response.status(), text: await response.text() };
}

/**
 * 打开连接器管理页并等待列表加载完成。
 *
 * @param page Playwright 页面
 * @returns 页面对象
 */
export async function openConnectors(page: Page): Promise<Page> {
  await page.goto(M05B_FIXTURES.routes.connectors);
  await expect(page.getByText(M05B_COPY.pageTitle, { exact: true }).first()).toBeVisible();
  await expect(page.getByText('连接器边界与安全')).toBeVisible();
  return page;
}

/**
 * 等待某个连接器行出现并按名称返回。
 *
 * @param page Playwright 页面
 * @param name 连接器名称
 * @returns 行 locator
 */
export function connectorRow(page: Page, name: string) {
  return page.getByRole('row').filter({ hasText: name });
}
