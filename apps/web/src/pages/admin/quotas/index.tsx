import { Link } from '@umijs/max';
import { PageContainer } from '@ant-design/pro-components';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Empty,
  Flex,
  Result,
  Skeleton,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useCallback, useEffect, useRef, useState } from 'react';
import { api as defaultApi } from '@/services/api';
import { ApiError } from '@/services/api/errors';
import { authStore, useAuth } from '@/services/auth/auth-store';
import {
  QUOTA_PERMISSIONS,
  describeQuotaError,
  findQuotaWindow,
  formatQuotaCount,
  formatQuotaLimit,
  formatQuotaResetText,
  hasQuotaPermission,
  quotaApi as defaultQuotaApi,
  quotaLimitSourceLabel,
  quotaRemainingText,
  quotaTenantLabel,
  quotaUsageText,
  quotaWindowLabel,
} from '@/services/quota';
import type {
  PlatformQuotaPage,
  PlatformQuotaRow,
  QuotaApi,
  QuotaWindowKind,
  QuotaWindowState,
  TenantQuota,
  UserQuota,
  UserQuotaPage,
} from '@/services/quota';
import { QuotaLimitModal } from './quota-limit-modal';
import './quotas.css';

/** 配额页可注入依赖，便于组件测试。 */
export interface QuotasPageProps {
  /** 配额 API；默认使用同源真实客户端。 */
  api?: QuotaApi;
}

/** 页面角色模式：平台跨租户聚合或租户只读视图。 */
type QuotasMode = 'platform' | 'tenant';

/** 页面主阶段。 */
type QuotasPhase = 'loading' | 'ready' | 'empty' | 'forbidden' | 'error';

/** 窗口展示顺序；服务端固定返回日/月两项，这里仍显式声明。 */
const WINDOW_ORDER: readonly QuotaWindowKind[] = ['DAILY', 'MONTHLY'];

/** 每页大小选项；与 ADR-0022 §2.4 的 page/size 上限一致。 */
const PAGE_SIZE_OPTIONS = ['20', '50', '100'];

/**
 * 渲染单个窗口的限额、已用与来源。
 *
 * `null`/0 都显示为“不限”，并保留来源说明，避免把部署默认与显式设置混为一谈。
 *
 * @param state 窗口状态；缺失时显示占位符
 * @returns 窗口摘要
 */
function WindowSummary({ state }: { state: QuotaWindowState | undefined }) {
  if (!state) {
    return <Typography.Text type="secondary">—</Typography.Text>;
  }
  return (
    <Space orientation="vertical" size={0}>
      <Typography.Text strong>{quotaUsageText(state)}</Typography.Text>
      <Typography.Text type="secondary">
        来源：{quotaLimitSourceLabel(state.source)}
      </Typography.Text>
    </Space>
  );
}

/**
 * 配额管理页。
 *
 * 两种角色共用一个路径：
 * - 平台管理员（`platform.quota.manage`）看到跨租户聚合，并可整体替换租户日/月限额；
 * - 租户管理员（`quota.read`）只读本租户窗口与用户用量，不出现任何写入口；
 * - 员工没有权限，页面直接 403 且不请求数据。
 *
 * @param props API 注入
 * @returns 配额页面
 */
export default function QuotasPage({ api = defaultQuotaApi }: QuotasPageProps) {
  const auth = useAuth();
  const [phase, setPhase] = useState<QuotasPhase>('loading');
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [platformPage, setPlatformPage] = useState<PlatformQuotaPage | null>(null);
  const [tenantQuota, setTenantQuota] = useState<TenantQuota | null>(null);
  const [userPage, setUserPage] = useState<UserQuotaPage | null>(null);
  const [editTenant, setEditTenant] = useState<PlatformQuotaRow | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const requestSequence = useRef(0);

  const permissions = auth.session?.permissions ?? [];
  const canPlatform = hasQuotaPermission(permissions, QUOTA_PERMISSIONS.platformManage);
  const canReadTenant = hasQuotaPermission(permissions, QUOTA_PERMISSIONS.read);
  const mode: QuotasMode | null = canPlatform ? 'platform' : canReadTenant ? 'tenant' : null;

  // 管理端布局不恢复会话；直接刷新时由页面补一次会话恢复。
  useEffect(() => {
    if (auth.status === 'unknown') {
      void authStore.restore(defaultApi);
    }
  }, [auth.status]);

  /**
   * 加载当前角色的配额数据。
   *
   * 平台模式只查询租户聚合；租户模式并行读取本租户窗口与用户分页。
   * 请求序号用于丢弃快速翻页时先发后到的旧响应。
   */
  const load = useCallback(async (): Promise<void> => {
    if (!mode) {
      return;
    }
    const sequence = requestSequence.current + 1;
    requestSequence.current = sequence;
    setPhase('loading');
    setError(null);
    try {
      if (mode === 'platform') {
        const result = await api.listPlatformQuotas({ page, size });
        if (requestSequence.current !== sequence) {
          return;
        }
        setPlatformPage(result);
        setPhase(result.items.length === 0 ? 'empty' : 'ready');
      } else {
        const [quota, users] = await Promise.all([
          api.getTenantQuota(),
          api.listTenantUserQuotas({ page, size }),
        ]);
        if (requestSequence.current !== sequence) {
          return;
        }
        setTenantQuota(quota);
        setUserPage(users);
        setPhase(quota.windows.length === 0 ? 'empty' : 'ready');
      }
    } catch (loadError) {
      if (requestSequence.current !== sequence) {
        return;
      }
      const context = mode === 'platform' ? 'platform-load' : 'tenant-load';
      setError(describeQuotaError(loadError, context));
      setPhase(
        loadError instanceof ApiError && (loadError.status === 403 || loadError.status === 401)
          ? 'forbidden'
          : 'error',
      );
    }
  }, [api, mode, page, size]);

  useEffect(() => {
    void load();
  }, [load]);

  /**
   * 处理分页变化；切换每页大小时回到第一页。
   *
   * @param nextPage 目标页
   * @param nextSize 目标每页大小
   */
  const handlePageChange = (nextPage: number, nextSize: number): void => {
    if (nextSize !== size) {
      setSize(nextSize);
      setPage(1);
      return;
    }
    setPage(nextPage);
  };

  /**
   * 保存成功后原地更新平台表格行，避免整页重新加载。
   *
   * @param updated 服务端返回的最新租户配额
   */
  const handleSaved = (updated: PlatformQuotaRow): void => {
    setPlatformPage((previous) =>
      previous
        ? {
            ...previous,
            items: previous.items.map((item) =>
              item.tenantId === updated.tenantId ? { ...item, ...updated } : item,
            ),
          }
        : previous,
    );
    setEditTenant(null);
    setNotice(
      `已保存租户“${quotaTenantLabel(updated)}”的配额；下一次新建运行准入时生效，审计记录已写入。`,
    );
  };

  const platformColumns: ColumnsType<PlatformQuotaRow> = [
    {
      title: '租户',
      dataIndex: 'tenantName',
      key: 'tenant',
      width: 200,
      render: (_value, record) => (
        <Space orientation="vertical" size={0}>
          <Typography.Text strong className="quotas-cell-wrap">
            {quotaTenantLabel(record)}
          </Typography.Text>
          <Typography.Text code>{record.tenantCode}</Typography.Text>
        </Space>
      ),
    },
    ...WINDOW_ORDER.map((kind) => ({
      title: `${quotaWindowLabel(kind)}窗口（限额 / 已用）`,
      key: `${kind}-window`,
      width: 210,
      render: (_value: unknown, record: PlatformQuotaRow) => (
        <WindowSummary state={findQuotaWindow(record.windows, kind)} />
      ),
    })),
    ...WINDOW_ORDER.map((kind) => ({
      title: `${quotaWindowLabel(kind)}重置时间`,
      key: `${kind}-reset`,
      width: 210,
      render: (_value: unknown, record: PlatformQuotaRow) => (
        <span className="quotas-cell-wrap">
          {formatQuotaResetText(findQuotaWindow(record.windows, kind)?.resetsAt)}
        </span>
      ),
    })),
    {
      title: '操作',
      key: 'actions',
      width: 130,
      render: (_value, record) => (
        <Button
          aria-label={`调整限额：${quotaTenantLabel(record)}`}
          autoInsertSpace={false}
          onClick={() => setEditTenant(record)}
          size="small"
          type="link"
        >
          调整限额
        </Button>
      ),
    },
  ];

  const userColumns: ColumnsType<UserQuota> = [
    {
      title: '用户',
      dataIndex: 'displayName',
      key: 'user',
      width: 190,
      render: (_value, record) => (
        <Space orientation="vertical" size={0}>
          <Typography.Text strong className="quotas-cell-wrap">
            {record.displayName}
          </Typography.Text>
          <Typography.Text type="secondary">@{record.username}</Typography.Text>
        </Space>
      ),
    },
    ...WINDOW_ORDER.map((kind) => ({
      title: `${quotaWindowLabel(kind)}窗口（限额 / 已用）`,
      key: `${kind}-window`,
      width: 210,
      render: (_value: unknown, record: UserQuota) => (
        <WindowSummary state={findQuotaWindow(record.windows, kind)} />
      ),
    })),
    ...WINDOW_ORDER.map((kind) => ({
      title: `${quotaWindowLabel(kind)}重置时间`,
      key: `${kind}-reset`,
      width: 210,
      render: (_value: unknown, record: UserQuota) => (
        <span className="quotas-cell-wrap">
          {formatQuotaResetText(findQuotaWindow(record.windows, kind)?.resetsAt)}
        </span>
      ),
    })),
  ];

  if (auth.status === 'unknown' || auth.status === 'checking') {
    return (
      <PageContainer title="配额">
        <div aria-busy="true" aria-label="正在检查登录状态">
          <Skeleton active paragraph={{ rows: 6 }} />
        </div>
      </PageContainer>
    );
  }

  if (auth.status === 'anonymous' || auth.status === 'expired') {
    return (
      <PageContainer title="配额">
        <Result
          status="403"
          subTitle="登录状态已失效，请重新登录后再访问配额。"
          title="没有访问权限"
        />
      </PageContainer>
    );
  }

  if (auth.status === 'error') {
    return (
      <PageContainer title="配额">
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

  if (!mode) {
    return (
      <PageContainer title="配额">
        <Result
          status="403"
          subTitle="配额页仅向平台管理员与租户管理员开放；员工没有入口，也不会请求任何配额数据。"
          title="没有访问权限"
        />
      </PageContainer>
    );
  }

  const pageTitle = mode === 'platform' ? '租户配额' : '配额';
  const subTitle =
    mode === 'platform'
      ? '平台级视图：跨租户查看日/月限额与用量，并整体替换租户限额；不展示租户内用户明细。'
      : '只读视图：本租户日/月限额与用户用量；限额修改只由平台管理员执行。';

  return (
    <PageContainer subTitle={subTitle} title={pageTitle}>
      <Alert
        className="quotas-notice"
        description="限额按请求数计，按 Asia/Shanghai 自然日/自然月重置；限额为空或 0 表示不限（不是禁止调用）。重置时间带 +08:00 偏移。"
        showIcon
        title="配额口径"
        type="info"
      />

      {notice && (
        <Alert
          closable
          className="quotas-notice"
          description={notice}
          onClose={() => setNotice(null)}
          showIcon
          title="操作已生效"
          type="success"
        />
      )}

      {error && phase !== 'forbidden' && (
        <Alert
          aria-live="assertive"
          className="quotas-notice"
          description={error}
          role="alert"
          showIcon
          title="配额数据未加载"
          type="error"
        />
      )}

      {phase === 'loading' && (
        <div aria-busy="true" aria-label="正在加载配额">
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
          subTitle={error ?? '当前账号无权查看配额。'}
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
          subTitle={error ?? '配额数据加载失败，请稍后重试。'}
          title="配额数据加载失败"
        />
      )}

      {phase === 'empty' && mode === 'platform' && (
        <Empty description="平台还没有可展示的租户；请先在“租户管理”创建租户，再回到本页设置限额。">
          <Space wrap>
            <Link to="/admin/tenants">前往租户管理</Link>
            <Button onClick={() => void load()}>重新加载配额</Button>
          </Space>
        </Empty>
      )}

      {phase === 'empty' && mode === 'tenant' && (
        <Empty description="服务端没有返回本租户的日/月窗口；请稍后重试或联系平台管理员检查配额配置。">
          <Button onClick={() => void load()} type="primary">
            重新加载配额
          </Button>
        </Empty>
      )}

      {phase === 'ready' && mode === 'platform' && platformPage && (
        <Card
          className="quotas-card"
          extra={
            <Button autoInsertSpace={false} onClick={() => void load()} size="small">
              重新加载
            </Button>
          }
          title="租户配额（跨租户聚合）"
        >
          <Typography.Paragraph className="quotas-table-note" type="secondary">
            列表只含租户维度；平台不展示租户内用户明细。共 {formatQuotaCount(platformPage.total)}{' '}
            个租户。
          </Typography.Paragraph>
          <Table<PlatformQuotaRow>
            columns={platformColumns}
            dataSource={platformPage.items}
            pagination={{
              current: platformPage.page,
              pageSize: platformPage.size,
              total: platformPage.total,
              showSizeChanger: true,
              pageSizeOptions: PAGE_SIZE_OPTIONS,
              showTotal: (total) => `共 ${formatQuotaCount(total)} 个租户`,
              onChange: handlePageChange,
            }}
            rowKey="tenantId"
            scroll={{ x: 1120 }}
            size="middle"
          />
        </Card>
      )}

      {phase === 'ready' && mode === 'tenant' && tenantQuota && (
        <Space orientation="vertical" size={16} style={{ width: '100%' }}>
          <Flex className="quotas-tenant-cards" gap={16} wrap>
            {WINDOW_ORDER.map((kind) => {
              const state = findQuotaWindow(tenantQuota.windows, kind);
              return (
                <Card
                  className="quotas-window-card"
                  key={kind}
                  title={`${quotaWindowLabel(kind)}窗口`}
                >
                  {state ? (
                    <Descriptions
                      column={1}
                      items={[
                        { key: 'limit', label: '限额', children: formatQuotaLimit(state.limit) },
                        {
                          key: 'used',
                          label: '已用',
                          children: formatQuotaCount(state.used),
                        },
                        { key: 'remaining', label: '剩余', children: quotaRemainingText(state) },
                        {
                          key: 'source',
                          label: '来源',
                          children: quotaLimitSourceLabel(state.source),
                        },
                        {
                          key: 'reset',
                          label: '重置时间',
                          children: formatQuotaResetText(state.resetsAt),
                        },
                      ]}
                      size="small"
                    />
                  ) : (
                    <Typography.Text type="secondary">服务端未返回该窗口。</Typography.Text>
                  )}
                </Card>
              );
            })}
          </Flex>

          <Card
            className="quotas-card"
            extra={
              <Button autoInsertSpace={false} onClick={() => void load()} size="small">
                重新加载
              </Button>
            }
            title={`用户用量（只读）${
              userPage ? ` · 共 ${formatQuotaCount(userPage.total)} 人` : ''
            }`}
          >
            <Space className="quotas-table-note" orientation="vertical" size={4}>
              <Typography.Text type="secondary">
                本页只读：限额修改只由平台管理员执行。当前租户代码 {tenantQuota.tenantCode}。
              </Typography.Text>
              <Tag>只读</Tag>
            </Space>
            <Table<UserQuota>
              columns={userColumns}
              dataSource={userPage?.items ?? []}
              locale={{
                emptyText: (
                  <Empty
                    description="本租户还没有可展示的用户配额记录；用户创建后会自动按部署默认值显示。"
                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                  />
                ),
              }}
              pagination={{
                current: userPage?.page ?? 1,
                pageSize: userPage?.size ?? size,
                total: userPage?.total ?? 0,
                showSizeChanger: true,
                pageSizeOptions: PAGE_SIZE_OPTIONS,
                showTotal: (total) => `共 ${formatQuotaCount(total)} 人`,
                onChange: handlePageChange,
              }}
              rowKey="userId"
              scroll={{ x: 1000 }}
              size="middle"
            />
          </Card>
        </Space>
      )}

      {mode === 'platform' && (
        <QuotaLimitModal
          api={api}
          onClose={() => setEditTenant(null)}
          onSaved={handleSaved}
          tenant={editTenant}
        />
      )}
    </PageContainer>
  );
}
