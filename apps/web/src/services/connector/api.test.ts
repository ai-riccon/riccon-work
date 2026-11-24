import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import { ConnectorClient } from './api';

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

/** 构造最小连接器响应。 */
function connectorPayload(overrides: Record<string, unknown> = {}) {
  return {
    id: 'connector-1',
    name: '销售合同归档',
    type: 'S3',
    status: 'ACTIVE',
    endpointId: 'internal-s3',
    config: {
      bucket: 'sales-archive',
      prefix: 'contracts/',
      region: 'cn-north-1',
      includeExtensions: ['pdf'],
      excludeGlobs: [],
    },
    defaultVisibility: 'TENANT',
    defaultDepartmentId: null,
    credentialMaskedHint: 'AKIA…7F2',
    scheduleEnabled: true,
    nextSyncAt: '2026-11-24T12:00:00+08:00',
    lastSyncStartedAt: '2026-11-24T09:00:00+08:00',
    lastSyncFinishedAt: '2026-11-24T09:01:00+08:00',
    lastSyncStatus: 'SUCCESS',
    lastSyncComplete: true,
    lastSuccessAt: '2026-11-24T09:01:00+08:00',
    lastErrorCode: null,
    lastSyncSummary: {
      scanned: 2,
      created: 1,
      updated: 1,
      skipped: 0,
      failed: 0,
      deleted: 0,
      complete: true,
    },
    createdBy: 'user-1',
    createdAt: '2026-11-20T10:00:00+08:00',
    updatedAt: '2026-11-24T09:01:00+08:00',
    ...overrides,
  };
}

/** 构造条目响应。 */
function itemPayload(overrides: Record<string, unknown> = {}) {
  return {
    id: 'item-1',
    externalId: 'contracts/a.pdf',
    externalVersion: 'etag-1',
    status: 'SYNCED',
    documentId: 'doc-1',
    generation: 1,
    errorCode: null,
    lastSeenAt: '2026-11-24T09:00:00+08:00',
    createdAt: '2026-11-20T10:00:00+08:00',
    updatedAt: '2026-11-24T09:01:00+08:00',
    deletedAt: null,
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

describe('连接器 API 客户端', () => {
  it('列表按分页、状态与类型构造查询，不发送 tenantId', async () => {
    const { fn, calls } = createFetch(() =>
      json({ page: 2, size: 10, total: 1, totalPages: 1, items: [connectorPayload()] }),
    );
    const client = new ConnectorClient({ baseUrl: 'http://api.test', fetchImpl: fn });

    const result = await client.listConnectors({ page: 2, size: 10, status: 'ERROR', type: 'S3' });

    expect(result.items).toHaveLength(1);
    expect(calls[0].url).toBe(
      'http://api.test/api/v1/knowledge/connectors?page=2&size=10&status=ERROR&type=S3',
    );
    expect(calls[0].url).not.toContain('tenantId');
    expect(calls[0].init?.credentials).toBe('same-origin');
  });

  it('创建使用 POST + CSRF + Idempotency-Key，并显式发送 defaultVisibility 与凭据', async () => {
    const { fn, calls } = createFetch(() => json(connectorPayload(), 201));
    const client = new ConnectorClient({
      baseUrl: '',
      fetchImpl: fn,
      csrfTokenProvider: () => 'csrf-1',
    });

    await client.createConnector(
      {
        name: '销售合同归档',
        type: 'S3',
        endpointId: 'internal-s3',
        config: { bucket: 'sales-archive', prefix: 'contracts/' },
        defaultVisibility: 'DEPT',
        defaultDepartmentId: 'dept-sales',
        scheduleEnabled: true,
        credential: { accessKeyId: 'TESTKEY-TEST', secretAccessKey: 'secret-test' },
      },
      'idem-key-1',
    );

    expect(calls[0].url).toBe('/api/v1/knowledge/connectors');
    expect(calls[0].init?.method).toBe('POST');
    expect(calls[0].init?.body).toBe(
      JSON.stringify({
        name: '销售合同归档',
        type: 'S3',
        endpointId: 'internal-s3',
        config: { bucket: 'sales-archive', prefix: 'contracts/' },
        defaultVisibility: 'DEPT',
        defaultDepartmentId: 'dept-sales',
        scheduleEnabled: true,
        credential: { accessKeyId: 'TESTKEY-TEST', secretAccessKey: 'secret-test' },
      }),
    );
    const headers = calls[0].init?.headers as Record<string, string>;
    expect(headers['X-CSRF-TOKEN']).toBe('csrf-1');
    expect(headers['Idempotency-Key']).toBe('idem-key-1');
  });

  it('更新使用 PATCH，只发送调用方提供的字段', async () => {
    const { fn, calls } = createFetch(() => json(connectorPayload({ scheduleEnabled: false })));
    const client = new ConnectorClient({
      baseUrl: '',
      fetchImpl: fn,
      csrfTokenProvider: () => 'csrf-1',
    });

    await client.updateConnector('connector-1', { scheduleEnabled: false, status: 'PAUSED' });

    expect(calls[0].url).toBe('/api/v1/knowledge/connectors/connector-1');
    expect(calls[0].init?.method).toBe('PATCH');
    expect(calls[0].init?.body).toBe(JSON.stringify({ scheduleEnabled: false, status: 'PAUSED' }));
  });

  it('凭据写入使用 PUT，响应只返回掩码且不会回显明文', async () => {
    const { fn, calls } = createFetch(() =>
      json(connectorPayload({ credentialMaskedHint: 'AKIA…NEW' })),
    );
    const client = new ConnectorClient({
      baseUrl: '',
      fetchImpl: fn,
      csrfTokenProvider: () => 'csrf-1',
    });

    const updated = await client.putConnectorCredential('connector-1', {
      accessKeyId: 'TESTKEY-SECRET',
      secretAccessKey: 'super-secret',
    });

    expect(calls[0].url).toBe('/api/v1/knowledge/connectors/connector-1/credential');
    expect(calls[0].init?.method).toBe('PUT');
    expect(calls[0].init?.body).toBe(
      JSON.stringify({ accessKeyId: 'TESTKEY-SECRET', secretAccessKey: 'super-secret' }),
    );
    expect(updated.credentialMaskedHint).toBe('AKIA…NEW');
    expect(JSON.stringify(updated)).not.toContain('super-secret');
  });

  it('连接测试与同步使用 POST 契约路径', async () => {
    let testCount = 0;
    const { fn, calls } = createFetch((url, init) => {
      if (init?.method === 'POST' && url.endsWith('/test')) {
        testCount += 1;
        return json({
          success: false,
          category: 'AUTH',
          message: '源端拒绝了凭据，请检查访问键与密钥是否有效。',
          testedAt: '2026-11-24T10:00:00+08:00',
        });
      }
      return json(
        {
          syncId: 'sync-1',
          connectorId: 'connector-1',
          status: 'PENDING',
          acceptedAt: '2026-11-24T10:05:00+08:00',
        },
        202,
      );
    });
    const client = new ConnectorClient({
      baseUrl: '',
      fetchImpl: fn,
      csrfTokenProvider: () => 'csrf-1',
    });

    const result = await client.testConnector('connector-1');
    const accepted = await client.syncConnector('connector-1');

    expect(testCount).toBe(1);
    expect(result).toMatchObject({ success: false, category: 'AUTH' });
    expect(calls[0].url).toBe('/api/v1/knowledge/connectors/connector-1/test');
    expect(calls[1].url).toBe('/api/v1/knowledge/connectors/connector-1/sync');
    expect(calls[1].init?.method).toBe('POST');
    expect(accepted.status).toBe('PENDING');
  });

  it('条目查询按分页与状态构造查询', async () => {
    const { fn, calls } = createFetch(() =>
      json({ page: 3, size: 20, total: 1, totalPages: 1, items: [itemPayload()] }),
    );
    const client = new ConnectorClient({ baseUrl: '', fetchImpl: fn });

    const result = await client.listConnectorItems('connector-1', {
      page: 3,
      size: 20,
      status: 'FAILED',
    });

    expect(result.items[0].status).toBe('SYNCED');
    expect(calls[0].url).toBe(
      '/api/v1/knowledge/connectors/connector-1/items?page=3&size=20&status=FAILED',
    );
  });

  it('删除使用 documents 查询，204 返回 undefined', async () => {
    const { fn, calls } = createFetch(() => new Response(null, { status: 204 }));
    const client = new ConnectorClient({
      baseUrl: '',
      fetchImpl: fn,
      csrfTokenProvider: () => 'csrf-1',
    });

    await expect(client.deleteConnector('connector-1', 'SOFT_DELETE')).resolves.toBeUndefined();
    expect(calls[0].url).toBe('/api/v1/knowledge/connectors/connector-1?documents=SOFT_DELETE');
    expect(calls[0].init?.method).toBe('DELETE');
    expect((calls[0].init?.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe('csrf-1');
  });

  it('删除默认传 KEEP', async () => {
    const { fn, calls } = createFetch(() => new Response(null, { status: 204 }));
    const client = new ConnectorClient({
      baseUrl: '',
      fetchImpl: fn,
      csrfTokenProvider: () => 'csrf-1',
    });

    await client.deleteConnector('connector-1');
    expect(calls[0].url).toBe('/api/v1/knowledge/connectors/connector-1?documents=KEEP');
  });

  it('403/404/409/503 归一为 ApiError，不误报成功', async () => {
    let attempt = 0;
    const { fn } = createFetch(() => {
      attempt += 1;
      if (attempt === 1) {
        return problem(403, 'RICCON-AUTH-403');
      }
      if (attempt === 2) {
        return problem(404, 'RICCON-TENANT-404');
      }
      if (attempt === 3) {
        return problem(409, 'RICCON-KB-409', 'connector sync is already pending or running');
      }
      return problem(503, 'RICCON-KB-503', 'connector feature is disabled');
    });
    const client = new ConnectorClient({
      baseUrl: '',
      fetchImpl: fn,
      csrfTokenProvider: () => 'csrf-1',
    });

    await expect(client.listConnectors()).rejects.toMatchObject({ status: 403 });
    await expect(client.listConnectorItems('connector-1')).rejects.toMatchObject({ status: 404 });
    await expect(client.syncConnector('connector-1')).rejects.toMatchObject({ status: 409 });
    await expect(client.testConnector('connector-1')).rejects.toMatchObject({ status: 503 });
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
      return json(connectorPayload({ scheduleEnabled: false }));
    });
    const client = new ConnectorClient({
      baseUrl: '',
      fetchImpl: fn,
      csrfTokenProvider: () => 'csrf-old',
    });

    await client.updateConnector('connector-1', { scheduleEnabled: false });

    expect(attempts).toBe(2);
    expect(calls.some((call) => call.url.endsWith('/auth/csrf'))).toBe(true);
  });

  it('网络失败归一为 ApiError.network', async () => {
    const client = new ConnectorClient({
      baseUrl: '',
      fetchImpl: (async () => {
        throw new Error('offline');
      }) as unknown as typeof fetch,
    });

    await expect(client.listConnectors()).rejects.toBeInstanceOf(ApiError);
    await expect(client.listConnectors()).rejects.toMatchObject({
      status: 0,
      code: 'RICCON-NETWORK-000',
    });
  });
});
