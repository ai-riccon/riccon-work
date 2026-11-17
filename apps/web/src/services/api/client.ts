import { ApiError, isAbortError, toApiError } from './errors';
import { parseChatEvent, parseSseStream } from './sse';
import type {
  Approval,
  ApprovalDecisionRequest,
  Artifact,
  ArtifactDetail,
  ArtifactVersion,
  ChatEvent,
  Conversation,
  ConversationDetail,
  CsrfToken,
  DownloadUrl,
  LoginRequest,
  McpTool,
  Message,
  Page,
  ResearchTask,
  ResearchTaskCreateRequest,
  ResearchTaskDetail,
  ResearchTaskRetryRequest,
  ResearchTaskStatus,
  SendMessageRequest,
  SessionInfo,
  Workspace,
  WorkspaceFile,
} from './types';

/** 与 globalThis.fetch 兼容的函数类型；测试可注入替身。 */
export type FetchLike = typeof fetch;

/** ApiClient 构造参数。 */
export interface ApiClientOptions {
  /** API 基础地址；空字符串表示同源。 */
  baseUrl?: string;
  /** fetch 实现；默认使用 globalThis.fetch；测试可注入替身。 */
  fetchImpl?: FetchLike;
  /** 任意需要登录态请求返回 401 时的回调，用于标记会话过期。 */
  onUnauthorized?: () => void;
}

/** 内部请求选项。 */
interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH' | 'DELETE';
  /** JSON 对象或 FormData；FormData 不设置 Content-Type。 */
  body?: unknown;
  /** 是否在 401 时触发会话过期回调；登录与 CSRF 获取为 false。 */
  sessionAuth?: boolean;
  signal?: AbortSignal;
  /** 幂等键等附加请求头。 */
  extraHeaders?: Record<string, string>;
}

/** 员工端 J1 需要的最小 API 能力集合。 */
export interface RicconApi {
  getSession(): Promise<SessionInfo>;
  login(input: LoginRequest): Promise<SessionInfo>;
  logout(): Promise<void>;
  listWorkspaces(): Promise<Page<Workspace>>;
  listConversations(workspaceId: string): Promise<Page<Conversation>>;
  createConversation(workspaceId: string, title?: string | null): Promise<Conversation>;
  getConversation(conversationId: string): Promise<ConversationDetail>;
  listMessages(conversationId: string, beforeSeq?: number, size?: number): Promise<Page<Message>>;
  listFiles(workspaceId: string, conversationId?: string): Promise<Page<WorkspaceFile>>;
  getFile(fileId: string): Promise<WorkspaceFile>;
  uploadFile(workspaceId: string, conversationId: string, file: File): Promise<WorkspaceFile>;
  retryFileParse(fileId: string): Promise<WorkspaceFile>;
  getFileDownloadUrl(fileId: string): Promise<DownloadUrl>;
  sendMessage(
    input: SendMessageRequest & { conversationId: string },
    signal?: AbortSignal,
  ): AsyncGenerator<ChatEvent, void, void>;
  stopGeneration(conversationId: string, runId: string): Promise<void>;
  listResearchTasks(workspaceId: string, status?: ResearchTaskStatus): Promise<Page<ResearchTask>>;
  createResearchTask(
    workspaceId: string,
    input: ResearchTaskCreateRequest,
    idempotencyKey?: string,
  ): Promise<ResearchTask>;
  getResearchTask(researchTaskId: string): Promise<ResearchTaskDetail>;
  retryResearchTask(
    researchTaskId: string,
    input?: ResearchTaskRetryRequest,
    idempotencyKey?: string,
  ): Promise<ResearchTask>;
  listGrantedMcpTools(): Promise<Page<McpTool>>;
  decideApproval(
    approvalId: string,
    input: ApprovalDecisionRequest,
    idempotencyKey?: string,
  ): Promise<Approval>;
  listArtifacts(workspaceId: string): Promise<Page<Artifact>>;
  getArtifact(artifactId: string): Promise<ArtifactDetail>;
  listArtifactVersions(artifactId: string): Promise<Page<ArtifactVersion>>;
  getArtifactVersion(artifactId: string, version: number): Promise<ArtifactVersion>;
  getArtifactDownloadUrl(artifactId: string, version: number): Promise<DownloadUrl>;
  fetchArtifactContent(artifactId: string, version: number, signal?: AbortSignal): Promise<string>;
}

/** 安全方法以外的请求需要 CSRF 令牌。 */
const UNSAFE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

/** 在线预览正文的字符上限；超过后仅允许下载，避免一次性读入过大对象。 */
const MAX_ARTIFACT_CONTENT_CHARS = 2 * 1024 * 1024;

/**
 * 构造“正文过大”的客户端错误。
 *
 * @returns 不可重试的 ApiError
 */
function artifactTooLargeError(): ApiError {
  return new ApiError('报告正文超过在线预览上限，请下载后查看。', {
    status: 422,
    code: 'RICCON-CLIENT-ARTIFACT-TOO-LARGE',
    retryable: false,
  });
}

/**
 * 判断是否为绝对 URL。
 *
 * 预签名地址通常是对象存储的绝对 URL；本地反向代理也可能返回同源相对路径。
 *
 * @param value 待判断的地址
 * @returns 是否以 http/https 开头
 */
function isAbsoluteUrl(value: string): boolean {
  return /^https?:\/\//i.test(value);
}

/** 生成 UUID；优先使用浏览器/Node 内置实现，测试环境缺失时退化为时间戳。 */
function randomId(): string {
  if (typeof globalThis.crypto?.randomUUID === 'function') {
    return globalThis.crypto.randomUUID();
  }
  return `id-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

/**
 * 统一 REST 与 SSE 客户端。
 *
 * 认证模型：服务端 Session Cookie（HttpOnly），所有写请求带 `X-CSRF-TOKEN`；
 * 客户端只在内存保存 CSRF 令牌，不写 Cookie 或 localStorage，降低 XSS 读取面。
 */
export class ApiClient implements RicconApi {
  private readonly baseUrl: string;

  private readonly fetchImpl: FetchLike;

  private readonly onUnauthorized?: () => void;

  private csrfToken: string | null = null;

  /**
   * 创建客户端。
   *
   * @param options 基础地址、fetch 实现与 401 回调
   */
  constructor(options: ApiClientOptions = {}) {
    this.baseUrl = (options.baseUrl ?? '').replace(/\/$/, '');
    this.fetchImpl = options.fetchImpl ?? globalThis.fetch.bind(globalThis);
    this.onUnauthorized = options.onUnauthorized;
  }

  /**
   * 读取当前会话。
   *
   * @returns 会话信息，同时刷新内存中的 CSRF 令牌
   */
  async getSession(): Promise<SessionInfo> {
    const session = await this.request<SessionInfo>('/api/v1/auth/session', {
      sessionAuth: false,
    });
    this.csrfToken = session.csrfToken;
    return session;
  }

  /**
   * 内置账号登录。
   *
   * 401 表示凭据错误，不触发“会话过期”回调，避免登录页被重定向。
   *
   * @param input 租户代码、账号与密码
   * @returns 登录后的会话
   */
  async login(input: LoginRequest): Promise<SessionInfo> {
    const session = await this.request<SessionInfo>('/api/v1/auth/login', {
      method: 'POST',
      body: input,
      sessionAuth: false,
    });
    this.csrfToken = session.csrfToken;
    return session;
  }

  /**
   * 登出并使服务端会话失效。
   */
  async logout(): Promise<void> {
    try {
      await this.request<void>('/api/v1/auth/logout', { method: 'POST' });
    } finally {
      // 无论服务端是否成功，客户端都不再持有 CSRF 令牌；避免退出后继续使用旧值。
      this.csrfToken = null;
    }
  }

  /**
   * 列出当前用户可访问的工作空间。
   *
   * @returns 工作空间分页，取前 100 个
   */
  listWorkspaces(): Promise<Page<Workspace>> {
    return this.request<Page<Workspace>>('/api/v1/workspaces?page=1&size=100');
  }

  /**
   * 列出工作空间会话。
   *
   * @param workspaceId 工作空间 ID
   * @returns 会话分页，取前 100 个
   */
  listConversations(workspaceId: string): Promise<Page<Conversation>> {
    return this.request<Page<Conversation>>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/conversations?page=1&size=100`,
    );
  }

  /**
   * 新建会话。
   *
   * @param workspaceId 工作空间 ID
   * @param title 可选标题；不传由服务端决定
   * @returns 新会话
   */
  createConversation(workspaceId: string, title?: string | null): Promise<Conversation> {
    const body: { title?: string | null } = {};
    if (title !== undefined) {
      body.title = title;
    }
    return this.request<Conversation>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/conversations`,
      { method: 'POST', body },
    );
  }

  /**
   * 读取会话详情（含最近消息）。
   *
   * @param conversationId 会话 ID
   * @returns 会话详情
   */
  getConversation(conversationId: string): Promise<ConversationDetail> {
    return this.request<ConversationDetail>(
      `/api/v1/conversations/${encodeURIComponent(conversationId)}`,
    );
  }

  /**
   * 列出会话消息，按 seq 升序。
   *
   * @param conversationId 会话 ID
   * @param beforeSeq 向前翻页游标
   * @param size 每页数量
   * @returns 消息分页
   */
  listMessages(conversationId: string, beforeSeq?: number, size = 100): Promise<Page<Message>> {
    const params = new URLSearchParams({ size: String(size) });
    if (beforeSeq) {
      params.set('beforeSeq', String(beforeSeq));
    }
    return this.request<Page<Message>>(
      `/api/v1/conversations/${encodeURIComponent(conversationId)}/messages?${params.toString()}`,
    );
  }

  /**
   * 列出工作空间文件。
   *
   * @param workspaceId 工作空间 ID
   * @param conversationId 可选会话过滤
   * @returns 文件分页
   */
  listFiles(workspaceId: string, conversationId?: string): Promise<Page<WorkspaceFile>> {
    const params = new URLSearchParams({ page: '1', size: '100' });
    if (conversationId) {
      params.set('conversationId', conversationId);
    }
    return this.request<Page<WorkspaceFile>>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/files?${params.toString()}`,
    );
  }

  /**
   * 读取单个文件元数据与解析状态。
   *
   * @param fileId 文件 ID
   * @returns 最新文件状态
   */
  getFile(fileId: string): Promise<WorkspaceFile> {
    return this.request<WorkspaceFile>(`/api/v1/files/${encodeURIComponent(fileId)}`);
  }

  /**
   * 上传文件。
   *
   * 使用 multipart/form-data；不手工设置 Content-Type，交给浏览器生成 boundary。
   *
   * @param workspaceId 工作空间 ID
   * @param conversationId 会话 ID
   * @param file 浏览器 File
   * @returns 已受理解析的文件
   */
  uploadFile(workspaceId: string, conversationId: string, file: File): Promise<WorkspaceFile> {
    const form = new FormData();
    form.append('file', file);
    form.append('conversationId', conversationId);
    return this.request<WorkspaceFile>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/files`,
      { method: 'POST', body: form },
    );
  }

  /**
   * 重试失败的文件解析。
   *
   * @param fileId 文件 ID
   * @returns 已受理的新解析尝试
   */
  retryFileParse(fileId: string): Promise<WorkspaceFile> {
    return this.request<WorkspaceFile>(
      `/api/v1/files/${encodeURIComponent(fileId)}/parse-retries`,
      {
        method: 'POST',
        body: {},
        extraHeaders: { 'Idempotency-Key': randomId() },
      },
    );
  }

  /**
   * 获取文件预签名下载地址。
   *
   * @param fileId 文件 ID
   * @returns 短时有效的下载地址
   */
  getFileDownloadUrl(fileId: string): Promise<DownloadUrl> {
    return this.request<DownloadUrl>(`/api/v1/files/${encodeURIComponent(fileId)}/download-url`);
  }

  /**
   * 发送消息并以异步迭代器返回 SSE 事件。
   *
   * 不在客户端重连或重放：回答是否可重试由 failed 事件的 `retryable` 决定，
   * 避免在用户不知情的情况下产生重复模型调用。
   *
   * @param input 会话、内容、客户端消息 ID 与可选文件
   * @param signal AbortSignal，用于停止生成
   * @returns 对话事件流
   */
  async *sendMessage(
    input: SendMessageRequest & { conversationId: string },
    signal?: AbortSignal,
  ): AsyncGenerator<ChatEvent, void, void> {
    const body: SendMessageRequest = {
      content: input.content,
      clientMessageId: input.clientMessageId,
      ...(input.fileIds && input.fileIds.length > 0 ? { fileIds: input.fileIds } : {}),
    };

    const response = await this.streamFetch(
      `/api/v1/conversations/${encodeURIComponent(input.conversationId)}/messages`,
      {
        method: 'POST',
        body,
        signal,
        extraHeaders: { 'Idempotency-Key': randomId() },
      },
    );

    if (!response.body) {
      throw new ApiError('回答流为空，请重试。', {
        status: 0,
        code: 'RICCON-CLIENT-EMPTY-STREAM',
        retryable: true,
        kind: 'network',
      });
    }

    for await (const frame of parseSseStream(response.body)) {
      const event = parseChatEvent(frame);
      if (event) {
        yield event;
      }
    }
  }

  /**
   * 停止当前回答。
   *
   * @param conversationId 会话 ID
   * @param runId 运行 ID，来自 started 事件
   */
  async stopGeneration(conversationId: string, runId: string): Promise<void> {
    await this.request<void>(`/api/v1/conversations/${encodeURIComponent(conversationId)}/stop`, {
      method: 'POST',
      body: { runId },
      extraHeaders: { 'Idempotency-Key': randomId() },
    });
  }

  /**
   * 列出工作空间内的报告任务。
   *
   * @param workspaceId 工作空间 ID
   * @param status 可选状态过滤
   * @returns 任务分页，取前 100 个
   */
  listResearchTasks(workspaceId: string, status?: ResearchTaskStatus): Promise<Page<ResearchTask>> {
    const params = new URLSearchParams({ page: '1', size: '100' });
    if (status) {
      params.set('status', status);
    }
    return this.request<Page<ResearchTask>>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/research-tasks?${params.toString()}`,
    );
  }

  /**
   * 创建市场调研报告任务。
   *
   * 契约要求必须带 Idempotency-Key；同一用户重复提交同一键返回既有任务。
   * 调用方可以传入稳定的键来安全重试，未提供时生成一次性 UUID。
   *
   * @param workspaceId 工作空间 ID
   * @param input 主题与授权的 MCP 工具
   * @param idempotencyKey 幂等键
   * @returns 已创建或已存在的任务
   */
  createResearchTask(
    workspaceId: string,
    input: ResearchTaskCreateRequest,
    idempotencyKey = randomId(),
  ): Promise<ResearchTask> {
    return this.request<ResearchTask>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/research-tasks`,
      {
        method: 'POST',
        body: input,
        extraHeaders: { 'Idempotency-Key': idempotencyKey },
      },
    );
  }

  /**
   * 读取报告任务详情与时间线。
   *
   * @param researchTaskId 报告任务 ID
   * @returns 任务详情
   */
  getResearchTask(researchTaskId: string): Promise<ResearchTaskDetail> {
    return this.request<ResearchTaskDetail>(
      `/api/v1/research-tasks/${encodeURIComponent(researchTaskId)}`,
    );
  }

  /**
   * 重试失败的阶段。
   *
   * 契约只允许 TOOL 或 GENERATION；沿用同一任务与产物，避免重复产物。
   *
   * @param researchTaskId 报告任务 ID
   * @param input 起始阶段；为空表示服务端按失败阶段重试
   * @param idempotencyKey 幂等键
   * @returns 已受理的任务摘要
   */
  retryResearchTask(
    researchTaskId: string,
    input: ResearchTaskRetryRequest = {},
    idempotencyKey = randomId(),
  ): Promise<ResearchTask> {
    const body: ResearchTaskRetryRequest = {};
    if (input.fromStage) {
      body.fromStage = input.fromStage;
    }
    return this.request<ResearchTask>(
      `/api/v1/research-tasks/${encodeURIComponent(researchTaskId)}/retry`,
      {
        method: 'POST',
        body,
        extraHeaders: { 'Idempotency-Key': idempotencyKey },
      },
    );
  }

  /**
   * 列出本租户已授权的 MCP 工具。
   *
   * @returns 工具分页，取前 100 个
   */
  listGrantedMcpTools(): Promise<Page<McpTool>> {
    return this.request<Page<McpTool>>('/api/v1/mcp-tools?page=1&size=100');
  }

  /**
   * 批准或拒绝外部工具调用。
   *
   * 重复提交同一决定由服务端幂等返回；调用方应复用同一个幂等键，
   * 避免响应丢失后的重试被当成新的决定。
   *
   * @param approvalId 审批 ID
   * @param input 决定与可选备注
   * @param idempotencyKey 幂等键
   * @returns 审批结果
   */
  decideApproval(
    approvalId: string,
    input: ApprovalDecisionRequest,
    idempotencyKey = randomId(),
  ): Promise<Approval> {
    return this.request<Approval>(`/api/v1/approvals/${encodeURIComponent(approvalId)}/decision`, {
      method: 'POST',
      body: input,
      extraHeaders: { 'Idempotency-Key': idempotencyKey },
    });
  }

  /**
   * 列出工作空间内的报告产物。
   *
   * @param workspaceId 工作空间 ID
   * @returns 产物分页，取前 100 个
   */
  listArtifacts(workspaceId: string): Promise<Page<Artifact>> {
    return this.request<Page<Artifact>>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/artifacts?page=1&size=100`,
    );
  }

  /**
   * 读取产物及其当前版本。
   *
   * @param artifactId 产物 ID
   * @returns 产物详情
   */
  getArtifact(artifactId: string): Promise<ArtifactDetail> {
    return this.request<ArtifactDetail>(`/api/v1/artifacts/${encodeURIComponent(artifactId)}`);
  }

  /**
   * 列出产物的历史版本。
   *
   * @param artifactId 产物 ID
   * @returns 版本分页，取前 100 个
   */
  listArtifactVersions(artifactId: string): Promise<Page<ArtifactVersion>> {
    return this.request<Page<ArtifactVersion>>(
      `/api/v1/artifacts/${encodeURIComponent(artifactId)}/versions?page=1&size=100`,
    );
  }

  /**
   * 读取指定产物版本元数据。
   *
   * @param artifactId 产物 ID
   * @param version 版本号
   * @returns 版本详情
   */
  getArtifactVersion(artifactId: string, version: number): Promise<ArtifactVersion> {
    return this.request<ArtifactVersion>(
      `/api/v1/artifacts/${encodeURIComponent(artifactId)}/versions/${version}`,
    );
  }

  /**
   * 获取产物正文的预签名下载地址。
   *
   * 契约要求 TTL 不超过 300 秒，调用方不应缓存或转存该 URL。
   *
   * @param artifactId 产物 ID
   * @param version 版本号
   * @returns 短时下载地址
   */
  getArtifactDownloadUrl(artifactId: string, version: number): Promise<DownloadUrl> {
    return this.request<DownloadUrl>(
      `/api/v1/artifacts/${encodeURIComponent(artifactId)}/versions/${version}/download-url`,
    );
  }

  /**
   * 读取产物正文文本（Markdown）。
   *
   * 正文存放在对象存储，API 只返回预签名 URL；这里先取 URL 再直接读取对象，
   * 不复用 API 客户端的 `Accept: application/json` 与 Cookie：
   * - 预签名 URL 可能指向不同源，携带 Cookie 会扩大凭据暴露面；
   * - 对象存储返回 text/markdown，按 JSON 解析会失败。
   *
   * 预览设置 2 MiB 上限，超过时提示用户下载后查看，避免把大对象读入内存。
   *
   * @param artifactId 产物 ID
   * @param version 版本号
   * @param signal 取消信号
   * @returns Markdown 正文
   */
  async fetchArtifactContent(
    artifactId: string,
    version: number,
    signal?: AbortSignal,
  ): Promise<string> {
    const download = await this.getArtifactDownloadUrl(artifactId, version);
    const url = isAbsoluteUrl(download.url)
      ? download.url
      : `${this.baseUrl}${download.url.startsWith('/') ? '' : '/'}${download.url}`;

    try {
      const fetchImpl = this.resolveFetch();
      const response = await fetchImpl(url, {
        method: 'GET',
        headers: { Accept: 'text/markdown, text/plain;q=0.9, */*;q=0.1' },
        // 预签名 URL 自带授权，不向对象存储发送同源 Cookie。
        credentials: 'omit',
        cache: 'no-store',
        signal,
      });
      if (!response.ok) {
        throw await toApiError(response);
      }
      const declaredBytes = Number(response.headers.get('content-length') ?? '0');
      if (declaredBytes > MAX_ARTIFACT_CONTENT_CHARS) {
        throw artifactTooLargeError();
      }
      const text = await response.text();
      if (text.length > MAX_ARTIFACT_CONTENT_CHARS) {
        throw artifactTooLargeError();
      }
      return text;
    } catch (error) {
      if (isAbortError(error) || error instanceof ApiError) {
        throw error;
      }
      throw ApiError.network(error);
    }
  }

  /**
   * 执行普通 JSON 请求。
   *
   * @param path 以 `/` 开头的 API 路径
   * @param options 方法、请求体、鉴权与取消信号
   * @returns 解析后的响应体
   */
  private async request<T>(path: string, options: RequestOptions = {}): Promise<T> {
    const method = options.method ?? 'GET';
    const unsafe = UNSAFE_METHODS.has(method);

    try {
      const response = await this.performRequest(path, method, options, unsafe);
      return await this.readResponse<T>(response);
    } catch (error) {
      if (isAbortError(error)) {
        throw error;
      }
      if (error instanceof ApiError) {
        throw error;
      }
      throw ApiError.network(error);
    }
  }

  /**
   * 实际发起请求，并在 CSRF 过期时刷新令牌重试一次。
   *
   * @param path API 路径
   * @param method HTTP 方法
   * @param options 请求选项
   * @param unsafe 是否需要 CSRF
   * @returns fetch Response
   */
  private async performRequest(
    path: string,
    method: string,
    options: RequestOptions,
    unsafe: boolean,
  ): Promise<Response> {
    const doFetch = async (retried: boolean): Promise<Response> => {
      const headers: Record<string, string> = { Accept: 'application/json' };
      if (unsafe) {
        headers['X-CSRF-TOKEN'] = await this.ensureCsrfToken();
      }
      let body: BodyInit | undefined;
      if (options.body instanceof FormData) {
        body = options.body;
      } else if (options.body !== undefined) {
        headers['Content-Type'] = 'application/json';
        body = JSON.stringify(options.body);
      }
      Object.assign(headers, options.extraHeaders);

      const fetchImpl = this.resolveFetch();
      const response = await fetchImpl(`${this.baseUrl}${path}`, {
        method,
        headers,
        body,
        credentials: 'same-origin',
        signal: options.signal,
      });

      if (response.status === 401 && options.sessionAuth !== false) {
        this.onUnauthorized?.();
      }

      // CSRF 令牌可能在会话轮换后失效；仅对写请求刷新一次，避免无限重试。
      if (unsafe && !retried && response.status === 403 && (await hasCsrfError(response))) {
        this.csrfToken = null;
        return doFetch(true);
      }

      return response;
    };

    const response = await doFetch(false);
    if (!response.ok) {
      throw await toApiError(response);
    }
    return response;
  }

  /**
   * 发起 SSE 请求。
   *
   * 与普通请求的差异：不在这里读取 JSON 错误体，而是保留 Response 交给调用方；
   * 只有 CSRF 403 会先解析并刷新令牌后重试一次。
   *
   * @param path API 路径
   * @param options 方法、请求体、取消信号与额外请求头
   * @returns 成功的 SSE 响应
   */
  private async streamFetch(
    path: string,
    options: {
      method: 'POST';
      body: SendMessageRequest;
      signal?: AbortSignal;
      extraHeaders?: Record<string, string>;
    },
  ): Promise<Response> {
    const doFetch = async (retried: boolean): Promise<Response> => {
      const fetchImpl = this.resolveFetch();
      const response = await fetchImpl(`${this.baseUrl}${path}`, {
        method: options.method,
        headers: {
          Accept: 'text/event-stream',
          'Content-Type': 'application/json',
          'X-CSRF-TOKEN': await this.ensureCsrfToken(),
          ...options.extraHeaders,
        },
        body: JSON.stringify(options.body),
        credentials: 'same-origin',
        signal: options.signal,
      });

      if (response.status === 401) {
        this.onUnauthorized?.();
        throw await toApiError(response);
      }

      if (!retried && response.status === 403 && (await hasCsrfError(response))) {
        this.csrfToken = null;
        return doFetch(true);
      }

      if (!response.ok) {
        throw await toApiError(response);
      }
      return response;
    };

    try {
      return await doFetch(false);
    } catch (error) {
      if (isAbortError(error) || error instanceof ApiError) {
        throw error;
      }
      throw ApiError.network(error);
    }
  }

  /**
   * 读取 JSON 响应；204 或空响应返回 undefined。
   *
   * @param response fetch Response
   * @returns 响应体
   */
  private async readResponse<T>(response: Response): Promise<T> {
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
   * 解析 fetch 实现。
   *
   * 客户端只持有构造时注入或全局的 fetch，不再有延迟加载分支；
   * 因此生产包内不存在任何 mock 适配层的动态 import 入口。
   *
   * @returns fetch 函数
   */
  private resolveFetch(): FetchLike {
    return this.fetchImpl;
  }

  /**
   * 获取 CSRF 令牌；未登录时服务端会建立仅承载令牌的匿名会话。
   *
   * @returns SSR 令牌
   */
  private async ensureCsrfToken(): Promise<string> {
    if (this.csrfToken) {
      return this.csrfToken;
    }
    const fetchImpl = this.resolveFetch();
    const response = await fetchImpl(`${this.baseUrl}/api/v1/auth/csrf`, {
      method: 'GET',
      headers: { Accept: 'application/json' },
      credentials: 'same-origin',
    });
    if (!response.ok) {
      throw await toApiError(response);
    }
    const token = (await response.json()) as CsrfToken;
    this.csrfToken = token.token;
    return token.token;
  }
}

/**
 * 判断 403 响应是否由 CSRF 令牌失效导致。
 *
 * 使用 clone 读取，保证后续仍可把原响应交给 toApiError。
 *
 * @param response 403 响应
 * @returns 是否为 CSRF 错误
 */
async function hasCsrfError(response: Response): Promise<boolean> {
  try {
    const problem = (await response.clone().json()) as { code?: string };
    return problem.code === 'RICCON-AUTH-403';
  } catch {
    return false;
  }
}
