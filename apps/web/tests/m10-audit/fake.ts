import { vi } from 'vitest';
import type { SessionInfo } from '@/services/api/types';
import { DEMO_SESSION } from '@/test/fake-api';
import type { AuditApi } from '@/services/audit/api';
import type {
  AuditEvent,
  AuditExportRequest,
  AuditExportRequestPage,
  AuditEventPage,
  AuditPolicy,
} from '@/services/audit/types';

/**
 * M10 审计/导出页面测试夹具。
 *
 * 放在 `apps/web/tests/m10-audit` 下，不进入 `src/**` 覆盖率分母；
 * 测试文件与页面共置，保证 Vitest 默认 include 能发现用例。
 */

/** 租户管理员会话：可查询、申请、审批与下载本人导出。 */
export const TENANT_ADMIN_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  roles: ['TENANT_ADMIN'],
  permissions: [...DEMO_SESSION.permissions, 'audit.read', 'audit.export.approve'],
};

/** 平台管理员会话：可查询平台审计，并审批单管理员租户导出。 */
export const PLATFORM_ADMIN_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  user: { ...DEMO_SESSION.user, id: 'platform-admin', displayName: '赵蕾' },
  tenant: { ...DEMO_SESSION.tenant, id: 'platform', name: '平台' },
  roles: ['PLATFORM_ADMIN'],
  permissions: ['audit.read', 'platform.audit.export.approve'],
};

/** 普通员工会话：没有审计权限。 */
export const MEMBER_SESSION: SessionInfo = DEMO_SESSION;

/** 构造分页。 */
export function pageOf<T>(
  items: T[],
  page = 1,
  size = 20,
): {
  page: number;
  size: number;
  total: number;
  totalPages: number;
  items: T[];
} {
  return {
    page,
    size,
    total: items.length,
    totalPages: items.length > 0 ? 1 : 0,
    items,
  };
}

/**
 * 构造审计策略。
 *
 * @param overrides 局部覆盖
 * @returns AuditPolicy
 */
export function makeAuditPolicy(overrides: Partial<AuditPolicy> = {}): AuditPolicy {
  return {
    retentionDays: 365,
    maxRangeDays: 180,
    pageSizeMax: 100,
    maxPage: 200,
    actions: ['file.uploaded', 'audit.queried', 'audit.export.requested'],
    exportLimits: { ttlHours: 24, rowLimit: 10000 },
    ...overrides,
  };
}

/**
 * 构造审计事件。
 *
 * @param overrides 局部覆盖
 * @returns AuditEvent
 */
export function makeAuditEvent(overrides: Partial<AuditEvent> = {}): AuditEvent {
  return {
    eventId: 'event-1',
    occurredAt: '2026-11-19T13:00:00+08:00',
    tenantId: 'tenant-a',
    actorType: 'USER',
    actorId: 'user-1',
    actorDisplayName: '王敏',
    action: 'file.uploaded',
    targetType: 'FILE',
    targetId: 'file-1',
    outcome: 'SUCCESS',
    traceId: 'trace-1',
    details: { fileId: 'file-1', contentLength: 2048 },
    ...overrides,
  };
}

/**
 * 构造导出请求。
 *
 * @param overrides 局部覆盖
 * @returns AuditExportRequest
 */
export function makeExport(overrides: Partial<AuditExportRequest> = {}): AuditExportRequest {
  return {
    id: 'export-1',
    status: 'PENDING_APPROVAL',
    requestedBy: 'user-1',
    requestedByDisplayName: '王敏',
    filter: {
      occurredFrom: '2026-11-01T00:00:00+08:00',
      occurredTo: '2026-11-02T00:00:00+08:00',
      action: 'file.uploaded',
      targetType: 'FILE',
      outcome: 'SUCCESS',
    },
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

/**
 * 构造审计 API 替身。
 *
 * @param overrides 局部覆盖
 * @returns AuditApi
 */
export function createAuditApi(overrides: Partial<AuditApi> = {}): AuditApi {
  const events: AuditEventPage = pageOf([makeAuditEvent()]);
  const exportsPage: AuditExportRequestPage = pageOf([makeExport()]);
  return {
    getPolicy: vi.fn(async () => makeAuditPolicy()),
    listEvents: vi.fn(async () => events),
    listExports: vi.fn(async () => exportsPage),
    createExport: vi.fn(async () => makeExport()),
    getExport: vi.fn(async () => makeExport()),
    decideExport: vi.fn(async () => makeExport({ status: 'APPROVED' })),
    downloadExport: vi.fn(async () => ({
      blob: new Blob(['eventId,action\n1,file.uploaded\n'], { type: 'text/csv' }),
      filename: 'audit-export-export-1.csv',
    })),
    listPlatformExports: vi.fn(async () => exportsPage),
    decidePlatformExport: vi.fn(async () => makeExport({ status: 'APPROVED' })),
    ...overrides,
  };
}
