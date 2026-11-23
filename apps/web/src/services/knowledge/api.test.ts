import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import { KnowledgeClient, knowledgeDownloadPath, parseKnowledgeFilename } from './api';

/** 构造 JSON 响应。 */
function json(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** 构造 ProblemDetail 响应。 */
function problem(status: number, code: string, detail?: string): Response {
  return new Response(
    JSON.stringify({
      type: 'about:blank',
      title: '请求失败',
      status,
      code,
      traceId: 'trace-1',
      ...(detail ? { detail } : {}),
    }),
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  );
}

/** 构造最小文档响应。 */
function documentPayload(overrides: Record<string, unknown> = {}) {
  return {
    id: 'doc-1',
    title: '销售数据.xlsx',
    sourceType: 'UPLOAD',
    fileId: 'file-1',
    status: 'INDEXED',
    checksum: 'a'.repeat(64),
    mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    sizeBytes: 1024,
    createdAt: '2026-11-23T10:00:00+08:00',
    visibility: 'DEPT',
    departmentId: 'dept-sales',
    aclUserCount: 0,
    ...overrides,
  };
}

/** 记录 fetch 调用的替身。 */
function createFetch(handler: (url: string, init?: RequestInit) => Response) {
  const calls: Array<{ url: string; init?: RequestInit }> = [];
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, init });
    return handler(url, init);
  });
  return { fn: fn as unknown as typeof fetch, calls };
}

describe('知识库 API 客户端', () => {
  it('列表按状态与分页构造查询，不发送 tenantId', async () => {
    const { fn, calls } = createFetch(() =>
      json({ page: 1, size: 10, total: 1, totalPages: 1, items: [documentPayload()] }),
    );
    const client = new KnowledgeClient({ baseUrl: 'http://api.test', fetchImpl: fn });

    const result = await client.listDocuments({ page: 2, size: 10, status: 'FAILED' });

    expect(result.items).toHaveLength(1);
    expect(calls[0].url).toBe(
      'http://api.test/api/v1/knowledge/documents?page=2&size=10&status=FAILED',
    );
    expect(calls[0].url).not.toContain('tenantId');
    expect(calls[0].init?.credentials).toBe('same-origin');
  });

  it('详情与 ACL 使用契约路径', async () => {
    const { fn, calls } = createFetch((url) => {
      if (url.endsWith('/acl')) {
        return json({
          visibility: 'USERS',
          departmentId: null,
          creatorUserId: 'user-1',
          users: [{ userId: 'user-1', displayName: '李强', creator: true }],
        });
      }
      return json(documentPayload());
    });
    const client = new KnowledgeClient({ baseUrl: '', fetchImpl: fn });

    await client.getDocument('doc-1');
    const acl = await client.getDocumentAcl('doc-1');

    expect(calls.map((call) => call.url)).toEqual([
      '/api/v1/knowledge/documents/doc-1',
      '/api/v1/knowledge/documents/doc-1/acl',
    ]);
    expect(acl.users[0].creator).toBe(true);
  });

  it('ACL 整体替换使用 PUT + CSRF，TENANT 只发送 visibility', async () => {
    const { fn, calls } = createFetch(() => json(documentPayload({ visibility: 'TENANT' })));
    const client = new KnowledgeClient({
      baseUrl: '',
      fetchImpl: fn,
      csrfTokenProvider: () => 'csrf-1',
    });

    await client.updateDocumentAcl('doc-1', { visibility: 'TENANT' });

    expect(calls[0].url).toBe('/api/v1/knowledge/documents/doc-1/acl');
    expect(calls[0].init?.method).toBe('PUT');
    expect(calls[0].init?.body).toBe(JSON.stringify({ visibility: 'TENANT' }));
    expect((calls[0].init?.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe('csrf-1');
  });

  it('重索引 force 查询与幂等键由调用方决定；409 归一为 ApiError', async () => {
    const { fn, calls } = createFetch((_url, init) => {
      if (init?.method === 'POST') {
        return problem(409, 'RICCON-KB-409', '文档正在索引');
      }
      return json(documentPayload());
    });
    const client = new KnowledgeClient({
      baseUrl: '',
      fetchImpl: fn,
      csrfTokenProvider: () => 'csrf-1',
    });

    await expect(client.reindexDocument('doc-1', true)).rejects.toMatchObject({
      status: 409,
      code: 'RICCON-KB-409',
    });
    expect(calls[0].url).toBe('/api/v1/knowledge/documents/doc-1/reindex?force=true');
    expect(calls[0].init?.method).toBe('POST');
  });

  it('删除接受 204 并返回 undefined', async () => {
    const { fn, calls } = createFetch(() => new Response(null, { status: 204 }));
    const client = new KnowledgeClient({
      baseUrl: '',
      fetchImpl: fn,
      csrfTokenProvider: () => 'csrf-1',
    });

    await expect(client.deleteDocument('doc-1')).resolves.toBeUndefined();
    expect(calls[0].init?.method).toBe('DELETE');
  });

  it('下载走同源代理路径并解析 RFC 5987 文件名，不暴露预签名或 key', async () => {
    const { fn, calls } = createFetch(
      () =>
        new Response(new Blob(['hello']), {
          status: 200,
          headers: {
            'Content-Type': 'application/pdf',
            'Content-Disposition':
              'attachment; filename="report.pdf"; filename*=UTF-8\'\'%E9%94%80%E5%94%AE%E6%95%B0%E6%8D%AE.xlsx',
          },
        }),
    );
    const client = new KnowledgeClient({ baseUrl: '', fetchImpl: fn });

    const download = await client.downloadDocument('doc-1');

    expect(calls[0].url).toBe('/api/v1/knowledge/documents/doc-1/download');
    expect(calls[0].url).not.toContain('presign');
    expect(calls[0].url).not.toContain('key=');
    expect(calls[0].init?.credentials).toBe('same-origin');
    expect(download.filename).toBe('销售数据.xlsx');
    expect(download.blob.size).toBe(5);
    expect(client.documentDownloadPath('doc-1')).toBe(knowledgeDownloadPath('doc-1'));
  });

  it('无源文件返回 RICCON-KB-404，不误判为不可见', async () => {
    const { fn } = createFetch(() => problem(404, 'RICCON-KB-404', '该文档没有可下载的源文件'));
    const client = new KnowledgeClient({ baseUrl: '', fetchImpl: fn });

    await expect(client.downloadDocument('doc-1')).rejects.toMatchObject({
      status: 404,
      code: 'RICCON-KB-404',
    });
  });

  it('网络失败归一为 ApiError.network', async () => {
    const client = new KnowledgeClient({
      baseUrl: '',
      fetchImpl: (async () => {
        throw new Error('offline');
      }) as unknown as typeof fetch,
    });

    await expect(client.listDocuments()).rejects.toMatchObject({
      status: 0,
      code: 'RICCON-NETWORK-000',
    });
  });

  it('CSRF 失效时刷新令牌并重试一次', async () => {
    let attempts = 0;
    const { fn, calls } = createFetch((url) => {
      if (url.endsWith('/auth/csrf')) {
        return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf-new' });
      }
      attempts += 1;
      if (attempts === 1) {
        return problem(403, 'RICCON-AUTH-403');
      }
      return json(documentPayload({ visibility: 'TENANT' }));
    });
    const client = new KnowledgeClient({
      baseUrl: '',
      fetchImpl: fn,
      csrfTokenProvider: () => 'csrf-old',
    });

    await client.updateDocumentAcl('doc-1', { visibility: 'TENANT' });

    expect(attempts).toBe(2);
    expect(calls.some((call) => call.url.endsWith('/auth/csrf'))).toBe(true);
  });

  it('文件名解析对非法编码和缺失头提供稳定兜底', () => {
    expect(parseKnowledgeFilename(null, 'doc-1')).toBe('knowledge-doc-1');
    expect(parseKnowledgeFilename('attachment; filename="fallback.xlsx"', 'doc-1')).toBe(
      'fallback.xlsx',
    );
    expect(parseKnowledgeFilename("attachment; filename*=UTF-8''bad%E0%A4%A", 'doc-1')).toContain(
      'bad',
    );
  });

  it('网络层错误保持 ApiError 类型', async () => {
    const client = new KnowledgeClient({
      baseUrl: '',
      fetchImpl: (async () => {
        throw new Error('offline');
      }) as unknown as typeof fetch,
    });

    await expect(client.getDocument('doc-1')).rejects.toBeInstanceOf(ApiError);
  });
});
