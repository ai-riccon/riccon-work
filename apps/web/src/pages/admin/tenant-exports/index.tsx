import { PageContainer } from '@ant-design/pro-components';
import { useNavigate } from '@umijs/max';
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
  Table,
  Tag,
  Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useCallback, useEffect, useRef, useState } from 'react';
import { api as defaultApi } from '@/services/api';
import { ApiError } from '@/services/api/errors';
import { auditApi as defaultAuditApi } from '@/services/audit';
import type { AuditApi } from '@/services/audit/api';
import { AUDIT_PERMISSIONS, PLATFORM_TENANT_ID, hasPermission } from '@/services/audit/access';
import {
  canDecideExport,
  describeAuditError,
  describeAuditFilter,
  exportDownloadState,
  exportStatusColor,
  exportStatusLabel,
  formatAuditTime,
  formatRowCount,
} from '@/services/audit/format';
import type {
  AuditExportDecision,
  AuditExportRequest,
  AuditExportStatus,
} from '@/services/audit/types';
import { saveExportFile } from '@/services/audit/download';
import { authStore, useAuth } from '@/services/auth/auth-store';
import { DecisionModal } from './decision-modal';
import { ExportDetailDrawer } from './export-detail-drawer';
import './tenant-exports.css';

/** 导出列表页参数。 */
export interface TenantExportsPageProps {
  /** 可注入的 API，便于组件测试。 */
  api?: AuditApi;
}

/** 列表主阶段。 */
type ExportsPhase = 'loading' | 'ready' | 'empty' | 'forbidden' | 'error';

/** 状态过滤选项。 */
const STATUS_OPTIONS: Array<{ value: AuditExportStatus; label: string }> = [
  { value: 'PENDING_APPROVAL', label: '待审批' },
  { value: 'APPROVED', label: '已批准' },
  { value: 'REJECTED', label: '已拒绝' },
  { value: 'EXPIRED', label: '已过期' },
];

/** 审批弹窗状态。 */
interface DecisionState {
  request: AuditExportRequest;
  decision: AuditExportDecision;
}

/**
 * 审计导出申请列表与审批页。
 *
 * 申请人可查看状态、详情并在批准后下载；审批人不能审批自己的请求。
 * 平台租户自身导出在 M10 不支持，页面直接给出 403 说明且不请求导出接口。
 *
 * @param props API 注入
 * @returns 审计导出页面
 */
export default function TenantExportsPage({ api = defaultAuditApi }: TenantExportsPageProps) {
  const auth = useAuth();
  const navigate = useNavigate();
  const [phase, setPhase] = useState<ExportsPhase>('loading');
  const [requests, setRequests] = useState<AuditExportRequest[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [statusFilter, setStatusFilter] = useState<AuditExportStatus | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [selected, setSelected] = useState<AuditExportRequest | null>(null);
  const [decisionState, setDecisionState] = useState<DecisionState | null>(null);
  const [downloadingId, setDownloadingId] = useState<string | null>(null);
  const requestSequence = useRef(0);

  const session = auth.session;
  const currentUserId = session?.user.id ?? null;
  const permissions = session?.permissions ?? [];
  const canRead = hasPermission(permissions, AUDIT_PERMISSIONS.read);
  const isPlatformTenant = session?.tenant.id === PLATFORM_TENANT_ID;

  // 管理端布局不恢复会话；直接刷新时由页面补一次恢复。
  useEffect(() => {
    if (auth.status === 'unknown') {
      void authStore.restore(defaultApi);
    }
  }, [auth.status]);

  /**
   * 加载导出申请列表。
   */
  const load = useCallback(async (): Promise<void> => {
    if (!canRead || isPlatformTenant) {
      return;
    }
    const sequence = requestSequence.current + 1;
    requestSequence.current = sequence;
    setPhase('loading');
    setError(null);
    try {
      const result = await api.listExports({ status: statusFilter, page, size });
      if (requestSequence.current !== sequence) {
        return;
      }
      setRequests(result.items);
      setTotal(result.total);
      setPhase(result.items.length === 0 ? 'empty' : 'ready');
    } catch (loadError) {
      if (requestSequence.current !== sequence) {
        return;
      }
      const message = describeAuditError(loadError, 'query');
      setError(message);
      setPhase(
        loadError instanceof ApiError && (loadError.status === 403 || loadError.status === 401)
          ? 'forbidden'
          : 'error',
      );
    }
  }, [api, canRead, isPlatformTenant, page, size, statusFilter]);

  useEffect(() => {
    void load();
  }, [load]);

  /**
   * 下载导出 CSV；仅申请人且已批准未过期时页面会显示入口。
   *
   * @param request 导出请求
   */
  const handleDownload = async (request: AuditExportRequest): Promise<void> => {
    setDownloadingId(request.id);
    setActionError(null);
    try {
      const file = await api.downloadExport(request.id);
      if (!saveExportFile(file.blob, file.filename)) {
        setActionError('当前浏览器不支持直接保存文件，请更换浏览器后重试。');
        return;
      }
      setNotice(`导出文件 ${file.filename} 已开始下载。`);
    } catch (downloadError) {
      setActionError(describeAuditError(downloadError, 'download'));
      if (
        downloadError instanceof ApiError &&
        (downloadError.status === 409 || downloadError.status === 410)
      ) {
        void load();
      }
    } finally {
      setDownloadingId(null);
    }
  };

  /**
   * 处理审批成功。
   *
   * @param decided 审批后的请求
   */
  const handleDecided = (decided: AuditExportRequest): void => {
    setNotice(
      decided.status === 'APPROVED'
        ? '导出申请已批准；申请人可在有效期内下载。'
        : '导出申请已拒绝；申请人会看到拒绝原因。',
    );
    setActionError(null);
    void load();
  };

  /**
   * 打开审批弹窗。
   *
   * @param request 导出请求
   * @param decision 决定
   */
  const openDecision = (request: AuditExportRequest, decision: AuditExportDecision): void => {
    setDecisionState({ request, decision });
  };

  const columns: ColumnsType<AuditExportRequest> = [
    {
      title: '申请时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 180,
      render: (value: string) => formatAuditTime(value),
    },
    {
      title: '申请人',
      dataIndex: 'requestedBy',
      key: 'requestedBy',
      width: 190,
      ellipsis: true,
      render: (_value, record) =>
        record.requestedByDisplayName
          ? `${record.requestedByDisplayName}（${record.requestedBy}）`
          : record.requestedBy,
    },
    {
      title: '条件摘要',
      dataIndex: 'filter',
      key: 'filter',
      width: 340,
      ellipsis: true,
      render: (_value, record) => (
        <span title={describeAuditFilter(record.filter)}>{describeAuditFilter(record.filter)}</span>
      ),
    },
    {
      title: '预计行数',
      dataIndex: 'estimatedRowCount',
      key: 'estimatedRowCount',
      width: 110,
      align: 'right',
      render: (value: number) => formatRowCount(value),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (value: AuditExportStatus) => (
        <Tag color={exportStatusColor(value)}>{exportStatusLabel(value)}</Tag>
      ),
    },
    {
      title: '下载截止',
      dataIndex: 'expiresAt',
      key: 'expiresAt',
      width: 180,
      render: (value: string | null | undefined) => formatAuditTime(value),
    },
    {
      title: '操作',
      key: 'actions',
      width: 300,
      fixed: 'right',
      render: (_value, record) => renderActions(record),
    },
  ];

  /**
   * 渲染单行操作：详情、下载与审批。
   *
   * @param record 导出请求
   * @returns 操作节点
   */
  function renderActions(record: AuditExportRequest) {
    const download = exportDownloadState(record, currentUserId);
    const decide = canDecideExport(record, currentUserId, permissions, AUDIT_PERMISSIONS.approve);
    return (
      <Space size={4} wrap>
        <Button
          aria-label={`查看详情：导出 ${record.id}`}
          onClick={() => setSelected(record)}
          size="small"
          type="link"
        >
          查看详情
        </Button>
        {download.canDownload ? (
          <Button
            aria-label={`下载 CSV：导出 ${record.id}`}
            loading={downloadingId === record.id}
            onClick={() => {
              void handleDownload(record);
            }}
            size="small"
            type="primary"
          >
            下载 CSV
          </Button>
        ) : (
          <Typography.Text className="export-download-reason" type="secondary">
            {download.reason}
          </Typography.Text>
        )}
        {record.status === 'PENDING_APPROVAL' &&
          (decide.allowed ? (
            <>
              <Button
                aria-label={`批准导出申请 ${record.id}`}
                autoInsertSpace={false}
                onClick={() => openDecision(record, 'APPROVE')}
                size="small"
                type="primary"
              >
                批准
              </Button>
              <Button
                aria-label={`拒绝导出申请 ${record.id}`}
                autoInsertSpace={false}
                danger
                onClick={() => openDecision(record, 'REJECT')}
                size="small"
              >
                拒绝
              </Button>
            </>
          ) : (
            <Typography.Text className="export-download-reason" type="secondary">
              {record.requestedBy === currentUserId
                ? '申请人不能审批自己的导出申请'
                : decide.reason}
            </Typography.Text>
          ))}
      </Space>
    );
  }

  if (auth.status === 'unknown' || auth.status === 'checking') {
    return (
      <PageContainer title="审计导出">
        <div aria-busy="true" aria-label="正在检查登录状态">
          <Skeleton active paragraph={{ rows: 6 }} />
        </div>
      </PageContainer>
    );
  }

  if (auth.status === 'anonymous' || auth.status === 'expired') {
    return (
      <PageContainer title="审计导出">
        <Result
          status="403"
          subTitle="登录状态已失效，请重新登录后再访问审计导出。"
          title="没有访问权限"
        />
      </PageContainer>
    );
  }

  if (auth.status === 'error') {
    return (
      <PageContainer title="审计导出">
        <Result
          status="error"
          subTitle={auth.error ?? '无法确认登录状态，请检查网络后重试。'}
          title="无法确认登录状态"
          extra={
            <Button onClick={() => void authStore.restore(defaultApi)} type="primary">
              重试
            </Button>
          }
        />
      </PageContainer>
    );
  }

  if (isPlatformTenant) {
    return (
      <PageContainer title="审计导出">
        <Result
          status="403"
          subTitle="平台租户只有平台管理员，无法满足“申请人不得自批”。平台管理员可在租户详情审批单管理员租户的导出申请，但不能申请或下载平台租户自身导出。"
          title="平台租户不支持导出"
        />
      </PageContainer>
    );
  }

  if (!canRead) {
    return (
      <PageContainer title="审计导出">
        <Result
          status="403"
          subTitle="审计导出仅向租户管理员开放；普通员工无权查看或发起导出。"
          title="没有访问权限"
        />
      </PageContainer>
    );
  }

  return (
    <PageContainer subTitle="导出需另一位管理员批准；申请人不能审批自己的请求" title="审计导出">
      <Alert
        action={
          <Button onClick={() => navigate('/admin/audit')} size="small" type="primary">
            去审计查询
          </Button>
        }
        description="在审计查询页筛选条件后申请导出；批准后按平台策略在有效期内下载。导出行为、审批人、范围与行数都会写入审计。"
        showIcon
        style={{ marginBottom: 16 }}
        title="导出流程"
        type="info"
      />

      {notice && (
        <Alert
          closable
          description={notice}
          onClose={() => setNotice(null)}
          showIcon
          style={{ marginBottom: 16 }}
          title="操作完成"
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
          style={{ marginBottom: 16 }}
          title="操作未完成"
          type="error"
        />
      )}

      <Card
        className="export-list-card"
        extra={
          <Flex align="center" gap={8} wrap>
            <Typography.Text>状态</Typography.Text>
            <Select<AuditExportStatus>
              allowClear
              aria-label="导出状态筛选"
              onChange={(value) => {
                setStatusFilter(value);
                setPage(1);
              }}
              options={STATUS_OPTIONS}
              placeholder="全部状态"
              style={{ minWidth: 140 }}
              value={statusFilter}
            />
            <Button onClick={() => void load()} size="small">
              刷新
            </Button>
          </Flex>
        }
        title="导出申请"
      >
        {phase === 'loading' && (
          <div aria-busy="true" aria-label="正在加载导出申请">
            <Skeleton active paragraph={{ rows: 8 }} />
          </div>
        )}

        {phase === 'forbidden' && (
          <Result
            status="403"
            subTitle={error ?? '当前账号无权查看审计导出。'}
            title="没有访问权限"
            extra={
              <Button onClick={() => void load()} type="primary">
                重试
              </Button>
            }
          />
        )}

        {phase === 'error' && (
          <Result
            status="error"
            subTitle={error ?? '导出列表加载失败，请稍后重试。'}
            title="导出列表加载失败"
            extra={
              <Button onClick={() => void load()} type="primary">
                重试
              </Button>
            }
          />
        )}

        {phase === 'empty' && (
          <Empty description="当前没有导出申请。到审计查询页筛选条件后申请导出。">
            <Button onClick={() => navigate('/admin/audit')} type="primary">
              去审计查询
            </Button>
          </Empty>
        )}

        {phase === 'ready' && (
          <Table<AuditExportRequest>
            columns={columns}
            dataSource={requests}
            pagination={{
              current: page,
              pageSize: size,
              total,
              showSizeChanger: true,
              pageSizeOptions: ['20', '50', '100'],
              showTotal: () => `共 ${total} 条`,
              onChange: (nextPage, nextSize) => {
                setPage(nextPage);
                setSize(nextSize);
              },
            }}
            rowKey="id"
            scroll={{ x: 1300 }}
            size="middle"
          />
        )}
      </Card>

      <ExportDetailDrawer
        currentUserId={currentUserId}
        onClose={() => setSelected(null)}
        request={selected}
      />

      <DecisionModal
        decision={decisionState?.decision ?? null}
        onClose={() => setDecisionState(null)}
        onDecided={handleDecided}
        open={decisionState !== null}
        request={decisionState?.request ?? null}
        submit={(decision, comment) => {
          const target = decisionState?.request;
          if (!target) {
            throw new Error('没有待审批的导出请求');
          }
          return api.decideExport(target.id, { decision, comment });
        }}
      />
    </PageContainer>
  );
}
