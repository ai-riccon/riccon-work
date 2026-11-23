import type { ChatMessage, Citation, ConversationDetail, Message } from '@/services/api/types';
import {
  citationListLabel,
  citationSourceText,
  citationLocatorText,
  toCitationView,
} from '@/services/knowledge/citation';

/**
 * 把服务端消息转换为前端展示模型。
 *
 * 规则：
 * 1. SYSTEM/TOOL 消息不进入 J1 对话气泡，避免暴露内部提示或工具原文；
 * 2. FAILED 消息只有 errorCode，缺少可读原因，统一给出可重试文案；
 * 3. 助手消息回溯最近一条用户消息作为 `question`，用于失败后重试。
 *
 * @param detail 会话详情
 * @returns 按 seq 升序排列的对话消息
 */
export function toChatMessages(detail: ConversationDetail): ChatMessage[] {
  const ordered = [...detail.messages].sort((left, right) => left.seq - right.seq);
  const result: ChatMessage[] = [];
  let lastQuestion = '';

  for (const message of ordered) {
    if (message.role === 'USER') {
      lastQuestion = message.content;
      result.push({
        id: `server-${message.id}`,
        role: 'user',
        content: message.content,
        status: 'COMPLETED',
        citations: [],
      });
      continue;
    }

    if (message.role !== 'ASSISTANT') {
      continue;
    }

    result.push({
      id: `server-${message.id}`,
      role: 'assistant',
      content: message.content,
      status: mapMessageStatus(message),
      citations: message.citations ?? [],
      runId: message.runId ?? undefined,
      question: lastQuestion || undefined,
      ...(message.status === 'FAILED'
        ? {
            error: {
              code: message.errorCode ?? 'RICCON-MODEL-500',
              message: '回答生成失败，请重试。',
              retryable: true,
            },
          }
        : {}),
    });
  }

  return result;
}

/**
 * 映射服务端消息状态到前端状态。
 *
 * @param message 服务端消息
 * @returns 前端展示状态
 */
export function mapMessageStatus(message: Message): ChatMessage['status'] {
  switch (message.status) {
    case 'COMPLETED':
      return 'COMPLETED';
    case 'STOPPED':
      return 'STOPPED';
    case 'FAILED':
      return 'FAILED';
    case 'PENDING':
    case 'STREAMING':
    default:
      return 'STREAMING';
  }
}

/**
 * 合并引用，按 ordinal 去重并升序排列。
 *
 * @param existing 已有引用
 * @param incoming 新到引用
 * @returns 合并后的引用
 */
export function mergeCitations(existing: Citation[], incoming: Citation[]): Citation[] {
  const byOrdinal = new Map<number, Citation>();
  existing.forEach((citation) => byOrdinal.set(citation.ordinal, citation));
  incoming.forEach((citation) => byOrdinal.set(citation.ordinal, citation));
  return [...byOrdinal.values()].sort((left, right) => left.ordinal - right.ordinal);
}

/**
 * 生成引用定位文案。
 *
 * @param citation 引用
 * @returns 例如 `第 3 页`、`工作表 销售数据!B2:D8`
 */
export function formatCitationLocator(citation: Citation): string {
  return citationLocatorText(toCitationView(citation));
}

/**
 * 生成引用列表按钮文案；不可访问/已删除时遮蔽原标题。
 *
 * @param citation 引用
 * @returns 例如 `[1] 季度报告.pdf` 或 `[2] 该引用不可访问`
 */
export function formatCitationListLabel(citation: Citation): string {
  return citationListLabel(toCitationView(citation));
}

/**
 * 引用来源类型的中文标签。
 *
 * @param citation 引用
 * @returns `文件` 或 `知识库`
 */
export function citationSourceLabel(citation: Citation): string {
  return citationSourceText(toCitationView(citation));
}

/**
 * 生成会话列表标题。
 *
 * @param conversation 会话
 * @returns 标题或稳定占位文案
 */
export function conversationLabel(conversation: { title: string | null }): string {
  return conversation.title?.trim() || '未命名会话';
}

/**
 * 根据失败的助手消息找到原始问题。
 *
 * @param messages 当前消息列表
 * @param assistantId 失败消息 ID
 * @returns 原始问题；找不到时返回 null
 */
export function findQuestionForRetry(messages: ChatMessage[], assistantId: string): string | null {
  const index = messages.findIndex((message) => message.id === assistantId);
  if (index < 0) {
    return null;
  }
  for (let cursor = index - 1; cursor >= 0; cursor -= 1) {
    const message = messages[cursor];
    if (message.role === 'user' && message.content.trim()) {
      return message.content;
    }
  }
  return null;
}

/** 本地消息 ID 的退化计数器；crypto 不可用时保证同一页面内唯一。 */
let fallbackCounter = 0;

/**
 * 生成客户端消息 ID，满足契约 8–64 字符要求。
 *
 * @returns UUID 或带时间戳的稳定 ID
 */
export function createClientMessageId(): string {
  if (typeof globalThis.crypto?.randomUUID === 'function') {
    return globalThis.crypto.randomUUID();
  }
  fallbackCounter += 1;
  return `msg-${Date.now()}-${fallbackCounter}`;
}
