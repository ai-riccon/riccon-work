import { describe, expect, it } from 'vitest';
import { ApiError } from '@/services/api/errors';
import type { AuditExportRequest, AuditPolicy } from './types';
import {
  auditPolicyNotice,
  canDecideExport,
  describeAuditError,
  describeAuditFilter,
  exportDownloadState,
  formatAuditTarget,
  formatAuditTime,
  formatRowCount,
  isExportExpired,
  toAuditFilter,
} from './format';

/** 构造导出请求。 */
function makeExport(overrides: Partial<AuditExportRequest> = {}): AuditExportRequest {
  return {
    id: 'export-1',
    status: 'PENDING_APPROVAL',
    requestedBy: 'user-requester',
    requestedByDisplayName: '王敏',
    filter: { action: 'file.uploaded' },
    estimatedRowCount: 120,
    approvedBy: null,
    approvedByDisplayName: null,
    decidedAt: null,
    decisionComment: null,
    expiresAt: null,
    createdAt: '2026-11-19T10:00:00+08:00',
    ...overrides,
  };
}

/** 构造审计异常。 */
function apiError(status: number, code: string): ApiError {
  return new ApiError('internal english detail', {
    status,
    code,
    problem: { type: 'about:blank', title: '错误', status, code, traceId: 'trace-1' },
  });
}

/** 审计策略。 */
const POLICY: AuditPolicy = {
  retentionDays: 365,
  maxRangeDays: 180,
  pageSizeMax: 100,
  maxPage: 200,
  actions: ['file.uploaded', 'audit.queried'],
  exportLimits: { ttlHours: 24, rowLimit: 10000 },
};

describe('审计展示与过滤', () => {
  it('时间统一按北京时间显示并保留 +08:00 偏移', () => {
    expect(formatAuditTime('2026-11-19T05:00:00Z')).toBe('2026-11-19 13:00:00 +08:00');
    expect(formatAuditTime(null)).toBe('—');
  });

  it('表单时间转换为带偏移的 ISO 8601，空字段被省略', () => {
    const filter = toAuditFilter({
      occurredFrom: { toDate: () => new Date('2026-11-01T00:00:00Z') },
      occurredTo: { toDate: () => new Date('2026-11-02T00:00:00Z') },
      action: ' file.uploaded ',
      actorId: '',
      targetType: 'FILE',
      targetId: 'file-1',
      outcome: 'SUCCESS',
    });
    expect(filter).toEqual({
      occurredFrom: '2026-11-01T08:00:00+08:00',
      occurredTo: '2026-11-02T08:00:00+08:00',
      action: 'file.uploaded',
      targetType: 'FILE',
      targetId: 'file-1',
      outcome: 'SUCCESS',
    });
  });

  it('空过滤摘要明确写出服务端默认最近 24 小时', () => {
    expect(describeAuditFilter({})).toBe('时间 最近 24 小时（服务端默认）');
  });

  it('过滤摘要包含时间、动作、操作者、对象与结果', () => {
    const summary = describeAuditFilter({
      occurredFrom: '2026-11-01T00:00:00+08:00',
      occurredTo: '2026-11-02T00:00:00+08:00',
      action: 'file.uploaded',
      actorId: 'user-1',
      targetType: 'FILE',
      targetId: 'file-1',
      outcome: 'DENIED',
    });
    expect(summary).toContain('时间 2026-11-01 00:00:00 +08:00 至 2026-11-02 00:00:00 +08:00');
    expect(summary).toContain('动作 file.uploaded');
    expect(summary).toContain('操作者 user-1');
    expect(summary).toContain('对象 文件 / file-1');
    expect(summary).toContain('结果 拒绝');
  });

  it('保留策略文案使用服务端天数，不写死 180', () => {
    expect(auditPolicyNotice(POLICY)).toBe('审计至少保留 365 天，可查最近 180 天；无删除入口。');
  });

  it('行数、操作者与对象展示可读', () => {
    expect(formatRowCount(12345)).toBe('12,345');
    expect(formatRowCount(null)).toBe('—');
    expect(formatAuditTarget('FILE', 'file-1')).toBe('文件 / file-1');
    expect(formatAuditTarget(null, null)).toBe('—');
  });
});

describe('导出状态机与下载入口', () => {
  it('待审批不可下载', () => {
    expect(exportDownloadState(makeExport(), 'user-requester').canDownload).toBe(false);
    expect(exportDownloadState(makeExport(), 'user-requester').reason).toContain('待审批');
  });

  it('拒绝显示审批意见且不可下载', () => {
    const state = exportDownloadState(
      makeExport({ status: 'REJECTED', decisionComment: '请缩小范围' }),
      'user-requester',
    );
    expect(state.canDownload).toBe(false);
    expect(state.reason).toContain('请缩小范围');
  });

  it('仅申请人本人可下载已批准未过期的导出', () => {
    const approved = makeExport({
      status: 'APPROVED',
      expiresAt: '2099-01-01T00:00:00+08:00',
    });
    expect(exportDownloadState(approved, 'user-requester').canDownload).toBe(true);
    expect(exportDownloadState(approved, 'user-other').reason).toContain('仅申请人');
  });

  it('过期状态与过期时间都阻止下载', () => {
    const expired = makeExport({ status: 'EXPIRED' });
    expect(isExportExpired(expired)).toBe(true);
    expect(exportDownloadState(expired, 'user-requester').reason).toContain('已过期');

    const past = makeExport({
      status: 'APPROVED',
      expiresAt: '2020-01-01T00:00:00+08:00',
    });
    expect(isExportExpired(past)).toBe(true);
    expect(exportDownloadState(past, 'user-requester').canDownload).toBe(false);
  });

  it('申请人不能审批自己的请求，其他管理员可审批', () => {
    const pending = makeExport();
    expect(canDecideExport(pending, 'user-requester', ['audit.export.approve']).allowed).toBe(
      false,
    );
    expect(canDecideExport(pending, 'user-other', ['audit.export.approve']).allowed).toBe(true);
    expect(canDecideExport(pending, 'user-other', []).allowed).toBe(false);
    expect(
      canDecideExport(makeExport({ status: 'APPROVED' }), 'user-other', ['audit.export.approve'])
        .allowed,
    ).toBe(false);
  });
});

describe('审计错误文案', () => {
  it('400 在申请与查询场景给出不同下一步', () => {
    const error = apiError(400, 'RICCON-AUDIT-400');
    expect(describeAuditError(error, 'create')).toContain('预计行数超过导出上限');
    expect(describeAuditError(error, 'query')).toContain('最大 180 天');
  });

  it('403 区分审批、下载与平台审批', () => {
    const error = apiError(403, 'RICCON-AUDIT-403');
    expect(describeAuditError(error, 'decide')).toContain('申请人不能审批');
    expect(describeAuditError(error, 'download')).toContain('只有申请人本人');
    expect(describeAuditError(error, 'platform-list')).toContain('平台租户导出');
  });

  it('409 在下载与平台审批场景给出不同说明', () => {
    const error = apiError(409, 'RICCON-AUDIT-409');
    expect(describeAuditError(error, 'download')).toContain('实际行数超过上限');
    expect(describeAuditError(error, 'platform-list')).toContain('多个启用的租户管理员');
  });

  it('410 提示重新申请，500 提示联系管理员', () => {
    expect(describeAuditError(apiError(410, 'RICCON-AUDIT-410'), 'download')).toContain('重新提交');
    expect(describeAuditError(apiError(500, 'RICCON-AUDIT-500'), 'query')).toContain('联系管理员');
  });

  it('未知异常使用兜底文案', () => {
    expect(describeAuditError(new Error('boom'), 'query')).toContain('稍后重试');
  });
});
