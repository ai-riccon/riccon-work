import { Alert, Form, Input, Modal, Typography } from 'antd';
import { useEffect } from 'react';
import type { OidcUnbindRequest, UserSummary } from '@/services/api/types';
import { identityProviderSummary } from '@/services/auth/identity-provider';

/** 解绑弹窗参数。 */
export interface OidcUnbindModalProps {
  /** 是否打开。 */
  open: boolean;
  /** 目标账号；关闭时可为 null。 */
  user: UserSummary | null;
  /** 提交中。 */
  submitting: boolean;
  /** 父级 API 错误文案。 */
  error: string | null;
  /** 取消。 */
  onCancel: () => void;
  /** 提交解绑。 */
  onSubmit: (input: OidcUnbindRequest) => void;
}

/** 解绑表单值。 */
interface UnbindFormValues {
  newPassword: string;
  confirmPassword: string;
}

/**
 * 校验新密码长度。
 *
 * 与 OpenAPI `OidcUnbindRequest.newPassword` 的 12–200 字符一致；
 * 不做 trim，避免用户以为输入了某密码但服务端保存了另一个值。
 *
 * @param value 表单值
 * @returns 错误文案；通过时返回 null
 */
export function newPasswordError(value: string | undefined): string | null {
  const password = value ?? '';
  if (!password) {
    return '请输入新的本地密码';
  }
  if (password.length < 12) {
    return '新密码至少 12 个字符';
  }
  if (password.length > 200) {
    return '新密码不能超过 200 个字符';
  }
  return null;
}

/**
 * 解除 OIDC 绑定并恢复本地密码弹窗。
 *
 * 没有“无凭据解绑”：必须设置新密码，避免账号变成不可登录状态。
 *
 * @param props 目标账号、提交状态、错误与回调
 * @returns 解绑弹窗
 */
export function OidcUnbindModal({
  open,
  user,
  submitting,
  error,
  onCancel,
  onSubmit,
}: OidcUnbindModalProps) {
  const [form] = Form.useForm<UnbindFormValues>();

  // 每次打开都清空密码，避免复用上一个账号的输入。
  useEffect(() => {
    if (open) {
      form.resetFields();
    }
  }, [form, open, user?.id]);

  return (
    <Modal
      confirmLoading={submitting}
      destroyOnHidden
      okText="确认解绑并恢复本地账号"
      onCancel={onCancel}
      onOk={() => form.submit()}
      open={open}
      title={user ? `解除企业账号绑定：${user.displayName}` : '解除企业账号绑定'}
    >
      {user && (
        <Typography.Paragraph type="secondary">
          {/* 详情沿用服务端脱敏来源；OIDC 显示 providerName 或 issuer host，不显示 subject/token。 */}
          {identityProviderSummary(user)}
        </Typography.Paragraph>
      )}
      <Alert
        description="解绑后账号恢复为内置账号，必须使用新设置的本地密码登录。请妥善转交密码；系统不会显示已设置的密码。"
        showIcon
        style={{ marginBottom: 16 }}
        title="解绑会恢复本地密码"
        type="warning"
      />
      {error && (
        <Alert
          aria-live="assertive"
          description={error}
          role="alert"
          showIcon
          style={{ marginBottom: 16 }}
          title="解绑失败"
          type="error"
        />
      )}
      <Form<UnbindFormValues>
        autoComplete="new-password"
        form={form}
        layout="vertical"
        onFinish={(values) => {
          onSubmit({ newPassword: values.newPassword });
        }}
        preserve={false}
        requiredMark={false}
      >
        <Form.Item
          extra="12–200 个字符。"
          label="新的本地密码"
          name="newPassword"
          rules={[
            {
              validator: async (_rule, value: string | undefined) => {
                const message = newPasswordError(value);
                if (message) {
                  throw new Error(message);
                }
              },
            },
          ]}
        >
          <Input.Password autoComplete="new-password" placeholder="请输入新的本地密码" />
        </Form.Item>
        <Form.Item
          dependencies={['newPassword']}
          label="确认新密码"
          name="confirmPassword"
          rules={[
            { required: true, message: '请再次输入新密码' },
            ({ getFieldValue }) => ({
              validator: async (_rule, value: string | undefined) => {
                if (value && value !== getFieldValue('newPassword')) {
                  throw new Error('两次输入的密码不一致');
                }
              },
            }),
          ]}
        >
          <Input.Password autoComplete="new-password" placeholder="请再次输入新密码" />
        </Form.Item>
        <Typography.Text type="secondary">
          解绑会写入审计；如果 IdP 配置有误，这是恢复账号登录的推荐方式。
        </Typography.Text>
      </Form>
    </Modal>
  );
}
