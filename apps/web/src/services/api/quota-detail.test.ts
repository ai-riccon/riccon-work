import { describe, expect, it } from 'vitest';
import type { ProblemDetail } from './types';
import {
  describeQuotaExceeded,
  formatOffsetDateTime,
  formatQuotaResetRelative,
  formatQuotaResetText,
} from './quota-detail';

/** 固定当前时间；所有相对时间断言都基于该时钟，避免依赖运行机器时间。 */
const NOW = new Date('2026-11-23T10:00:00+08:00');

/** 构造配额超限 ProblemDetail。 */
function makeProblem(overrides: Partial<ProblemDetail> = {}): ProblemDetail {
  return {
    type: 'about:blank',
    title: '配额超限',
    status: 429,
    code: 'RICCON-QUOTA-429',
    traceId: 'trace-1',
    detail: '已达日限额，2026-11-23T13:00:00+08:00后恢复。',
    scopeType: 'TENANT',
    window: 'DAILY',
    limit: 100,
    used: 100,
    resetsAt: '2026-11-23T13:00:00+08:00',
    ...overrides,
  };
}

describe('配额重置时间', () => {
  it('绝对时间固定为北京时间并带 +08:00', () => {
    expect(formatOffsetDateTime('2026-11-23T05:00:00Z')).toBe('2026-11-23 13:00:00 +08:00');
    expect(formatOffsetDateTime(null)).toBe('—');
    expect(formatOffsetDateTime('bad-time')).toBe('—');
  });

  it('不足 24 小时显示小时，超过 24 小时显示天，已过期显示即将恢复', () => {
    expect(formatQuotaResetRelative('2026-11-23T13:00:00+08:00', NOW)).toBe('还有 3 小时');
    expect(formatQuotaResetRelative('2026-11-24T09:00:00+08:00', NOW)).toBe('还有 23 小时');
    expect(formatQuotaResetRelative('2026-11-25T11:00:00+08:00', NOW)).toBe('还有 2 天');
    expect(formatQuotaResetRelative('2026-11-23T09:00:00+08:00', NOW)).toBe('即将恢复');
    expect(formatQuotaResetRelative(null, NOW)).toBeNull();
    expect(formatQuotaResetRelative('bad-time', NOW)).toBeNull();
  });

  it('相对时间按上行取整，不宣称已恢复但实际仍有限额', () => {
    expect(formatQuotaResetRelative('2026-11-23T10:00:01+08:00', NOW)).toBe('还有 1 小时');
    expect(formatQuotaResetRelative('2026-11-23T12:59:00+08:00', NOW)).toBe('还有 3 小时');
  });

  it('相对时间与绝对时间同时展示', () => {
    expect(formatQuotaResetText('2026-11-23T13:00:00+08:00', NOW)).toBe(
      '2026-11-23 13:00:00 +08:00（还有 3 小时）',
    );
    expect(formatQuotaResetText(null, NOW)).toBe('—');
  });
});

describe('配额超限文案', () => {
  it('透传 detail，并补充相对时间、绝对时间与下一步', () => {
    const message = describeQuotaExceeded(makeProblem(), NOW);

    expect(message).toContain('已达日限额，2026-11-23T13:00:00+08:00后恢复。');
    expect(message).toContain('还有 3 小时');
    expect(message).toContain('2026-11-23 13:00:00 +08:00');
    expect(message).toContain('联系管理员调整限额');
    expect(message).not.toContain('tenantId');
    expect(message).not.toContain('已用');
  });

  it('缺少 detail 时用结构化窗口字段生成限额类型', () => {
    const message = describeQuotaExceeded(
      makeProblem({ detail: undefined, window: 'MONTHLY' }),
      NOW,
    );
    expect(message).toContain('已达月限额');
    expect(message).toContain('还有 3 小时');
  });

  it('缺少结构化字段时仍给出可读兜底且不抛异常', () => {
    const message = describeQuotaExceeded(undefined, NOW);
    expect(message).toContain('已达到配额限额');
    expect(message).toContain('联系管理员调整限额');
  });
});
