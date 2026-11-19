import { Alert, Descriptions, Modal, Typography } from 'antd';
import { useEffect, useState } from 'react';
import type { AuditApi } from '@/services/audit/api';
import { describeAuditError, describeAuditFilter } from '@/services/audit/format';
import type { AuditExportRequest, AuditFilter, AuditPolicy } from '@/services/audit/types';

/** 导出申请弹窗参数。 */
export interface ExportRequestModalProps {
  /** 是否打开。 */
  open: boolean;
  /** 审计 API。 */
  api: AuditApi;
  /** 当前查询条件；弹窗只展示摘要，不再重复编辑。 */
  filter: AuditFilter;
  /** 审计策略，用于展示 TTL 与行数上限。 */
  policy: AuditPolicy;
  /** 关闭回调。 */
  onClose: () => void;
  /** 创建成功回调；由页面刷新列表并提示。 */
  onCreated: (request: AuditExportRequest) => void;
}

/**
 * 审计导出申请弹窗。
 *
 * 申请条件直接复用审计页当前查询条件，避免“看到的列表”和“导出的范围”不一致。
 * 预计行数由服务端按同一 SQL 条件计算，界面不提供客户端自报入口。
 *
 * @param props 当前条件、策略与回调
 * @returns 导出申请弹窗
 */
export function ExportRequestModal({
  open,
  api,
  filter,
  policy,
  onClose,
  onCreated,
}: ExportRequestModalProps) {
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 每次打开重置上一次错误，避免关闭后残留失败提示。
  useEffect(() => {
    if (open) {
      setError(null);
    }
  }, [open]);

  /**
   * 提交导出申请。
   */
  const handleSubmit = async (): Promise<void> => {
    setSubmitting(true);
    setError(null);
    try {
      const created = await api.createExport(filter);
      onCreated(created);
      onClose();
    } catch (submitError) {
      setError(describeAuditError(submitError, 'create'));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      destroyOnHidden
      okText="提交申请"
      onCancel={onClose}
      onOk={() => {
        void handleSubmit();
      }}
      open={open}
      confirmLoading={submitting}
      cancelText="取消"
      title="申请导出审计"
    >
      <Typography.Paragraph>
        导出会按当前查询条件生成 CSV，需另一位管理员批准后才能下载。申请人不能审批自己的请求。
      </Typography.Paragraph>

      {error && (
        <Alert
          aria-live="assertive"
          description={error}
          role="alert"
          showIcon
          style={{ marginBottom: 16 }}
          title="申请未提交"
          type="error"
        />
      )}

      <Descriptions
        bordered
        column={1}
        items={[
          { key: 'filter', label: '条件摘要', children: describeAuditFilter(filter) },
          {
            key: 'estimate',
            label: '预计行数',
            children: '提交后由服务端按同一条件计算；超过上限会被拒绝。',
          },
          {
            key: 'limits',
            label: '导出上限',
            children: `${policy.exportLimits.rowLimit.toLocaleString('zh-CN')} 行；批准后 ${
              policy.exportLimits.ttlHours
            } 小时内可下载。`,
          },
          {
            key: 'fields',
            label: '内容范围',
            children:
              '只包含审计字段白名单；不包含密码哈希、会话令牌、模型/MCP 凭据、文件原文、邮箱与来源 IP。',
          },
        ]}
        size="small"
      />
    </Modal>
  );
}
