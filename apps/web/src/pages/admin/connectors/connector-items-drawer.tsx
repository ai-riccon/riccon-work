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
import type { RefObject } from 'react';
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
import { resolveTabTrapTarget } from './focus-trap';

/** 条目抽屉属性。 */
export interface ConnectorItemsDrawerProps {
  /** 连接器 API；由页面注入，测试使用替身。 */
  api: ConnectorApi;
  /** 目标连接器；null 表示关闭。 */
  connector: Connector | null;
  /** 关闭抽屉。 */
  onClose: () => void;
  /** 打开抽屉的触发元素；关闭后把焦点归还给它。 */
  returnFocusRef?: RefObject<HTMLElement | null>;
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
 * 从抽屉内容容器向上解析出 role=dialog 的面板。
 *
 * 不直接使用 antd 的 panelRef：该 ref 在首次打开与关闭动画期间存在未挂载的空档，
 * 而内容容器在抽屉打开期间始终存在，向上查找更稳定。
 *
 * @param content 抽屉内容根节点
 * @returns 抽屉面板或 null
 */
function resolveDrawerPanel(content: HTMLElement | null): HTMLElement | null {
  return content?.closest<HTMLElement>('[role="dialog"]') ?? null;
}

/**
 * 连接器同步条目抽屉。
 *
 * 展示最近一次同步计数与源对象状态（PENDING/SYNCED/SKIPPED/FAILED/DELETED）、失败码；
 * `externalId` 只展示末两段或 32 字符，不展示对象原文、完整对象 key、bucket 或端点。
 * 同步进行中时提示管理员等待，页面会禁用重复触发。
 *
 * @param props API、连接器、关闭回调与触发按钮引用
 * @returns 条目抽屉
 */
export function ConnectorItemsDrawer({
  api,
  connector,
  onClose,
  returnFocusRef,
}: ConnectorItemsDrawerProps) {
  const [phase, setPhase] = useState<ItemsPhase>('loading');
  const [items, setItems] = useState<ConnectorItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [filter, setFilter] = useState<ItemStatusFilter>('ALL');
  const [error, setError] = useState<string | null>(null);
  const sequence = useRef(0);
  // 抽屉内容根节点；向上可解析出 role=dialog 面板，用于初始焦点与 Tab 循环。
  const contentRef = useRef<HTMLDivElement | null>(null);
  // 包裹条目表格，用于给 antd 的横向滚动容器补可聚焦属性（F-002）。
  const tableHostRef = useRef<HTMLDivElement | null>(null);
  // 标记抽屉曾经打开，避免初始关闭状态误把焦点移走。
  const wasOpenRef = useRef(false);

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
   * 抽屉内容挂载回调：把焦点移入抽屉（优先关闭按钮）。
   *
   * antd Drawer 的 portal 可能晚于父组件 effect 才提交内容，只用 effect 会在首次
   * 打开时遇到空 ref；ref 回调在节点插入后执行，能稳定找到关闭按钮（F-004）。
   *
   * @param node 抽屉内容根节点；卸载时为 null
   */
  const handleContentRef = useCallback((node: HTMLDivElement | null) => {
    contentRef.current = node;
    if (!node) {
      return;
    }
    const panel = resolveDrawerPanel(node);
    if (!panel) {
      return;
    }
    const closeButton = panel.querySelector<HTMLElement>('.ant-drawer-close');
    (closeButton ?? panel).focus({ preventScroll: true });
  }, []);

  // 抽屉已打开时切换连接器或重新打开：内容未重新挂载也能把焦点移回关闭按钮。
  useEffect(() => {
    if (!open) {
      return;
    }
    const panel = resolveDrawerPanel(contentRef.current);
    if (!panel) {
      return;
    }
    const closeButton = panel.querySelector<HTMLElement>('.ant-drawer-close');
    (closeButton ?? panel).focus({ preventScroll: true });
  }, [open, connectorId]);

  // 抽屉内 Tab 循环：焦点在抽屉内时拦截首/尾的 Tab，避免焦点逃到页面 BODY
  // （antd Drawer 的默认焦点锁只处理 focusin，焦点移出文档时不可靠）。
  // 只在焦点已位于抽屉内时处理，不干扰 Select 等挂到 body 的浮层。
  useEffect(() => {
    if (!open) {
      return;
    }
    /**
     * 处理文档级 Tab 键；首尾环绕。
     *
     * @param event 键盘事件
     */
    const handleKeyDown = (event: KeyboardEvent): void => {
      if (event.key !== 'Tab') {
        return;
      }
      const panel = resolveDrawerPanel(contentRef.current);
      if (!panel) {
        return;
      }
      const result = resolveTabTrapTarget(
        panel,
        document.activeElement as HTMLElement | null,
        event.shiftKey,
      );
      if (result.preventDefault) {
        event.preventDefault();
        result.target?.focus({ preventScroll: true });
      }
    };
    document.addEventListener('keydown', handleKeyDown, true);
    return () => document.removeEventListener('keydown', handleKeyDown, true);
  }, [open]);

  // 关闭后把焦点还给触发按钮；覆盖 Escape、遮罩、关闭按钮与父级联动关闭（F-004）。
  // 延后一拍，等 antd 释放焦点锁与关闭动画开始，避免焦点又被拉回抽屉。
  useEffect(() => {
    if (open) {
      wasOpenRef.current = true;
      return;
    }
    if (!wasOpenRef.current) {
      return;
    }
    wasOpenRef.current = false;
    const trigger = returnFocusRef?.current;
    if (!trigger) {
      return;
    }
    const timer = window.setTimeout(() => trigger.focus({ preventScroll: true }), 0);
    return () => window.clearTimeout(timer);
  }, [open, returnFocusRef]);

  // antd Table 的横向滚动容器 `.ant-table-content` 默认不可聚焦，axe 会报
  // scrollable-region-focusable（serious）。条目表格没有其他可聚焦内容，因此在表格
  // 渲染后补 tabindex/role 与可读名称，让键盘用户可聚焦并用方向键横向滚动（F-002）。
  useEffect(() => {
    if (!open) {
      return;
    }
    const scroller = tableHostRef.current?.querySelector<HTMLElement>('.ant-table-content');
    if (!scroller) {
      return;
    }
    scroller.setAttribute('tabindex', '0');
    scroller.setAttribute('role', 'region');
    scroller.setAttribute('aria-label', '同步条目表格（可横向滚动）');
  }, [open, phase, items, page, pageSize, filter]);

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
      focusable={{ trap: true, focusTriggerAfterClose: true }}
      onClose={onClose}
      open={open}
      title={`同步条目：${connector?.name ?? ''}`}
      size="min(860px, 100vw)"
    >
      {connector && (
        <Flex gap={16} orientation="vertical" ref={handleContentRef}>
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
            <div ref={tableHostRef}>
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
            </div>
          )}
        </Flex>
      )}
    </Drawer>
  );
}
