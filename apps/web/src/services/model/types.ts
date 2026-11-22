import type { ModelPurpose, PageMeta } from '@/services/api/types';

/**
 * M04 模型网关管理端前端契约类型。
 *
 * 类型来源：`contracts/openapi.yaml` 的 `EmployeeModel`、`AdminModel`、`ModelProvider`、
 * `ModelRouting*`、`ModelCredential*` 与相关参数；只声明前端实际消费的字段，
 * 避免复制整份契约形成第二事实源。新增字段时先更新契约，再同步本文件。
 */

/** 端点部署标签，对应 OpenAPI `EndpointEnvironmentLabel`。 */
export type EndpointEnvironmentLabel = 'PRODUCTION' | 'DEMO_ONLY';

/** 模型启停状态，对应 OpenAPI `AdminModel.status`。 */
export type ModelStatus = 'ACTIVE' | 'DISABLED';

/** 平台 key 状态，对应 OpenAPI `ModelCredentialStatus`。 */
export type ModelCredentialStatus = 'ACTIVE' | 'DISABLED' | 'INVALID';

/** 员工可见模型，对应 OpenAPI `EmployeeModel`；不包含 provider、价格或 key。 */
export interface EmployeeModel {
  id: string;
  modelKey: string;
  displayName: string;
  contextWindow: number;
  maxOutputTokens: number;
  capabilities?: Record<string, unknown> | null;
  status: ModelStatus;
}

/** 员工模型分页，对应 OpenAPI `ModelPage`。 */
export type ModelPage = PageMeta & { items: EmployeeModel[] };

/** 平台模型目录行，对应 OpenAPI `AdminModel`。 */
export interface AdminModel {
  id: string;
  modelKey: string;
  displayName: string;
  providerId: string;
  providerCode: string;
  environmentLabel: EndpointEnvironmentLabel;
  contextWindow: number;
  maxOutputTokens: number;
  capabilities?: Record<string, unknown> | null;
  inputPricePer1M?: string | null;
  outputPricePer1M?: string | null;
  priceCurrency?: string | null;
  pricingStatus: 'PRICED' | 'UNPRICED';
  status: ModelStatus;
}

/** 平台模型分页，对应 OpenAPI `AdminModelPage`。 */
export type AdminModelPage = PageMeta & { items: AdminModel[] };

/** 模型启停请求，对应 OpenAPI `ModelStatusUpdateRequest`。 */
export interface ModelStatusUpdateRequest {
  status: ModelStatus;
}

/** 平台端点目录行，对应 OpenAPI `ModelProvider`；`baseUrl` 只对平台管理员可见。 */
export interface ModelProvider {
  id: string;
  code: string;
  name: string;
  protocol: 'OPENAI_COMPATIBLE';
  baseUrl?: string;
  environmentLabel: EndpointEnvironmentLabel;
  /** 是否在部署允许列表且地址一致；false 时不可被路由选择。 */
  allowed: boolean;
  activeCredentialCount: number;
  modelCount: number;
  status: ModelStatus;
}

/** 平台端点分页，对应 OpenAPI `ModelProviderPage`。 */
export type ModelProviderPage = PageMeta & { items: ModelProvider[] };

/** 路由链条目，对应 OpenAPI `ModelRoutingEntry`。 */
export interface ModelRoutingEntry {
  position: number;
  modelId: string;
  displayName: string;
  status: ModelStatus;
  environmentLabel: EndpointEnvironmentLabel;
}

/** 一条路由链，对应 OpenAPI `ModelRouting`。 */
export interface ModelRouting {
  purpose: ModelPurpose;
  version: number;
  entries: ModelRoutingEntry[];
  updatedAt: string;
  updatedBy?: string | null;
}

/** 两条路由链，对应 OpenAPI `ModelRoutingList`；CHAT/REPORT 各一条。 */
export interface ModelRoutingList {
  items: ModelRouting[];
}

/** 整链保存请求，对应 OpenAPI `ModelRoutingUpdateRequest`。 */
export interface ModelRoutingUpdateRequest {
  version: number;
  modelIds: string[];
}

/** 平台 key 视图，对应 OpenAPI `ModelCredential`；永不包含明文或密文。 */
export interface ModelCredential {
  id: string;
  providerId: string;
  label: string;
  maskedHint: string;
  status: ModelCredentialStatus;
  priority: number;
  version: number;
  consecutiveFailures?: number;
  cooldownUntil?: string | null;
  /** 状态变更接口使用；false 表示幂等无变化、未重复写审计。 */
  changed?: boolean;
  createdAt: string;
  updatedAt: string;
  rotatedAt?: string | null;
  lastUsedAt?: string | null;
}

/** 平台 key 分页，对应 OpenAPI `ModelCredentialPage`。 */
export type ModelCredentialPage = PageMeta & { items: ModelCredential[] };

/** 新增 key 请求，对应 OpenAPI `ModelCredentialCreateRequest`；明文只进不出。 */
export interface ModelCredentialCreateRequest {
  label: string;
  apiKey: string;
  priority?: number;
}

/** 轮换 key 请求，对应 OpenAPI `ModelCredentialRotateRequest`。 */
export interface ModelCredentialRotateRequest {
  apiKey: string;
  label?: string;
}
