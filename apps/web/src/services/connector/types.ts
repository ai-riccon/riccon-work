import type { Page } from '@/services/api/types';

/**
 * M05b 知识库连接器前端契约类型。
 *
 * 类型来源：`contracts/openapi.yaml` 1.5.0-m05b 的 `Connector`、`ConnectorCreate`、
 * `ConnectorUpdate`、`ConnectorCredential`、`ConnectorTestResult`、
 * `ConnectorSyncAccepted`、`ConnectorItem` 与相关枚举。页面只声明实际消费的字段，
 * 新增字段必须先更新契约，再同步本文件。
 */

/** 连接器类型；M05b 只有 S3 兼容目录。 */
export type ConnectorType = 'S3';

/** 连接器状态；`ERROR` 只由同步结果产生，不能通过 PATCH 写入。 */
export type ConnectorStatus = 'ACTIVE' | 'PAUSED' | 'ERROR';

/** PATCH 可显式设置的状态；`ERROR` 不在其中。 */
export type ConnectorTargetStatus = 'ACTIVE' | 'PAUSED';

/** 连接器导入文档的默认可见范围；没有 `USERS`。 */
export type ConnectorVisibility = 'TENANT' | 'DEPT';

/** 最近一次同步结果。 */
export type ConnectorSyncStatus = 'SUCCESS' | 'PARTIAL' | 'ERROR';

/** 单个源对象同步状态。 */
export type ConnectorItemStatus = 'PENDING' | 'SYNCED' | 'SKIPPED' | 'FAILED' | 'DELETED';

/** 连接测试失败分类；成功时为空。 */
export type ConnectorTestCategory = 'AUTH' | 'NETWORK' | 'ENDPOINT' | 'CONFIG';

/** 删除连接器时对已导入文档的处置；默认 `KEEP`。 */
export type ConnectorDocumentDisposition = 'KEEP' | 'SOFT_DELETE';

/** 连接器配置，对应 OpenAPI `ConnectorConfig`。 */
export interface ConnectorConfig {
  bucket: string;
  prefix?: string;
  region?: string | null;
  includeExtensions?: string[];
  excludeGlobs?: string[];
}

/** 连接器，对应 OpenAPI `Connector`。 */
export interface Connector {
  id: string;
  name: string;
  type: ConnectorType;
  status: ConnectorStatus;
  endpointId: string;
  config: ConnectorConfig;
  defaultVisibility: ConnectorVisibility;
  defaultDepartmentId?: string | null;
  credentialMaskedHint?: string | null;
  scheduleEnabled: boolean;
  nextSyncAt?: string | null;
  lastSyncStartedAt?: string | null;
  lastSyncFinishedAt?: string | null;
  lastSyncStatus?: ConnectorSyncStatus | null;
  lastSyncComplete: boolean;
  lastSuccessAt?: string | null;
  lastErrorCode?: string | null;
  lastSyncSummary?: ConnectorSyncSummary | null;
  createdBy?: string | null;
  createdAt: string;
  updatedAt: string;
}

/** 连接器分页，对应 OpenAPI `ConnectorPage`。 */
export type ConnectorPage = Page<Connector>;

/** 创建请求，对应 OpenAPI `ConnectorCreate`；`defaultVisibility` 必须显式提供。 */
export interface ConnectorCreateRequest {
  name: string;
  type: ConnectorType;
  endpointId: string;
  config: ConnectorConfig;
  defaultVisibility: ConnectorVisibility;
  defaultDepartmentId?: string | null;
  scheduleEnabled?: boolean;
  credential?: ConnectorCredential | null;
}

/**
 * 部分更新请求，对应 OpenAPI `ConnectorUpdate`。
 *
 * 不允许修改 `type`，也不允许直接写入 `ERROR`；`defaultDepartmentId` 显式传 null
 * 表示清空部门，缺省表示沿用原值。
 */
export interface ConnectorUpdateRequest {
  name?: string;
  endpointId?: string;
  config?: ConnectorConfig;
  defaultVisibility?: ConnectorVisibility;
  defaultDepartmentId?: string | null;
  scheduleEnabled?: boolean;
  status?: ConnectorTargetStatus;
}

/** 只写不读的 S3 凭据，对应 OpenAPI `ConnectorCredential`。 */
export interface ConnectorCredential {
  accessKeyId: string;
  secretAccessKey: string;
}

/** 连接器列表查询参数；分页从 1 开始。 */
export interface ConnectorQuery {
  page?: number;
  size?: number;
  status?: ConnectorStatus;
  type?: ConnectorType;
}

/** 连接测试结果，对应 OpenAPI `ConnectorTestResult`。 */
export interface ConnectorTestResult {
  success: boolean;
  category?: ConnectorTestCategory | null;
  message: string;
  testedAt: string;
}

/** 同步受理结果，对应 OpenAPI `ConnectorSyncAccepted`。 */
export interface ConnectorSyncAccepted {
  syncId: string;
  connectorId: string;
  status: 'PENDING' | 'RUNNING';
  acceptedAt: string;
}

/** 最近一次同步计数，对应 OpenAPI `ConnectorSyncSummary`。 */
export interface ConnectorSyncSummary {
  scanned: number;
  created: number;
  updated: number;
  skipped: number;
  failed: number;
  deleted: number;
  complete: boolean;
}

/** 同步条目，对应 OpenAPI `ConnectorItem`；不包含对象原文或完整对象 key。 */
export interface ConnectorItem {
  id: string;
  externalId: string;
  externalVersion?: string | null;
  status: ConnectorItemStatus;
  documentId?: string | null;
  generation: number;
  errorCode?: string | null;
  lastSeenAt?: string | null;
  createdAt: string;
  updatedAt: string;
  deletedAt?: string | null;
}

/** 同步条目分页，对应 OpenAPI `ConnectorItemPage`。 */
export type ConnectorItemPage = Page<ConnectorItem>;

/** 同步条目查询参数；分页从 1 开始。 */
export interface ConnectorItemQuery {
  page?: number;
  size?: number;
  status?: ConnectorItemStatus;
}

/** 表单提交的配置值；凭据只在提交时短暂携带，不进入页面状态。 */
export interface ConnectorFormSubmission {
  name: string;
  endpointId: string;
  config: ConnectorConfig;
  defaultVisibility: ConnectorVisibility;
  defaultDepartmentId?: string | null;
  scheduleEnabled: boolean;
  credential?: ConnectorCredential;
}
