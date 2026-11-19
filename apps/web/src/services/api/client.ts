import {
  FEDERATED_LOGOUT_HEADER,
  rememberFederatedLogoutUrl,
} from '@/services/auth/federated-logout';
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
  Department,
  DepartmentCreateRequest,
  DepartmentMember,
  DepartmentStatus,
  DepartmentUpdateRequest,
  DepartmentWorkspaceCreateRequest,
  DownloadUrl,
  LoginRequest,
  ManagedWorkspace,
  McpTool,
  Message,
  Page,
  ResearchTask,
  ResearchTaskCreateRequest,
  ResearchTaskDetail,
  ResearchTaskRetryRequest,
  ResearchTaskStatus,
  RunDetail,
  SendMessageRequest,
  SessionInfo,
  TenantSummary,
  UserSummary,
  Workspace,
  WorkspaceCreateRequest,
  WorkspaceFile,
  WorkspaceMember,
  WorkspaceUpdateRequest,
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
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  /** JSON 对象或 FormData；FormData 不设置 Content-Type。 */
  body?: unknown;
  /** 是否在 401 时触发会话过期回调；登录与 CSRF 获取为 false。 */
  sessionAuth?: boolean;
  signal?: AbortSignal;
  /** 幂等键等附加请求头。 */
  extraHeaders?: Record<string, string>;
  /** 可选缓存策略；产物正文预览必须绕过浏览器缓存。 */
  cache?: RequestCache;
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
  getRun(runId: string): Promise<RunDetail>;
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
  getArtifactContentUrl(artifactId: string, version: number): string;
  getArtifactDownloadUrl(artifactId: string, version: number): Promise<DownloadUrl>;
  fetchArtifactContent(artifactId: string, version: number, signal?: AbortSignal): Promise<string>;
}

/**
 * M03 空间共享管理能力。
 *
 * 单独声明接口而不并入 `RicconApi`：J1 页面只依赖最小读取能力，
 * 管理界面可以独立注入 fake；ApiClient 同时实现两者。
 */
export interface WorkspaceManagementApi {
  listTeamWorkspaces(): Promise<Page<ManagedWorkspace>>;
  listDepartments(): Promise<Page<Department>>;
  listDepartmentMembers(departmentId: string): Promise<Page<DepartmentMember>>;
  listDepartmentWorkspaces(departmentId: string): Promise<Page<ManagedWorkspace>>;
  createDepartmentWorkspace(
    departmentId: string,
    input: DepartmentWorkspaceCreateRequest,
  ): Promise<ManagedWorkspace>;
  createWorkspace(input: WorkspaceCreateRequest): Promise<ManagedWorkspace>;
  updateWorkspace(workspaceId: string, input: WorkspaceUpdateRequest): Promise<ManagedWorkspace>;
  listWorkspaceMembers(workspaceId: string): Promise<Page<WorkspaceMember>>;
  addWorkspaceMember(workspaceId: string, userId: string): Promise<WorkspaceMember>;
  removeWorkspaceMember(workspaceId: string, userId: string): Promise<void>;
}

/**
 * 平台管理员部门元数据管理能力。
 *
 * 与租户内空间管理分离：这些端点要求 `platform.department.manage`，
 * 只返回组织字段与账号展示名，不读取目标租户业务内容。
 */
export interface PlatformDepartmentApi {
  listTenants(): Promise<Page<TenantSummary>>;
  listTenantDepartments(tenantId: string, status?: DepartmentStatus): Promise<Page<Department>>;
  createTenantDepartment(tenantId: string, input: DepartmentCreateRequest): Promise<Department>;
  renameTenantDepartment(
    tenantId: string,
    departmentId: string,
    input: DepartmentUpdateRequest,
  ): Promise<Department>;
  disableTenantDepartment(tenantId: string, departmentId: string): Promise<Department>;
  enableTenantDepartment(tenantId: string, departmentId: string): Promise<Department>;
  listTenantDepartmentMembers(
    tenantId: string,
    departmentId: string,
  ): Promise<Page<DepartmentMember>>;
  addTenantDepartmentMember(
    tenantId: string,
    departmentId: string,
    userId: string,
  ): Promise<DepartmentMember>;
  removeTenantDepartmentMember(
    tenantId: string,
    departmentId: string,
    userId: string,
  ): Promise<void>;
  transferTenantDepartmentManager(
    tenantId: string,
    departmentId: string,
    userId: string,
  ): Promise<Department>;
  listTenantUsers(tenantId: string): Promise<Page<UserSummary>>;
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
export class ApiClient implements RicconApi, WorkspaceManagementApi, PlatformDepartmentApi {
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
   *
   * 本地登出是权威结果。若服务端返回 `X-Riccon-Federated-Logout-Url`
   * （仅 OIDC 账号且租户配置了 end-session），只把安全地址暂存到 sessionStorage；
   * 顶层导航由登录页在渲染“已退出本地会话”后执行，IdP 不可达时不改写本地退出结果。
   */
  async logout(): Promise<void> {
    try {
      const response = await this.performRequest('/api/v1/auth/logout', 'POST', {}, true);
      rememberFederatedLogoutUrl(response.headers.get(FEDERATED_LOGOUT_HEADER));
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
   * 列出当前用户可访问的团队空间（含部门空间与历史 TENANT 空间）。
   *
   * 管理界面只展示 `kind=TEAM`，个人空间不参与成员或可见性管理。
   *
   * @returns 团队空间分页，取前 100 个
   */
  listTeamWorkspaces(): Promise<Page<ManagedWorkspace>> {
    return this.request<Page<ManagedWorkspace>>('/api/v1/workspaces?page=1&size=100&kind=TEAM');
  }

  /**
   * 读取当前用户可见的部门。
   *
   * 普通员工只得到所属部门；租户管理员得到本租户全部部门（只读）。
   *
   * @returns 部门分页
   */
  listDepartments(): Promise<Page<Department>> {
    return this.request<Page<Department>>('/api/v1/departments');
  }

  /**
   * 列出部门成员。
   *
   * @param departmentId 部门 ID
   * @returns 成员分页，取前 100 个
   */
  listDepartmentMembers(departmentId: string): Promise<Page<DepartmentMember>> {
    return this.request<Page<DepartmentMember>>(
      `/api/v1/departments/${encodeURIComponent(departmentId)}/members?page=1&size=100`,
    );
  }

  /**
   * 列出部门空间（管理用元数据）。
   *
   * @param departmentId 部门 ID
   * @returns 空间分页，取前 100 个
   */
  listDepartmentWorkspaces(departmentId: string): Promise<Page<ManagedWorkspace>> {
    return this.request<Page<ManagedWorkspace>>(
      `/api/v1/departments/${encodeURIComponent(departmentId)}/workspaces?page=1&size=100`,
    );
  }

  /**
   * 在部门下创建团队空间。
   *
   * 部门来自路径，避免负责人伪造 `departmentId`；可见性只允许 `DEPT` / `CUSTOM`。
   *
   * @param departmentId 部门 ID
   * @param input 名称、描述与可见性
   * @returns 新建空间
   */
  createDepartmentWorkspace(
    departmentId: string,
    input: DepartmentWorkspaceCreateRequest,
  ): Promise<ManagedWorkspace> {
    return this.request<ManagedWorkspace>(
      `/api/v1/departments/${encodeURIComponent(departmentId)}/workspaces`,
      { method: 'POST', body: input },
    );
  }

  /**
   * 以租户管理员身份在任意启用部门创建团队空间。
   *
   * @param input 名称、部门与可见性
   * @returns 新建空间
   */
  createWorkspace(input: WorkspaceCreateRequest): Promise<ManagedWorkspace> {
    return this.request<ManagedWorkspace>('/api/v1/workspaces', {
      method: 'POST',
      body: input,
    });
  }

  /**
   * 修改团队空间名称、描述或可见性。
   *
   * `TENANT` 历史空间不可转换，服务端会以 400 拒绝；界面不展示该入口。
   *
   * @param workspaceId 空间 ID
   * @param input 待更新字段
   * @returns 更新后的空间
   */
  updateWorkspace(workspaceId: string, input: WorkspaceUpdateRequest): Promise<ManagedWorkspace> {
    return this.request<ManagedWorkspace>(`/api/v1/workspaces/${encodeURIComponent(workspaceId)}`, {
      method: 'PATCH',
      body: input,
    });
  }

  /**
   * 列出 CUSTOM 空间的显式成员。
   *
   * @param workspaceId 空间 ID
   * @returns 显式成员分页
   */
  listWorkspaceMembers(workspaceId: string): Promise<Page<WorkspaceMember>> {
    return this.request<Page<WorkspaceMember>>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/members?page=1&size=100`,
    );
  }

  /**
   * 把本部门成员加入 CUSTOM 空间。
   *
   * @param workspaceId 空间 ID
   * @param userId 目标账号 ID
   * @returns 新增的显式成员
   */
  addWorkspaceMember(workspaceId: string, userId: string): Promise<WorkspaceMember> {
    return this.request<WorkspaceMember>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/members`,
      { method: 'POST', body: { userId } },
    );
  }

  /**
   * 移出 CUSTOM 空间显式成员；成员不存在时服务端返回 204。
   *
   * @param workspaceId 空间 ID
   * @param userId 目标账号 ID
   */
  removeWorkspaceMember(workspaceId: string, userId: string): Promise<void> {
    return this.request<void>(
      `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/members/${encodeURIComponent(userId)}`,
      { method: 'DELETE' },
    );
  }

  /**
   * 列出平台租户。
   *
   * @returns 租户分页，取前 100 个
   */
  listTenants(): Promise<Page<TenantSummary>> {
    return this.request<Page<TenantSummary>>('/api/v1/admin/tenants?page=1&size=100');
  }

  /**
   * 平台管理员列出目标租户部门。
   *
   * @param tenantId 目标租户 ID
   * @param status 可选状态过滤
   * @returns 部门分页
   */
  listTenantDepartments(tenantId: string, status?: DepartmentStatus): Promise<Page<Department>> {
    const params = new URLSearchParams({ page: '1', size: '100' });
    if (status) {
      params.set('status', status);
    }
    return this.request<Page<Department>>(
      `/api/v1/admin/tenants/${encodeURIComponent(tenantId)}/departments?${params.toString()}`,
    );
  }

  /**
   * 平台管理员在目标租户创建部门。
   *
   * @param tenantId 目标租户 ID
   * @param input 部门名称
   * @returns 新建部门
   */
  createTenantDepartment(tenantId: string, input: DepartmentCreateRequest): Promise<Department> {
    return this.request<Department>(
      `/api/v1/admin/tenants/${encodeURIComponent(tenantId)}/departments`,
      { method: 'POST', body: input },
    );
  }

  /**
   * 平台管理员重命名目标租户部门。
   *
   * @param tenantId 目标租户 ID
   * @param departmentId 部门 ID
   * @param input 新名称
   * @returns 更新后的部门
   */
  renameTenantDepartment(
    tenantId: string,
    departmentId: string,
    input: DepartmentUpdateRequest,
  ): Promise<Department> {
    return this.request<Department>(
      `/api/v1/admin/tenants/${encodeURIComponent(tenantId)}/departments/${encodeURIComponent(
        departmentId,
      )}`,
      { method: 'PATCH', body: input },
    );
  }

  /**
   * 平台管理员停用目标租户部门；服务端幂等。
   *
   * @param tenantId 目标租户 ID
   * @param departmentId 部门 ID
   * @returns 更新后的部门
   */
  disableTenantDepartment(tenantId: string, departmentId: string): Promise<Department> {
    return this.request<Department>(
      `/api/v1/admin/tenants/${encodeURIComponent(tenantId)}/departments/${encodeURIComponent(
        departmentId,
      )}/disable`,
      { method: 'POST', body: {} },
    );
  }

  /**
   * 平台管理员启用目标租户部门；服务端幂等。
   *
   * @param tenantId 目标租户 ID
   * @param departmentId 部门 ID
   * @returns 更新后的部门
   */
  enableTenantDepartment(tenantId: string, departmentId: string): Promise<Department> {
    return this.request<Department>(
      `/api/v1/admin/tenants/${encodeURIComponent(tenantId)}/departments/${encodeURIComponent(
        departmentId,
      )}/enable`,
      { method: 'POST', body: {} },
    );
  }

  /**
   * 平台管理员列出目标租户部门成员。
   *
   * @param tenantId 目标租户 ID
   * @param departmentId 部门 ID
   * @returns 成员分页，取前 100 个
   */
  listTenantDepartmentMembers(
    tenantId: string,
    departmentId: string,
  ): Promise<Page<DepartmentMember>> {
    return this.request<Page<DepartmentMember>>(
      `/api/v1/admin/tenants/${encodeURIComponent(tenantId)}/departments/${encodeURIComponent(
        departmentId,
      )}/members?page=1&size=100`,
    );
  }

  /**
   * 平台管理员把目标租户账号加入部门。
   *
   * @param tenantId 目标租户 ID
   * @param departmentId 部门 ID
   * @param userId 目标账号 ID
   * @returns 加入后的成员
   */
  addTenantDepartmentMember(
    tenantId: string,
    departmentId: string,
    userId: string,
  ): Promise<DepartmentMember> {
    return this.request<DepartmentMember>(
      `/api/v1/admin/tenants/${encodeURIComponent(tenantId)}/departments/${encodeURIComponent(
        departmentId,
      )}/members`,
      { method: 'POST', body: { userId } },
    );
  }

  /**
   * 平台管理员把账号移出部门；服务端幂等。
   *
   * @param tenantId 目标租户 ID
   * @param departmentId 部门 ID
   * @param userId 目标账号 ID
   */
  removeTenantDepartmentMember(
    tenantId: string,
    departmentId: string,
    userId: string,
  ): Promise<void> {
    return this.request<void>(
      `/api/v1/admin/tenants/${encodeURIComponent(tenantId)}/departments/${encodeURIComponent(
        departmentId,
      )}/members/${encodeURIComponent(userId)}`,
      { method: 'DELETE' },
    );
  }

  /**
   * 平台管理员任命/移交部门负责人。
   *
   * @param tenantId 目标租户 ID
   * @param departmentId 部门 ID
   * @param userId 新负责人账号 ID
   * @returns 更新后的部门
   */
  transferTenantDepartmentManager(
    tenantId: string,
    departmentId: string,
    userId: string,
  ): Promise<Department> {
    return this.request<Department>(
      `/api/v1/admin/tenants/${encodeURIComponent(tenantId)}/departments/${encodeURIComponent(
        departmentId,
      )}/manager`,
      { method: 'PUT', body: { userId } },
    );
  }

  /**
   * 平台管理员列出目标租户账号元数据。
   *
   * 只用于部门成员选择，不含业务内容；页面按 `status` 过滤停用账号。
   *
   * @param tenantId 目标租户 ID
   * @returns 账号分页，取前 100 个
   */
  listTenantUsers(tenantId: string): Promise<Page<UserSummary>> {
    return this.request<Page<UserSummary>>(
      `/api/v1/admin/tenants/${encodeURIComponent(tenantId)}/users?page=1&size=100`,
    );
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
   * 读取运行详情与模型调用记录。
   *
   * 只读接口；跨租户或不可见对象由服务端统一返回 404，客户端不做对象存在性推断。
   *
   * @param runId 运行 ID
   * @returns 运行详情
   */
  getRun(runId: string): Promise<RunDetail> {
    return this.request<RunDetail>(`/api/v1/runs/${encodeURIComponent(runId)}`);
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
   * 生成产物正文的同源内容地址。
   *
   * 下载按钮直接使用该地址，由 API 以流式方式代理对象存储内容；
   * 同源地址会携带会话 Cookie，不依赖对象存储 CORS，也不把正文读入内存。
   *
   * @param artifactId 产物 ID
   * @param version 版本号
   * @returns 同源内容 URL
   */
  getArtifactContentUrl(artifactId: string, version: number): string {
    return `${this.baseUrl}/api/v1/artifacts/${encodeURIComponent(artifactId)}/versions/${version}/content`;
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
   * 正文通过 API 同源内容端点读取，不直接访问对象存储：
   * - 同源请求复用会话 Cookie，不向跨源对象存储暴露凭据，也不依赖其 CORS 配置；
   * - API 负责租户与版本授权，正文不会被对象存储地址绕过。
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
    try {
      const response = await this.performRequest(
        `/api/v1/artifacts/${encodeURIComponent(artifactId)}/versions/${version}/content`,
        'GET',
        {
          signal,
          cache: 'no-store',
          extraHeaders: { Accept: 'text/markdown, text/plain;q=0.9, */*;q=0.1' },
        },
        false,
      );
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
        cache: options.cache,
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
