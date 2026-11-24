import { ApiError, toApiError } from '@/services/api/errors';
import type {
  Connector,
  ConnectorCreateRequest,
  ConnectorCredential,
  ConnectorDocumentDisposition,
  ConnectorItemPage,
  ConnectorItemQuery,
  ConnectorPage,
  ConnectorQuery,
  ConnectorSyncAccepted,
  ConnectorTestResult,
  ConnectorUpdateRequest,
} from './types';

/** 与 globalThis.fetch 兼容的函数类型；测试可注入替身。 */
export type ConnectorFetchLike = typeof fetch;

/** 连接器 API 客户端构造参数。 */
export interface ConnectorClientOptions {
  /** API 基础地址；空字符串表示同源。 */
  baseUrl?: string;
  /** fetch 实现；默认使用 globalThis.fetch。 */
  fetchImpl?: ConnectorFetchLike;
  /** 从当前会话读取 CSRF 令牌；未登录时返回 null。 */
  csrfTokenProvider?: () => string | null;
  /** 任意带登录态请求返回 401 时的回调。 */
  onUnauthorized?: () => void;
}

/**
 * M05b 知识库连接器管理端能力。
 *
 * 页面只依赖该接口，组件测试注入替身；生产实现由 `ConnectorClient` 提供，
 * 复用统一 `ApiError` 归一与 CSRF 刷新语义。凭据只通过 `putConnectorCredential`
 * 和创建请求的 `credential` 字段写入，任何响应都不含明文或密文。
 */
export interface ConnectorApi {
  /** 分页列出本租户连接器；平台管理员由服务端返回 404。 */
  listConnectors(query?: ConnectorQuery): Promise<ConnectorPage>;
  /** 创建 S3 连接器；必须显式提供默认可见范围。 */
  createConnector(input: ConnectorCreateRequest, idempotencyKey?: string): Promise<Connector>;
  /** 部分更新连接器；未提供的字段保持原值。 */
  updateConnector(connectorId: string, input: ConnectorUpdateRequest): Promise<Connector>;
  /** 软删除连接器，并按 `documents` 处置已导入文档。 */
  deleteConnector(connectorId: string, documents?: ConnectorDocumentDisposition): Promise<void>;
  /** 写入或轮换凭据；响应只返回掩码。 */
  putConnectorCredential(connectorId: string, credential: ConnectorCredential): Promise<Connector>;
  /** 使用托管凭据做一次只读连接测试。 */
  testConnector(connectorId: string): Promise<ConnectorTestResult>;
  /** 立即受理一次同步；并发同步由服务端返回 409。 */
  syncConnector(connectorId: string, idempotencyKey?: string): Promise<ConnectorSyncAccepted>;
  /** 分页查询源对象同步状态。 */
  listConnectorItems(connectorId: string, query?: ConnectorItemQuery): Promise<ConnectorItemPage>;
}

/** 普通 JSON 请求选项。 */
interface ConnectorRequestOptions {
  method?: 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE';
  body?: unknown;
  signal?: AbortSignal;
  /** 幂等键等附加请求头。 */
  extraHeaders?: Record<string, string>;
}

/**
 * M05b 连接器 API 客户端。
 *
 * 为什么不直接扩展 `services/api/client.ts`：连接器端点集中在知识库管理域，
 * 独立客户端可以保持接口窄、测试注入简单，同时复用统一 `ApiError` 与 CSRF 语义。
 */
export class ConnectorClient implements ConnectorApi {
  private readonly baseUrl: string;

  private readonly fetchImpl: ConnectorFetchLike;

  private readonly csrfTokenProvider?: () => string | null;

  private readonly onUnauthorized?: () => void;

  private csrfToken: string | null = null;

  /**
   * 创建连接器客户端。
   *
   * @param options 基础地址、fetch、CSRF 与会话过期回调
   */
  constructor(options: ConnectorClientOptions = {}) {
    this.baseUrl = (options.baseUrl ?? '').replace(/\/$/, '');
    this.fetchImpl = options.fetchImpl ?? globalThis.fetch.bind(globalThis);
    this.csrfTokenProvider = options.csrfTokenProvider;
    this.onUnauthorized = options.onUnauthorized;
  }

  /**
   * 分页列出连接器。
   *
   * @param query 页码、每页大小与可选状态/类型
   * @returns 连接器分页
   */
  listConnectors(query: ConnectorQuery = {}): Promise<ConnectorPage> {
    const params = new URLSearchParams();
    params.set('page', String(query.page ?? 1));
    params.set('size', String(query.size ?? 10));
    if (query.status) {
      params.set('status', query.status);
    }
    if (query.type) {
      params.set('type', query.type);
    }
    return this.request<ConnectorPage>(`/api/v1/knowledge/connectors?${params.toString()}`);
  }

  /**
   * 创建连接器。
   *
   * @param input 创建请求；`defaultVisibility` 必须显式提供
   * @param idempotencyKey 可选幂等键
   * @returns 已创建的连接器（仅掩码）
   */
  createConnector(input: ConnectorCreateRequest, idempotencyKey?: string): Promise<Connector> {
    return this.request<Connector>('/api/v1/knowledge/connectors', {
      method: 'POST',
      body: input,
      ...(idempotencyKey ? { extraHeaders: { 'Idempotency-Key': idempotencyKey } } : {}),
    });
  }

  /**
   * 部分更新连接器。
   *
   * @param connectorId 连接器 ID
   * @param input 更新请求
   * @returns 更新后的连接器
   */
  updateConnector(connectorId: string, input: ConnectorUpdateRequest): Promise<Connector> {
    return this.request<Connector>(
      `/api/v1/knowledge/connectors/${encodeURIComponent(connectorId)}`,
      { method: 'PATCH', body: input },
    );
  }

  /**
   * 软删除连接器。
   *
   * @param connectorId 连接器 ID
   * @param documents 已导入文档处置；缺省 `KEEP`
   */
  async deleteConnector(
    connectorId: string,
    documents: ConnectorDocumentDisposition = 'KEEP',
  ): Promise<void> {
    const params = new URLSearchParams({ documents });
    await this.request<void>(
      `/api/v1/knowledge/connectors/${encodeURIComponent(connectorId)}?${params.toString()}`,
      { method: 'DELETE' },
    );
  }

  /**
   * 写入或轮换凭据。
   *
   * @param connectorId 连接器 ID
   * @param credential 只写凭据
   * @returns 更新后的连接器（仅掩码）
   */
  putConnectorCredential(connectorId: string, credential: ConnectorCredential): Promise<Connector> {
    return this.request<Connector>(
      `/api/v1/knowledge/connectors/${encodeURIComponent(connectorId)}/credential`,
      { method: 'PUT', body: credential },
    );
  }

  /**
   * 连接测试。
   *
   * 失败仍是 200，`success=false` 时按分类给出中文原因；401/403/404/503 统一抛 `ApiError`。
   *
   * @param connectorId 连接器 ID
   * @returns 测试结果
   */
  testConnector(connectorId: string): Promise<ConnectorTestResult> {
    return this.request<ConnectorTestResult>(
      `/api/v1/knowledge/connectors/${encodeURIComponent(connectorId)}/test`,
      { method: 'POST', body: {} },
    );
  }

  /**
   * 立即同步。
   *
   * @param connectorId 连接器 ID
   * @param idempotencyKey 可选幂等键
   * @returns 202 受理结果
   */
  syncConnector(connectorId: string, idempotencyKey?: string): Promise<ConnectorSyncAccepted> {
    return this.request<ConnectorSyncAccepted>(
      `/api/v1/knowledge/connectors/${encodeURIComponent(connectorId)}/sync`,
      {
        method: 'POST',
        body: {},
        ...(idempotencyKey ? { extraHeaders: { 'Idempotency-Key': idempotencyKey } } : {}),
      },
    );
  }

  /**
   * 分页查询同步条目。
   *
   * @param connectorId 连接器 ID
   * @param query 页码、每页大小与可选条目状态
   * @returns 同步条目分页
   */
  listConnectorItems(
    connectorId: string,
    query: ConnectorItemQuery = {},
  ): Promise<ConnectorItemPage> {
    const params = new URLSearchParams();
    params.set('page', String(query.page ?? 1));
    params.set('size', String(query.size ?? 10));
    if (query.status) {
      params.set('status', query.status);
    }
    return this.request<ConnectorItemPage>(
      `/api/v1/knowledge/connectors/${encodeURIComponent(connectorId)}/items?${params.toString()}`,
    );
  }

  /**
   * 执行 JSON 请求并读取响应体。
   *
   * @param path API 路径
   * @param options 方法、请求体与取消信号
   * @returns 解析后的响应体
   */
  private async request<T>(path: string, options: ConnectorRequestOptions = {}): Promise<T> {
    const method = options.method ?? 'GET';
    try {
      const response = await this.performRequest(
        path,
        options,
        method !== 'GET',
        'application/json',
      );
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
   * 发起 fetch，并在 CSRF 令牌失效时刷新一次后重试。
   *
   * @param path API 路径
   * @param options 请求选项
   * @param unsafe 是否需要 CSRF
   * @param accept Accept 头
   * @returns fetch Response
   */
  private async performRequest(
    path: string,
    options: ConnectorRequestOptions,
    unsafe: boolean,
    accept: string,
  ): Promise<Response> {
    const doFetch = async (retried: boolean): Promise<Response> => {
      const headers: Record<string, string> = { Accept: accept };
      if (unsafe) {
        headers['X-CSRF-TOKEN'] = await this.ensureCsrfToken(retried);
      }
      let body: string | undefined;
      if (options.body !== undefined) {
        headers['Content-Type'] = 'application/json';
        body = JSON.stringify(options.body);
      }
      Object.assign(headers, options.extraHeaders);

      const response = await this.fetchImpl(`${this.baseUrl}${path}`, {
        method: options.method ?? 'GET',
        headers,
        body,
        credentials: 'same-origin',
        signal: options.signal,
      });

      if (response.status === 401) {
        this.onUnauthorized?.();
      }

      // 令牌可能随会话轮换失效；只对写请求刷新一次，避免无限重试。
      if (unsafe && !retried && response.status === 403 && (await isCsrfError(response))) {
        this.csrfToken = null;
        return doFetch(true);
      }

      return response;
    };

    return doFetch(false);
  }

  /**
   * 获取 CSRF 令牌；优先使用当前会话令牌，失败后走 `/auth/csrf` 重新获取。
   *
   * @param forceRefresh 是否忽略会话令牌与本地缓存
   * @returns CSRF 令牌
   */
  private async ensureCsrfToken(forceRefresh = false): Promise<string> {
    if (!forceRefresh) {
      const fromSession = this.csrfTokenProvider?.();
      if (fromSession) {
        return fromSession;
      }
      if (this.csrfToken) {
        return this.csrfToken;
      }
    }

    const response = await this.fetchImpl(`${this.baseUrl}/api/v1/auth/csrf`, {
      method: 'GET',
      headers: { Accept: 'application/json' },
      credentials: 'same-origin',
    });
    if (!response.ok) {
      throw await toApiError(response);
    }
    const payload = (await response.json()) as { token: string };
    this.csrfToken = payload.token;
    return payload.token;
  }
}

/**
 * 读取 JSON 响应；204 或空响应返回 undefined。
 *
 * @param response 成功响应
 * @returns 解析后的响应体
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

/**
 * 判断 403 是否由 CSRF 令牌失效导致。
 *
 * 使用 clone 读取，保证原响应仍可交给 `toApiError`。
 *
 * @param response 403 响应
 * @returns 是否为 CSRF 错误
 */
async function isCsrfError(response: Response): Promise<boolean> {
  try {
    const problem = (await response.clone().json()) as { code?: string };
    return problem.code === 'RICCON-AUTH-403';
  } catch {
    return false;
  }
}
