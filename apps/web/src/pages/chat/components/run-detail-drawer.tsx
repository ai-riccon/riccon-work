import {
  Alert,
  Button,
  Descriptions,
  Drawer,
  Empty,
  Flex,
  Space,
  Spin,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { TableColumnsType } from 'antd';
import { useEffect, useState } from 'react';
import type { CSSProperties } from 'react';
import type { RicconApi } from '@/services/api/client';
import { ApiError, describeApiError } from '@/services/api/errors';
import type { ModelInvocation, RunDetail } from '@/services/api/types';
import { formatDuration, formatDateTime } from '@/services/research/format';
import {
  formatTokenUsage,
  invocationStatusColor,
  invocationStatusLabel,
  modelPurposeLabel,
  runKindLabel,
  runStageLabel,
  runStatusColor,
  runStatusLabel,
} from '@/services/runs/format';

/** 运行详情加载阶段。 */
type RunDetailPhase = 'idle' | 'loading' | 'ready' | 'error';

/**
 * 状态 Tag 的高对比配色。
 *
 * antd 默认 success/warning 填充色在 12px 字号下对比度不足（axe color-contrast），
 * 这里统一改为深色文字 + 浅色背景，满足 WCAG 2.1 AA；与 J1 文件解析状态标签保持一致。
 */
const STATUS_TAG_STYLES: Record<
  'default' | 'processing' | 'warning' | 'success' | 'error',
  CSSProperties
> = {
  default: { color: '#595959', background: '#fafafa', borderColor: '#d9d9d9' },
  processing: { color: '#0958d9', background: '#e6f4ff', borderColor: '#91caff' },
  warning: { color: '#ad6800', background: '#fffbe6', borderColor: '#ffe58f' },
  success: { color: '#237804', background: '#f6ffed', borderColor: '#b7eb8f' },
  error: { color: '#a8071a', background: '#fff1f0', borderColor: '#ffa39e' },
};

/** 运行详情抽屉属性。 */
export interface RunDetailDrawerProps {
  /** API 客户端；由页面注入，测试使用 fake。 */
  api: RicconApi;
  /** 目标运行 ID；null 表示关闭。 */
  runId: string | null;
  /** 关闭抽屉。 */
  onClose: () => void;
}

/**
 * J1 运行详情抽屉。
 *
 * 只在用户打开时请求 `GET /api/v1/runs/{runId}`，避免影响正在进行的主回答流；
 * 请求失败或记录为空时保留独立状态与重试入口，不伪造模型调用数据。
 *
 * @param props API、运行 ID 与关闭回调
 * @returns 运行详情抽屉
 */
export function RunDetailDrawer({ api, runId, onClose }: RunDetailDrawerProps) {
  const [phase, setPhase] = useState<RunDetailPhase>('idle');
  const [detail, setDetail] = useState<RunDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [reloadToken, setReloadToken] = useState(0);

  // runId 变化或用户重试时重新加载；cancelled 标记丢弃过期响应，避免快速切换运行记录时串台。
  useEffect(() => {
    if (!runId) {
      setPhase('idle');
      setDetail(null);
      setError(null);
      return;
    }
    let cancelled = false;
    setPhase('loading');
    setDetail(null);
    setError(null);
    void api
      .getRun(runId)
      .then((result) => {
        if (cancelled) {
          return;
        }
        setDetail(result);
        setPhase('ready');
      })
      .catch((loadError: unknown) => {
        if (cancelled) {
          return;
        }
        setError(describeRunError(loadError));
        setPhase('error');
      });
    return () => {
      cancelled = true;
    };
  }, [api, reloadToken, runId]);

  return (
    <Drawer
      destroyOnHidden
      onClose={onClose}
      open={Boolean(runId)}
      size="min(760px, 96vw)"
      title="运行详情"
    >
      {phase === 'loading' && (
        <Flex align="center" aria-busy="true" gap={12} justify="center" style={{ padding: 32 }}>
          <Spin />
          <Typography.Text>正在加载运行详情…</Typography.Text>
        </Flex>
      )}

      {phase === 'error' && (
        <Alert
          action={
            <Button
              onClick={() => {
                setReloadToken((token) => token + 1);
              }}
              size="small"
            >
              重试
            </Button>
          }
          description={error}
          showIcon
          title="运行详情加载失败"
          type="error"
        />
      )}

      {phase === 'ready' && detail && <RunDetailContent detail={detail} />}
    </Drawer>
  );
}

/**
 * 运行详情主体：运行摘要与模型调用记录。
 *
 * @param props 运行详情
 * @returns 详情内容
 */
function RunDetailContent({ detail }: { detail: RunDetail }) {
  const summaryItems = [
    {
      key: 'status',
      label: '状态',
      children: (
        <Tag style={STATUS_TAG_STYLES[runStatusColor(detail.status)]}>
          {runStatusLabel(detail.status)}
        </Tag>
      ),
    },
    { key: 'kind', label: '类别', children: runKindLabel(detail.kind) },
    { key: 'stage', label: '当前阶段', children: runStageLabel(detail.currentStage) },
    { key: 'attempt', label: '尝试', children: `第 ${detail.attempt} 次` },
    { key: 'startedAt', label: '开始时间', children: formatDateTime(detail.startedAt) },
    { key: 'finishedAt', label: '结束时间', children: formatDateTime(detail.finishedAt) },
    ...(detail.errorCode
      ? [
          { key: 'errorCode', label: '错误码', children: detail.errorCode },
          {
            key: 'errorMessage',
            label: '失败原因',
            children: detail.errorMessage || '—',
            span: 2,
          },
        ]
      : []),
  ];

  const invocationColumns: TableColumnsType<ModelInvocation> = [
    {
      title: '模型',
      dataIndex: 'modelKey',
      key: 'modelKey',
      width: 150,
      render: (value: string, record) => (
        <Typography.Text title={record.modelId}>{value || '—'}</Typography.Text>
      ),
    },
    {
      title: '用途',
      dataIndex: 'purpose',
      key: 'purpose',
      width: 92,
      render: (value: ModelInvocation['purpose']) => modelPurposeLabel(value),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 88,
      render: (value: ModelInvocation['status']) => (
        <Tag style={STATUS_TAG_STYLES[invocationStatusColor(value)]}>
          {invocationStatusLabel(value)}
        </Tag>
      ),
    },
    {
      title: '首字',
      dataIndex: 'firstTokenMs',
      key: 'firstTokenMs',
      width: 88,
      render: (value: number | null | undefined) => formatDuration(value),
    },
    {
      title: '耗时',
      dataIndex: 'durationMs',
      key: 'durationMs',
      width: 88,
      render: (value: number | null | undefined) => formatDuration(value),
    },
    {
      title: 'Token（入/出）',
      dataIndex: 'promptTokens',
      key: 'tokens',
      width: 124,
      render: (_value: number | null | undefined, record) =>
        formatTokenUsage(record.promptTokens, record.completionTokens),
    },
    {
      title: '错误码',
      dataIndex: 'errorCode',
      key: 'errorCode',
      width: 150,
      render: (value: string | null | undefined) => value || '—',
    },
  ];

  return (
    <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
      <Descriptions bordered column={2} items={summaryItems} size="small" />

      <div>
        <Typography.Title level={5} style={{ marginTop: 0 }}>
          模型调用
        </Typography.Title>
        <Table<ModelInvocation>
          columns={invocationColumns}
          dataSource={detail.modelInvocations}
          locale={{
            emptyText: (
              <Empty
                description="本次运行没有模型调用记录。"
                image={Empty.PRESENTED_IMAGE_SIMPLE}
              />
            ),
          }}
          pagination={false}
          rowKey="id"
          scroll={{ x: 780 }}
          size="small"
        />
      </div>
    </Space>
  );
}

/**
 * 把加载失败归一为适合运行详情展示的文案。
 *
 * @param error 任意异常
 * @returns 中文错误消息
 */
function describeRunError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 403) {
      return '没有查看运行记录的权限。';
    }
    if (error.status === 404) {
      return '运行记录不存在或已被删除。';
    }
  }
  return describeApiError(error, '运行详情加载失败，请重试。');
}
