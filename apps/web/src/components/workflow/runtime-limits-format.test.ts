import { describe, expect, it } from 'vitest';
import { ApiError } from '@/services/api/errors';
import type { ProblemDetail } from '@/services/api/types';
import {
  describeRuntimeLimitsDisabledReason,
  describeWorkflowRunLimitExceeded,
  formatLimitBytes,
  formatLimitSeconds,
  parseWorkflowRunLimitExceeded,
  retentionPolicyLabel,
} from './runtime-limits-format';

/**
 * 运行限制展示与 429 文案纯逻辑测试。
 *
 * 覆盖秒/字节格式化、保留策略标签、关闭原因翻译，以及后端 429 `detail` 的
 * scope/limit/current 解析与“等待/调整流程/联系管理员”下一步文案。
 */

/** 构造 ProblemDetail。 */
function problem(detail: string, code = 'RICCON-WF-429'): ProblemDetail {
  return {
    type: 'about:blank',
    title: 'Too Many Requests',
    status: 429,
    detail,
    code,
    traceId: 'trace-1',
  };
}

/**
 * 构造 429 ApiError。
 *
 * @param detail 后端 detail
 * @param code 稳定错误码
 * @returns ApiError
 */
function limitError(detail: string, code = 'RICCON-WF-429'): ApiError {
  return new ApiError(detail, { status: 429, code, problem: problem(detail, code) });
}

describe('运行限制格式化', () => {
  it('秒数保留原始值并附带分钟/小时', () => {
    expect(formatLimitSeconds(45)).toBe('45 秒');
    expect(formatLimitSeconds(120)).toBe('120 秒（2 分钟）');
    expect(formatLimitSeconds(300)).toBe('300 秒（5 分钟）');
    expect(formatLimitSeconds(1800)).toBe('1800 秒（30 分钟）');
    expect(formatLimitSeconds(3665)).toBe('3665 秒（1 小时 1 分 5 秒）');
    expect(formatLimitSeconds(0)).toBe('—');
    expect(formatLimitSeconds(Number.NaN)).toBe('—');
  });

  it('字节按 MiB 展示并保留精确字节数', () => {
    expect(formatLimitBytes(5 * 1024 * 1024)).toBe('5 MiB（5,242,880 字节）');
    expect(formatLimitBytes(4.5 * 1024 * 1024)).toBe('4.5 MiB（4,718,592 字节）');
    expect(formatLimitBytes(0)).toBe('—');
  });

  it('保留策略显示中文来源', () => {
    expect(retentionPolicyLabel('DEPLOYMENT_DEFAULT')).toBe('部署默认');
    expect(retentionPolicyLabel('TENANT_POLICY')).toBe('租户策略覆盖');
  });

  it('关闭原因将后端默认英文基线翻译为中文，自定义原因原样保留', () => {
    expect(
      describeRuntimeLimitsDisabledReason(
        'workflow orchestration is disabled by deployment configuration',
      ),
    ).toBe('部署配置已关闭编排功能。');
    expect(describeRuntimeLimitsDisabledReason('只读演练')).toBe('只读演练');
    expect(describeRuntimeLimitsDisabledReason('  ')).toContain('未提供关闭原因');
    expect(describeRuntimeLimitsDisabledReason(null)).toContain('未提供关闭原因');
  });
});

describe('运行并发 429 文案', () => {
  it('解析租户并发 detail 中的 scope/limit/current', () => {
    const parsed = parseWorkflowRunLimitExceeded(
      limitError(
        'tenant active run limit reached (limit=10, current=12); wait for an active run to finish before retrying',
      ),
    );
    expect(parsed).toEqual({ scope: 'tenant', limit: 10, current: 12 });
  });

  it('解析单流程并发 detail，并可从 ApiError.message 回退', () => {
    const error = new ApiError(
      'workflow active run limit reached (limit=5, current=6); wait for an active run to finish before retrying',
      { status: 429, code: 'RICCON-WF-429' },
    );
    expect(parseWorkflowRunLimitExceeded(error)).toEqual({
      scope: 'workflow',
      limit: 5,
      current: 6,
    });
  });

  it('非 WF-429 与其他状态返回 null，不影响既有配额/限流文案', () => {
    expect(
      parseWorkflowRunLimitExceeded(
        limitError('tenant active run limit reached (limit=1, current=2)', 'RICCON-QUOTA-429'),
      ),
    ).toBeNull();
    expect(
      parseWorkflowRunLimitExceeded(new ApiError('boom', { status: 503, code: 'RICCON-WF-503' })),
    ).toBeNull();
  });

  it('租户超限显示具体限制、当前值与等待/联系管理员下一步', () => {
    const text = describeWorkflowRunLimitExceeded(
      limitError(
        'tenant active run limit reached (limit=10, current=12); wait for an active run to finish before retrying',
      ),
    );
    expect(text).toContain('租户并发运行已达上限');
    expect(text).toContain('当前 12 个');
    expect(text).toContain('上限 10 个');
    expect(text).toContain('等待已有运行结束后重试');
    expect(text).toContain('联系租户管理员');
    expect(text).not.toContain('请求过于频繁');
  });

  it('单流程超限额外提示可调整流程', () => {
    const text = describeWorkflowRunLimitExceeded(
      limitError('workflow active run limit reached (limit=5, current=5); wait before retrying'),
    );
    expect(text).toContain('该流程并发运行已达上限');
    expect(text).toContain('当前 5 个');
    expect(text).toContain('上限 5 个');
    expect(text).toContain('调整流程');
    expect(text).toContain('联系租户管理员');
  });

  it('detail 无法解析时仍给出等待、调整流程与联系管理员兜底', () => {
    const text = describeWorkflowRunLimitExceeded(limitError('unexpected format'));
    expect(text).not.toBeNull();
    expect(text).toContain('等待已有运行结束后重试');
    expect(text).toContain('调整流程');
    expect(text).toContain('联系租户管理员');
    expect(text).not.toContain('请求过于频繁');
  });
});
