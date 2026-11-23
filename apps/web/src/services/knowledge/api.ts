import { ApiError, toApiError } from '@/services/api/errors';
import type {
  KnowledgeDocument,
  KnowledgeDocumentAcl,
  KnowledgeDocumentAclUpdate,
  KnowledgeDocumentPage,
  KnowledgeDocumentQuery,
  KnowledgeDownload,
} from './types';

/** 与 globalThis.fetch 兼容的函数类型；测试可注入替身。 */
export type KnowledgeFetchLike = typeof fetch;

/** 知识库 API 客户端构造参数。 */
export interface KnowledgeClientOptions {
  /** API 基础地址；空字符串表示同源。 */
  baseUrl?: string;
  /** fetch 实现；默认使用 globalThis.fetch。 */
  fetchImpl?: KnowledgeFetchLike;
  /** 从当前会话读取 CSRF 令牌；未登录时返回 null。 */
  csrfTokenProvider?: () => string | null;
  /** 任意带登录态请求返回 401 时的回调。 */
  onUnauthorized?: () => void;
}

/**
 * M05 知识库员工端能力。
 *
 * 列表/详情/ACL/下载/重索引/删除都只调用 apps/api；下载始终走服务端代理，
 * 前端不接触对象 key、预签名 URL 或存储端点。页面只依赖该接口，
 * 组件测试注入替身，不发起真实网络请求。
 */
export interface KnowledgeApi {
  /** 按文档 ACL 列出当前账号可见的文档。 */
  listDocuments(query?: KnowledgeDocumentQuery): Promise<KnowledgeDocumentPage>;
  /** 读取单个文档详情；不可见统一 404。 */
  getDocument(documentId: string): Promise<KnowledgeDocument>;
  /** 读取文档 ACL；仅创建人或 `knowledge.acl.manage` 成功。 */
  getDocumentAcl(documentId: string): Promise<KnowledgeDocumentAcl>;
  /** 整体替换文档 ACL；保存后下一次请求生效。 */
  updateDocumentAcl(
    documentId: string,
    input: KnowledgeDocumentAclUpdate,
  ): Promise<KnowledgeDocument>;
  /** 触发文档级 reindex；`force=false` 只补缺失页。 */
  reindexDocument(documentId: string, force?: boolean): Promise<KnowledgeDocument>;
  /** 软删除文档；重复删除幂等。 */
  deleteDocument(documentId: string): Promise<void>;
  /** 通过服务端代理下载源文件；不返回对象 key 或预签名 URL。 */
  downloadDocument(documentId: string, signal?: AbortSignal): Promise<KnowledgeDownload>;
  /** 下载端点路径；仅用于展示/测试同源约束，不用于直接暴露给用户。 */
  documentDownloadPath(documentId: string): string;
}

/** 普通 JSON 请求选项。 */
interface KnowledgeRequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  body?: unknown;
  signal?: AbortSignal;
}

/**
 * 构造知识库下载端点路径。
 *
 * 路径固定同源，不接受 baseUrl 之外的对象存储地址；页面只能通过
 * `downloadDocument` 使用会话 Cookie 获取字节。
 *
 * @param documentId 文档 ID
 * @returns `/api/v1/knowledge/documents/{id}/download`
 */
export function knowledgeDownloadPath(documentId: string): string {
  return `/api/v1/knowledge/documents/${encodeURIComponent(documentId)}/download`;
}

/**
 * 从 Content-Disposition 解析下载文件名。
 *
 * 优先使用 RFC 5987 的 `filename*=UTF-8''`；解析失败时回退到
 * `filename=`，最后回退到固定前缀，避免把响应头原样展示给用户。
 *
 * @param header 响应头
 * @param documentId 文档 ID，用于兜底
 * @returns 安全文件名
 */
export function parseKnowledgeFilename(header: string | null, documentId: string): string {
  if (header) {
    const encoded = /filename\*=UTF-8''([^";]+)/i.exec(header);
    if (encoded?.[1]) {
      try {
        return decodeURIComponent(encoded[1]);
      } catch {
        return encoded[1];
      }
    }
    const plain = /filename="?([^";]+)"?/i.exec(header);
    if (plain?.[1]) {
      try {
        return decodeURIComponent(plain[1]);
      } catch {
        return plain[1];
      }
    }
  }
  return `knowledge-${documentId}`;
}

/**
 * M05 知识库 API 客户端。
 *
 * 为什么不直接扩展 `services/api/client.ts`：知识库端点独立、下载需要二进制
 * 响应处理；本客户端复用统一 `ApiError` 归一与 CSRF 刷新语义，
 * 不包含 mock 或演示成功分支。
 */
export class KnowledgeClient implements KnowledgeApi {
  private readonly baseUrl: string;

  private readonly fetchImpl: KnowledgeFetchLike;

  private readonly csrfTokenProvider?: () => string | null;

  private readonly onUnauthorized?: () => void;

  private csrfToken: string | null = null;

  /**
   * 创建知识库客户端。
   *
   * @param options 基础地址、fetch、CSRF 与会话过期回调
   */
  constructor(options: KnowledgeClientOptions = {}) {
    this.baseUrl = (options.baseUrl ?? '').replace(/\/$/, '');
    this.fetchImpl = options.fetchImpl ?? globalThis.fetch.bind(globalThis);
    this.csrfTokenProvider = options.csrfTokenProvider;
    this.onUnauthorized = options.onUnauthorized;
  }

  /**
   * 分页列出可见文档。
   *
   * @param query 页码、每页大小与可选状态
   * @returns 文档分页
   */
  listDocuments(query: KnowledgeDocumentQuery = {}): Promise<KnowledgeDocumentPage> {
    const params = new URLSearchParams();
    params.set('page', String(query.page ?? 1));
    params.set('size', String(query.size ?? 20));
    if (query.status) {
      params.set('status', query.status);
    }
    return this.request<KnowledgeDocumentPage>(`/api/v1/knowledge/documents?${params.toString()}`);
  }

  /**
   * 读取文档详情。
   *
   * @param documentId 文档 ID
   * @returns 文档
   */
  getDocument(documentId: string): Promise<KnowledgeDocument> {
    return this.request<KnowledgeDocument>(
      `/api/v1/knowledge/documents/${encodeURIComponent(documentId)}`,
    );
  }

  /**
   * 读取文档 ACL。
   *
   * @param documentId 文档 ID
   * @returns ACL
   */
  getDocumentAcl(documentId: string): Promise<KnowledgeDocumentAcl> {
    return this.request<KnowledgeDocumentAcl>(
      `/api/v1/knowledge/documents/${encodeURIComponent(documentId)}/acl`,
    );
  }

  /**
   * 整体替换文档 ACL。
   *
   * @param documentId 文档 ID
   * @param input 新的可见范围与名单
   * @returns 更新后的文档
   */
  updateDocumentAcl(
    documentId: string,
    input: KnowledgeDocumentAclUpdate,
  ): Promise<KnowledgeDocument> {
    return this.request<KnowledgeDocument>(
      `/api/v1/knowledge/documents/${encodeURIComponent(documentId)}/acl`,
      { method: 'PUT', body: input },
    );
  }

  /**
   * 触发文档级 reindex。
   *
   * @param documentId 文档 ID
   * @param force 是否整本重做；缺省只补缺失页
   * @returns 已受理文档
   */
  reindexDocument(documentId: string, force = false): Promise<KnowledgeDocument> {
    const query = force ? '?force=true' : '';
    return this.request<KnowledgeDocument>(
      `/api/v1/knowledge/documents/${encodeURIComponent(documentId)}/reindex${query}`,
      { method: 'POST', body: {} },
    );
  }

  /**
   * 软删除文档。
   *
   * @param documentId 文档 ID
   */
  async deleteDocument(documentId: string): Promise<void> {
    await this.request<void>(`/api/v1/knowledge/documents/${encodeURIComponent(documentId)}`, {
      method: 'DELETE',
    });
  }

  /**
   * 通过服务端代理下载源文件。
   *
   * 响应 2xx 时读取为 Blob 并解析文件名；非 2xx 统一转为 `ApiError`，
   * 由界面映射为中文原因（例如 `RICCON-KB-404` 表示 ACL 已通过但无源文件）。
   *
   * @param documentId 文档 ID
   * @param signal 取消信号
   * @returns 文件字节与文件名
   */
  async downloadDocument(documentId: string, signal?: AbortSignal): Promise<KnowledgeDownload> {
    let response: Response;
    try {
      response = await this.performRequest(
        knowledgeDownloadPath(documentId),
        { method: 'GET', signal },
        false,
        'application/octet-stream',
      );
      if (!response.ok) {
        throw await toApiError(response);
      }
    } catch (error) {
      if (error instanceof ApiError) {
        throw error;
      }
      throw ApiError.network(error);
    }
    const blob = await response.blob();
    return {
      blob,
      filename: parseKnowledgeFilename(response.headers.get('content-disposition'), documentId),
    };
  }

  /**
   * 返回下载端点路径（同源）。
   *
   * @param documentId 文档 ID
   * @returns 同源路径
   */
  documentDownloadPath(documentId: string): string {
    return knowledgeDownloadPath(documentId);
  }

  /**
   * 执行 JSON 请求并读取响应体。
   *
   * @param path API 路径
   * @param options 方法、请求体与取消信号
   * @returns 解析后的响应体
   */
  private async request<T>(path: string, options: KnowledgeRequestOptions = {}): Promise<T> {
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
    options: KnowledgeRequestOptions,
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
