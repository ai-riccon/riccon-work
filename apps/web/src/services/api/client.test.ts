import { describe, expect, it, vi } from 'vitest';
import { ApiClient } from './client';
import { ApiError } from './errors';
import type { ChatEvent, SessionInfo } from './types';
import {
  DEMO_SESSION,
  GRANTED_MCP_TOOL,
  makeApproval,
  makeArtifact,
  makeFile,
  makeResearchDetail,
  makeResearchTask,
  ARTIFACT_MARKDOWN,
} from '@/test/fake-api';

/** 记录 fetch 调用。 */
interface FetchCall {
  url: string;
  init: RequestInit;
}

/**
 * 构造可控 fetch 替身。
 *
 * @param handler 按 URL 返回响应
 * @returns fetch 函数与调用记录
 */
function createFetch(handler: (url: string, init: RequestInit) => Response | Promise<Response>) {
  const calls: FetchCall[] = [];
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url =
      typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
    const requestInit = init ?? {};
    calls.push({ url, init: requestInit });
    return handler(url, requestInit);
  });
  return { fn: fn as unknown as typeof fetch, calls, spy: fn };
}

/** JSON 响应。 */
function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** ProblemDetail 响应。 */
function problem(status: number, code: string): Response {
  return new Response(
    JSON.stringify({
      type: 'about:blank',
      title: '错误',
      status,
      code,
      traceId: 'trace-1',
    }),
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  );
}

/** SSE 响应。 */
function sse(events: ChatEvent[]): Response {
  const encoder = new TextEncoder();
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      events.forEach((event) => {
        controller.enqueue(
          encoder.encode(`event: ${event.eventType}\ndata: ${JSON.stringify(event)}\n\n`),
        );
      });
      controller.close();
    },
  });
  return new Response(body, {
    status: 200,
    headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-store' },
  });
}

/** 一条 delta 事件。 */
const DELTA: ChatEvent = {
  eventId: 'e1',
  eventType: 'riccon.chat.delta.v1',
  version: 1,
  occurredAt: '2026-11-17T08:00:00.000Z',
  tenantId: 'tenant-a',
  runId: 'run-1',
  messageId: 'message-1',
  traceId: 'trace-1',
  data: { delta: '你好' },
};

describe('ApiClient', () => {
  it('读取会话并保存 CSRF；后续写请求自动携带令牌', async () => {
    const { fn, calls } = createFetch((url) => {
      if (url.endsWith('/auth/session')) return json(DEMO_SESSION);
      if (url.endsWith('/conversations')) {
        return json(
          {
            id: 'c1',
            workspaceId: 'w1',
            title: null,
            status: 'ACTIVE',
            lastMessageAt: null,
            createdAt: DEMO_SESSION.expiresAt,
            updatedAt: DEMO_SESSION.expiresAt,
          },
          201,
        );
      }
      return json({}, 404);
    });
    const client = new ApiClient({ baseUrl: 'http://api.test', fetchImpl: fn });

    const session = await client.getSession();
    expect(session.tenant.name).toBe('示例企业');

    await client.createConversation('w1');
    const post = calls.find((call) => call.url.endsWith('/conversations'));
    expect(post?.init.method).toBe('POST');
    expect((post?.init.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe(
      DEMO_SESSION.csrfToken,
    );
    expect(calls.some((call) => call.url.endsWith('/auth/csrf'))).toBe(false);
  });

  it('登录先取 CSRF，再使用登录响应轮换后的令牌', async () => {
    const rotated: SessionInfo = { ...DEMO_SESSION, csrfToken: 'rotated-token' };
    const { fn, calls } = createFetch((url) => {
      if (url.endsWith('/auth/csrf'))
        return json({ headerName: 'X-CSRF-TOKEN', token: 'login-token' });
      if (url.endsWith('/auth/login')) return json(rotated);
      if (url.endsWith('/parse-retries')) return json(makeFile(), 202);
      return json({}, 404);
    });
    const client = new ApiClient({ baseUrl: 'http://api.test', fetchImpl: fn });

    await client.login({ tenantCode: 'acme', username: 'demo', password: 'demo1234' });
    await client.retryFileParse('file-1');

    const loginCall = calls.find((call) => call.url.endsWith('/auth/login'));
    expect((loginCall?.init.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe('login-token');
    const retryCall = calls.find((call) => call.url.endsWith('/parse-retries'));
    expect((retryCall?.init.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe(
      'rotated-token',
    );
    expect((retryCall?.init.headers as Record<string, string>)['Idempotency-Key']).toBeTruthy();
  });

  it('登录 401 不触发会话过期回调，带登录态请求 401 会触发', async () => {
    const onUnauthorized = vi.fn();
    const { fn } = createFetch((url) => {
      if (url.endsWith('/auth/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' });
      if (url.endsWith('/auth/login')) return problem(401, 'RICCON-AUTH-001');
      if (url.includes('/api/v1/workspaces')) return problem(401, 'RICCON-AUTH-401');
      return json({}, 404);
    });
    const client = new ApiClient({ onUnauthorized, fetchImpl: fn });

    await expect(
      client.login({ tenantCode: 'acme', username: 'bad', password: 'bad' }),
    ).rejects.toMatchObject({ status: 401, code: 'RICCON-AUTH-001' });
    expect(onUnauthorized).not.toHaveBeenCalled();

    await expect(client.listWorkspaces()).rejects.toMatchObject({ status: 401 });
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });

  it('CSRF 令牌过期时刷新一次并重试写请求', async () => {
    let csrfCount = 0;
    let attempts = 0;
    const { fn, calls } = createFetch((url) => {
      if (url.endsWith('/auth/csrf')) {
        csrfCount += 1;
        return json({ headerName: 'X-CSRF-TOKEN', token: `csrf-${csrfCount}` });
      }
      if (url.endsWith('/conversations')) {
        attempts += 1;
        if (attempts === 1) return problem(403, 'RICCON-AUTH-403');
        return json(
          {
            id: 'c1',
            workspaceId: 'w1',
            title: null,
            status: 'ACTIVE',
            lastMessageAt: null,
            createdAt: DEMO_SESSION.expiresAt,
            updatedAt: DEMO_SESSION.expiresAt,
          },
          201,
        );
      }
      return json({}, 404);
    });
    const client = new ApiClient({ fetchImpl: fn });

    const conversation = await client.createConversation('w1');

    expect(conversation.id).toBe('c1');
    expect(csrfCount).toBe(2);
    expect(attempts).toBe(2);
    const posts = calls.filter((call) => call.url.endsWith('/conversations'));
    expect((posts[1].init.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe('csrf-2');
  });

  it('上传使用 multipart 且不手工设置 Content-Type', async () => {
    const { fn, calls } = createFetch((url) => {
      if (url.endsWith('/auth/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' });
      if (url.endsWith('/files')) return json(makeFile(), 201);
      return json({}, 404);
    });
    const client = new ApiClient({ fetchImpl: fn });
    const file = new File(['hello'], 'a.pdf', { type: 'application/pdf' });

    await client.uploadFile('w1', 'c1', file);

    const upload = calls.find((call) => call.url.endsWith('/files'));
    expect(upload?.init.body).toBeInstanceOf(FormData);
    const headers = upload?.init.headers as Record<string, string>;
    expect(headers['X-CSRF-TOKEN']).toBe('csrf');
    expect(headers['Content-Type']).toBeUndefined();
  });

  it('发送消息解析 SSE 并传递取消信号', async () => {
    const { fn, calls } = createFetch((url) => {
      if (url.endsWith('/auth/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' });
      if (url.endsWith('/messages')) return sse([DELTA]);
      return json({}, 404);
    });
    const client = new ApiClient({ fetchImpl: fn });
    const controller = new AbortController();
    const events: ChatEvent[] = [];

    for await (const event of client.sendMessage(
      { conversationId: 'c1', content: '你好', clientMessageId: 'client-message-1' },
      controller.signal,
    )) {
      events.push(event);
    }

    expect(events).toHaveLength(1);
    expect(events[0].eventType).toBe('riccon.chat.delta.v1');
    const streamCall = calls.find((call) => call.url.endsWith('/messages'));
    expect(streamCall?.init.signal).toBe(controller.signal);
    const headers = streamCall?.init.headers as Record<string, string>;
    expect(headers.Accept).toBe('text/event-stream');
    expect(headers['X-CSRF-TOKEN']).toBe('csrf');
    expect(streamCall?.init.body).toBe(
      JSON.stringify({ content: '你好', clientMessageId: 'client-message-1' }),
    );
  });

  it('SSE 401 触发过期回调并抛出 ApiError', async () => {
    const onUnauthorized = vi.fn();
    const { fn } = createFetch((url) => {
      if (url.endsWith('/auth/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' });
      if (url.endsWith('/messages')) return problem(401, 'RICCON-AUTH-401');
      return json({}, 404);
    });
    const client = new ApiClient({ onUnauthorized, fetchImpl: fn });

    const iterator = client.sendMessage({
      conversationId: 'c1',
      content: '你好',
      clientMessageId: 'client-message-1',
    });
    await expect(iterator.next()).rejects.toMatchObject({ status: 401 });
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });

  it('409 审批冲突保留 ProblemDetail 错误码且不可重试', async () => {
    const { fn } = createFetch((url) => {
      if (url.endsWith('/auth/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' });
      if (url.endsWith('/approvals/approval-1/decision')) {
        return problem(409, 'RICCON-APPROVAL-409');
      }
      return json({}, 404);
    });
    const client = new ApiClient({ fetchImpl: fn });

    await expect(
      client.decideApproval('approval-1', { decision: 'APPROVE' }, 'idem-conflict-1'),
    ).rejects.toMatchObject({
      status: 409,
      code: 'RICCON-APPROVAL-409',
      retryable: false,
      kind: 'http',
    });
  });

  it('网络失败转为 ApiError，取消保持 AbortError 语义', async () => {
    const { fn } = createFetch(() => {
      throw new TypeError('fetch failed');
    });
    const client = new ApiClient({ fetchImpl: fn });

    await expect(client.listWorkspaces()).rejects.toMatchObject({
      status: 0,
      kind: 'network',
      code: 'RICCON-NETWORK-000',
    });

    const abortFetch = vi.fn(async () => {
      throw new DOMException('aborted', 'AbortError');
    });
    const abortClient = new ApiClient({ fetchImpl: abortFetch as unknown as typeof fetch });
    await expect(abortClient.listWorkspaces()).rejects.toBeInstanceOf(DOMException);
  });

  it('204 返回 undefined，非法 JSON 抛客户端错误', async () => {
    const { fn } = createFetch((url) => {
      if (url.endsWith('/auth/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' });
      if (url.endsWith('/auth/logout')) return new Response(null, { status: 204 });
      if (url.includes('/api/v1/workspaces')) {
        return new Response('not-json', {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      return json({}, 404);
    });
    const client = new ApiClient({ fetchImpl: fn });

    await expect(client.logout()).resolves.toBeUndefined();
    await expect(client.listWorkspaces()).rejects.toBeInstanceOf(ApiError);
  });

  it('报告任务接口使用契约路径与幂等键', async () => {
    const { fn, calls } = createFetch((url) => {
      if (url.endsWith('/auth/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' });
      if (url.includes('/research-tasks?')) {
        return json({ page: 1, size: 100, total: 0, totalPages: 0, items: [] });
      }
      if (url.endsWith('/research-tasks')) return json(makeResearchTask(), 201);
      if (url.endsWith('/research-tasks/task-1')) return json(makeResearchDetail());
      if (url.endsWith('/research-tasks/task-1/retry')) return json(makeResearchTask(), 202);
      if (url.endsWith('/mcp-tools?page=1&size=100')) {
        return json({ page: 1, size: 100, total: 1, totalPages: 1, items: [GRANTED_MCP_TOOL] });
      }
      if (url.endsWith('/approvals/approval-1/decision')) return json(makeApproval());
      if (url.endsWith('/artifacts/artifact-1')) return json(makeArtifact());
      if (url.includes('/artifacts/artifact-1/versions?')) {
        return json({
          page: 1,
          size: 100,
          total: 1,
          totalPages: 1,
          items: [makeArtifact().currentVersionDetail],
        });
      }
      if (url.endsWith('/artifacts/artifact-1/versions/2')) {
        return json(makeArtifact().currentVersionDetail);
      }
      if (url.includes('/workspaces/w1/artifacts?')) {
        return json({
          page: 1,
          size: 100,
          total: 1,
          totalPages: 1,
          items: [makeArtifact()],
        });
      }
      if (url.endsWith('/artifacts/artifact-1/versions/2/download-url')) {
        return json({
          url: 'https://objects.test/report.md',
          expiresAt: '2026-11-17T09:00:00.000Z',
        });
      }
      return json({}, 404);
    });
    const client = new ApiClient({ baseUrl: 'http://api.test', fetchImpl: fn });

    await client.listResearchTasks('w1', 'FAILED');
    await client.createResearchTask(
      'w1',
      { topic: '市场调研', mcpToolId: 'tool-1' },
      'idem-create-1',
    );
    await client.getResearchTask('task-1');
    await client.retryResearchTask('task-1', { fromStage: 'TOOL' }, 'idem-retry-1');
    await client.listGrantedMcpTools();
    await client.decideApproval(
      'approval-1',
      { decision: 'REJECT', comment: '数据范围过大' },
      'idem-approval-1',
    );
    await client.listArtifacts('w1');
    await client.getArtifact('artifact-1');
    await client.listArtifactVersions('artifact-1');
    await client.getArtifactVersion('artifact-1', 2);
    await client.getArtifactDownloadUrl('artifact-1', 2);

    expect(
      calls.some((call) => call.url.endsWith('/workspaces/w1/artifacts?page=1&size=100')),
    ).toBe(true);
    expect(calls.some((call) => call.url.endsWith('/artifacts/artifact-1'))).toBe(true);
    expect(
      calls.some((call) => call.url.endsWith('/artifacts/artifact-1/versions?page=1&size=100')),
    ).toBe(true);

    expect(
      calls.some((call) => call.url.endsWith('/research-tasks?page=1&size=100&status=FAILED')),
    ).toBe(true);
    const create = calls.find((call) => call.url.endsWith('/research-tasks'));
    expect(create?.init.method).toBe('POST');
    expect((create?.init.headers as Record<string, string>)['Idempotency-Key']).toBe(
      'idem-create-1',
    );
    expect(create?.init.body).toBe(JSON.stringify({ topic: '市场调研', mcpToolId: 'tool-1' }));

    const retry = calls.find((call) => call.url.endsWith('/research-tasks/task-1/retry'));
    expect(retry?.init.body).toBe(JSON.stringify({ fromStage: 'TOOL' }));
    expect((retry?.init.headers as Record<string, string>)['Idempotency-Key']).toBe('idem-retry-1');

    const decision = calls.find((call) => call.url.endsWith('/approvals/approval-1/decision'));
    expect(decision?.init.body).toBe(
      JSON.stringify({ decision: 'REJECT', comment: '数据范围过大' }),
    );
    expect((decision?.init.headers as Record<string, string>)['Idempotency-Key']).toBe(
      'idem-approval-1',
    );
  });

  it('读取产物正文时不向对象存储发送 Cookie', async () => {
    const { fn, calls } = createFetch((url) => {
      if (url.endsWith('/auth/csrf')) return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf' });
      if (url.endsWith('/download-url')) {
        return json({
          url: 'https://objects.test/report.md',
          expiresAt: '2026-11-17T09:00:00.000Z',
        });
      }
      if (url === 'https://objects.test/report.md') {
        return new Response(ARTIFACT_MARKDOWN, {
          status: 200,
          headers: { 'Content-Type': 'text/markdown' },
        });
      }
      return json({}, 404);
    });
    const client = new ApiClient({ baseUrl: 'http://api.test', fetchImpl: fn });

    await expect(client.fetchArtifactContent('artifact-1', 1)).resolves.toContain('市场规模与增长');
    const objectCall = calls.find((call) => call.url === 'https://objects.test/report.md');
    expect(objectCall?.init.credentials).toBe('omit');
    expect(objectCall?.init.cache).toBe('no-store');
    expect((objectCall?.init.headers as Record<string, string>).Accept).toBe(
      'text/markdown, text/plain;q=0.9, */*;q=0.1',
    );
  });

  it('相对预签名地址拼接 baseUrl，正文超限给出不可重试错误', async () => {
    let contentLength = 0;
    const { fn, calls } = createFetch((url) => {
      if (url.endsWith('/download-url')) {
        return json({ url: '/objects/report.md', expiresAt: '2026-11-17T09:00:00.000Z' });
      }
      if (url === 'http://api.test/objects/report.md') {
        return new Response('正文', {
          status: 200,
          headers: { 'Content-Length': String(contentLength) },
        });
      }
      return json({}, 404);
    });
    const client = new ApiClient({ baseUrl: 'http://api.test', fetchImpl: fn });

    await expect(client.fetchArtifactContent('artifact-1', 1)).resolves.toBe('正文');
    expect(calls.some((call) => call.url === 'http://api.test/objects/report.md')).toBe(true);

    contentLength = 2 * 1024 * 1024 + 1;
    await expect(client.fetchArtifactContent('artifact-1', 1)).rejects.toMatchObject({
      status: 422,
      code: 'RICCON-CLIENT-ARTIFACT-TOO-LARGE',
      retryable: false,
    });
  });

  it('正文超过字符上限、对象存储失败与网络失败都归一为 ApiError', async () => {
    const { fn } = createFetch((url) => {
      if (url.endsWith('/download-url')) {
        return json({
          url: 'https://objects.test/report.md',
          expiresAt: '2026-11-17T09:00:00.000Z',
        });
      }
      if (url === 'https://objects.test/forbidden.md') return problem(403, 'RICCON-AUTH-403');
      if (url === 'https://objects.test/network.md') throw new TypeError('fetch failed');
      return new Response('x'.repeat(2 * 1024 * 1024 + 1), { status: 200 });
    });
    const client = new ApiClient({ fetchImpl: fn });

    await expect(client.fetchArtifactContent('artifact-1', 1)).rejects.toMatchObject({
      code: 'RICCON-CLIENT-ARTIFACT-TOO-LARGE',
    });

    const forbiddenClient = new ApiClient({
      fetchImpl: createFetch((url) => {
        if (url.endsWith('/download-url')) {
          return json({
            url: 'https://objects.test/forbidden.md',
            expiresAt: '2026-11-17T09:00:00.000Z',
          });
        }
        return problem(403, 'RICCON-AUTH-403');
      }).fn,
    });
    await expect(forbiddenClient.fetchArtifactContent('artifact-1', 1)).rejects.toMatchObject({
      status: 403,
    });

    const networkClient = new ApiClient({
      fetchImpl: createFetch((url) => {
        if (url.endsWith('/download-url')) {
          return json({
            url: 'https://objects.test/network.md',
            expiresAt: '2026-11-17T09:00:00.000Z',
          });
        }
        throw new TypeError('fetch failed');
      }).fn,
    });
    await expect(networkClient.fetchArtifactContent('artifact-1', 1)).rejects.toMatchObject({
      status: 0,
      kind: 'network',
    });
  });
});
