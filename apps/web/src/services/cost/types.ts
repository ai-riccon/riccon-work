import type { PageMeta } from '@/services/api/types';

/**
 * M04 成本看板前端契约类型。
 *
 * 类型来源：`contracts/openapi.yaml` 的 `CostTotals`、`TenantCostBucket`、`PlatformCostBucket`、
 * `TenantCostReport`、`PlatformCostReport`、`CostInvocation` 与 `CostInvocationPage`；
 * 金额与价格一律为字符串，前端只用字符串/整数运算，不使用浮点读取金额。
 */

/** 租户成本聚合维度，对应 OpenAPI `TenantCostReport.groupBy`。 */
export type TenantCostGroupBy = 'USER' | 'MODEL' | 'DAY';

/** 平台成本聚合维度，对应 OpenAPI `PlatformCostReport.groupBy`。 */
export type PlatformCostGroupBy = 'TENANT' | 'MODEL' | 'DAY';

/** 定价状态，对应 OpenAPI `CostPricingStatus`。 */
export type CostPricingStatus = 'PRICED' | 'UNPRICED' | 'USAGE_UNKNOWN';

/** 成本合计，对应 OpenAPI `CostTotals`。 */
export interface CostTotals {
  /** 部署币种（ISO 4217）。 */
  currency: string;
  /** 6 位小数字符串；只汇总已定价且币种一致的调用。 */
  amount: string;
  pricedCalls: number;
  unpricedCalls: number;
  usageUnknownCalls: number;
  /** 历史快照币种与当前配置不一致的调用；不计入金额。 */
  currencyMismatchCalls: number;
  /** `cost_day` 为空（回滚窗口旧 Worker/未回填）的调用；不计入金额。 */
  unbucketedCalls: number;
}

/**
 * 成本分桶的公共字段。
 *
 * 平台桶不含用户字段；租户桶在 USER 维度额外包含 `userId`/`userDisplayName`。
 * 这里把租户字段声明为可选，平台与租户桶都能安全消费。
 */
export interface CostBucket {
  /** 维度键；同时作为表格 rowKey。 */
  key: string;
  tenantId?: string | null;
  tenantCode?: string | null;
  modelId?: string | null;
  modelDisplayName?: string | null;
  /** 只出现在租户成本报告。 */
  userId?: string | null;
  /** 只出现在租户成本报告。 */
  userDisplayName?: string | null;
  /** `Asia/Shanghai` 自然日；只在 DAY 维度出现。 */
  day?: string | null;
  /** 6 位小数金额字符串。 */
  amount: string;
  pricedCalls: number;
  unpricedCalls: number;
  usageUnknownCalls: number;
}

/** 租户成本桶，对应 OpenAPI `TenantCostBucket`。 */
export type TenantCostBucket = CostBucket;

/** 平台成本桶，对应 OpenAPI `PlatformCostBucket`；结构上不含用户字段。 */
export type PlatformCostBucket = Omit<CostBucket, 'userId' | 'userDisplayName'>;

/** 成本报告公共字段；平台与租户报告结构一致，只有 groupBy 枚举与桶字段不同。 */
export interface CostReportBase extends PageMeta {
  /** 起始自然日（含）。 */
  from: string;
  /** 结束自然日（含）。 */
  to: string;
  currency: string;
  /** 币种最小单位；CNY 为 2。金额展示按该单位四舍五入一次。 */
  currencyMinorUnit: number;
  /** 服务端固定免责文案；前端不硬编码第二份。 */
  disclaimer: string;
  totals: CostTotals;
}

/** 租户成本报告，对应 OpenAPI `TenantCostReport`。 */
export interface TenantCostReport extends CostReportBase {
  groupBy: TenantCostGroupBy;
  items: TenantCostBucket[];
}

/** 平台成本报告，对应 OpenAPI `PlatformCostReport`。 */
export interface PlatformCostReport extends CostReportBase {
  groupBy: PlatformCostGroupBy;
  items: PlatformCostBucket[];
}

/** 单次调用的成本快照，对应 OpenAPI `CostInvocation`。 */
export interface CostInvocation {
  id: string;
  modelId: string;
  modelDisplayName: string;
  userId?: string | null;
  userDisplayName?: string | null;
  /** 带偏移的 ISO 8601 开始时间。 */
  startedAt: string;
  promptTokens?: number | null;
  completionTokens?: number | null;
  inputPricePer1M?: string | null;
  outputPricePer1M?: string | null;
  priceCurrency?: string | null;
  amount?: string | null;
  pricingStatus: CostPricingStatus;
}

/** 调用成本明细分页，对应 OpenAPI `CostInvocationPage`。 */
export type CostInvocationPage = PageMeta & { items: CostInvocation[] };

/** 成本报告公共查询参数；`from`/`to` 为 `YYYY-MM-DD`（Asia/Shanghai 自然日）。 */
export interface CostReportQuery {
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

/** 租户成本报告查询。 */
export interface TenantCostQuery extends CostReportQuery {
  groupBy: TenantCostGroupBy;
}

/** 平台成本报告查询；`tenantId` 只作聚合过滤，仍不返回用户明细。 */
export interface PlatformCostQuery extends CostReportQuery {
  groupBy: PlatformCostGroupBy;
  tenantId?: string;
}

/** 调用明细查询；租户范围由服务端从会话派生。 */
export interface CostInvocationQuery extends CostReportQuery {
  userId?: string;
  modelId?: string;
}
