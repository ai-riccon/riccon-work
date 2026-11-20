// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import type { SessionInfo } from '@/services/api/types';
import { authStore } from '@/services/auth/auth-store';
import type { ModelAdminApi } from '@/services/model';
import type { ModelCredential, ModelProvider } from '@/services/model';
import { DEMO_SESSION, pageOf } from '@/test/fake-api';
import { withProviders } from '@/test/render';
import ModelKeysPage from './index';

/** 平台管理员会话：可读可写平台 key。 */
const PLATFORM_ADMIN_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  user: { ...DEMO_SESSION.user, id: 'platform-admin', displayName: '赵蕾' },
  tenant: { ...DEMO_SESSION.tenant, id: 'platform', name: '平台' },
  roles: ['PLATFORM_ADMIN'],
  permissions: ['platform.model.read', 'platform.model.manage'],
};

/** 租户管理员会话：没有平台模型权限。 */
const TENANT_ADMIN_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  roles: ['TENANT_ADMIN'],
  permissions: [...DEMO_SESSION.permissions, 'tenant.user.manage', 'audit.read'],
};

/**
 * 构造端点。
 *
 * @param overrides 局部覆盖
 * @returns ModelProvider
 */
function makeProvider(overrides: Partial<ModelProvider> = {}): ModelProvider {
  return {
    id: 'provider-primary',
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
 * 构造平台 key。
 *
 * @param overrides 局部覆盖
 * @returns ModelCredential
 */
function makeCredential(overrides: Partial<ModelCredential> = {}): ModelCredential {
  return {
    id: 'cred-1',
    providerId: 'provider-primary',
    label: '主 key',
    maskedHint: '****a1b2',
    status: 'ACTIVE',
    priority: 100,
    version: 1,
    consecutiveFailures: 0,
    cooldownUntil: null,
    createdAt: '2026-11-20T10:00:00+08:00',
    updatedAt: '2026-11-20T10:00:00+08:00',
    rotatedAt: null,
    lastUsedAt: null,
    ...overrides,
  };
}

/** 构造模型管理 API 替身。 */
function createModelApi(overrides: Partial<ModelAdminApi> = {}): ModelAdminApi {
  const credential = makeCredential();
  return {
    listEmployeeModels: vi.fn(async () => pageOf([])),
    listAdminModels: vi.fn(async () => pageOf([])),
    listModelProviders: vi.fn(async () => pageOf([makeProvider()])),
    updateModelStatus: vi.fn(async () => {
      throw new Error('not used');
    }),
    getModelRouting: vi.fn(async () => ({ items: [] })),
    updateModelRouting: vi.fn(async () => {
      throw new Error('not used');
    }),
    listModelCredentials: vi.fn(async () => pageOf([credential])),
    createModelCredential: vi.fn(async () => credential),
    rotateModelCredential: vi.fn(async () =>
      makeCredential({ id: 'cred-2', label: '轮换后', maskedHint: '****c3d4' }),
    ),
    disableModelCredential: vi.fn(async () =>
      makeCredential({ status: 'DISABLED', changed: true }),
    ),
    enableModelCredential: vi.fn(async () => makeCredential({ status: 'ACTIVE', changed: true })),
    ...overrides,
  };
}

/**
 * 渲染模型 key 页。
 *
 * @param api API 替身
 * @returns Testing Library 渲染结果
 */
function renderPage(api: ModelAdminApi) {
  return render(withProviders(<ModelKeysPage api={api} />));
}

describe('模型 key 页', () => {
  beforeEach(() => {
    authStore.reset();
    authStore.setAuthenticated(PLATFORM_ADMIN_SESSION);
  });

  it('列表只显示掩码与元数据，不渲染任何明文 key', async () => {
    const leaked = {
      ...makeCredential(),
      apiKey: 'server-leak-123456',
    } as unknown as ModelCredential;
    renderPage(createModelApi({ listModelCredentials: vi.fn(async () => pageOf([leaked])) }));

    expect(await screen.findByText('主 key')).toBeVisible();
    expect(screen.getByText('****a1b2')).toBeVisible();
    expect(screen.getByText('可用')).toBeVisible();
    expect(screen.getByText('最后一把可用 key')).toBeVisible();
    expect(screen.getByText(/优先级 100/)).toBeVisible();
    expect(screen.queryByText(/server-leak/)).not.toBeInTheDocument();
    expect(screen.queryByText(/apiKey/)).not.toBeInTheDocument();
    expect(screen.getByText(/key 生命周期与在途影响/)).toBeVisible();
  });

  it('租户管理员没有平台模型权限时直接 403 且不请求数据', () => {
    authStore.reset();
    authStore.setAuthenticated(TENANT_ADMIN_SESSION);
    const api = createModelApi();
    renderPage(api);

    expect(screen.getByText('没有访问权限')).toBeVisible();
    expect(screen.getByText(/模型 key 仅向平台管理员开放/)).toBeVisible();
    expect(api.listModelProviders).not.toHaveBeenCalled();
    expect(api.listModelCredentials).not.toHaveBeenCalled();
  });

  it('部署没有端点时给出空态与重新加载入口', async () => {
    const api = createModelApi({ listModelProviders: vi.fn(async () => pageOf([])) });
    renderPage(api);

    expect(await screen.findByText(/部署尚未配置任何模型端点/)).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '重新加载端点目录' }));
    await waitFor(() => expect(api.listModelProviders).toHaveBeenCalledTimes(2));
  });

  it('端点没有 key 时引导新增第一把 key', async () => {
    renderPage(createModelApi({ listModelCredentials: vi.fn(async () => pageOf([])) }));

    expect(await screen.findByText(/该端点还没有平台 key/)).toBeVisible();
    expect(screen.getByRole('button', { name: '新增第一把 key' })).toBeVisible();
  });

  it('key 列表加载失败显示错误态并可重试', async () => {
    let attempts = 0;
    const api = createModelApi({
      listModelCredentials: vi.fn(async () => {
        attempts += 1;
        if (attempts === 1) {
          throw new ApiError('server', { status: 500, code: 'RICCON-COMMON-500' });
        }
        return pageOf([makeCredential()]);
      }),
    });
    renderPage(api);

    expect(await screen.findByText('key 列表加载失败')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(await screen.findByText('主 key')).toBeVisible();
    expect(api.listModelCredentials).toHaveBeenCalledTimes(2);
  });

  it('新增 key 提交一次明文，成功后关闭弹窗且提示只含掩码', async () => {
    const created = makeCredential({ id: 'cred-new', label: '新 key', maskedHint: '****e5f6' });
    const api = createModelApi({
      createModelCredential: vi.fn(async () => created),
    });
    renderPage(api);

    fireEvent.click(await screen.findByRole('button', { name: /新增 key/ }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/敏感信息只提交一次/)).toBeInTheDocument();
    fireEvent.change(within(dialog).getByLabelText('key 名称'), { target: { value: '新 key' } });
    fireEvent.change(within(dialog).getByLabelText('API key'), {
      target: { value: 'test-key-1234567890' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: '新增 key' }));

    await waitFor(() =>
      expect(api.createModelCredential).toHaveBeenCalledWith('provider-primary', {
        label: '新 key',
        apiKey: 'test-key-1234567890',
        priority: 100,
      }),
    );
    expect(await screen.findByText(/已新增 key「新 key」（\*\*\*\*e5f6）/)).toBeVisible();
    expect(screen.queryByText(/test-key-1234567890/)).not.toBeInTheDocument();
  });

  it('新增 key 在客户端拒绝过短或带首尾空白的明文', async () => {
    const api = createModelApi();
    renderPage(api);

    fireEvent.click(await screen.findByRole('button', { name: /新增 key/ }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('key 名称'), { target: { value: '短 key' } });
    fireEvent.change(within(dialog).getByLabelText('API key'), { target: { value: 'short' } });
    fireEvent.click(within(dialog).getByRole('button', { name: '新增 key' }));

    expect(
      await within(dialog).findByText('API key 长度必须为 16–512 个字符。'),
    ).toBeInTheDocument();
    expect(api.createModelCredential).not.toHaveBeenCalled();
  });

  it('重复 key 的 409 在弹窗内显示固定文案且不回显明文', async () => {
    const api = createModelApi({
      createModelCredential: vi.fn(async () => {
        throw new ApiError('duplicate', {
          status: 409,
          code: 'RICCON-MODEL-409',
          problem: {
            type: 'about:blank',
            title: 'Conflict',
            status: 409,
            code: 'RICCON-MODEL-409',
            traceId: 'trace-1',
            reason: 'DUPLICATE_CREDENTIAL',
          },
        });
      }),
    });
    renderPage(api);

    fireEvent.click(await screen.findByRole('button', { name: /新增 key/ }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('key 名称'), { target: { value: '重复 key' } });
    fireEvent.change(within(dialog).getByLabelText('API key'), {
      target: { value: 'test-key-1234567890' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: '新增 key' }));

    expect(await within(dialog).findByText(/相同 key 已存在/)).toBeInTheDocument();
    expect(within(dialog).queryByText(/test-key-1234567890/)).not.toBeInTheDocument();
  });

  it('轮换弹窗在保存前说明在途影响，提交后提示旧 key 只保留掩码', async () => {
    const api = createModelApi();
    renderPage(api);

    fireEvent.click(await screen.findByRole('button', { name: '轮换 key 主 key' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('轮换影响（保存前请确认）')).toBeInTheDocument();
    expect(within(dialog).getByText(/进行中的流式请求不会被取消/)).toBeInTheDocument();
    fireEvent.change(within(dialog).getByLabelText('API key'), {
      target: { value: 'test-key-0987654321' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: '确认轮换' }));

    await waitFor(() =>
      expect(api.rotateModelCredential).toHaveBeenCalledWith('provider-primary', 'cred-1', {
        apiKey: 'test-key-0987654321',
        label: '主 key',
      }),
    );
    expect(await screen.findByText(/已轮换 key「轮换后」（\*\*\*\*c3d4）/)).toBeVisible();
  });

  it('最后一把可用 key 可停用，并明确该端点随后没有可用 key', async () => {
    const api = createModelApi();
    renderPage(api);

    fireEvent.click(await screen.findByRole('button', { name: '停用 key 主 key' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('这是该端点最后一把可用 key')).toBeInTheDocument();
    expect(within(dialog).getByText(/在途流式请求继续/)).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: /停\s*用/ }));

    await waitFor(() =>
      expect(api.disableModelCredential).toHaveBeenCalledWith('provider-primary', 'cred-1'),
    );
    expect(await screen.findByText(/该端点当前没有可用 key/)).toBeVisible();
    expect(await screen.findByText('已停用')).toBeVisible();
  });

  it('失效 key 可恢复，成功后清除失效状态', async () => {
    const api = createModelApi({
      listModelCredentials: vi.fn(async () =>
        pageOf([makeCredential({ status: 'INVALID', consecutiveFailures: 3 })]),
      ),
    });
    renderPage(api);

    expect(await screen.findByText('已失效')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '恢复 key 主 key' }));

    await waitFor(() =>
      expect(api.enableModelCredential).toHaveBeenCalledWith('provider-primary', 'cred-1'),
    );
    expect(await screen.findByText(/已恢复失效 key「主 key」/)).toBeVisible();
    expect(await screen.findByText('可用')).toBeVisible();
  });

  it('不可路由端点禁止新增 key，但保留已有 key 的停用入口', async () => {
    renderPage(
      createModelApi({
        listModelProviders: vi.fn(async () =>
          pageOf([makeProvider({ allowed: false, environmentLabel: 'DEMO_ONLY' })]),
        ),
      }),
    );

    expect(await screen.findByText('端点当前不可路由')).toBeVisible();
    expect(screen.getByText(/生产模式仅允许客户内网网关/)).toBeVisible();
    expect(screen.getByRole('button', { name: /新增 key/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: '停用 key 主 key' })).toBeEnabled();
  });
});
