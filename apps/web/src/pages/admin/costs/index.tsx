import { Link } from '@umijs/max';
import { PageContainer } from '@ant-design/pro-components';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Empty,
  Flex,
  Input,
  Result,
  Segmented,
  Skeleton,
  Space,
  Table,
  Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api as defaultApi } from '@/services/api';
import { ApiError } from '@/services/api/errors';
import { authStore, useAuth } from '@/services/auth/auth-store';
import {
  COST_DISCLAIMER,
  COST_MAX_DAYS,
  COST_PERMISSIONS,
  type CostApi,
  type CostBucket,
  type CostReportBase,
  type PlatformCostGroupBy,
  type TenantCostGroupBy,
  costApi as defaultCostApi,
  costBucketDetail,
  costBucketTitle,
  costGroupByLabel,
  defaultCostRange,
  describeCostError,
  formatCostAmount,
  formatCostCount,
  formatCostExactAmount,
  hasCostPermission,
  validateCostRange,
} from '@/services/cost';
import { InvocationPanel } from './invocation-panel';
import './costs.css';

/** 成本页可注入依赖，便于组件测试。 */
export interface CostsPageProps {
  /** 成本 API；默认使用同源真实客户端。 */
  api?: CostApi;
}

/** 页面角色模式：平台跨租户聚合或本租户聚合。 */
type CostsMode = 'platform' | 'tenant';

/** 页面主阶段。 */
type CostsPhase = 'loading' | 'ready' | 'empty' | 'forbidden' | 'error';

/** 报告中与角色无关的公共字段；平台/租户桶都可按 `CostBucket` 消费。 */
interface CostReportView extends CostReportBase {
  groupBy: string;
  items: CostBucket[];
}

/** 服务端 page 上限；分页组件按此截断，避免用户点到第 201 页才收到 400。 */
const MAX_PAGE = 200;

/** 每页大小选项；与服务端 size ≤ 100 一致。 */
const PAGE_SIZE_OPTIONS = ['20', '50', '100'];

/** 平台聚合维度；默认按租户。 */
const PLATFORM_GROUPS: ReadonlyArray<{ value: PlatformCostGroupBy; label: string }> = [
  { value: 'TENANT', label: '按租户' },
  { value: 'MODEL', label: '按模型' },
  { value: 'DAY', label: '按日' },
];

/** 租户聚合维度；默认按用户，便于展开查看单个用户的调用明细。 */
const TENANT_GROUPS: ReadonlyArray<{ value: TenantCostGroupBy; label: string }> = [
  { value: 'USER', label: '按用户' },
  { value: 'MODEL', label: '按模型' },
  { value: 'DAY', label: '按日' },
];

/**
 * 计算分页组件的总条数。
 *
 * @param total 服务端总条数
 * @param size 每页大小
 * @param maxPage 最大页码
 * @returns 分页组件使用的总条数
 */
function cappedPaginationTotal(total: number, size: number, maxPage: number): number {
  return Math.min(total, size * maxPage);
}

/**
 * 返回当前维度下表格首列标题。
 *
 * @param groupBy 聚合维度
 * @returns 中文列名
 */
function bucketColumnTitle(groupBy: string): string {
  if (groupBy === 'TENANT') {
    return '租户';
  }
  if (groupBy === 'USER') {
    return '用户';
  }
  if (groupBy === 'MODEL') {
    return '模型';
  }
  return '日（Asia/Shanghai）';
}

/**
 * 成本看板页。
 *
 * 两种角色共用一个路径：
 * - 平台管理员（`platform.cost.read`）按租户/模型/日聚合，只到聚合层，不展示用户明细；
 * - 租户管理员（`cost.read`）按用户/模型/日聚合，可展开分组查看调用价格快照；
 * - 员工没有权限，页面直接 403 且不请求数据。
 *
 * 金额展示按 `currencyMinorUnit` 四舍五入一次；未定价/用量未知/币种不一致/未分桶调用
 * 单列且不计入金额，并提供固定免责文案（AC-11）。
 *
 * @param props API 注入
 * @returns 成本看板页面
 */
export default function CostsPage({ api = defaultCostApi }: CostsPageProps) {
  const auth = useAuth();
  const permissions = auth.session?.permissions ?? [];
  const canPlatform = hasCostPermission(permissions, COST_PERMISSIONS.platformRead);
  const canReadTenant = hasCostPermission(permissions, COST_PERMISSIONS.read);
  const mode: CostsMode | null = canPlatform ? 'platform' : canReadTenant ? 'tenant' : null;

  const [groupBy, setGroupBy] = useState<TenantCostGroupBy | PlatformCostGroupBy>(() =>
    canPlatform ? 'TENANT' : canReadTenant ? 'USER' : 'DAY',
  );
  const [range, setRange] = useState(() => defaultCostRange());
  const [draftFrom, setDraftFrom] = useState(range.from);
  const [draftTo, setDraftTo] = useState(range.to);
  const [rangeError, setRangeError] = useState<string | null>(null);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [report, setReport] = useState<CostReportView | null>(null);
  const [phase, setPhase] = useState<CostsPhase>('loading');
  const [error, setError] = useState<string | null>(null);
  const [expandedKeys, setExpandedKeys] = useState<string[]>([]);
  const requestSequence = useRef(0);

  // 管理端布局不恢复会话；直接刷新时由页面补一次会话恢复。
  useEffect(() => {
    if (auth.status === 'unknown') {
      void authStore.restore(defaultApi);
    }
  }, [auth.status]);

  // 会话从“未加载”恢复后，若默认维度不属于当前角色，切换到该角色的默认维度。
  useEffect(() => {
    if (mode === 'platform' && groupBy === 'USER') {
      setGroupBy('TENANT');
    }
    if (mode === 'tenant' && groupBy === 'TENANT') {
      setGroupBy('USER');
    }
  }, [mode, groupBy]);

  /**
   * 加载成本报告。
   *
   * 平台与租户使用不同端点；请求序号用于丢弃快速切换维度/翻页时先发后到的旧响应。
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
      const result =
        mode === 'platform'
          ? await api.getPlatformCostReport({
              from: range.from,
              to: range.to,
              groupBy: groupBy as PlatformCostGroupBy,
              page,
              size,
            })
          : await api.getTenantCostReport({
              from: range.from,
              to: range.to,
              groupBy: groupBy as TenantCostGroupBy,
              page,
              size,
            });
      if (requestSequence.current !== sequence) {
        return;
      }
      setReport(result);
      setPhase(result.items.length === 0 ? 'empty' : 'ready');
    } catch (loadError) {
      if (requestSequence.current !== sequence) {
        return;
      }
      const context = mode === 'platform' ? 'platform-report' : 'tenant-report';
      setError(describeCostError(loadError, context));
      setPhase(
        loadError instanceof ApiError && (loadError.status === 403 || loadError.status === 401)
          ? 'forbidden'
          : 'error',
      );
    }
  }, [api, groupBy, mode, page, range.from, range.to, size]);

  useEffect(() => {
    void load();
  }, [load]);

  /**
   * 校验并应用新的时间范围；非法时不发起请求，保留上一次报告。
   *
   * @param nextFrom 起始日
   * @param nextTo 结束日
   */
  const applyRange = (nextFrom: string, nextTo: string): void => {
    const problem = validateCostRange(nextFrom, nextTo);
    if (problem) {
      setRangeError(problem);
      return;
    }
    setRangeError(null);
    setDraftFrom(nextFrom);
    setDraftTo(nextTo);
    setExpandedKeys([]);
    setPage(1);
    setRange({ from: nextFrom, to: nextTo });
  };

  /**
   * 应用“最近 N 天”快捷范围。
   *
   * @param days 天数；最多 180
   */
  const applyRecentDays = (days: number): void => {
    const next = defaultCostRange(days);
    applyRange(next.from, next.to);
  };

  /**
   * 处理聚合维度切换；回到第一页并收起已展开的分组。
   *
   * @param value Segmented 选中值
   */
  const handleGroupChange = (value: string | number): void => {
    setGroupBy(value as TenantCostGroupBy);
    setPage(1);
    setExpandedKeys([]);
  };

  /**
   * 处理分页变化；切换每页大小时回到第一页。
   *
   * @param nextPage 目标页
   * @param nextSize 目标每页大小
   */
  const handlePageChange = (nextPage: number, nextSize: number): void => {
    setExpandedKeys([]);
    if (nextSize !== size) {
      setSize(nextSize);
      setPage(1);
      return;
    }
    setPage(nextPage);
  };

  const groups = mode === 'platform' ? PLATFORM_GROUPS : TENANT_GROUPS;
  const groupOptions = useMemo(
    () => groups.map((item) => ({ value: item.value, label: item.label })),
    [groups],
  );

  const columns: ColumnsType<CostBucket> = [
    {
      title: bucketColumnTitle(groupBy),
      key: 'label',
      width: 240,
      render: (_value, record) => {
        const detail = costBucketDetail(record, groupBy);
        return (
          <Space className="costs-cell-wrap" orientation="vertical" size={0}>
            <Typography.Text strong>{costBucketTitle(record, groupBy)}</Typography.Text>
            {detail && <Typography.Text type="secondary">{detail}</Typography.Text>}
          </Space>
        );
      },
    },
    {
      title: `金额（${report?.currency ?? ''}）`,
      dataIndex: 'amount',
      key: 'amount',
      width: 170,
      render: (value: string) => (
        <span title={`${formatCostExactAmount(value)} ${report?.currency ?? ''}`.trim()}>
          {formatCostAmount(value, report?.currency ?? '', report?.currencyMinorUnit ?? 2)}
        </span>
      ),
    },
    {
      title: '已定价调用',
      dataIndex: 'pricedCalls',
      key: 'pricedCalls',
      width: 120,
      render: (value: number) => formatCostCount(value),
    },
    {
      title: '未定价调用（不计金额）',
      dataIndex: 'unpricedCalls',
      key: 'unpricedCalls',
      width: 170,
      render: (value: number) => formatCostCount(value),
    },
    {
      title: '用量未知（不计金额）',
      dataIndex: 'usageUnknownCalls',
      key: 'usageUnknownCalls',
      width: 170,
      render: (value: number) => formatCostCount(value),
    },
  ];

  if (auth.status === 'unknown' || auth.status === 'checking') {
    return (
      <PageContainer title="成本">
        <div aria-busy="true" aria-label="正在检查登录状态">
          <Skeleton active paragraph={{ rows: 6 }} />
        </div>
      </PageContainer>
    );
  }

  if (auth.status === 'anonymous' || auth.status === 'expired') {
    return (
      <PageContainer title="成本">
        <Result
          status="403"
          subTitle="登录状态已失效，请重新登录后再查看成本。"
          title="没有访问权限"
        />
      </PageContainer>
    );
  }

  if (auth.status === 'error') {
    return (
      <PageContainer title="成本">
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
      <PageContainer title="成本">
        <Result
          status="403"
          subTitle="成本看板仅向平台管理员与租户管理员开放；员工没有入口，也不会请求任何金额数据。"
          title="没有访问权限"
        />
      </PageContainer>
    );
  }

  const pageTitle = mode === 'platform' ? '成本看板' : '成本';
  const subTitle =
    mode === 'platform'
      ? '跨租户聚合：按租户、模型、日查看估算成本；平台不展示用户维度明细。'
      : '本租户聚合：按用户、模型、日查看估算成本，可展开分组查看调用价格快照。';
  const totals = report?.totals;

  return (
    <PageContainer subTitle={subTitle} title={pageTitle}>
      <Alert
        className="costs-notice"
        description={
          <>
            {report?.disclaimer || COST_DISCLAIMER}
            金额按 6 位小数计算，展示按币种最小单位（当前{' '}
            {report ? `${report.currency} ${report.currencyMinorUnit} 位` : '按部署币种'}
            ）四舍五入一次； 合计按未舍入值汇总。只使用部署配置的单一币种，不做汇率换算；日桶按
            Asia/Shanghai 自然日。
          </>
        }
        showIcon
        title="成本口径"
        type="info"
      />

      <Alert
        className="costs-notice"
        description={
          mode === 'platform'
            ? '平台视图只到租户/模型/日聚合，不返回用户明细，避免跨租户推断个人行为。需要按用户排查时，由租户管理员在“成本”页查看本租户明细。'
            : '可展开任一分组查看该次调用的输入/输出价格快照与金额；只显示本租户数据。'
        }
        showIcon
        title="可见范围"
        type="info"
      />

      <Card className="costs-filter-card" title="查询条件">
        <Space className="costs-filter" orientation="vertical" size={12} style={{ width: '100%' }}>
          <Space align="center" size={8} wrap>
            <Typography.Text>聚合维度</Typography.Text>
            <Segmented
              aria-label="聚合维度"
              onChange={handleGroupChange}
              options={groupOptions}
              value={groupBy}
            />
          </Space>

          <Flex align="flex-end" gap={12} wrap>
            <label className="costs-date-field" htmlFor="costs-from">
              <span>开始日期（Asia/Shanghai）</span>
              <Input
                id="costs-from"
                max={draftTo}
                onChange={(event) => setDraftFrom(event.target.value)}
                type="date"
                value={draftFrom}
              />
            </label>
            <label className="costs-date-field" htmlFor="costs-to">
              <span>结束日期（Asia/Shanghai）</span>
              <Input
                id="costs-to"
                min={draftFrom}
                onChange={(event) => setDraftTo(event.target.value)}
                type="date"
                value={draftTo}
              />
            </label>
            <Space size={8} wrap>
              <Button
                autoInsertSpace={false}
                onClick={() => applyRange(draftFrom, draftTo)}
                type="primary"
              >
                查询
              </Button>
              <Button autoInsertSpace={false} onClick={() => applyRecentDays(7)}>
                近 7 天
              </Button>
              <Button autoInsertSpace={false} onClick={() => applyRecentDays(30)}>
                近 30 天
              </Button>
              <Button autoInsertSpace={false} onClick={() => applyRecentDays(90)}>
                近 90 天
              </Button>
            </Space>
          </Flex>

          <Typography.Text type="secondary">
            当前范围：{range.from} 至 {range.to}（含端点，最多 {COST_MAX_DAYS}{' '}
            天）；未修改时间时默认最近 30 天。查询为只读，不写审计。
          </Typography.Text>
        </Space>
      </Card>

      {rangeError && (
        <Alert
          aria-live="assertive"
          className="costs-notice"
          description={rangeError}
          role="alert"
          showIcon
          title="时间范围无效"
          type="error"
        />
      )}

      {error && phase !== 'forbidden' && (
        <Alert
          aria-live="assertive"
          className="costs-notice"
          description={error}
          role="alert"
          showIcon
          title="成本数据未加载"
          type="error"
        />
      )}

      {phase === 'loading' && (
        <div aria-busy="true" aria-label="正在加载成本报告">
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
          subTitle={error ?? '当前账号无权查看成本。'}
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
          subTitle={error ?? '成本报告加载失败，请稍后重试。'}
          title="成本报告加载失败"
        />
      )}

      {phase === 'empty' && (
        <Empty description="所选时间范围内没有调用记录。可扩大时间范围；若尚未配置模型目录或路由，请先由平台管理员完成部署种子与路由配置。">
          <Space wrap>
            <Button autoInsertSpace={false} onClick={() => void load()} type="primary">
              重新查询
            </Button>
            <Button autoInsertSpace={false} onClick={() => applyRecentDays(30)}>
              近 30 天
            </Button>
            {mode === 'platform' && <Link to="/admin/model-routing">前往模型路由</Link>}
          </Space>
        </Empty>
      )}

      {phase === 'ready' && report && totals && (
        <Space orientation="vertical" size={16} style={{ width: '100%' }}>
          <Card title={`合计（${costGroupByLabel(report.groupBy)}）`}>
            <Descriptions
              column={{ xs: 1, sm: 2, lg: 3 }}
              items={[
                {
                  key: 'amount',
                  label: '合计金额',
                  children: (
                    <Space className="costs-amount" orientation="vertical" size={0}>
                      <Typography.Text strong>
                        {formatCostAmount(totals.amount, report.currency, report.currencyMinorUnit)}
                      </Typography.Text>
                      <Typography.Text type="secondary">
                        精确值 {formatCostExactAmount(totals.amount)} {report.currency}
                      </Typography.Text>
                    </Space>
                  ),
                },
                {
                  key: 'priced',
                  label: '已定价调用',
                  children: formatCostCount(totals.pricedCalls),
                },
                {
                  key: 'unpriced',
                  label: '未定价调用（单列，不计入金额）',
                  children: formatCostCount(totals.unpricedCalls),
                },
                {
                  key: 'unknown',
                  label: '用量未知调用（单列，不计入金额）',
                  children: formatCostCount(totals.usageUnknownCalls),
                },
                {
                  key: 'mismatch',
                  label: '币种不一致调用（不计入金额）',
                  children: formatCostCount(totals.currencyMismatchCalls),
                },
                {
                  key: 'unbucketed',
                  label: '未分桶调用（回滚窗口/未回填，不计入金额）',
                  children: formatCostCount(totals.unbucketedCalls),
                },
              ]}
              size="small"
            />
            <Typography.Paragraph className="costs-table-note" type="secondary">
              未定价、用量未知、币种不一致与未分桶调用只进入调用量统计，不参与金额合计；服务端不返回
              部分结果。
            </Typography.Paragraph>
          </Card>

          <Card
            className="costs-result-card"
            extra={
              <Button autoInsertSpace={false} onClick={() => void load()} size="small">
                重新加载
              </Button>
            }
            title={`聚合结果（${costGroupByLabel(report.groupBy)}）`}
          >
            <Typography.Paragraph className="costs-table-note" type="secondary">
              范围 {report.from} 至 {report.to}；每页最多 100 条、最多 {MAX_PAGE}{' '}
              页；按金额降序、再按维度键升序。
            </Typography.Paragraph>
            <Table<CostBucket>
              columns={columns}
              dataSource={report.items}
              expandable={
                mode === 'tenant'
                  ? {
                      expandedRowKeys: expandedKeys,
                      onExpand: (expanded, record) =>
                        setExpandedKeys((previous) =>
                          expanded
                            ? [...previous, record.key]
                            : previous.filter((key) => key !== record.key),
                        ),
                      expandIcon: ({ expanded, onExpand, record }) => (
                        <Button
                          aria-label={`${expanded ? '收起明细' : '查看明细'}：${costBucketTitle(
                            record,
                            groupBy,
                          )}`}
                          autoInsertSpace={false}
                          onClick={(event) => onExpand(record, event)}
                          size="small"
                          type="link"
                        >
                          {expanded ? '收起明细' : '查看明细'}
                        </Button>
                      ),
                      expandedRowRender: (record) => (
                        <InvocationPanel
                          api={api}
                          bucket={record}
                          currency={report.currency}
                          currencyMinorUnit={report.currencyMinorUnit}
                          from={range.from}
                          groupBy={groupBy}
                          to={range.to}
                        />
                      ),
                    }
                  : undefined
              }
              pagination={{
                current: report.page,
                pageSize: report.size,
                total: cappedPaginationTotal(report.total, report.size, MAX_PAGE),
                showSizeChanger: true,
                pageSizeOptions: PAGE_SIZE_OPTIONS,
                showTotal: () => `共 ${formatCostCount(report.total)} 个分组`,
                onChange: handlePageChange,
              }}
              rowKey="key"
              scroll={{ x: 900 }}
              size="middle"
            />
          </Card>
        </Space>
      )}
    </PageContainer>
  );
}
