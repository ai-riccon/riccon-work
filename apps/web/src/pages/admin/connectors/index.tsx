import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import {
  Alert,
  Button,
  Empty,
  Flex,
  Modal,
  Radio,
  Result,
  Select,
  Skeleton,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { TableColumnsType } from 'antd';
import type { Department } from '@/services/api/types';
import type { CSSProperties } from 'react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api as defaultApi } from '@/services/api';
import { ApiError, describeApiError } from '@/services/api/errors';
import { authStore, useAuth } from '@/services/auth/auth-store';
import type {
  Connector,
  ConnectorApi,
  ConnectorDepartmentDirectory,
  ConnectorDocumentDisposition,
  ConnectorFormSubmission,
  ConnectorStatus,
} from '@/services/connector';
import {
  CONNECTOR_PERMISSIONS,
  connectorApi as defaultConnectorApi,
  connectorDepartmentDirectory,
  connectorStatusColor,
  connectorStatusLabel,
  connectorSyncStatusColor,
  connectorSyncStatusLabel,
  connectorTestCategoryLabel,
  connectorVisibilityLabel,
  describeConnectorError,
  describeConnectorErrorCode,
  formatConnectorTime,
  formatCredentialHint,
  hasConnectorPermission,
  isConnectorSyncInProgress,
  isPlatformAdminSession,
} from '@/services/connector';
import { ConnectorFormModal, type ConnectorFormMode } from './connector-form-modal';
import { ConnectorItemsDrawer } from './connector-items-drawer';
import './connectors.css';

/** 连接器页可注入依赖，便于组件测试。 */
export interface ConnectorsPageProps {
  /** 连接器管理 API；默认使用同源真实客户端。 */
  api?: ConnectorApi;
  /** 部门目录；默认复用 `/departments`。 */
  departments?: ConnectorDepartmentDirectory;
}

/** 页面主阶段。 */
type ConnectorsPhase = 'loading' | 'ready' | 'empty' | 'forbidden' | 'not-found' | 'error';

/** 列表状态筛选；`ALL` 不传 status。 */
type StatusFilter = ConnectorStatus | 'ALL';

/** 行内操作；同一时间只允许一个操作。 */
interface BusyAction {
  id: string;
  action: 'test' | 'sync' | 'status';
}

/** 表单弹窗状态；关闭时为 null。 */
interface FormState {
  mode: ConnectorFormMode;
  connector?: Connector | null;
}

/** 状态筛选选项。 */
const STATUS_FILTER_OPTIONS: Array<{ value: StatusFilter; label: string }> = [
  { value: 'ALL', label: '全部状态' },
  { value: 'ACTIVE', label: '启用' },
  { value: 'PAUSED', label: '暂停' },
  { value: 'ERROR', label: '同步失败' },
];

/**
 * 状态与结果 Tag 的高对比配色。
 *
 * antd 6 filled Tag 默认用 preset 色阶 7 作文字、色阶 1 作背景：绿色 3.37:1、橙色 3.34:1、
 * 金色 2.76:1，12px 字号下低于 WCAG 2.1 AA 的 4.5:1（axe color-contrast）。这里把文字加深到
 * 色阶 8/9，背景保持色阶 1 并补齐同色系边框：绿色 5.44:1、橙色 5.09:1、金色 6.53:1。
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
 * @returns 已覆盖颜色的内联样式；红色/蓝色/默认色已满足 AA，返回 undefined
 */
function highContrastTagStyle(color: string): CSSProperties | undefined {
  return HIGH_CONTRAST_TAG_STYLES[color as keyof typeof HIGH_CONTRAST_TAG_STYLES];
}

/**
 * 生成删除确认文案中的文档处置说明。
 *
 * @param disposition 处置
 * @returns 中文说明
 */
function dispositionLabel(disposition: ConnectorDocumentDisposition): string {
  return disposition === 'SOFT_DELETE' ? '一并软删除已导入文档' : '保留已导入文档';
}

/**
 * 知识库连接器管理页。
 *
 * 租户管理员查看本租户 S3 兼容目录连接器，创建/部分更新/软删除，连接测试、手动同步
 * 与同步条目查询；凭据只写不读，列表与表单只出现掩码。平台管理员没有
 * `knowledge.connector.*`，按服务端语义展示 404 `RICCON-TENANT-404`，不泄露租户数据。
 *
 * @param props API 与部门目录注入
 * @returns 连接器管理页面
 */
export default function ConnectorsPage({
  api = defaultConnectorApi,
  departments: departmentDirectory = connectorDepartmentDirectory,
}: ConnectorsPageProps) {
  const auth = useAuth();
  const [phase, setPhase] = useState<ConnectorsPhase>('loading');
  const [connectors, setConnectors] = useState<Connector[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ALL');
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState<BusyAction | null>(null);
  const [formState, setFormState] = useState<FormState | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<Connector | null>(null);
  const [deleteDisposition, setDeleteDisposition] = useState<ConnectorDocumentDisposition>('KEEP');
  const [deleting, setDeleting] = useState(false);
  const [itemsConnectorId, setItemsConnectorId] = useState<string | null>(null);
  const [departments, setDepartments] = useState<Department[]>([]);
  const [departmentsLoading, setDepartmentsLoading] = useState(false);
  const [departmentsError, setDepartmentsError] = useState<string | null>(null);
  const requestSequence = useRef(0);

  const session = auth.session;
  const permissions = session?.permissions ?? [];
  const canRead = hasConnectorPermission(permissions, CONNECTOR_PERMISSIONS.read);
  const canManage = hasConnectorPermission(permissions, CONNECTOR_PERMISSIONS.manage);
  const platformAdmin = isPlatformAdminSession(session);

  // 管理端布局不恢复会话；直接刷新时由页面补一次恢复。
  useEffect(() => {
    if (auth.status === 'unknown') {
      void authStore.restore(defaultApi);
    }
  }, [auth.status]);

  /**
   * 加载连接器分页。
   *
   * @param targetPage 页码
   * @param targetSize 每页大小
   * @param targetStatus 状态筛选；ALL 不传
   */
  const loadConnectors = useCallback(
    async (targetPage: number, targetSize: number, targetStatus: StatusFilter): Promise<void> => {
      if (!canRead) {
        return;
      }
      const sequence = requestSequence.current + 1;
      requestSequence.current = sequence;
      setPhase('loading');
      setError(null);
      try {
        const result = await api.listConnectors({
          page: targetPage,
          size: targetSize,
          ...(targetStatus === 'ALL' ? {} : { status: targetStatus }),
        });
        if (requestSequence.current !== sequence) {
          return;
        }
        setConnectors(result.items);
        setTotal(result.total);
        setPhase(result.items.length === 0 ? 'empty' : 'ready');
      } catch (loadError) {
        if (requestSequence.current !== sequence) {
          return;
        }
        setError(describeConnectorError(loadError, 'list'));
        if (
          loadError instanceof ApiError &&
          (loadError.status === 403 || loadError.status === 401)
        ) {
          setPhase('forbidden');
        } else if (loadError instanceof ApiError && loadError.status === 404) {
          setPhase('not-found');
        } else {
          setPhase('error');
        }
      }
    },
    [api, canRead],
  );

  useEffect(() => {
    void loadConnectors(page, pageSize, statusFilter);
  }, [loadConnectors, page, pageSize, statusFilter]);

  /**
   * 加载部门目录；失败不阻断 TENANT/TENANT 之外的列表操作，但 DEPT 表单会提示重试。
   */
  const loadDepartments = useCallback(async (): Promise<void> => {
    if (!canRead) {
      return;
    }
    setDepartmentsLoading(true);
    setDepartmentsError(null);
    try {
      const result = await departmentDirectory.listDepartments();
      setDepartments(result.items);
    } catch (loadError) {
      setDepartmentsError(describeApiError(loadError, '部门目录加载失败，请稍后重试。'));
    } finally {
      setDepartmentsLoading(false);
    }
  }, [canRead, departmentDirectory]);

  useEffect(() => {
    void loadDepartments();
  }, [loadDepartments]);

  const departmentNameById = useMemo(
    () => new Map(departments.map((department) => [department.id, department.name])),
    [departments],
  );

  const endpointSuggestions = useMemo(
    () => connectors.map((connector) => connector.endpointId),
    [connectors],
  );

  const itemsConnector = connectors.find((connector) => connector.id === itemsConnectorId) ?? null;

  /**
   * 刷新列表；保持当前页码、每页大小与状态筛选。
   */
  const handleRefresh = (): void => {
    setNotice(null);
    setActionError(null);
    void loadConnectors(page, pageSize, statusFilter);
  };

  /**
   * 切换状态筛选并回到第一页。
   *
   * @param nextFilter 新筛选
   */
  const handleStatusFilterChange = (nextFilter: StatusFilter): void => {
    setStatusFilter(nextFilter);
    setPage(1);
  };

  /**
   * 切换分页；改变每页大小时回到第一页。
   *
   * @param nextPage 新页码
   * @param nextSize 新每页大小
   */
  const handlePaginationChange = (nextPage: number, nextSize: number): void => {
    if (nextSize !== pageSize) {
      setPageSize(nextSize);
      setPage(1);
      return;
    }
    setPage(nextPage);
  };

  /**
   * 打开创建/编辑弹窗。
   *
   * @param mode 模式
   * @param connector 编辑目标
   */
  const openForm = (mode: ConnectorFormMode, connector?: Connector | null): void => {
    setFormError(null);
    setActionError(null);
    setNotice(null);
    setFormState({ mode, connector: connector ?? null });
  };

  /**
   * 关闭表单并丢弃凭据相关的临时状态。
   */
  const closeForm = (): void => {
    setFormState(null);
    setFormError(null);
    setSubmitting(false);
  };

  /**
   * 用服务端最新连接器替换列表行。
   *
   * @param updated 最新连接器
   */
  const replaceConnector = (updated: Connector): void => {
    setConnectors((previous) =>
      previous.map((connector) => (connector.id === updated.id ? updated : connector)),
    );
  };

  /**
   * 提交创建或编辑。
   *
   * 创建使用 POST，默认可见范围必须显式；编辑使用 PATCH，只提交表单字段。
   * 若填写了凭据：创建时随创建请求写入；编辑时先保存配置，再调用凭据端点覆盖轮换。
   * 凭据明文只存在于本次调用栈，不进入页面状态、通知或错误文案。
   *
   * @param values 归一后的表单值
   */
  const handleSubmit = async (values: ConnectorFormSubmission): Promise<void> => {
    if (!formState) {
      return;
    }
    if (!values.defaultVisibility) {
      setFormError(
        '必须显式选择默认可见范围（TENANT 或 DEPT）；缺省会被服务端以 400 RICCON-KB-400 拒绝。',
      );
      return;
    }
    if (values.defaultVisibility === 'DEPT' && !values.defaultDepartmentId) {
      setFormError('默认可见范围为 DEPT 时必须选择启用部门（400 RICCON-KB-400）。');
      return;
    }
    setSubmitting(true);
    setFormError(null);
    setActionError(null);
    try {
      if (formState.mode === 'create') {
        const created = await api.createConnector({
          name: values.name,
          type: 'S3',
          endpointId: values.endpointId,
          config: values.config,
          defaultVisibility: values.defaultVisibility,
          ...(values.defaultVisibility === 'DEPT'
            ? { defaultDepartmentId: values.defaultDepartmentId ?? null }
            : {}),
          scheduleEnabled: values.scheduleEnabled,
          ...(values.credential ? { credential: values.credential } : {}),
        });
        setNotice(
          `已创建连接器「${created.name}」（默认暂停）。${
            created.credentialMaskedHint
              ? `凭据已加密保存，仅显示掩码 ${formatCredentialHint(created.credentialMaskedHint)}。`
              : '尚未写入凭据，请先填写凭据并完成连接测试。'
          }`,
        );
      } else if (formState.connector) {
        const updated = await api.updateConnector(formState.connector.id, {
          name: values.name,
          endpointId: values.endpointId,
          config: values.config,
          defaultVisibility: values.defaultVisibility,
          defaultDepartmentId:
            values.defaultVisibility === 'DEPT' ? (values.defaultDepartmentId ?? null) : null,
          scheduleEnabled: values.scheduleEnabled,
        });
        replaceConnector(updated);
        if (values.credential) {
          try {
            const rotated = await api.putConnectorCredential(
              formState.connector.id,
              values.credential,
            );
            replaceConnector(rotated);
            setNotice(
              `已保存连接器「${rotated.name}」并轮换凭据（仅显示掩码 ${formatCredentialHint(
                rotated.credentialMaskedHint,
              )}）。`,
            );
          } catch (credentialError) {
            setFormError(
              `连接器配置已保存，但凭据轮换失败：${describeConnectorError(
                credentialError,
                'credential',
              )}`,
            );
            setSubmitting(false);
            return;
          }
        } else {
          setNotice(`已保存连接器「${updated.name}」；下一次同步使用新配置。`);
        }
      }
      closeForm();
      await loadConnectors(page, pageSize, statusFilter);
    } catch (submitError) {
      setFormError(
        describeConnectorError(submitError, formState.mode === 'create' ? 'create' : 'update'),
      );
    } finally {
      setSubmitting(false);
    }
  };

  /**
   * 启用或暂停连接器。
   *
   * ERROR 状态也允许显式启用；错误码保留用于排障，下一次同步成功后由 Worker 更新。
   *
   * @param connector 目标连接器
   */
  const handleToggleStatus = async (connector: Connector): Promise<void> => {
    const nextStatus: ConnectorStatus = connector.status === 'ACTIVE' ? 'PAUSED' : 'ACTIVE';
    setBusy({ id: connector.id, action: 'status' });
    setActionError(null);
    setNotice(null);
    try {
      const updated = await api.updateConnector(connector.id, { status: nextStatus });
      replaceConnector(updated);
      setNotice(
        nextStatus === 'ACTIVE'
          ? connector.scheduleEnabled
            ? `已启用连接器「${updated.name}」；参与部署固定周期同步，仍可随时手动同步。`
            : `已启用连接器「${updated.name}」；定时同步未开启（scheduleEnabled=false），仍可随时手动同步。`
          : `已暂停连接器「${updated.name}」；定时同步停止，手动同步仍可用。`,
      );
    } catch (toggleError) {
      setActionError(describeConnectorError(toggleError, 'update'));
    } finally {
      setBusy(null);
    }
  };

  /**
   * 连接测试：失败仍为 200，按 AUTH/NETWORK/ENDPOINT/CONFIG 分类展示中文原因。
   *
   * @param connector 目标连接器
   */
  const handleTest = async (connector: Connector): Promise<void> => {
    setBusy({ id: connector.id, action: 'test' });
    setActionError(null);
    setNotice(null);
    try {
      const result = await api.testConnector(connector.id);
      if (result.success) {
        setNotice(
          `连接器「${connector.name}」连接测试成功：${result.message}（${formatConnectorTime(
            result.testedAt,
          )}）。`,
        );
      } else {
        const category = result.category
          ? `（${connectorTestCategoryLabel(result.category)} · ${result.category}）`
          : '';
        setActionError(
          `连接器「${connector.name}」连接测试失败${category}：${result.message}（${formatConnectorTime(
            result.testedAt,
          )}）。`,
        );
      }
    } catch (testError) {
      setActionError(describeConnectorError(testError, 'test'));
    } finally {
      setBusy(null);
    }
  };

  /**
   * 立即同步：受理后写本地进行中标记，Worker 完成前禁用重复触发。
   *
   * @param connector 目标连接器
   */
  const handleSync = async (connector: Connector): Promise<void> => {
    setBusy({ id: connector.id, action: 'sync' });
    setActionError(null);
    setNotice(null);
    try {
      const accepted = await api.syncConnector(connector.id);
      replaceConnector({
        ...connector,
        lastSyncStartedAt: accepted.acceptedAt,
        lastSyncFinishedAt: null,
      });
      setNotice(
        `已受理连接器「${connector.name}」的同步（202，${accepted.status}，syncId ${accepted.syncId}）。Worker 异步执行；完成前请勿重复触发，可稍后刷新或查看同步条目。`,
      );
    } catch (syncError) {
      setActionError(describeConnectorError(syncError, 'sync'));
    } finally {
      setBusy(null);
    }
  };

  /**
   * 打开删除确认并把文档处置重置为默认 KEEP。
   *
   * @param connector 目标连接器
   */
  const openDelete = (connector: Connector): void => {
    setDeleteTarget(connector);
    setDeleteDisposition('KEEP');
    setActionError(null);
    setNotice(null);
  };

  /**
   * 确认软删除；列表立即移除该行，再从服务端刷新真实分页。
   */
  const handleDelete = async (): Promise<void> => {
    if (!deleteTarget) {
      return;
    }
    setDeleting(true);
    setActionError(null);
    try {
      await api.deleteConnector(deleteTarget.id, deleteDisposition);
      const remaining = connectors.filter((connector) => connector.id !== deleteTarget.id);
      setConnectors(remaining);
      setTotal((previous) => Math.max(0, previous - 1));
      setDeleteTarget(null);
      if (itemsConnectorId === deleteTarget.id) {
        setItemsConnectorId(null);
      }
      setNotice(
        `已软删除连接器「${deleteTarget.name}」，${dispositionLabel(
          deleteDisposition,
        )}。定时同步已停止；凭据密文保留 30 天供误删恢复，之后由运维维护任务清空。`,
      );
      if (remaining.length === 0 && page > 1) {
        setPage(page - 1);
      } else {
        await loadConnectors(page, pageSize, statusFilter);
      }
    } catch (deleteError) {
      setDeleteTarget(null);
      setActionError(describeConnectorError(deleteError, 'delete'));
    } finally {
      setDeleting(false);
    }
  };

  if (auth.status === 'unknown' || auth.status === 'checking') {
    return (
      <PageContainer title="知识库连接器">
        <div aria-busy="true" aria-label="正在检查登录状态">
          <Skeleton active paragraph={{ rows: 6 }} />
        </div>
      </PageContainer>
    );
  }

  if (auth.status === 'anonymous' || auth.status === 'expired') {
    return (
      <PageContainer title="知识库连接器">
        <Result
          status="403"
          subTitle="登录状态已失效，请重新登录后再管理知识库连接器。"
          title="没有访问权限"
        />
      </PageContainer>
    );
  }

  if (auth.status === 'error') {
    return (
      <PageContainer title="知识库连接器">
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
    // 服务端对平台管理员统一返 404 RICCON-TENANT-404（避免跨租户探测）；前端保持同一语义，
    // 不用 403 暗示“申请权限后即可访问租户连接器”。
    if (platformAdmin) {
      return (
        <PageContainer title="知识库连接器">
          <Result
            status="404"
            subTitle="连接器属于租户范围，平台管理员不能查看或管理租户连接器（RICCON-TENANT-404）。"
            title="连接器不存在"
          />
        </PageContainer>
      );
    }
    return (
      <PageContainer title="知识库连接器">
        <Result
          status="403"
          subTitle="知识库连接器仅向租户管理员开放（RICCON-AUTH-403）；普通员工无权查看或修改。"
          title="没有访问权限"
        />
      </PageContainer>
    );
  }

  const columns: TableColumnsType<Connector> = [
    {
      title: '名称',
      dataIndex: 'name',
      key: 'name',
      fixed: 'left',
      width: 210,
      render: (name: string) => <Typography.Text strong>{name}</Typography.Text>,
    },
    {
      title: '类型',
      dataIndex: 'type',
      key: 'type',
      width: 80,
      render: (type: string) => <Tag color="blue">{type}</Tag>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 110,
      render: (status: ConnectorStatus) => (
        <Tag
          color={connectorStatusColor(status)}
          style={highContrastTagStyle(connectorStatusColor(status))}
        >
          {connectorStatusLabel(status)}
        </Tag>
      ),
    },
    {
      title: '端点 ID',
      dataIndex: 'endpointId',
      key: 'endpointId',
      width: 150,
      render: (value: string) => <Typography.Text code>{value}</Typography.Text>,
    },
    {
      title: '默认可见',
      dataIndex: 'defaultVisibility',
      key: 'defaultVisibility',
      width: 150,
      render: (_: unknown, connector: Connector) => {
        if (connector.defaultVisibility !== 'DEPT') {
          return connectorVisibilityLabel(connector.defaultVisibility);
        }
        const name = connector.defaultDepartmentId
          ? departmentNameById.get(connector.defaultDepartmentId)
          : undefined;
        return (
          <span>
            {connectorVisibilityLabel(connector.defaultVisibility)}：
            {name ?? connector.defaultDepartmentId?.slice(0, 8) ?? '未设置'}
          </span>
        );
      },
    },
    {
      title: '定时同步',
      dataIndex: 'scheduleEnabled',
      key: 'scheduleEnabled',
      width: 170,
      render: (enabled: boolean, connector: Connector) =>
        enabled ? (
          <span>
            是
            {connector.nextSyncAt ? (
              <Typography.Text type="secondary">
                （下次 {formatConnectorTime(connector.nextSyncAt)}）
              </Typography.Text>
            ) : null}
          </span>
        ) : (
          '否'
        ),
    },
    {
      title: '最近同步',
      dataIndex: 'lastSyncStatus',
      key: 'lastSyncStatus',
      width: 120,
      render: (status: Connector['lastSyncStatus']) => (
        <Tag
          color={connectorSyncStatusColor(status)}
          style={highContrastTagStyle(connectorSyncStatusColor(status))}
        >
          {connectorSyncStatusLabel(status)}
        </Tag>
      ),
    },
    {
      title: 'list 完整',
      dataIndex: 'lastSyncComplete',
      key: 'lastSyncComplete',
      width: 100,
      render: (complete: boolean) => (complete ? '是' : '否'),
    },
    {
      title: '最近成功',
      dataIndex: 'lastSuccessAt',
      key: 'lastSuccessAt',
      width: 190,
      render: (value: string | null | undefined) => formatConnectorTime(value),
    },
    {
      title: '失败码',
      dataIndex: 'lastErrorCode',
      key: 'lastErrorCode',
      width: 220,
      render: (code: string | null | undefined) =>
        code ? (
          <span>
            {describeConnectorErrorCode(code)}（<Typography.Text code>{code}</Typography.Text>）
          </span>
        ) : (
          '—'
        ),
    },
    {
      title: '凭据',
      dataIndex: 'credentialMaskedHint',
      key: 'credentialMaskedHint',
      width: 120,
      render: (hint: string | null | undefined) => (
        <Typography.Text code>{formatCredentialHint(hint)}</Typography.Text>
      ),
    },
    {
      title: '操作',
      key: 'actions',
      fixed: 'right',
      width: 330,
      render: (_: unknown, connector: Connector) => {
        const syncing = isConnectorSyncInProgress(connector);
        return (
          <Space className="connectors-actions" size={4} wrap>
            <Button
              aria-label={`测试 ${connector.name}`}
              autoInsertSpace={false}
              disabled={!canManage || busy !== null || deleting}
              loading={busy?.id === connector.id && busy.action === 'test'}
              onClick={() => void handleTest(connector)}
              size="small"
            >
              测试
            </Button>
            <Button
              aria-label={`${syncing ? '同步中' : '同步'} ${connector.name}`}
              autoInsertSpace={false}
              disabled={!canManage || busy !== null || deleting || syncing}
              loading={busy?.id === connector.id && busy.action === 'sync'}
              onClick={() => void handleSync(connector)}
              size="small"
            >
              {syncing ? '同步中' : '同步'}
            </Button>
            <Button
              aria-label={`条目 ${connector.name}`}
              autoInsertSpace={false}
              disabled={deleting}
              onClick={() => setItemsConnectorId(connector.id)}
              size="small"
            >
              条目
            </Button>
            <Button
              aria-label={`编辑 ${connector.name}`}
              autoInsertSpace={false}
              disabled={!canManage || busy !== null || deleting}
              onClick={() => openForm('edit', connector)}
              size="small"
            >
              编辑
            </Button>
            <Button
              aria-label={`${connector.status === 'ACTIVE' ? '暂停' : '启用'} ${connector.name}`}
              autoInsertSpace={false}
              disabled={!canManage || busy !== null || deleting}
              loading={busy?.id === connector.id && busy.action === 'status'}
              onClick={() => void handleToggleStatus(connector)}
              size="small"
            >
              {connector.status === 'ACTIVE' ? '暂停' : '启用'}
            </Button>
            <Button
              aria-label={`删除 ${connector.name}`}
              autoInsertSpace={false}
              danger
              disabled={!canManage || busy !== null || deleting}
              onClick={() => openDelete(connector)}
              size="small"
            >
              删除
            </Button>
          </Space>
        );
      },
    },
  ];

  const showTableWithData =
    connectors.length > 0 &&
    (phase === 'ready' || (phase === 'loading' && requestSequence.current > 0));

  return (
    <PageContainer
      subTitle="S3 兼容目录连接器；端点只来自部署允许列表，凭据只写不读，导入文档按默认可见范围授权。"
      title="知识库连接器"
    >
      <Alert
        description="连接器创建后默认暂停；同步由 Worker 异步执行，源端删除只在 list 完整时传播。凭据只显示掩码，软删除后密文保留 30 天。"
        showIcon
        title="连接器边界与安全"
        type="info"
      />

      {!canManage && (
        <Alert
          description="当前账号只有查看权限；新建、编辑、测试、同步、启停与删除已禁用。"
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

      {phase === 'forbidden' && (
        <Result
          extra={
            <Button
              onClick={() => void loadConnectors(page, pageSize, statusFilter)}
              type="primary"
            >
              重试
            </Button>
          }
          status="403"
          subTitle={error ?? '当前账号无权查看知识库连接器（RICCON-AUTH-403）。'}
          title="没有访问权限"
        />
      )}

      {phase === 'not-found' && (
        <Result
          extra={
            <Button
              onClick={() => void loadConnectors(page, pageSize, statusFilter)}
              type="primary"
            >
              重试
            </Button>
          }
          status="404"
          subTitle={
            error ??
            '连接器不存在或不属于当前租户（RICCON-TENANT-404）；平台管理员不能查看租户连接器。'
          }
          title="连接器不存在"
        />
      )}

      {phase === 'error' && (
        <Result
          extra={
            <Button
              onClick={() => void loadConnectors(page, pageSize, statusFilter)}
              type="primary"
            >
              重试
            </Button>
          }
          status="error"
          subTitle={error ?? '连接器列表加载失败，请稍后重试。'}
          title="连接器列表加载失败"
        />
      )}

      {(phase === 'loading' || phase === 'empty' || showTableWithData) && (
        <>
          <Flex align="center" className="connectors-toolbar" gap={8} justify="space-between" wrap>
            <Flex align="center" gap={8} wrap>
              <Select<StatusFilter>
                aria-label="连接器状态筛选"
                onChange={handleStatusFilterChange}
                options={STATUS_FILTER_OPTIONS}
                style={{ minWidth: 160 }}
                value={statusFilter}
              />
              <Button
                icon={<ReloadOutlined />}
                onClick={handleRefresh}
                disabled={phase === 'loading' && connectors.length === 0}
              >
                刷新
              </Button>
              <Typography.Text type="secondary">共 {total} 个连接器</Typography.Text>
            </Flex>
            <Button
              disabled={!canManage}
              icon={<PlusOutlined />}
              onClick={() => openForm('create')}
              type="primary"
            >
              新建连接器
            </Button>
          </Flex>

          {phase === 'loading' && connectors.length === 0 && (
            <div aria-busy="true" aria-label="正在加载连接器列表" style={{ marginTop: 16 }}>
              <Skeleton active paragraph={{ rows: 8 }} />
            </div>
          )}

          {phase === 'empty' && (
            <Empty
              description={
                statusFilter === 'ALL'
                  ? '还没有连接器。新建后请先写入凭据并完成连接测试，再启用或立即同步。'
                  : '当前状态筛选下没有连接器。'
              }
              style={{ marginTop: 24 }}
            >
              {canManage && statusFilter === 'ALL' && (
                <Button onClick={() => openForm('create')} type="primary">
                  新建第一个连接器
                </Button>
              )}
            </Empty>
          )}

          {showTableWithData && (
            <Table<Connector>
              className="connectors-table"
              columns={columns}
              dataSource={connectors}
              loading={phase === 'loading'}
              pagination={{
                current: page,
                pageSize,
                total,
                showSizeChanger: true,
                pageSizeOptions: [10, 20, 50],
                onChange: handlePaginationChange,
                showTotal: (count) => `共 ${count} 条`,
              }}
              rowKey="id"
              scroll={{ x: 1600 }}
              size="small"
              style={{ marginTop: 16 }}
            />
          )}
        </>
      )}

      <ConnectorFormModal
        connector={formState?.connector}
        departments={departments}
        departmentsError={departmentsError}
        departmentsLoading={departmentsLoading}
        endpointSuggestions={endpointSuggestions}
        formError={formError}
        mode={formState?.mode ?? 'create'}
        onCancel={closeForm}
        onReloadDepartments={() => void loadDepartments()}
        onSubmit={(values) => void handleSubmit(values)}
        open={formState !== null}
        submitting={submitting}
      />

      <ConnectorItemsDrawer
        api={api}
        connector={itemsConnector}
        onClose={() => setItemsConnectorId(null)}
      />

      <Modal
        cancelText="取消"
        confirmLoading={deleting}
        destroyOnHidden
        okButtonProps={{ danger: true }}
        okText="确认删除"
        onCancel={() => setDeleteTarget(null)}
        onOk={() => void handleDelete()}
        open={deleteTarget !== null}
        styles={{ body: { maxHeight: '62vh', overflowY: 'auto' } }}
        title={`删除连接器「${deleteTarget?.name ?? ''}」`}
        width="min(560px, 100vw)"
      >
        <Alert
          description="删除是软删除：立即停止定时同步并从列表消失；凭据密文保留 30 天供误删恢复，之后由运维维护任务清空。已导入文档的原文不会删除。"
          showIcon
          style={{ marginBottom: 16 }}
          title="删除影响"
          type="warning"
        />
        <Typography.Paragraph>请选择已导入文档的处置方式：</Typography.Paragraph>
        <Radio.Group
          aria-label="已导入文档处置"
          onChange={(event) =>
            setDeleteDisposition(event.target.value as ConnectorDocumentDisposition)
          }
          options={[
            {
              value: 'KEEP',
              label: 'KEEP：保留已导入文档（默认）',
            },
            {
              value: 'SOFT_DELETE',
              label: 'SOFT_DELETE：一并软删除已导入文档并清理索引',
            },
          ]}
          value={deleteDisposition}
        />
        {deleteDisposition === 'SOFT_DELETE' && (
          <Alert
            description="已导入文档会立即软删除、从知识库列表消失并投递索引 DELETE；此操作不在线恢复，请确认后再继续。"
            showIcon
            style={{ marginTop: 16 }}
            title="将一并删除文档"
            type="error"
          />
        )}
      </Modal>
    </PageContainer>
  );
}
