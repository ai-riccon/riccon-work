import type { ChatEvent } from './types';

/**
 * 解析结果帧：只保留事件名与原始 data 字符串。
 * JSON 解析放在上层，以便未知事件或非法 JSON 可以按契约忽略而不中断整个回答。
 */
export interface SseFrame {
  event: string;
  data: string;
}

/**
 * 把字节流按 SSE 规范切分为事件帧。
 *
 * 处理要点：
 * 1. 帧以空行分隔，兼容 `\n\n` 与 `\r\n\r\n`；
 * 2. 一次读取可能包含半帧，必须保留 buffer；
 * 3. 忽略注释行（以 `:` 开头）与非 `event`/`data` 字段；
 * 4. 流结束时刷新剩余 buffer，避免最后一帧丢失。
 *
 * @param body 响应体 ReadableStream
 * @returns 事件帧异步迭代器
 */
export async function* parseSseStream(
  body: ReadableStream<Uint8Array>,
): AsyncGenerator<SseFrame, void, void> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) {
        break;
      }
      buffer += decoder.decode(value, { stream: true });
      const frames = extractFrames(buffer);
      buffer = frames.rest;
      for (const frame of frames.items) {
        yield frame;
      }
    }
    buffer += decoder.decode();
    for (const frame of extractFrames(`${buffer}\n\n`).items) {
      yield frame;
    }
  } finally {
    reader.releaseLock();
  }
}

/**
 * 从完整缓冲区中切出所有事件帧。
 *
 * @param buffer 当前文本缓冲
 * @returns 已完成的帧与剩余不完整文本
 */
function extractFrames(buffer: string): { items: SseFrame[]; rest: string } {
  const normalized = buffer.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
  const parts = normalized.split('\n\n');
  const rest = parts.pop() ?? '';
  const items: SseFrame[] = [];

  for (const part of parts) {
    const frame = parseFrame(part);
    if (frame) {
      items.push(frame);
    }
  }

  return { items, rest };
}

/**
 * 解析单个 SSE 帧。
 *
 * @param part 不含分隔空行的帧文本
 * @returns 事件帧；空帧或缺少 data 时返回 null
 */
function parseFrame(part: string): SseFrame | null {
  let event = 'message';
  const dataLines: string[] = [];

  for (const line of part.split('\n')) {
    if (!line || line.startsWith(':')) {
      continue;
    }
    const separator = line.indexOf(':');
    if (separator === -1) {
      continue;
    }
    const field = line.slice(0, separator);
    // SSE 规范：冒号后可选一个空格，多行 data 用换行拼接。
    const value = line.slice(separator + 1).replace(/^ /, '');
    if (field === 'event') {
      event = value;
    } else if (field === 'data') {
      dataLines.push(value);
    }
  }

  if (dataLines.length === 0) {
    return null;
  }

  return { event, data: dataLines.join('\n') };
}

/** 契约允许事件类型；未知事件必须被忽略。 */
const KNOWN_EVENTS = new Set<ChatEvent['eventType']>([
  'riccon.chat.started.v1',
  'riccon.chat.delta.v1',
  'riccon.chat.citation.v1',
  'riccon.chat.completed.v1',
  'riccon.chat.failed.v1',
  'riccon.chat.stopped.v1',
]);

/**
 * 把 SSE 帧解析为强类型对话事件。
 *
 * 契约要求消费方忽略未知事件与未知字段；因此未识别的事件、非法 JSON、
 * 以及 eventType 与 event 名不一致的帧都返回 null，由调用方跳过。
 *
 * @param frame SSE 帧
 * @returns 已识别的 ChatEvent 或 null
 */
export function parseChatEvent(frame: SseFrame): ChatEvent | null {
  if (!KNOWN_EVENTS.has(frame.event as ChatEvent['eventType'])) {
    return null;
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(frame.data);
  } catch {
    return null;
  }

  if (!parsed || typeof parsed !== 'object') {
    return null;
  }

  const candidate = parsed as Partial<ChatEvent> & { data?: unknown };
  if (typeof candidate.eventType !== 'string') {
    return null;
  }

  // 服务端可能使用 `event:` 字段与 envelope.eventType 两种来源；不一致时按未知事件处理，
  // 防止把 A 类型的数据当成 B 类型消费。
  if (candidate.eventType !== frame.event) {
    return null;
  }

  if (!candidate.data || typeof candidate.data !== 'object') {
    return null;
  }

  return candidate as ChatEvent;
}
