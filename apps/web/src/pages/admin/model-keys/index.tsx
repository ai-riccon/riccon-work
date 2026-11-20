import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import {
  Alert,
  Button,
  Card,
  Empty,
  Flex,
  Modal,
  Result,
  Select,
  Skeleton,
  Space,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import { api as defaultApi } from '@/services/api';
import { ApiError } from '@/services/api/errors';
import { authStore, useAuth } from '@/services/auth/auth-store';
import {
  MODEL_PERMISSIONS,
  credentialMask,
  credentialStatusColor,
  credentialStatusLabel,
  describeModelError,
  endpointColor,
  endpointLabel,
  formatModelTime,
  hasPermission,
  isLastActiveCredential,
  modelApi as defaultModelApi,
} from '@/services/model';
import type { ModelAdminApi, ModelCredential, ModelProvider } from '@/services/model';
import {
  CredentialFormModal,
  type CredentialFieldErrors,
  type CredentialFormMode,
  type CredentialFormSubmission,
} from './credential-form-modal';
import './model-keys.css';

/** 模型 key 页可注入依赖，便于组件测试。 */
export interface ModelKeysPageProps {
  /** 平台模型管理 API；默认使用同源真实客户端。 */
  api?: ModelAdminApi;
}

/** 页面主阶段（端点目录）。 */
type KeysPhase = 'loading' | 'ready' | 'empty' | 'forbidden' | 'error';

/** key 列表阶段。 */
type CredentialsPhase = 'loading' | 'ready' | 'empty' | 'error';

/** 弹窗状态；关闭时为 null。 */
interface CredentialFormState {
  mode: CredentialFormMode;
  credential?: ModelCredential | null;
}

/**
 * 平台模型 key 管理页。
 *
 * 平台管理员按端点查看掩码 key、新增/轮换/停用/恢复；列表只显示末 4 位掩码，
 * 明文只在新增/轮换弹窗中提交一次，不进入页面状态、成功提示或错误文案。
 * 轮换与“最后一把 key”停用前均展示 AC-07 影响说明；最后一把 key 仍允许停用。
 * 租户管理员与员工没有 `platform.model.*` 权限，页面直接 403 且不请求数据。
 *
 * @param props API 注入
 * @returns 模型 key 页面
 */
export default function ModelKeysPage({ api = defaultModelApi }: ModelKeysPageProps) {
  const auth = useAuth();
  const [phase, setPhase] = useState<KeysPhase>('loading');
  const [providers, setProviders] = useState<ModelProvider[]>([]);
  const [selectedProviderId, setSelectedProviderId] = useState<string | null>(null);
  const [credentials, setCredentials] = useState<ModelCredential[]>([]);
  const [credentialsPhase, setCredentialsPhase] = useState<CredentialsPhase>('loading');
  const [credentialsError, setCredentialsError] = useState<string | null>(null);
  const [formState, setFormState] = useState<CredentialFormState | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<CredentialFieldErrors>({});
  const [disableTarget, setDisableTarget] = useState<ModelCredential | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const requestSequence = useRef(0);
  const credentialsSequence = useRef(0);

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

  const selectedProvider = providers.find((provider) => provider.id === selectedProviderId);
  const activeCredentialCount = credentials.filter(
    (credential) => credential.status === 'ACTIVE',
  ).length;

  /**
   * 加载指定端点的 key 列表。
   *
   * 单独于端点目录加载：切换端点时只刷新 key，避免重复请求目录。
   * 请求序号用于丢弃快速切换端点时先发后到的旧响应。
   *
   * @param providerId 端点 ID
   */
  const loadCredentials = useCallback(
    async (providerId: string): Promise<void> => {
      const sequence = credentialsSequence.current + 1;
      credentialsSequence.current = sequence;
      setCredentialsPhase('loading');
      setCredentialsError(null);
      try {
        const page = await api.listModelCredentials(providerId);
        if (credentialsSequence.current !== sequence) {
          return;
        }
        setCredentials(page.items);
        setCredentialsPhase(page.items.length === 0 ? 'empty' : 'ready');
      } catch (loadError) {
        if (credentialsSequence.current !== sequence) {
          return;
        }
        setCredentialsError(describeModelError(loadError, 'credentials-load'));
        setCredentialsPhase('error');
      }
    },
    [api],
  );

  /**
   * 加载端点目录并选中第一个端点。
   *
   * 每次重新加载都回到第一个端点：端点顺序由部署配置决定，页面不记住跨会话选择，
   * 避免端点被移除后仍指向已不存在的对象。
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
    try {
      const page = await api.listModelProviders();
      if (requestSequence.current !== sequence) {
        return;
      }
      setProviders(page.items);
      if (page.items.length === 0) {
        setSelectedProviderId(null);
        setCredentials([]);
        setCredentialsPhase('empty');
        setPhase('empty');
        return;
      }
      const providerId = page.items[0].id;
      setSelectedProviderId(providerId);
      setPhase('ready');
      await loadCredentials(providerId);
    } catch (loadError) {
      if (requestSequence.current !== sequence) {
        return;
      }
      const message = describeModelError(loadError, 'credentials-load');
      setError(message);
      setPhase(
        loadError instanceof ApiError && (loadError.status === 403 || loadError.status === 401)
          ? 'forbidden'
          : 'error',
      );
    }
  }, [api, canRead, loadCredentials]);

  useEffect(() => {
    void load();
  }, [load]);

  /**
   * 切换端点并加载其 key。
   *
   * @param providerId 端点 ID
   */
  const handleProviderChange = (providerId: string): void => {
    setSelectedProviderId(providerId);
    setActionError(null);
    setNotice(null);
    void loadCredentials(providerId);
  };

  /**
   * 打开新增/轮换弹窗。
   *
   * @param mode 模式
   * @param credential 轮换目标；新增时为空
   */
  const openForm = (mode: CredentialFormMode, credential?: ModelCredential | null): void => {
    setFormError(null);
    setFieldErrors({});
    setFormState({ mode, credential: credential ?? null });
  };

  /**
   * 关闭弹窗并丢弃明文相关的错误状态。
   */
  const closeForm = (): void => {
    setFormState(null);
    setFormError(null);
    setFieldErrors({});
    setSubmitting(false);
  };

  /**
   * 提交新增或轮换。
   *
   * `apiKey` 只从弹窗参数流向 HTTP 请求体：不写入页面状态、成功提示或错误文案。
   * 服务端字段级错误映射到表单输入；其他错误显示固定中文说明。
   *
   * @param values 表单值
   */
  const handleSubmit = async (values: CredentialFormSubmission): Promise<void> => {
    if (!selectedProvider || !formState) {
      return;
    }
    setSubmitting(true);
    setFormError(null);
    setFieldErrors({});
    try {
      if (formState.mode === 'create') {
        const created = await api.createModelCredential(selectedProvider.id, {
          label: values.label,
          apiKey: values.apiKey,
          ...(values.priority === undefined ? {} : { priority: values.priority }),
        });
        setNotice(
          `已新增 key「${created.label}」（${credentialMask(
            created.maskedHint,
          )}）。明文已丢弃，仅在本次提交中传输。`,
        );
      } else if (formState.credential) {
        const rotated = await api.rotateModelCredential(
          selectedProvider.id,
          formState.credential.id,
          {
            apiKey: values.apiKey,
            ...(values.label ? { label: values.label } : {}),
          },
        );
        setNotice(
          `已轮换 key「${rotated.label}」（${credentialMask(
            rotated.maskedHint,
          )}）；旧 key 已停用，仅保留掩码。`,
        );
      }
      closeForm();
      await loadCredentials(selectedProvider.id);
    } catch (submitError) {
      const context = formState.mode === 'create' ? 'credential-create' : 'credential-rotate';
      const message = describeModelError(submitError, context);
      setFormError(message);
      const reason = submitError instanceof ApiError ? submitError.problem?.reason : undefined;
      if (reason === 'INVALID_API_KEY') {
        setFieldErrors({ apiKey: message });
      } else if (reason === 'INVALID_LABEL') {
        setFieldErrors({ label: message });
      } else if (reason === 'INVALID_PRIORITY') {
        setFieldErrors({ priority: message });
      }
      setSubmitting(false);
    }
  };

  /**
   * 停用 key；最后一把可用 key 也允许停用，但会提示该端点随后没有可用 key。
   *
   * @param credential 目标 key
   */
  const handleDisable = async (credential: ModelCredential): Promise<void> => {
    if (!selectedProvider) {
      return;
    }
    setBusyId(credential.id);
    setActionError(null);
    try {
      const updated = await api.disableModelCredential(selectedProvider.id, credential.id);
      const remainingActive = credentials.filter(
        (item) => item.status === 'ACTIVE' && item.id !== credential.id,
      ).length;
      setCredentials((previous) =>
        previous.map((item) => (item.id === updated.id ? { ...item, ...updated } : item)),
      );
      setDisableTarget(null);
      if (updated.changed === false) {
        setNotice(`key「${updated.label}」已是停用状态，服务端未重复记录审计。`);
      } else if (remainingActive === 0) {
        setNotice(
          `已停用 key「${updated.label}」；该端点当前没有可用 key，模型请求会失败，直到新增或恢复 key。在途请求继续。`,
        );
      } else {
        setNotice(`已停用 key「${updated.label}」；新请求不再使用该 key，在途请求继续。`);
      }
    } catch (disableError) {
      setDisableTarget(null);
      setActionError(describeModelError(disableError, 'credential-disable'));
    } finally {
      setBusyId(null);
    }
  };

  /**
   * 恢复 DISABLED/INVALID key。
   *
   * @param credential 目标 key
   */
  const handleEnable = async (credential: ModelCredential): Promise<void> => {
    if (!selectedProvider) {
      return;
    }
    setBusyId(credential.id);
    setActionError(null);
    try {
      const updated = await api.enableModelCredential(selectedProvider.id, credential.id);
      setCredentials((previous) =>
        previous.map((item) => (item.id === updated.id ? { ...item, ...updated } : item)),
      );
      if (updated.changed === false) {
        setNotice(`key「${updated.label}」已可用，无需恢复。`);
      } else if (credential.status === 'INVALID') {
        setNotice(`已恢复失效 key「${updated.label}」；冷却与失效标记已清除，新请求可再次使用。`);
      } else {
        setNotice(`已启用 key「${updated.label}」；新请求可再次使用。`);
      }
    } catch (enableError) {
      setActionError(describeModelError(enableError, 'credential-enable'));
    } finally {
      setBusyId(null);
    }
  };

  if (auth.status === 'unknown' || auth.status === 'checking') {
    return (
      <PageContainer title="模型 key">
        <div aria-busy="true" aria-label="正在检查登录状态">
          <Skeleton active paragraph={{ rows: 6 }} />
        </div>
      </PageContainer>
    );
  }

  if (auth.status === 'anonymous' || auth.status === 'expired') {
    return (
      <PageContainer title="模型 key">
        <Result
          status="403"
          subTitle="登录状态已失效，请重新登录后再管理模型 key。"
          title="没有访问权限"
        />
      </PageContainer>
    );
  }

  if (auth.status === 'error') {
    return (
      <PageContainer title="模型 key">
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
      <PageContainer title="模型 key">
        <Result
          status="403"
          subTitle="模型 key 仅向平台管理员开放；租户管理员与员工无权查看或修改。"
          title="没有访问权限"
        />
      </PageContainer>
    );
  }

  return (
    <PageContainer
      subTitle="平台托管 key 池；列表只显示末 4 位掩码，明文只在新增/轮换时提交一次。"
      title="模型 key"
    >
      <Alert
        description="新请求使用最新可用 key；轮换会在同一事务中新增新 key 并停用旧 key。进行中的流式请求可能以可重试错误结束，且不会重复输出（AC-07）。页面、接口、审计与错误响应都不显示 key 明文。"
        showIcon
        title="key 生命周期与在途影响"
        type="info"
      />

      {!canManage && (
        <Alert
          description="当前账号只有查看权限；新增、轮换、停用与恢复已禁用。"
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
          action={
            selectedProviderId ? (
              <Button onClick={() => void loadCredentials(selectedProviderId)} size="small">
                刷新列表
              </Button>
            ) : undefined
          }
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
        <div aria-busy="true" aria-label="正在加载模型端点" style={{ marginTop: 16 }}>
          <Skeleton active paragraph={{ rows: 6 }} />
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
          subTitle={error ?? '当前账号无权查看模型 key。'}
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
          subTitle={error ?? '端点目录加载失败，请稍后重试。'}
          title="端点目录加载失败"
        />
      )}

      {phase === 'empty' && (
        <Empty
          description="部署尚未配置任何模型端点。请由部署管理员在端点允许列表与部署种子中配置 provider 后，再回到本页管理平台 key。"
          style={{ marginTop: 24 }}
        >
          <Button onClick={() => void load()} type="primary">
            重新加载端点目录
          </Button>
        </Empty>
      )}

      {phase === 'ready' && selectedProvider && (
        <Card
          className="model-keys-card"
          extra={
            <Tag color={endpointColor(selectedProvider.environmentLabel)}>
              {endpointLabel(selectedProvider.environmentLabel)}
            </Tag>
          }
          style={{ marginTop: 16 }}
          title={`端点：${selectedProvider.name}`}
        >
          <Flex align="flex-end" gap={8} wrap>
            <div className="model-keys-provider-select">
              <Typography.Text id="model-keys-provider-label">选择模型端点</Typography.Text>
              <Select
                aria-labelledby="model-keys-provider-label"
                onChange={handleProviderChange}
                optionFilterProp="label"
                options={providers.map((provider) => ({
                  value: provider.id,
                  label: `${provider.name}（${provider.code} · ${endpointLabel(
                    provider.environmentLabel,
                  )}${provider.allowed ? '' : ' · 不可路由'}）`,
                }))}
                showSearch
                style={{ width: '100%' }}
                value={selectedProvider.id}
              />
            </div>
            <Button
              disabled={submitting || busyId !== null}
              icon={<ReloadOutlined />}
              onClick={() => void loadCredentials(selectedProvider.id)}
            >
              刷新 key 列表
            </Button>
            <Button
              disabled={!canManage || submitting || busyId !== null || !selectedProvider.allowed}
              icon={<PlusOutlined />}
              onClick={() => openForm('create')}
              type="primary"
            >
              新增 key
            </Button>
          </Flex>

          <Flex className="model-keys-summary" gap={16} wrap>
            <Typography.Text>端点代码：{selectedProvider.code}</Typography.Text>
            <Typography.Text>
              可路由：{selectedProvider.allowed ? '是' : '否（未在部署允许列表或仅演示端点不可用）'}
            </Typography.Text>
            <Typography.Text>可用 key：{activeCredentialCount}</Typography.Text>
            <Typography.Text>关联模型：{selectedProvider.modelCount} 个</Typography.Text>
          </Flex>

          {!selectedProvider.allowed && (
            <Alert
              className="model-keys-provider-warning"
              description="该端点未在部署允许列表，或仅演示端点在当前部署模式不可用；不能新增 key，已有 key 仍可查看与停用。生产模式仅允许客户内网网关。"
              showIcon
              title="端点当前不可路由"
              type="warning"
            />
          )}

          {credentialsPhase === 'loading' && (
            <div aria-busy="true" aria-label="正在加载 key 列表">
              <Skeleton active paragraph={{ rows: 4 }} />
            </div>
          )}

          {credentialsPhase === 'error' && (
            <Result
              extra={
                <Button onClick={() => void loadCredentials(selectedProvider.id)} type="primary">
                  重试
                </Button>
              }
              status="error"
              subTitle={credentialsError ?? 'key 列表加载失败，请稍后重试。'}
              title="key 列表加载失败"
            />
          )}

          {credentialsPhase === 'empty' && (
            <Empty
              description="该端点还没有平台 key。新增后端点下的模型才能被调用；列表只显示末 4 位掩码。"
              image={Empty.PRESENTED_IMAGE_SIMPLE}
            >
              <Button
                disabled={!canManage || !selectedProvider.allowed}
                onClick={() => openForm('create')}
                type="primary"
              >
                新增第一把 key
              </Button>
            </Empty>
          )}

          {credentialsPhase === 'ready' && (
            <Flex className="model-keys-list" gap={8} orientation="vertical">
              {credentials.map((credential) => {
                const lastActive = isLastActiveCredential(credentials, credential.id);
                return (
                  <div className="model-key-item" key={credential.id}>
                    <div className="model-key-item-main">
                      <Flex align="center" gap={8} wrap>
                        <Typography.Text strong className="model-key-label">
                          {credential.label}
                        </Typography.Text>
                        <Typography.Text code>
                          {credentialMask(credential.maskedHint)}
                        </Typography.Text>
                        <Tag color={credentialStatusColor(credential.status)}>
                          {credentialStatusLabel(credential.status)}
                        </Tag>
                        {lastActive && <Tag color="gold">最后一把可用 key</Tag>}
                      </Flex>
                      <Flex className="model-key-meta" gap={12} wrap>
                        <span>优先级 {credential.priority}</span>
                        <span>连续失败 {credential.consecutiveFailures ?? 0} 次</span>
                        <span>创建于 {formatModelTime(credential.createdAt)}</span>
                        <span>最近使用 {formatModelTime(credential.lastUsedAt)}</span>
                        {credential.rotatedAt && (
                          <span>轮换于 {formatModelTime(credential.rotatedAt)}</span>
                        )}
                        {credential.cooldownUntil && (
                          <span>冷却至 {formatModelTime(credential.cooldownUntil)}</span>
                        )}
                      </Flex>
                    </div>
                    <Space className="model-key-actions" wrap>
                      <Button
                        aria-label={`轮换 key ${credential.label}`}
                        disabled={!canManage || busyId !== null || submitting}
                        onClick={() => openForm('rotate', credential)}
                        size="small"
                      >
                        轮换
                      </Button>
                      <Button
                        aria-label={`停用 key ${credential.label}`}
                        disabled={
                          !canManage ||
                          busyId !== null ||
                          submitting ||
                          credential.status === 'DISABLED'
                        }
                        onClick={() => setDisableTarget(credential)}
                        size="small"
                      >
                        停用
                      </Button>
                      <Button
                        aria-label={`恢复 key ${credential.label}`}
                        disabled={
                          !canManage ||
                          busyId !== null ||
                          submitting ||
                          credential.status === 'ACTIVE'
                        }
                        loading={busyId === credential.id}
                        onClick={() => void handleEnable(credential)}
                        size="small"
                      >
                        恢复
                      </Button>
                    </Space>
                  </div>
                );
              })}
            </Flex>
          )}
        </Card>
      )}

      <CredentialFormModal
        credential={formState?.credential}
        fieldErrors={fieldErrors}
        formError={formError}
        mode={formState?.mode ?? 'create'}
        onCancel={closeForm}
        onSubmit={(values) => void handleSubmit(values)}
        open={formState !== null}
        providerName={selectedProvider?.name ?? '模型端点'}
        submitting={submitting}
      />

      <Modal
        cancelText="取消"
        confirmLoading={disableTarget ? busyId === disableTarget.id : false}
        okButtonProps={{ danger: true }}
        okText="停用"
        onCancel={() => setDisableTarget(null)}
        onOk={() => {
          if (disableTarget) {
            void handleDisable(disableTarget);
          }
        }}
        open={disableTarget !== null}
        styles={{ body: { maxHeight: '62vh', overflowY: 'auto' } }}
        title={`停用 key「${disableTarget?.label ?? ''}」`}
        width={520}
      >
        {disableTarget && isLastActiveCredential(credentials, disableTarget.id) && (
          <Alert
            description="停用后该端点所有模型请求都会失败，直到新增或恢复 key；在途请求不会被中断。"
            showIcon
            style={{ marginBottom: 12 }}
            title="这是该端点最后一把可用 key"
            type="warning"
          />
        )}
        <Typography.Paragraph>
          停用后新请求不再使用该 key；在途流式请求继续，不会中断。之后可以通过“恢复”重新启用。
        </Typography.Paragraph>
      </Modal>
    </PageContainer>
  );
}
