// @vitest-environment jsdom
import { fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import { withProviders } from '@/test/render';
import type { WorkflowRuntimeLimits, WorkflowRuntimeLimitsApi } from './runtime-limits-api';
import { WorkflowRuntimeLimitsDrawer } from './runtime-limits';

/**
 * 运行限制只读抽屉测试。
 *
 * 覆盖全字段展示、租户只读（无输入/编辑控件）、加载/错误/无权限/编排关闭态、刷新重试、
 * 375px 结构与键盘可达；真实浏览器 axe/375px 横向滚动由 T16a/T17 E2E 复核。
 */

/** 运行限制夹具。 */
const LIMITS: WorkflowRuntimeLimits = {
  featureEnabled: true,
  disabledReason: null,
  maxNodes: 50,
  maxSteps: 200,
  nodeTimeoutSeconds: { model: 300, retrieval: 120, mcp: 60 },
  runTimeoutSeconds: 1800,
  tenantConcurrency: 10,
  workflowConcurrency: 5,
  resultMaxBytes: 5 * 1024 * 1024,
  retentionDays: 180,
  retentionPolicy: 'DEPLOYMENT_DEFAULT',
  generatedAt: '2026-11-26T10:30:00+08:00',
};

/**
 * 构造运行限制 API 替身。
 *
 * @param overrides 覆盖项
 * @returns API 替身
 */
function createFakeApi(
  overrides: Partial<WorkflowRuntimeLimitsApi> = {},
): WorkflowRuntimeLimitsApi {
  return {
    getRuntimeLimits: vi.fn(async () => LIMITS),
    ...overrides,
  };
}

/**
 * 渲染打开的抽屉。
 *
 * @param api 运行限制 API
 * @returns 渲染结果
 */
function renderDrawer(api: WorkflowRuntimeLimitsApi = createFakeApi()) {
  return render(
    withProviders(<WorkflowRuntimeLimitsDrawer open onClose={() => undefined} api={api} />),
  );
}

describe('WorkflowRuntimeLimitsDrawer', () => {
  beforeEach(() => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1024 });
  });

  it('展示节点/步数/模型/检索/MCP/运行/并发/结果大小/保留与只读说明', async () => {
    renderDrawer();

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('运行限制（只读）')).toBeVisible();
    expect(
      within(dialog).getByText(
        /限制值由部署配置决定，租户只能查看，不能在此修改。如需调整，请联系平台运维提交部署配置变更。/,
      ),
    ).toBeVisible();

    expect(await within(dialog).findByText('50 个节点')).toBeVisible();
    expect(within(dialog).getByText('200 步')).toBeVisible();
    expect(within(dialog).getByText('300 秒（5 分钟）')).toBeVisible();
    expect(within(dialog).getByText('120 秒（2 分钟）')).toBeVisible();
    expect(within(dialog).getByText('60 秒（1 分钟）')).toBeVisible();
    expect(within(dialog).getByText('1800 秒（30 分钟）（审批等待不计）')).toBeVisible();
    expect(within(dialog).getByText('10 个运行（含等待审批）')).toBeVisible();
    expect(within(dialog).getByText('5 个运行')).toBeVisible();
    expect(within(dialog).getByText('5 MiB（5,242,880 字节）')).toBeVisible();
    expect(within(dialog).getByText('180 天（部署默认）')).toBeVisible();
    expect(within(dialog).getByText('2026-11-26 10:30:00 +08:00')).toBeVisible();
    expect(within(dialog).getByText('已启用')).toBeVisible();

    // 只读：没有任何输入/数字/编辑控件，避免租户误以为可修改部署限制。
    expect(within(dialog).queryAllByRole('textbox')).toHaveLength(0);
    expect(within(dialog).queryAllByRole('spinbutton')).toHaveLength(0);
    expect(within(dialog).queryByRole('button', { name: /编辑|保存|修改/ })).toBeNull();
    expect(within(dialog).getByText(/本页为只读视图，不提供修改入口/)).toBeVisible();
  });

  it('加载中显示可读进度，不先展示空值', async () => {
    let resolveLimits: ((value: WorkflowRuntimeLimits) => void) | undefined;
    const getRuntimeLimits = vi.fn(
      () =>
        new Promise<WorkflowRuntimeLimits>((resolve) => {
          resolveLimits = resolve;
        }),
    );
    renderDrawer(createFakeApi({ getRuntimeLimits }));

    expect(screen.getByLabelText('正在加载运行限制')).toBeVisible();
    expect(screen.queryByText('50 个节点')).toBeNull();

    resolveLimits?.(LIMITS);
    expect(await screen.findByText('50 个节点')).toBeVisible();
  });

  it('加载失败显示中文原因，重试成功后展示限制', async () => {
    const getRuntimeLimits = vi
      .fn<WorkflowRuntimeLimitsApi['getRuntimeLimits']>()
      .mockRejectedValueOnce(new ApiError('boom', { status: 500, code: 'RICCON-INTERNAL-500' }))
      .mockResolvedValueOnce(LIMITS);
    renderDrawer(createFakeApi({ getRuntimeLimits }));

    expect(await screen.findByText('运行限制加载失败')).toBeVisible();
    expect(screen.getByText('服务暂时不可用，请稍后重试。')).toBeVisible();

    fireEvent.click(screen.getByRole('button', { name: '重试' }));
    expect(await screen.findByText('50 个节点')).toBeVisible();
    expect(getRuntimeLimits).toHaveBeenCalledTimes(2);
  });

  it('403 显示无权限说明与申请路径', async () => {
    const getRuntimeLimits = vi
      .fn<WorkflowRuntimeLimitsApi['getRuntimeLimits']>()
      .mockRejectedValue(new ApiError('forbidden', { status: 403, code: 'RICCON-AUTH-403' }));
    renderDrawer(createFakeApi({ getRuntimeLimits }));

    expect(await screen.findByText('没有查看运行限制的权限')).toBeVisible();
    expect(screen.getByText(/workflow.read/)).toBeVisible();
    expect(screen.getByRole('button', { name: '重试' })).toBeVisible();
  });

  it('编排关闭时展示关闭原因与固定说明，限制值仍可见', async () => {
    renderDrawer(
      createFakeApi({
        getRuntimeLimits: vi.fn(async () => ({
          ...LIMITS,
          featureEnabled: false,
          disabledReason: 'workflow orchestration is disabled by deployment configuration',
        })),
      }),
    );

    expect(await screen.findByText('编排功能已关闭')).toBeVisible();
    expect(screen.getByText(/新运行、重试与审批决定暂不可用/)).toBeVisible();
    expect(screen.getByText('部署配置已关闭编排功能。', { exact: false })).toBeVisible();
    expect(screen.getByText('已关闭')).toBeVisible();
    expect(screen.getByText('50 个节点')).toBeVisible();
    expect(screen.getByText('10 个运行（含等待审批）')).toBeVisible();
  });

  it('关闭状态下不请求接口，打开后才读取', async () => {
    const api = createFakeApi();
    const { rerender } = render(
      withProviders(
        <WorkflowRuntimeLimitsDrawer open={false} onClose={() => undefined} api={api} />,
      ),
    );
    expect(api.getRuntimeLimits).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    rerender(
      withProviders(<WorkflowRuntimeLimitsDrawer open onClose={() => undefined} api={api} />),
    );
    expect(await screen.findByText('50 个节点')).toBeVisible();
    expect(api.getRuntimeLimits).toHaveBeenCalledTimes(1);
  });

  it('刷新按钮重新读取限制，保留只读展示', async () => {
    const api = createFakeApi();
    renderDrawer(api);

    expect(await screen.findByText('50 个节点')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '刷新运行限制' }));
    expect(api.getRuntimeLimits).toHaveBeenCalledTimes(2);
    expect(await screen.findByText('50 个节点')).toBeVisible();
    expect(screen.queryAllByRole('textbox')).toHaveLength(0);
  });

  it('375px 下关键字段与刷新/关闭按钮可聚焦，样式不产生横向滚动', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 375 });
    renderDrawer();

    expect(await screen.findByText('50 个节点')).toBeVisible();
    const refresh = screen.getByRole('button', { name: '刷新运行限制' });
    refresh.focus();
    expect(refresh).toHaveFocus();

    const close = screen.getByRole('button', { name: '关闭' });
    close.focus();
    expect(close).toHaveFocus();
  });
});
