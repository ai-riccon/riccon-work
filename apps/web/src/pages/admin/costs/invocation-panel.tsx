import { Alert, Button, Empty, Skeleton, Space, Table, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { CSSProperties } from 'react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  type CostApi,
  type CostBucket,
  type CostInvocation,
  type CostInvocationQuery,
  describeCostError,
  formatCostAmount,
  formatCostCount,
  formatCostExactAmount,
  formatCostTime,
  pricingStatusColor,
  pricingStatusLabel,
} from '@/services/cost';

/** 明细面板属性。 */
export interface InvocationPanelProps {
  /** 成本 API；由页面注入。 */
  api: CostApi;
  /** 当前聚合维度；决定明细过滤条件。 */
  groupBy: string;
  /** 当前报告时间范围（`YYYY-MM-DD`）。 */
  from: string;
  to: string;
  /** 展开的成本桶。 */
  bucket: CostBucket;
  /** 部署币种与最小单位。 */
  currency: string;
  currencyMinorUnit: number;
}

/** 明细面板阶段。 */
type PanelPhase = 'loading' | 'ready' | 'empty' | 'error';

/** 每页明细条数；受服务端 size 上限 100 约束，这里取较小值保持展开区紧凑。 */
const PANEL_PAGE_SIZE = 10;

/**
 * 定价状态 Tag 的高对比配色。
 *
 * antd 6 filled Tag 的绿色/金色 preset 在 12px 字号下低于 WCAG 2.1 AA 的 4.5:1
 * （绿色 3.37:1、金色 2.76:1）；复用 B9a 已评审的浅底深字方案，
 * 让成本页与路由/key 页的 Tag 对比度保持一致（AC-14）。
 */
const HIGH_CONTRAST_TAG_STYLES: Record<'green' | 'gold', CSSProperties> = {
  green: { color: '#237804', background: '#f6ffed', borderColor: '#b7eb8f' },
  gold: { color: '#874d00', background: '#fffbe6', borderColor: '#ffe58f' },
};

/**
 * 返回 Tag 的高对比内联样式。
 *
 * @param color antd Tag preset 颜色
 * @returns 已覆盖颜色的内联样式；默认色本身满足 AA，返回 undefined
 */
function highContrastTagStyle(color: string): CSSProperties | undefined {
  return HIGH_CONTRAST_TAG_STYLES[color as keyof typeof HIGH_CONTRAST_TAG_STYLES];
}

/**
 * 成本分桶的调用明细面板。
 *
 * 只由租户管理员使用：平台视图不提供用户维度明细（ADR-0022 §2.4）。
 * 展开时按当前维度追加过滤：USER 用 `userId`、MODEL 用 `modelId`、DAY 用该日
 * `from=to=day`；金额与价格全部来自调用时快照，不 join 当前模型价格。
 *
 * @param props API、过滤上下文与币种
 * @returns 可翻页的调用明细表
 */
export function InvocationPanel({
  api,
  groupBy,
  from,
  to,
  bucket,
  currency,
  currencyMinorUnit,
}: InvocationPanelProps) {
  const [phase, setPhase] = useState<PanelPhase>('loading');
  const [page, setPage] = useState(1);
  const [items, setItems] = useState<CostInvocation[]>([]);
  const [total, setTotal] = useState(0);
  const [error, setError] = useState<string | null>(null);

  // 只在维度键/日期变化时重建过滤对象，避免父组件重渲染触发重复请求。
  const filter = useMemo<CostInvocationQuery>(() => {
    if (groupBy === 'USER' && bucket.userId) {
      return { from, to, userId: bucket.userId };
    }
    if (groupBy === 'MODEL' && bucket.modelId) {
      return { from, to, modelId: bucket.modelId };
    }
    if (groupBy === 'DAY' && bucket.day) {
      return { from: bucket.day, to: bucket.day };
    }
    return { from, to };
  }, [bucket.day, bucket.modelId, bucket.userId, from, groupBy, to]);

  const load = useCallback(
    async (nextPage: number): Promise<void> => {
      setPhase('loading');
      setError(null);
      try {
        const result = await api.listTenantCostInvocations({
          ...filter,
          page: nextPage,
          size: PANEL_PAGE_SIZE,
        });
        setItems(result.items);
        setTotal(result.total);
        setPage(result.page);
        setPhase(result.items.length === 0 ? 'empty' : 'ready');
      } catch (loadError) {
        setError(describeCostError(loadError, 'invocations'));
        setPhase('error');
      }
    },
    [api, filter],
  );

  useEffect(() => {
    void load(1);
  }, [load]);

  const columns: ColumnsType<CostInvocation> = [
    {
      title: '开始时间（+08:00）',
      dataIndex: 'startedAt',
      key: 'startedAt',
      width: 200,
      render: (_value, record) => (
        <span className="costs-cell-wrap">{formatCostTime(record.startedAt)}</span>
      ),
    },
    {
      title: '模型',
      dataIndex: 'modelDisplayName',
      key: 'model',
      width: 170,
      render: (_value, record) => (
        <Space className="costs-cell-wrap" orientation="vertical" size={0}>
          <Typography.Text>{record.modelDisplayName}</Typography.Text>
          <Typography.Text code>{record.modelId.slice(0, 8)}</Typography.Text>
        </Space>
      ),
    },
    {
      title: '用户',
      dataIndex: 'userDisplayName',
      key: 'user',
      width: 150,
      render: (_value, record) => (
        <span className="costs-cell-wrap">
          {record.userDisplayName || record.userId || '未知用户'}
        </span>
      ),
    },
    {
      title: '输入 token',
      dataIndex: 'promptTokens',
      key: 'promptTokens',
      width: 110,
      render: (value: number | null | undefined) =>
        value === null || value === undefined ? (
          <Typography.Text type="secondary">未返回</Typography.Text>
        ) : (
          formatCostCount(value)
        ),
    },
    {
      title: '输出 token',
      dataIndex: 'completionTokens',
      key: 'completionTokens',
      width: 110,
      render: (value: number | null | undefined) =>
        value === null || value === undefined ? (
          <Typography.Text type="secondary">未返回</Typography.Text>
        ) : (
          formatCostCount(value)
        ),
    },
    {
      title: '输入价格（/1M）',
      dataIndex: 'inputPricePer1M',
      key: 'inputPrice',
      width: 150,
      render: (value: string | null | undefined) =>
        value ? (
          formatCostExactAmount(value)
        ) : (
          <Typography.Text type="secondary">—</Typography.Text>
        ),
    },
    {
      title: '输出价格（/1M）',
      dataIndex: 'outputPricePer1M',
      key: 'outputPrice',
      width: 150,
      render: (value: string | null | undefined) =>
        value ? (
          formatCostExactAmount(value)
        ) : (
          <Typography.Text type="secondary">—</Typography.Text>
        ),
    },
    {
      title: '金额（快照）',
      dataIndex: 'amount',
      key: 'amount',
      width: 140,
      render: (value: string | null | undefined, record) => {
        if (!value) {
          return <Typography.Text type="secondary">不计入金额</Typography.Text>;
        }
        const snapshotCurrency = record.priceCurrency ?? currency;
        // 币种不一致的历史快照不参与合计；展示时避免用部署币种的最小单位误舍入其他币种。
        const display =
          record.priceCurrency && record.priceCurrency !== currency
            ? `${snapshotCurrency} ${formatCostExactAmount(value)}`
            : formatCostAmount(value, snapshotCurrency, currencyMinorUnit);
        return (
          <span title={`${formatCostExactAmount(value)} ${snapshotCurrency}`.trim()}>
            {display}
          </span>
        );
      },
    },
    {
      title: '定价状态',
      dataIndex: 'pricingStatus',
      key: 'pricingStatus',
      width: 120,
      render: (value: CostInvocation['pricingStatus']) => (
        <Tag
          color={pricingStatusColor(value)}
          style={highContrastTagStyle(pricingStatusColor(value))}
        >
          {pricingStatusLabel(value)}
        </Tag>
      ),
    },
  ];

  if (phase === 'loading') {
    return (
      <div aria-busy="true" aria-label="正在加载调用明细">
        <Skeleton active paragraph={{ rows: 3 }} />
      </div>
    );
  }

  if (phase === 'error') {
    return (
      <Alert
        action={
          <Button autoInsertSpace={false} onClick={() => void load(1)} size="small">
            重试
          </Button>
        }
        description={error ?? '调用明细加载失败，请稍后重试。'}
        showIcon
        title="调用明细加载失败"
        type="error"
      />
    );
  }

  if (phase === 'empty') {
    return (
      <Empty
        description="该分组在当前时间范围内没有调用明细。"
        image={Empty.PRESENTED_IMAGE_SIMPLE}
      />
    );
  }

  return (
    <Space
      className="costs-invocation-panel"
      orientation="vertical"
      size={8}
      style={{ width: '100%' }}
    >
      <Typography.Text type="secondary">
        明细展示调用时价格快照；金额按 6 位小数计算，此处按币种最小单位展示。
        {total > items.length ? ' 可翻页查看更早的调用。' : ''}
      </Typography.Text>
      <Table<CostInvocation>
        columns={columns}
        dataSource={items}
        pagination={{
          current: page,
          pageSize: PANEL_PAGE_SIZE,
          total,
          size: 'small',
          showSizeChanger: false,
          onChange: (nextPage) => void load(nextPage),
        }}
        rowKey="id"
        scroll={{ x: 1280 }}
        size="small"
      />
    </Space>
  );
}
