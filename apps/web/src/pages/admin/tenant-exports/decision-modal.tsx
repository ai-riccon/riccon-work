import { Alert, Form, Input, Modal, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { describeAuditError } from '@/services/audit/format';
import type { AuditExportDecision, AuditExportRequest } from '@/services/audit/types';

/** 审批意见表单值。 */
interface DecisionFormValues {
  comment?: string;
}

/** 审批弹窗参数。 */
export interface DecisionModalProps {
  /** 是否打开。 */
  open: boolean;
  /** 目标导出请求；空值表示关闭。 */
  request: AuditExportRequest | null;
  /** 当前操作：批准或拒绝。 */
  decision: AuditExportDecision | null;
  /** 执行审批；由租户页或平台页注入对应 API 调用。 */
  submit: (decision: AuditExportDecision, comment: string | null) => Promise<AuditExportRequest>;
  /** 关闭回调。 */
  onClose: () => void;
  /** 审批成功回调；由页面刷新列表并提示。 */
  onDecided: (request: AuditExportRequest) => void;
  /** 平台审批模式：文案强调只处理元数据。 */
  platformMode?: boolean;
}

/**
 * 导出审批弹窗。
 *
 * 审批意见只保存在导出记录，不进入审计 details；拒绝原因会展示给申请人。
 * 弹窗不判断权限与自批，入口由页面按状态/权限/申请人隐藏。
 *
 * @param props 目标请求、决定与提交回调
 * @returns 审批弹窗
 */
export function DecisionModal({
  open,
  request,
  decision,
  submit,
  onClose,
  onDecided,
  platformMode = false,
}: DecisionModalProps) {
  const [form] = Form.useForm<DecisionFormValues>();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 每次打开或切换决定时重置表单，避免把上一次拒绝原因带到批准操作。
  useEffect(() => {
    if (open) {
      form.resetFields();
      setError(null);
    }
  }, [decision, form, open]);

  /**
   * 提交审批。
   *
   * @param values 审批意见
   */
  const handleFinish = async (values: DecisionFormValues): Promise<void> => {
    if (!decision) {
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      const comment = values.comment?.trim();
      const decided = await submit(decision, comment ? comment : null);
      onDecided(decided);
      onClose();
    } catch (submitError) {
      setError(describeAuditError(submitError, platformMode ? 'platform-decide' : 'decide'));
    } finally {
      setSubmitting(false);
    }
  };

  const approving = decision === 'APPROVE';

  return (
    <Modal
      cancelText="取消"
      confirmLoading={submitting}
      destroyOnHidden
      okButtonProps={{ danger: !approving }}
      okText={approving ? '确认批准' : '确认拒绝'}
      onCancel={onClose}
      onOk={() => form.submit()}
      open={open}
      title={approving ? '批准导出申请' : '拒绝导出申请'}
    >
      <Typography.Paragraph>
        {approving
          ? platformMode
            ? '批准后目标租户的申请人可在有效期内下载；平台管理员不能下载该文件。审批动作会写入平台审计。'
            : '批准后申请人可在有效期内下载 CSV；审批动作、范围与行数会写入审计。'
          : '拒绝后申请人不可下载；拒绝原因保存在导出记录并展示给申请人，不会写入审计 details。'}
      </Typography.Paragraph>

      {request && (
        <Typography.Paragraph type="secondary">
          申请人 {request.requestedByDisplayName ?? request.requestedBy}，预计{' '}
          {request.estimatedRowCount.toLocaleString('zh-CN')} 行。
        </Typography.Paragraph>
      )}

      {error && (
        <Alert
          aria-live="assertive"
          description={error}
          role="alert"
          showIcon
          style={{ marginBottom: 16 }}
          title="审批未完成"
          type="error"
        />
      )}

      <Form<DecisionFormValues>
        form={form}
        layout="vertical"
        onFinish={(values) => {
          void handleFinish(values);
        }}
      >
        <Form.Item
          label="审批意见（可选）"
          name="comment"
          rules={[{ max: 500, message: '审批意见不能超过 500 个字符。' }]}
        >
          <Input.TextArea
            aria-label="审批意见"
            maxLength={500}
            placeholder={approving ? '例如：范围与用途已确认' : '例如：请缩小时间范围后重新申请'}
            rows={3}
            showCount
          />
        </Form.Item>
      </Form>
    </Modal>
  );
}
