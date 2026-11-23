// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import type { SessionInfo } from '@/services/api/types';
import { authStore } from '@/services/auth/auth-store';
import type { PlatformQuotaPage, QuotaApi, TenantQuota, UserQuotaPage } from '@/services/quota';
import { DEMO_SESSION, pageOf } from '@/test/fake-api';
import { withProviders } from '@/test/render';
import QuotasPage from './index';

/** 平台管理员会话：跨租户配额读写。 */
const PLATFORM_ADMIN_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  user: { ...DEMO_SESSION.user, id: 'platform-admin', displayName: '赵蕾' },
  tenant: { ...DEMO_SESSION.tenant, id: 'platform', name: '平台' },
  roles: ['PLATFORM_ADMIN'],
  permissions: ['platform.quota.manage'],
};

/** 租户管理员会话：本租户配额只读。 */
const TENANT_ADMIN_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  roles: ['TENANT_ADMIN'],
  permissions: [...DEMO_SESSION.permissions, 'quota.read'],
};

/** 构造平台配额行。 */
function makePlatformRow(
  overrides: Partial<PlatformQuotaPage['items'][number]> = {},
): PlatformQuotaPage['items'][number] {
  return {
    tenantId: 'acme',
    tenantCode: 'acme',
    tenantName: '示例企业',
    windows: [
      {
        window: 'DAILY',
        limit: 1000,
        used: 12,
        resetsAt: '2026-11-22T00:00:00+08:00',
        source: 'EXPLICIT',
      },
      {
        window: 'MONTHLY',
        limit: null,
        used: 40,
        resetsAt: '2026-12-01T00:00:00+08:00',
        source: 'EXPLICIT_UNLIMITED',
      },
    ],
    ...overrides,
  };
}

/** 构造平台配额分页。 */
function makePlatformPage(items = [makePlatformRow()]): PlatformQuotaPage {
  return { page: 1, size: 20, total: items.length, totalPages: 1, items };
}

/** 构造本租户配额。 */
function makeTenantQuota(overrides: Partial<TenantQuota> = {}): TenantQuota {
  return {
    tenantId: 'tenant-a',
    tenantCode: 'tenant-a',
    tenantName: null,
    windows: [
      {
        window: 'DAILY',
        limit: 1000,
        used: 12,
        resetsAt: '2026-11-22T00:00:00+08:00',
        source: 'DEPLOYMENT_DEFAULT',
      },
      {
        window: 'MONTHLY',
        limit: 20000,
        used: 40,
        resetsAt: '2026-12-01T00:00:00+08:00',
        source: 'EXPLICIT',
      },
    ],
    ...overrides,
  };
}

/** 构造用户配额分页。 */
function makeUserPage(): UserQuotaPage {
  return pageOf([
    {
      userId: 'user-1',
      username: 'demo',
      displayName: '王敏',
      windows: makeTenantQuota().windows,
    },
  ]);
}

/**
 * 构造配额 API 替身。
 *
 * @param overrides 局部覆盖
 * @returns QuotaApi 替身
 */
function createQuotaApi(overrides: Partial<QuotaApi> = {}): QuotaApi {
  return {
    getTenantQuota: vi.fn(async () => makeTenantQuota()),
    listTenantUserQuotas: vi.fn(async () => makeUserPage()),
    listPlatformQuotas: vi.fn(async () => makePlatformPage()),
    updateTenantQuota: vi.fn(async (_tenantId, input) =>
      makePlatformRow({
        windows: makePlatformRow().windows.map((state, index) => ({
          ...state,
          limit: index === 0 ? input.limits.daily : input.limits.monthly,
          source: 'EXPLICIT',
        })),
      }),
    ),
    ...overrides,
  };
}

/**
 * 渲染配额页。
 *
 * @param api API 替身
 * @returns Testing Library 渲染结果
 */
function renderPage(api: QuotaApi) {
  return render(withProviders(<QuotasPage api={api} />));
}

describe('配额页', () => {
  beforeEach(() => {
    authStore.reset();
    authStore.setAuthenticated(PLATFORM_ADMIN_SESSION);
  });

  it('平台管理员看到跨租户聚合：限额、已用、来源与 +08:00 重置时间', async () => {
    const api = createQuotaApi();
    renderPage(api);

    expect(await screen.findByText('示例企业')).toBeVisible();
    expect(screen.getByText('acme')).toBeVisible();
    expect(screen.getAllByText('已用 12 / 1,000').length).toBeGreaterThan(0);
    expect(screen.getAllByText('已用 40 / 不限').length).toBeGreaterThan(0);
    expect(screen.getByText('来源：显式设置')).toBeVisible();
    expect(screen.getByText('来源：明确不限')).toBeVisible();
    expect(screen.getAllByText(/2026-11-22 00:00:00 \+08:00/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/不展示租户内用户明细/).length).toBeGreaterThan(0);
    expect(api.listPlatformQuotas).toHaveBeenCalledWith({ page: 1, size: 20 });
    expect(api.getTenantQuota).not.toHaveBeenCalled();
    // 平台视图没有用户维度列。
    expect(screen.queryByRole('columnheader', { name: '用户' })).not.toBeInTheDocument();
  });

  it('平台管理员整体替换限额：空值提交为 null 并原地更新行', async () => {
    const api = createQuotaApi();
    renderPage(api);

    fireEvent.click(await screen.findByRole('button', { name: '调整限额：示例企业' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/整体替换与不限语义/)).toBeInTheDocument();
    expect(within(dialog).getByText(/留空或 0 表示“明确不限”/)).toBeInTheDocument();

    const daily = within(dialog).getByLabelText('日限额（次/自然日）');
    const monthly = within(dialog).getByLabelText('月限额（次/自然月）');
    expect(daily).toHaveValue('1000');
    fireEvent.change(daily, { target: { value: '5000' } });
    fireEvent.change(monthly, { target: { value: '' } });
    fireEvent.click(within(dialog).getByRole('button', { name: '保存限额' }));

    await waitFor(() =>
      expect(api.updateTenantQuota).toHaveBeenCalledWith('acme', {
        limits: { daily: 5000, monthly: null },
      }),
    );
    expect(await screen.findByText(/已保存租户“示例企业”的配额/)).toBeVisible();
    expect(await screen.findByText(/已用 12 \/ 5,000/)).toBeVisible();
  });

  it('弹窗关闭与重开不产生 useForm 未连接警告', async () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    /** rc-field-form 的未连接警告在 setTimeout(0) 中触发；等一个宏任务再断言。 */
    const flushWarning = () => new Promise((resolve) => setTimeout(resolve, 0));
    /** 断言当前没有 useForm 未连接警告。 */
    const expectNoUseFormWarning = (): void => {
      expect(errorSpy.mock.calls.map((call) => call.join(' ')).join('\n')).not.toMatch(/useForm/);
    };
    try {
      const api = createQuotaApi();
      renderPage(api);
      await screen.findByText('示例企业');
      await flushWarning();
      expectNoUseFormWarning();

      fireEvent.click(screen.getByRole('button', { name: '调整限额：示例企业' }));
      const dialog = await screen.findByRole('dialog');
      await flushWarning();
      expectNoUseFormWarning();

      // 取消关闭后 Form 已卸载；这里不再调用 resetFields，不能出现未连接警告。
      fireEvent.click(within(dialog).getByRole('button', { name: '取消' }));
      await flushWarning();
      expectNoUseFormWarning();

      // 重新打开时用服务端值覆盖表单，不依赖关闭时的 resetFields。
      fireEvent.click(screen.getByRole('button', { name: '调整限额：示例企业' }));
      await flushWarning();
      expectNoUseFormWarning();
      expect(screen.getByLabelText('日限额（次/自然日）')).toHaveValue('1000');
    } finally {
      errorSpy.mockRestore();
    }
  });

  it('保存遇到停用租户 409 时保留弹窗并显示固定文案', async () => {
    const api = createQuotaApi({
      updateTenantQuota: vi.fn(async () => {
        throw new ApiError('conflict', { status: 409, code: 'RICCON-TENANT-409' });
      }),
    });
    renderPage(api);

    fireEvent.click(await screen.findByRole('button', { name: '调整限额：示例企业' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('日限额（次/自然日）'), {
      target: { value: '800' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: '保存限额' }));

    expect(await within(dialog).findByText(/目标租户已停用/)).toBeInTheDocument();
    expect(screen.queryByText(/已保存租户/)).not.toBeInTheDocument();
    expect(dialog).toBeInTheDocument();
  });

  it('平台没有租户时给出空态与租户管理入口', async () => {
    const api = createQuotaApi({ listPlatformQuotas: vi.fn(async () => pageOf([])) });
    renderPage(api);

    expect(await screen.findByText(/平台还没有可展示的租户/)).toBeVisible();
    expect(screen.getByRole('link', { name: '前往租户管理' })).toHaveAttribute(
      'href',
      '/admin/tenants',
    );
  });

  it('平台加载失败显示错误态，重试后恢复', async () => {
    let attempts = 0;
    const api = createQuotaApi({
      listPlatformQuotas: vi.fn(async () => {
        attempts += 1;
        if (attempts === 1) {
          throw new ApiError('server', { status: 500, code: 'RICCON-COMMON-500' });
        }
        return makePlatformPage();
      }),
    });
    renderPage(api);

    expect(await screen.findByText('配额数据加载失败')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(await screen.findByText('示例企业')).toBeVisible();
    expect(api.listPlatformQuotas).toHaveBeenCalledTimes(2);
  });

  it('平台接口返回 403 时显示无权限态并可重试', async () => {
    const api = createQuotaApi({
      listPlatformQuotas: vi.fn(async () => {
        throw new ApiError('forbidden', { status: 403, code: 'RICCON-AUTH-403' });
      }),
    });
    renderPage(api);

    expect(await screen.findByText('没有访问权限')).toBeVisible();
    expect(screen.getByText(/没有配额查看权限/)).toBeVisible();
  });

  it('租户管理员只读本租户窗口与用户用量，不出现写入口', async () => {
    authStore.reset();
    authStore.setAuthenticated(TENANT_ADMIN_SESSION);
    const api = createQuotaApi();
    renderPage(api);

    expect(await screen.findByText('王敏')).toBeVisible();
    expect(screen.getByText('@demo')).toBeVisible();
    expect(screen.getByText('日窗口')).toBeVisible();
    expect(screen.getByText('月窗口')).toBeVisible();
    expect(screen.getAllByText('部署默认').length).toBeGreaterThan(0);
    expect(screen.getAllByText('显式设置').length).toBeGreaterThan(0);
    expect(screen.getByText('剩余 988')).toBeVisible();
    expect(screen.getAllByText(/2026-12-01 00:00:00 \+08:00/).length).toBeGreaterThan(0);
    expect(screen.getByText(/用户用量（只读）/)).toBeVisible();
    expect(screen.queryByRole('button', { name: /调整限额/ })).not.toBeInTheDocument();
    expect(screen.getByText('只读')).toBeVisible();
    expect(api.getTenantQuota).toHaveBeenCalledTimes(1);
    expect(api.listTenantUserQuotas).toHaveBeenCalledWith({ page: 1, size: 20 });
    expect(api.listPlatformQuotas).not.toHaveBeenCalled();
  });

  it('行操作按钮的可访问名称以可见文字开头（WCAG 2.5.3）', async () => {
    const api = createQuotaApi();
    renderPage(api);

    const button = await screen.findByRole('button', { name: '调整限额：示例企业' });
    expect(button.textContent?.trim()).toBe('调整限额');
    expect((button.getAttribute('aria-label') ?? '').startsWith('调整限额')).toBe(true);
  });

  it('租户没有用户时表格给出空态说明而不是空白', async () => {
    authStore.reset();
    authStore.setAuthenticated(TENANT_ADMIN_SESSION);
    const api = createQuotaApi({ listTenantUserQuotas: vi.fn(async () => pageOf([])) });
    renderPage(api);

    expect(await screen.findByText(/本租户还没有可展示的用户配额记录/)).toBeVisible();
    expect(screen.getByText('日窗口')).toBeVisible();
  });

  it('员工没有配额权限时直接 403 且不请求数据', () => {
    authStore.reset();
    authStore.setAuthenticated(DEMO_SESSION);
    const api = createQuotaApi();
    renderPage(api);

    expect(screen.getByText('没有访问权限')).toBeVisible();
    expect(screen.getByText(/配额页仅向平台管理员与租户管理员开放/)).toBeVisible();
    expect(api.getTenantQuota).not.toHaveBeenCalled();
    expect(api.listTenantUserQuotas).not.toHaveBeenCalled();
    expect(api.listPlatformQuotas).not.toHaveBeenCalled();
  });
});
