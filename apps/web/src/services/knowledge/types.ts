import type {
  BoundingBox,
  CitationSourceStatus,
  LocatorType,
  Page,
  UserSummary,
} from '@/services/api/types';

/**
 * M05 知识库前端契约类型。
 *
 * 类型来源：`contracts/openapi.yaml` 1.4.0-m05 的 `KnowledgeDocument`、
 * `KnowledgeDocumentAcl` 与相关枚举。页面只声明实际消费的字段，
 * 新增字段必须先更新契约，再同步本文件。
 */

/** 文档可见范围，对应 OpenAPI `KnowledgeVisibility`。 */
export type KnowledgeVisibility = 'TENANT' | 'DEPT' | 'USERS';

/** 文档索引状态，对应 OpenAPI `KnowledgeDocumentStatus`。 */
export type KnowledgeDocumentStatus = 'PENDING' | 'INDEXING' | 'INDEXED' | 'FAILED' | 'DELETED';

/** 解析引擎，对应 OpenAPI `ParseEngine`。 */
export type ParseEngine = 'PDFBOX' | 'POI' | 'TESSERACT' | 'RAPIDOCR' | 'NONE' | null;

/** 保留策略来源，对应 OpenAPI `KnowledgeRetentionPolicy`。 */
export type KnowledgeRetentionPolicy = 'DEPLOYMENT_DEFAULT' | 'TENANT_POLICY' | 'EXPLICIT';

/** 工作表结构摘要，对应 OpenAPI `KnowledgeParseSheetSummary`。 */
export interface KnowledgeParseSheetSummary {
  name: string;
  cellRange: string | null;
  empty: boolean;
  indexed?: boolean;
  indexedRange?: string | null;
}

/** OCR 结构摘要，对应 OpenAPI `KnowledgeParseOcrSummary`。 */
export interface KnowledgeParseOcrSummary {
  averageConfidence: number;
  lowConfidencePages: number[];
  failedPages: number[];
}

/** 解析结构摘要，对应 OpenAPI `KnowledgeParseSummary`；只含元数据。 */
export interface KnowledgeParseSummary {
  sheets?: KnowledgeParseSheetSummary[];
  hiddenSheetCount?: number;
  ocr?: KnowledgeParseOcrSummary | null;
}

/** 知识库文档，对应 OpenAPI `KnowledgeDocument`。 */
export interface KnowledgeDocument {
  id: string;
  title: string;
  sourceType: 'UPLOAD' | 'SEED';
  fileId?: string | null;
  status: KnowledgeDocumentStatus;
  checksum: string;
  mimeType: string;
  sizeBytes: number;
  errorCode?: string | null;
  errorMessage?: string | null;
  indexedAt?: string | null;
  /** 最近一次解析/索引失败时间；仅 FAILED 非空。 */
  failedAt?: string | null;
  createdAt: string;
  visibility: KnowledgeVisibility;
  departmentId?: string | null;
  aclUserCount: number;
  parseEngine?: ParseEngine;
  warnings?: string[];
  parseSummary?: KnowledgeParseSummary | null;
  sourceFileAccessLimited?: boolean;
  retentionExpiresAt?: string | null;
  retentionDays?: number | null;
  retentionPolicy?: KnowledgeRetentionPolicy;
}

/** 文档分页，对应 OpenAPI `KnowledgeDocumentPage`。 */
export type KnowledgeDocumentPage = Page<KnowledgeDocument>;

/** ACL 用户条目，对应 OpenAPI `KnowledgeAclUser`。 */
export interface KnowledgeAclUser {
  userId: string;
  displayName: string;
  /** 创建人隐式可见标记；该条目不可从名单移除。 */
  creator?: boolean;
}

/** 文档 ACL，对应 OpenAPI `KnowledgeDocumentAcl`。 */
export interface KnowledgeDocumentAcl {
  visibility: KnowledgeVisibility;
  departmentId?: string | null;
  users: KnowledgeAclUser[];
  creatorUserId?: string | null;
}

/** 整体替换 ACL 的请求体，对应 OpenAPI `KnowledgeDocumentAclUpdate`。 */
export interface KnowledgeDocumentAclUpdate {
  visibility: KnowledgeVisibility;
  departmentId?: string | null;
  userIds?: string[];
}

/** 新文档登记请求，对应 OpenAPI `KnowledgeDocumentCreateRequest`。 */
export interface KnowledgeDocumentCreateRequest {
  title: string;
  fileId: string;
  visibility?: KnowledgeVisibility;
  departmentId?: string | null;
  userIds?: string[];
}

/** 文档列表查询参数；分页从 1 开始。 */
export interface KnowledgeDocumentQuery {
  page?: number;
  size?: number;
  status?: KnowledgeDocumentStatus;
}

/** 下载结果：字节内容与从 `Content-Disposition` 解析出的文件名。 */
export interface KnowledgeDownload {
  blob: Blob;
  filename: string;
}

/** 引用详情统一视图；J1 `Citation` 与 J2 `ArtifactCitation` 都可映射到该结构。 */
export interface CitationView {
  ordinal: number;
  sourceType: 'FILE' | 'KNOWLEDGE' | 'MCP';
  title: string;
  snippet: string;
  fileId?: string | null;
  knowledgeDocumentId?: string | null;
  mcpInvocationId?: string | null;
  locatorType?: LocatorType | null;
  pageNo?: number | null;
  sheetName?: string | null;
  cellRange?: string | null;
  paragraphNo?: number | null;
  bbox?: BoundingBox | null;
  ocrConfidence?: number | null;
  sourceStatus?: CitationSourceStatus;
}

/** ACL 用户目录；由账号管理接口实现，页面测试可注入替身。 */
export interface KnowledgeUserDirectory {
  /** 列出本租户可授权账号（最多 200 个）。 */
  listUsers(): Promise<UserSummary[]>;
}
