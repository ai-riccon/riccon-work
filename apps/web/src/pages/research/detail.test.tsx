// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import { authStore } from '@/services/auth/auth-store';
import type { ResearchTaskDetail } from '@/services/api/types';
import {
  DEMO_SESSION,
  RESEARCH_STAGES,
  createFakeApi,
  makeApproval,
  makeArtifact,
  makeInvocation,
  makeResearchDetail,
  makeResearchTask,
} from '@/test/fake-api';
import { withProviders } from '@/test/render';
import ResearchDetailPage from './detail';

/**
 * J2 报告任务详情页测试。
 *
 * 覆盖等待审批、批准/拒绝、MCP 失败重试、产物正文与引用定位、正文错误、
 * 非 Markdown 预览以及 403/404/500 状态。E2E 负责真实路由与浏览器行为。
 */
describe('报告任务详情页', () => {
  beforeEach(() => {
    authStore.reset();
    authStore.setAuthenticated(DEMO_SESSION);
  });

  /**
   * 渲染详情页。
   *
   * @param api fake API
   * @param taskId 任务 ID
   * @param navigate 导航替身
   * @returns render 结果
   */
  function renderDetail(
    api: ReturnType<typeof createFakeApi>,
    taskId = 'research-task-1',
    navigate = vi.fn(),
  ) {
    return render(
      withProviders(
        <ResearchDetailPage
          api={api}
          navigate={navigate}
          pollIntervalMs={60_000}
          taskId={taskId}
        />,
      ),
    );
  }

  /** 构造已完成任务详情。 */
  function succeededDetail(overrides: Partial<ResearchTaskDetail> = {}): ResearchTaskDetail {
    const invocation = makeInvocation();
    return makeResearchDetail({
      status: 'SUCCEEDED',
      currentStage: null,
      failureCode: null,
      failureMessage: null,
      finishedAt: '2026-11-17T08:05:00.000Z',
      stages: [
        { stage: 'RETRIEVAL', attempt: 1, status: 'SUCCEEDED' },
        { stage: 'TOOL', attempt: 1, status: 'SUCCEEDED' },
        { stage: 'APPROVAL', attempt: 1, status: 'SUCCEEDED' },
        { stage: 'GENERATION', attempt: 1, status: 'SUCCEEDED' },
      ],
      approvals: [
        makeApproval({
          status: 'APPROVED',
          decidedBy: DEMO_SESSION.user.id,
          decidedAt: '2026-11-17T08:04:00.000Z',
        }),
      ],
      mcpInvocations: [invocation],
      artifact: makeArtifact(),
      ...overrides,
    });
  }

  it('等待审批时展示阶段时间线、来源、工具与数据范围', async () => {
    const api = createFakeApi();
    renderDetail(api);

    expect(screen.getByLabelText('正在加载报告任务详情')).toBeInTheDocument();
    expect(
      await screen.findByRole('heading', { name: '2026 年企业协同办公市场规模与竞争格局' }),
    ).toBeVisible();
    expect(screen.getByText('等待审批')).toBeVisible();
    expect(screen.getByText('任务正在等待审批')).toBeVisible();

    expect(screen.getByText('检索')).toBeVisible();
    expect(screen.getByText('工具取数')).toBeVisible();
    expect(screen.getByText('审批')).toBeVisible();
    expect(screen.getByText('生成报告')).toBeVisible();
    expect(screen.getByText('知识库：2025 协同办公市场研究')).toBeVisible();
    expect(screen.getByText(/同比增长 18%/)).toBeVisible();

    expect(screen.getByText('market_data_lookup')).toBeVisible();
    expect(screen.getByText(/market: 企业协同办公/)).toBeVisible();
    expect(screen.getByText('待审批：调用外部工具')).toBeVisible();
    expect(screen.getByRole('button', { name: /批\s*准/ })).toBeEnabled();
    expect(screen.getByRole('button', { name: /拒\s*绝/ })).toBeEnabled();
    expect(screen.getByText('审批通过并完成生成后，报告会显示在这里。')).toBeVisible();
    expect(api.fetchArtifactContent).not.toHaveBeenCalled();
  });

  it('批准后刷新为工具执行中，双击只提交一次', async () => {
    let current = makeResearchDetail();
    const api = createFakeApi({
      getResearchTask: vi.fn(async () => current),
      decideApproval: vi.fn(async (approvalId: string) => {
        const invocation = makeInvocation({
          status: 'STARTED',
          durationMs: null,
          resultSha256: null,
          finishedAt: null,
        });
        current = makeResearchDetail({
          status: 'RUNNING',
          currentStage: 'TOOL',
          stages: [
            ...RESEARCH_STAGES,
            {
              stage: 'APPROVAL',
              attempt: 1,
              status: 'SUCCEEDED',
              startedAt: '2026-11-17T08:00:02.000Z',
              finishedAt: '2026-11-17T08:04:00.000Z',
            },
            {
              stage: 'TOOL',
              attempt: 1,
              status: 'RUNNING',
              startedAt: '2026-11-17T08:04:00.000Z',
              details: { invocationId: invocation.id },
            },
          ],
          approvals: [
            makeApproval({
              id: approvalId,
              status: 'APPROVED',
              decidedBy: DEMO_SESSION.user.id,
              decidedAt: '2026-11-17T08:04:00.000Z',
            }),
          ],
          mcpInvocations: [invocation],
        });
        return current.approvals![0];
      }),
    });
    renderDetail(api);

    const approve = await screen.findByRole('button', { name: /批\s*准/ });
    fireEvent.click(approve);
    fireEvent.click(approve);

    await waitFor(() => {
      expect(api.decideApproval).toHaveBeenCalledTimes(1);
    });
    expect(api.decideApproval).toHaveBeenCalledWith(
      'approval-1',
      { decision: 'APPROVE', comment: null },
      expect.any(String),
    );
    expect(await screen.findByText('已批准')).toBeVisible();
    expect(screen.getByText('MCP 调用 · market_data_lookup（第 1 次尝试）')).toBeVisible();
    expect(screen.queryByText('任务正在等待审批')).not.toBeInTheDocument();
  });

  it('拒绝审批时记录备注并终止任务', async () => {
    let current = makeResearchDetail();
    const api = createFakeApi({
      getResearchTask: vi.fn(async () => current),
      decideApproval: vi.fn(async (_approvalId: string, input) => {
        current = makeResearchDetail({
          status: 'CANCELLED',
          currentStage: null,
          failureCode: 'RICCON-APPROVAL-REJECTED',
          failureMessage: '审批被拒绝，任务已终止，未生成报告。',
          stages: [
            ...RESEARCH_STAGES,
            { stage: 'APPROVAL', attempt: 1, status: 'FAILED' },
            { stage: 'GENERATION', attempt: 1, status: 'SKIPPED' },
          ],
          approvals: [
            makeApproval({
              status: 'REJECTED',
              decidedBy: DEMO_SESSION.user.id,
              decidedAt: '2026-11-17T08:04:00.000Z',
              decisionComment: input.comment,
            }),
          ],
        });
        return current.approvals![0];
      }),
    });
    renderDetail(api);

    fireEvent.change(await screen.findByLabelText('审批备注（可选）'), {
      target: { value: '数据范围过大，先补充说明' },
    });
    fireEvent.click(screen.getByRole('button', { name: /拒\s*绝/ }));

    await waitFor(() => {
      expect(api.decideApproval).toHaveBeenCalledWith(
        'approval-1',
        { decision: 'REJECT', comment: '数据范围过大，先补充说明' },
        expect.any(String),
      );
    });
    expect(await screen.findByText('任务已终止')).toBeVisible();
    expect(screen.getByText('已拒绝')).toBeVisible();
    expect(screen.getByText('任务已终止，未生成报告。')).toBeVisible();
    expect(screen.getByText('审批被拒绝，任务已终止，未生成报告。')).toBeVisible();
    expect(screen.getByText('数据范围过大，先补充说明')).toBeVisible();
    expect(screen.getAllByText('演示员工').length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: /下载 Markdown/ })).not.toBeInTheDocument();
  });

  it('工具失败时显示原因并可重试阶段，保留旧调用记录', async () => {
    const failedInvocation = makeInvocation({
      id: 'invocation-failed',
      status: 'FAILED',
      errorCode: 'RICCON-MCP-504',
      resultSha256: null,
      durationMs: 600,
    });
    const failedDetail = makeResearchDetail({
      status: 'FAILED',
      currentStage: null,
      failureCode: 'RICCON-MCP-504',
      failureMessage: '外部数据工具响应超时（演示），请重试工具阶段。',
      stages: [
        ...RESEARCH_STAGES,
        {
          stage: 'TOOL',
          attempt: 1,
          status: 'FAILED',
          errorCode: 'RICCON-MCP-504',
          details: { invocationId: failedInvocation.id },
        },
      ],
      approvals: [
        makeApproval({
          status: 'APPROVED',
          decidedBy: DEMO_SESSION.user.id,
          decidedAt: '2026-11-17T08:04:00.000Z',
        }),
      ],
      mcpInvocations: [failedInvocation],
    });
    let current: ResearchTaskDetail = failedDetail;
    const retried = makeResearchDetail({
      status: 'WAITING_APPROVAL',
      currentStage: 'APPROVAL',
      failureCode: null,
      failureMessage: null,
      stages: failedDetail.stages,
      approvals: [
        ...(failedDetail.approvals ?? []),
        makeApproval({
          id: 'approval-2',
          attempt: 2,
          status: 'PENDING',
          createdAt: '2026-11-17T08:06:00.000Z',
        }),
      ],
      mcpInvocations: [failedInvocation],
    });
    const api = createFakeApi({
      getResearchTask: vi.fn(async () => current),
      retryResearchTask: vi.fn(async () => {
        current = retried;
        return makeResearchTask({ status: 'WAITING_APPROVAL', currentStage: 'APPROVAL' });
      }),
    });
    renderDetail(api);

    expect(await screen.findByText('任务失败')).toBeVisible();
    expect(screen.getAllByText(/RICCON-MCP-504/).length).toBeGreaterThan(0);
    expect(screen.getByText('工具调用失败')).toBeVisible();
    expect(screen.getAllByText(/外部数据工具响应超时/).length).toBeGreaterThan(0);

    fireEvent.click(screen.getByRole('button', { name: '重试工具阶段' }));

    await waitFor(() => {
      expect(api.retryResearchTask).toHaveBeenCalledWith(
        'research-task-1',
        { fromStage: 'TOOL' },
        expect.any(String),
      );
    });
    expect(await screen.findByText('待审批：调用外部工具')).toBeVisible();
    expect(screen.getAllByText('market_data_lookup').length).toBeGreaterThan(0);
    expect(screen.getByText('MCP 调用 · market_data_lookup（第 1 次尝试）')).toBeVisible();
    expect(screen.queryByRole('button', { name: '重试工具阶段' })).not.toBeInTheDocument();
  });

  it('检索无结果时引导调整主题，不提供原地重试', async () => {
    const detail = makeResearchDetail({
      status: 'FAILED',
      currentStage: null,
      failureCode: 'RICCON-RESEARCH-404',
      failureMessage: '未检索到相关资料，请调整主题后重新创建任务。',
      stages: [
        {
          stage: 'RETRIEVAL',
          attempt: 1,
          status: 'FAILED',
          errorCode: 'RICCON-RESEARCH-404',
          details: { query: '不存在的主题' },
        },
      ],
      approvals: [],
      sources: [],
      mcpInvocations: [],
      artifact: null,
    });
    const api = createFakeApi({ getResearchTask: vi.fn(async () => detail) });
    const navigate = vi.fn();
    renderDetail(api, 'research-task-1', navigate);

    expect(await screen.findByText('没有检索命中')).toBeVisible();
    expect(screen.getByText('任务失败', { exact: true })).toBeVisible();
    expect(screen.getByRole('button', { name: '调整主题重新创建' })).toBeVisible();
    expect(screen.queryByRole('button', { name: /重\s*试.*阶段/ })).not.toBeInTheDocument();
    expect(
      screen.getByText('任务失败，未生成报告；已完成阶段记录仍保留在时间线中。'),
    ).toBeVisible();

    fireEvent.click(screen.getByRole('button', { name: '调整主题重新创建' }));
    expect(navigate).toHaveBeenCalledWith('/research/tasks');
  });

  it('生成失败时可重试生成阶段', async () => {
    let current = makeResearchDetail({
      status: 'FAILED',
      currentStage: null,
      failureCode: 'RICCON-MODEL-500',
      failureMessage: '报告生成失败（演示），已完成阶段记录已保留。',
      stages: [
        ...RESEARCH_STAGES,
        { stage: 'TOOL', attempt: 1, status: 'SUCCEEDED' },
        { stage: 'GENERATION', attempt: 1, status: 'FAILED', errorCode: 'RICCON-MODEL-500' },
      ],
      approvals: [
        makeApproval({
          status: 'APPROVED',
          decidedBy: DEMO_SESSION.user.id,
          decidedAt: '2026-11-17T08:04:00.000Z',
        }),
      ],
      mcpInvocations: [makeInvocation()],
    });
    const api = createFakeApi({
      getResearchTask: vi.fn(async () => current),
      retryResearchTask: vi.fn(async () => {
        current = succeededDetail();
        return makeResearchTask({ status: 'SUCCEEDED', currentStage: null });
      }),
    });
    renderDetail(api);

    expect((await screen.findAllByText(/报告生成失败（演示）/)).length).toBeGreaterThan(0);
    fireEvent.click(screen.getByRole('button', { name: '重试生成阶段' }));

    await waitFor(() => {
      expect(api.retryResearchTask).toHaveBeenCalledWith(
        'research-task-1',
        { fromStage: 'GENERATION' },
        expect.any(String),
      );
    });
    expect(await screen.findByText('报告已生成')).toBeVisible();
  });

  it('成功后展示产物正文、引用清单与数据来源，引用可定位时间线', async () => {
    const api = createFakeApi({
      getResearchTask: vi.fn(async () => succeededDetail()),
    });
    const createObjectURL = vi.fn(() => 'blob:report');
    const revokeObjectURL = vi.fn();
    Object.defineProperty(globalThis.URL, 'createObjectURL', {
      configurable: true,
      value: createObjectURL,
    });
    Object.defineProperty(globalThis.URL, 'revokeObjectURL', {
      configurable: true,
      value: revokeObjectURL,
    });
    const anchorClick = vi
      .spyOn(HTMLAnchorElement.prototype, 'click')
      .mockImplementation(() => undefined);

    renderDetail(api);

    expect(await screen.findByText('报告已生成')).toBeVisible();
    expect(screen.getByText('680 ms')).toBeVisible();
    expect(screen.getByText('结果已脱敏，仅保留校验摘要。')).toBeVisible();
    expect(
      await screen.findByRole('heading', { name: '2026 年企业协同办公市场调研报告' }),
    ).toBeVisible();
    expect(
      screen.getByText('报告基于知识库检索与市场数据工具生成，包含市场规模、竞争格局与建议。'),
    ).toBeVisible();
    expect(await screen.findByRole('heading', { name: '市场规模与增长' })).toBeVisible();
    expect(screen.getByRole('heading', { name: '竞争格局' })).toBeVisible();
    expect(screen.getByText('2025 年国内协同办公市场规模约为 320 亿元[1]。')).toBeVisible();
    expect(screen.getByText('引用清单')).toBeVisible();
    expect(screen.getByText('[1] 知识库：2025 协同办公市场研究')).toBeVisible();
    expect(screen.getByText('[2] MCP：market_data_lookup')).toBeVisible();
    expect(screen.getByText('数据来源')).toBeVisible();
    expect(screen.getByText(/MCP 工具 market_data_lookup/)).toBeVisible();

    fireEvent.click(screen.getByRole('button', { name: /在时间线中查看引用 1/ }));
    await waitFor(() => {
      expect(document.getElementById('timeline-RETRIEVAL')).toHaveAttribute(
        'data-highlighted',
        'true',
      );
    });

    fireEvent.click(screen.getByRole('button', { name: /在时间线中查看引用 2/ }));
    await waitFor(() => {
      expect(document.getElementById('timeline-invocation-invocation-1')).toHaveAttribute(
        'data-highlighted',
        'true',
      );
    });

    fireEvent.click(screen.getByRole('button', { name: /下载 Markdown/ }));
    expect(createObjectURL).toHaveBeenCalledTimes(1);
    expect(anchorClick).toHaveBeenCalled();
    anchorClick.mockRestore();
    expect(api.fetchArtifactContent).toHaveBeenCalledWith('artifact-1', 1, expect.anything());
  });

  it('正文加载失败时提示并可重试；非 Markdown 只提示下载', async () => {
    const api = createFakeApi({
      getResearchTask: vi.fn(async () => succeededDetail()),
      fetchArtifactContent: vi.fn(async () => {
        throw new ApiError('服务不可用', { status: 500, code: 'RICCON-INTERNAL-500' });
      }),
    });
    const { unmount } = renderDetail(api);

    expect(await screen.findByText('报告正文加载失败')).toBeVisible();
    expect(screen.getByRole('button', { name: /下载 Markdown/ })).toBeDisabled();
    fireEvent.click(
      within(screen.getByText('报告正文加载失败').closest('.ant-alert') as HTMLElement).getByRole(
        'button',
        { name: /重\s*试/ },
      ),
    );
    await waitFor(() => {
      expect(api.fetchArtifactContent).toHaveBeenCalledTimes(2);
    });
    unmount();

    const unsupported = succeededDetail();
    const artifact = makeArtifact();
    artifact.currentVersionDetail = {
      ...artifact.currentVersionDetail!,
      contentType: 'application/json',
    };
    const unsupportedApi = createFakeApi({
      getResearchTask: vi.fn(async () => ({ ...unsupported, artifact })),
    });
    renderDetail(unsupportedApi);
    expect(await screen.findByText('暂不支持在线预览')).toBeVisible();
    expect(unsupportedApi.fetchArtifactContent).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: /下载 Markdown/ })).toBeDisabled();
  });

  it('没有引用或数据来源时给出明确空态', async () => {
    const detail = succeededDetail({
      sources: [],
      mcpInvocations: [],
      artifact: makeArtifact({
        currentVersionDetail: {
          ...makeArtifact().currentVersionDetail!,
          citations: [],
        },
      }),
    });
    const api = createFakeApi({ getResearchTask: vi.fn(async () => detail) });
    renderDetail(api);

    expect(await screen.findByText('该版本没有引用条目。')).toBeVisible();
    expect(screen.getByText('本次报告没有外部数据来源。')).toBeVisible();
  });

  it('403、404 与 500 分别展示可恢复的错误状态', async () => {
    const forbidden = createFakeApi({
      getResearchTask: vi.fn(async () => {
        throw new ApiError('没有权限', { status: 403, code: 'RICCON-AUTH-403' });
      }),
    });
    const first = renderDetail(forbidden);
    expect(await screen.findByText('没有访问权限')).toBeVisible();
    first.unmount();

    const missing = createFakeApi({
      getResearchTask: vi.fn(async () => {
        throw new ApiError('不存在', { status: 404, code: 'RICCON-TENANT-404' });
      }),
    });
    const second = renderDetail(missing);
    expect(await screen.findByText('报告任务不存在')).toBeVisible();
    second.unmount();

    const broken = createFakeApi({
      getResearchTask: vi.fn(async () => {
        throw new ApiError('服务不可用', { status: 500, code: 'RICCON-INTERNAL-500' });
      }),
    });
    renderDetail(broken);
    expect(await screen.findByText('报告任务加载失败')).toBeVisible();
    expect(screen.getByText('服务暂时不可用，请稍后重试。')).toBeVisible();
  });

  it('缺少任务标识时进入不存在状态', async () => {
    const api = createFakeApi();
    renderDetail(api, '');
    expect(await screen.findByText('报告任务不存在')).toBeVisible();
    expect(api.getResearchTask).not.toHaveBeenCalled();
  });
});
