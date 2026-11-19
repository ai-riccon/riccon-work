import { Alert, Form, Input, Modal, Typography } from 'antd';
import { useEffect } from 'react';
import type { OidcBindingRequest, UserSummary } from '@/services/api/types';
import { identityProviderSummary } from '@/services/auth/identity-provider';

/** 绑定弹窗参数。 */
export interface OidcBindingModalProps {
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
  /** 提交绑定。 */
  onSubmit: (input: OidcBindingRequest) => void;
}

/** 绑定表单值。 */
interface BindingFormValues {
  issuer: string;
  subject: string;
}

/**
 * 校验 issuer：必须是最长 255 字符的绝对 http(s) URL。
 *
 * 客户端无法得知租户配置的 issuer，因此这里只做格式校验；
 * 是否与租户配置一致由服务端返回 400 `RICCON-OIDC-400` 决定。
 *
 * @param value 表单值
 * @returns 错误文案；通过时返回 null
 */
export function issuerError(value: string | undefined): string | null {
  const issuer = value ?? '';
  if (!issuer) {
    return '请输入 issuer';
  }
  if (issuer.length > 255) {
    return 'issuer 不能超过 255 个字符';
  }
  try {
    const url = new URL(issuer);
    if (!['http:', 'https:'].includes(url.protocol) || !url.hostname) {
      return 'issuer 必须是绝对 http(s) URL';
    }
  } catch {
    return 'issuer 必须是绝对 http(s) URL';
  }
  return null;
}

/**
 * 校验 subject：1–255 字符，保留原值，不允许首尾空白。
 *
 * subject 是 IdP 的 `sub`，必须与 ID Token 中的值完全一致，因此不能 trim 后提交。
 *
 * @param value 表单值
 * @returns 错误文案；通过时返回 null
 */
export function subjectError(value: string | undefined): string | null {
  const subject = value ?? '';
  if (!subject) {
    return '请输入 subject';
  }
  if (subject.length > 255) {
    return 'subject 不能超过 255 个字符';
  }
  if (subject !== subject.trim()) {
    return 'subject 不能包含首尾空白；请与 IdP 返回的 sub 完全一致';
  }
  return null;
}

/**
 * 绑定企业账号弹窗。
 *
 * 只提交 issuer + subject；绑定成功后服务端会删除本地密码并写入审计。
 * 错误以服务端状态为准，界面不把英文 detail 直接展示给用户。
 *
 * @param props 目标账号、提交状态、错误与回调
 * @returns 绑定弹窗
 */
export function OidcBindingModal({
  open,
  user,
  submitting,
  error,
  onCancel,
  onSubmit,
}: OidcBindingModalProps) {
  const [form] = Form.useForm<BindingFormValues>();

  // 每次打开或切换账号都重置表单，避免把上一个账号的 issuer/subject 带给另一个账号。
  useEffect(() => {
    if (open) {
      form.resetFields();
    }
  }, [form, open, user?.id]);

  return (
    <Modal
      confirmLoading={submitting}
      destroyOnHidden
      okText="确认绑定"
      onCancel={onCancel}
      onOk={() => form.submit()}
      open={open}
      title={user ? `绑定企业账号：${user.displayName}` : '绑定企业账号'}
    >
      {user && (
        <Typography.Paragraph type="secondary">
          {/* 详情沿用服务端脱敏来源；LOCAL 显示“本地账号”，不显示邮箱或完整 issuer。 */}
          {identityProviderSummary(user)}
        </Typography.Paragraph>
      )}
      <Alert
        description="绑定后将删除该账号的本地密码，账号只能通过企业身份提供方登录；角色与部门归属不变。请确认 subject 与 IdP 返回的 sub 完全一致。"
        showIcon
        style={{ marginBottom: 16 }}
        title="绑定会切换认证来源"
        type="warning"
      />
      {error && (
        <Alert
          aria-live="assertive"
          description={error}
          role="alert"
          showIcon
          style={{ marginBottom: 16 }}
          title="绑定失败"
          type="error"
        />
      )}
      <Form<BindingFormValues>
        autoComplete="off"
        form={form}
        layout="vertical"
        onFinish={(values) => {
          onSubmit({ issuer: values.issuer, subject: values.subject });
        }}
        preserve={false}
        requiredMark={false}
      >
        <Form.Item
          extra="必须与租户当前配置的 issuer 规范化后完全一致。"
          label="issuer"
          name="issuer"
          rules={[
            {
              validator: async (_rule, value: string | undefined) => {
                const message = issuerError(value);
                if (message) {
                  throw new Error(message);
                }
              },
            },
          ]}
        >
          <Input placeholder="https://idp.example.com/realms/acme" />
        </Form.Item>
        <Form.Item
          extra="IdP 的 sub，不是邮箱；系统不会按邮箱自动绑定或合并账号。"
          label="subject"
          name="subject"
          rules={[
            {
              validator: async (_rule, value: string | undefined) => {
                const message = subjectError(value);
                if (message) {
                  throw new Error(message);
                }
              },
            },
          ]}
        >
          <Input placeholder="例如 248289761001" />
        </Form.Item>
        <Typography.Text type="secondary">subject 会被哈希后写入审计，不记录原文。</Typography.Text>
      </Form>
    </Modal>
  );
}
