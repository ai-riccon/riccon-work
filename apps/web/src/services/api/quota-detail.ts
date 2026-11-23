import type { ProblemDetail } from './types';
import { formatOffsetDateTime } from './time';

export { formatOffsetDateTime } from './time';

/**
 * 生成配额重置的相对时间。
 *
 * 按剩余时长选择可读粒度：不足 24 小时显示“还有 X 小时”，更远显示“还有 X 天”；
 * 已到期或时间缺失时不臆造相对时长。绝对时间仍由调用方同时展示，
 * 保证跨月/跨年时相对与绝对信息不矛盾（M05-AC-18）。
 *
 * @param value 重置时间（ISO 8601 带偏移）
 * @param now 当前时间；测试可注入固定时钟
 * @returns 例如 `还有 3 小时`、`还有 2 天`；无法计算时为 null
 */
export function formatQuotaResetRelative(
  value: string | null | undefined,
  now: Date = new Date(),
): string | null {
  if (!value) {
    return null;
  }
  const target = new Date(value);
  if (Number.isNaN(target.getTime())) {
    return null;
  }
  const diffMs = target.getTime() - now.getTime();
  if (diffMs <= 0) {
    return '即将恢复';
  }
  const hours = Math.max(1, Math.ceil(diffMs / 3_600_000));
  if (hours < 24) {
    return `还有 ${hours} 小时`;
  }
  const days = Math.max(1, Math.floor(hours / 24));
  return `还有 ${days} 天`;
}

/**
 * 同时展示绝对重置时间与相对剩余时间。
 *
 * @param value 重置时间（ISO 8601 带偏移）
 * @param now 当前时间；测试可注入固定时钟
 * @returns 例如 `2026-12-01 00:00:00 +08:00（还有 3 小时）`
 */
export function formatQuotaResetText(
  value: string | null | undefined,
  now: Date = new Date(),
): string {
  const absolute = formatOffsetDateTime(value);
  if (absolute === '—') {
    return absolute;
  }
  const relative = formatQuotaResetRelative(value, now);
  return relative ? `${absolute}（${relative}）` : absolute;
}

/**
 * 把 `RICCON-QUOTA-429` 的 ProblemDetail 转换为员工可读文案。
 *
 * 设计取舍：
 * 1. 透传服务端 `detail`，保持“限额类型 + 重置时间”的权威口径；
 * 2. 使用结构化字段补充相对时间和带 +08:00 的绝对时间，避免只显示原始时间字符串；
 * 3. 不展示 tenantId、scopeId、limit/used 等内部标识，避免泄露其他租户或用户用量。
 *
 * @param problem 配额超限 ProblemDetail
 * @param now 当前时间；测试可注入固定时钟
 * @returns 面向员工的中文说明
 */
export function describeQuotaExceeded(
  problem: ProblemDetail | undefined,
  now: Date = new Date(),
): string {
  const windowLabel =
    problem?.window === 'DAILY' ? '日' : problem?.window === 'MONTHLY' ? '月' : null;
  const headline =
    problem?.detail?.trim() || (windowLabel ? `已达${windowLabel}限额。` : '已达到配额限额。');
  const relative = formatQuotaResetRelative(problem?.resetsAt, now);
  const absolute = formatOffsetDateTime(problem?.resetsAt);
  const details: string[] = [];
  if (relative) {
    details.push(relative);
  }
  if (absolute !== '—') {
    details.push(`重置时间 ${absolute}`);
  }
  const reset = details.length > 0 ? ` ${details.join('，')}。` : '';
  return `${headline}${reset} 请等待配额重置，或联系管理员调整限额。`;
}
