import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import { CostClient } from './api';
import type { CostInvocationPage, PlatformCostReport, TenantCostReport } from './types';

/** 记录 fetch 调用。 */
interface FetchCall {
  url: string;
  init: RequestInit;
}

/**
 * 构造可控 fetch 替身。
 *
 * @param handler 按 URL 返回响应
 * @returns fetch 函数与调用记录
 */
function createFetch(handler: (url: string, init: RequestInit) => Response | Promise<Response>) {
  const calls: FetchCall[] = [];
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url =
      typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
    const requestInit = init ?? {};
    calls.push({ url, init: requestInit });
    return handler(url, requestInit);
  });
  return { fn: fn as unknown as typeof fetch, calls };
}

/** JSON 响应。 */
function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** ProblemDetail 响应。 */
function problem(status: number, code: string): Response {
  return new Response(
    JSON.stringify({ type: 'about:blank', title: '错误', status, code, traceId: 'trace-1' }),
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  );
}

/** 公共合计。 */
const TOTALS = {
  currency: 'CNY',
  amount: '12.345600',
  pricedCalls: 3,
  unpricedCalls: 1,
  usageUnknownCalls: 2,
  currencyMismatchCalls: 0,
  unbucketedCalls: 0,
};

/** 租户成本报告。 */
const TENANT_REPORT: TenantCostReport = {
  page: 1,
  size: 20,
  total: 1,
  totalPages: 1,
  from: '2026-10-23',
  to: '2026-11-21',
  groupBy: 'USER',
  currency: 'CNY',
  currencyMinorUnit: 2,
  disclaimer: '成本为按调用时价格快照估算，不是供应商账单。',
  totals: TOTALS,
  items: [
    {
      key: 'user-1',
      userId: 'user-1',
      userDisplayName: '王敏',
      amount: '12.345600',
      pricedCalls: 3,
      unpricedCalls: 1,
      usageUnknownCalls: 2,
    },
  ],
};

/** 平台成本报告。 */
const PLATFORM_REPORT: PlatformCostReport = {
  ...TENANT_REPORT,
  groupBy: 'TENANT',
  items: [
    {
      key: 'acme',
      tenantId: 'acme',
      tenantCode: 'acme',
      amount: '12.345600',
      pricedCalls: 3,
      unpricedCalls: 1,
      usageUnknownCalls: 2,
    },
  ],
};

/** 调用明细分页。 */
const INVOCATION_PAGE: CostInvocationPage = {
  page: 1,
  size: 10,
  total: 1,
  totalPages: 1,
  items: [
    {
      id: 'inv-1',
      modelId: 'model-1',
      modelDisplayName: 'GPT',
      userId: 'user-1',
      userDisplayName: '王敏',
      startedAt: '2026-11-21T10:30:00+08:00',
      promptTokens: 1000,
      completionTokens: 500,
      inputPricePer1M: '3.000000',
      outputPricePer1M: '6.000000',
      priceCurrency: 'CNY',
      amount: '0.006000',
      pricingStatus: 'PRICED',
    },
  ],
};

describe('CostClient', () => {
  it('租户报告按 OpenAPI 参数发送时间、维度与分页', async () => {
    const { fn, calls } = createFetch(() => json(TENANT_REPORT));
    const client = new CostClient({ baseUrl: 'http://api.test', fetchImpl: fn });

    const report = await client.getTenantCostReport({
      from: '2026-10-23',
      to: '2026-11-21',
      groupBy: 'USER',
      page: 2,
      size: 50,
    });
    expect(report.items[0]?.userDisplayName).toBe('王敏');
    const url = new URL(calls[0].url);
    expect(url.pathname).toBe('/api/v1/costs');
    expect(url.searchParams.get('from')).toBe('2026-10-23');
    expect(url.searchParams.get('to')).toBe('2026-11-21');
    expect(url.searchParams.get('groupBy')).toBe('USER');
    expect(url.searchParams.get('page')).toBe('2');
    expect(url.searchParams.get('size')).toBe('50');
  });

  it('平台报告使用 admin 端点，租户过滤可选且不发送空值', async () => {
    const { fn, calls } = createFetch(() => json(PLATFORM_REPORT));
    const client = new CostClient({ baseUrl: 'http://api.test', fetchImpl: fn });

    await client.getPlatformCostReport({
      from: '2026-11-01',
      to: '2026-11-21',
      groupBy: 'MODEL',
      tenantId: 'acme',
      page: 1,
      size: 20,
    });
    const url = new URL(calls[0].url);
    expect(url.pathname).toBe('/api/v1/admin/costs');
    expect(url.searchParams.get('tenantId')).toBe('acme');
    expect(url.searchParams.get('groupBy')).toBe('MODEL');

    await client.getPlatformCostReport({ groupBy: 'DAY' });
    expect(calls[1].url).toBe('http://api.test/api/v1/admin/costs?groupBy=DAY');
  });

  it('调用明细支持用户/模型过滤并读取价格快照', async () => {
    const { fn, calls } = createFetch(() => json(INVOCATION_PAGE));
    const client = new CostClient({ baseUrl: 'http://api.test', fetchImpl: fn });

    const page = await client.listTenantCostInvocations({
      from: '2026-10-23',
      to: '2026-11-21',
      userId: 'user-1',
      page: 1,
      size: 10,
    });
    expect(page.items[0]?.inputPricePer1M).toBe('3.000000');
    expect(page.items[0]?.amount).toBe('0.006000');
    const url = new URL(calls[0].url);
    expect(url.pathname).toBe('/api/v1/costs/invocations');
    expect(url.searchParams.get('userId')).toBe('user-1');
    expect(url.searchParams.has('modelId')).toBe(false);
  });

  it('401 触发会话过期回调；503 保留稳定错误码', async () => {
    const onUnauthorized = vi.fn();
    let requests = 0;
    const { fn } = createFetch(() => {
      requests += 1;
      return requests === 1 ? problem(401, 'RICCON-AUTH-401') : problem(503, 'RICCON-COST-503');
    });
    const client = new CostClient({
      baseUrl: 'http://api.test',
      fetchImpl: fn,
      onUnauthorized,
    });

    await expect(client.getTenantCostReport({ groupBy: 'DAY' })).rejects.toMatchObject({
      status: 401,
    } satisfies Partial<ApiError>);
    expect(onUnauthorized).toHaveBeenCalledTimes(1);

    await expect(client.getTenantCostReport({ groupBy: 'DAY' })).rejects.toMatchObject({
      status: 503,
      code: 'RICCON-COST-503',
    } satisfies Partial<ApiError>);
  });
});
