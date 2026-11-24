import { vi } from 'vitest';
import type { Department, Page } from '@/services/api/types';
import type {
  Connector,
  ConnectorApi,
  ConnectorCreateRequest,
  ConnectorItem,
  ConnectorItemPage,
  ConnectorPage,
  ConnectorTestResult,
  ConnectorUpdateRequest,
} from '@/services/connector';

/**
 * 测试用连接器。
 *
 * 默认是一台启用中的 S3 连接器，最近同步成功且 list 完整；测试按需覆盖状态、
 * 同步结果与凭据掩码。
 *
 * @param overrides 局部覆盖
 * @returns Connector
 */
export function makeConnector(overrides: Partial<Connector> = {}): Connector {
  return {
    id: 'connector-1',
    name: '销售合同归档',
    type: 'S3',
    status: 'ACTIVE',
    endpointId: 'internal-s3',
    config: {
      bucket: 'sales-archive',
      prefix: 'contracts/2026/',
      region: 'cn-north-1',
      includeExtensions: ['pdf', 'docx'],
      excludeGlobs: ['**/tmp/**'],
    },
    defaultVisibility: 'TENANT',
    defaultDepartmentId: null,
    credentialMaskedHint: 'AKIA…7F2',
    scheduleEnabled: true,
    nextSyncAt: '2026-11-24T12:00:00+08:00',
    lastSyncStartedAt: '2026-11-24T09:00:00+08:00',
    lastSyncFinishedAt: '2026-11-24T09:01:00+08:00',
    lastSyncStatus: 'SUCCESS',
    lastSyncComplete: true,
    lastSuccessAt: '2026-11-24T09:01:00+08:00',
    lastErrorCode: null,
    lastSyncSummary: {
      scanned: 12,
      created: 3,
      updated: 1,
      skipped: 2,
      failed: 0,
      deleted: 0,
      complete: true,
    },
    createdBy: 'user-tenant-admin',
    createdAt: '2026-11-20T10:00:00+08:00',
    updatedAt: '2026-11-24T09:01:00+08:00',
    ...overrides,
  };
}

/**
 * 构造测试用同步条目。
 *
 * @param overrides 局部覆盖
 * @returns ConnectorItem
 */
export function makeConnectorItem(overrides: Partial<ConnectorItem> = {}): ConnectorItem {
  return {
    id: 'item-1',
    externalId: 'contracts/2026/a.pdf',
    externalVersion: 'etag-1',
    status: 'SYNCED',
    documentId: 'doc-1',
    generation: 1,
    errorCode: null,
    lastSeenAt: '2026-11-24T09:00:00+08:00',
    createdAt: '2026-11-20T10:00:00+08:00',
    updatedAt: '2026-11-24T09:01:00+08:00',
    deletedAt: null,
    ...overrides,
  };
}

/**
 * 构造测试用部门。
 *
 * @param overrides 局部覆盖
 * @returns Department
 */
export function makeDepartment(overrides: Partial<Department> = {}): Department {
  return {
    id: 'dept-sales',
    name: '销售部',
    status: 'ACTIVE',
    managerUserId: null,
    managerDisplayName: null,
    memberCount: 12,
    createdAt: '2026-11-20T10:00:00+08:00',
    updatedAt: '2026-11-20T10:00:00+08:00',
    ...overrides,
  };
}

/**
 * 构造连接器分页。
 *
 * @param items 列表
 * @param overrides 分页覆盖
 * @returns ConnectorPage
 */
export function connectorPage(
  items: Connector[],
  overrides: Partial<Omit<ConnectorPage, 'items'>> = {},
): ConnectorPage {
  return {
    page: 1,
    size: 10,
    total: items.length,
    totalPages: items.length > 0 ? 1 : 0,
    items,
    ...overrides,
  };
}

/**
 * 构造条目分页。
 *
 * @param items 列表
 * @param overrides 分页覆盖
 * @returns ConnectorItemPage
 */
export function connectorItemPage(
  items: ConnectorItem[],
  overrides: Partial<Omit<ConnectorItemPage, 'items'>> = {},
): ConnectorItemPage {
  return {
    page: 1,
    size: 10,
    total: items.length,
    totalPages: items.length > 0 ? 1 : 0,
    items,
    ...overrides,
  };
}

/**
 * 构造连接测试结果。
 *
 * @param overrides 局部覆盖
 * @returns ConnectorTestResult
 */
export function makeTestResult(overrides: Partial<ConnectorTestResult> = {}): ConnectorTestResult {
  return {
    success: true,
    category: null,
    message: '连接测试成功；bucket 可访问。',
    testedAt: '2026-11-24T10:00:00+08:00',
    ...overrides,
  };
}

/**
 * 构造连接器 API 替身。
 *
 * @param overrides 局部覆盖
 * @returns ConnectorApi
 */
export function createFakeConnectorApi(overrides: Partial<ConnectorApi> = {}): ConnectorApi {
  const connector = makeConnector();
  return {
    listConnectors: vi.fn(async () => connectorPage([connector])),
    createConnector: vi.fn(async (input: ConnectorCreateRequest) =>
      makeConnector({
        id: 'connector-new',
        name: input.name,
        status: 'PAUSED',
        endpointId: input.endpointId,
        config: input.config,
        defaultVisibility: input.defaultVisibility,
        defaultDepartmentId: input.defaultDepartmentId ?? null,
        credentialMaskedHint: input.credential ? 'AKIA…NEW' : null,
        scheduleEnabled: input.scheduleEnabled ?? false,
        nextSyncAt: null,
        lastSyncStartedAt: null,
        lastSyncFinishedAt: null,
        lastSyncStatus: null,
        lastSyncComplete: false,
        lastSuccessAt: null,
        lastSyncSummary: null,
      }),
    ),
    updateConnector: vi.fn(async (connectorId: string, input: ConnectorUpdateRequest) =>
      makeConnector({
        id: connectorId,
        ...(input.name === undefined ? {} : { name: input.name }),
        ...(input.endpointId === undefined ? {} : { endpointId: input.endpointId }),
        ...(input.config === undefined ? {} : { config: input.config }),
        ...(input.defaultVisibility === undefined
          ? {}
          : { defaultVisibility: input.defaultVisibility }),
        ...(input.defaultDepartmentId === undefined
          ? {}
          : { defaultDepartmentId: input.defaultDepartmentId }),
        ...(input.scheduleEnabled === undefined ? {} : { scheduleEnabled: input.scheduleEnabled }),
        ...(input.status === undefined ? {} : { status: input.status }),
      }),
    ),
    deleteConnector: vi.fn(async () => undefined),
    putConnectorCredential: vi.fn(async (connectorId) =>
      makeConnector({ id: connectorId, credentialMaskedHint: 'AKIA…ROT' }),
    ),
    testConnector: vi.fn(async () => makeTestResult()),
    syncConnector: vi.fn(async (connectorId) => ({
      syncId: 'sync-1',
      connectorId,
      status: 'PENDING' as const,
      acceptedAt: '2026-11-24T10:05:00+08:00',
    })),
    listConnectorItems: vi.fn(async () => connectorItemPage([makeConnectorItem()])),
    ...overrides,
  };
}

/**
 * 构造部门目录替身。
 *
 * @param departments 部门列表
 * @returns 可注入页面的部门目录
 */
export function createFakeDepartmentDirectory(departments: Department[] = [makeDepartment()]) {
  return {
    listDepartments: vi.fn(async (): Promise<Page<Department>> => ({
      page: 1,
      size: 100,
      total: departments.length,
      totalPages: departments.length > 0 ? 1 : 0,
      items: departments,
    })),
  };
}
