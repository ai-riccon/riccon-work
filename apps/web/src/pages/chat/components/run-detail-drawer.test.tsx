// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import { createFakeApi, makeModelInvocation, makeRunDetail } from '@/test/fake-api';
import { withProviders } from '@/test/render';
import { RunDetailDrawer } from './run-detail-drawer';

/**
 * 渲染运行详情抽屉。
 *
 * @param api fake API
 * @param runId 运行 ID
 * @param onClose 关闭回调
 * @returns render 结果
 */
function renderDrawer(
  api: ReturnType<typeof createFakeApi>,
  runId: string | null = 'run-1',
  onClose = vi.fn(),
) {
  return render(withProviders(<RunDetailDrawer api={api} onClose={onClose} runId={runId} />));
}

describe('运行详情抽屉', () => {
  it('展示运行摘要与模型调用记录', async () => {
    const api = createFakeApi({
      getRun: vi.fn(async () =>
        makeRunDetail({
          attempt: 2,
          modelInvocations: [
            makeModelInvocation(),
            makeModelInvocation({
              id: 'model-invocation-2',
              modelKey: 'demo-report-model',
              purpose: 'REPORT',
              status: 'FAILED',
              firstTokenMs: null,
              durationMs: 640,
              promptTokens: 256,
              completionTokens: null,
              errorCode: 'RICCON-MODEL-500',
            }),
          ],
        }),
      ),
    });
    renderDrawer(api);

    expect(await screen.findByText('demo-chat-model')).toBeVisible();
    expect(screen.getByText('demo-report-model')).toBeVisible();
    expect(screen.getByText('第 2 次')).toBeVisible();
    expect(screen.getByText('320 ms')).toBeVisible();
    expect(screen.getByText('128 / 96')).toBeVisible();
    expect(screen.getByText('256 / —')).toBeVisible();
    expect(screen.getByText('RICCON-MODEL-500')).toBeVisible();
  });

  it('加载中显示忙碌态，不展示旧记录', async () => {
    let resolveDetail: ((value: ReturnType<typeof makeRunDetail>) => void) | undefined;
    const pending = new Promise<ReturnType<typeof makeRunDetail>>((resolve) => {
      resolveDetail = resolve;
    });
    const api = createFakeApi({ getRun: vi.fn(() => pending) });
    renderDrawer(api);

    expect(await screen.findByText('正在加载运行详情…')).toBeVisible();
    expect(screen.queryByText('demo-chat-model')).not.toBeInTheDocument();

    resolveDetail?.(makeRunDetail());
    expect(await screen.findByText('demo-chat-model')).toBeVisible();
    expect(screen.queryByText('正在加载运行详情…')).not.toBeInTheDocument();
  });

  it('加载失败显示原因并可重试成功', async () => {
    const getRun = vi
      .fn()
      .mockRejectedValueOnce(
        new ApiError('服务不可用', { status: 500, code: 'RICCON-INTERNAL-500' }),
      )
      .mockResolvedValueOnce(makeRunDetail());
    const api = createFakeApi({ getRun });
    renderDrawer(api);

    expect(await screen.findByText('运行详情加载失败')).toBeVisible();
    expect(screen.getByText('服务暂时不可用，请稍后重试。')).toBeVisible();

    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(await screen.findByText('demo-chat-model')).toBeVisible();
    await waitFor(() => {
      expect(getRun).toHaveBeenCalledTimes(2);
    });
  });

  it('无模型调用时展示空态而不是伪造记录', async () => {
    const api = createFakeApi({
      getRun: vi.fn(async () => makeRunDetail({ modelInvocations: [] })),
    });
    renderDrawer(api);

    expect(await screen.findByText('本次运行没有模型调用记录。')).toBeVisible();
    expect(screen.queryByText('demo-chat-model')).not.toBeInTheDocument();
  });

  it('404 显示记录不存在，且页面不请求未打开的运行', async () => {
    const api = createFakeApi({
      getRun: vi.fn(async () => {
        throw new ApiError('不存在', { status: 404, code: 'RICCON-TENANT-404' });
      }),
    });
    const { unmount } = renderDrawer(api, null);
    expect(api.getRun).not.toHaveBeenCalled();
    unmount();

    renderDrawer(api);
    expect(await screen.findByText('运行记录不存在或已被删除。')).toBeVisible();
  });
});
