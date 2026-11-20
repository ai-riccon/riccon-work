import { ApiError, describeApiError } from '@/services/api/errors';
import type { CostBucket, CostPricingStatus } from './types';

/** 固定免责文案兜底；正常情况使用服务端 `disclaimer`，避免前端硬编码漂移（ADR-0022 §2.4）。 */
export const COST_DISCLAIMER = '成本为按调用时价格快照估算，不是供应商账单。';

/** 成本金额与价格精度；与 ADR-0022 §2.2 保持一致。 */
export const COST_SCALE = 6;

/** 成本可查天数上限；与 ADR-0022 §2.4/§2.5 保持一致。 */
export const COST_MAX_DAYS = 180;

/** 成本默认查询天数（含端点）。 */
export const COST_DEFAULT_DAYS = 30;

/** 团队默认时区；日桶与重置时间统一按北京时间展示。 */
const TIME_ZONE = 'Asia/Shanghai';

/** 聚合维度中文名；租户与平台维度合并声明，未命中时回退维度键。 */
const GROUP_LABELS: Record<string, string> = {
  TENANT: '按租户',
  USER: '按用户',
  MODEL: '按模型',
  DAY: '按日',
};

/** 定价状态中文名。 */
const PRICING_LABELS: Record<CostPricingStatus, string> = {
  PRICED: '已定价',
  UNPRICED: '未定价',
  USAGE_UNKNOWN: '用量未知',
};

/** 成本错误文案上下文。 */
export type CostErrorContext = 'platform-report' | 'tenant-report' | 'invocations';

/**
 * 把非负小数字符串按指定小数位四舍五入（HALF_UP）。
 *
 * 使用 BigInt 做十进制整数运算，避免 `Number`/`parseFloat` 的二进制浮点误差；
 * 金额在契约中是 `\d{1,14}(\.\d{1,6})?` 字符串，这里只接受该形状，其他输入按 0 处理。
 *
 * @param value 十进制字符串，例如 `0.123456`
 * @param scale 保留小数位（0..6）
 * @returns 指定小数位的字符串；scale=0 时不含小数点
 */
export function roundDecimalString(value: string, scale: number): string {
  const safeScale = Math.min(Math.max(Math.trunc(scale), 0), COST_SCALE);
  const trimmed = value.trim();
  if (!/^\d+(\.\d+)?$/.test(trimmed)) {
    return safeScale === 0 ? '0' : `0.${'0'.repeat(safeScale)}`;
  }
  const [integerPart, fractionPart = ''] = trimmed.split('.');
  const digits = fractionPart.padEnd(safeScale + 1, '0');
  const keptDigits = digits.slice(0, safeScale);
  const nextDigit = Number(digits.charAt(safeScale));
  let scaled = BigInt(`${integerPart}${keptDigits}` || '0');
  if (nextDigit >= 5) {
    scaled += 1n;
  }
  const divisor = 10n ** BigInt(safeScale);
  const integer = scaled / divisor;
  if (safeScale === 0) {
    return integer.toString();
  }
  const fraction = (scaled % divisor).toString().padStart(safeScale, '0');
  return `${integer}.${fraction}`;
}

/**
 * 给整数部分添加千分位分隔符。
 *
 * @param value 整数字符串，可带负号
 * @returns 例如 `1,234,567`
 */
export function groupThousands(value: string): string {
  const sign = value.startsWith('-') ? '-' : '';
  const digits = sign ? value.slice(1) : value;
  return `${sign}${digits.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}`;
}

/**
 * 按币种最小单位展示金额。
 *
 * 契约金额是未舍入的 6 位小数字符串；展示时只在此处按 HALF_UP 舍入一次，
 * 不对分桶先舍入再求和（ADR-0022 §2.2）。最小单位非法时回退到 2 位。
 *
 * @param amount 6 位小数金额字符串或空值
 * @param currency ISO 4217 币种
 * @param minorUnit 币种最小单位（0..4）
 * @returns 例如 `CNY 12.35`；空值返回 `—`
 */
export function formatCostAmount(
  amount: string | null | undefined,
  currency: string,
  minorUnit: number,
): string {
  if (!amount) {
    return '—';
  }
  const safeMinorUnit = Number.isFinite(minorUnit) ? Math.min(Math.max(minorUnit, 0), 4) : 2;
  const rounded = roundDecimalString(amount, safeMinorUnit);
  const [integerPart, fractionPart] = rounded.split('.');
  const formatted = fractionPart
    ? `${groupThousands(integerPart)}.${fractionPart}`
    : groupThousands(integerPart);
  return currency ? `${currency} ${formatted}` : formatted;
}

/**
 * 展示未舍入的 6 位金额，用于明细与精确值提示。
 *
 * @param amount 金额字符串或空值
 * @returns 例如 `0.123456`；空值返回 `—`
 */
export function formatCostExactAmount(amount: string | null | undefined): string {
  if (!amount) {
    return '—';
  }
  return roundDecimalString(amount, COST_SCALE);
}

/**
 * 金额与调用量的千分位格式。
 *
 * @param value 非负整数
 * @returns 例如 `1,024`
 */
export function formatCostCount(value: number): string {
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 }).format(value);
}

/**
 * 返回聚合维度中文名。
 *
 * @param groupBy 维度键
 * @returns 中文名；未知键原样返回
 */
export function costGroupByLabel(groupBy: string): string {
  return GROUP_LABELS[groupBy] ?? groupBy;
}

/**
 * 返回成本桶标题。
 *
 * 平台 TENANT 桶只有租户代码；租户 USER 桶优先展示姓名，退化到用户 ID；MODEL 桶同理。
 *
 * @param bucket 成本桶
 * @param groupBy 当前维度
 * @returns 表格主标签
 */
export function costBucketTitle(bucket: CostBucket, groupBy: string): string {
  if (groupBy === 'TENANT') {
    return bucket.tenantCode || bucket.tenantId || bucket.key;
  }
  if (groupBy === 'USER') {
    return bucket.userDisplayName || bucket.userId || bucket.key;
  }
  if (groupBy === 'MODEL') {
    return bucket.modelDisplayName || bucket.modelId || bucket.key;
  }
  if (groupBy === 'DAY') {
    return bucket.day || bucket.key;
  }
  return bucket.key;
}

/**
 * 返回成本桶副标签（对象标识）。
 *
 * 只在主标签是展示名时补充 ID，便于管理员核对；平台租户桶没有名称，返回 null。
 *
 * @param bucket 成本桶
 * @param groupBy 当前维度
 * @returns 副标签或 null
 */
export function costBucketDetail(bucket: CostBucket, groupBy: string): string | null {
  if (groupBy === 'USER') {
    return bucket.userDisplayName && bucket.userId ? bucket.userId : null;
  }
  if (groupBy === 'MODEL') {
    return bucket.modelDisplayName && bucket.modelId ? bucket.modelId : null;
  }
  return null;
}

/**
 * 返回定价状态中文名。
 *
 * @param status 定价状态
 * @returns 中文名
 */
export function pricingStatusLabel(status: CostPricingStatus): string {
  return PRICING_LABELS[status];
}

/**
 * 返回定价状态对应的 antd Tag 颜色。
 *
 * @param status 定价状态
 * @returns antd Tag preset 颜色
 */
export function pricingStatusColor(status: CostPricingStatus): string {
  if (status === 'PRICED') {
    return 'green';
  }
  if (status === 'UNPRICED') {
    return 'gold';
  }
  return 'default';
}

/**
 * 把带偏移的 ISO 8601 时间格式化为北京时间，并显式保留 `+08:00` 偏移。
 *
 * @param value 带偏移的 ISO 8601 字符串或空值
 * @returns 例如 `2026-11-21 10:30:00 +08:00`
 */
export function formatCostTime(value: string | null | undefined): string {
  if (!value) {
    return '—';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '—';
  }
  const parts = new Intl.DateTimeFormat('zh-CN', {
    timeZone: TIME_ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(date);
  const pick = (type: Intl.DateTimeFormatPartTypes): string =>
    parts.find((part) => part.type === type)?.value ?? '';
  return `${pick('year')}-${pick('month')}-${pick('day')} ${pick('hour')}:${pick(
    'minute',
  )}:${pick('second')} +08:00`;
}

/**
 * 判断字符串是否为合法的 `YYYY-MM-DD` 日期。
 *
 * @param value 日期文本
 * @returns 是否合法
 */
export function isIsoDate(value: string): boolean {
  const matched = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!matched) {
    return false;
  }
  const year = Number(matched[1]);
  const month = Number(matched[2]);
  const day = Number(matched[3]);
  const date = new Date(Date.UTC(year, month - 1, day));
  return (
    date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day
  );
}

/**
 * 返回北京时间“今天”的自然日字符串。
 *
 * @param now 当前瞬时；测试注入固定时间
 * @returns `YYYY-MM-DD`
 */
export function shanghaiToday(now: Date = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: TIME_ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(now);
  const pick = (type: Intl.DateTimeFormatPartTypes): string =>
    parts.find((part) => part.type === type)?.value ?? '';
  return `${pick('year')}-${pick('month')}-${pick('day')}`;
}

/**
 * 在自然日上加减天数。
 *
 * 只在 `Asia/Shanghai` 无 DST 的前提下按 UTC 正午对齐计算，避免本地时区影响结果。
 *
 * @param date `YYYY-MM-DD`
 * @param days 天数（可为负）
 * @returns 新的 `YYYY-MM-DD`
 */
export function addDays(date: string, days: number): string {
  const [year, month, day] = date.split('-').map(Number);
  const base = new Date(Date.UTC(year, month - 1, day, 12));
  base.setUTCDate(base.getUTCDate() + days);
  return base.toISOString().slice(0, 10);
}

/**
 * 生成默认成本查询范围（最近 N 天，含今天）。
 *
 * @param days 天数；默认 30 天
 * @param now 当前瞬时；测试注入固定时间
 * @returns 起止自然日
 */
export function defaultCostRange(
  days: number = COST_DEFAULT_DAYS,
  now: Date = new Date(),
): { from: string; to: string } {
  const safeDays = Math.min(Math.max(Math.trunc(days), 1), COST_MAX_DAYS);
  const to = shanghaiToday(now);
  return { from: addDays(to, -(safeDays - 1)), to };
}

/**
 * 校验成本查询时间范围。
 *
 * 与后端 `CostRange` 同口径：日期格式、起止顺序、最多 180 天（含端点）。
 *
 * @param from 起始日
 * @param to 结束日
 * @returns 错误文案；合法时返回 null
 */
export function validateCostRange(from: string, to: string): string | null {
  if (!isIsoDate(from) || !isIsoDate(to)) {
    return '请选择有效的开始与结束日期（YYYY-MM-DD）。';
  }
  if (from > to) {
    return '开始日期不能晚于结束日期。';
  }
  const [fromYear, fromMonth, fromDay] = from.split('-').map(Number);
  const [toYear, toMonth, toDay] = to.split('-').map(Number);
  const start = Date.UTC(fromYear, fromMonth - 1, fromDay);
  const end = Date.UTC(toYear, toMonth - 1, toDay);
  const days = Math.round((end - start) / 86_400_000) + 1;
  if (days > COST_MAX_DAYS) {
    return `可查范围不能超过 ${COST_MAX_DAYS} 天，请缩小时间范围。`;
  }
  return null;
}

/**
 * 把成本查询错误归一为固定中文文案与下一步。
 *
 * 不直接展示服务端 `detail`，避免内部细节进入页面；成本查看不写审计（ADR-0022 §2.4）。
 *
 * @param error 任意异常
 * @param context 操作上下文
 * @returns 面向管理员的中文说明
 */
export function describeCostError(error: unknown, context: CostErrorContext): string {
  if (!(error instanceof ApiError)) {
    return '成本查询未完成，请稍后重试。';
  }

  if (error.status === 401) {
    return '登录状态已失效，请重新登录后再查看成本。';
  }
  if (error.status === 403 || error.code === 'RICCON-AUTH-403') {
    return context === 'invocations'
      ? '没有调用明细查看权限；明细只向本租户管理员开放。'
      : '没有成本查看权限；成本看板仅向平台管理员与租户管理员开放，员工无入口。';
  }
  if (error.status === 404) {
    return '用户或模型不属于本租户，或对象已被移除；请清除过滤条件后重试。';
  }
  if (error.status === 400 || error.code === 'RICCON-COST-400') {
    return `时间范围或分页参数不正确；可查范围最多 ${COST_MAX_DAYS} 天，每页最多 100 条。`;
  }
  if (error.status === 503 || error.code === 'RICCON-COST-503') {
    return '成本查询服务暂不可用，服务端未返回部分结果；请稍后重试。';
  }

  return describeApiError(error, '成本查询未完成，请稍后重试。');
}
