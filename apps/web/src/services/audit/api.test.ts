import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import { AuditClient, parseFilename } from './api';
import type { AuditExportRequest, AuditEventPage, AuditPolicy } from './types';

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
  return { fn: fn as unknown as typeof fetch, calls };
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

/** 策略。 */
const POLICY: AuditPolicy = {
  retentionDays: 180,
  maxRangeDays: 180,
  pageSizeMax: 100,
  maxPage: 200,
  actions: ['file.uploaded'],
  exportLimits: { ttlHours: 24, rowLimit: 10000 },
};

/** 审计分页。 */
const EVENTS: AuditEventPage = {
  page: 2,
  size: 50,
  total: 1,
  totalPages: 1,
  items: [
    {
      eventId: 'event-1',
      occurredAt: '2026-11-19T13:00:00+08:00',
      tenantId: 'tenant-a',
      actorType: 'USER',
      actorId: 'user-1',
      actorDisplayName: '王敏',
      action: 'file.uploaded',
      targetType: 'FILE',
      targetId: 'file-1',
      outcome: 'SUCCESS',
      traceId: 'trace-1',
      details: { fileId: 'file-1' },
    },
  ],
};

/** 导出请求。 */
const EXPORT: AuditExportRequest = {
  id: 'export-1',
  status: 'PENDING_APPROVAL',
  requestedBy: 'user-1',
  requestedByDisplayName: '王敏',
  filter: { action: 'file.uploaded' },
  estimatedRowCount: 12,
  approvedBy: null,
  approvedByDisplayName: null,
  decidedAt: null,
  decisionComment: null,
  expiresAt: null,
  createdAt: '2026-11-19T10:00:00+08:00',
};

describe('AuditClient', () => {
  it('读取策略并按 OpenAPI 参数查询审计事件', async () => {
    const { fn, calls } = createFetch((url) => {
      if (url.includes('/policy')) {
        return json(POLICY);
      }
      return json(EVENTS);
    });
    const client = new AuditClient({
      baseUrl: 'http://api.test',
      fetchImpl: fn,
      csrfTokenProvider: () => 'session-token',
    });

    expect((await client.getPolicy()).retentionDays).toBe(180);
    const page = await client.listEvents({
      action: 'file.uploaded',
      occurredFrom: '2026-11-01T00:00:00+08:00',
      targetType: 'FILE',
      outcome: 'SUCCESS',
      page: 2,
      size: 50,
    });

    expect(page.items).toHaveLength(1);
    const url = new URL(calls[1].url);
    expect(url.pathname).toBe('/api/v1/audit-events');
    expect(url.searchParams.get('action')).toBe('file.uploaded');
    expect(url.searchParams.get('occurredFrom')).toBe('2026-11-01T00:00:00+08:00');
    expect(url.searchParams.get('targetType')).toBe('FILE');
    expect(url.searchParams.get('outcome')).toBe('SUCCESS');
    expect(url.searchParams.get('page')).toBe('2');
    expect(url.searchParams.get('size')).toBe('50');
    expect(url.searchParams.has('sort')).toBe(false);
  });

  it('导出申请携带 CSRF 与会话令牌并提交 filter', async () => {
    const { fn, calls } = createFetch(() => json(EXPORT, 201));
    const client = new AuditClient({
      baseUrl: 'http://api.test',
      fetchImpl: fn,
      csrfTokenProvider: () => 'session-token',
    });

    const created = await client.createExport({ action: 'file.uploaded' });
    expect(created.id).toBe('export-1');
    expect(calls[0].url).toBe('http://api.test/api/v1/audit-exports');
    expect(calls[0].init.method).toBe('POST');
    expect((calls[0].init.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe('session-token');
    expect(JSON.parse(String(calls[0].init.body))).toEqual({
      filter: { action: 'file.uploaded' },
    });
  });

  it('CSRF 失效时刷新令牌并只重试一次', async () => {
    let posts = 0;
    const { fn, calls } = createFetch((url, init) => {
      if (url.endsWith('/auth/csrf')) {
        return json({ headerName: 'X-CSRF-TOKEN', token: 'fresh-token' });
      }
      if (init.method === 'POST') {
        posts += 1;
        return posts === 1 ? problem(403, 'RICCON-AUTH-403') : json(EXPORT, 201);
      }
      return json({}, 404);
    });
    const client = new AuditClient({
      baseUrl: 'http://api.test',
      fetchImpl: fn,
      csrfTokenProvider: () => 'stale-token',
    });

    await client.createExport({ action: 'file.uploaded' });
    expect(posts).toBe(2);
    expect(calls.map((call) => new URL(call.url).pathname)).toContain('/api/v1/auth/csrf');
    expect((calls[2].init.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe('fresh-token');
  });

  it('下载 CSV 使用服务端文件名并返回 Blob', async () => {
    const { fn, calls } = createFetch(
      () =>
        new Response('eventId,action\n1,file.uploaded\n', {
          status: 200,
          headers: {
            'Content-Type': 'text/csv; charset=utf-8',
            'Content-Disposition': 'attachment; filename="audit-export-export-1.csv"',
            'Cache-Control': 'no-store',
          },
        }),
    );
    const client = new AuditClient({ baseUrl: 'http://api.test', fetchImpl: fn });

    const download = await client.downloadExport('export-1');
    expect(download.filename).toBe('audit-export-export-1.csv');
    expect(await download.blob.text()).toContain('file.uploaded');
    expect(calls[0].init.method).toBe('GET');
    expect((calls[0].init.headers as Record<string, string>).Accept).toBe('text/csv');
  });

  it('下载过期返回 410 稳定错误码', async () => {
    const { fn } = createFetch(() => problem(410, 'RICCON-AUDIT-410'));
    const client = new AuditClient({ baseUrl: 'http://api.test', fetchImpl: fn });

    await expect(client.downloadExport('export-1')).rejects.toMatchObject({
      status: 410,
      code: 'RICCON-AUDIT-410',
    } satisfies Partial<ApiError>);
  });

  it('401 触发会话过期回调并抛出 ApiError', async () => {
    const onUnauthorized = vi.fn();
    const { fn } = createFetch(() => problem(401, 'RICCON-AUTH-401'));
    const client = new AuditClient({
      baseUrl: 'http://api.test',
      fetchImpl: fn,
      onUnauthorized,
    });

    await expect(client.listExports()).rejects.toBeInstanceOf(ApiError);
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
  });

  it('平台审批使用目标租户路径并提交决定', async () => {
    const { fn, calls } = createFetch(() => json({ ...EXPORT, status: 'APPROVED' }));
    const client = new AuditClient({
      baseUrl: 'http://api.test',
      fetchImpl: fn,
      csrfTokenProvider: () => 'session-token',
    });

    await client.listPlatformExports('acme', { status: 'PENDING_APPROVAL', page: 1, size: 20 });
    expect(calls[0].url).toBe(
      'http://api.test/api/v1/admin/tenants/acme/audit-exports?status=PENDING_APPROVAL&page=1&size=20',
    );

    await client.decidePlatformExport('acme', 'export-1', {
      decision: 'APPROVE',
      comment: '已确认',
    });
    const decisionCall = calls[1];
    expect(decisionCall.url).toBe(
      'http://api.test/api/v1/admin/tenants/acme/audit-exports/export-1/decision',
    );
    expect(JSON.parse(String(decisionCall.init.body))).toEqual({
      decision: 'APPROVE',
      comment: '已确认',
    });
  });

  it('文件名缺失时使用导出 ID 兜底', () => {
    expect(parseFilename(null, 'export-9')).toBe('audit-export-export-9.csv');
    expect(parseFilename('attachment', 'export-9')).toBe('audit-export-export-9.csv');
  });
});
