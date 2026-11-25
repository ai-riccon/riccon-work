// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import { withProviders } from '@/test/render';
import type { WorkflowApprovalApi, WorkflowApprovalDetail } from './approval-api';
import {
  REJECTED_RESULT_MESSAGE,
  REJECT_COMMENT_REQUIRED_MESSAGE,
  SELF_APPROVAL_MESSAGE,
  WORKFLOW_UNAVAILABLE_MESSAGE,
} from './format';
import { ApprovalDetail } from './workflow-approvals-detail';

/**
 * 审批详情与决定组件测试。
 *
 * 覆盖 dataScopeSummary/脱敏参数展示、批准/拒绝、拒绝理由必填、自批入口隐藏、403/404/409/503
 * 与已决定记录；真实键盘路径与 axe 由 T16a 的 E2E-M06-04/@a11y 覆盖。
 */

/** 固定时钟；与夹具创建时间相差 2 小时。 */
const NOW = () => new Date('2026-11-25T10:00:00+08:00');

/** 审批详情夹具。 */
function makeDetail(overrides: Partial<WorkflowApprovalDetail> = {}): WorkflowApprovalDetail {
  return {
    id: 'approval-1',
    workflowRunId: 'run-1',
    workflowId: 'workflow-1',
    workflowName: '市场调研流程',
    nodeId: 'approval-node',
    nodeName: '人工审批',
    status: 'PENDING',
    assigneeType: 'USER',
    assigneeUserIds: ['approver-1'],
    actionKind: 'MCP_TOOL',
    dataScopeSummary: {
      runScope: 'DEPARTMENT',
      departmentId: 'dept-0001-aaaa',
      documentReferenceCount: 2,
    },
    targetToolName: 'market_data',
    paramsPreview: { symbol: '600519', limit: 20 },
    planSha256: 'a'.repeat(64),
    requestedBy: 'user-0001',
    createdAt: '2026-11-25T08:00:00+08:00',
    selfApprovalBlocked: false,
    documentReferenceCount: 2,
    ...overrides,
  };
}

/** 构造审批 API 替身。 */
function createFakeApi(overrides: Partial<WorkflowApprovalApi> = {}): WorkflowApprovalApi {
  return {
    listApprovals: vi.fn(async () => ({
      page: 1,
      size: 20,
      total: 0,
      totalPages: 0,
      items: [],
    })),
    getApproval: vi.fn(async () => makeDetail()),
    decideApproval: vi.fn(async () => makeDetail({ status: 'APPROVED' })),
    ...overrides,
  };
}

/**
 * 渲染审批详情。
 *
 * @param api API 替身
 * @returns 返回与决定回调替身
 */
function renderDetail(api: WorkflowApprovalApi) {
  const onBack = vi.fn();
  const onDecided = vi.fn();
  render(
    withProviders(
      <ApprovalDetail
        api={api}
        approvalId="approval-1"
        now={NOW}
        onBack={onBack}
        onDecided={onDecided}
      />,
    ),
  );
  return { onBack, onDecided };
}

/**
 * 解析 rgb()/hex 颜色为 WCAG 相对亮度。
 *
 * @param color 内联样式颜色
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
 * 断言 Tag 前景/背景对比度达到 WCAG 2.1 AA 4.5:1。
 *
 * @param tag 状态 Tag
 */
function expectAATagContrast(tag: HTMLElement): void {
  expect(tag.style.color).not.toBe('');
  expect(tag.style.backgroundColor).not.toBe('');
  const [lighter, darker] = [
    relativeLuminance(tag.style.color),
    relativeLuminance(tag.style.backgroundColor),
  ].sort((left, right) => right - left);
  expect((lighter + 0.05) / (darker + 0.05)).toBeGreaterThanOrEqual(4.5);
}

describe('ApprovalDetail', () => {
  beforeEach(() => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1024 });
  });

  it('展示动作、目标工具、数据范围与脱敏参数摘要', async () => {
    const api = createFakeApi();
    renderDetail(api);

    expect(await screen.findByRole('heading', { name: '审批详情', level: 1 })).toBeVisible();
    expect(screen.getByText('市场调研流程')).toBeVisible();
    expect(screen.getByText('调用 MCP 工具：market_data')).toBeVisible();
    expect(screen.getByText('market_data')).toBeVisible();
    expect(screen.getByText('本部门 · 部门 dept-000… · 引用文档 2 篇')).toBeVisible();
    expect(screen.getByText('2 小时')).toBeVisible();
    expect(screen.getByText(/symbol: 600519/)).toBeVisible();
    expect(screen.getByText(/limit: 20/)).toBeVisible();
    expect(screen.getByText('aaaaaaaaaaaa…')).toBeVisible();
    expect(api.getApproval).toHaveBeenCalledWith('approval-1');
  });

  it('详情就绪后聚焦标题', async () => {
    renderDetail(createFakeApi());

    const heading = await screen.findByRole('heading', { name: '审批详情', level: 1 });
    await waitFor(() => expect(heading).toHaveFocus());
  });

  it('批准时提交可选意见并回调结果', async () => {
    const api = createFakeApi();
    const { onDecided } = renderDetail(api);

    fireEvent.change(await screen.findByLabelText(/审批意见/), {
      target: { value: ' 同意执行 ' },
    });
    fireEvent.click(screen.getByRole('button', { name: '批准' }));

    await waitFor(() =>
      expect(api.decideApproval).toHaveBeenCalledWith('approval-1', {
        decision: 'APPROVE',
        comment: '同意执行',
      }),
    );
    expect(onDecided).toHaveBeenCalledWith('APPROVE');
  });

  it('批准不填意见时只提交决定', async () => {
    const api = createFakeApi();
    const { onDecided } = renderDetail(api);

    fireEvent.click(await screen.findByRole('button', { name: '批准' }));

    await waitFor(() =>
      expect(api.decideApproval).toHaveBeenCalledWith('approval-1', { decision: 'APPROVE' }),
    );
    expect(onDecided).toHaveBeenCalledWith('APPROVE');
  });

  it('拒绝理由为空时阻止提交并聚焦输入框', async () => {
    const api = createFakeApi();
    renderDetail(api);

    fireEvent.click(await screen.findByRole('button', { name: '拒绝' }));

    expect(screen.getByText(REJECT_COMMENT_REQUIRED_MESSAGE)).toBeVisible();
    expect(screen.getByLabelText(/审批意见/)).toHaveFocus();
    expect(api.decideApproval).not.toHaveBeenCalled();
  });

  it('拒绝理由只有空白时同样阻止提交', async () => {
    const api = createFakeApi();
    renderDetail(api);

    fireEvent.change(await screen.findByLabelText(/审批意见/), {
      target: { value: '   ' },
    });
    fireEvent.click(screen.getByRole('button', { name: '拒绝' }));

    expect(screen.getByText(REJECT_COMMENT_REQUIRED_MESSAGE)).toBeVisible();
    expect(api.decideApproval).not.toHaveBeenCalled();
  });

  it('拒绝时提交必填理由并回调结果', async () => {
    const api = createFakeApi();
    const { onDecided } = renderDetail(api);

    fireEvent.change(await screen.findByLabelText(/审批意见/), {
      target: { value: '数据来源不可信' },
    });
    fireEvent.click(screen.getByRole('button', { name: '拒绝' }));

    await waitFor(() =>
      expect(api.decideApproval).toHaveBeenCalledWith('approval-1', {
        decision: 'REJECT',
        comment: '数据来源不可信',
      }),
    );
    expect(onDecided).toHaveBeenCalledWith('REJECT');
  });

  it('自批时显示固定文案且不提供批准/拒绝入口', async () => {
    const api = createFakeApi({
      getApproval: vi.fn(async () => makeDetail({ selfApprovalBlocked: true })),
    });
    renderDetail(api);

    expect(await screen.findByText(SELF_APPROVAL_MESSAGE)).toBeVisible();
    expect(screen.queryByRole('button', { name: '批准' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '拒绝' })).not.toBeInTheDocument();
  });

  it('决定 403 显示固定说明且不回调结果', async () => {
    const api = createFakeApi({
      decideApproval: vi.fn(async () => {
        throw new ApiError('forbidden', { status: 403, code: 'RICCON-AUTH-403' });
      }),
    });
    const { onDecided } = renderDetail(api);

    fireEvent.click(await screen.findByRole('button', { name: '批准' }));

    expect(await screen.findByText(/无法完成审批：你不能审批自己发起的运行/)).toBeVisible();
    expect(onDecided).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: '刷新审批' })).toBeVisible();
  });

  it('并发 409 提示已被处理，刷新后读取最新状态', async () => {
    const decideApproval = vi.fn(async () => {
      throw new ApiError('conflict', { status: 409, code: 'RICCON-WF-409' });
    });
    const getApproval = vi.fn(async () => makeDetail());
    const api = createFakeApi({ decideApproval, getApproval });
    renderDetail(api);

    fireEvent.click(await screen.findByRole('button', { name: '批准' }));

    expect(
      await screen.findByText('该审批已被处理（可能由其他审批人完成），请刷新后查看最新状态。'),
    ).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '刷新审批' }));
    await waitFor(() => expect(getApproval).toHaveBeenCalledTimes(2));
  });

  it('编排关闭 503 显示关闭说明并保留重试入口', async () => {
    const api = createFakeApi({
      decideApproval: vi.fn(async () => {
        throw new ApiError('disabled', { status: 503, code: 'RICCON-WF-503' });
      }),
    });
    const { onDecided } = renderDetail(api);

    fireEvent.click(await screen.findByRole('button', { name: '批准' }));

    expect(await screen.findByText(WORKFLOW_UNAVAILABLE_MESSAGE)).toBeVisible();
    expect(screen.getByRole('button', { name: '批准' })).toBeEnabled();
    expect(onDecided).not.toHaveBeenCalled();
  });

  it('详情 404 说明只有 assignee 可查看', async () => {
    const api = createFakeApi({
      getApproval: vi.fn(async () => {
        throw new ApiError('not found', { status: 404, code: 'RICCON-TENANT-404' });
      }),
    });
    const { onBack } = renderDetail(api);

    expect(await screen.findByText('审批不存在或你无权查看')).toBeVisible();
    expect(
      screen.getByText('只有分配给你的审批才能查看。如果你认为这是误判，请联系租户管理员。'),
    ).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: /返回待办/ }));
    expect(onBack).toHaveBeenCalled();
  });

  it('详情 403 说明联系租户管理员', async () => {
    const api = createFakeApi({
      getApproval: vi.fn(async () => {
        throw new ApiError('forbidden', { status: 403, code: 'RICCON-AUTH-403' });
      }),
    });
    renderDetail(api);

    expect(await screen.findByText('没有访问权限')).toBeVisible();
    expect(screen.getByText('你没有审批权限，请联系租户管理员申请。')).toBeVisible();
  });

  it('详情加载失败可重试', async () => {
    const getApproval = vi
      .fn<WorkflowApprovalApi['getApproval']>()
      .mockRejectedValueOnce(new ApiError('boom', { status: 500, code: 'RICCON-INTERNAL-500' }))
      .mockResolvedValueOnce(makeDetail());
    renderDetail(createFakeApi({ getApproval }));

    expect(await screen.findByText('审批详情加载失败')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '重试' }));
    expect(await screen.findByText('市场调研流程')).toBeVisible();
    expect(getApproval).toHaveBeenCalledTimes(2);
  });

  it('已决定的审批展示记录且不再提供决定入口', async () => {
    const api = createFakeApi({
      getApproval: vi.fn(async () =>
        makeDetail({
          status: 'REJECTED',
          decidedBy: 'approver-2',
          decidedAt: '2026-11-25T09:30:00+08:00',
          decisionComment: '引用资料不足',
        }),
      ),
    });
    renderDetail(api);

    expect(await screen.findByText(REJECTED_RESULT_MESSAGE)).toBeVisible();
    expect(screen.getByText('审批记录')).toBeVisible();
    expect(screen.getByText('approver…')).toBeVisible();
    expect(screen.getByText('引用资料不足')).toBeVisible();
    expect(screen.queryByRole('button', { name: '批准' })).not.toBeInTheDocument();
  });

  it('键盘可聚焦返回、意见输入与决定按钮', async () => {
    renderDetail(createFakeApi());

    const back = await screen.findByRole('button', { name: /返回待办/ });
    const comment = screen.getByLabelText(/审批意见/);
    const approve = screen.getByRole('button', { name: '批准' });

    back.focus();
    expect(back).toHaveFocus();
    comment.focus();
    expect(comment).toHaveFocus();
    approve.focus();
    expect(approve).toHaveFocus();
  });

  it('375px 参数摘要与决定按钮可见', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 375 });
    renderDetail(createFakeApi());

    expect(await screen.findByText(/symbol: 600519/)).toBeVisible();
    expect(screen.getByRole('button', { name: '批准' })).toBeVisible();
    expect(screen.getByRole('button', { name: '拒绝' })).toBeVisible();
  });

  it('待审批 Tag 达到 AA 对比度，决定按钮按可见名称可查', async () => {
    renderDetail(createFakeApi());

    const tags = await screen.findAllByText('待审批');
    expect(tags.length).toBeGreaterThan(0);
    tags.forEach((tag) => {
      expect(tag).toHaveClass('ant-tag-gold');
      expectAATagContrast(tag);
    });

    const approve = screen.getByRole('button', { name: '批准' });
    const reject = screen.getByRole('button', { name: '拒绝' });
    expect(approve.textContent).toBe('批准');
    expect(reject.textContent).toBe('拒绝');
  });
});
