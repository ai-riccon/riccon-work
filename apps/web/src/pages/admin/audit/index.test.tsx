// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import { authStore } from '@/services/auth/auth-store';
import { withProviders } from '@/test/render';
import {
  MEMBER_SESSION,
  TENANT_ADMIN_SESSION,
  createAuditApi,
  makeAuditEvent,
  pageOf,
} from '../../../../tests/m10-audit/fake';
import AuditPage, { cappedPaginationTotal } from './index';

/** 构造审计 403。 */
function forbidden(): ApiError {
  return new ApiError('forbidden', {
    status: 403,
    code: 'RICCON-AUTH-403',
  });
}

/** 渲染审计页。 */
function renderPage(api = createAuditApi()) {
  return render(withProviders(<AuditPage api={api} />));
}

/**
 * 断言按钮可访问名称包含全部可见文本。
 *
 * 对应 axe `label-content-name-mismatch`（WCAG 2.5.3）：可访问名称必须以连续子串
 * 形式包含可见文本，否则语音控制用户无法按屏幕上看到的文字操作按钮。
 *
 * @param button 行内操作按钮
 */
function expectAccessibleNameContainsVisibleText(button: HTMLElement): void {
  const visibleText = (button.textContent ?? '').replace(/\s+/g, ' ').trim();
  const accessibleName = (button.getAttribute('aria-label') ?? '').replace(/\s+/g, ' ');
  expect(visibleText.length).toBeGreaterThan(0);
  expect(accessibleName).toContain(visibleText);
}

describe('审计查询页', () => {
  beforeEach(() => {
    authStore.reset();
    authStore.setAuthenticated(TENANT_ADMIN_SESSION);
  });

  it('展示策略文案、事件字段与时间倒序说明，且没有删除/编辑入口', async () => {
    const api = createAuditApi();
    renderPage(api);

    expect(await screen.findByText(/审计至少保留 365 天/)).toBeVisible();
    expect(screen.getByText('无删除入口。', { exact: false })).toBeVisible();
    expect(await screen.findByText('2026-11-19 13:00:00 +08:00')).toBeVisible();
    expect(screen.getAllByText('file.uploaded').length).toBeGreaterThan(0);
    expect(screen.getByText('王敏（user-1）', { exact: false })).toBeVisible();
    expect(screen.getByText('文件 / file-1')).toBeVisible();
    expect(screen.getByText(/排序固定为发生时间倒序/)).toBeVisible();

    expect(screen.queryByRole('button', { name: /删除/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /编辑/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /修改/ })).not.toBeInTheDocument();
  });

  it('空结果显示明确空态与重置入口', async () => {
    renderPage(createAuditApi({ listEvents: vi.fn(async () => pageOf([])) }));

    expect(await screen.findByText(/当前条件下没有审计事件/)).toBeVisible();
    expect(screen.getByRole('button', { name: '重置筛选' })).toBeVisible();
  });

  it('普通员工没有 audit.read 时直接 403，不请求审计数据', () => {
    authStore.reset();
    authStore.setAuthenticated(MEMBER_SESSION);
    const api = createAuditApi();
    renderPage(api);

    expect(screen.getByText('没有访问权限')).toBeVisible();
    expect(screen.getByText(/普通员工无权查看/)).toBeVisible();
    expect(api.getPolicy).not.toHaveBeenCalled();
    expect(api.listEvents).not.toHaveBeenCalled();
  });

  it('接口 403 显示无权限态', async () => {
    renderPage(
      createAuditApi({
        listEvents: vi.fn(async () => {
          throw forbidden();
        }),
      }),
    );

    expect(await screen.findByText('没有访问权限')).toBeVisible();
    expect((await screen.findAllByText(/没有审计访问权限/)).length).toBeGreaterThan(0);
  });

  it('查询失败显示错误态并可重试', async () => {
    let attempts = 0;
    const api = createAuditApi({
      listEvents: vi.fn(async () => {
        attempts += 1;
        if (attempts === 1) {
          throw new ApiError('server', { status: 500, code: 'RICCON-COMMON-500' });
        }
        return pageOf([makeAuditEvent()]);
      }),
    });
    renderPage(api);

    expect(await screen.findByText('审计查询失败')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));

    expect(await screen.findByText('2026-11-19 13:00:00 +08:00')).toBeVisible();
    expect(api.listEvents).toHaveBeenCalledTimes(2);
  });

  it('按操作者过滤后回到第一页并传递 actorId', async () => {
    const api = createAuditApi();
    renderPage(api);

    await screen.findByText('2026-11-19 13:00:00 +08:00');
    fireEvent.change(screen.getByLabelText('操作者 ID'), {
      target: { value: '11111111-1111-4111-8111-111111111111' },
    });
    fireEvent.click(screen.getByRole('button', { name: /查\s*询/ }));

    await waitFor(() => {
      expect(api.listEvents).toHaveBeenLastCalledWith(
        expect.objectContaining({
          actorId: '11111111-1111-4111-8111-111111111111',
          page: 1,
          size: 20,
        }),
      );
    });
  });

  it('翻页请求下一页数据', async () => {
    const api = createAuditApi({
      listEvents: vi.fn(async () => ({
        page: 1,
        size: 20,
        total: 45,
        totalPages: 3,
        items: [makeAuditEvent()],
      })),
    });
    renderPage(api);

    await screen.findByText('2026-11-19 13:00:00 +08:00');
    const pageTwo = screen.getByTitle('2');
    fireEvent.click(within(pageTwo).getByText('2'));

    await waitFor(() => {
      expect(api.listEvents).toHaveBeenLastCalledWith(
        expect.objectContaining({ page: 2, size: 20 }),
      );
    });
  });

  it('分页总数按服务端最大页码截断，避免请求第 201 页', () => {
    expect(cappedPaginationTotal(10000, 20, 200)).toBe(4000);
    expect(cappedPaginationTotal(100, 20, 200)).toBe(100);
  });

  it('查看详情按钮的可访问名称包含可见文本并保留动作上下文（WCAG 2.5.3）', async () => {
    renderPage();

    const detail = await screen.findByRole('button', { name: '查看详情：事件 file.uploaded' });
    expectAccessibleNameContainsVisibleText(detail);
    expect(detail).toHaveAccessibleName(/file\.uploaded$/);
  });

  it('打开事件详情抽屉展示 ID、追踪 ID 与白名单 details', async () => {
    renderPage();

    await screen.findByText('2026-11-19 13:00:00 +08:00');
    fireEvent.click(screen.getByRole('button', { name: '查看详情：事件 file.uploaded' }));

    const drawer = await screen.findByRole('dialog');
    expect(within(drawer).getByText('事件 ID')).toBeVisible();
    expect(within(drawer).getByText('trace-1')).toBeVisible();
    expect(within(drawer).getByLabelText('事件附加详情 JSON')).toHaveTextContent('file-1');
    expect(within(drawer).queryByText(/删除/)).not.toBeInTheDocument();
  });

  it('申请导出展示条件摘要并由服务端返回预计行数', async () => {
    const api = createAuditApi();
    renderPage(api);

    await screen.findByText('2026-11-19 13:00:00 +08:00');
    fireEvent.click(screen.getByRole('button', { name: '申请导出' }));

    const modal = await screen.findByRole('dialog');
    expect(within(modal).getByText('时间 最近 24 小时（服务端默认）')).toBeInTheDocument();
    fireEvent.click(within(modal).getByRole('button', { name: '提交申请' }));

    await waitFor(() => {
      expect(api.createExport).toHaveBeenCalledWith({});
    });
    expect(await screen.findByText(/预计 120 行/)).toBeVisible();
  });

  it('导出申请超过行数上限时在弹窗内显示可读原因', async () => {
    const api = createAuditApi({
      createExport: vi.fn(async () => {
        throw new ApiError('estimated rows exceed', {
          status: 400,
          code: 'RICCON-AUDIT-400',
        });
      }),
    });
    renderPage(api);

    await screen.findByText('2026-11-19 13:00:00 +08:00');
    fireEvent.click(screen.getByRole('button', { name: '申请导出' }));
    const modal = await screen.findByRole('dialog');
    fireEvent.click(within(modal).getByRole('button', { name: '提交申请' }));

    expect(await within(modal).findByText(/预计行数超过导出上限/)).toBeInTheDocument();
    expect(within(modal).queryByText(/estimated rows exceed/)).not.toBeInTheDocument();
  });

  it('策略加载失败时显示错误且不渲染表格', async () => {
    renderPage(
      createAuditApi({
        getPolicy: vi.fn(async () => {
          throw new ApiError('server', { status: 500, code: 'RICCON-COMMON-500' });
        }),
      }),
    );

    expect(await screen.findByText('审计查询失败')).toBeVisible();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });
});
