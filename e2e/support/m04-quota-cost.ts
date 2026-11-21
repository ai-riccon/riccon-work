import { expect, type APIRequestContext, type Page } from '@playwright/test';

import { queryValue, runSql } from '../../scripts/real-stack/lib/mysql.mjs';

/**
 * M04 配额/成本页面的稳定路径、文案与合成数据。
 *
 * 与 B9b 页面、`e2e/stub/server.mjs` 的 M04 seed 以及 ADR-0022 §1.1/§2.2 对齐；
 * 只用于 E2E 断言，不进入生产代码。
 */
export const M04_QUOTA_COST_FIXTURES = {
  routes: {
    quotas: '/admin/quotas',
    costs: '/admin/costs',
  },
  /** 固定重置时间：与合同桩 seed 的 M04_DAILY_RESET/MONTHLY_RESET 一致。 */
  resets: {
    daily: '2026-11-22 00:00:00 +08:00',
    monthly: '2026-12-01 00:00:00 +08:00',
  },
  disclaimer: '成本为按调用时价格快照估算，不是供应商账单。',
  tenants: {
    acmeCode: 'tenant-acme',
    betaCode: 'tenant-beta',
    acmeName: '示例企业',
    betaName: 'Beta 演示企业',
  },
  /** 平台合计：0.0042（acme 已定价）+ 0.008（beta 已定价）。 */
  platform: {
    amount: 'CNY 0.01',
    exactAmount: '0.012200 CNY',
    pricedCalls: '2',
    unpricedCalls: '1',
    usageUnknownCalls: '1',
  },
  /** 租户 acme 合计：仅 0.0042 已定价；展示按 2 位小数舍入。 */
  tenant: {
    amount: 'CNY 0.00',
    exactAmount: '0.004200 CNY',
  },
} as const;

/**
 * 定位配额表格中的租户行；按租户代码过滤，避免与表头/说明文本混用。
 *
 * @param page Playwright 页面
 * @param tenantCode 租户代码
 * @returns 行 locator
 */
export function quotaTenantRow(page: Page, tenantCode: string) {
  return page.getByRole('row').filter({ hasText: tenantCode });
}

/**
 * 定位成本聚合表格中的分组行；按维度标题过滤。
 *
 * @param page Playwright 页面
 * @param label 维度标题（租户代码、用户展示名、模型名或日期）
 * @returns 行 locator
 */
export function costBucketRow(page: Page, label: string) {
  return page.getByRole('row').filter({ hasText: label });
}

/**
 * 定位成本合计中的单项；用 antd Descriptions 项容器同时匹配标签与值。
 *
 * @param page Playwright 页面
 * @param label 标签文本或正则
 * @returns Descriptions 项 locator
 */
export function costTotalsItem(page: Page, label: string | RegExp) {
  return page.locator('.ant-descriptions-item').filter({ hasText: label });
}

/**
 * 在真实栈执行一段只作用于测试栈的 SQL。
 *
 * 复用 `scripts/real-stack/lib/mysql.mjs` 的 compose+utf8mb4 参数，避免字符集与连接参数漂移；
 * 只用于成本边界夹具，不改变产品语义。
 *
 * @param sql SQL 文本
 */
export function runRealStackSql(sql: string): void {
  const result = runSql(sql, { repoRoot: process.cwd() });
  expect(result.status, `真实栈 SQL 执行失败：${result.stderr || result.stdout}`).toBe(0);
}

/**
 * 在真实栈执行单值查询并返回字符串。
 *
 * @param sql SQL 查询
 * @returns 最后一行结果
 */
export function realStackQueryValue(sql: string): string {
  return queryValue(sql).trim();
}

/**
 * 真实栈 API 基地址；与 `scripts/real-stack/run.mjs` 的 REAL_API_PORT 默认值一致。
 *
 * @returns API 基础地址
 */
export function realApiBase(): string {
  return `http://127.0.0.1:${Number(process.env.REAL_API_PORT ?? 18080)}`;
}

/**
 * 修正合同桩“保存配额”响应的 `used` 字段。
 *
 * B8 合同桩的 `PUT /admin/tenants/{tenantId}/quotas` 只回写限额与来源、丢失 seed 的 `used`，
 * 页面按契约渲染时会显示 `NaN`。B8 不在 B11b 写入范围，这里在用例内按 OpenAPI 的
 * `QuotaWindowState.used` 补回 seed 计数，避免把桩缺陷误判为页面缺陷。
 *
 * @param page Playwright 页面
 */
export async function patchStubQuotaSaveUsage(page: Page): Promise<void> {
  await page.route(/\/api\/v1\/admin\/tenants\/[^/]+\/quotas$/, async (route) => {
    if (route.request().method() !== 'PUT') {
      await route.continue();
      return;
    }
    const response = await route.fetch();
    if (response.status() !== 200) {
      await route.fulfill({ response });
      return;
    }
    const payload = (await response.json()) as {
      windows?: Array<{ window: string; used?: number }>;
    };
    const used: Record<string, number> = { DAILY: 120, MONTHLY: 3400 };
    payload.windows = (payload.windows ?? []).map((window) => ({
      ...window,
      used: used[window.window] ?? 0,
    }));
    await route.fulfill({ response, json: payload });
  });
}

/**
 * 读取当前会话的 CSRF 令牌，用于真实栈中直接调用写接口。
 *
 * `APIRequestContext` 与浏览器共享 Cookie；令牌随登录轮换，因此每次写操作前重新读取。
 *
 * @param request Playwright 请求上下文
 * @returns CSRF 令牌
 */
export async function csrfToken(request: APIRequestContext): Promise<string> {
  const response = await request.get('/api/v1/auth/csrf');
  expect(response.status(), '读取 CSRF 令牌失败').toBe(200);
  const body = (await response.json()) as { token?: string };
  expect(body.token, 'CSRF 响应缺少 token').toBeTruthy();
  return body.token as string;
}

/**
 * 真实栈登录响应中的 CSRF 令牌（登录接口会轮换令牌）。
 *
 * @param request Playwright 请求上下文
 * @param tenantCode 租户代码
 * @param username 账号
 * @param password 密码
 * @returns 登录会话的 CSRF 令牌
 */
export async function loginByApi(
  request: APIRequestContext,
  tenantCode: string,
  username: string,
  password: string,
): Promise<string> {
  // 登录是写操作，Spring Security 要求先取得 CSRF 令牌；登录成功后令牌会轮换。
  const initialToken = await csrfToken(request);
  const response = await request.post('/api/v1/auth/login', {
    headers: { 'X-CSRF-TOKEN': initialToken },
    data: { tenantCode, username, password },
  });
  expect(response.status(), `API 登录失败：${tenantCode}/${username}`).toBe(200);
  const body = (await response.json()) as { csrfToken?: string };
  expect(body.csrfToken, '登录响应缺少 csrfToken').toBeTruthy();
  return body.csrfToken as string;
}

/**
 * 真实栈网关开关响应；字段与 OpenAPI `ModelGatewaySwitches` 对齐。
 */
export interface GatewaySwitches {
  routingMode: 'ROUTED' | 'BASELINE';
  failoverEnabled: boolean;
  quotaEnforcementEnabled: boolean;
  version: number;
  source: 'DATABASE' | 'ENVIRONMENT';
  envOverride?: Record<string, unknown> | null;
  updatedAt: string;
  updatedBy: string | null;
}

/**
 * 读取真实栈网关开关。
 *
 * @param request 平台管理员请求上下文
 * @returns 当前生效开关
 */
export async function readGatewaySwitches(request: APIRequestContext): Promise<GatewaySwitches> {
  const response = await request.get('/api/v1/admin/model-gateway/switches');
  expect(response.status(), '读取网关开关失败').toBe(200);
  return (await response.json()) as GatewaySwitches;
}

/**
 * 通过 PUT 切换配额执行开关，保留路由与降级开关原值。
 *
 * 环境变量覆盖生效时管理端写接口会返回 409 `ENV_OVERRIDE_ACTIVE`（或 GET 的
 * `source=ENVIRONMENT`）；测试栈不应存在覆盖，出现时直接失败并输出覆盖键。
 *
 * @param request 平台管理员请求上下文
 * @param csrf CSRF 令牌
 * @param enabled 目标值
 * @returns 保存后的开关
 */
export async function setQuotaEnforcement(
  request: APIRequestContext,
  csrf: string,
  enabled: boolean,
): Promise<GatewaySwitches> {
  const current = await readGatewaySwitches(request);
  if (current.source === 'ENVIRONMENT') {
    throw new Error(
      `测试栈存在网关开关环境覆盖（${JSON.stringify(current.envOverride ?? {})}），无法通过 API 切换配额执行开关`,
    );
  }
  const response = await request.put('/api/v1/admin/model-gateway/switches', {
    headers: { 'X-CSRF-TOKEN': csrf },
    data: {
      version: current.version,
      routingMode: current.routingMode,
      failoverEnabled: current.failoverEnabled,
      quotaEnforcementEnabled: enabled,
    },
  });
  if (response.status() === 409) {
    const problem = (await response.json()) as {
      code?: string;
      reason?: string;
      detail?: string;
    };
    throw new Error(
      `切换网关开关被拒绝（409 ${problem.code ?? ''} ${problem.reason ?? ''}）：${
        problem.detail ?? '环境覆盖生效或版本冲突'
      }`,
    );
  }
  if (response.status() !== 200) {
    throw new Error(`切换网关开关失败：${response.status()} ${await response.text()}`);
  }
  const saved = (await response.json()) as GatewaySwitches;
  expect(saved.quotaEnforcementEnabled).toBe(enabled);
  expect(saved.version).toBe(current.version + 1);
  return saved;
}

/**
 * 通过 PUT 恢复网关开关原值（读取最新 version 后整组保存）。
 *
 * @param request 平台管理员请求上下文
 * @param csrf CSRF 令牌
 * @param original 原始开关值
 */
export async function restoreGatewaySwitches(
  request: APIRequestContext,
  csrf: string,
  original: Pick<GatewaySwitches, 'routingMode' | 'failoverEnabled' | 'quotaEnforcementEnabled'>,
): Promise<void> {
  const current = await readGatewaySwitches(request);
  if (current.source === 'ENVIRONMENT') {
    throw new Error(
      `测试栈存在网关开关环境覆盖（${JSON.stringify(current.envOverride ?? {})}），无法恢复开关`,
    );
  }
  const response = await request.put('/api/v1/admin/model-gateway/switches', {
    headers: { 'X-CSRF-TOKEN': csrf },
    data: {
      version: current.version,
      routingMode: original.routingMode,
      failoverEnabled: original.failoverEnabled,
      quotaEnforcementEnabled: original.quotaEnforcementEnabled,
    },
  });
  if (response.status() !== 200) {
    throw new Error(`恢复网关开关失败：${response.status()} ${await response.text()}`);
  }
  const restored = (await response.json()) as GatewaySwitches;
  expect(restored.quotaEnforcementEnabled).toBe(original.quotaEnforcementEnabled);
  expect(restored.routingMode).toBe(original.routingMode);
  expect(restored.failoverEnabled).toBe(original.failoverEnabled);
}

/**
 * 读取 Prometheus 文本；真实栈默认只允许 loopback 来源。
 *
 * @param request Playwright 请求上下文
 * @returns Prometheus 文本
 */
export async function readPrometheus(request: APIRequestContext): Promise<string> {
  const response = await request.get(`${realApiBase()}/actuator/prometheus`);
  expect(response.status(), '/actuator/prometheus 不可读（需 loopback 来源）').toBe(200);
  return response.text();
}
