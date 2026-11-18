// @vitest-environment jsdom
import { act, renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { WorkspaceFile } from '@/services/api/types';
import {
  DEMO_SESSION,
  createFakeApi,
  makeConversation,
  makeDetail,
  makeFile,
  pageOf,
} from '@/test/fake-api';
import { useJ1Workspace } from './use-j1-workspace';

/**
 * 构造可手动完成的 Promise，用于把“上传完成”和“发送”压进同一个 React 提交周期。
 *
 * @returns Promise 与 resolve 函数
 */
function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve: (value: T) => void = () => undefined;
  const promise = new Promise<T>((innerResolve) => {
    resolve = innerResolve;
  });
  return { promise, resolve };
}

/**
 * 渲染 hook 并完成工作空间与会话初始化。
 *
 * @param api fake API
 * @returns renderHook 结果
 */
async function setupWorkspace(api: ReturnType<typeof createFakeApi>) {
  const rendered = renderHook(() => useJ1Workspace(api, DEMO_SESSION, 60_000));
  await waitFor(() => {
    expect(rendered.result.current.phase).toBe('ready');
  });
  await act(async () => {
    await rendered.result.current.selectConversation('conversation-1');
  });
  return rendered;
}

/**
 * 读取一次 sendMessage 调用的请求体。
 *
 * @param api fake API
 * @param index 调用序号
 * @returns 请求体
 */
function sentBody(api: ReturnType<typeof createFakeApi>, index = 0) {
  const calls = (api.sendMessage as unknown as { mock: { calls: unknown[][] } }).mock.calls;
  return calls[index][0] as { fileIds?: string[] };
}

describe('J1 文件快照竞态', () => {
  it('上传完成与发送在同一提交周期时使用最新文件快照', async () => {
    const upload = deferred<WorkspaceFile>();
    const api = createFakeApi({
      listConversations: vi.fn(async () => pageOf([makeConversation()])),
      getConversation: vi.fn(async () => makeDetail([])),
      listFiles: vi.fn(async () => pageOf([])),
      uploadFile: vi.fn(() => upload.promise),
    });
    const { result } = await setupWorkspace(api);

    await act(async () => {
      const uploading = result.current.uploadFiles([
        new File(['pdf-bytes'], 'race.pdf', { type: 'application/pdf' }),
      ]);
      // 上传响应已到达，但 React 尚未提交状态；发送必须读取同步维护的快照。
      upload.resolve(makeFile({ id: 'file-race' }));
      await uploading;
      await result.current.send('请总结刚上传的文件');
    });

    expect(sentBody(api).fileIds).toEqual(['file-race']);
  });

  it('切换会话时同步清空旧文件快照，不会把上一会话文件带给新会话', async () => {
    const secondDetail = deferred<ReturnType<typeof makeDetail>>();
    const secondFiles = deferred<ReturnType<typeof pageOf<WorkspaceFile>>>();
    const api = createFakeApi({
      listConversations: vi.fn(async () =>
        pageOf([
          makeConversation({ id: 'conversation-1', title: '会话一' }),
          makeConversation({ id: 'conversation-2', title: '会话二' }),
        ]),
      ),
      getConversation: vi.fn((conversationId: string) =>
        conversationId === 'conversation-1'
          ? Promise.resolve(makeDetail([]))
          : secondDetail.promise,
      ),
      listFiles: vi.fn((_workspaceId: string, conversationId?: string) =>
        conversationId === 'conversation-1'
          ? Promise.resolve(pageOf([makeFile({ id: 'file-old' })]))
          : secondFiles.promise,
      ),
    });
    const { result } = await setupWorkspace(api);

    await act(async () => {
      // 选中新会话会先同步切换 activeId 并清空文件；列表响应保持挂起，发送发生在响应返回之前。
      const switching = result.current.selectConversation('conversation-2');
      // 让过 stopStreaming 的微任务，使 activeId 与文件快照已切换、会话详情仍在加载。
      await Promise.resolve();
      expect(api.getConversation).toHaveBeenCalledWith('conversation-2');
      await result.current.send('新会话的问题');
      secondDetail.resolve(makeDetail([]));
      secondFiles.resolve(pageOf([]));
      await switching;
    });

    expect(sentBody(api)).toEqual(expect.objectContaining({ content: '新会话的问题' }));
    expect(sentBody(api).fileIds ?? []).toEqual([]);
  });
});
