import { Alert, Form, Input, InputNumber, Modal } from 'antd';
import { useEffect } from 'react';
import type { ModelCredential } from '@/services/model';

/** 新增/轮换 key 弹窗模式。 */
export type CredentialFormMode = 'create' | 'rotate';

/** 表单提交值；`priority` 只在新增时使用。 */
export interface CredentialFormSubmission {
  label: string;
  apiKey: string;
  priority?: number;
}

/** 服务端字段级错误；key 只用于把错误挂到对应表单项。 */
export type CredentialFieldErrors = Partial<Record<'label' | 'apiKey' | 'priority', string>>;

/** 新增/轮换 key 弹窗属性。 */
export interface CredentialFormModalProps {
  /** 模式：新增或轮换。 */
  mode: CredentialFormMode;
  /** 是否打开；关闭后销毁表单，避免明文留在 DOM。 */
  open: boolean;
  /** 端点展示名，用于标题与说明。 */
  providerName: string;
  /** 轮换目标 key；新增时为空。 */
  credential?: ModelCredential | null;
  /** 提交中，禁用重复提交。 */
  submitting: boolean;
  /** 与服务端交互失败的固定中文说明。 */
  formError?: string | null;
  /** 服务端字段级错误。 */
  fieldErrors?: CredentialFieldErrors;
  /** 取消/关闭。 */
  onCancel: () => void;
  /** 提交表单值；调用方负责清空明文状态。 */
  onSubmit: (values: CredentialFormSubmission) => void;
}

/** 表单值；InputNumber 清空时为 null。 */
interface FormValues {
  label?: string;
  apiKey?: string;
  priority?: number | null;
}

/**
 * 新增/轮换模型 key 弹窗。
 *
 * 明文只存在于密码输入框与本次请求体；弹窗销毁时清空表单，父页面不在状态或
 * 成功提示中保存 `apiKey`。轮换模式在保存前展示 AC-07 的在途影响说明，
 * 让管理员在提交前理解“新请求用新 key、在途可能可重试失败且不重复输出”。
 *
 * @param props 模式、目标 key 与提交回调
 * @returns key 表单弹窗
 */
export function CredentialFormModal({
  mode,
  open,
  providerName,
  credential,
  submitting,
  formError,
  fieldErrors,
  onCancel,
  onSubmit,
}: CredentialFormModalProps) {
  const [form] = Form.useForm<FormValues>();

  // 每次打开都重置，避免上一次的明文或字段错误残留。
  useEffect(() => {
    if (!open) {
      return;
    }
    form.resetFields();
    form.setFieldsValue(
      mode === 'rotate'
        ? { label: credential?.label ?? '', apiKey: '' }
        : { label: '', apiKey: '', priority: 100 },
    );
  }, [credential, form, mode, open]);

  // 服务端字段级错误映射到对应输入；文案由调用方固定映射，不含明文。
  useEffect(() => {
    if (!open || !fieldErrors) {
      return;
    }
    form.setFields(
      (Object.entries(fieldErrors) as Array<[keyof FormValues, string | undefined]>)
        .filter(([, message]) => Boolean(message))
        .map(([name, message]) => ({ name, errors: [message as string] })),
    );
  }, [fieldErrors, form, open]);

  /**
   * 提交前把空字符串与 null 归一；不 trim API key，避免发送与用户输入不一致的凭据。
   *
   * @param values 表单值
   */
  const handleFinish = (values: FormValues): void => {
    onSubmit({
      label: (values.label ?? '').trim(),
      apiKey: values.apiKey ?? '',
      priority: typeof values.priority === 'number' ? values.priority : undefined,
    });
  };

  const labelRules =
    mode === 'create'
      ? [
          { required: true, whitespace: true, message: '请填写 key 名称。' },
          { max: 64, message: 'key 名称不能超过 64 个字符。' },
        ]
      : [{ max: 64, message: 'key 名称不能超过 64 个字符。' }];

  return (
    <Modal
      cancelText="取消"
      confirmLoading={submitting}
      destroyOnHidden
      okText={mode === 'create' ? '新增 key' : '确认轮换'}
      onCancel={onCancel}
      onOk={() => form.submit()}
      open={open}
      styles={{ body: { maxHeight: '62vh', overflowY: 'auto' } }}
      title={
        mode === 'create' ? `为 ${providerName} 新增 key` : `轮换 key「${credential?.label ?? ''}」`
      }
      width={560}
    >
      {mode === 'create' ? (
        <Alert
          description="API key 只随本次提交发送；服务端立即加密保存。关闭后无法再次查看明文，列表只显示末 4 位掩码。"
          showIcon
          style={{ marginBottom: 16 }}
          title="敏感信息只提交一次"
          type="info"
        />
      ) : (
        <Alert
          description="保存后立即新增新 key 并停用旧 key。进行中的流式请求不会被取消，可能以可重试错误结束，且不会重复输出；新请求将使用新 key。旧 key 从此只显示掩码，页面、接口、审计与错误响应都不再出现明文。"
          showIcon
          style={{ marginBottom: 16 }}
          title="轮换影响（保存前请确认）"
          type="warning"
        />
      )}

      {formError && (
        <Alert
          aria-live="assertive"
          description={formError}
          role="alert"
          showIcon
          style={{ marginBottom: 16 }}
          title="操作未完成"
          type="error"
        />
      )}

      <Form
        autoComplete="off"
        form={form}
        layout="vertical"
        onFinish={handleFinish}
        requiredMark="optional"
      >
        <Form.Item label="key 名称" name="label" rules={labelRules}>
          <Input allowClear maxLength={64} placeholder="例如：主 key 2026-11" />
        </Form.Item>
        <Form.Item
          extra="只提交一次；提交后页面与接口只保留掩码，不显示明文。"
          label="API key"
          name="apiKey"
          rules={[
            { required: true, message: '请粘贴 API key。' },
            { min: 16, max: 512, message: 'API key 长度必须为 16–512 个字符。' },
            {
              validator: async (_rule, value: string | undefined) => {
                if (typeof value === 'string' && value !== value.trim()) {
                  throw new Error('API key 不能包含首尾空白。');
                }
              },
            },
          ]}
        >
          <Input.Password autoComplete="new-password" placeholder="粘贴 16–512 字符的 key" />
        </Form.Item>
        {mode === 'create' && (
          <Form.Item
            extra="数字越小越优先；同一端点内按优先级与健康状态选择。"
            label="优先级"
            name="priority"
            rules={[{ required: true, message: '请填写优先级。' }]}
          >
            <InputNumber max={1000} min={1} precision={0} style={{ width: '100%' }} />
          </Form.Item>
        )}
      </Form>
    </Modal>
  );
}
