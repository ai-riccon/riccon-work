import { describe, expect, it } from 'vitest';
import { ApiError } from '@/services/api/errors';
import {
  COST_DISCLAIMER,
  addDays,
  costBucketDetail,
  costBucketTitle,
  costGroupByLabel,
  defaultCostRange,
  describeCostError,
  formatCostAmount,
  formatCostCount,
  formatCostExactAmount,
  formatCostTime,
  groupThousands,
  isIsoDate,
  pricingStatusColor,
  pricingStatusLabel,
  roundDecimalString,
  shanghaiToday,
  validateCostRange,
} from './format';

describe('成本金额精度', () => {
  it('按 HALF_UP 保持或舍入到指定小数位', () => {
    expect(roundDecimalString('0.123456', 2)).toBe('0.12');
    expect(roundDecimalString('0.125000', 2)).toBe('0.13');
    expect(roundDecimalString('0.004999', 2)).toBe('0.00');
    expect(roundDecimalString('999.995000', 2)).toBe('1000.00');
    expect(roundDecimalString('1.9995', 3)).toBe('2.000');
    expect(roundDecimalString('12', 0)).toBe('12');
    expect(roundDecimalString('12.5', 0)).toBe('13');
    expect(roundDecimalString('0', 6)).toBe('0.000000');
  });

  it('非法金额输入退化为 0，不抛异常也不产生 NaN', () => {
    expect(roundDecimalString('abc', 2)).toBe('0.00');
    expect(roundDecimalString('-1.25', 2)).toBe('0.00');
    expect(roundDecimalString('1e3', 2)).toBe('0.00');
  });

  it('整数部分使用千分位', () => {
    expect(groupThousands('1234567')).toBe('1,234,567');
    expect(groupThousands('0')).toBe('0');
    expect(groupThousands('1000')).toBe('1,000');
  });

  it('按币种最小单位展示金额，空值显示占位符', () => {
    expect(formatCostAmount('12.345600', 'CNY', 2)).toBe('CNY 12.35');
    expect(formatCostAmount('0.000000', 'CNY', 2)).toBe('CNY 0.00');
    expect(formatCostAmount('1234.5', 'JPY', 0)).toBe('JPY 1,235');
    expect(formatCostAmount('12.345678', 'USD', 4)).toBe('USD 12.3457');
    expect(formatCostAmount(null, 'CNY', 2)).toBe('—');
    expect(formatCostAmount('12', '', 2)).toBe('12.00');
  });

  it('精确值固定 6 位小数，用于明细与提示', () => {
    expect(formatCostExactAmount('0.123456')).toBe('0.123456');
    expect(formatCostExactAmount('3')).toBe('3.000000');
    expect(formatCostExactAmount(null)).toBe('—');
  });

  it('调用量使用千分位', () => {
    expect(formatCostCount(12)).toBe('12');
    expect(formatCostCount(1024)).toBe('1,024');
  });
});

describe('成本维度与状态', () => {
  it('维度中文名覆盖平台与租户视图', () => {
    expect(costGroupByLabel('TENANT')).toBe('按租户');
    expect(costGroupByLabel('USER')).toBe('按用户');
    expect(costGroupByLabel('MODEL')).toBe('按模型');
    expect(costGroupByLabel('DAY')).toBe('按日');
    expect(costGroupByLabel('UNKNOWN')).toBe('UNKNOWN');
  });

  it('分桶标题按维度选择并避免展示空值', () => {
    const userBucket = {
      key: 'user-1',
      userId: 'user-1',
      userDisplayName: '王敏',
      amount: '0.000000',
      pricedCalls: 0,
      unpricedCalls: 0,
      usageUnknownCalls: 0,
    };
    expect(costBucketTitle(userBucket, 'USER')).toBe('王敏');
    expect(costBucketDetail(userBucket, 'USER')).toBe('user-1');
    expect(costBucketTitle({ ...userBucket, userDisplayName: null }, 'USER')).toBe('user-1');
    expect(costBucketTitle({ ...userBucket, key: 'tenant-a', tenantCode: 'acme' }, 'TENANT')).toBe(
      'acme',
    );
    expect(
      costBucketTitle(
        { ...userBucket, key: 'm-1', modelId: 'm-1', modelDisplayName: 'GPT' },
        'MODEL',
      ),
    ).toBe('GPT');
    expect(costBucketTitle({ ...userBucket, key: '2026-11-21', day: '2026-11-21' }, 'DAY')).toBe(
      '2026-11-21',
    );
  });

  it('定价状态使用固定中文名与可读颜色', () => {
    expect(pricingStatusLabel('PRICED')).toBe('已定价');
    expect(pricingStatusLabel('UNPRICED')).toBe('未定价');
    expect(pricingStatusLabel('USAGE_UNKNOWN')).toBe('用量未知');
    expect(pricingStatusColor('PRICED')).toBe('green');
    expect(pricingStatusColor('UNPRICED')).toBe('gold');
    expect(pricingStatusColor('USAGE_UNKNOWN')).toBe('default');
  });
});

describe('成本日期与时间', () => {
  it('按北京时间计算今天与最近 N 天', () => {
    // UTC 16:30 已是北京时间次日；默认范围必须跟随 Asia/Shanghai。
    const now = new Date('2026-11-20T16:30:00Z');
    expect(shanghaiToday(now)).toBe('2026-11-21');
    expect(defaultCostRange(30, now)).toEqual({ from: '2026-10-23', to: '2026-11-21' });
    expect(defaultCostRange(7, now)).toEqual({ from: '2026-11-15', to: '2026-11-21' });
    expect(addDays('2026-11-30', 1)).toBe('2026-12-01');
    expect(addDays('2026-01-01', -1)).toBe('2025-12-31');
  });

  it('校验日期格式、起止顺序与 180 天上限', () => {
    expect(validateCostRange('2026-11-01', '2026-11-21')).toBeNull();
    expect(validateCostRange('2026-05-26', '2026-11-21')).toBeNull(); // 恰好 180 天
    expect(validateCostRange('2026-11-21', '2026-11-01')).toContain('不能晚于');
    expect(validateCostRange('2026-05-25', '2026-11-21')).toContain('180');
    expect(validateCostRange('2026-02-30', '2026-03-01')).toContain('有效的开始与结束日期');
    expect(isIsoDate('2026-02-28')).toBe(true);
    expect(isIsoDate('2026-13-01')).toBe(false);
    expect(isIsoDate('2026-2-1')).toBe(false);
  });

  it('调用时间按北京时间展示并保留 +08:00', () => {
    expect(formatCostTime('2026-11-21T10:30:00+08:00')).toBe('2026-11-21 10:30:00 +08:00');
    expect(formatCostTime('2026-11-21T02:30:00Z')).toBe('2026-11-21 10:30:00 +08:00');
    expect(formatCostTime(undefined)).toBe('—');
  });

  it('固定免责文案与契约一致', () => {
    expect(COST_DISCLAIMER).toBe('成本为按调用时价格快照估算，不是供应商账单。');
  });
});

describe('成本错误文案', () => {
  it('区分查看报告与展开明细的 403，并给出 400/503 的下一步', () => {
    const forbidden = new ApiError('forbidden', { status: 403, code: 'RICCON-AUTH-403' });
    expect(describeCostError(forbidden, 'platform-report')).toContain('员工无入口');
    expect(describeCostError(forbidden, 'invocations')).toContain('调用明细');

    const invalid = new ApiError('invalid', { status: 400, code: 'RICCON-COST-400' });
    expect(describeCostError(invalid, 'tenant-report')).toContain('180');

    const unavailable = new ApiError('unavailable', { status: 503, code: 'RICCON-COST-503' });
    expect(describeCostError(unavailable, 'tenant-report')).toContain('未返回部分结果');

    const notFound = new ApiError('missing', { status: 404, code: 'RICCON-TENANT-404' });
    expect(describeCostError(notFound, 'invocations')).toContain('不属于本租户');

    expect(describeCostError('boom', 'tenant-report')).toBe('成本查询未完成，请稍后重试。');
  });
});
