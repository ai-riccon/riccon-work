import { describe, expect, it } from 'vitest';
import { ApiError } from '@/services/api/errors';
import {
  credentialMask,
  credentialStatusColor,
  credentialStatusLabel,
  describeModelError,
  endpointColor,
  endpointLabel,
  extractRoutingConflictVersion,
  formatModelTime,
  isLastActiveCredential,
  isProviderRoutable,
  isRoutingDraftDirty,
  modelStatusColor,
  modelStatusLabel,
  providerRoutabilityHint,
  purposeLabel,
} from './format';
import type { ModelCredential, ModelProvider, ModelRouting } from './types';

/**
 * 构造带稳定 reason 的模型错误。
 *
 * @param status HTTP 状态
 * @param code 错误码
 * @param reason 稳定原因码
 * @param detail 服务端脱敏说明
 * @returns ApiError
 */
function modelError(status: number, code: string, reason?: string, detail?: string): ApiError {
  return new ApiError(detail ?? 'problem', {
    status,
    code,
    traceId: 'trace-1',
    problem: {
      type: 'about:blank',
      title: 'Problem',
      status,
      code,
      traceId: 'trace-1',
      detail,
      reason,
    },
  });
}

/**
 * 构造 key。
 *
 * @param overrides 局部覆盖
 * @returns ModelCredential
 */
function credential(overrides: Partial<ModelCredential> = {}): ModelCredential {
  return {
    id: 'cred-1',
    providerId: 'provider-1',
    label: '主 key',
    maskedHint: '****a1b2',
    status: 'ACTIVE',
    priority: 100,
    version: 1,
    consecutiveFailures: 0,
    cooldownUntil: null,
    createdAt: '2026-11-20T02:00:00Z',
    updatedAt: '2026-11-20T02:00:00Z',
    rotatedAt: null,
    lastUsedAt: null,
    ...overrides,
  };
}

/**
 * 构造端点。
 *
 * @param overrides 局部覆盖
 * @returns ModelProvider
 */
function provider(overrides: Partial<ModelProvider> = {}): ModelProvider {
  return {
    id: 'provider-1',
    code: 'primary',
    name: '主端点',
    protocol: 'OPENAI_COMPATIBLE',
    environmentLabel: 'PRODUCTION',
    allowed: true,
    activeCredentialCount: 1,
    modelCount: 2,
    status: 'ACTIVE',
    ...overrides,
  };
}

/**
 * 构造路由链。
 *
 * @param modelIds 有序模型 ID
 * @returns ModelRouting
 */
function routing(modelIds: string[]): ModelRouting {
  return {
    purpose: 'CHAT',
    version: 3,
    entries: modelIds.map((modelId, index) => ({
      position: index + 1,
      modelId,
      displayName: `模型 ${modelId}`,
      status: 'ACTIVE',
      environmentLabel: 'PRODUCTION',
    })),
    updatedAt: '2026-11-20T02:00:00Z',
    updatedBy: null,
  };
}

describe('模型管理格式化', () => {
  it('用途、状态与端点标签使用固定中文口径', () => {
    expect(purposeLabel('CHAT')).toBe('对话链');
    expect(purposeLabel('REPORT')).toBe('报告链');
    expect(modelStatusLabel('ACTIVE')).toBe('启用');
    expect(modelStatusLabel('DISABLED')).toBe('已停用');
    expect(modelStatusColor('ACTIVE')).toBe('green');
    expect(modelStatusColor('DISABLED')).toBe('default');
    expect(endpointLabel('PRODUCTION')).toBe('生产可用');
    expect(endpointLabel('DEMO_ONLY')).toBe('仅演示（非生产）');
    expect(endpointLabel(undefined)).toBe('未知标签');
    expect(endpointColor('PRODUCTION')).toBe('green');
    expect(endpointColor('DEMO_ONLY')).toBe('orange');
    expect(endpointColor(undefined)).toBe('red');
    expect(credentialStatusLabel('ACTIVE')).toBe('可用');
    expect(credentialStatusLabel('DISABLED')).toBe('已停用');
    expect(credentialStatusLabel('INVALID')).toBe('已失效');
    expect(credentialStatusColor('INVALID')).toBe('red');
    expect(credentialStatusColor('DISABLED')).toBe('default');
  });

  it('时间统一格式化为北京时间并带 +08:00 偏移', () => {
    expect(formatModelTime('2026-11-20T02:00:00Z')).toBe('2026-11-20 10:00:00 +08:00');
    expect(formatModelTime(null)).toBe('—');
    expect(formatModelTime('not-a-date')).toBe('—');
  });

  it('掩码防御式只保留末 4 位，不整串回显', () => {
    expect(credentialMask('****a1b2')).toBe('****a1b2');
    expect(credentialMask('sk-live-1234567890')).toBe('****7890');
    expect(credentialMask(null)).toBe('****');
    expect(credentialMask('****')).toBe('****');
  });

  it('最后一把可用 key 只统计 ACTIVE', () => {
    expect(isLastActiveCredential([credential()], 'cred-1')).toBe(true);
    expect(isLastActiveCredential([credential(), credential({ id: 'cred-2' })], 'cred-1')).toBe(
      false,
    );
    expect(
      isLastActiveCredential(
        [credential({ status: 'DISABLED' }), credential({ id: 'cred-2' })],
        'cred-1',
      ),
    ).toBe(false);
  });

  it('草稿比较忽略相同顺序，识别移动与增减', () => {
    const chain = routing(['a', 'b']);
    expect(isRoutingDraftDirty(chain, ['a', 'b'])).toBe(false);
    expect(isRoutingDraftDirty(chain, ['b', 'a'])).toBe(true);
    expect(isRoutingDraftDirty(chain, ['a'])).toBe(true);
    expect(isRoutingDraftDirty(chain, ['a', 'b', 'c'])).toBe(true);
  });

  it('端点可路由只看部署 allowed，并给出生产/演示可读原因', () => {
    expect(isProviderRoutable(provider())).toBe(true);
    expect(isProviderRoutable(provider({ allowed: false }))).toBe(false);
    expect(isProviderRoutable(undefined)).toBe(false);
    expect(providerRoutabilityHint(undefined)).toContain('端点不在目录中');
    expect(providerRoutabilityHint(provider({ allowed: false }))).toContain('未被部署允许列表放行');
    expect(
      providerRoutabilityHint(provider({ allowed: false, environmentLabel: 'DEMO_ONLY' })),
    ).toContain('仅用于演示，生产模式仅允许客户内网网关');
  });

  it('从 409 detail 中提取当前版本，缺失时返回 null', () => {
    expect(extractRoutingConflictVersion('routing version conflict; current version is 7')).toBe(7);
    expect(extractRoutingConflictVersion('current version is 0')).toBeNull();
    expect(extractRoutingConflictVersion('conflict')).toBeNull();
    expect(extractRoutingConflictVersion(null)).toBeNull();
  });
});

describe('模型错误文案', () => {
  it('403/401 使用固定权限与会话文案', () => {
    expect(describeModelError(modelError(403, 'RICCON-AUTH-403'), 'routing-save')).toContain(
      '只有平台管理员',
    );
    expect(describeModelError(modelError(401, 'RICCON-AUTH-401'), 'routing-load')).toContain(
      '登录状态已失效',
    );
  });

  it('AC-15 端点拒绝与生产演示端点使用可读中文原因', () => {
    expect(
      describeModelError(
        modelError(400, 'RICCON-MODEL-400', 'ENDPOINT_NOT_ALLOWED'),
        'routing-save',
      ),
    ).toContain('生产模式仅允许客户内网网关');
    expect(
      describeModelError(modelError(400, 'RICCON-MODEL-400', 'MODEL_NOT_ROUTABLE'), 'routing-save'),
    ).toContain('端点已停用或协议不受支持');
    expect(
      describeModelError(
        modelError(400, 'RICCON-MODEL-400', 'PROVIDER_NOT_ALLOWED'),
        'credential-create',
      ),
    ).toContain('不能新增 key');
  });

  it('路由 409 提示刷新并解析服务端当前版本', () => {
    const message = describeModelError(
      modelError(
        409,
        'RICCON-MODEL-409',
        'VERSION_CONFLICT',
        'routing version conflict; current version is 5',
      ),
      'routing-save',
    );
    expect(message).toContain('已被其他管理员修改');
    expect(message).toContain('v5');
    expect(message).toContain('重新加载');
  });

  it('key 冲突、字段错误与 404 映射为固定文案', () => {
    expect(
      describeModelError(
        modelError(409, 'RICCON-MODEL-409', 'DUPLICATE_CREDENTIAL'),
        'credential-create',
      ),
    ).toContain('相同 key 已存在');
    expect(
      describeModelError(
        modelError(409, 'RICCON-MODEL-409', 'CREDENTIAL_STATE_CONFLICT'),
        'credential-disable',
      ),
    ).toContain('刷新列表');
    expect(
      describeModelError(
        modelError(400, 'RICCON-MODEL-400', 'INVALID_API_KEY'),
        'credential-create',
      ),
    ).toContain('16–512');
    expect(describeModelError(modelError(404, 'RICCON-TENANT-404'), 'credentials-load')).toContain(
      '端点不存在',
    );
    expect(describeModelError(new Error('boom'), 'routing-load')).toContain('模型管理操作未完成');
  });
});
