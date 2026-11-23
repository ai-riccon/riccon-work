// @vitest-environment jsdom
import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import { authStore } from '@/services/auth/auth-store';
import { DEMO_SESSION, createFakeApi, makeConversation, makeDetail, pageOf } from '@/test/fake-api';
import { withProviders } from '@/test/render';
import ChatPage from './index';

/** 构造带 3 小时后重置的配额超限错误。 */
function quotaError(): ApiError {
  const resetsAt = new Date(Date.now() + 3 * 3_600_000).toISOString();
  return new ApiError('quota exceeded', {
    status: 429,
    code: 'RICCON-QUOTA-429',
    problem: {
      type: 'about:blank',
      title: '配额超限',
      status: 429,
      code: 'RICCON-QUOTA-429',
      traceId: 'trace-quota',
      detail: '已达日限额，请稍后恢复。',
      scopeType: 'TENANT',
      window: 'DAILY',
      resetsAt,
    },
  });
}

/** 在 Sender 中输入并发送。 */
function sendQuestion(question: string): void {
  const textarea = screen.getByPlaceholderText('输入问题，Enter 发送，Shift+Enter 换行');
  fireEvent.change(textarea, { target: { value: question } });
  const send = document.querySelector('.ant-sender-actions-btn') as HTMLButtonElement;
  fireEvent.click(send);
}

describe('聊天入口配额 429 文案（M05-AC-17/18）', () => {
  beforeEach(() => {
    authStore.reset();
    authStore.setAuthenticated(DEMO_SESSION);
  });

  it('透传限额类型与重置时间，显示相对时间与 +08:00 绝对时间', async () => {
    const api = createFakeApi({
      listConversations: vi.fn(async () => pageOf([makeConversation()])),
      getConversation: vi.fn(async () => makeDetail([])),
      sendMessage: vi.fn(async function* stream() {
        // 配额在创建 run 前由 API 以 429 拒绝，SSE 生成器不产生任何事件。
        yield* [] as never[];
        throw quotaError();
      }),
    });
    render(withProviders(<ChatPage api={api} navigate={vi.fn()} pollIntervalMs={10} />));

    const conversation = await screen.findByRole('button', { name: '示例会话' });
    fireEvent.click(conversation);
    // 等待会话与文件面板完成异步读取后再输入，避免在 Sender 挂载前查询。
    await screen.findByText(/会话文件 \d\/5/);
    sendQuestion('请总结文件');

    expect(await screen.findByText(/已达日限额/)).toBeVisible();
    expect(screen.getByText(/还有 [23] 小时/)).toBeVisible();
    expect(screen.getByText(/\+08:00/)).toBeVisible();
    expect(screen.queryByText('请求过于频繁，请稍后重试。')).not.toBeInTheDocument();
    expect(screen.queryByText(/tenantId/)).not.toBeInTheDocument();
  });
});
