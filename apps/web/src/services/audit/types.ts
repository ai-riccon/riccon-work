import type { PageMeta } from '@/services/api/types';

/**
 * M10 审计查询/导出前端契约类型。
 *
 * 类型来源：`contracts/openapi.yaml` 的 `AuditEvent`、`AuditEventPage`、`AuditPolicy`、
 * `AuditFilter`、`AuditExportRequest` 与相关参数；只声明前端实际消费的字段，
 * 避免复制整份契约形成第二事实源。新增字段时先更新契约，再同步本文件。
 */

/** 操作者类型，对应 OpenAPI `AuditActorType`。 */
export type AuditActorType = 'USER' | 'SERVICE' | 'SYSTEM';

/** 事件结果，对应 OpenAPI `AuditOutcome`。 */
export type AuditOutcome = 'SUCCESS' | 'FAILURE' | 'DENIED';

/** 审计对象类型，对应 OpenAPI `AuditTargetType`。 */
export type AuditTargetType =
  | 'SESSION'
  | 'WORKSPACE'
  | 'DEPARTMENT'
  | 'FILE'
  | 'CONVERSATION'
  | 'KNOWLEDGE_DOCUMENT'
  | 'RESEARCH_TASK'
  | 'RUN'
  | 'ARTIFACT'
  | 'MODEL'
  | 'MCP_TOOL'
  | 'USER'
  | 'ROLE'
  | 'TENANT'
  | 'AUDIT'
  | 'AUDIT_EXPORT';

/** 导出请求展示状态；`EXPIRED` 由服务端按批准时间与过期时间派生。 */
export type AuditExportStatus = 'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED' | 'EXPIRED';

/** 审批决定，对应 OpenAPI `AuditExportDecision`。 */
export type AuditExportDecision = 'APPROVE' | 'REJECT';

/** 一条审计事件，对应 OpenAPI `AuditEvent`。 */
export interface AuditEvent {
  eventId: string;
  occurredAt: string;
  tenantId: string;
  actorType: AuditActorType;
  actorId: string;
  actorDisplayName?: string | null;
  action: string;
  targetType?: AuditTargetType | null;
  targetId?: string | null;
  outcome: AuditOutcome;
  traceId: string;
  details?: Record<string, unknown> | null;
}

/** 审计分页，对应 OpenAPI `AuditEventPage`。 */
export type AuditEventPage = PageMeta & { items: AuditEvent[] };

/** 导出限制，对应 OpenAPI `AuditExportLimits`。 */
export interface AuditExportLimits {
  ttlHours: number;
  rowLimit: number;
}

/** 审计策略，对应 OpenAPI `AuditPolicy`。 */
export interface AuditPolicy {
  retentionDays: number;
  maxRangeDays: number;
  pageSizeMax: number;
  maxPage: number;
  actions: string[];
  exportLimits: AuditExportLimits;
}

/** 查询/导出过滤器，对应 OpenAPI `AuditFilter`；时间必须带偏移。 */
export interface AuditFilter {
  occurredFrom?: string;
  occurredTo?: string;
  action?: string;
  actorId?: string;
  targetType?: AuditTargetType;
  targetId?: string;
  outcome?: AuditOutcome;
}

/** 审计查询参数；排序固定由服务端决定，不传客户端排序字段。 */
export interface AuditEventQuery extends AuditFilter {
  page?: number;
  size?: number;
}

/** 导出请求列表参数。 */
export interface AuditExportListQuery {
  status?: AuditExportStatus;
  page?: number;
  size?: number;
}

/** 导出申请体，对应 OpenAPI `AuditExportCreateRequest`。 */
export interface AuditExportCreateRequest {
  filter: AuditFilter;
}

/** 审批请求体，对应 OpenAPI `AuditExportDecisionRequest`。 */
export interface AuditExportDecisionRequest {
  decision: AuditExportDecision;
  comment?: string | null;
}

/** 导出请求，对应 OpenAPI `AuditExportRequest`。 */
export interface AuditExportRequest {
  id: string;
  status: AuditExportStatus;
  requestedBy: string;
  requestedByDisplayName?: string | null;
  filter: AuditFilter;
  estimatedRowCount: number;
  approvedBy?: string | null;
  approvedByDisplayName?: string | null;
  decidedAt?: string | null;
  decisionComment?: string | null;
  expiresAt?: string | null;
  createdAt: string;
}

/** 导出请求分页，对应 OpenAPI `AuditExportRequestPage`。 */
export type AuditExportRequestPage = PageMeta & { items: AuditExportRequest[] };

/** 下载结果：CSV 二进制与服务端给出的文件名。 */
export interface AuditExportDownload {
  blob: Blob;
  filename: string;
}
