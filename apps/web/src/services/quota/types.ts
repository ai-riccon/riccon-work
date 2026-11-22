import type { PageMeta } from '@/services/api/types';

/**
 * M04 配额管理前端契约类型。
 *
 * 类型来源：`contracts/openapi.yaml` 的 `QuotaWindowKind`、`QuotaWindowState`、`QuotaLimitSource`、
 * `TenantQuota`、`UserQuota`、`UserQuotaPage`、`PlatformQuotaRow`、`PlatformQuotaPage` 与
 * `QuotaUpdateRequest`；只声明前端实际消费的字段，避免复制整份契约形成第二事实源。
 * 新增字段时先更新契约，再同步本文件。
 */

/** 配额窗口，对应 OpenAPI `QuotaWindowKind`。 */
export type QuotaWindowKind = 'DAILY' | 'MONTHLY';

/** 配额维度，对应 OpenAPI `QuotaScopeType`；页面只展示，不用于写请求。 */
export type QuotaScopeType = 'TENANT' | 'USER';

/**
 * 限额来源，对应 OpenAPI `QuotaLimitSource`。
 *
 * `EXPLICIT_UNLIMITED` 表示显式设置为不限；`DEPLOYMENT_DEFAULT` 表示没有显式行，
 * 使用部署配置默认值（默认值也可能是不限）。
 */
export type QuotaLimitSource = 'EXPLICIT' | 'EXPLICIT_UNLIMITED' | 'DEPLOYMENT_DEFAULT';

/** 单个窗口的限额与用量，对应 OpenAPI `QuotaWindowState`。 */
export interface QuotaWindowState {
  window: QuotaWindowKind;
  /** 生效限额；null 表示不限（显式不限或部署默认 0）。 */
  limit: number | null;
  /** 当前窗口已准入的请求数。 */
  used: number;
  /** 下一次重置的绝对时间，带 +08:00 偏移。 */
  resetsAt: string;
  source: QuotaLimitSource;
}

/** 本租户配额，对应 OpenAPI `TenantQuota`；`tenantName` 在租户自读时可能为 null。 */
export interface TenantQuota {
  tenantId: string;
  tenantCode: string;
  tenantName?: string | null;
  windows: QuotaWindowState[];
}

/** 租户内某个用户的配额，对应 OpenAPI `UserQuota`。 */
export interface UserQuota {
  userId: string;
  username: string;
  displayName: string;
  windows: QuotaWindowState[];
}

/** 本租户用户配额分页，对应 OpenAPI `UserQuotaPage`。 */
export type UserQuotaPage = PageMeta & { items: UserQuota[] };

/**
 * 平台租户聚合行，对应 OpenAPI `PlatformQuotaRow`。
 *
 * 平台视图**不含**用户明细；结构上与 `TenantQuota` 一致，但语义不同，单独命名避免误用。
 */
export interface PlatformQuotaRow {
  tenantId: string;
  tenantCode: string;
  tenantName?: string | null;
  windows: QuotaWindowState[];
}

/** 平台租户配额分页，对应 OpenAPI `PlatformQuotaPage`。 */
export type PlatformQuotaPage = PageMeta & { items: PlatformQuotaRow[] };

/**
 * 整体替换请求，对应 OpenAPI `QuotaUpdateRequest`。
 *
 * 日/月两个键都必须出现在请求体中；`null` 表示明确不限，0 也等价于不限。
 */
export interface QuotaUpdateRequest {
  limits: {
    daily: number | null;
    monthly: number | null;
  };
}

/** 分页查询参数；缺省时由服务端使用页码 1、每页 20。 */
export interface QuotaPageQuery {
  page?: number;
  size?: number;
}
