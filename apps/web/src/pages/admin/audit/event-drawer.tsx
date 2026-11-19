import { Descriptions, Drawer, Tag, Typography } from 'antd';
import type { AuditEvent } from '@/services/audit/types';
import {
  actorTypeLabel,
  formatActor,
  formatAuditTarget,
  formatAuditTime,
  outcomeColor,
  outcomeLabel,
} from '@/services/audit/format';

/** 审计事件详情抽屉参数。 */
export interface AuditEventDrawerProps {
  /** 选中的事件；空值表示关闭。 */
  event: AuditEvent | null;
  /** 关闭回调。 */
  onClose: () => void;
}

/**
 * 审计事件详情抽屉。
 *
 * 只展示服务端返回的字段：`details` 已在读取时通过 action-aware 白名单，
 * 界面不提供编辑、删除或重放入口。原始 `occurredAt` 保留带偏移的 ISO 8601。
 *
 * @param props 事件与关闭回调
 * @returns 事件详情抽屉
 */
export function AuditEventDrawer({ event, onClose }: AuditEventDrawerProps) {
  return (
    <Drawer
      destroyOnHidden
      onClose={onClose}
      open={event !== null}
      title="审计事件详情"
      width="min(520px, 100vw)"
    >
      {event && (
        <div className="audit-event-detail">
          <Descriptions column={1} items={descriptionItems(event)} size="small" />
          <Typography.Title level={5}>附加详情</Typography.Title>
          {event.details && Object.keys(event.details).length > 0 ? (
            <pre aria-label="事件附加详情 JSON" className="audit-details-json">
              {JSON.stringify(event.details, null, 2)}
            </pre>
          ) : (
            <Typography.Paragraph type="secondary">
              该事件没有附加详情；字段白名单会在读取时过滤敏感键。
            </Typography.Paragraph>
          )}
        </div>
      )}
    </Drawer>
  );
}

/**
 * 组装事件详情字段。
 *
 * @param event 审计事件
 * @returns Descriptions 的 items
 */
function descriptionItems(event: AuditEvent) {
  return [
    {
      key: 'eventId',
      label: '事件 ID',
      children: <Typography.Text code>{event.eventId}</Typography.Text>,
    },
    {
      key: 'occurredAt',
      label: '发生时间',
      children: `${formatAuditTime(event.occurredAt)}（${event.occurredAt}）`,
    },
    { key: 'tenantId', label: '租户', children: event.tenantId },
    {
      key: 'actor',
      label: '操作者',
      children: `${formatActor(event.actorDisplayName, event.actorId)}（${actorTypeLabel(
        event.actorType,
      )}）`,
    },
    {
      key: 'action',
      label: '动作',
      children: <Typography.Text code>{event.action}</Typography.Text>,
    },
    {
      key: 'target',
      label: '对象',
      children: formatAuditTarget(event.targetType, event.targetId),
    },
    {
      key: 'outcome',
      label: '结果',
      children: <Tag color={outcomeColor(event.outcome)}>{outcomeLabel(event.outcome)}</Tag>,
    },
    {
      key: 'traceId',
      label: '追踪 ID',
      children: (
        <Typography.Text code copyable>
          {event.traceId}
        </Typography.Text>
      ),
    },
  ];
}
