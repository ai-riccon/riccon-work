import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import { authStore } from '@/services/auth/auth-store';
import { DEMO_SESSION } from '@/test/fake-api';
import type { WorkflowDefinitionDetail } from './workflow-api';
import { createWorkflowApi, MARKET_RESEARCH_TEMPLATE_ID } from './workflow-api';

/**
 * 编排 API 客户端测试。
 *
 * 覆盖列表查询参数、请求体、发布幂等键、CSRF 头与失效重试、错误归一化和目录接口；
 * 页面测试注入替身，本测试确保默认真实客户端与契约一致。
 */

/** 构造 JSON 响应。 */
function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

/** 分页空响应。 */
const EMPTY_PAGE = { page: 1, size: 20, total: 0, totalPages: 0, items: [] };

/** 流程摘要。 */
const WORKFLOW = {
  id: 'workflow-1',
  name: '销售部流程',
  description: null,
  ownerUserId: 'user-1',
  status: 'ACTIVE' as const,
  draftVersion: 2,
  publishedVersionId: null,
  publishedVersionNo: null,
  hasUnpublishedChanges: true,
  createdAt: '2026-11-25T08:00:00+08:00',
  updatedAt: '2026-11-25T08:00:00+08:00',
};

/** 空草稿图。 */
const EMPTY_GRAPH = { schemaVersion: 1 as const, nodes: [], edges: [] };

/** 流程详情。 */
const DETAIL: WorkflowDefinitionDetail = {
  ...WORKFLOW,
  draftGraph: EMPTY_GRAPH,
  latestVersion: null,
  canEdit: true,
  canPublish: true,
  canRun: false,
};

/** 捕获调用。 */
interface CapturedCall {
  url: string;
  init?: RequestInit;
}

/**
 * 创建捕获 fetch。
 *
 * @param handler 响应处理器
 * @returns fetch 替身与调用记录
 */
function createFetch(handler: (call: CapturedCall) => Response | Promise<Response>) {
  const calls: CapturedCall[] = [];
  const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const call = { url: String(input), init };
    calls.push(call);
    return handler(call);
  });
  return { calls, fetchImpl: fetchImpl as unknown as typeof fetch };
}

describe('createWorkflowApi', () => {
  beforeEach(() => {
    authStore.reset();
  });

  it('列表请求带视图、状态与分页参数', async () => {
    const { calls, fetchImpl } = createFetch(() => jsonResponse(EMPTY_PAGE));
    const api = createWorkflowApi({
      baseUrl: 'https://api.test',
      fetchImpl,
      csrfToken: () => 'csrf',
    });
    await api.listWorkflows({ view: 'RUNNABLE', status: 'ACTIVE', page: 2, size: 10 });
    expect(calls[0].url).toBe(
      'https://api.test/api/v1/workflows?view=RUNNABLE&page=2&size=10&status=ACTIVE',
    );
  });

  it('创建模板流程发送 templateId，保存草稿发送版本与图', async () => {
    const { calls, fetchImpl } = createFetch(() => jsonResponse(DETAIL, 201));
    const api = createWorkflowApi({
      baseUrl: 'https://api.test',
      fetchImpl,
      csrfToken: () => 'csrf',
    });
    await api.createWorkflow({ templateId: MARKET_RESEARCH_TEMPLATE_ID });
    expect(calls[0].url).toContain('/api/v1/workflows');
    expect(calls[0].init?.method).toBe('POST');
    expect(calls[0].init?.headers).toMatchObject({ 'X-CSRF-TOKEN': 'csrf' });
    expect(JSON.parse(String(calls[0].init?.body))).toEqual({
      templateId: MARKET_RESEARCH_TEMPLATE_ID,
    });

    await api.saveDraft('workflow-1', {
      version: 2,
      name: '流程',
      graph: EMPTY_GRAPH,
    });
    expect(calls[1].url).toContain('/api/v1/workflows/workflow-1/draft');
    expect(calls[1].init?.method).toBe('PUT');
    expect(JSON.parse(String(calls[1].init?.body))).toMatchObject({ version: 2, name: '流程' });
  });

  it('另存副本可省略 body，校验使用 query 范围参数', async () => {
    const { calls, fetchImpl } = createFetch((call) => {
      if (call.url.includes('/duplicate')) {
        return jsonResponse(DETAIL, 201);
      }
      return jsonResponse({ valid: true, issues: [] });
    });
    const api = createWorkflowApi({
      baseUrl: 'https://api.test',
      fetchImpl,
      csrfToken: () => 'csrf',
    });
    await api.duplicateWorkflow('workflow-1');
    expect(JSON.parse(String(calls[0].init?.body))).toEqual({});
    await api.validateWorkflow('workflow-1', {
      runScope: 'DEPARTMENT',
      departmentId: 'dept-1',
    });
    expect(calls[1].url).toContain('runScope=DEPARTMENT&departmentId=dept-1');
    expect(calls[1].init?.method).toBe('POST');
  });

  it('发布带 Idempotency-Key，目录接口带分页', async () => {
    const version = {
      id: 'version-1',
      workflowId: 'workflow-1',
      versionNo: 1,
      runScope: 'ONLY_SELF',
      graphSha256: 'a'.repeat(64),
      nodeCount: 3,
      publishedBy: 'user-1',
      publishedAt: '2026-11-25T08:00:00+08:00',
    };
    const { calls, fetchImpl } = createFetch((call) => {
      if (call.url.includes('/publish')) {
        return jsonResponse(version, 201);
      }
      if (call.url.includes('/models')) {
        return jsonResponse(EMPTY_PAGE);
      }
      if (call.url.includes('/mcp-tools')) {
        return jsonResponse(EMPTY_PAGE);
      }
      return jsonResponse(EMPTY_PAGE);
    });
    const api = createWorkflowApi({
      baseUrl: 'https://api.test',
      fetchImpl,
      csrfToken: () => 'csrf',
    });
    await api.publishWorkflow('workflow-1', { runScope: 'ONLY_SELF' });
    const headers = calls[0].init?.headers as Record<string, string>;
    expect(headers['Idempotency-Key'].length).toBeGreaterThanOrEqual(8);
    expect(JSON.parse(String(calls[0].init?.body))).toEqual({ runScope: 'ONLY_SELF' });
    await api.listModels();
    await api.listMcpTools();
    await api.listDepartments();
    expect(calls[1].url).toContain('/api/v1/models?page=1&size=100');
    expect(calls[2].url).toContain('/api/v1/mcp-tools?page=1&size=100');
    expect(calls[3].url).toContain('/api/v1/departments');
  });

  it('错误响应归一化为 ApiError，保留状态码与业务码', async () => {
    const { fetchImpl } = createFetch(() =>
      jsonResponse(
        {
          type: 'about:blank',
          title: '冲突',
          status: 409,
          code: 'RICCON-WF-409',
          detail: 'draft version does not match',
        },
        409,
      ),
    );
    const api = createWorkflowApi({
      baseUrl: 'https://api.test',
      fetchImpl,
      csrfToken: () => 'csrf',
    });
    await expect(
      api.saveDraft('workflow-1', { version: 1, graph: EMPTY_GRAPH }),
    ).rejects.toMatchObject({ status: 409, code: 'RICCON-WF-409' });
  });

  it('CSRF 失效时刷新令牌并重试一次', async () => {
    let attempt = 0;
    let tokenCount = 0;
    const csrf = vi.fn(() => `csrf-${++tokenCount}`);
    const { calls, fetchImpl } = createFetch(() => {
      attempt += 1;
      if (attempt === 1) {
        return jsonResponse(
          { type: 'about:blank', title: 'CSRF', status: 403, code: 'RICCON-AUTH-403' },
          403,
        );
      }
      return jsonResponse(DETAIL);
    });
    const api = createWorkflowApi({ baseUrl: 'https://api.test', fetchImpl, csrfToken: csrf });
    await api.createWorkflow({ name: '流程' });
    expect(calls).toHaveLength(2);
    expect(csrf).toHaveBeenCalledTimes(2);
  });

  it('CSRF 失效时即使有会话令牌也强制刷新', async () => {
    authStore.setAuthenticated(DEMO_SESSION);
    let attempt = 0;
    const { calls, fetchImpl } = createFetch((call) => {
      if (call.url.endsWith('/api/v1/auth/csrf')) {
        return jsonResponse({ token: 'fresh-token' });
      }
      attempt += 1;
      if (attempt === 1) {
        return jsonResponse(
          { type: 'about:blank', title: 'CSRF', status: 403, code: 'RICCON-AUTH-403' },
          403,
        );
      }
      return jsonResponse(DETAIL);
    });
    const api = createWorkflowApi({ baseUrl: 'https://api.test', fetchImpl });
    await api.createWorkflow({ name: '流程' });
    expect(calls.map((call) => call.url)).toEqual([
      'https://api.test/api/v1/workflows',
      'https://api.test/api/v1/auth/csrf',
      'https://api.test/api/v1/workflows',
    ]);
    expect(calls[2].init?.headers).toMatchObject({ 'X-CSRF-TOKEN': 'fresh-token' });
  });

  it('默认 CSRF 优先使用已恢复会话，不额外请求令牌端点', async () => {
    authStore.setAuthenticated(DEMO_SESSION);
    const { calls, fetchImpl } = createFetch(() => jsonResponse(DETAIL));
    const api = createWorkflowApi({ baseUrl: 'https://api.test', fetchImpl });
    await api.createWorkflow({ name: '流程' });
    expect(calls).toHaveLength(1);
    expect(calls[0].init?.headers).toMatchObject({
      'X-CSRF-TOKEN': DEMO_SESSION.csrfToken,
    });
  });

  it('无会话时从 /auth/csrf 获取令牌', async () => {
    const { calls, fetchImpl } = createFetch((call) => {
      if (call.url.endsWith('/api/v1/auth/csrf')) {
        return jsonResponse({ token: 'anon-token' });
      }
      return jsonResponse(DETAIL);
    });
    const api = createWorkflowApi({ baseUrl: 'https://api.test', fetchImpl });
    await api.createWorkflow({ name: '流程' });
    expect(calls[0].url).toBe('https://api.test/api/v1/auth/csrf');
    expect(calls[1].init?.headers).toMatchObject({ 'X-CSRF-TOKEN': 'anon-token' });
  });

  it('CSRF 端点失败时抛出 ApiError', async () => {
    const { fetchImpl } = createFetch(() =>
      jsonResponse(
        { type: 'about:blank', title: '失败', status: 500, code: 'RICCON-INTERNAL-500' },
        500,
      ),
    );
    const api = createWorkflowApi({ baseUrl: 'https://api.test', fetchImpl });
    await expect(api.createWorkflow({ name: '流程' })).rejects.toBeInstanceOf(ApiError);
  });
});
