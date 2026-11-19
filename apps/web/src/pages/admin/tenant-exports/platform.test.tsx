// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import { authStore } from '@/services/auth/auth-store';
import { withProviders } from '@/test/render';
import {
  PLATFORM_ADMIN_SESSION,
  TENANT_ADMIN_SESSION,
  createAuditApi,
  pageOf,
} from '../../../../tests/m10-audit/fake';
import PlatformAuditExportsPage from './platform';

/** 渲染平台审批页。 */
function renderPage(
  api = createAuditApi(),
  props: { tenantId?: string; navigate?: (to: string) => void } = { tenantId: 'acme' },
) {
  return render(
    withProviders(<PlatformAuditExportsPage api={api} navigate={vi.fn()} {...props} />),
  );
}

describe('平台审计导出审批页', () => {
  beforeEach(() => {
    authStore.reset();
    authStore.setAuthenticated(PLATFORM_ADMIN_SESSION);
  });

  it('租户管理员没有平台审批权限时 403', () => {
    authStore.reset();
    authStore.setAuthenticated(TENANT_ADMIN_SESSION);
    const api = createAuditApi();
    renderPage(api);

    expect(screen.getByText('没有访问权限')).toBeVisible();
    expect(screen.getByText(/只有平台管理员/)).toBeVisible();
    expect(api.listPlatformExports).not.toHaveBeenCalled();
  });

  it('展示单管理员租户的申请元数据，不提供下载与审计内容', async () => {
    const api = createAuditApi();
    renderPage(api);

    expect(await screen.findByText('导出申请元数据')).toBeVisible();
    expect(await screen.findByText(/时间 2026-11-01/)).toBeVisible();
    expect(screen.getByText(/平台管理员只查看申请人/)).toBeVisible();
    expect(screen.getByText('120')).toBeVisible();

    expect(screen.queryByRole('button', { name: /下载/ })).not.toBeInTheDocument();
    expect(screen.queryByText(/查看审计内容/)).not.toBeInTheDocument();
    expect(api.listPlatformExports).toHaveBeenCalledWith(
      'acme',
      expect.objectContaining({ status: 'PENDING_APPROVAL', page: 1, size: 20 }),
    );
  });

  it('批准目标租户导出并刷新列表', async () => {
    const api = createAuditApi();
    renderPage(api);

    fireEvent.click(await screen.findByRole('button', { name: '批准导出申请 export-1' }));
    const modal = await screen.findByRole('dialog');
    expect(within(modal).getByText(/平台管理员不能下载该文件/)).toBeInTheDocument();
    fireEvent.click(within(modal).getByRole('button', { name: '确认批准' }));

    await waitFor(() => {
      expect(api.decidePlatformExport).toHaveBeenCalledWith('acme', 'export-1', {
        decision: 'APPROVE',
        comment: null,
      });
    });
    expect(await screen.findByText(/已批准目标租户的导出申请/)).toBeVisible();
    expect(api.listPlatformExports).toHaveBeenCalledTimes(2);
  });

  it('拒绝时提交审批意见', async () => {
    const api = createAuditApi();
    renderPage(api);

    fireEvent.click(await screen.findByRole('button', { name: '拒绝导出申请 export-1' }));
    const modal = await screen.findByRole('dialog');
    fireEvent.change(within(modal).getByLabelText('审批意见'), {
      target: { value: '请申请人补充说明用途' },
    });
    fireEvent.click(within(modal).getByRole('button', { name: '确认拒绝' }));

    await waitFor(() => {
      expect(api.decidePlatformExport).toHaveBeenCalledWith('acme', 'export-1', {
        decision: 'REJECT',
        comment: '请申请人补充说明用途',
      });
    });
  });

  it('多管理员租户 409 展示平台不能代审的说明', async () => {
    renderPage(
      createAuditApi({
        listPlatformExports: vi.fn(async () => {
          throw new ApiError('conflict', {
            status: 409,
            code: 'RICCON-AUDIT-409',
          });
        }),
      }),
    );

    expect(await screen.findByText('该租户不能由平台审批')).toBeVisible();
    expect(screen.getByText(/多个启用的租户管理员/)).toBeVisible();
    expect(screen.queryByRole('button', { name: '批准导出申请 export-1' })).not.toBeInTheDocument();
  });

  it('目标租户不存在显示 404', async () => {
    renderPage(
      createAuditApi({
        listPlatformExports: vi.fn(async () => {
          throw new ApiError('not found', {
            status: 404,
            code: 'RICCON-TENANT-404',
          });
        }),
      }),
    );

    expect(await screen.findByText('目标租户不存在')).toBeVisible();
  });

  it('没有租户 ID 时先输入租户代码再加载', async () => {
    const api = createAuditApi();
    const navigate = vi.fn();
    renderPage(api, { navigate });

    expect(screen.getByText('选择目标租户')).toBeVisible();
    expect(api.listPlatformExports).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText('租户代码'), {
      target: { value: 'acme' },
    });
    fireEvent.click(screen.getByRole('button', { name: '加载审批列表' }));

    await waitFor(() => {
      expect(navigate).toHaveBeenCalledWith('/admin/tenants/acme/audit-exports');
    });
    expect(api.listPlatformExports).toHaveBeenCalledWith(
      'acme',
      expect.objectContaining({ status: 'PENDING_APPROVAL' }),
    );
  });

  it('平台租户自身导出直接 403 且不请求审批列表', () => {
    const api = createAuditApi();
    renderPage(api, { tenantId: 'platform' });

    expect(screen.getByText('平台租户不支持导出审批')).toBeVisible();
    expect(api.listPlatformExports).not.toHaveBeenCalled();
  });

  it('详情抽屉明确平台审批只处理元数据', async () => {
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: '查看导出元数据 export-1' }));
    const drawer = await screen.findByRole('dialog');
    expect(within(drawer).getByText(/平台审批只处理元数据/)).toBeVisible();
    expect(within(drawer).getByText(/不含密码哈希/)).toBeVisible();
  });

  it('列表空态不显示审批按钮', async () => {
    renderPage(createAuditApi({ listPlatformExports: vi.fn(async () => pageOf([])) }));

    expect(await screen.findByText(/当前没有符合条件的导出申请/)).toBeVisible();
    expect(screen.queryByRole('button', { name: '批准导出申请 export-1' })).not.toBeInTheDocument();
  });
});
