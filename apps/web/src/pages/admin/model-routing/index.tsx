import { ReloadOutlined, SaveOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import {
  Alert,
  Button,
  Card,
  Empty,
  Flex,
  Result,
  Select,
  Skeleton,
  Space,
  Tag,
  Typography,
} from 'antd';
import type { CSSProperties } from 'react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api as defaultApi } from '@/services/api';
import { ApiError } from '@/services/api/errors';
import { authStore, useAuth } from '@/services/auth/auth-store';
import {
  MODEL_PERMISSIONS,
  describeModelError,
  endpointColor,
  endpointLabel,
  formatModelTime,
  hasPermission,
  isProviderRoutable,
  isRoutingDraftDirty,
  modelApi as defaultModelApi,
  modelStatusColor,
  modelStatusLabel,
  providerRoutabilityHint,
  purposeLabel,
} from '@/services/model';
import type {
  AdminModel,
  ModelAdminApi,
  ModelProvider,
  ModelPurpose,
  ModelRouting,
} from '@/services/model';
import './model-routing.css';

/** 模型路由页可注入依赖，便于组件测试。 */
export interface ModelRoutingPageProps {
  /** 平台模型管理 API；默认使用同源真实客户端。 */
  api?: ModelAdminApi;
}

/** 页面主阶段。 */
type RoutingPhase = 'loading' | 'ready' | 'empty' | 'forbidden' | 'error';

/** 路由链固定顺序；服务端保证各返回一条，页面仍按业务顺序渲染。 */
const PURPOSES: readonly ModelPurpose[] = ['CHAT', 'REPORT'];

/**
 * 状态与端点 Tag 的高对比配色。
 *
 * antd 6 filled Tag 默认用 preset 色阶 7 作文字、色阶 1 作背景：绿色 3.37:1、橙色 3.34:1、
 * 金色 2.76:1，12px 字号下低于 WCAG 2.1 AA 的 4.5:1（axe color-contrast）。这里把文字加深到
 * 色阶 8/9，背景保持色阶 1 并补齐同色系边框：绿色 5.44:1、橙色 5.09:1、金色 6.53:1。
 * Tag 仍保留 preset 颜色（ant-tag-green 等），只在视觉上换成浅底深字。
 */
const HIGH_CONTRAST_TAG_STYLES: Record<'green' | 'orange' | 'gold', CSSProperties> = {
  green: { color: '#237804', background: '#f6ffed', borderColor: '#b7eb8f' },
  orange: { color: '#ad4e00', background: '#fff7e6', borderColor: '#ffd591' },
  gold: { color: '#874d00', background: '#fffbe6', borderColor: '#ffe58f' },
};

/**
 * 返回 Tag 的高对比内联样式。
 *
 * @param color Tag 的 preset 颜色
 * @returns 已覆盖颜色的内联样式；其他颜色（红/蓝/默认）默认值已满足 AA，返回 undefined
 */
function highContrastTagStyle(color: string): CSSProperties | undefined {
  return HIGH_CONTRAST_TAG_STYLES[color as keyof typeof HIGH_CONTRAST_TAG_STYLES];
}

/**
 * 按 CHAT/REPORT 固定顺序排序路由链。
 *
 * @param items 服务端返回的链
 * @returns 排序后的链
 */
function orderChains(items: readonly ModelRouting[]): ModelRouting[] {
  const byPurpose = new Map(items.map((chain) => [chain.purpose, chain]));
  return PURPOSES.map((purpose) => byPurpose.get(purpose)).filter(
    (chain): chain is ModelRouting => chain !== undefined,
  );
}

/**
 * 从服务端链生成可编辑草稿。
 *
 * @param chains 服务端链
 * @returns 每条链的有序模型 ID
 */
function draftFromChains(chains: readonly ModelRouting[]): Record<ModelPurpose, string[]> {
  return {
    CHAT:
      chains.find((chain) => chain.purpose === 'CHAT')?.entries.map((entry) => entry.modelId) ?? [],
    REPORT:
      chains.find((chain) => chain.purpose === 'REPORT')?.entries.map((entry) => entry.modelId) ??
      [],
  };
}

/**
 * 把模型 ID 列表转换为新数组，并在边界内移动一项。
 *
 * @param list 原列表
 * @param index 当前下标
 * @param delta 位移方向（-1 上移，1 下移）
 * @returns 新列表；越界时返回原列表副本
 */
function moveItem(list: readonly string[], index: number, delta: number): string[] {
  const next = [...list];
  const target = index + delta;
  if (index < 0 || index >= next.length || target < 0 || target >= next.length) {
    return next;
  }
  const [item] = next.splice(index, 1);
  next.splice(target, 0, item);
  return next;
}

/** 路由链条目行，携带草稿位置。 */
interface RoutingRow {
  modelId: string;
  index: number;
}

/** 单条路由链卡片的属性。 */
interface ChainCardProps {
  chain: ModelRouting;
  draft: readonly string[];
  models: readonly AdminModel[];
  providersById: ReadonlyMap<string, ModelProvider>;
  canManage: boolean;
  saving: boolean;
  busyModelId: string | null;
  selectedModelId?: string;
  addError?: string;
  conflict?: string;
  onMove: (index: number, delta: number) => void;
  onRemove: (modelId: string) => void;
  onSelectModel: (modelId: string | undefined) => void;
  onAdd: () => void;
  onToggleStatus: (model: AdminModel) => void;
  onSave: () => void;
  onReset: () => void;
  onReload: () => void;
}

/**
 * 单条路由链卡片。
 *
 * 展示有序模型、端点标签与启停状态；顺序调整使用原生按钮，保证键盘可完成
 * “打开路由页 → 调整顺序 → 保存”（AC-14）。服务端只接受整链保存，因此
 * 所有修改先进入草稿，点击保存时携带读取到的版本号。
 *
 * @param props 链、草稿与操作回调
 * @returns 路由链卡片
 */
function ChainCard({
  chain,
  draft,
  models,
  providersById,
  canManage,
  saving,
  busyModelId,
  selectedModelId,
  addError,
  conflict,
  onMove,
  onRemove,
  onSelectModel,
  onAdd,
  onToggleStatus,
  onSave,
  onReset,
  onReload,
}: ChainCardProps) {
  const dirty = isRoutingDraftDirty(chain, draft);
  const modelById = useMemo(() => new Map(models.map((model) => [model.id, model])), [models]);
  const rows: RoutingRow[] = draft.map((modelId, index) => ({ modelId, index }));
  const options = useMemo(
    () =>
      models
        .filter((model) => !draft.includes(model.id))
        .map((model) => {
          const provider = providersById.get(model.providerId);
          const routable = isProviderRoutable(provider);
          const suffix = routable ? '' : ' · 不可路由';
          return {
            value: model.id,
            label: `${model.displayName}（${model.modelKey} · ${model.providerCode} · ${endpointLabel(
              model.environmentLabel,
            )}${suffix}）`,
          };
        }),
    [draft, models, providersById],
  );

  return (
    <Card
      className="model-routing-card"
      extra={
        <Typography.Text type="secondary">
          更新于 {formatModelTime(chain.updatedAt)}
        </Typography.Text>
      }
      title={
        <Space wrap>
          <span>{purposeLabel(chain.purpose)}</span>
          <Tag color="blue">版本 v{chain.version}</Tag>
          {dirty && (
            <Tag color="gold" style={highContrastTagStyle('gold')}>
              未保存
            </Tag>
          )}
        </Space>
      }
    >
      <Typography.Paragraph type="secondary" className="model-routing-description">
        按顺序尝试：仅当当前模型在首字前失败时才切换到下一条；停用模型保留在链中但运行时跳过。
        端点标签由部署允许列表派生，修改端点需走部署变更。
      </Typography.Paragraph>

      {conflict && (
        <Alert
          action={
            <Button onClick={onReload} size="small">
              重新加载
            </Button>
          }
          className="model-routing-conflict"
          description={conflict}
          showIcon
          title="路由版本冲突"
          type="warning"
        />
      )}

      {draft.length === 0 ? (
        <Empty
          description="该链还没有模型；至少添加 1 个模型才能保存。若模型目录为空，请先由部署种子配置端点与模型。"
          image={Empty.PRESENTED_IMAGE_SIMPLE}
        >
          <Button disabled={!canManage || saving} onClick={onReload}>
            加载模型目录
          </Button>
        </Empty>
      ) : (
        <Flex className="model-routing-list" gap={8} orientation="vertical">
          {rows.map((row) => {
            const entry = chain.entries.find((item) => item.modelId === row.modelId);
            const model = modelById.get(row.modelId);
            const provider = model ? providersById.get(model.providerId) : undefined;
            const name = model?.displayName ?? entry?.displayName ?? '未知模型';
            const status = model?.status ?? entry?.status ?? 'DISABLED';
            const environment = entry?.environmentLabel ?? model?.environmentLabel;
            return (
              <div className="model-routing-item" key={row.modelId}>
                <Flex align="center" className="model-routing-item-main" gap={8} wrap>
                  <Tag color="default">#{row.index + 1}</Tag>
                  <Typography.Text strong className="model-routing-name">
                    {name}
                  </Typography.Text>
                  {model && <Typography.Text code>{model.modelKey}</Typography.Text>}
                  <Tag
                    color={modelStatusColor(status)}
                    style={highContrastTagStyle(modelStatusColor(status))}
                  >
                    {modelStatusLabel(status)}
                  </Tag>
                  <Tag
                    color={endpointColor(environment)}
                    style={highContrastTagStyle(endpointColor(environment))}
                  >
                    {endpointLabel(environment)}
                  </Tag>
                  {provider && !provider.allowed && <Tag color="red">不可路由</Tag>}
                  {!model && <Tag color="red">目录缺失</Tag>}
                </Flex>
                <Space className="model-routing-item-actions" wrap>
                  <Button
                    aria-label={`上移 ${name}（${purposeLabel(chain.purpose)}）`}
                    autoInsertSpace={false}
                    disabled={!canManage || saving || row.index === 0}
                    onClick={() => onMove(row.index, -1)}
                    size="small"
                  >
                    上移
                  </Button>
                  <Button
                    aria-label={`下移 ${name}（${purposeLabel(chain.purpose)}）`}
                    autoInsertSpace={false}
                    disabled={!canManage || saving || row.index === draft.length - 1}
                    onClick={() => onMove(row.index, 1)}
                    size="small"
                  >
                    下移
                  </Button>
                  <Button
                    aria-label={`移除 ${name}（${purposeLabel(chain.purpose)}）`}
                    autoInsertSpace={false}
                    disabled={!canManage || saving}
                    onClick={() => onRemove(row.modelId)}
                    size="small"
                  >
                    移除
                  </Button>
                  <Button
                    aria-label={`${
                      status === 'ACTIVE' ? '停用模型' : '启用模型'
                    } ${name}（${purposeLabel(chain.purpose)}）`}
                    disabled={!canManage || saving || !model || busyModelId === model.id}
                    loading={busyModelId === model?.id}
                    onClick={() => model && onToggleStatus(model)}
                    size="small"
                  >
                    {status === 'ACTIVE' ? '停用模型' : '启用模型'}
                  </Button>
                </Space>
              </div>
            );
          })}
        </Flex>
      )}

      <Flex align="flex-start" className="model-routing-add" gap={8} wrap>
        <Select
          aria-label={`选择要加入${purposeLabel(chain.purpose)}的模型`}
          disabled={!canManage || saving || options.length === 0}
          onChange={onSelectModel}
          options={options}
          optionFilterProp="label"
          placeholder={options.length === 0 ? '没有可添加的模型' : '选择模型'}
          showSearch
          style={{ flex: '1 1 260px', minWidth: 0 }}
          value={selectedModelId}
        />
        <Button
          aria-label={`添加模型到${purposeLabel(chain.purpose)}`}
          disabled={!canManage || saving || !selectedModelId}
          onClick={onAdd}
        >
          添加模型
        </Button>
      </Flex>

      {addError && (
        <Alert
          className="model-routing-add-error"
          description={addError}
          showIcon
          title="无法加入路由链"
          type="error"
        />
      )}

      <Flex align="center" className="model-routing-footer" gap={8} justify="space-between" wrap>
        <Space wrap>
          <Button
            disabled={!canManage || saving || draft.length === 0 || !dirty}
            icon={<SaveOutlined />}
            loading={saving}
            onClick={onSave}
            type="primary"
          >
            保存{purposeLabel(chain.purpose)}
          </Button>
          <Button
            aria-label={`放弃修改（${purposeLabel(chain.purpose)}）`}
            disabled={!canManage || saving || !dirty}
            onClick={onReset}
          >
            放弃修改
          </Button>
          <Button
            aria-label={`重新加载${purposeLabel(chain.purpose)}`}
            disabled={saving}
            icon={<ReloadOutlined />}
            onClick={onReload}
          >
            重新加载
          </Button>
        </Space>
        <Typography.Text type="secondary">
          共 {draft.length} 个模型；保存后下一次模型请求生效。
        </Typography.Text>
      </Flex>
    </Card>
  );
}

/**
 * 平台模型路由页。
 *
 * 平台管理员可查看 CHAT/REPORT 两条链、调整顺序、启停模型并整链保存；
 * 租户管理员与员工没有 `platform.model.*` 权限，页面直接 403 且不请求数据。
 * 端点标签与不可路由原因来自部署允许列表，保存前在界面给出可读拒绝文案（AC-15）。
 *
 * @param props API 注入
 * @returns 模型路由页面
 */
export default function ModelRoutingPage({ api = defaultModelApi }: ModelRoutingPageProps) {
  const auth = useAuth();
  const [phase, setPhase] = useState<RoutingPhase>('loading');
  const [chains, setChains] = useState<ModelRouting[]>([]);
  const [models, setModels] = useState<AdminModel[]>([]);
  const [providers, setProviders] = useState<ModelProvider[]>([]);
  const [drafts, setDrafts] = useState<Record<ModelPurpose, string[]>>({ CHAT: [], REPORT: [] });
  const [selections, setSelections] = useState<Partial<Record<ModelPurpose, string>>>({});
  const [addErrors, setAddErrors] = useState<Partial<Record<ModelPurpose, string>>>({});
  const [conflicts, setConflicts] = useState<Partial<Record<ModelPurpose, string>>>({});
  const [savingPurpose, setSavingPurpose] = useState<ModelPurpose | null>(null);
  const [busyModelId, setBusyModelId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const requestSequence = useRef(0);

  const session = auth.session;
  const permissions = session?.permissions ?? [];
  const canRead = hasPermission(permissions, MODEL_PERMISSIONS.read);
  const canManage = hasPermission(permissions, MODEL_PERMISSIONS.manage);

  // 管理端布局不恢复会话；直接刷新时由页面补一次恢复。
  useEffect(() => {
    if (auth.status === 'unknown') {
      void authStore.restore(defaultApi);
    }
  }, [auth.status]);

  const providersById = useMemo(
    () => new Map(providers.map((provider) => [provider.id, provider])),
    [providers],
  );

  /**
   * 加载路由链、模型目录与端点目录。
   *
   * 三个接口都只读；任一失败都让页面进入错误态，避免在目录不完整时保存
   * 出“未知模型/不可路由端点”的链。请求序号用于丢弃先发后到的旧响应。
   */
  const load = useCallback(async (): Promise<void> => {
    if (!canRead) {
      return;
    }
    const sequence = requestSequence.current + 1;
    requestSequence.current = sequence;
    setPhase('loading');
    setError(null);
    setActionError(null);
    setNotice(null);
    setConflicts({});
    setAddErrors({});
    try {
      const [routing, modelPage, providerPage] = await Promise.all([
        api.getModelRouting(),
        api.listAdminModels(),
        api.listModelProviders(),
      ]);
      if (requestSequence.current !== sequence) {
        return;
      }
      const ordered = orderChains(routing.items);
      if (ordered.length !== PURPOSES.length) {
        setError('路由链数据不完整：服务端未返回 CHAT 与 REPORT 两条链，请稍后重试。');
        setPhase('error');
        return;
      }
      setChains(ordered);
      setModels(modelPage.items);
      setProviders(providerPage.items);
      setDrafts(draftFromChains(ordered));
      setSelections({});
      const isEmpty =
        modelPage.items.length === 0 && ordered.every((chain) => chain.entries.length === 0);
      setPhase(isEmpty ? 'empty' : 'ready');
    } catch (loadError) {
      if (requestSequence.current !== sequence) {
        return;
      }
      const message = describeModelError(loadError, 'routing-load');
      setError(message);
      setPhase(
        loadError instanceof ApiError && (loadError.status === 403 || loadError.status === 401)
          ? 'forbidden'
          : 'error',
      );
    }
  }, [api, canRead]);

  useEffect(() => {
    void load();
  }, [load]);

  /**
   * 调整某条链中的模型顺序。
   *
   * @param purpose 路由用途
   * @param index 当前下标
   * @param delta 位移方向
   */
  const handleMove = (purpose: ModelPurpose, index: number, delta: number): void => {
    setDrafts((previous) => ({
      ...previous,
      [purpose]: moveItem(previous[purpose], index, delta),
    }));
  };

  /**
   * 从草稿中移除模型；保存前不会影响线上路由。
   *
   * @param purpose 路由用途
   * @param modelId 模型 ID
   */
  const handleRemove = (purpose: ModelPurpose, modelId: string): void => {
    setDrafts((previous) => ({
      ...previous,
      [purpose]: previous[purpose].filter((id) => id !== modelId),
    }));
    setAddErrors((previous) => ({ ...previous, [purpose]: undefined }));
  };

  /**
   * 把选择器中的模型加入草稿。
   *
   * 保存前先检查端点是否可路由：不可路由时不写入草稿，并给出 AC-15 的中文原因，
   * 避免“看似保存成功、运行时永远跳过”的不可用链。
   *
   * @param purpose 路由用途
   */
  const handleAdd = (purpose: ModelPurpose): void => {
    const modelId = selections[purpose];
    if (!modelId) {
      return;
    }
    const model = models.find((item) => item.id === modelId);
    if (!model) {
      setAddErrors((previous) => ({
        ...previous,
        [purpose]: '所选模型已不在模型目录中，请重新加载后重试。',
      }));
      return;
    }
    const provider = providersById.get(model.providerId);
    if (!isProviderRoutable(provider)) {
      setAddErrors((previous) => ({
        ...previous,
        [purpose]: providerRoutabilityHint(provider),
      }));
      return;
    }
    setDrafts((previous) =>
      previous[purpose].includes(modelId)
        ? previous
        : { ...previous, [purpose]: [...previous[purpose], modelId] },
    );
    setSelections((previous) => ({ ...previous, [purpose]: undefined }));
    setAddErrors((previous) => ({ ...previous, [purpose]: undefined }));
  };

  /**
   * 启用或停用模型。
   *
   * 停用是“保留位置但运行时跳过”，因此只更新目录与链目状态，不修改顺序。
   *
   * @param model 目标模型
   */
  const handleToggleStatus = async (model: AdminModel): Promise<void> => {
    const nextStatus = model.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE';
    setBusyModelId(model.id);
    setActionError(null);
    try {
      const updated = await api.updateModelStatus(model.id, nextStatus);
      setModels((previous) =>
        previous.map((item) => (item.id === updated.id ? { ...item, ...updated } : item)),
      );
      setChains((previous) =>
        previous.map((chain) => ({
          ...chain,
          entries: chain.entries.map((entry) =>
            entry.modelId === updated.id ? { ...entry, status: updated.status } : entry,
          ),
        })),
      );
      setNotice(
        nextStatus === 'ACTIVE'
          ? `已启用模型“${updated.displayName}”，下一次模型请求生效。`
          : `已停用模型“${updated.displayName}”；该模型保留在路由链中，但运行时会被跳过。`,
      );
    } catch (toggleError) {
      setActionError(describeModelError(toggleError, 'model-status'));
    } finally {
      setBusyModelId(null);
    }
  };

  /**
   * 保存一条路由链。
   *
   * 409 表示其他管理员已推进版本：页面保留本地草稿并提示重新加载，
   * 不自动覆盖、不自动重试（ADR-0021 §4.3）。
   *
   * @param purpose 路由用途
   */
  const handleSave = async (purpose: ModelPurpose): Promise<void> => {
    const chain = chains.find((item) => item.purpose === purpose);
    const draft = drafts[purpose];
    if (!chain) {
      return;
    }
    if (draft.length === 0) {
      setActionError('路由链不能为空：请至少保留 1 个模型后再保存。');
      return;
    }
    if (!isRoutingDraftDirty(chain, draft)) {
      setNotice('没有需要保存的变更。');
      return;
    }
    setSavingPurpose(purpose);
    setActionError(null);
    setConflicts((previous) => ({ ...previous, [purpose]: undefined }));
    try {
      const saved = await api.updateModelRouting(purpose, {
        version: chain.version,
        modelIds: draft,
      });
      setChains((previous) =>
        previous.map((item) => (item.purpose === saved.purpose ? saved : item)),
      );
      setDrafts((previous) => ({
        ...previous,
        [purpose]: saved.entries.map((entry) => entry.modelId),
      }));
      setNotice(`已保存${purposeLabel(purpose)}（版本 v${saved.version}）；下一次模型请求生效。`);
    } catch (saveError) {
      if (saveError instanceof ApiError && saveError.status === 409) {
        setConflicts((previous) => ({
          ...previous,
          [purpose]: describeModelError(saveError, 'routing-save'),
        }));
      } else {
        setActionError(describeModelError(saveError, 'routing-save'));
      }
    } finally {
      setSavingPurpose(null);
    }
  };

  /**
   * 放弃某条链的本地修改。
   *
   * @param purpose 路由用途
   */
  const handleReset = (purpose: ModelPurpose): void => {
    const chain = chains.find((item) => item.purpose === purpose);
    if (!chain) {
      return;
    }
    setDrafts((previous) => ({
      ...previous,
      [purpose]: chain.entries.map((entry) => entry.modelId),
    }));
    setAddErrors((previous) => ({ ...previous, [purpose]: undefined }));
    setConflicts((previous) => ({ ...previous, [purpose]: undefined }));
  };

  if (auth.status === 'unknown' || auth.status === 'checking') {
    return (
      <PageContainer title="模型路由">
        <div aria-busy="true" aria-label="正在检查登录状态">
          <Skeleton active paragraph={{ rows: 6 }} />
        </div>
      </PageContainer>
    );
  }

  if (auth.status === 'anonymous' || auth.status === 'expired') {
    return (
      <PageContainer title="模型路由">
        <Result
          status="403"
          subTitle="登录状态已失效，请重新登录后再访问模型路由。"
          title="没有访问权限"
        />
      </PageContainer>
    );
  }

  if (auth.status === 'error') {
    return (
      <PageContainer title="模型路由">
        <Result
          extra={
            <Button onClick={() => void authStore.restore(defaultApi)} type="primary">
              重试
            </Button>
          }
          status="error"
          subTitle={auth.error ?? '无法确认登录状态，请检查网络后重试。'}
          title="无法确认登录状态"
        />
      </PageContainer>
    );
  }

  if (!canRead) {
    return (
      <PageContainer title="模型路由">
        <Result
          status="403"
          subTitle="模型路由仅向平台管理员开放；租户管理员与员工无权查看或修改。"
          title="没有访问权限"
        />
      </PageContainer>
    );
  }

  return (
    <PageContainer
      subTitle="平台级配置；保存后下一次模型请求生效。租户管理员与员工不可见。"
      title="模型路由"
    >
      <Alert
        description="端点标签来自部署允许列表：“仅演示（非生产）”端点在生产模式不可被路由选择，且界面不提供自由输入地址。停用模型会保留在链中但运行时跳过。"
        showIcon
        title="路由与端点边界"
        type="info"
      />

      {!canManage && (
        <Alert
          description="当前账号只有查看权限；顺序调整、启停与保存已禁用。"
          showIcon
          style={{ marginTop: 16 }}
          title="只读模式"
          type="warning"
        />
      )}

      {notice && (
        <Alert
          closable
          description={notice}
          onClose={() => setNotice(null)}
          showIcon
          style={{ marginTop: 16 }}
          title="操作已生效"
          type="success"
        />
      )}

      {actionError && (
        <Alert
          aria-live="assertive"
          closable
          description={actionError}
          onClose={() => setActionError(null)}
          role="alert"
          showIcon
          style={{ marginTop: 16 }}
          title="操作未完成"
          type="error"
        />
      )}

      {phase === 'loading' && (
        <div aria-busy="true" aria-label="正在加载模型路由" style={{ marginTop: 16 }}>
          <Skeleton active paragraph={{ rows: 8 }} />
        </div>
      )}

      {phase === 'forbidden' && (
        <Result
          extra={
            <Button onClick={() => void load()} type="primary">
              重试
            </Button>
          }
          status="403"
          subTitle={error ?? '当前账号无权查看模型路由。'}
          title="没有访问权限"
        />
      )}

      {phase === 'error' && (
        <Result
          extra={
            <Button onClick={() => void load()} type="primary">
              重试
            </Button>
          }
          status="error"
          subTitle={error ?? '模型路由加载失败，请稍后重试。'}
          title="模型路由加载失败"
        />
      )}

      {phase === 'empty' && (
        <Empty
          description="模型目录为空，无法配置路由。请先由部署管理员在端点允许列表和部署种子中配置 provider 与模型，然后回到本页添加模型并保存。"
          style={{ marginTop: 24 }}
        >
          <Button onClick={() => void load()} type="primary">
            重新加载模型目录
          </Button>
        </Empty>
      )}

      {phase === 'ready' && (
        <Space
          className="model-routing-chains"
          orientation="vertical"
          size={16}
          style={{ width: '100%' }}
        >
          {chains.map((chain) => (
            <ChainCard
              addError={addErrors[chain.purpose]}
              busyModelId={busyModelId}
              canManage={canManage}
              chain={chain}
              conflict={conflicts[chain.purpose]}
              draft={drafts[chain.purpose]}
              key={chain.purpose}
              models={models}
              onAdd={() => handleAdd(chain.purpose)}
              onMove={(index, delta) => handleMove(chain.purpose, index, delta)}
              onReload={() => void load()}
              onRemove={(modelId) => handleRemove(chain.purpose, modelId)}
              onReset={() => handleReset(chain.purpose)}
              onSave={() => void handleSave(chain.purpose)}
              onSelectModel={(modelId) =>
                setSelections((previous) => ({ ...previous, [chain.purpose]: modelId }))
              }
              onToggleStatus={(model) => void handleToggleStatus(model)}
              providersById={providersById}
              saving={savingPurpose === chain.purpose}
              selectedModelId={selections[chain.purpose]}
            />
          ))}
        </Space>
      )}
    </PageContainer>
  );
}
