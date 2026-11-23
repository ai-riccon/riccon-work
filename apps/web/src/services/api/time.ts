/** 团队约定时区；合同 `project.timezone`。 */
const TEAM_TIME_ZONE = 'Asia/Shanghai';

/**
 * 把带偏移的 ISO 8601 时间格式化为北京时间，并显式保留 `+08:00` 偏移。
 *
 * 解析失败时返回占位符，不把原始异常或服务端文本带到界面。
 *
 * @param value 带偏移的 ISO 8601 字符串或空值
 * @returns 例如 `2026-12-01 00:00:00 +08:00`
 */
export function formatOffsetDateTime(value: string | null | undefined): string {
  if (!value) {
    return '—';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '—';
  }
  const parts = new Intl.DateTimeFormat('zh-CN', {
    timeZone: TEAM_TIME_ZONE,
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
 * 只格式化日期（北京时间）。
 *
 * @param value ISO 时间或空值
 * @returns `YYYY-MM-DD` 或 `—`
 */
export function formatDate(value: string | null | undefined): string {
  if (!value) {
    return '—';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '—';
  }
  const parts = new Intl.DateTimeFormat('zh-CN', {
    timeZone: TEAM_TIME_ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(date);
  const pick = (type: Intl.DateTimeFormatPartTypes): string =>
    parts.find((part) => part.type === type)?.value ?? '';
  return `${pick('year')}-${pick('month')}-${pick('day')}`;
}
