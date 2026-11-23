import { describe, expect, it } from 'vitest';
import { ApiError } from '@/services/api/errors';
import {
  describeQuotaError,
  findQuotaWindow,
  formatQuotaCount,
  formatQuotaLimit,
  formatQuotaResetRelative,
  formatQuotaResetText,
  formatQuotaTime,
  quotaLimitSourceLabel,
  quotaRemainingText,
  quotaTenantLabel,
  quotaUsageText,
  quotaWindowLabel,
} from './format';
import type { QuotaWindowState } from './types';

/** 构造窗口状态。 */
function makeWindow(overrides: Partial<QuotaWindowState> = {}): QuotaWindowState {
  return {
    window: 'DAILY',
    limit: 1000,
    used: 12,
    resetsAt: '2026-11-22T00:00:00+08:00',
    source: 'EXPLICIT',
    ...overrides,
  };
}

describe('配额格式', () => {
  it('窗口与来源使用固定中文名', () => {
    expect(quotaWindowLabel('DAILY')).toBe('日');
    expect(quotaWindowLabel('MONTHLY')).toBe('月');
    expect(quotaLimitSourceLabel('EXPLICIT')).toBe('显式设置');
    expect(quotaLimitSourceLabel('EXPLICIT_UNLIMITED')).toBe('明确不限');
    expect(quotaLimitSourceLabel('DEPLOYMENT_DEFAULT')).toBe('部署默认');
  });

  it('null 与 0 都显示为不限，正整数使用千分位', () => {
    expect(formatQuotaLimit(null)).toBe('不限');
    expect(formatQuotaLimit(undefined)).toBe('不限');
    expect(formatQuotaLimit(0)).toBe('不限');
    expect(formatQuotaLimit(1000)).toBe('1,000');
    expect(formatQuotaCount(200000)).toBe('200,000');
  });

  it('用量、限额来源与剩余次数文案一致', () => {
    const state = makeWindow();
    expect(quotaUsageText(state)).toBe('已用 12 / 1,000');
    expect(quotaLimitSourceLabel(state.source)).toBe('显式设置');
    expect(quotaRemainingText(state)).toBe('剩余 988');
  });

  it('不限与超额时剩余次数不出现负数', () => {
    expect(quotaRemainingText(makeWindow({ limit: null }))).toBe('不限');
    expect(quotaRemainingText(makeWindow({ limit: 10, used: 25 }))).toBe('剩余 0');
  });

  it('按类型查找窗口，不依赖数组顺序', () => {
    const windows = [makeWindow({ window: 'MONTHLY', limit: 20000 }), makeWindow()];
    expect(findQuotaWindow(windows, 'DAILY')?.limit).toBe(1000);
    expect(findQuotaWindow(windows, 'MONTHLY')?.limit).toBe(20000);
    expect(findQuotaWindow(undefined, 'DAILY')).toBeUndefined();
  });

  it('租户展示名优先使用名称，空名称回退到代码', () => {
    expect(quotaTenantLabel({ tenantCode: 'acme', tenantName: '示例企业' })).toBe('示例企业');
    expect(quotaTenantLabel({ tenantCode: 'acme', tenantName: '  ' })).toBe('acme');
    expect(quotaTenantLabel({ tenantCode: 'acme', tenantName: null })).toBe('acme');
  });

  it('重置时间按北京时间显示并保留 +08:00 偏移', () => {
    expect(formatQuotaTime('2026-12-01T00:00:00+08:00')).toBe('2026-12-01 00:00:00 +08:00');
    expect(formatQuotaTime('2026-11-30T16:00:00Z')).toBe('2026-12-01 00:00:00 +08:00');
    expect(formatQuotaTime(null)).toBe('—');
    expect(formatQuotaTime('not-a-time')).toBe('—');
  });

  it('错误文案区分查看与保存上下文', () => {
    const forbidden = new ApiError('forbidden', { status: 403, code: 'RICCON-AUTH-403' });
    expect(describeQuotaError(forbidden, 'platform-load')).toContain('没有配额查看权限');
    expect(describeQuotaError(forbidden, 'limit-save')).toContain('只有平台管理员');

    const conflict = new ApiError('conflict', { status: 409, code: 'RICCON-TENANT-409' });
    expect(describeQuotaError(conflict, 'limit-save')).toContain('目标租户已停用');

    const unavailable = new ApiError('unavailable', { status: 503, code: 'RICCON-QUOTA-503' });
    expect(describeQuotaError(unavailable, 'tenant-load')).toContain('fail-closed');

    const invalid = new ApiError('invalid', { status: 400, code: 'RICCON-QUOTA-400' });
    expect(describeQuotaError(invalid, 'limit-save')).toContain('0 或正整数');

    expect(describeQuotaError(new Error('boom'), 'platform-load')).toBe(
      '配额操作未完成，请稍后重试。',
    );
  });
});

describe('配额重置相对时间（M05-AC-18）', () => {
  const now = new Date('2026-11-23T10:00:00+08:00');

  it('配额页同时显示绝对时间与“还有 X 小时/天”', () => {
    expect(formatQuotaResetRelative('2026-11-23T13:00:00+08:00', now)).toBe('还有 3 小时');
    expect(formatQuotaResetRelative('2026-11-25T11:00:00+08:00', now)).toBe('还有 2 天');
    expect(formatQuotaResetText('2026-11-23T13:00:00+08:00', now)).toBe(
      '2026-11-23 13:00:00 +08:00（还有 3 小时）',
    );
  });
});
