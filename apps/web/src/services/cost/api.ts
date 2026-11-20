import { ApiError, toApiError } from '@/services/api/errors';
import type {
  CostInvocationPage,
  CostInvocationQuery,
  PlatformCostQuery,
  PlatformCostReport,
  TenantCostQuery,
  TenantCostReport,
} from './types';

/** 与 globalThis.fetch 兼容的函数类型；测试可注入替身。 */
export type CostFetchLike = typeof fetch;

/** 成本 API 客户端构造参数。 */
export interface CostClientOptions {
  /** API 基础地址；空字符串表示同源。 */
  baseUrl?: string;
  /** fetch 实现；默认使用 globalThis.fetch。 */
  fetchImpl?: CostFetchLike;
  /** 任意带登录态请求返回 401 时的回调。 */
  onUnauthorized?: () => void;
}

/**
 * M04 成本只读能力。
 *
 * 平台管理员只使用跨租户聚合；租户管理员使用本租户聚合与调用明细。
 * 平台不提供用户明细方法，避免前端误用（ADR-0022 §2.4）。
 */
export interface CostApi {
  /** 本租户成本聚合（`cost.read`）。 */
  getTenantCostReport(query: TenantCostQuery): Promise<TenantCostReport>;
  /** 平台跨租户成本聚合（`platform.cost.read`），不含用户维度。 */
  getPlatformCostReport(query: PlatformCostQuery): Promise<PlatformCostReport>;
  /** 本租户调用成本明细（`cost.read`）。 */
  listTenantCostInvocations(query: CostInvocationQuery): Promise<CostInvocationPage>;
}

/**
 * M04 成本 API 客户端。
 *
 * 为什么不直接扩展 `services/api/client.ts`：该文件是共享客户端且不在本任务写范围内；
 * 按 `AuditClient`/`UserAdminClient` 的既有模式独立成客户端，复用统一 `ApiError` 归一，
 * 不包含 mock 或演示成功分支。
 */
export class CostClient implements CostApi {
  private readonly baseUrl: string;

  private readonly fetchImpl: CostFetchLike;

  private readonly onUnauthorized?: () => void;

  /**
   * 创建成本客户端。
   *
   * @param options 基础地址、fetch 与会话过期回调
   */
  constructor(options: CostClientOptions = {}) {
    this.baseUrl = (options.baseUrl ?? '').replace(/\/$/, '');
    this.fetchImpl = options.fetchImpl ?? globalThis.fetch.bind(globalThis);
    this.onUnauthorized = options.onUnauthorized;
  }

  /**
   * 查询本租户成本聚合。
   *
   * @param query 时间范围、维度与分页
   * @returns 租户成本报告
   */
  getTenantCostReport(query: TenantCostQuery): Promise<TenantCostReport> {
    return this.request<TenantCostReport>(this.withQuery('/api/v1/costs', query));
  }

  /**
   * 查询平台跨租户成本聚合。
   *
   * @param query 时间范围、维度、可选租户过滤与分页
   * @returns 平台成本报告
   */
  getPlatformCostReport(query: PlatformCostQuery): Promise<PlatformCostReport> {
    return this.request<PlatformCostReport>(this.withQuery('/api/v1/admin/costs', query));
  }

  /**
   * 查询本租户调用成本明细。
   *
   * @param query 时间范围、用户/模型过滤与分页
   * @returns 调用明细分页
   */
  listTenantCostInvocations(query: CostInvocationQuery): Promise<CostInvocationPage> {
    return this.request<CostInvocationPage>(this.withQuery('/api/v1/costs/invocations', query));
  }

  /**
   * 执行 GET 请求并读取响应体。
   *
   * @param path API 路径
   * @returns 解析后的响应体
   */
  private async request<T>(path: string): Promise<T> {
    try {
      const response = await this.fetchImpl(`${this.baseUrl}${path}`, {
        method: 'GET',
        headers: { Accept: 'application/json' },
        credentials: 'same-origin',
      });
      if (response.status === 401) {
        this.onUnauthorized?.();
      }
      if (!response.ok) {
        throw await toApiError(response);
      }
      return await readJson<T>(response);
    } catch (error) {
      if (error instanceof ApiError) {
        throw error;
      }
      throw ApiError.network(error);
    }
  }

  /**
   * 拼接查询参数；空值与空字符串不发送，避免服务端把空条件当成有效过滤。
   *
   * @param path API 路径
   * @param query 查询参数对象
   * @returns 带查询字符串的路径
   */
  private withQuery(path: string, query: object): string {
    const search = new URLSearchParams();
    Object.entries(query as Record<string, unknown>).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== '') {
        search.set(key, String(value));
      }
    });
    const serialized = search.toString();
    return serialized ? `${path}?${serialized}` : path;
  }
}

/**
 * 读取 JSON 响应；204 或空响应返回 undefined。
 *
 * @param response fetch 响应
 * @returns 解析后的 JSON
 */
async function readJson<T>(response: Response): Promise<T> {
  if (response.status === 204) {
    return undefined as T;
  }
  const text = await response.text();
  if (!text) {
    return undefined as T;
  }
  try {
    return JSON.parse(text) as T;
  } catch {
    throw new ApiError('服务端返回了无法识别的响应。', {
      status: response.status,
      code: 'RICCON-CLIENT-BAD-JSON',
    });
  }
}
