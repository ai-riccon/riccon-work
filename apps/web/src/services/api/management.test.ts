import { describe, expect, it, vi } from 'vitest';
import { ApiClient } from './client';

/** 记录 fetch 调用。 */
interface FetchCall {
  url: string;
  init: RequestInit;
}

/**
 * 构造可控 fetch 替身。
 *
 * @param handler 按 URL 与方法返回响应
 * @returns fetch 函数与调用记录
 */
function createFetch(handler: (url: string, init: RequestInit) => Response) {
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

/**
 * 生成默认处理器：CSRF 返回令牌，DELETE 返回 204，其余返回空 JSON。
 *
 * @returns fetch 替身与调用记录
 */
function defaultFetch() {
  return createFetch((url, init) => {
    if (url.endsWith('/auth/csrf')) {
      return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf-test' });
    }
    if (init.method === 'DELETE') {
      return new Response(null, { status: 204 });
    }
    return json({});
  });
}

describe('M03 部门与空间管理客户端', () => {
  it('读取端点使用契约路径与分页参数', async () => {
    const { fn, calls } = defaultFetch();
    const client = new ApiClient({ baseUrl: 'http://api.test', fetchImpl: fn });

    await client.listTeamWorkspaces();
    await client.listDepartments();
    await client.listDepartmentMembers('dept-1');
    await client.listDepartmentWorkspaces('dept-1');
    await client.listWorkspaceMembers('ws-1');
    await client.listTenants();
    await client.listTenantDepartments('tenant-1');
    await client.listTenantDepartments('tenant-1', 'DISABLED');
    await client.listTenantDepartmentMembers('tenant-1', 'dept-1');
    await client.listTenantUsers('tenant-1');

    expect(calls.map((call) => call.url)).toEqual([
      'http://api.test/api/v1/workspaces?page=1&size=100&kind=TEAM',
      'http://api.test/api/v1/departments',
      'http://api.test/api/v1/departments/dept-1/members?page=1&size=100',
      'http://api.test/api/v1/departments/dept-1/workspaces?page=1&size=100',
      'http://api.test/api/v1/workspaces/ws-1/members?page=1&size=100',
      'http://api.test/api/v1/admin/tenants?page=1&size=100',
      'http://api.test/api/v1/admin/tenants/tenant-1/departments?page=1&size=100',
      'http://api.test/api/v1/admin/tenants/tenant-1/departments?page=1&size=100&status=DISABLED',
      'http://api.test/api/v1/admin/tenants/tenant-1/departments/dept-1/members?page=1&size=100',
      'http://api.test/api/v1/admin/tenants/tenant-1/users?page=1&size=100',
    ]);
  });

  it('写端点携带 CSRF、正确方法与请求体', async () => {
    const { fn, calls } = defaultFetch();
    const client = new ApiClient({ baseUrl: 'http://api.test', fetchImpl: fn });

    await client.createDepartmentWorkspace('dept-1', {
      name: '销售部空间',
      visibility: 'CUSTOM',
    });
    await client.createWorkspace({
      name: '市场空间',
      departmentId: 'dept-2',
      visibility: 'DEPT',
    });
    await client.updateWorkspace('ws-1', { visibility: 'CUSTOM' });
    await client.addWorkspaceMember('ws-1', 'user-1');
    await client.removeWorkspaceMember('ws-1', 'user-1');
    await client.createTenantDepartment('tenant-1', { name: '销售部' });
    await client.renameTenantDepartment('tenant-1', 'dept-1', { name: '销售一部' });
    await client.disableTenantDepartment('tenant-1', 'dept-1');
    await client.enableTenantDepartment('tenant-1', 'dept-1');
    await client.addTenantDepartmentMember('tenant-1', 'dept-1', 'user-1');
    await client.removeTenantDepartmentMember('tenant-1', 'dept-1', 'user-1');
    await client.transferTenantDepartmentManager('tenant-1', 'dept-1', 'user-2');

    const writeCalls = calls.filter((call) => !call.url.endsWith('/auth/csrf'));
    expect(
      writeCalls.map((call) => `${call.init.method} ${call.url.replace('http://api.test', '')}`),
    ).toEqual([
      'POST /api/v1/departments/dept-1/workspaces',
      'POST /api/v1/workspaces',
      'PATCH /api/v1/workspaces/ws-1',
      'POST /api/v1/workspaces/ws-1/members',
      'DELETE /api/v1/workspaces/ws-1/members/user-1',
      'POST /api/v1/admin/tenants/tenant-1/departments',
      'PATCH /api/v1/admin/tenants/tenant-1/departments/dept-1',
      'POST /api/v1/admin/tenants/tenant-1/departments/dept-1/disable',
      'POST /api/v1/admin/tenants/tenant-1/departments/dept-1/enable',
      'POST /api/v1/admin/tenants/tenant-1/departments/dept-1/members',
      'DELETE /api/v1/admin/tenants/tenant-1/departments/dept-1/members/user-1',
      'PUT /api/v1/admin/tenants/tenant-1/departments/dept-1/manager',
    ]);

    const bodies = writeCalls.map((call) => call.init.body);
    expect(bodies[0]).toBe(JSON.stringify({ name: '销售部空间', visibility: 'CUSTOM' }));
    expect(bodies[3]).toBe(JSON.stringify({ userId: 'user-1' }));
    expect(bodies[11]).toBe(JSON.stringify({ userId: 'user-2' }));
    writeCalls.forEach((call) => {
      expect((call.init.headers as Record<string, string>)['X-CSRF-TOKEN']).toBe('csrf-test');
    });
  });

  it('409 冲突保留错误码与可读 detail，供页面映射', async () => {
    const { fn } = createFetch((url) => {
      if (url.endsWith('/auth/csrf')) {
        return json({ headerName: 'X-CSRF-TOKEN', token: 'csrf-test' });
      }
      return new Response(
        JSON.stringify({
          type: 'about:blank',
          title: '冲突',
          status: 409,
          detail: 'department name already exists',
          code: 'RICCON-DEPT-409',
          traceId: 'trace-1',
        }),
        { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
      );
    });
    const client = new ApiClient({ fetchImpl: fn });

    await expect(
      client.createTenantDepartment('tenant-1', { name: '销售部' }),
    ).rejects.toMatchObject({
      status: 409,
      code: 'RICCON-DEPT-409',
      message: 'department name already exists',
    });
  });
});
