import { describe, expect, it } from 'vitest';
import { parseChatEvent, parseSseStream } from './sse';
import type { ChatEvent } from './types';

/**
 * 用分块字节流模拟网络传输。
 *
 * @param chunks UTF-8 文本块
 * @returns ReadableStream
 */
function streamOf(chunks: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  return new ReadableStream({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)));
      controller.close();
    },
  });
}

/**
 * 收集 SSE 帧。
 *
 * @param chunks 文本块
 * @returns 事件帧
 */
async function collect(chunks: string[]) {
  const frames = [];
  for await (const frame of parseSseStream(streamOf(chunks))) {
    frames.push(frame);
  }
  return frames;
}

/** 一条合法 delta 事件。 */
const DELTA_EVENT: ChatEvent = {
  eventId: 'event-1',
  eventType: 'riccon.chat.delta.v1',
  version: 1,
  occurredAt: '2026-11-17T08:00:00.000Z',
  tenantId: 'tenant-a',
  runId: 'run-1',
  messageId: 'message-1',
  traceId: 'trace-1',
  data: { delta: '你好' },
};

describe('SSE 解析', () => {
  it('按空行切帧并支持跨 chunk 的半帧', async () => {
    const text = `event: riccon.chat.delta.v1\ndata: ${JSON.stringify(DELTA_EVENT)}\n\n`;
    const frames = await collect([text.slice(0, 20), text.slice(20, 45), text.slice(45)]);

    expect(frames).toHaveLength(1);
    expect(frames[0].event).toBe('riccon.chat.delta.v1');
    expect(JSON.parse(frames[0].data).data.delta).toBe('你好');
  });

  it('兼容 CRLF、注释行与多行 data', async () => {
    const frames = await collect([
      ': keep-alive\r\n',
      `event: riccon.chat.failed.v1\r\ndata: {"eventType":"riccon.chat.failed.v1",\r\ndata: "data":{"code":"X","message":"y","retryable":true}}\r\n\r\n`,
    ]);

    expect(frames).toHaveLength(1);
    expect(frames[0].event).toBe('riccon.chat.failed.v1');
    expect(frames[0].data).toContain('"retryable":true');
  });

  it('流结束时刷新最后一帧', async () => {
    const frames = await collect([
      `event: riccon.chat.delta.v1\ndata: ${JSON.stringify(DELTA_EVENT)}`,
    ]);
    expect(frames).toHaveLength(1);
  });

  it('完整帧可解析为强类型事件', () => {
    const event = parseChatEvent({
      event: 'riccon.chat.delta.v1',
      data: JSON.stringify(DELTA_EVENT),
    });
    expect(event?.eventType).toBe('riccon.chat.delta.v1');
  });

  it('忽略非法 JSON、未知事件与事件名不一致的帧', () => {
    expect(parseChatEvent({ event: 'x', data: 'not-json' })).toBeNull();
    expect(
      parseChatEvent({
        event: 'unknown',
        data: JSON.stringify({ ...DELTA_EVENT, eventType: 'unknown' }),
      }),
    ).toBeNull();
    expect(
      parseChatEvent({
        event: 'riccon.chat.delta.v1',
        data: JSON.stringify({ ...DELTA_EVENT, eventType: 'riccon.chat.completed.v1' }),
      }),
    ).toBeNull();
    expect(parseChatEvent({ event: 'x', data: JSON.stringify({ eventType: 'x' }) })).toBeNull();
  });
});
