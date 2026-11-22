import { PageContainer } from '@ant-design/pro-components';
import { Link } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Empty,
  Form,
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
import { auditApi as defaultAuditApi } from '@/services/audit';
import type { AuditApi } from '@/services/audit/api';
import {
  actorTypeLabel,
  auditPolicyNotice,
  describeAuditError,
  formatActor,
  formatAuditTarget,
  formatAuditTime,
  formatRowCount,
  outcomeColor,
  outcomeLabel,
  toAuditFilter,
} from '@/services/audit/format';
import type { AuditFilterFormValues } from '@/services/audit/format';
import { AUDIT_PERMISSIONS, hasPermission } from '@/services/audit/access';
import type {
  AuditEvent,
  AuditExportRequest,
  AuditFilter,
  AuditPolicy,
} from '@/services/audit/types';
import { authStore, useAuth } from '@/services/auth/auth-store';
import { AuditEventDrawer } from './event-drawer';
import { ExportRequestModal } from './export-request-modal';
import { AuditFilterFields } from './filter-fields';
import { FAILOVER_PRESET_LABEL, buildFailoverPreset } from './presets';
import './audit.css';

/** 审计查询页参数。 */
export interface AuditPageProps {
  /** 可注入的 API，便于组件测试。 */
  api?: AuditApi;
}

/** 页面主阶段。 */
type AuditPhase = 'loading' | 'ready' | 'empty' | 'forbidden' | 'error';

/**
 * 计算分页组件的总条数。
 *
 * 服务端允许 `page ≤ 200`；把分页组件的 total 截断到上限，避免用户点到
 * 第 201 页才收到 400。真实总条数仍通过 `showTotal` 展示。
 *
 * @param total 服务端返回总条数
 * @param size 每页大小
 * @param maxPage 最大页码
 * @returns 分页组件使用的总条数
 */
export function cappedPaginationTotal(total: number, size: number, maxPage: number): number {
  return Math.min(total, size * maxPage);
}

/**
 * 判断时间范围是否超过 180 天。
 *
 * @param from 开始时间
 * @param to 结束时间
 * @returns 是否超限
 */
function rangeTooWide(from: Date | undefined, to: Date | undefined): boolean {
  if (!from || !to) {
    return false;
  }
  return to.getTime() - from.getTime() > 180 * 24 * 60 * 60 * 1000;
}

/**
 * 审计查询页。
 *
 * 只读：页面没有删除、修改或写操作入口；查询条件与导出条件共用同一套字段。
 * 租户范围由服务端从登录主体派生，界面不提供 `tenantId` 输入。
 *
 * @param props API 注入
 * @returns 审计查询页面
 */
export default function AuditPage({ api = defaultAuditApi }: AuditPageProps) {
  const auth = useAuth();
  const [form] = Form.useForm<AuditFilterFormValues>();
  const [phase, setPhase] = useState<AuditPhase>('loading');
  const [policy, setPolicy] = useState<AuditPolicy | null>(null);
  const [events, setEvents] = useState<AuditEvent[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [appliedFilter, setAppliedFilter] = useState<AuditFilter>({});
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [presetNotice, setPresetNotice] = useState<{
    label: string;
    from: string;
    to: string;
  } | null>(null);
  const [selectedEvent, setSelectedEvent] = useState<AuditEvent | null>(null);
  const [exportOpen, setExportOpen] = useState(false);
  const policyRef = useRef<AuditPolicy | null>(null);
  const requestSequence = useRef(0);

  const session = auth.session;
  const canRead = session ? hasPermission(session.permissions, AUDIT_PERMISSIONS.read) : false;

  // 管理端布局不恢复会话；直接刷新 /admin/audit 时由页面补一次会话恢复。
  useEffect(() => {
    if (auth.status === 'unknown') {
      void authStore.restore(defaultApi);
    }
  }, [auth.status]);

  /**
   * 加载策略与当前页审计事件。
   *
   * 策略只读取一次并缓存；分页/过滤变化只重新查询事件。请求序号用于丢弃
   * 快速翻页时先发后到的旧响应，避免表格显示错页数据。
   */
  const load = useCallback(async (): Promise<void> => {
    if (!canRead) {
      return;
    }
    const sequence = requestSequence.current + 1;
    requestSequence.current = sequence;
    setPhase('loading');
    setError(null);
    try {
      const nextPolicy = policyRef.current ?? (await api.getPolicy());
      if (!policyRef.current) {
        policyRef.current = nextPolicy;
        setPolicy(nextPolicy);
      }
      const result = await api.listEvents({ ...appliedFilter, page, size });
      if (requestSequence.current !== sequence) {
        return;
      }
      setEvents(result.items);
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
  }, [api, appliedFilter, canRead, page, size]);

  useEffect(() => {
    void load();
  }, [load]);

  /**
   * 提交过滤条件并回到第一页。
   *
   * @param values 表单值
   */
  const handleSearch = (values: AuditFilterFormValues): void => {
    const from = values.occurredFrom?.toDate();
    const to = values.occurredTo?.toDate();
    if (rangeTooWide(from, to)) {
      setError('可查范围不能超过 180 天，请缩小时间范围或改用导出。');
      setPhase('error');
      return;
    }
    setPresetNotice(null);
    setAppliedFilter(toAuditFilter(values));
    setPage(1);
  };

  /**
   * 应用“降级事件（最近 7 天）”前端预设。
   *
   * 直接写入查询条件而不依赖 DatePicker 的 dayjs 值：预设是只读查询语义，
   * 时间窗口与动作由前端生成；用户仍可用筛选条件覆盖，重置会清除预设。
   */
  const handleFailoverPreset = (): void => {
    const preset = buildFailoverPreset(new Date());
    form.resetFields();
    setPresetNotice({
      label: FAILOVER_PRESET_LABEL,
      from: preset.occurredFrom,
      to: preset.occurredTo,
    });
    setAppliedFilter({
      action: preset.action,
      occurredFrom: preset.occurredFrom,
      occurredTo: preset.occurredTo,
    });
    setPage(1);
    setError(null);
  };

  /**
   * 重置过滤条件。
   */
  const handleReset = (): void => {
    form.resetFields();
    setAppliedFilter({});
    setPage(1);
    setError(null);
    setPresetNotice(null);
  };

  /**
   * 处理导出申请成功。
   *
   * @param created 新建导出请求
   */
  const handleExportCreated = (created: AuditExportRequest): void => {
    setNotice(
      `已提交导出申请，预计 ${formatRowCount(created.estimatedRowCount)} 行，状态为待审批；请到“审计导出”页查看审批与下载。`,
    );
  };

  const columns: ColumnsType<AuditEvent> = [
    {
      title: '发生时间（倒序）',
      dataIndex: 'occurredAt',
      key: 'occurredAt',
      width: 210,
      render: (_value, record) => (
        <Typography.Text className="audit-time-cell">
          {formatAuditTime(record.occurredAt)}
        </Typography.Text>
      ),
    },
    {
      title: '操作者',
      dataIndex: 'actorId',
      key: 'actorId',
      width: 220,
      ellipsis: true,
      render: (_value, record) => (
        <span title={formatActor(record.actorDisplayName, record.actorId)}>
          {formatActor(record.actorDisplayName, record.actorId)}
          <Typography.Text type="secondary">（{actorTypeLabel(record.actorType)}）</Typography.Text>
        </span>
      ),
    },
    {
      title: '动作',
      dataIndex: 'action',
      key: 'action',
      width: 190,
      render: (value: string) => <Typography.Text code>{value}</Typography.Text>,
    },
    {
      title: '对象',
      dataIndex: 'targetId',
      key: 'target',
      width: 220,
      ellipsis: true,
      render: (_value, record) => (
        <span title={formatAuditTarget(record.targetType, record.targetId)}>
          {formatAuditTarget(record.targetType, record.targetId)}
        </span>
      ),
    },
    {
      title: '结果',
      dataIndex: 'outcome',
      key: 'outcome',
      width: 96,
      render: (value: AuditEvent['outcome']) => (
        <Tag color={outcomeColor(value)}>{outcomeLabel(value)}</Tag>
      ),
    },
    {
      title: '租户',
      dataIndex: 'tenantId',
      key: 'tenantId',
      width: 130,
      ellipsis: true,
    },
    {
      title: '操作',
      key: 'actions',
      width: 110,
      fixed: 'right',
      render: (_value, record) => (
        <Button
          aria-label={`查看详情：事件 ${record.action}`}
          onClick={() => setSelectedEvent(record)}
          size="small"
          type="link"
        >
          查看详情
        </Button>
      ),
    },
  ];

  if (auth.status === 'unknown' || auth.status === 'checking') {
    return (
      <PageContainer title="审计日志">
        <div aria-busy="true" aria-label="正在检查登录状态">
          <Skeleton active paragraph={{ rows: 6 }} />
        </div>
      </PageContainer>
    );
  }

  if (auth.status === 'anonymous' || auth.status === 'expired') {
    return (
      <PageContainer title="审计日志">
        <Result
          status="403"
          subTitle="登录状态已失效，请重新登录后再访问审计。"
          title="没有访问权限"
        />
      </PageContainer>
    );
  }

  if (auth.status === 'error') {
    return (
      <PageContainer title="审计日志">
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

  if (!canRead) {
    return (
      <PageContainer title="审计日志">
        <Result
          status="403"
          subTitle="审计查询仅向租户管理员与平台管理员开放；普通员工无权查看，也不会请求任何审计数据。"
          title="没有访问权限"
        />
      </PageContainer>
    );
  }

  return (
    <PageContainer subTitle="只读查询；租户范围由登录主体决定，不接受手工指定租户" title="审计日志">
      {policy && (
        <Alert
          className="audit-policy-notice"
          description={auditPolicyNotice(policy)}
          showIcon
          title="保留策略与只读边界"
          type="info"
        />
      )}

      {notice && (
        <Alert
          action={
            <Link className="audit-export-link" to="/admin/audit-exports">
              查看审计导出
            </Link>
          }
          closable
          description={notice}
          onClose={() => setNotice(null)}
          showIcon
          style={{ marginBottom: 16 }}
          title="导出申请已提交"
          type="success"
        />
      )}

      {presetNotice && (
        <Alert
          aria-live="polite"
          closable
          description={`已应用 ${presetNotice.label}：${formatAuditTime(
            presetNotice.from,
          )} 至 ${formatAuditTime(
            presetNotice.to,
          )}。可继续在筛选条件中调整时间范围（最大 180 天）；该预设仅在前端生成，不改变服务端默认窗口。`}
          onClose={() => setPresetNotice(null)}
          showIcon
          style={{ marginBottom: 16 }}
          title="已应用降级事件预设"
          type="info"
        />
      )}

      <Card className="audit-filter-card" title="筛选条件">
        <Form<AuditFilterFormValues>
          className="audit-filter-grid"
          form={form}
          layout="vertical"
          onFinish={handleSearch}
          requiredMark="optional"
        >
          <AuditFilterFields actions={policy?.actions ?? []} disabled={phase === 'loading'} />
          <div className="audit-filter-actions">
            <Space wrap>
              <Button htmlType="submit" type="primary">
                查询
              </Button>
              <Button onClick={handleReset}>重置</Button>
              <Button onClick={handleFailoverPreset}>{FAILOVER_PRESET_LABEL}</Button>
              <Button disabled={!policy} onClick={() => setExportOpen(true)} type="default">
                申请导出
              </Button>
            </Space>
            <Typography.Text type="secondary">
              时间按浏览器本地时区选择，提交时转换为 Asia/Shanghai（+08:00）；未选时间时按最近 24
              小时，降级事件预设为最近 7 天。
            </Typography.Text>
          </div>
        </Form>
      </Card>

      {error && (
        <Alert
          aria-live="assertive"
          description={error}
          role="alert"
          showIcon
          style={{ margin: '16px 0' }}
          title="查询未完成"
          type={phase === 'forbidden' ? 'warning' : 'error'}
        />
      )}

      {phase === 'loading' && (
        <div aria-busy="true" aria-label="正在加载审计事件">
          <Skeleton active paragraph={{ rows: 8 }} />
        </div>
      )}

      {phase === 'forbidden' && (
        <Result
          status="403"
          subTitle={error ?? '当前账号无权查询审计。'}
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
          subTitle={error ?? '审计查询失败，请稍后重试。'}
          title="审计查询失败"
          extra={
            <Button onClick={() => void load()} type="primary">
              重试
            </Button>
          }
        />
      )}

      {phase === 'empty' && (
        <Empty description="当前条件下没有审计事件。可以扩大时间范围或清除过滤条件。">
          <Button onClick={handleReset}>重置筛选</Button>
        </Empty>
      )}

      {phase === 'ready' && (
        <>
          <div className="audit-table-toolbar">
            <Typography.Text type="secondary">
              排序固定为发生时间倒序（服务端排序，不支持客户端改序）；每页最多 100 条，最多 200 页。
            </Typography.Text>
            {policy && total > cappedPaginationTotal(total, size, policy.maxPage) && (
              <Typography.Text type="warning">
                结果超过 {policy.maxPage} 页，请缩小时间范围或改用导出。
              </Typography.Text>
            )}
          </div>
          <Table<AuditEvent>
            columns={columns}
            dataSource={events}
            pagination={{
              current: page,
              pageSize: size,
              total: policy ? cappedPaginationTotal(total, size, policy.maxPage) : total,
              showSizeChanger: true,
              pageSizeOptions: ['20', '50', '100'],
              showTotal: () =>
                policy && total > cappedPaginationTotal(total, size, policy.maxPage)
                  ? `共 ${total} 条；最多显示前 ${policy.maxPage} 页`
                  : `共 ${total} 条`,
              onChange: (nextPage, nextSize) => {
                setPage(nextPage);
                setSize(nextSize);
              },
            }}
            rowKey="eventId"
            scroll={{ x: 1180 }}
            size="middle"
          />
        </>
      )}

      <AuditEventDrawer event={selectedEvent} onClose={() => setSelectedEvent(null)} />

      {policy && (
        <ExportRequestModal
          api={api}
          filter={appliedFilter}
          onClose={() => setExportOpen(false)}
          onCreated={handleExportCreated}
          open={exportOpen}
          policy={policy}
        />
      )}
    </PageContainer>
  );
}
