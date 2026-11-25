import { describe, expect, it } from 'vitest';
import { ApiError } from '@/services/api/errors';
import type { WorkflowApproval, WorkflowDataScopeSummary } from './approval-api';
import {
  APPROVAL_EMPTY_MESSAGE,
  APPROVAL_STATUS_LABELS,
  DECISION_COMMENT_MAX_LENGTH,
  REJECTED_RESULT_MESSAGE,
  REJECT_COMMENT_REQUIRED_MESSAGE,
  SELF_APPROVAL_MESSAGE,
  WORKFLOW_UNAVAILABLE_MESSAGE,
  describeAction,
  describeApprovalError,
  describeDataScope,
  describeDecisionSuccess,
  formatParamsPreview,
  formatShortId,
  formatWaitDuration,
  isWorkflowUnavailable,
} from './format';

/**
 * 审批展示规则单元测试。
 *
 * 覆盖 PRODUCT 固定文案、等待时长边界、数据范围摘要、脱敏参数格式化和错误码中文映射；
 * 页面状态由组件测试覆盖。
 */

/** 数据范围夹具。 */
function scope(overrides: Partial<WorkflowDataScopeSummary> = {}): WorkflowDataScopeSummary {
  return { runScope: 'TENANT', departmentId: null, documentReferenceCount: 2, ...overrides };
}

/** 审批动作夹具。 */
function action(
  overrides: Partial<Pick<WorkflowApproval, 'actionKind' | 'targetToolName'>> = {},
): Pick<WorkflowApproval, 'actionKind' | 'targetToolName'> {
  return { actionKind: 'MCP_TOOL', targetToolName: 'market_data', ...overrides };
}

describe('审批固定文案', () => {
  it('待办空态与自批文案与 PRODUCT §12.4 一致', () => {
    expect(APPROVAL_EMPTY_MESSAGE).toBe('暂无待审批项，新的审批请求会显示在这里。');
    expect(SELF_APPROVAL_MESSAGE).toBe('你不能审批自己发起的运行。');
    expect(REJECTED_RESULT_MESSAGE).toBe('审批已拒绝，本次运行终止，后续节点未执行。');
    expect(REJECT_COMMENT_REQUIRED_MESSAGE).toBe('拒绝时必须填写理由（1–500 字）。');
    expect(DECISION_COMMENT_MAX_LENGTH).toBe(500);
    expect(APPROVAL_STATUS_LABELS).toEqual({
      PENDING: '待审批',
      APPROVED: '已批准',
      REJECTED: '已拒绝',
      CANCELLED: '已取消',
    });
  });
});

describe('formatShortId', () => {
  it('空值返回占位符，长值按长度截断', () => {
    expect(formatShortId(null)).toBe('—');
    expect(formatShortId('abc')).toBe('abc');
    expect(formatShortId('01990000-0000-7000-8000-000000000601')).toBe('01990000…');
    expect(formatShortId('abcdefghijkl', 4)).toBe('abcd…');
  });
});

describe('formatWaitDuration', () => {
  const now = new Date('2026-11-25T10:00:00+08:00');

  it('不足一分钟与非法时间有明确占位', () => {
    expect(formatWaitDuration('2026-11-25T09:59:30+08:00', now)).toBe('不足 1 分钟');
    expect(formatWaitDuration('2026-11-25T10:30:00+08:00', now)).toBe('不足 1 分钟');
    expect(formatWaitDuration('not-a-date', now)).toBe('—');
  });

  it('按分钟、小时、天递进格式化', () => {
    expect(formatWaitDuration('2026-11-25T09:25:00+08:00', now)).toBe('35 分钟');
    expect(formatWaitDuration('2026-11-25T08:00:00+08:00', now)).toBe('2 小时');
    expect(formatWaitDuration('2026-11-25T06:40:00+08:00', now)).toBe('3 小时 20 分钟');
    expect(formatWaitDuration('2026-11-24T07:00:00+08:00', now)).toBe('1 天 3 小时');
    expect(formatWaitDuration('2026-11-23T10:00:00+08:00', now)).toBe('2 天');
  });
});

describe('describeDataScope', () => {
  it('租户范围只显示范围与引用数', () => {
    expect(describeDataScope(scope())).toBe('本租户 · 引用文档 2 篇');
  });

  it('部门范围带截断的部门标识；缺失时明确说明', () => {
    expect(
      describeDataScope(
        scope({
          runScope: 'DEPARTMENT',
          departmentId: 'department-0001',
          documentReferenceCount: 0,
        }),
      ),
    ).toBe('本部门 · 部门 departme… · 引用文档 0 篇');
    expect(describeDataScope(scope({ runScope: 'DEPARTMENT', departmentId: null }))).toBe(
      '本部门 · 部门未指定 · 引用文档 2 篇',
    );
  });

  it('仅自己范围不显示部门', () => {
    expect(describeDataScope(scope({ runScope: 'ONLY_SELF' }))).toBe('仅自己 · 引用文档 2 篇');
  });
});

describe('describeAction', () => {
  it('MCP 工具带目标工具名', () => {
    expect(describeAction(action())).toBe('调用 MCP 工具：market_data');
    expect(describeAction(action({ targetToolName: null }))).toBe('调用 MCP 工具');
  });

  it('模型与继续动作使用固定说明', () => {
    expect(describeAction(action({ actionKind: 'MODEL', targetToolName: null }))).toBe(
      '调用模型生成内容',
    );
    expect(describeAction(action({ actionKind: 'CONTINUE', targetToolName: null }))).toBe(
      '继续执行后续节点',
    );
  });
});

describe('formatParamsPreview', () => {
  it('空摘要与空对象有明确占位', () => {
    expect(formatParamsPreview(null)).toBe('—');
    expect(formatParamsPreview(undefined)).toBe('—');
    expect(formatParamsPreview({})).toBe('（无参数）');
  });

  it('按行展示脱敏字段并截断长值', () => {
    expect(
      formatParamsPreview({
        symbol: '600519',
        limit: 20,
        since: true,
        missing: null,
        filter: { market: 'CN' },
      }),
    ).toBe('symbol: 600519\nlimit: 20\nsince: true\nmissing: —\nfilter: {"market":"CN"}');

    const long = 'x'.repeat(260);
    expect(formatParamsPreview({ query: long })).toBe(`query: ${'x'.repeat(200)}…`);
  });

  it('无法序列化的值不抛异常', () => {
    const circular: Record<string, unknown> = {};
    circular.self = circular;
    expect(formatParamsPreview({ circular })).toBe('circular: [无法展示的值]');
    expect(formatParamsPreview({ fn: () => undefined })).toBe('fn: —');
  });
});

describe('describeApprovalError', () => {
  it('403/404/409 使用固定中文文案', () => {
    expect(
      describeApprovalError(new ApiError('forbidden', { status: 403, code: 'RICCON-AUTH-403' })),
    ).toContain(SELF_APPROVAL_MESSAGE);
    expect(
      describeApprovalError(new ApiError('not found', { status: 404, code: 'RICCON-TENANT-404' })),
    ).toBe('审批不存在，或你没有该审批的查看权限。');
    expect(
      describeApprovalError(new ApiError('conflict', { status: 409, code: 'RICCON-WF-409' })),
    ).toContain('该审批已被处理');
  });

  it('503 区分编排关闭与通用服务不可用', () => {
    const disabled = new ApiError('disabled', { status: 503, code: 'RICCON-WF-503' });
    expect(isWorkflowUnavailable(disabled)).toBe(true);
    expect(describeApprovalError(disabled)).toBe(WORKFLOW_UNAVAILABLE_MESSAGE);
    expect(
      describeApprovalError(new ApiError('down', { status: 503, code: 'RICCON-INTERNAL-503' })),
    ).toBe('服务暂时不可用，请稍后重试。');
  });

  it('400 提示理由范围，其他错误走统一描述，非 ApiError 用兜底', () => {
    expect(
      describeApprovalError(new ApiError('bad request', { status: 400, code: 'RICCON-WF-400' })),
    ).toContain(REJECT_COMMENT_REQUIRED_MESSAGE);
    expect(
      describeApprovalError(new ApiError('boom', { status: 500, code: 'RICCON-INTERNAL-500' })),
    ).toBe('服务暂时不可用，请稍后重试。');
    expect(describeApprovalError(new Error('unknown'), '兜底文案')).toBe('兜底文案');
    expect(isWorkflowUnavailable(new Error('x'))).toBe(false);
  });
});

describe('describeDecisionSuccess', () => {
  it('批准与拒绝使用不同结果文案', () => {
    expect(describeDecisionSuccess('APPROVE')).toContain('审批已批准');
    expect(describeDecisionSuccess('REJECT')).toBe(REJECTED_RESULT_MESSAGE);
  });
});
