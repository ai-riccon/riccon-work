import { describe, expect, it } from 'vitest';
import { ApiError } from '@/services/api/errors';
import {
  connectorItemStatusColor,
  connectorItemStatusLabel,
  connectorStatusColor,
  connectorStatusLabel,
  connectorSyncStatusLabel,
  connectorTestCategoryLabel,
  connectorVisibilityLabel,
  describeConnectorError,
  describeConnectorErrorCode,
  formatConnectorExternalId,
  formatConnectorTime,
  formatCredentialHint,
  isConnectorSyncInProgress,
} from './format';
import { makeConnector } from '@/test/fake-connectors';

/**
 * 构造 ApiError。
 *
 * @param status HTTP 状态
 * @param code 稳定错误码
 * @param detail 服务端 detail
 * @returns ApiError
 */
function apiError(status: number, code: string, detail?: string): ApiError {
  return new ApiError(detail ?? 'request failed', {
    status,
    code,
    problem: {
      type: 'about:blank',
      title: '请求失败',
      status,
      code,
      traceId: 'trace-1',
      ...(detail ? { detail } : {}),
    },
  });
}

describe('连接器展示格式', () => {
  it('状态、可见范围、同步与条目文案完整覆盖契约枚举', () => {
    expect(connectorStatusLabel('ACTIVE')).toBe('启用');
    expect(connectorStatusLabel('PAUSED')).toBe('暂停');
    expect(connectorStatusLabel('ERROR')).toBe('同步失败');
    expect(connectorStatusColor('ACTIVE')).toBe('green');
    expect(connectorStatusColor('ERROR')).toBe('red');
    expect(connectorVisibilityLabel('TENANT')).toBe('全租户');
    expect(connectorVisibilityLabel('DEPT')).toBe('指定部门');
    expect(connectorSyncStatusLabel('SUCCESS')).toBe('成功');
    expect(connectorSyncStatusLabel('PARTIAL')).toBe('部分成功');
    expect(connectorSyncStatusLabel('ERROR')).toBe('失败');
    expect(connectorSyncStatusLabel(null)).toBe('尚未同步');
    expect(connectorItemStatusLabel('PENDING')).toBe('待同步');
    expect(connectorItemStatusLabel('SYNCED')).toBe('已同步');
    expect(connectorItemStatusLabel('SKIPPED')).toBe('已跳过');
    expect(connectorItemStatusLabel('FAILED')).toBe('失败');
    expect(connectorItemStatusLabel('DELETED')).toBe('已删除');
    expect(connectorItemStatusColor('FAILED')).toBe('red');
    expect(connectorTestCategoryLabel('AUTH')).toBe('凭据被拒绝');
    expect(connectorTestCategoryLabel(null)).toBe('');
  });

  it('失败码包含中文原因，未知码不猜测', () => {
    expect(describeConnectorErrorCode('KB_CONNECTOR_AUTH')).toBe('源端认证失败');
    expect(describeConnectorErrorCode('KB_CONNECTOR_TIMEOUT')).toBe('同步超时');
    expect(describeConnectorErrorCode('KB_CONNECTOR_TYPE_UNSUPPORTED')).toBe('文件类型不支持');
    expect(describeConnectorErrorCode('FUTURE_CODE')).toBe('同步失败');
    expect(describeConnectorErrorCode(null)).toBe('—');
  });

  it('时间格式化为北京时间并保留 +08:00，非法值为占位符', () => {
    expect(formatConnectorTime('2026-11-24T10:00:00+08:00')).toBe('2026-11-24 10:00:00 +08:00');
    expect(formatConnectorTime('2026-11-24T02:00:00Z')).toBe('2026-11-24 10:00:00 +08:00');
    expect(formatConnectorTime(null)).toBe('—');
    expect(formatConnectorTime('not-a-time')).toBe('—');
  });

  it('对象 ID 防御式只显示末两段或 32 字符', () => {
    expect(formatConnectorExternalId('contracts/2026/a.pdf')).toBe('2026/a.pdf');
    const truncated = formatConnectorExternalId(`prefix/${'x'.repeat(40)}/file.pdf`);
    expect(truncated).toHaveLength(33);
    expect(truncated).toMatch(/^…/);
    expect(formatConnectorExternalId(null)).toBe('—');
  });

  it('凭据掩码保留首尾且不会整串回显', () => {
    expect(formatCredentialHint(null)).toBe('未配置');
    expect(formatCredentialHint('AKIA…7F2')).toBe('AKIA…7F2');
    expect(formatCredentialHint('FAKEACCESSKEYVALUE123456')).toBe('FAKE…456');
    expect(formatCredentialHint('short')).toBe('s…t');
  });

  it('同步进行中由 started 非空且 finished 为空判定', () => {
    expect(isConnectorSyncInProgress(makeConnector({ lastSyncFinishedAt: null }))).toBe(true);
    expect(isConnectorSyncInProgress(makeConnector())).toBe(false);
    expect(
      isConnectorSyncInProgress(
        makeConnector({ lastSyncStartedAt: null, lastSyncFinishedAt: null }),
      ),
    ).toBe(false);
  });

  it('403/404/409/400/503 映射为可执行中文说明并保留错误码', () => {
    expect(describeConnectorError(apiError(403, 'RICCON-AUTH-403'), 'list')).toContain(
      'RICCON-AUTH-403',
    );
    expect(describeConnectorError(apiError(403, 'RICCON-AUTH-403'), 'update')).toContain(
      '连接器管理权限',
    );
    expect(describeConnectorError(apiError(404, 'RICCON-TENANT-404'), 'list')).toContain(
      'RICCON-TENANT-404',
    );
    expect(
      describeConnectorError(
        apiError(400, 'RICCON-KB-400', 'defaultVisibility is required'),
        'create',
      ),
    ).toContain('必须显式选择默认可见范围');
    expect(
      describeConnectorError(
        apiError(400, 'RICCON-KB-400', 'endpointId is not in the deployment allowlist'),
        'create',
      ),
    ).toContain('RICCON_CONNECTOR_ENDPOINTS');
    expect(
      describeConnectorError(
        apiError(409, 'RICCON-KB-409', 'connector name already exists'),
        'create',
      ),
    ).toContain('软删除后同名也不能重建');
    expect(
      describeConnectorError(
        apiError(409, 'RICCON-KB-409', 'connector sync is already pending or running'),
        'sync',
      ),
    ).toContain('已有排队或运行中的同步');
    expect(
      describeConnectorError(
        apiError(503, 'RICCON-KB-503', 'connector feature is disabled'),
        'test',
      ),
    ).toContain('RICCON_CONNECTOR_ENABLED');
  });

  it('未识别异常使用兜底文案', () => {
    expect(describeConnectorError(new Error('boom'), 'list')).toBe(
      '连接器操作未完成，请稍后重试。',
    );
  });
});
