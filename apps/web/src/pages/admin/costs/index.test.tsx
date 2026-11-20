// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import type { SessionInfo } from '@/services/api/types';
import { authStore } from '@/services/auth/auth-store';
import type {
  CostApi,
  CostBucket,
  CostInvocationPage,
  PlatformCostReport,
  TenantCostReport,
} from '@/services/cost';
import { COST_DISCLAIMER, defaultCostRange } from '@/services/cost';
import { DEMO_SESSION } from '@/test/fake-api';
import { withProviders } from '@/test/render';
import CostsPage from './index';

/** 平台管理员会话：跨租户成本聚合。 */
const PLATFORM_ADMIN_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  user: { ...DEMO_SESSION.user, id: 'platform-admin', displayName: '赵蕾' },
  tenant: { ...DEMO_SESSION.tenant, id: 'platform', name: '平台' },
  roles: ['PLATFORM_ADMIN'],
  permissions: ['platform.cost.read'],
};

/** 租户管理员会话：本租户成本聚合与调用明细。 */
const TENANT_ADMIN_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  roles: ['TENANT_ADMIN'],
  permissions: [...DEMO_SESSION.permissions, 'cost.read'],
};

/** 公共合计。 */
const TOTALS = {
  currency: 'CNY',
  amount: '12.345600',
  pricedCalls: 3,
  unpricedCalls: 2,
  usageUnknownCalls: 1,
  currencyMismatchCalls: 0,
  unbucketedCalls: 4,
};

/**
 * 构造成本桶。
 *
 * @param overrides 局部覆盖
 * @returns 成本桶
 */
function makeBucket(overrides: Partial<CostBucket> = {}): CostBucket {
  return {
    key: 'acme',
    tenantId: 'acme',
    tenantCode: 'acme',
    amount: '12.345600',
    pricedCalls: 3,
    unpricedCalls: 2,
    usageUnknownCalls: 1,
    ...overrides,
  };
}

/**
 * 构造平台成本报告。
 *
 * @param groupBy 聚合维度
 * @returns 平台报告
 */
function makePlatformReport(groupBy: PlatformCostReport['groupBy'] = 'TENANT'): PlatformCostReport {
  const items: CostBucket[] =
    groupBy === 'MODEL'
      ? [makeBucket({ key: 'model-1', modelId: 'model-1', modelDisplayName: 'GPT' })]
      : groupBy === 'DAY'
        ? [makeBucket({ key: '2026-11-21', day: '2026-11-21', tenantId: null, tenantCode: null })]
        : [makeBucket(), makeBucket({ key: 'beta', tenantId: 'beta', tenantCode: 'beta' })];
  return {
    page: 1,
    size: 20,
    total: items.length,
    totalPages: 1,
    from: '2026-10-23',
    to: '2026-11-21',
    groupBy,
    currency: 'CNY',
    currencyMinorUnit: 2,
    disclaimer: COST_DISCLAIMER,
    totals: TOTALS,
    items,
  };
}

/**
 * 构造租户成本报告。
 *
 * @param groupBy 聚合维度
 * @returns 租户报告
 */
function makeTenantReport(groupBy: TenantCostReport['groupBy'] = 'USER'): TenantCostReport {
  const items: CostBucket[] =
    groupBy === 'MODEL'
      ? [makeBucket({ key: 'model-1', modelId: 'model-1', modelDisplayName: 'GPT' })]
      : groupBy === 'DAY'
        ? [makeBucket({ key: '2026-11-21', day: '2026-11-21' })]
        : [
            makeBucket({ key: 'user-1', userId: 'user-1', userDisplayName: '王敏' }),
            makeBucket({
              key: 'user-2',
              userId: 'user-2',
              userDisplayName: '李强',
              amount: '0.000000',
            }),
          ];
  return {
    page: 1,
    size: 20,
    total: items.length,
    totalPages: 1,
    from: '2026-10-23',
    to: '2026-11-21',
    groupBy,
    currency: 'CNY',
    currencyMinorUnit: 2,
    disclaimer: COST_DISCLAIMER,
    totals: TOTALS,
    items,
  };
}

/** 调用明细分页，含已定价与未定价两行。 */
function makeInvocationPage(): CostInvocationPage {
  return {
    page: 1,
    size: 10,
    total: 2,
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
      {
        id: 'inv-2',
        modelId: 'model-2',
        modelDisplayName: '未定价模型',
        userId: 'user-1',
        userDisplayName: '王敏',
        startedAt: '2026-11-21T10:31:00+08:00',
        promptTokens: 2000,
        completionTokens: 100,
        inputPricePer1M: null,
        outputPricePer1M: null,
        priceCurrency: null,
        amount: null,
        pricingStatus: 'UNPRICED',
      },
    ],
  };
}

/**
 * 构造成本 API 替身。
 *
 * @param overrides 局部覆盖
 * @returns CostApi 替身
 */
function createCostApi(overrides: Partial<CostApi> = {}): CostApi {
  return {
    getTenantCostReport: vi.fn(async (query) => makeTenantReport(query.groupBy)),
    getPlatformCostReport: vi.fn(async (query) => makePlatformReport(query.groupBy)),
    listTenantCostInvocations: vi.fn(async () => makeInvocationPage()),
    ...overrides,
  };
}

/**
 * 渲染成本页。
 *
 * @param api API 替身
 * @returns Testing Library 渲染结果
 */
function renderPage(api: CostApi) {
  return render(withProviders(<CostsPage api={api} />));
}

/**
 * 解析 rgb()/hex 颜色为 WCAG 相对亮度。
 *
 * @param color 浏览器计算后的颜色
 * @returns 相对亮度（0–1）
 */
function relativeLuminance(color: string): number {
  const channels = color.startsWith('rgb')
    ? (color.match(/\d+/g) ?? []).slice(0, 3).map(Number)
    : [1, 3, 5].map((offset) => Number.parseInt(color.slice(offset, offset + 2), 16));
  const [r, g, b] = channels.map((value) => {
    const channel = value / 255;
    return channel <= 0.03928 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/**
 * 断言 Tag 前背景对比度达到 WCAG 2.1 AA（12px 正文 4.5:1）。
 *
 * @param tag 定价状态 Tag
 */
function expectAATagContrast(tag: HTMLElement): void {
  const { color, backgroundColor } = tag.style;
  expect(color).not.toBe('');
  expect(backgroundColor).not.toBe('');
  const [lighter, darker] = [relativeLuminance(color), relativeLuminance(backgroundColor)].sort(
    (a, b) => b - a,
  );
  expect((lighter + 0.05) / (darker + 0.05)).toBeGreaterThanOrEqual(4.5);
}

describe('成本页', () => {
  beforeEach(() => {
    authStore.reset();
    authStore.setAuthenticated(PLATFORM_ADMIN_SESSION);
  });

  it('平台管理员按租户聚合：6 位金额/2 位展示、固定文案、未定价/未知/未分桶单列', async () => {
    const api = createCostApi();
    renderPage(api);

    expect(await screen.findByText('acme')).toBeVisible();
    expect(screen.getByText('beta')).toBeVisible();
    expect(screen.getAllByText('CNY 12.35').length).toBeGreaterThan(0);
    expect(screen.getAllByText('精确值 12.345600 CNY').length).toBeGreaterThan(0);
    expect(screen.getByText(/成本为按调用时价格快照估算/)).toBeVisible();
    expect(screen.getByText(/未定价调用（单列，不计入金额）/)).toBeVisible();
    expect(screen.getByText(/用量未知调用（单列，不计入金额）/)).toBeVisible();
    expect(screen.getByText(/未分桶调用（回滚窗口\/未回填，不计入金额）/)).toBeVisible();
    const totalsCard = screen.getByText('合计（按租户）').closest('.ant-card');
    expect(totalsCard).not.toBeNull();
    expect(within(totalsCard as HTMLElement).getByText('2')).toBeVisible();
    expect(within(totalsCard as HTMLElement).getByText('4')).toBeVisible();
    // 平台不出现用户维度明细与展开按钮。
    expect(screen.queryByRole('button', { name: /查看明细/ })).not.toBeInTheDocument();
    expect(screen.getByText(/平台视图只到租户\/模型\/日聚合/)).toBeVisible();

    const query = vi.mocked(api.getPlatformCostReport).mock.calls[0]?.[0];
    expect(query).toMatchObject({ groupBy: 'TENANT', page: 1, size: 20 });
    expect(query?.from).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(query?.to).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });

  it('切换聚合维度后按新维度重新查询并回到第一页', async () => {
    const api = createCostApi();
    renderPage(api);
    await screen.findByText('acme');

    fireEvent.click(screen.getByText('按模型'));
    await waitFor(() =>
      expect(api.getPlatformCostReport).toHaveBeenLastCalledWith(
        expect.objectContaining({ groupBy: 'MODEL', page: 1 }),
      ),
    );
    expect(await screen.findByText('GPT')).toBeVisible();
  });

  it('时间范围超过 180 天时前端拒绝查询并保留上一次结果', async () => {
    const api = createCostApi();
    renderPage(api);
    await screen.findByText('acme');
    const callsBefore = vi.mocked(api.getPlatformCostReport).mock.calls.length;

    fireEvent.change(screen.getByLabelText('开始日期（Asia/Shanghai）'), {
      target: { value: '2025-01-01' },
    });
    fireEvent.change(screen.getByLabelText('结束日期（Asia/Shanghai）'), {
      target: { value: '2026-11-21' },
    });
    fireEvent.click(screen.getByRole('button', { name: '查询' }));

    expect(await screen.findByText(/可查范围不能超过 180 天/)).toBeVisible();
    expect(api.getPlatformCostReport).toHaveBeenCalledTimes(callsBefore);
    expect(screen.getByText('acme')).toBeVisible();
  });

  it('空结果给出空态与“查看近 30 天”入口', async () => {
    const api = createCostApi({
      getPlatformCostReport: vi.fn(async (query) => ({
        ...makePlatformReport(query.groupBy),
        total: 0,
        totalPages: 0,
        items: [],
      })),
    });
    renderPage(api);

    expect(await screen.findByText(/所选时间范围内没有调用记录/)).toBeVisible();
    expect(screen.getByRole('link', { name: '前往模型路由' })).toHaveAttribute(
      'href',
      '/admin/model-routing',
    );
    const callsBefore = vi.mocked(api.getPlatformCostReport).mock.calls.length;
    fireEvent.click(screen.getByRole('button', { name: '重新查询' }));
    await waitFor(() =>
      expect(vi.mocked(api.getPlatformCostReport).mock.calls.length).toBeGreaterThan(callsBefore),
    );
  });

  it('403 显示无权限态；加载失败显示错误态并可重试', async () => {
    const forbiddenApi = createCostApi({
      getPlatformCostReport: vi.fn(async () => {
        throw new ApiError('forbidden', { status: 403, code: 'RICCON-AUTH-403' });
      }),
    });
    const { unmount } = renderPage(forbiddenApi);
    expect(await screen.findByText('没有访问权限')).toBeVisible();
    expect(screen.getByText(/员工无入口/)).toBeVisible();
    unmount();

    let attempts = 0;
    const flakyApi = createCostApi({
      getPlatformCostReport: vi.fn(async () => {
        attempts += 1;
        if (attempts === 1) {
          throw new ApiError('server', { status: 500, code: 'RICCON-COMMON-500' });
        }
        return makePlatformReport();
      }),
    });
    renderPage(flakyApi);
    expect(await screen.findByText('成本报告加载失败')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(await screen.findByText('acme')).toBeVisible();
  });

  it('租户管理员按用户聚合，展开分组后显示调用价格快照与未定价状态', async () => {
    authStore.reset();
    authStore.setAuthenticated(TENANT_ADMIN_SESSION);
    const api = createCostApi();
    renderPage(api);

    expect(await screen.findByText('王敏')).toBeVisible();
    expect(screen.getByText('李强')).toBeVisible();
    expect(api.getTenantCostReport).toHaveBeenCalledWith(
      expect.objectContaining({ groupBy: 'USER', page: 1, size: 20 }),
    );
    expect(api.getPlatformCostReport).not.toHaveBeenCalled();
    expect(screen.getByText(/可展开任一分组查看该次调用的输入\/输出价格快照/)).toBeVisible();

    fireEvent.click(screen.getByRole('button', { name: '查看明细：王敏' }));
    expect(await screen.findByText('3.000000')).toBeVisible();
    expect(screen.getByText('6.000000')).toBeVisible();
    expect(screen.getByText('CNY 0.01')).toBeVisible();
    expect(screen.getByText('不计入金额')).toBeVisible();
    const query = vi.mocked(api.listTenantCostInvocations).mock.calls[0]?.[0];
    expect(query).toMatchObject({ page: 1, size: 10, userId: 'user-1' });
    expect(query?.from).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });

  it('展开按钮可访问名称以可见文字开头，已定价 Tag 对比度达到 AA', async () => {
    authStore.reset();
    authStore.setAuthenticated(TENANT_ADMIN_SESSION);
    const api = createCostApi();
    renderPage(api);

    const expandButton = await screen.findByRole('button', { name: '查看明细：王敏' });
    expect(expandButton.textContent?.trim()).toBe('查看明细');
    expect((expandButton.getAttribute('aria-label') ?? '').startsWith('查看明细')).toBe(true);

    fireEvent.click(expandButton);
    const pricedTag = (await screen.findByText('已定价')).closest('.ant-tag');
    expect(pricedTag).not.toBeNull();
    expectAATagContrast(pricedTag as HTMLElement);
    const unpricedTag = screen.getByText('未定价').closest('.ant-tag');
    expect(unpricedTag).not.toBeNull();
    expectAATagContrast(unpricedTag as HTMLElement);
  });

  it('按日聚合展开时用该日作为明细查询范围', async () => {
    authStore.reset();
    authStore.setAuthenticated(TENANT_ADMIN_SESSION);
    const api = createCostApi();
    renderPage(api);
    await screen.findByText('王敏');

    fireEvent.click(screen.getByText('按日'));
    await waitFor(() =>
      expect(api.getTenantCostReport).toHaveBeenLastCalledWith(
        expect.objectContaining({ groupBy: 'DAY' }),
      ),
    );
    fireEvent.click(await screen.findByRole('button', { name: '查看明细：2026-11-21' }));
    await waitFor(() =>
      expect(api.listTenantCostInvocations).toHaveBeenCalledWith(
        expect.objectContaining({ from: '2026-11-21', to: '2026-11-21' }),
      ),
    );
  });

  it('租户默认时间范围是最近 30 天（Asia/Shanghai）', () => {
    expect(defaultCostRange(30, new Date('2026-11-20T16:30:00Z'))).toEqual({
      from: '2026-10-23',
      to: '2026-11-21',
    });
  });

  it('员工没有成本权限时直接 403 且不请求数据', () => {
    authStore.reset();
    authStore.setAuthenticated(DEMO_SESSION);
    const api = createCostApi();
    renderPage(api);

    expect(screen.getByText('没有访问权限')).toBeVisible();
    expect(screen.getByText(/成本看板仅向平台管理员与租户管理员开放/)).toBeVisible();
    expect(api.getTenantCostReport).not.toHaveBeenCalled();
    expect(api.getPlatformCostReport).not.toHaveBeenCalled();
    expect(api.listTenantCostInvocations).not.toHaveBeenCalled();
  });
});
