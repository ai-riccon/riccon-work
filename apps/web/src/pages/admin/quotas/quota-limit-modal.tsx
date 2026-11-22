import { Alert, Form, InputNumber, Modal, Typography } from 'antd';
import { useEffect, useState } from 'react';
import {
  describeQuotaError,
  findQuotaWindow,
  type PlatformQuotaRow,
  type QuotaApi,
} from '@/services/quota';

/** 限额表单值；InputNumber 清空后为 null/undefined，统一按“明确不限”提交。 */
interface LimitFormValues {
  daily?: number | null;
  monthly?: number | null;
}

/** 编辑弹窗属性。 */
export interface QuotaLimitModalProps {
  /** 配额 API；由页面注入，测试使用替身。 */
  api: QuotaApi;
  /** 目标租户；null 表示弹窗关闭。 */
  tenant: PlatformQuotaRow | null;
  /** 关闭弹窗。 */
  onClose: () => void;
  /** 保存成功；返回服务端最新配额。 */
  onSaved: (quota: PlatformQuotaRow) => void;
}

/**
 * 平台租户配额编辑弹窗。
 *
 * 保存为“整体替换”：日/月两个键总是同时提交，空值提交为 JSON null（明确不限）。
 * 这是一个不可逆的写操作，因此弹窗内固定说明不限语义、审计与生效时机；
 * 409（租户停用）与 404（租户移除）在弹窗内展示，不自动重试。
 *
 * @param props API、目标租户与回调
 * @returns 配额编辑弹窗
 */
export function QuotaLimitModal({ api, tenant, onClose, onSaved }: QuotaLimitModalProps) {
  const [form] = Form.useForm<LimitFormValues>();
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 打开或切换目标租户时，用服务端生效限额填充表单；关闭时只清错误，不调用
  // form.resetFields()：Modal 关闭后 Form 已卸载，rc-field-form 会因此打印
  // “useForm is not connected” 警告，而重新打开时 setFieldsValue 会覆盖旧值。
  useEffect(() => {
    if (!tenant) {
      setError(null);
      return;
    }
    form.setFieldsValue({
      daily: findQuotaWindow(tenant.windows, 'DAILY')?.limit ?? null,
      monthly: findQuotaWindow(tenant.windows, 'MONTHLY')?.limit ?? null,
    });
    setError(null);
  }, [tenant, form]);

  /**
   * 校验并提交整体替换。
   *
   * InputNumber 的 min/precision 只约束交互；这里再次校验，防止测试替身、
   * 粘贴或浏览器实现差异提交负数/小数。
   */
  const handleOk = async (): Promise<void> => {
    if (!tenant) {
      return;
    }
    let values: LimitFormValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    const daily = values.daily ?? null;
    const monthly = values.monthly ?? null;
    const invalid = [daily, monthly].some(
      (value) => value !== null && (!Number.isInteger(value) || value < 0),
    );
    if (invalid) {
      setError('限额必须为 0 或正整数；留空表示不限。');
      return;
    }

    setSaving(true);
    setError(null);
    try {
      const updated = await api.updateTenantQuota(tenant.tenantId, {
        limits: { daily, monthly },
      });
      onSaved({
        ...tenant,
        ...updated,
        tenantName: updated.tenantName ?? tenant.tenantName,
      });
    } catch (saveError) {
      setError(describeQuotaError(saveError, 'limit-save'));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      cancelButtonProps={{ autoInsertSpace: false, disabled: saving }}
      cancelText="取消"
      confirmLoading={saving}
      destroyOnHidden
      mask={{ closable: false }}
      okButtonProps={{ autoInsertSpace: false }}
      okText="保存限额"
      onCancel={onClose}
      onOk={() => void handleOk()}
      open={tenant !== null}
      title={
        tenant ? `调整租户配额：${tenant.tenantName?.trim() || tenant.tenantCode}` : '调整租户配额'
      }
    >
      <Alert
        description="保存时同时写入日/月两个窗口；留空或 0 表示“明确不限”，不是禁止调用。保存后写入 quota.limit.changed 审计，审计失败则本次修改不生效。"
        showIcon
        title="整体替换与不限语义"
        type="info"
      />

      {error && (
        <Alert
          aria-live="assertive"
          description={error}
          role="alert"
          showIcon
          style={{ marginTop: 12 }}
          title="保存未完成"
          type="error"
        />
      )}

      <Form
        form={form}
        layout="vertical"
        name="quota-limit-form"
        preserve={false}
        style={{ marginTop: 12 }}
      >
        <Form.Item
          extra="Asia/Shanghai 自然日 00:00 重置；留空表示不限。"
          label="日限额（次/自然日）"
          name="daily"
        >
          <InputNumber min={0} placeholder="留空表示不限" precision={0} style={{ width: '100%' }} />
        </Form.Item>
        <Form.Item
          extra="Asia/Shanghai 自然月 1 日 00:00 重置；留空表示不限。"
          label="月限额（次/自然月）"
          name="monthly"
        >
          <InputNumber min={0} placeholder="留空表示不限" precision={0} style={{ width: '100%' }} />
        </Form.Item>
      </Form>

      <Typography.Text type="secondary">
        限额按请求数计；本轮已准入的请求不受影响，修改在下一次新建运行准入时生效。
      </Typography.Text>
    </Modal>
  );
}
