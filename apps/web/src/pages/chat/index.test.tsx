// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ChatEvent, Citation, WorkspaceFile } from '@/services/api/types';
import { ApiError } from '@/services/api/errors';
import { authStore } from '@/services/auth/auth-store';
import {
  DEMO_SESSION,
  createFakeApi,
  makeConversation,
  makeDetail,
  makeFile,
  makeMessage,
  pageOf,
} from '@/test/fake-api';
import { withProviders } from '@/test/render';
import ChatPage from './index';

/** 一条 PDF 引用。 */
const CITATION: Citation = {
  ordinal: 1,
  sourceType: 'FILE',
  fileId: 'file-1',
  locatorType: 'PAGE',
  pageNo: 3,
  title: '季度报告.pdf',
  snippet: '这是引用片段',
  score: 0.9,
};

/** 构造 started 事件。 */
function startedEvent(runId = 'run-1'): ChatEvent {
  return {
    eventId: 'e-started',
    eventType: 'riccon.chat.started.v1',
    version: 1,
    occurredAt: '2026-11-17T08:00:00.000Z',
    tenantId: 'tenant-a',
    runId,
    messageId: 'assistant-1',
    traceId: 'trace-1',
    data: { modelId: 'model-1', modelKey: 'demo-model' },
  };
}

/** 构造引用事件。 */
function citationEvent(): ChatEvent {
  return {
    eventId: 'e-citation',
    eventType: 'riccon.chat.citation.v1',
    version: 1,
    occurredAt: '2026-11-17T08:00:00.000Z',
    tenantId: 'tenant-a',
    runId: 'run-1',
    messageId: 'assistant-1',
    traceId: 'trace-1',
    data: { citations: [CITATION] },
  };
}

/** 构造 delta 事件。 */
function deltaEvent(delta: string): ChatEvent {
  return {
    eventId: `e-delta-${delta}`,
    eventType: 'riccon.chat.delta.v1',
    version: 1,
    occurredAt: '2026-11-17T08:00:00.000Z',
    tenantId: 'tenant-a',
    runId: 'run-1',
    messageId: 'assistant-1',
    traceId: 'trace-1',
    data: { delta },
  };
}

/** 构造 failed 事件。 */
function failedEvent(): ChatEvent {
  return {
    eventId: 'e-failed',
    eventType: 'riccon.chat.failed.v1',
    version: 1,
    occurredAt: '2026-11-17T08:00:00.000Z',
    tenantId: 'tenant-a',
    runId: 'run-1',
    messageId: 'assistant-1',
    traceId: 'trace-1',
    data: { code: 'RICCON-MODEL-500', message: '模型调用失败，请重试。', retryable: true },
  };
}

/**
 * 生成等待 AbortSignal 的 Promise，用于模拟可停止的长流。
 *
 * @param signal 取消信号
 * @returns abort 时拒绝的 Promise
 */
function waitForAbort(signal?: AbortSignal): Promise<never> {
  return new Promise((_resolve, reject) => {
    if (!signal) {
      return;
    }
    signal.addEventListener(
      'abort',
      () => {
        reject(new DOMException('aborted', 'AbortError'));
      },
      { once: true },
    );
  });
}

/**
 * 渲染已登录的 ChatPage。
 *
 * @param api fake API
 * @param pollIntervalMs 轮询间隔
 * @returns render 结果
 */
function renderChat(api: ReturnType<typeof createFakeApi>, pollIntervalMs = 10) {
  return render(
    withProviders(<ChatPage api={api} navigate={vi.fn()} pollIntervalMs={pollIntervalMs} />),
  );
}

/** 选择会话。 */
async function selectConversation(label: string) {
  const button = await screen.findByRole('button', { name: label });
  fireEvent.click(button);
  // 选择动作包含异步读取会话与文件；等待文件面板出现，避免在状态提交前输入。
  await screen.findByText(/会话文件 \d\/5/);
}

/** 在 Sender 中输入并发送。 */
function sendQuestion(question: string) {
  const textarea = screen.getByPlaceholderText('输入问题，Enter 发送，Shift+Enter 换行');
  fireEvent.change(textarea, { target: { value: question } });
  const send = document.querySelector('.ant-sender-actions-btn') as HTMLButtonElement;
  fireEvent.click(send);
}

describe('J1 工作空间', () => {
  beforeEach(() => {
    authStore.reset();
    authStore.setAuthenticated(DEMO_SESSION);
  });

  it('加载工作空间与会话，并展示租户名', async () => {
    const api = createFakeApi({
      listConversations: vi.fn(async () => pageOf([makeConversation()])),
      getConversation: vi.fn(async () => makeDetail([])),
    });
    renderChat(api);

    expect(await screen.findByRole('heading', { name: 'Riccon 工作空间' })).toBeVisible();
    expect(screen.getByText(/示例企业/)).toBeVisible();
    expect(screen.getByText(/我的工作空间/)).toBeVisible();
    expect(await screen.findByRole('button', { name: '示例会话' })).toBeVisible();
  });

  it('无会话时点击新建，进入空会话欢迎态', async () => {
    const api = createFakeApi();
    renderChat(api);

    expect(await screen.findByText(/还没有会话/)).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '新建会话' }));

    await waitFor(() => {
      expect(api.createConversation).toHaveBeenCalledWith('workspace-1');
    });
    expect(await screen.findByText('开始 J1 问答')).toBeVisible();
  });

  it('打开历史会话，展示消息、引用并能打开引用详情', async () => {
    const api = createFakeApi({
      listConversations: vi.fn(async () => pageOf([makeConversation()])),
      getConversation: vi.fn(async () =>
        makeDetail([
          makeMessage({ id: 'u1', seq: 1, role: 'USER', content: '请总结文件' }),
          makeMessage({
            id: 'a1',
            seq: 2,
            role: 'ASSISTANT',
            content: '根据文件内容，结论如下。',
            citations: [CITATION],
          }),
        ]),
      ),
    });
    renderChat(api);

    await selectConversation('示例会话');
    expect(await screen.findByText('根据文件内容，结论如下。')).toBeVisible();

    fireEvent.click(screen.getByRole('button', { name: /季度报告\.pdf/ }));
    const drawer = await screen.findByRole('dialog');
    expect(within(drawer).getByText('引用详情')).toBeVisible();
    expect(within(drawer).getByText(/第 3 页/)).toBeVisible();
    expect(within(drawer).getByText('这是引用片段')).toBeVisible();
  });

  it('流式回答展示引用，停止后保留已生成内容', async () => {
    const api = createFakeApi({
      listConversations: vi.fn(async () => pageOf([makeConversation()])),
      getConversation: vi.fn(async () => makeDetail([])),
      sendMessage: vi.fn(async function* stream(_input: unknown, signal?: AbortSignal) {
        yield startedEvent();
        yield citationEvent();
        yield deltaEvent('已经生成的内容');
        await waitForAbort(signal);
        yield {
          eventId: 'e-completed',
          eventType: 'riccon.chat.completed.v1',
          version: 1,
          occurredAt: '2026-11-17T08:00:00.000Z',
          tenantId: 'tenant-a',
          runId: 'run-1',
          messageId: 'assistant-1',
          traceId: 'trace-1',
          data: { finishReason: 'stop' },
        } as ChatEvent;
      }),
    });
    renderChat(api);

    await selectConversation('示例会话');
    sendQuestion('请总结文件');

    expect(await screen.findByText(/已经生成的内容/)).toBeVisible();
    expect(screen.getByRole('button', { name: /季度报告\.pdf/ })).toBeVisible();

    fireEvent.click(screen.getByRole('button', { name: '停止生成' }));

    expect(await screen.findByText('已停止生成')).toBeVisible();
    expect(screen.getByText(/已经生成的内容/)).toBeVisible();
    expect(api.stopGeneration).toHaveBeenCalledWith('conversation-1', 'run-1');
  });

  it('SSE 中途断线标记失败、保留已生成内容并提供重试', async () => {
    const api = createFakeApi({
      listConversations: vi.fn(async () => pageOf([makeConversation()])),
      getConversation: vi.fn(async () => makeDetail([])),
      sendMessage: vi.fn(async function* stream() {
        yield startedEvent();
        yield deltaEvent('已经生成的内容');
        // 模拟网络在流结束前中断：生成器抛出普通网络异常。
        throw new TypeError('fetch failed');
      }),
    });
    renderChat(api);

    await selectConversation('示例会话');
    sendQuestion('请总结文件');

    expect(await screen.findByText(/已经生成的内容/)).toBeVisible();
    expect(await screen.findByText('回答生成失败，请重试。')).toBeVisible();
    expect(screen.getByText(/RICCON-CLIENT-000/)).toBeVisible();
    expect(screen.getByRole('button', { name: /重\s*试/ })).toBeVisible();
  });

  it('回答完成且无引用时明确提示未检索到资料', async () => {
    const api = createFakeApi({
      listConversations: vi.fn(async () => pageOf([makeConversation()])),
      getConversation: vi.fn(async () => makeDetail([])),
      sendMessage: vi.fn(async function* stream() {
        yield startedEvent();
        yield deltaEvent('直接回答。');
        yield {
          eventId: 'e-completed',
          eventType: 'riccon.chat.completed.v1',
          version: 1,
          occurredAt: '2026-11-17T08:00:00.000Z',
          tenantId: 'tenant-a',
          runId: 'run-1',
          messageId: 'assistant-1',
          traceId: 'trace-1',
          data: { finishReason: 'stop' },
        } as ChatEvent;
      }),
    });
    renderChat(api);

    await selectConversation('示例会话');
    sendQuestion('没有资料的问题');

    expect(await screen.findByText('直接回答。')).toBeVisible();
    expect(await screen.findByText(/未检索到相关资料/)).toBeVisible();
  });

  it('模型失败显示错误与重试入口，重试会再次发送原问题', async () => {
    let calls = 0;
    const api = createFakeApi({
      listConversations: vi.fn(async () => pageOf([makeConversation()])),
      getConversation: vi.fn(async () => makeDetail([])),
      sendMessage: vi.fn(async function* stream() {
        calls += 1;
        yield startedEvent();
        if (calls === 1) {
          yield failedEvent();
          return;
        }
        yield deltaEvent('重试成功');
      }),
    });
    renderChat(api);

    await selectConversation('示例会话');
    sendQuestion('会失败的问题');

    expect(await screen.findByText('模型调用失败，请重试。')).toBeVisible();
    expect(screen.getByText(/RICCON-MODEL-500/)).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));

    expect(await screen.findByText('重试成功')).toBeVisible();
    expect(api.sendMessage).toHaveBeenCalledTimes(2);
    const secondCall = (api.sendMessage as unknown as { mock: { calls: unknown[][] } }).mock
      .calls[1];
    expect((secondCall[0] as { content: string }).content).toBe('会失败的问题');
  });

  it('上传文件后轮询解析状态并显示成功', async () => {
    const pending = makeFile({
      id: 'file-1',
      parse: {
        id: 'parse-1',
        attempt: 1,
        status: 'PENDING',
        startedAt: '2026-11-17T08:00:00.000Z',
        errorCode: null,
        errorMessage: null,
        finishedAt: null,
      },
    });
    const succeeded = makeFile({ id: 'file-1' });
    const api = createFakeApi({
      listConversations: vi.fn(async () => pageOf([makeConversation()])),
      getConversation: vi.fn(async () => makeDetail([])),
      uploadFile: vi.fn(async () => pending),
      getFile: vi.fn(async () => succeeded),
    });
    renderChat(api);

    await selectConversation('示例会话');
    await screen.findByText(/尚未上传文件/);
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    const file = new File(['content'], 'a.pdf', { type: 'application/pdf' });
    fireEvent.change(input, { target: { files: [file] } });

    expect(await screen.findByText('解析成功')).toBeVisible();
    expect(api.uploadFile).toHaveBeenCalledWith('workspace-1', 'conversation-1', file);
  });

  it('不支持格式在客户端直接拒绝，不调用上传接口', async () => {
    const api = createFakeApi({
      listConversations: vi.fn(async () => pageOf([makeConversation()])),
      getConversation: vi.fn(async () => makeDetail([])),
    });
    renderChat(api);

    await selectConversation('示例会话');
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    fireEvent.change(input, {
      target: { files: [new File(['x'], 'a.txt', { type: 'text/plain' })] },
    });

    expect(await screen.findByText(/格式不受支持/)).toBeVisible();
    expect(api.uploadFile).not.toHaveBeenCalled();
  });

  it('解析失败显示原因并可重试成功', async () => {
    const failed = makeFile({
      id: 'file-1',
      parse: {
        id: 'parse-1',
        attempt: 1,
        status: 'FAILED',
        errorCode: 'RICCON-FILE-500',
        errorMessage: '文件已加密，无法解析。',
        startedAt: '2026-11-17T08:00:00.000Z',
        finishedAt: '2026-11-17T08:00:01.000Z',
      },
    });
    const api = createFakeApi({
      listConversations: vi.fn(async () => pageOf([makeConversation()])),
      getConversation: vi.fn(async () => makeDetail([])),
      listFiles: vi.fn(async () => pageOf([failed])),
      retryFileParse: vi.fn(async () => ({
        ...failed,
        parse: { ...failed.parse!, status: 'SUCCEEDED' as const },
      })),
    });
    renderChat(api);

    await selectConversation('示例会话');
    expect(await screen.findByText('文件已加密，无法解析。')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '重试解析 季度报告.pdf' }));

    expect(await screen.findByText('解析成功')).toBeVisible();
    expect(api.retryFileParse).toHaveBeenCalledWith('file-1');
  });

  it('会话文件达到 5 个时禁用上传', async () => {
    const files: WorkspaceFile[] = Array.from({ length: 5 }, (_, index) =>
      makeFile({ id: `file-${index}`, originalName: `f-${index}.pdf` }),
    );
    const api = createFakeApi({
      listConversations: vi.fn(async () => pageOf([makeConversation()])),
      getConversation: vi.fn(async () => makeDetail([])),
      listFiles: vi.fn(async () => pageOf(files)),
    });
    renderChat(api);

    await selectConversation('示例会话');
    expect(await screen.findByText('会话文件 5/5')).toBeVisible();
    expect(screen.getByRole('button', { name: /上传文件/ })).toBeDisabled();
  });

  it('切换会话时重新加载各自消息', async () => {
    const first = makeConversation({
      id: 'conversation-1',
      title: '会话一',
      updatedAt: '2026-11-17T08:10:00.000Z',
    });
    const second = makeConversation({
      id: 'conversation-2',
      title: '会话二',
      updatedAt: '2026-11-17T08:20:00.000Z',
    });
    const api = createFakeApi({
      listConversations: vi.fn(async () => pageOf([first, second])),
      getConversation: vi.fn(async (conversationId: string) =>
        conversationId === 'conversation-1'
          ? makeDetail([makeMessage({ id: 'm1', seq: 1, role: 'USER', content: '会话一的消息' })], {
              id: 'conversation-1',
              title: '会话一',
            })
          : makeDetail([makeMessage({ id: 'm2', seq: 1, role: 'USER', content: '会话二的消息' })], {
              id: 'conversation-2',
              title: '会话二',
            }),
      ),
    });
    renderChat(api);

    await selectConversation('会话一');
    expect(await screen.findByText('会话一的消息')).toBeVisible();
    await selectConversation('会话二');
    expect(await screen.findByText('会话二的消息')).toBeVisible();
    await selectConversation('会话一');
    expect(await screen.findByText('会话一的消息')).toBeVisible();
  });

  it('工作空间 403 与空态分别给出明确下一步', async () => {
    const forbidden = createFakeApi({
      listWorkspaces: vi.fn(async () => {
        throw new ApiError('forbidden', { status: 403, code: 'RICCON-AUTH-403' });
      }),
    });
    const { unmount } = renderChat(forbidden);
    expect(await screen.findByText('没有访问权限')).toBeVisible();
    expect(screen.getByRole('button', { name: /重\s*试/ })).toBeVisible();
    unmount();

    const empty = createFakeApi({
      listWorkspaces: vi.fn(async () => pageOf([])),
    });
    renderChat(empty);
    expect(await screen.findByText('暂无可用工作空间')).toBeVisible();
  });

  it('会话详情 403 显示无权限，不展示伪造内容', async () => {
    const api = createFakeApi({
      listConversations: vi.fn(async () => pageOf([makeConversation()])),
      getConversation: vi.fn(async () => {
        throw new ApiError('forbidden', { status: 403, code: 'RICCON-AUTH-403' });
      }),
    });
    renderChat(api);

    await selectConversation('示例会话');
    expect(await screen.findByText('没有访问权限')).toBeVisible();
    expect(screen.queryByText('根据文件内容，结论如下。')).not.toBeInTheDocument();
  });

  it('停止请求失败时保留内容并提示重试停止', async () => {
    const api = createFakeApi({
      listConversations: vi.fn(async () => pageOf([makeConversation()])),
      getConversation: vi.fn(async () => makeDetail([])),
      sendMessage: vi.fn(async function* stream(_input: unknown, signal?: AbortSignal) {
        yield startedEvent();
        yield deltaEvent('保留内容');
        await waitForAbort(signal);
      }),
      stopGeneration: vi.fn(async () => {
        throw new ApiError('服务不可用', { status: 503, code: 'RICCON-500' });
      }),
    });
    renderChat(api);

    await selectConversation('示例会话');
    sendQuestion('请回答');
    expect(await screen.findByText('保留内容')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '停止生成' }));

    expect(await screen.findByText(/停止请求未送达/)).toBeVisible();
    expect(screen.getByText('保留内容')).toBeVisible();
    expect(await screen.findByText('已停止生成')).toBeVisible();
  });
});
