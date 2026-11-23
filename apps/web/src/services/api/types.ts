/**
 * M02 J1 前端使用的 REST/SSE 契约类型。
 *
 * 类型来源：`contracts/openapi.yaml` 与 `contracts/events/sse/chat-event.v1.schema.json`。
 * 这里只声明前端实际消费的字段，避免把整份契约复制成易漂移的第二事实源；
 * 新增字段时先更新契约，再同步本文件。
 */

/** 分页元数据，对应 OpenAPI `PageMeta`。 */
export interface PageMeta {
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

/** 强制分页列表，对应 OpenAPI 各 `*Page` schema。 */
export type Page<T> = PageMeta & { items: T[] };

/** 用户状态。 */
export type UserStatus = 'ACTIVE' | 'DISABLED';

/** 租户状态。 */
export type TenantStatus = 'ACTIVE' | 'SUSPENDED';

/**
 * 账号认证来源，对应 OpenAPI `AuthSource`。
 *
 * `LOCAL` 使用本地密码；`OIDC` 使用 issuer + subject 绑定且没有本地密码凭据。
 */
export type AuthSource = 'LOCAL' | 'OIDC';

/** 用户摘要，对应 OpenAPI `UserSummary`。 */
export interface UserSummary {
  id: string;
  username: string;
  displayName: string;
  email: string | null;
  status: UserStatus;
  roles: string[];
  /** 主部门 ID；未分配时为 null。服务端可能暂未下发，界面以 /departments 为准。 */
  departmentId?: string | null;
  /** 主部门名；停用部门仍显示名称。 */
  departmentName?: string | null;
  /**
   * 认证来源；管理端与 `/auth/session` 会下发。
   * 历史响应可能省略该字段，界面按“未标注”展示，不猜测为任何来源。
   */
  authSource?: AuthSource;
  /**
   * 来源 IdP 的脱敏展示名，对应 OpenAPI `UserSummary.identityProvider`。
   *
   * OIDC 为租户配置的 providerName（issuer 与配置一致时）或 issuer 的 host（非默认端口保留）；
   * LOCAL、未知来源或无法解析时服务端省略或下发 null。界面只展示该值，
   * 不在前端拼接完整 issuer、subject、token 或邮箱。
   */
  identityProvider?: string | null;
  lastLoginAt: string | null;
  createdAt: string;
}

/** 企业账号登录可用性，对应 OpenAPI `OidcProviderAvailability`。 */
export interface OidcProviderAvailability {
  /** 是否启用企业账号登录；未知租户、未配置与已停用统一为 false。 */
  enabled: boolean;
  /** 登录页展示名；仅 enabled=true 时返回。 */
  providerName?: string | null;
}

/** OIDC 身份绑定请求，对应 OpenAPI `OidcBindingRequest`。 */
export interface OidcBindingRequest {
  /** 必须等于租户当前配置的 issuer（规范化后）。 */
  issuer: string;
  /** IdP 的 subject；不存邮箱、不按邮箱匹配。 */
  subject: string;
}

/** OIDC 解绑并恢复本地密码请求，对应 OpenAPI `OidcUnbindRequest`。 */
export interface OidcUnbindRequest {
  /** 恢复本地账号时设置的新密码，12–200 字符。 */
  newPassword: string;
}

/** 租户摘要，对应 OpenAPI `TenantSummary`。 */
export interface TenantSummary {
  id: string;
  name: string;
  status: TenantStatus;
  plan?: string;
  retentionDays: number;
  createdAt: string;
}

/** 当前会话，对应 OpenAPI `SessionInfo`。 */
export interface SessionInfo {
  authenticated: true;
  user: UserSummary;
  tenant: TenantSummary;
  roles: string[];
  permissions: string[];
  csrfToken: string;
  expiresAt: string;
}

/** CSRF 令牌，对应 OpenAPI `CsrfToken`。 */
export interface CsrfToken {
  headerName: 'X-CSRF-TOKEN';
  token: string;
}

/** 登录请求，对应 OpenAPI `LoginRequest`。 */
export interface LoginRequest {
  tenantCode: string;
  username: string;
  password: string;
}

/** 工作空间类型。 */
export type WorkspaceKind = 'PERSONAL' | 'TEAM';

/** 工作空间状态。 */
export type WorkspaceStatus = 'ACTIVE' | 'ARCHIVED';

/** 工作空间，对应 OpenAPI `Workspace`。 */
export interface Workspace {
  id: string;
  kind: WorkspaceKind;
  name: string;
  description: string | null;
  ownerUserId: string;
  status: WorkspaceStatus;
  createdAt: string;
  updatedAt: string;
}

/**
 * 工作空间可见性，对应 OpenAPI `WorkspaceVisibility`。
 *
 * `TENANT` 只用于 M02 遗留空间，M03 起不允许新建或转换；界面必须把它展示为只读。
 */
export type WorkspaceVisibility = 'PRIVATE' | 'TENANT' | 'DEPT' | 'CUSTOM';

/**
 * 部门状态，对应 OpenAPI `DepartmentStatus`。
 */
export type DepartmentStatus = 'ACTIVE' | 'DISABLED';

/**
 * M03 管理界面使用的空间响应。
 *
 * 严格对应 OpenAPI `Workspace`：`visibility` 从 M03 起必填，`departmentId` /
 * `departmentName` 在个人空间与历史 `TENANT` 空间为 null。
 * 这里单独声明而不改动 M02 的 `Workspace`，避免把仅供 J1 消费的最小类型
 * 强行扩展到所有调用点。
 */
export interface ManagedWorkspace extends Workspace {
  visibility: WorkspaceVisibility;
  /** 所属部门；个人空间与历史 TENANT 空间可能省略或为 null。 */
  departmentId?: string | null;
  /** 所属部门名；仅用于展示，不参与授权判断。 */
  departmentName?: string | null;
}

/**
 * 部门摘要，对应 OpenAPI `Department`。
 *
 * 只含组织字段与负责人展示名，不含账号邮箱、角色或业务内容。
 */
export interface Department {
  id: string;
  name: string;
  status: DepartmentStatus;
  managerUserId?: string | null;
  managerDisplayName?: string | null;
  memberCount: number;
  createdAt: string;
  updatedAt: string;
}

/**
 * 部门成员，对应 OpenAPI `DepartmentMember`。
 */
export interface DepartmentMember {
  id: string;
  username: string;
  displayName: string;
  status: UserStatus;
}

/**
 * CUSTOM 空间显式成员，对应 OpenAPI `WorkspaceMember`。
 */
export interface WorkspaceMember {
  userId: string;
  username: string;
  displayName: string;
  grantedAt: string;
}

/** 创建部门请求，对应 OpenAPI `DepartmentCreateRequest`。 */
export interface DepartmentCreateRequest {
  name: string;
}

/** 重命名部门请求，对应 OpenAPI `DepartmentUpdateRequest`。 */
export interface DepartmentUpdateRequest {
  name: string;
}

/** 加入部门成员请求，对应 OpenAPI `DepartmentMemberRequest`。 */
export interface DepartmentMemberRequest {
  userId: string;
}

/** 任命/移交部门负责人请求，对应 OpenAPI `DepartmentManagerRequest`。 */
export interface DepartmentManagerRequest {
  userId: string;
}

/**
 * 创建团队空间请求，对应 OpenAPI `WorkspaceCreateRequest`。
 *
 * M03 起团队空间必须归属一个启用部门，可见性只允许 `DEPT` / `CUSTOM`。
 */
export interface WorkspaceCreateRequest {
  name: string;
  description?: string | null;
  departmentId: string;
  visibility?: 'DEPT' | 'CUSTOM';
}

/** 在部门路径下创建空间请求，对应 OpenAPI `DepartmentWorkspaceCreateRequest`。 */
export interface DepartmentWorkspaceCreateRequest {
  name: string;
  description?: string | null;
  visibility?: 'DEPT' | 'CUSTOM';
}

/**
 * 修改空间请求，对应 OpenAPI `WorkspaceUpdateRequest`。
 *
 * `TENANT` 历史空间在 M03 只读，可见性只能传 `DEPT` / `CUSTOM`。
 */
export interface WorkspaceUpdateRequest {
  name?: string;
  description?: string | null;
  visibility?: 'DEPT' | 'CUSTOM';
}

/** 加入 CUSTOM 空间成员请求，对应 OpenAPI `WorkspaceMemberAddRequest`。 */
export interface WorkspaceMemberAddRequest {
  userId: string;
}

/** 支持的办公文件格式。 */
export type FileFormat = 'PDF' | 'DOCX' | 'XLSX' | 'PNG' | 'JPEG';

/** 文件生命周期状态。 */
export type FileStatus = 'AVAILABLE' | 'DELETED';

/** 解析状态。 */
export type ParseStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';

/** 单次解析尝试，对应 OpenAPI `FileParse`。 */
export interface FileParse {
  id: string;
  attempt: number;
  status: ParseStatus;
  parserVersion?: string | null;
  pageCount?: number | null;
  sheetCount?: number | null;
  chunkCount?: number | null;
  errorCode?: string | null;
  errorMessage?: string | null;
  startedAt?: string | null;
  finishedAt?: string | null;
}

/**
 * 工作空间文件，对应 OpenAPI `File`。
 * 命名为 WorkspaceFile 以避免与浏览器全局 `File` 类型混淆。
 */
export interface WorkspaceFile {
  id: string;
  workspaceId: string;
  conversationId: string | null;
  originalName: string;
  format: FileFormat;
  mimeType: string;
  sizeBytes: number;
  sha256: string;
  status: FileStatus;
  parse: FileParse | null;
  createdAt: string;
  deletedAt: string | null;
}

/** 会话状态。 */
export type ConversationStatus = 'ACTIVE' | 'ARCHIVED';

/** 会话，对应 OpenAPI `Conversation`。 */
export interface Conversation {
  id: string;
  workspaceId: string;
  title: string | null;
  status: ConversationStatus;
  lastMessageAt: string | null;
  createdAt: string;
  updatedAt: string;
}

/** 消息角色。 */
export type MessageRole = 'USER' | 'ASSISTANT' | 'SYSTEM' | 'TOOL';

/** 消息状态。 */
export type MessageStatus = 'PENDING' | 'STREAMING' | 'COMPLETED' | 'STOPPED' | 'FAILED';

/** 引用来源类型。 */
export type CitationSourceType = 'FILE' | 'KNOWLEDGE';

/** 引用定位类型。 */
export type LocatorType = 'PAGE' | 'SHEET' | 'PARAGRAPH';

/** 引用条目，对应 OpenAPI `Citation`。 */
export interface Citation {
  ordinal: number;
  sourceType: CitationSourceType;
  fileId?: string | null;
  knowledgeDocumentId?: string | null;
  chunkId?: string | null;
  locatorType?: LocatorType;
  pageNo?: number | null;
  sheetName?: string | null;
  cellRange?: string | null;
  paragraphNo?: number | null;
  /** 页内/图片像素区域；M05 新增，页图渲染由后续模块承接。 */
  bbox?: BoundingBox | null;
  /** OCR 块置信度（0–1）；仅 OCR 来源携带。 */
  ocrConfidence?: number | null;
  /** 引用可用性；缺省按 `AVAILABLE` 处理。 */
  sourceStatus?: CitationSourceStatus;
  title: string;
  snippet: string;
  score?: number | null;
}

/** 消息，对应 OpenAPI `Message`。 */
export interface Message {
  id: string;
  conversationId: string;
  seq: number;
  role: MessageRole;
  content: string;
  status: MessageStatus;
  clientMessageId?: string | null;
  runId?: string | null;
  modelId?: string | null;
  errorCode?: string | null;
  citations?: Citation[];
  createdAt: string;
}

/** 会话详情，包含最近消息。 */
export interface ConversationDetail extends Conversation {
  messages: Message[];
}

/** 发送消息请求，对应 OpenAPI `SendMessageRequest`。 */
export interface SendMessageRequest {
  content: string;
  clientMessageId: string;
  fileIds?: string[];
}

/** 停止生成请求，对应 OpenAPI `StopRunRequest`。 */
export interface StopRunRequest {
  runId: string;
}

/** 文件下载地址，对应 OpenAPI `DownloadUrl`。 */
export interface DownloadUrl {
  url: string;
  expiresAt: string;
}

/** RFC 9457 问题详情，对应 OpenAPI `ProblemDetail`。 */
export interface ProblemDetail {
  type: string;
  title: string;
  status: number;
  detail?: string;
  instance?: string | null;
  code: string;
  traceId: string;
  tenantId?: string | null;
  /** 字段级错误定位；模型端点只读字段、非法状态或 URL 校验使用（ADR-0021 §2.4/§7.3）。 */
  field?: string | null;
  /** 稳定原因码；只在字段级错误出现，例如 `ENDPOINT_NOT_ALLOWED`、`DUPLICATE_CREDENTIAL`。 */
  reason?: string | null;
  /** 配额超限维度；仅 `RICCON-QUOTA-429` 携带。 */
  scopeType?: 'TENANT' | 'USER' | null;
  /** 配额超限窗口；仅 `RICCON-QUOTA-429` 携带。 */
  window?: 'DAILY' | 'MONTHLY' | null;
  /** 生效限额；仅 `RICCON-QUOTA-429` 携带，不直接展示给员工。 */
  limit?: number | null;
  /** 拒绝时已用量；仅 `RICCON-QUOTA-429` 携带，不直接展示给员工。 */
  used?: number | null;
  /** 配额重置时间（ISO 8601 带偏移）；仅 `RICCON-QUOTA-429` 携带。 */
  resetsAt?: string | null;
}

/** 页内/图片像素区域，对应 OpenAPI `BoundingBox`。 */
export interface BoundingBox {
  x: number;
  y: number;
  w: number;
  h: number;
}

/** 引用可用性，对应 OpenAPI `CitationSourceStatus`。 */
export type CitationSourceStatus = 'AVAILABLE' | 'NO_ACCESS' | 'DELETED' | 'UPDATED';

/** SSE 事件名。 */
export type ChatEventType =
  | 'riccon.chat.started.v1'
  | 'riccon.chat.delta.v1'
  | 'riccon.chat.citation.v1'
  | 'riccon.chat.completed.v1'
  | 'riccon.chat.failed.v1'
  | 'riccon.chat.stopped.v1';

/** SSE 事件公共信封。 */
export interface ChatEventBase {
  eventId: string;
  eventType: ChatEventType;
  version: 1;
  occurredAt: string;
  tenantId: string;
  runId: string;
  messageId: string;
  traceId: string;
}

/** 模型开始生成。 */
export interface ChatStartedEvent extends ChatEventBase {
  eventType: 'riccon.chat.started.v1';
  data: { modelId: string; modelKey: string };
}

/** 增量文本。 */
export interface ChatDeltaEvent extends ChatEventBase {
  eventType: 'riccon.chat.delta.v1';
  data: { delta: string };
}

/** 引用到达。 */
export interface ChatCitationEvent extends ChatEventBase {
  eventType: 'riccon.chat.citation.v1';
  data: { citations: Citation[] };
}

/** 回答完成。 */
export interface ChatCompletedEvent extends ChatEventBase {
  eventType: 'riccon.chat.completed.v1';
  data: {
    finishReason: 'stop' | 'length' | 'error';
    promptTokens?: number | null;
    completionTokens?: number | null;
    firstTokenMs?: number | null;
  };
}

/** 回答失败。 */
export interface ChatFailedEvent extends ChatEventBase {
  eventType: 'riccon.chat.failed.v1';
  data: { code: string; message: string; retryable: boolean };
}

/** 回答被停止。 */
export interface ChatStoppedEvent extends ChatEventBase {
  eventType: 'riccon.chat.stopped.v1';
  data: { reason: 'USER_CANCELLED' | 'CLIENT_DISCONNECT' | 'PERMISSION_REVOKED' };
}

/** 对话 SSE 事件联合类型。 */
export type ChatEvent =
  | ChatStartedEvent
  | ChatDeltaEvent
  | ChatCitationEvent
  | ChatCompletedEvent
  | ChatFailedEvent
  | ChatStoppedEvent;

/** 报告任务状态，对应 OpenAPI `ResearchTaskStatus`。 */
export type ResearchTaskStatus =
  'PENDING' | 'RUNNING' | 'WAITING_APPROVAL' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';

/** 报告任务阶段，对应 OpenAPI `RunStage`。 */
export type RunStage = 'RETRIEVAL' | 'TOOL' | 'APPROVAL' | 'GENERATION';

/** 运行类别，对应 OpenAPI `RunKind`。 */
export type RunKind = 'CHAT' | 'FILE_PARSE' | 'KNOWLEDGE_INDEX' | 'RESEARCH_REPORT';

/** 运行状态，对应 OpenAPI `RunStatus`。 */
export type RunStatus =
  'PENDING' | 'RUNNING' | 'WAITING_APPROVAL' | 'RETRY_WAIT' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';

/** 模型调用用途，对应 OpenAPI `ModelPurpose`。 */
export type ModelPurpose = 'CHAT' | 'REPORT';

/** 模型调用状态，对应 OpenAPI `InvocationStatus`。 */
export type InvocationStatus = 'STARTED' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';

/** 阶段状态，对应 OpenAPI `StageStatus`。 */
export type StageStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED';

/** 审批状态，对应 OpenAPI `ApprovalStatus`。 */
export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED' | 'CANCELLED';

/** 审批决定，对应 OpenAPI `ApprovalDecision`。 */
export type ApprovalDecision = 'APPROVE' | 'REJECT';

/** MCP 调用状态，对应 OpenAPI `McpInvocationStatus`。 */
export type McpInvocationStatus = 'STARTED' | 'SUCCEEDED' | 'FAILED' | 'DENIED';

/** MCP 工具风险级别，对应 OpenAPI `McpToolRisk`。 */
export type McpToolRisk = 'READ' | 'WRITE';

/** 产物状态，对应 OpenAPI `ArtifactStatus`。 */
export type ArtifactStatus = 'DRAFT' | 'SAVED' | 'DELETED';

/** 产物引用来源类型，对应 OpenAPI `ArtifactCitation.sourceType`。 */
export type ArtifactSourceType = 'FILE' | 'KNOWLEDGE' | 'MCP';

/** 报告任务摘要，对应 OpenAPI `ResearchTask`。 */
export interface ResearchTask {
  id: string;
  workspaceId: string;
  topic: string;
  status: ResearchTaskStatus;
  currentStage?: RunStage | null;
  mcpToolId?: string | null;
  currentRunId?: string | null;
  failureCode?: string | null;
  failureMessage?: string | null;
  createdAt: string;
  startedAt?: string | null;
  finishedAt?: string | null;
}

/** 阶段记录 details 的白名单键；后端可附加其他脱敏字段。 */
export interface RunStageDetails {
  /** 检索主题或查询摘要。 */
  query?: string;
  /** 检索命中数。 */
  hitCount?: number;
  /** 对应的 MCP 调用 ID。 */
  invocationId?: string;
  /** 审批入参的脱敏摘要。 */
  paramsPreview?: Record<string, unknown> | null;
  /** 工具结果的脱敏摘要。 */
  resultSummary?: string;
  [key: string]: unknown;
}

/** 阶段时间线记录，对应 OpenAPI `RunStageRecord`。 */
export interface RunStageRecord {
  stage: RunStage;
  attempt: number;
  status: StageStatus;
  errorCode?: string | null;
  startedAt?: string | null;
  finishedAt?: string | null;
  /** 服务端白名单摘要；只包含脱敏后的查询、命中数、工具调用 ID 等。 */
  details?: RunStageDetails | null;
}

/**
 * 模型调用记录，对应 OpenAPI `ModelInvocation`。
 *
 * 只包含展示所需的模型标识、首字/耗时、token 与结果状态；不包含提示词、
 * 回答原文、上游凭据或错误堆栈。
 */
export interface ModelInvocation {
  id: string;
  modelId: string;
  modelKey: string;
  purpose: ModelPurpose;
  status: InvocationStatus;
  firstTokenMs?: number | null;
  durationMs?: number | null;
  promptTokens?: number | null;
  completionTokens?: number | null;
  errorCode?: string | null;
  startedAt: string;
  finishedAt?: string | null;
}

/** 运行详情，对应 OpenAPI `RunDetail`。 */
export interface RunDetail {
  id: string;
  kind: RunKind;
  status: RunStatus;
  currentStage?: RunStage | null;
  attempt: number;
  errorCode?: string | null;
  errorMessage?: string | null;
  startedAt: string;
  finishedAt?: string | null;
  modelInvocations: ModelInvocation[];
  stages: RunStageRecord[];
}

/** 审批记录，对应 OpenAPI `Approval`。 */
export interface Approval {
  id: string;
  researchTaskId: string;
  runId: string;
  stage: RunStage;
  attempt: number;
  status: ApprovalStatus;
  actionType: 'MCP_TOOL_CALL';
  toolName: string;
  paramsPreview?: Record<string, unknown> | null;
  paramsSha256: string;
  requestedBy?: string;
  decidedBy?: string | null;
  decidedAt?: string | null;
  decisionComment?: string | null;
  expiresAt?: string | null;
  createdAt: string;
}

/** MCP 调用记录，对应 OpenAPI `McpInvocation`。 */
export interface McpInvocation {
  id: string;
  toolName: string;
  status: McpInvocationStatus;
  paramsSha256: string;
  resultSha256?: string | null;
  durationMs?: number | null;
  errorCode?: string | null;
  startedAt: string;
  finishedAt?: string | null;
}

/** 已授权 MCP 工具，对应 OpenAPI `McpTool`。 */
export interface McpTool {
  id: string;
  serverId?: string | null;
  name: string;
  description?: string | null;
  riskLevel: McpToolRisk;
  requiresApproval: boolean;
  status: 'ACTIVE' | 'DISABLED';
}

/** 产物引用条目，对应 OpenAPI `ArtifactCitation`。 */
export interface ArtifactCitation {
  ordinal: number;
  sourceType: ArtifactSourceType;
  fileId?: string | null;
  knowledgeDocumentId?: string | null;
  mcpInvocationId?: string | null;
  title: string;
  snippet: string;
  locatorType?: LocatorType | null;
  pageNo?: number | null;
  sheetName?: string | null;
  /** Excel 引用区域（如 `B2:D10`）；整表片段为整表范围。 */
  cellRange?: string | null;
  paragraphNo?: number | null;
  bbox?: BoundingBox | null;
  ocrConfidence?: number | null;
  sourceStatus?: CitationSourceStatus;
}

/** 产物版本，对应 OpenAPI `ArtifactVersion`；正文在对象存储中。 */
export interface ArtifactVersion {
  id: string;
  version: number;
  contentType: string;
  sizeBytes: number;
  sha256: string;
  summary?: string | null;
  createdAt: string;
  citations: ArtifactCitation[];
}

/** 产物摘要，对应 OpenAPI `Artifact`。 */
export interface Artifact {
  id: string;
  workspaceId: string;
  researchTaskId?: string | null;
  kind: 'REPORT';
  title: string;
  status: ArtifactStatus;
  currentVersion: number;
  createdAt: string;
  updatedAt: string;
}

/** 产物详情，对应 OpenAPI `ArtifactDetail`。 */
export interface ArtifactDetail extends Artifact {
  currentVersionDetail: ArtifactVersion | null;
}

/** 报告任务详情，对应 OpenAPI `ResearchTaskDetail`。 */
export interface ResearchTaskDetail extends ResearchTask {
  stages: RunStageRecord[];
  approvals?: Approval[];
  mcpInvocations: McpInvocation[];
  sources: Citation[];
  artifact?: ArtifactDetail | null;
}

/** 创建报告任务请求，对应 OpenAPI `ResearchTaskCreateRequest`。 */
export interface ResearchTaskCreateRequest {
  topic: string;
  mcpToolId: string;
}

/** 重试报告任务请求，对应 OpenAPI `ResearchTaskRetryRequest`。 */
export interface ResearchTaskRetryRequest {
  fromStage?: 'TOOL' | 'GENERATION' | null;
}

/** 审批决定请求，对应 OpenAPI `ApprovalDecisionRequest`。 */
export interface ApprovalDecisionRequest {
  decision: ApprovalDecision;
  comment?: string | null;
}

/** 前端展示用的会话消息；与后端 Message 分离，便于表达流式与失败状态。 */
export interface ChatMessage {
  /** 前端本地 key，打开历史消息时使用服务端 id。 */
  id: string;
  role: 'user' | 'assistant';
  content: string;
  status: 'STREAMING' | 'COMPLETED' | 'STOPPED' | 'FAILED';
  citations: Citation[];
  runId?: string;
  modelKey?: string;
  /** 用户提问原文，用于失败后重试；历史消息在计算时补齐。 */
  question?: string;
  error?: {
    code: string;
    message: string;
    retryable: boolean;
  };
}
