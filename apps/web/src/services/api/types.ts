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

/** 用户摘要，对应 OpenAPI `UserSummary`。 */
export interface UserSummary {
  id: string;
  username: string;
  displayName: string;
  email: string | null;
  status: UserStatus;
  roles: string[];
  lastLoginAt: string | null;
  createdAt: string;
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
}

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
