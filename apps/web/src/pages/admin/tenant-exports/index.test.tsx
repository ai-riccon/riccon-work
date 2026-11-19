// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import { authStore } from '@/services/auth/auth-store';
import { withProviders } from '@/test/render';
import {
  MEMBER_SESSION,
  PLATFORM_ADMIN_SESSION,
  TENANT_ADMIN_SESSION,
  createAuditApi,
  makeExport,
  pageOf,
} from '../../../../tests/m10-audit/fake';
import TenantExportsPage from './index';

/** Blob URL 替身类型；jsdom 不实现 createObjectURL。 */
interface UrlWithBlob {
  createObjectURL: (blob: Blob) => string;
  revokeObjectURL: (url: string) => void;
}

/** 渲染导出页。 */
function renderPage(api = createAuditApi()) {
  return render(withProviders(<TenantExportsPage api={api} />));
}

/** 构造 409。 */
function conflict(code = 'RICCON-AUDIT-409'): ApiError {
  return new ApiError('internal', { status: 409, code });
}

describe('审计导出页', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    authStore.reset();
    authStore.setAuthenticated(TENANT_ADMIN_SESSION);
    (URL as unknown as UrlWithBlob).createObjectURL = vi.fn(() => 'blob:test');
    (URL as unknown as UrlWithBlob).revokeObjectURL = vi.fn();
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
  });

  it('列表展示申请条件、预计行数、状态与下载截止', async () => {
    renderPage();

    expect(await screen.findByText('导出申请')).toBeVisible();
    expect(await screen.findByText(/时间 2026-11-01/)).toBeVisible();
    expect(screen.getByText('120')).toBeVisible();
    expect(screen.getAllByText('待审批').length).toBeGreaterThan(0);
    expect(screen.getByText('审计导出')).toBeVisible();
  });

  it('自己的待审批申请没有自批入口，并提示需另一位管理员', async () => {
    renderPage();

    expect(await screen.findByText(/申请人不能审批自己的导出申请/)).toBeVisible();
    expect(screen.queryByRole('button', { name: '批准导出申请 export-1' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '拒绝导出申请 export-1' })).not.toBeInTheDocument();
  });

  it('其他管理员的待审批申请可批准，审批后刷新列表', async () => {
    const api = createAuditApi({
      listExports: vi.fn(async () =>
        pageOf([
          makeExport({
            id: 'export-2',
            requestedBy: 'user-2',
            requestedByDisplayName: '李强',
          }),
        ]),
      ),
    });
    renderPage(api);

    const approve = await screen.findByRole('button', { name: '批准导出申请 export-2' });
    fireEvent.click(approve);
    const modal = await screen.findByRole('dialog');
    fireEvent.click(within(modal).getByRole('button', { name: '确认批准' }));

    await waitFor(() => {
      expect(api.decideExport).toHaveBeenCalledWith('export-2', {
        decision: 'APPROVE',
        comment: null,
      });
    });
    expect(await screen.findByText(/导出申请已批准/)).toBeVisible();
    expect(api.listExports).toHaveBeenCalledTimes(2);
  });

  it('拒绝时可填写原因并提交 REJECT', async () => {
    const api = createAuditApi({
      listExports: vi.fn(async () =>
        pageOf([
          makeExport({
            id: 'export-2',
            requestedBy: 'user-2',
            requestedByDisplayName: '李强',
          }),
        ]),
      ),
      decideExport: vi.fn(async () =>
        makeExport({ id: 'export-2', status: 'REJECTED', decisionComment: '范围过大' }),
      ),
    });
    renderPage(api);

    fireEvent.click(await screen.findByRole('button', { name: '拒绝导出申请 export-2' }));
    const modal = await screen.findByRole('dialog');
    fireEvent.change(within(modal).getByLabelText('审批意见'), {
      target: { value: '范围过大，请缩小时间范围' },
    });
    fireEvent.click(within(modal).getByRole('button', { name: '确认拒绝' }));

    await waitFor(() => {
      expect(api.decideExport).toHaveBeenCalledWith('export-2', {
        decision: 'REJECT',
        comment: '范围过大，请缩小时间范围',
      });
    });
    expect(await screen.findByText(/导出申请已拒绝/)).toBeVisible();
  });

  it('仅申请人本人可下载已批准导出', async () => {
    const api = createAuditApi({
      listExports: vi.fn(async () =>
        pageOf([
          makeExport({
            status: 'APPROVED',
            expiresAt: '2099-01-01T00:00:00+08:00',
            approvedBy: 'user-2',
            approvedByDisplayName: '李强',
          }),
        ]),
      ),
    });
    renderPage(api);

    const download = await screen.findByRole('button', { name: '下载导出 export-1' });
    fireEvent.click(download);

    await waitFor(() => {
      expect(api.downloadExport).toHaveBeenCalledWith('export-1');
    });
    expect(URL.createObjectURL).toHaveBeenCalled();
    expect(await screen.findByText(/已开始下载/)).toBeVisible();
  });

  it('非申请人看不到下载入口', async () => {
    renderPage(
      createAuditApi({
        listExports: vi.fn(async () =>
          pageOf([
            makeExport({
              status: 'APPROVED',
              requestedBy: 'user-2',
              requestedByDisplayName: '李强',
              expiresAt: '2099-01-01T00:00:00+08:00',
            }),
          ]),
        ),
      }),
    );

    expect(await screen.findByText(/仅申请人本人可下载/)).toBeVisible();
    expect(screen.queryByRole('button', { name: '下载导出 export-1' })).not.toBeInTheDocument();
  });

  it('过期申请显示过期文案且没有下载按钮', async () => {
    renderPage(
      createAuditApi({
        listExports: vi.fn(async () => pageOf([makeExport({ status: 'EXPIRED' })])),
      }),
    );

    expect(await screen.findByText(/已过期，请重新提交导出申请/)).toBeVisible();
    expect(screen.queryByRole('button', { name: '下载导出 export-1' })).not.toBeInTheDocument();
  });

  it('下载遇到 410 显示过期原因并刷新列表', async () => {
    const api = createAuditApi({
      listExports: vi.fn(async () =>
        pageOf([
          makeExport({
            status: 'APPROVED',
            expiresAt: '2099-01-01T00:00:00+08:00',
          }),
        ]),
      ),
      downloadExport: vi.fn(async () => {
        throw new ApiError('expired', { status: 410, code: 'RICCON-AUDIT-410' });
      }),
    });
    renderPage(api);

    fireEvent.click(await screen.findByRole('button', { name: '下载导出 export-1' }));

    expect(await screen.findByText(/导出已过期，请重新提交导出申请/)).toBeVisible();
    await waitFor(() => {
      expect(api.listExports).toHaveBeenCalledTimes(2);
    });
  });

  it('审批 409 在弹窗内显示状态冲突，不泄露英文内部说明', async () => {
    const api = createAuditApi({
      listExports: vi.fn(async () =>
        pageOf([
          makeExport({
            id: 'export-2',
            requestedBy: 'user-2',
            requestedByDisplayName: '李强',
          }),
        ]),
      ),
      decideExport: vi.fn(async () => {
        throw conflict();
      }),
    });
    renderPage(api);

    fireEvent.click(await screen.findByRole('button', { name: '批准导出申请 export-2' }));
    const modal = await screen.findByRole('dialog');
    fireEvent.click(within(modal).getByRole('button', { name: '确认批准' }));

    expect(await within(modal).findByText(/导出状态已变化/)).toBeInTheDocument();
    expect(within(modal).queryByText('internal')).not.toBeInTheDocument();
  });

  it('平台租户直接展示导出不支持，不请求导出接口', () => {
    authStore.reset();
    authStore.setAuthenticated(PLATFORM_ADMIN_SESSION);
    const api = createAuditApi();
    renderPage(api);

    expect(screen.getByText('平台租户不支持导出')).toBeVisible();
    expect(api.listExports).not.toHaveBeenCalled();
  });

  it('普通员工没有审计权限时 403', () => {
    authStore.reset();
    authStore.setAuthenticated(MEMBER_SESSION);
    const api = createAuditApi();
    renderPage(api);

    expect(screen.getByText('没有访问权限')).toBeVisible();
    expect(api.listExports).not.toHaveBeenCalled();
  });

  it('空列表展示空态与去审计查询入口', async () => {
    renderPage(createAuditApi({ listExports: vi.fn(async () => pageOf([])) }));

    expect(await screen.findByText(/当前没有导出申请/)).toBeVisible();
    expect(screen.getAllByRole('button', { name: '去审计查询' }).length).toBeGreaterThan(0);
  });

  it('按状态过滤后重新请求列表', async () => {
    const api = createAuditApi();
    renderPage(api);

    await screen.findByText('导出申请');
    fireEvent.mouseDown(screen.getByRole('combobox', { name: '导出状态筛选' }));
    fireEvent.click(await screen.findByText('已拒绝'));

    await waitFor(() => {
      expect(api.listExports).toHaveBeenLastCalledWith(
        expect.objectContaining({ status: 'REJECTED', page: 1 }),
      );
    });
  });

  it('详情抽屉展示条件摘要、审批人与意见', async () => {
    renderPage(
      createAuditApi({
        listExports: vi.fn(async () =>
          pageOf([
            makeExport({
              status: 'REJECTED',
              decisionComment: '请缩小范围',
              approvedBy: 'user-2',
              approvedByDisplayName: '李强',
              decidedAt: '2026-11-19T12:00:00+08:00',
            }),
          ]),
        ),
      }),
    );

    fireEvent.click(await screen.findByRole('button', { name: '查看导出详情 export-1' }));
    const drawer = await screen.findByRole('dialog');
    expect(within(drawer).getByText('条件摘要')).toBeVisible();
    expect(within(drawer).getByText('请缩小范围')).toBeVisible();
    expect(within(drawer).getByText(/李强/)).toBeVisible();
    expect(within(drawer).getByText(/不含密码哈希/)).toBeVisible();
  });
});
