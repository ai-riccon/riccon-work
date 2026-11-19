import { PageContainer } from '@ant-design/pro-components';
import { useNavigate, useParams } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Empty,
  Form,
  Input,
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
import { authStore, useAuth } from '@/services/auth/auth-store';
import { DecisionModal } from './decision-modal';
import { ExportDetailDrawer } from './export-detail-drawer';
import './tenant-exports.css';

/** 平台审批页参数。 */
export interface PlatformAuditExportsPageProps {
  /** 可注入的 API，便于组件测试。 */
  api?: AuditApi;
  /** 目标租户 ID；不传时从路由参数或 `tenantId` 查询参数读取。 */
  tenantId?: string;
  /** 可注入的导航，便于组件测试。 */
  navigate?: (to: string) => void;
}

/** 页面主阶段。 */
type PlatformPhase =
  'idle' | 'loading' | 'ready' | 'empty' | 'forbidden' | 'notFound' | 'multiAdmin' | 'error';

/** 租户 ID 格式，与 OpenAPI `TenantId` 一致。 */
const TENANT_CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/;

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
 * 从地址栏读取 `tenantId` 查询参数。
 *
 * 平台租户列表页可以把审批入口链接到
 * `/admin/tenants/<tenantCode>/audit-exports`，也允许用查询参数直达。
 *
 * @returns 租户 ID 或 undefined
 */
function readTenantIdFromQuery(): string | undefined {
  if (typeof window === 'undefined') {
    return undefined;
  }
  return new URLSearchParams(window.location.search).get('tenantId') ?? undefined;
}

/**
 * 平台管理员审计导出审批页。
 *
 * 只处理单管理员租户的导出申请元数据：可查看申请人、过滤条件、预计行数与状态，
 * 可批准/拒绝，但**不返回审计内容、不提供下载**。多管理员租户由服务端返回 409，
 * 页面明确要求回到租户内由另一位管理员审批。
 *
 * @param props API、租户 ID 与导航注入
 * @returns 平台审批页面
 */
export default function PlatformAuditExportsPage({
  api = defaultAuditApi,
  tenantId: injectedTenantId,
  navigate: injectedNavigate,
}: PlatformAuditExportsPageProps) {
  const auth = useAuth();
  const params = useParams<{ tenantId?: string }>();
  const umiNavigate = useNavigate();
  const navigate = injectedNavigate ?? umiNavigate;
  const [manualTenantId, setManualTenantId] = useState<string | null>(null);
  const tenantId =
    injectedTenantId ?? params.tenantId ?? readTenantIdFromQuery() ?? manualTenantId ?? '';
  const [lookupForm] = Form.useForm<{ tenantId: string }>();
  const [phase, setPhase] = useState<PlatformPhase>(tenantId ? 'loading' : 'idle');
  const [requests, setRequests] = useState<AuditExportRequest[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [statusFilter, setStatusFilter] = useState<AuditExportStatus | undefined>(
    'PENDING_APPROVAL',
  );
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [selected, setSelected] = useState<AuditExportRequest | null>(null);
  const [decisionState, setDecisionState] = useState<DecisionState | null>(null);
  const requestSequence = useRef(0);

  const session = auth.session;
  const currentUserId = session?.user.id ?? null;
  const permissions = session?.permissions ?? [];
  const canApprove = hasPermission(permissions, AUDIT_PERMISSIONS.platformApprove);

  // 管理端布局不恢复会话；直接刷新时由页面补一次恢复。
  useEffect(() => {
    if (auth.status === 'unknown') {
      void authStore.restore(defaultApi);
    }
  }, [auth.status]);

  /**
   * 加载目标租户导出申请元数据。
   */
  const load = useCallback(async (): Promise<void> => {
    if (!canApprove || !tenantId || tenantId === PLATFORM_TENANT_ID) {
      return;
    }
    const sequence = requestSequence.current + 1;
    requestSequence.current = sequence;
    setPhase('loading');
    setError(null);
    try {
      const result = await api.listPlatformExports(tenantId, {
        status: statusFilter,
        page,
        size,
      });
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
      const message = describeAuditError(loadError, 'platform-list');
      setError(message);
      if (loadError instanceof ApiError) {
        if (loadError.code === 'RICCON-AUDIT-409') {
          setPhase('multiAdmin');
        } else if (loadError.status === 404) {
          setPhase('notFound');
        } else if (loadError.status === 403 || loadError.status === 401) {
          setPhase('forbidden');
        } else {
          setPhase('error');
        }
      } else {
        setPhase('error');
      }
    }
  }, [api, canApprove, page, size, statusFilter, tenantId]);

  useEffect(() => {
    void load();
  }, [load]);

  /**
   * 处理审批成功。
   *
   * @param decided 审批后的请求
   */
  const handleDecided = (decided: AuditExportRequest): void => {
    setNotice(
      decided.status === 'APPROVED'
        ? '已批准目标租户的导出申请；平台管理员不能下载该文件。'
        : '已拒绝目标租户的导出申请；申请人会看到拒绝原因。',
    );
    void load();
  };

  /**
   * 提交租户 ID，进入目标租户审批列表。
   *
   * @param values 租户 ID
   */
  const handleLookup = (values: { tenantId: string }): void => {
    const target = values.tenantId.trim();
    setManualTenantId(target);
    setPage(1);
    setPhase('loading');
    navigate(`/admin/tenants/${encodeURIComponent(target)}/audit-exports`);
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
      width: 360,
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
      title: '审批人 / 截止',
      key: 'decision',
      width: 210,
      render: (_value, record) =>
        record.approvedBy ? (
          <span>
            {record.approvedByDisplayName ?? record.approvedBy}
            <br />
            <Typography.Text type="secondary">{formatAuditTime(record.expiresAt)}</Typography.Text>
          </span>
        ) : (
          '—'
        ),
    },
    {
      title: '操作',
      key: 'actions',
      width: 260,
      fixed: 'right',
      render: (_value, record) => renderActions(record),
    },
  ];

  /**
   * 渲染单行操作：详情与审批；平台模式没有下载入口。
   *
   * @param record 导出请求
   * @returns 操作节点
   */
  function renderActions(record: AuditExportRequest) {
    const decide = canDecideExport(
      record,
      currentUserId,
      permissions,
      AUDIT_PERMISSIONS.platformApprove,
    );
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
        {record.status === 'PENDING_APPROVAL' &&
          (decide.allowed ? (
            <>
              <Button
                aria-label={`批准导出申请 ${record.id}`}
                autoInsertSpace={false}
                onClick={() => setDecisionState({ request: record, decision: 'APPROVE' })}
                size="small"
                type="primary"
              >
                批准
              </Button>
              <Button
                aria-label={`拒绝导出申请 ${record.id}`}
                autoInsertSpace={false}
                danger
                onClick={() => setDecisionState({ request: record, decision: 'REJECT' })}
                size="small"
              >
                拒绝
              </Button>
            </>
          ) : (
            <Typography.Text className="export-download-reason" type="secondary">
              {decide.reason}
            </Typography.Text>
          ))}
      </Space>
    );
  }

  if (auth.status === 'unknown' || auth.status === 'checking') {
    return (
      <PageContainer title="平台审计导出审批">
        <div aria-busy="true" aria-label="正在检查登录状态">
          <Skeleton active paragraph={{ rows: 6 }} />
        </div>
      </PageContainer>
    );
  }

  if (auth.status === 'anonymous' || auth.status === 'expired') {
    return (
      <PageContainer title="平台审计导出审批">
        <Result
          status="403"
          subTitle="登录状态已失效，请重新登录后再访问平台审批。"
          title="没有访问权限"
        />
      </PageContainer>
    );
  }

  if (auth.status === 'error') {
    return (
      <PageContainer title="平台审计导出审批">
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

  if (!canApprove) {
    return (
      <PageContainer title="平台审计导出审批">
        <Result
          status="403"
          subTitle="只有平台管理员可以审批单管理员租户的导出申请；平台审批只处理元数据。"
          title="没有访问权限"
        />
      </PageContainer>
    );
  }

  if (!tenantId) {
    return (
      <PageContainer
        subTitle="仅限单管理员租户；只审批元数据，不读取审计内容、不下载导出"
        title="平台审计导出审批"
      >
        <Card className="platform-audit-lookup" title="选择目标租户">
          <Typography.Paragraph type="secondary">
            输入租户代码后加载待审批导出。目标租户如有多个启用的租户管理员，系统会拒绝平台审批，
            必须回到租户内由另一位管理员处理。
          </Typography.Paragraph>
          <Form form={lookupForm} layout="vertical" onFinish={handleLookup} requiredMark="optional">
            <Form.Item
              label="租户代码"
              name="tenantId"
              rules={[
                { required: true, message: '请输入租户代码。' },
                { pattern: TENANT_CODE_PATTERN, message: '租户代码格式不正确。' },
              ]}
            >
              <Input allowClear placeholder="例如 acme" />
            </Form.Item>
            <Button htmlType="submit" type="primary">
              加载审批列表
            </Button>
          </Form>
        </Card>
      </PageContainer>
    );
  }

  return (
    <PageContainer
      subTitle={`目标租户 ${tenantId}；只处理导出请求元数据，不读取审计内容`}
      title="平台审计导出审批"
    >
      {tenantId === PLATFORM_TENANT_ID ? (
        <Result
          status="403"
          subTitle="平台租户自身导出在 M10 不支持：平台租户只有平台管理员，无法满足“申请人不得自批”。"
          title="平台租户不支持导出审批"
        />
      ) : (
        <>
          <Alert
            description="平台管理员只查看申请人、过滤条件、预计行数与状态；不能查看审计事件内容，也不能下载导出文件。多管理员租户必须由租户内另一位管理员审批。"
            showIcon
            style={{ marginBottom: 16 }}
            title="平台审批边界"
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

          <Card
            className="export-list-card"
            extra={
              <Space align="center" size={8} wrap>
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
              </Space>
            }
            title="导出申请元数据"
          >
            {phase === 'loading' && (
              <div aria-busy="true" aria-label="正在加载导出申请">
                <Skeleton active paragraph={{ rows: 8 }} />
              </div>
            )}

            {phase === 'multiAdmin' && (
              <Result
                status="warning"
                subTitle={
                  error ??
                  '目标租户有多个启用的租户管理员，导出必须由租户内另一位管理员审批；平台不能代为审批。'
                }
                title="该租户不能由平台审批"
                extra={
                  <Button onClick={() => void load()} type="primary">
                    重新检查
                  </Button>
                }
              />
            )}

            {phase === 'notFound' && (
              <Result status="404" subTitle="目标租户不存在或已被清理。" title="目标租户不存在" />
            )}

            {phase === 'forbidden' && (
              <Result
                status="403"
                subTitle={error ?? '当前账号无权执行平台审批。'}
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

            {phase === 'empty' && <Empty description="目标租户当前没有符合条件的导出申请。" />}

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
                scroll={{ x: 1240 }}
                size="middle"
              />
            )}
          </Card>

          <ExportDetailDrawer
            currentUserId={currentUserId}
            onClose={() => setSelected(null)}
            platformMode
            request={selected}
          />

          <DecisionModal
            decision={decisionState?.decision ?? null}
            onClose={() => setDecisionState(null)}
            onDecided={handleDecided}
            open={decisionState !== null}
            platformMode
            request={decisionState?.request ?? null}
            submit={(decision, comment) => {
              const target = decisionState?.request;
              if (!target) {
                throw new Error('没有待审批的导出请求');
              }
              return api.decidePlatformExport(tenantId, target.id, { decision, comment });
            }}
          />
        </>
      )}
    </PageContainer>
  );
}
