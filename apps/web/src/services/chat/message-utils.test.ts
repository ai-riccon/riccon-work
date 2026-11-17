import { describe, expect, it } from 'vitest';
import type { ChatMessage, Citation, ConversationDetail } from '@/services/api/types';
import {
  citationSourceLabel,
  conversationLabel,
  createClientMessageId,
  findQuestionForRetry,
  formatCitationLocator,
  mapMessageStatus,
  mergeCitations,
  toChatMessages,
} from './message-utils';
import { makeDetail, makeMessage } from '@/test/fake-api';

/** 构造引用。 */
function citation(overrides: Partial<Citation> = {}): Citation {
  return {
    ordinal: 1,
    sourceType: 'FILE',
    fileId: 'file-1',
    locatorType: 'PAGE',
    pageNo: 3,
    title: '报告.pdf',
    snippet: '片段',
    ...overrides,
  };
}

describe('消息转换', () => {
  it('按 seq 排序、过滤系统消息并回溯原始问题', () => {
    const detail: ConversationDetail = makeDetail([
      makeMessage({ id: 'm2', seq: 2, role: 'ASSISTANT', content: '回答', status: 'COMPLETED' }),
      makeMessage({ id: 'sys', seq: 0, role: 'SYSTEM', content: '系统提示' }),
      makeMessage({ id: 'm1', seq: 1, role: 'USER', content: '问题' }),
    ]);

    const messages = toChatMessages(detail);

    expect(messages).toHaveLength(2);
    expect(messages[0]).toMatchObject({ id: 'server-m1', role: 'user', content: '问题' });
    expect(messages[1]).toMatchObject({
      id: 'server-m2',
      role: 'assistant',
      content: '回答',
      status: 'COMPLETED',
      question: '问题',
    });
  });

  it('失败消息补齐可重试错误', () => {
    const detail = makeDetail([
      makeMessage({ id: 'u', seq: 1, role: 'USER', content: '你好' }),
      makeMessage({
        id: 'a',
        seq: 2,
        role: 'ASSISTANT',
        content: '半截',
        status: 'FAILED',
        errorCode: 'RICCON-MODEL-500',
      }),
    ]);

    const assistant = toChatMessages(detail)[1];
    expect(assistant.status).toBe('FAILED');
    expect(assistant.error?.code).toBe('RICCON-MODEL-500');
    expect(assistant.error?.retryable).toBe(true);
  });

  it('映射服务端消息状态', () => {
    expect(mapMessageStatus(makeMessage({ status: 'STOPPED' }))).toBe('STOPPED');
    expect(mapMessageStatus(makeMessage({ status: 'STREAMING' }))).toBe('STREAMING');
  });
});

describe('引用与标题', () => {
  it('按 ordinal 合并去重并排序', () => {
    const merged = mergeCitations(
      [citation({ ordinal: 2, title: '旧' })],
      [citation({ ordinal: 1 }), citation({ ordinal: 2, title: '新' })],
    );

    expect(merged.map((item) => item.ordinal)).toEqual([1, 2]);
    expect(merged[1].title).toBe('新');
  });

  it('格式化三种定位与缺失定位', () => {
    expect(formatCitationLocator(citation())).toBe('第 3 页');
    expect(
      formatCitationLocator(
        citation({ locatorType: 'SHEET', sheetName: '销售', cellRange: 'B2:D8' }),
      ),
    ).toBe('工作表 销售（B2:D8）');
    expect(formatCitationLocator(citation({ locatorType: 'PARAGRAPH', paragraphNo: 5 }))).toBe(
      '第 5 段',
    );
    expect(formatCitationLocator(citation({ locatorType: undefined }))).toBe('位置未标注');
    expect(formatCitationLocator(citation({ locatorType: 'PAGE', pageNo: null }))).toBe(
      '页码未标注',
    );
  });

  it('引用来源与会话标题使用稳定文案', () => {
    expect(citationSourceLabel(citation())).toBe('文件');
    expect(citationSourceLabel(citation({ sourceType: 'KNOWLEDGE' }))).toBe('知识库');
    expect(conversationLabel({ title: null })).toBe('未命名会话');
    expect(conversationLabel({ title: '  ' })).toBe('未命名会话');
    expect(conversationLabel({ title: '季度复盘' })).toBe('季度复盘');
  });
});

describe('重试辅助', () => {
  it('找到失败消息前最近一条用户消息', () => {
    const messages: ChatMessage[] = [
      { id: 'u1', role: 'user', content: '第一个问题', status: 'COMPLETED', citations: [] },
      { id: 'a1', role: 'assistant', content: '', status: 'FAILED', citations: [] },
      { id: 'u2', role: 'user', content: '第二个问题', status: 'COMPLETED', citations: [] },
      { id: 'a2', role: 'assistant', content: '', status: 'FAILED', citations: [] },
    ];

    expect(findQuestionForRetry(messages, 'a2')).toBe('第二个问题');
    expect(findQuestionForRetry(messages, 'a1')).toBe('第一个问题');
    expect(findQuestionForRetry(messages, 'missing')).toBeNull();
  });

  it('客户端消息 ID 满足契约长度', () => {
    expect(createClientMessageId().length).toBeGreaterThanOrEqual(8);
  });
});
