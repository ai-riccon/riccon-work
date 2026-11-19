import { Alert, Descriptions, Drawer, Tag, Typography } from 'antd';
import {
  describeAuditFilter,
  exportDownloadState,
  exportStatusColor,
  exportStatusLabel,
  formatAuditTime,
  formatRowCount,
} from '@/services/audit/format';
import type { AuditExportRequest } from '@/services/audit/types';

/** 导出详情抽屉参数。 */
export interface ExportDetailDrawerProps {
  /** 选中的导出请求；空值表示关闭。 */
  request: AuditExportRequest | null;
  /** 当前账号 ID；用于判断是否申请人。 */
  currentUserId: string | null;
  /** 关闭回调。 */
  onClose: () => void;
  /** 平台审批模式：只展示元数据，不展示下载入口说明。 */
  platformMode?: boolean;
}

/**
 * 审计导出请求详情抽屉。
 *
 * 展示申请条件、预计行数、审批人与决定、过期时间；不含审计事件内容，
 * 也不提供文件预览。下载能力由列表按“申请人 + 已批准 + 未过期”控制。
 *
 * @param props 请求、当前账号与模式
 * @returns 导出详情抽屉
 */
export function ExportDetailDrawer({
  request,
  currentUserId,
  onClose,
  platformMode = false,
}: ExportDetailDrawerProps) {
  const download = request
    ? exportDownloadState(request, currentUserId)
    : { canDownload: false, reason: '' };

  return (
    <Drawer
      destroyOnHidden
      onClose={onClose}
      open={request !== null}
      title="审计导出详情"
      width="min(560px, 100vw)"
    >
      {request && (
        <div className="export-detail">
          <Descriptions
            bordered
            column={1}
            items={[
              {
                key: 'id',
                label: '导出请求',
                children: <Typography.Text code>{request.id}</Typography.Text>,
              },
              {
                key: 'status',
                label: '状态',
                children: (
                  <Tag color={exportStatusColor(request.status)}>
                    {exportStatusLabel(request.status)}
                  </Tag>
                ),
              },
              {
                key: 'requester',
                label: '申请人',
                children: `${request.requestedByDisplayName ?? '未命名账号'}（${
                  request.requestedBy
                }）`,
              },
              {
                key: 'filter',
                label: '条件摘要',
                children: describeAuditFilter(request.filter),
              },
              {
                key: 'rows',
                label: '预计行数',
                children: formatRowCount(request.estimatedRowCount),
              },
              {
                key: 'createdAt',
                label: '申请时间',
                children: formatAuditTime(request.createdAt),
              },
              {
                key: 'decidedAt',
                label: '审批时间',
                children: formatAuditTime(request.decidedAt),
              },
              {
                key: 'approver',
                label: '审批人',
                children: request.approvedBy
                  ? `${request.approvedByDisplayName ?? '未命名账号'}（${request.approvedBy}）`
                  : '—',
              },
              {
                key: 'comment',
                label: '审批意见',
                children: request.decisionComment ?? '—',
              },
              {
                key: 'expiresAt',
                label: '下载截止',
                children: formatAuditTime(request.expiresAt),
              },
              {
                key: 'download',
                label: '下载',
                children: platformMode
                  ? '平台审批只处理元数据，平台管理员不能下载导出文件。'
                  : download.reason,
              },
            ]}
            size="small"
          />

          <Alert
            description="导出只包含审计字段白名单：不含密码哈希、会话令牌、模型/MCP 凭据、文件原文、邮箱与来源 IP；过期后不可下载。"
            showIcon
            style={{ marginTop: 16 }}
            title="内容范围"
            type="info"
          />
        </div>
      )}
    </Drawer>
  );
}
