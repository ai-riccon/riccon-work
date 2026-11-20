// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import type { SessionInfo } from '@/services/api/types';
import { authStore } from '@/services/auth/auth-store';
import type { ModelAdminApi } from '@/services/model';
import type {
  AdminModel,
  ModelCredential,
  ModelProvider,
  ModelPurpose,
  ModelRouting,
  ModelRoutingUpdateRequest,
  ModelStatus,
} from '@/services/model';
import { DEMO_SESSION, pageOf } from '@/test/fake-api';
import { withProviders } from '@/test/render';
import ModelRoutingPage from './index';

/** 平台管理员会话：可读可写模型路由。 */
const PLATFORM_ADMIN_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  user: { ...DEMO_SESSION.user, id: 'platform-admin', displayName: '赵蕾' },
  tenant: { ...DEMO_SESSION.tenant, id: 'platform', name: '平台' },
  roles: ['PLATFORM_ADMIN'],
  permissions: ['platform.model.read', 'platform.model.manage'],
};

/** 只读平台账号：只有 platform.model.read。 */
const PLATFORM_READONLY_SESSION: SessionInfo = {
  ...PLATFORM_ADMIN_SESSION,
  permissions: ['platform.model.read'],
};

/** 租户管理员会话：没有平台模型权限。 */
const TENANT_ADMIN_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  roles: ['TENANT_ADMIN'],
  permissions: [...DEMO_SESSION.permissions, 'tenant.user.manage', 'audit.read'],
};

/**
 * 构造平台模型。
 *
 * @param overrides 局部覆盖
 * @returns AdminModel
 */
function makeModel(overrides: Partial<AdminModel> = {}): AdminModel {
  return {
    id: 'model-a',
    modelKey: 'model-a',
    displayName: '模型 A',
    providerId: 'provider-primary',
    providerCode: 'primary',
    environmentLabel: 'PRODUCTION',
    contextWindow: 128000,
    maxOutputTokens: 4096,
    capabilities: null,
    inputPricePer1M: '1.000000',
    outputPricePer1M: '2.000000',
    priceCurrency: 'CNY',
    pricingStatus: 'PRICED',
    status: 'ACTIVE',
    ...overrides,
  };
}

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
 * 构造路由链。
 *
 * @param purpose 用途
 * @param modelIds 有序模型
 * @param version 版本
 * @returns ModelRouting
 */
function makeRouting(purpose: ModelPurpose, modelIds: string[], version = 3): ModelRouting {
  return {
    purpose,
    version,
    entries: modelIds.map((modelId, index) => ({
      position: index + 1,
      modelId,
      displayName: modelId === 'model-a' ? '模型 A' : modelId === 'model-b' ? '模型 B' : '模型 C',
      status: 'ACTIVE',
      environmentLabel: modelId === 'model-b' ? 'DEMO_ONLY' : 'PRODUCTION',
    })),
    updatedAt: '2026-11-20T10:00:00+08:00',
    updatedBy: null,
  };
}

/**
 * 构造 key 占位，满足 ModelAdminApi 的形状。
 *
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
    createdAt: '2026-11-20T10:00:00+08:00',
    updatedAt: '2026-11-20T10:00:00+08:00',
    ...overrides,
  };
}

/** 构造模型管理 API 替身。 */
function createModelApi(overrides: Partial<ModelAdminApi> = {}): ModelAdminApi {
  const models = [
    makeModel(),
    makeModel({ id: 'model-b', modelKey: 'model-b', displayName: '模型 B' }),
  ];
  const chat = makeRouting('CHAT', ['model-a']);
  const report = makeRouting('REPORT', ['model-b'], 2);
  return {
    listEmployeeModels: vi.fn(async () => pageOf([])),
    listAdminModels: vi.fn(async () => pageOf(models)),
    listModelProviders: vi.fn(async () => pageOf([makeProvider()])),
    updateModelStatus: vi.fn(async (modelId: string, status: ModelStatus) => {
      const model = models.find((item) => item.id === modelId) ?? makeModel();
      return { ...model, status };
    }),
    getModelRouting: vi.fn(async () => ({ items: [chat, report] })),
    updateModelRouting: vi.fn(async (purpose: ModelPurpose, input: ModelRoutingUpdateRequest) => ({
      ...(purpose === 'CHAT' ? chat : report),
      version: input.version + 1,
      entries: input.modelIds.map((modelId, index) => ({
        position: index + 1,
        modelId,
        displayName: models.find((model) => model.id === modelId)?.displayName ?? modelId,
        status: 'ACTIVE' as const,
        environmentLabel: 'PRODUCTION' as const,
      })),
    })),
    listModelCredentials: vi.fn(async () => pageOf([])),
    createModelCredential: vi.fn(async () => makeCredential()),
    rotateModelCredential: vi.fn(async () => makeCredential()),
    disableModelCredential: vi.fn(async () => makeCredential({ status: 'DISABLED' })),
    enableModelCredential: vi.fn(async () => makeCredential()),
    ...overrides,
  };
}

/**
 * 渲染模型路由页。
 *
 * @param api API 替身
 * @returns Testing Library 渲染结果
 */
function renderPage(api: ModelAdminApi) {
  return render(withProviders(<ModelRoutingPage api={api} />));
}

describe('模型路由页', () => {
  beforeEach(() => {
    authStore.reset();
    authStore.setAuthenticated(PLATFORM_ADMIN_SESSION);
  });

  it('平台管理员看到 CHAT/REPORT 两链、版本、端点标签与只读边界说明', async () => {
    renderPage(createModelApi());

    expect(await screen.findByText('对话链')).toBeVisible();
    expect(screen.getByText('报告链')).toBeVisible();
    expect(screen.getAllByText('版本 v3').length).toBeGreaterThan(0);
    expect(screen.getByText('生产可用')).toBeVisible();
    expect(screen.getByText('仅演示（非生产）')).toBeVisible();
    expect(screen.getByText(/端点标签来自部署允许列表/)).toBeVisible();
    expect(screen.getByRole('button', { name: /保存报告链/ })).toBeDisabled();
  });

  it('租户管理员没有平台模型权限时直接 403 且不请求数据', () => {
    authStore.reset();
    authStore.setAuthenticated(TENANT_ADMIN_SESSION);
    const api = createModelApi();
    renderPage(api);

    expect(screen.getByText('没有访问权限')).toBeVisible();
    expect(screen.getByText(/模型路由仅向平台管理员开放/)).toBeVisible();
    expect(api.getModelRouting).not.toHaveBeenCalled();
    expect(api.listAdminModels).not.toHaveBeenCalled();
    expect(api.listModelProviders).not.toHaveBeenCalled();
  });

  it('模型目录为空时给出空态与重新加载入口', async () => {
    const api = createModelApi({
      getModelRouting: vi.fn(async () => ({
        items: [makeRouting('CHAT', [], 1), makeRouting('REPORT', [], 1)],
      })),
      listAdminModels: vi.fn(async () => pageOf([])),
      listModelProviders: vi.fn(async () => pageOf([])),
    });
    renderPage(api);

    expect(await screen.findByText(/模型目录为空，无法配置路由/)).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '重新加载模型目录' }));
    await waitFor(() => expect(api.getModelRouting).toHaveBeenCalledTimes(2));
  });

  it('加载失败显示错误态，重试后恢复', async () => {
    let attempts = 0;
    const api = createModelApi({
      getModelRouting: vi.fn(async () => {
        attempts += 1;
        if (attempts === 1) {
          throw new ApiError('server', { status: 500, code: 'RICCON-COMMON-500' });
        }
        return { items: [makeRouting('CHAT', ['model-a']), makeRouting('REPORT', ['model-b'])] };
      }),
    });
    renderPage(api);

    expect(await screen.findByText('模型路由加载失败')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(await screen.findByText('对话链')).toBeVisible();
  });

  it('键盘可完成“调整顺序 → 保存”，成功后显示版本与生效说明', async () => {
    const api = createModelApi({
      getModelRouting: vi.fn(async () => ({
        items: [makeRouting('CHAT', ['model-a', 'model-b']), makeRouting('REPORT', ['model-a'], 2)],
      })),
    });
    renderPage(api);

    fireEvent.click(await screen.findByRole('button', { name: '下移 模型 A（对话链）' }));
    const save = screen.getByRole('button', { name: /保存对话链/ });
    expect(save).toBeEnabled();
    fireEvent.click(save);

    await waitFor(() =>
      expect(api.updateModelRouting).toHaveBeenCalledWith('CHAT', {
        version: 3,
        modelIds: ['model-b', 'model-a'],
      }),
    );
    expect(await screen.findByText(/已保存对话链（版本 v4）/)).toBeVisible();
    expect(await screen.findByText('版本 v4')).toBeVisible();
  });

  it('放弃修改恢复服务端顺序并禁用保存', async () => {
    renderPage(
      createModelApi({
        getModelRouting: vi.fn(async () => ({
          items: [
            makeRouting('CHAT', ['model-a', 'model-b']),
            makeRouting('REPORT', ['model-a'], 2),
          ],
        })),
      }),
    );

    fireEvent.click(await screen.findByRole('button', { name: '上移 模型 B（对话链）' }));
    expect(screen.getByRole('button', { name: /保存对话链/ })).toBeEnabled();
    fireEvent.click(screen.getByRole('button', { name: '放弃修改（对话链）' }));
    expect(screen.getByRole('button', { name: /保存对话链/ })).toBeDisabled();
  });

  it('版本冲突提示刷新且不自动覆盖本地草稿', async () => {
    const api = createModelApi({
      getModelRouting: vi.fn(async () => ({
        items: [makeRouting('CHAT', ['model-a', 'model-b']), makeRouting('REPORT', ['model-a'], 2)],
      })),
      updateModelRouting: vi.fn(async () => {
        throw new ApiError('routing version conflict; current version is 6', {
          status: 409,
          code: 'RICCON-MODEL-409',
          problem: {
            type: 'about:blank',
            title: 'Conflict',
            status: 409,
            code: 'RICCON-MODEL-409',
            traceId: 'trace-1',
            detail: 'routing version conflict; current version is 6',
            reason: 'VERSION_CONFLICT',
          },
        });
      }),
    });
    renderPage(api);

    fireEvent.click(await screen.findByRole('button', { name: '下移 模型 A（对话链）' }));
    fireEvent.click(screen.getByRole('button', { name: /保存对话链/ }));

    const alert = await screen.findByText('路由版本冲突');
    expect(alert).toBeVisible();
    expect(screen.getByText(/服务端当前版本 v6/)).toBeVisible();
    expect(api.updateModelRouting).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: /保存对话链/ })).toBeEnabled();

    const conflict = alert.closest('.ant-alert');
    fireEvent.click(within(conflict as HTMLElement).getByRole('button', { name: '重新加载' }));
    await waitFor(() => expect(api.getModelRouting).toHaveBeenCalledTimes(2));
  });

  it('停用模型保留链位置并提示运行时跳过', async () => {
    const api = createModelApi();
    renderPage(api);

    fireEvent.click(await screen.findByRole('button', { name: '停用模型 模型 A（对话链）' }));
    await waitFor(() => expect(api.updateModelStatus).toHaveBeenCalledWith('model-a', 'DISABLED'));
    expect(await screen.findByText(/已停用模型“模型 A”/)).toBeVisible();
    expect(screen.getByText(/保留在路由链中，但运行时会被跳过/)).toBeVisible();
    expect(screen.getAllByText('已停用').length).toBeGreaterThan(0);
  });

  it('添加模型后保存包含新顺序', async () => {
    const api = createModelApi({
      getModelRouting: vi.fn(async () => ({
        items: [makeRouting('CHAT', ['model-a']), makeRouting('REPORT', ['model-a'], 2)],
      })),
    });
    renderPage(api);

    const combo = await screen.findByRole('combobox', { name: '选择要加入对话链的模型' });
    fireEvent.mouseDown(combo.parentElement as HTMLElement);
    fireEvent.click(await screen.findByTitle(/模型 B（model-b/));
    fireEvent.click(screen.getByRole('button', { name: '添加模型到对话链' }));
    fireEvent.click(screen.getByRole('button', { name: /保存对话链/ }));

    await waitFor(() =>
      expect(api.updateModelRouting).toHaveBeenCalledWith('CHAT', {
        version: 3,
        modelIds: ['model-a', 'model-b'],
      }),
    );
  });

  it('不可路由端点不会被加入草稿，并显示 AC-15 中文原因', async () => {
    const api = createModelApi({
      getModelRouting: vi.fn(async () => ({
        items: [makeRouting('CHAT', ['model-a']), makeRouting('REPORT', ['model-a'], 2)],
      })),
      listModelProviders: vi.fn(async () =>
        pageOf([
          makeProvider({
            id: 'provider-demo',
            code: 'demo',
            allowed: false,
            environmentLabel: 'DEMO_ONLY',
          }),
        ]),
      ),
      listAdminModels: vi.fn(async () =>
        pageOf([
          makeModel(),
          makeModel({
            id: 'model-demo',
            modelKey: 'model-demo',
            displayName: '演示模型',
            providerId: 'provider-demo',
            providerCode: 'demo',
            environmentLabel: 'DEMO_ONLY',
          }),
        ]),
      ),
    });
    renderPage(api);

    const combo = await screen.findByRole('combobox', { name: '选择要加入对话链的模型' });
    fireEvent.mouseDown(combo.parentElement as HTMLElement);
    fireEvent.click(await screen.findByTitle(/演示模型（model-demo/));
    fireEvent.click(screen.getByRole('button', { name: '添加模型到对话链' }));

    expect(await screen.findByText('无法加入路由链')).toBeVisible();
    expect(screen.getByText(/仅用于演示，生产模式仅允许客户内网网关/)).toBeVisible();
    expect(screen.queryByRole('button', { name: /移除 演示模型/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /保存对话链/ })).toBeDisabled();
    expect(api.updateModelRouting).not.toHaveBeenCalled();
  });

  it('保存被端点策略拒绝时显示稳定中文原因', async () => {
    const api = createModelApi({
      getModelRouting: vi.fn(async () => ({
        items: [makeRouting('CHAT', ['model-a', 'model-b']), makeRouting('REPORT', ['model-a'], 2)],
      })),
      updateModelRouting: vi.fn(async () => {
        throw new ApiError('endpoint not allowed', {
          status: 400,
          code: 'RICCON-MODEL-400',
          problem: {
            type: 'about:blank',
            title: 'Invalid request',
            status: 400,
            code: 'RICCON-MODEL-400',
            traceId: 'trace-1',
            detail: 'modelIds contains a model whose endpoint is not allowed',
            field: 'modelIds',
            reason: 'ENDPOINT_NOT_ALLOWED',
          },
        });
      }),
    });
    renderPage(api);

    fireEvent.click(await screen.findByRole('button', { name: '下移 模型 A（对话链）' }));
    fireEvent.click(screen.getByRole('button', { name: /保存对话链/ }));

    expect(await screen.findByText(/生产模式仅允许客户内网网关/)).toBeVisible();
  });

  it('只有查看权限时禁用排序、启停与保存', async () => {
    authStore.reset();
    authStore.setAuthenticated(PLATFORM_READONLY_SESSION);
    renderPage(createModelApi());

    expect(await screen.findByText('只读模式')).toBeVisible();
    expect(screen.getByRole('button', { name: /保存对话链/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: '下移 模型 A（对话链）' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '停用模型 模型 A（对话链）' })).toBeDisabled();
  });
});
