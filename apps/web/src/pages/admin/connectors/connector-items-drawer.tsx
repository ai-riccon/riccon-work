import {
  Alert,
  Button,
  Descriptions,
  Drawer,
  Empty,
  Flex,
  Result,
  Select,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { TableColumnsType } from 'antd';
import { useCallback, useEffect, useRef, useState } from 'react';
import type {
  Connector,
  ConnectorApi,
  ConnectorItem,
  ConnectorItemStatus,
} from '@/services/connector';
import {
  connectorItemStatusColor,
  connectorItemStatusLabel,
  connectorSyncStatusColor,
  connectorSyncStatusLabel,
  describeConnectorError,
  describeConnectorErrorCode,
  formatConnectorExternalId,
  formatConnectorTime,
  isConnectorSyncInProgress,
} from '@/services/connector';

/** 条目抽屉属性。 */
export interface ConnectorItemsDrawerProps {
  /** 连接器 API；由页面注入，测试使用替身。 */
  api: ConnectorApi;
  /** 目标连接器；null 表示关闭。 */
  connector: Connector | null;
  /** 关闭抽屉。 */
  onClose: () => void;
}

/** 差异过滤器；`ALL` 表示不传 status。 */
type ItemStatusFilter = ConnectorItemStatus | 'ALL';

/** 条目列表阶段。 */
type ItemsPhase = 'loading' | 'ready' | 'empty' | 'error';

/** 状态筛选选项。 */
const STATUS_FILTER_OPTIONS: Array<{ value: ItemStatusFilter; label: string }> = [
  { value: 'ALL', label: '全部状态' },
  { value: 'PENDING', label: '待同步' },
  { value: 'SYNCED', label: '已同步' },
  { value: 'SKIPPED', label: '已跳过' },
  { value: 'FAILED', label: '失败' },
  { value: 'DELETED', label: '已删除' },
];

/** 高对比 Tag 配色；与连接器列表保持一致，避免 axe color-contrast。 */
const HIGH_CONTRAST_TAG_STYLES = {
  green: { color: '#237804', background: '#f6ffed', borderColor: '#b7eb8f' },
  orange: { color: '#ad4e00', background: '#fff7e6', borderColor: '#ffd591' },
  gold: { color: '#874d00', background: '#fffbe6', borderColor: '#ffe58f' },
} as const;

/**
 * 返回 Tag 的高对比内联样式。
 *
 * @param color Tag 的 preset 颜色
 * @returns 覆盖样式；红/蓝/默认色已满足 AA，返回 undefined
 */
function highContrastTagStyle(color: string) {
  return HIGH_CONTRAST_TAG_STYLES[color as keyof typeof HIGH_CONTRAST_TAG_STYLES];
}

/**
 * 连接器同步条目抽屉。
 *
 * 展示最近一次同步计数与源对象状态（PENDING/SYNCED/SKIPPED/FAILED/DELETED）、失败码；
 * `externalId` 只展示末两段或 32 字符，不展示对象原文、完整对象 key、bucket 或端点。
 * 同步进行中时提示管理员等待，页面会禁用重复触发。
 *
 * @param props API、连接器与关闭回调
 * @returns 条目抽屉
 */
export function ConnectorItemsDrawer({ api, connector, onClose }: ConnectorItemsDrawerProps) {
  const [phase, setPhase] = useState<ItemsPhase>('loading');
  const [items, setItems] = useState<ConnectorItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [filter, setFilter] = useState<ItemStatusFilter>('ALL');
  const [error, setError] = useState<string | null>(null);
  const sequence = useRef(0);

  const open = connector !== null;
  const connectorId = connector?.id;

  /**
   * 加载指定页与筛选的条目。
   *
   * 只依赖连接器 ID 而不是整个对象：页面刷新连接器列表时对象引用会变化，
   * 但不能因此把抽屉重置回第一页。
   *
   * @param targetPage 页码
   * @param targetSize 每页大小
   * @param targetStatus 状态筛选；ALL 不传
   */
  const loadItems = useCallback(
    async (targetPage: number, targetSize: number, targetStatus: ItemStatusFilter) => {
      if (!connectorId) {
        return;
      }
      const current = sequence.current + 1;
      sequence.current = current;
      setPhase('loading');
      setError(null);
      try {
        const result = await api.listConnectorItems(connectorId, {
          page: targetPage,
          size: targetSize,
          ...(targetStatus === 'ALL' ? {} : { status: targetStatus }),
        });
        if (sequence.current !== current) {
          return;
        }
        setItems(result.items);
        setTotal(result.total);
        setPhase(result.items.length === 0 ? 'empty' : 'ready');
      } catch (loadError) {
        if (sequence.current !== current) {
          return;
        }
        setError(describeConnectorError(loadError, 'items'));
        setPhase('error');
      }
    },
    [api, connectorId],
  );

  // 打开或切换连接器时回到第一页、10 条与“全部状态”，避免把上一个连接器的筛选带过来。
  useEffect(() => {
    if (!open) {
      sequence.current += 1;
      return;
    }
    setPage(1);
    setPageSize(10);
    setFilter('ALL');
    setItems([]);
    setTotal(0);
    void loadItems(1, 10, 'ALL');
  }, [open, connectorId, loadItems]);

  /**
   * 切换状态筛选；回到第一页重新加载。
   *
   * @param nextFilter 新筛选
   */
  const handleFilterChange = (nextFilter: ItemStatusFilter): void => {
    setFilter(nextFilter);
    setPage(1);
    void loadItems(1, pageSize, nextFilter);
  };

  /**
   * 切换分页。
   *
   * @param nextPage 新页码
   * @param nextSize 新每页大小
   */
  const handlePaginationChange = (nextPage: number, nextSize: number): void => {
    const targetPage = nextSize !== pageSize ? 1 : nextPage;
    setPage(targetPage);
    setPageSize(nextSize);
    void loadItems(targetPage, nextSize, filter);
  };

  const columns: TableColumnsType<ConnectorItem> = [
    {
      title: '对象（末段）',
      dataIndex: 'externalId',
      key: 'externalId',
      render: (value: string) => (
        <Typography.Text code>{formatConnectorExternalId(value)}</Typography.Text>
      ),
      width: 220,
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (status: ConnectorItemStatus) => (
        <Tag
          color={connectorItemStatusColor(status)}
          style={highContrastTagStyle(connectorItemStatusColor(status))}
        >
          {connectorItemStatusLabel(status)}
        </Tag>
      ),
    },
    {
      title: '失败原因',
      dataIndex: 'errorCode',
      key: 'errorCode',
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
      title: '代数',
      dataIndex: 'generation',
      key: 'generation',
      width: 72,
      render: (value: number) => `g${value}`,
    },
    {
      title: '最近发现',
      dataIndex: 'lastSeenAt',
      key: 'lastSeenAt',
      width: 190,
      render: (value: string | null | undefined) => formatConnectorTime(value),
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      key: 'updatedAt',
      width: 190,
      render: (value: string) => formatConnectorTime(value),
    },
  ];

  const summary = connector?.lastSyncSummary;

  return (
    <Drawer
      destroyOnHidden
      onClose={onClose}
      open={open}
      title={`同步条目：${connector?.name ?? ''}`}
      size="min(860px, 100vw)"
    >
      {connector && (
        <Flex gap={16} orientation="vertical">
          {isConnectorSyncInProgress(connector) && (
            <Alert
              description="同步已受理，Worker 正在异步执行。完成前不要重复触发手动同步，否则会被服务端以 409 拒绝。"
              showIcon
              title="同步进行中"
              type="info"
            />
          )}

          <Descriptions
            bordered
            column={1}
            items={[
              {
                key: 'status',
                label: '最近同步',
                children: (
                  <Flex align="center" gap={8} wrap>
                    <Tag
                      color={connectorSyncStatusColor(connector.lastSyncStatus)}
                      style={highContrastTagStyle(
                        connectorSyncStatusColor(connector.lastSyncStatus),
                      )}
                    >
                      {connectorSyncStatusLabel(connector.lastSyncStatus)}
                    </Tag>
                    <span>
                      list {connector.lastSyncComplete ? '完整' : '不完整；本轮不传播删除'}
                    </span>
                  </Flex>
                ),
              },
              {
                key: 'started',
                label: '开始时间',
                children: formatConnectorTime(connector.lastSyncStartedAt),
              },
              {
                key: 'finished',
                label: '结束时间',
                children: formatConnectorTime(connector.lastSyncFinishedAt),
              },
              {
                key: 'success',
                label: '最近成功',
                children: formatConnectorTime(connector.lastSuccessAt),
              },
              {
                key: 'error',
                label: '最近失败码',
                children: connector.lastErrorCode ? (
                  <span>
                    {describeConnectorErrorCode(connector.lastErrorCode)}（
                    <Typography.Text code>{connector.lastErrorCode}</Typography.Text>）
                  </span>
                ) : (
                  '—'
                ),
              },
              ...(summary
                ? [
                    {
                      key: 'summary',
                      label: '最近计数',
                      children: `扫描 ${summary.scanned}；新增 ${summary.created}；更新 ${summary.updated}；跳过 ${summary.skipped}；失败 ${summary.failed}；删除 ${summary.deleted}`,
                    },
                  ]
                : []),
            ]}
            size="small"
          />

          <Flex align="center" gap={8} justify="space-between" wrap>
            <Select<ItemStatusFilter>
              aria-label="同步条目状态筛选"
              onChange={handleFilterChange}
              options={STATUS_FILTER_OPTIONS}
              style={{ minWidth: 160 }}
              value={filter}
            />
            <Typography.Text type="secondary">
              共 {total} 条；对象只显示末段，不含完整 key、bucket 或原文。
            </Typography.Text>
          </Flex>

          {phase === 'error' ? (
            <Result
              extra={
                <Button onClick={() => void loadItems(page, pageSize, filter)} type="link">
                  重试
                </Button>
              }
              status="error"
              subTitle={error ?? '同步条目加载失败，请稍后重试。'}
              title="同步条目加载失败"
            />
          ) : phase === 'empty' ? (
            <Empty
              description={
                filter === 'ALL'
                  ? '该连接器还没有同步条目。请先完成一次同步。'
                  : '当前筛选下没有同步条目。'
              }
            />
          ) : (
            <Table<ConnectorItem>
              columns={columns}
              dataSource={items}
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
              scroll={{ x: 980 }}
              size="small"
            />
          )}
        </Flex>
      )}
    </Drawer>
  );
}
