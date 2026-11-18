// @vitest-environment jsdom
import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ResearchTaskDetail } from '@/services/api/types';
import { createFakeApi, makeResearchDetail, makeResearchTask } from '@/test/fake-api';
import { useResearchTask } from './use-research-task';

/** 构造一个手动 resolve 的 Promise，用于模拟请求竞态。 */
function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((resolver) => {
    resolve = resolver;
  });
  return { promise, resolve };
}

/** 构造已取消（审批拒绝）的任务详情。 */
function cancelledDetail(finishedAt = '2026-11-17T08:04:00.000Z'): ResearchTaskDetail {
  return makeResearchDetail({
    status: 'CANCELLED',
    currentStage: null,
    failureCode: 'RICCON-APPROVAL-REJECTED',
    failureMessage: '审批被拒绝，任务已终止，未生成报告。',
    finishedAt,
  });
}

/**
 * 报告任务轮询单元测试。
 *
 * 覆盖终态停止、终态后补刷一次、重复终态不重复补刷，以及陈旧活跃响应竞态；
 * 页面级展示由 detail.test.tsx 覆盖。
 */
describe('useResearchTask 终态轮询', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('终态后停止轮询并只补刷一次详情', async () => {
    vi.useFakeTimers();
    const running = makeResearchDetail({ status: 'RUNNING', currentStage: 'TOOL' });
    const terminal = cancelledDetail();
    const getResearchTask = vi
      .fn()
      .mockResolvedValueOnce(running)
      .mockResolvedValueOnce(terminal)
      .mockResolvedValue(terminal);
    const api = createFakeApi({ getResearchTask });

    const { result } = renderHook(() => useResearchTask(api, 'research-task-1', 1_000));

    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(result.current.detail?.status).toBe('RUNNING');
    expect(getResearchTask).toHaveBeenCalledTimes(1);

    // 轮询到终态：停止 interval，并安排一次 300ms 后的补刷。
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_000);
    });
    expect(result.current.detail?.status).toBe('CANCELLED');
    expect(getResearchTask).toHaveBeenCalledTimes(2);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(300);
    });
    expect(getResearchTask).toHaveBeenCalledTimes(3);

    // 终态补刷完成后不再轮询，也不会重复补刷。
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000);
    });
    expect(getResearchTask).toHaveBeenCalledTimes(3);
  });

  it('终态之后的陈旧活跃响应不会把任务拉回轮询', async () => {
    vi.useFakeTimers();
    const running = makeResearchDetail({ status: 'RUNNING', currentStage: 'TOOL' });
    const terminal = cancelledDetail();
    const getResearchTask = vi
      .fn()
      .mockResolvedValueOnce(running)
      .mockResolvedValueOnce(terminal)
      .mockResolvedValue(terminal);
    const api = createFakeApi({ getResearchTask });

    const { result } = renderHook(() => useResearchTask(api, 'research-task-1', 1_000));

    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_000);
    });
    expect(result.current.detail?.status).toBe('CANCELLED');

    // 先让终态补刷完成，再模拟一个迟到的活跃响应。
    await act(async () => {
      await vi.advanceTimersByTimeAsync(300);
    });
    getResearchTask.mockResolvedValueOnce(running);
    await act(async () => {
      await result.current.reload();
    });
    expect(result.current.detail?.status).toBe('CANCELLED');

    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000);
    });
    expect(result.current.detail?.status).toBe('CANCELLED');
    // 初次加载 + 轮询终态 + 终态补刷 + 一次被丢弃的手动刷新；之后没有新轮询。
    expect(getResearchTask).toHaveBeenCalledTimes(4);
  });

  it('重试后允许从终态回到活跃并恢复轮询', async () => {
    vi.useFakeTimers();
    const failed = makeResearchDetail({
      status: 'FAILED',
      currentStage: null,
      failureCode: 'RICCON-MCP-504',
      failureMessage: '工具调用失败。',
      finishedAt: '2026-11-17T08:04:00.000Z',
    });
    const running = makeResearchDetail({ status: 'RUNNING', currentStage: 'TOOL' });
    const getResearchTask = vi.fn().mockResolvedValueOnce(failed).mockResolvedValue(running);
    const api = createFakeApi({
      getResearchTask,
      retryResearchTask: vi.fn(async () => makeResearchTask({ status: 'RUNNING' })),
    });

    const { result } = renderHook(() => useResearchTask(api, 'research-task-1', 1_000));

    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(result.current.detail?.status).toBe('FAILED');

    await act(async () => {
      await result.current.retry('TOOL');
    });
    expect(result.current.detail?.status).toBe('RUNNING');

    // 显式重试清除了终态保护，后续轮询应恢复。
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_000);
    });
    expect(getResearchTask).toHaveBeenCalledTimes(3);
  });

  it('两个并发刷新以最新终态为准，旧响应不会覆盖', async () => {
    vi.useFakeTimers();
    const stale = deferred<ResearchTaskDetail>();
    const terminal = deferred<ResearchTaskDetail>();
    const running = makeResearchDetail({ status: 'RUNNING', currentStage: 'TOOL' });
    const getResearchTask = vi
      .fn()
      .mockResolvedValueOnce(running)
      .mockReturnValueOnce(stale.promise)
      .mockReturnValueOnce(terminal.promise)
      .mockResolvedValue(cancelledDetail());
    const api = createFakeApi({ getResearchTask });

    const { result } = renderHook(() => useResearchTask(api, 'research-task-1', 60_000));

    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(result.current.detail?.status).toBe('RUNNING');

    act(() => {
      void result.current.reload();
      void result.current.reload();
    });
    await act(async () => {
      terminal.resolve(cancelledDetail());
    });
    expect(result.current.detail?.status).toBe('CANCELLED');

    // 较旧的活跃响应随后返回，必须被 load token 丢弃。
    await act(async () => {
      stale.resolve(running);
    });
    expect(result.current.detail?.status).toBe('CANCELLED');

    await act(async () => {
      await vi.advanceTimersByTimeAsync(500);
    });
    expect(result.current.detail?.status).toBe('CANCELLED');
    expect(getResearchTask).toHaveBeenCalledTimes(4);
  });
});
