import { useNavigate } from '@umijs/max';
import { Alert, Button, Card, Flex, Form, Input, Result, Spin, Typography } from 'antd';
import { useEffect, useState } from 'react';
import type { RicconApi } from '@/services/api/client';
import { ApiError, describeApiError } from '@/services/api/errors';
import { api as defaultApi } from '@/services/api';
import { IS_MOCK_API, MOCK_CREDENTIALS } from '@/services/api/config';
import type { LoginRequest } from '@/services/api/types';
import { authStore, useAuth } from '@/services/auth/auth-store';

/** 登录页可注入依赖，便于组件测试不发起真实网络请求。 */
export interface LoginPageProps {
  api?: RicconApi;
  navigate?: (to: string, options?: { replace?: boolean }) => void;
}

/**
 * 员工登录页。
 *
 * 流程：获取会话状态 → 输入租户代码/账号/密码 → 登录 → 进入 /chat。
 * 错误文案遵循 M02-AC-01：凭据失败不区分租户、账号或密码，避免账号枚举。
 *
 * @param props API 与导航注入（测试用）
 * @returns 登录界面
 */
export default function LoginPage({
  api = defaultApi,
  navigate: injectedNavigate,
}: LoginPageProps) {
  const umiNavigate = useNavigate();
  const navigate = injectedNavigate ?? umiNavigate;
  const auth = useAuth();
  const [submitting, setSubmitting] = useState(false);
  const [loginError, setLoginError] = useState<string | null>(null);

  // 首次进入时恢复会话；已过期/已匿名时不自动重查，避免覆盖退出提示。
  useEffect(() => {
    if (auth.status === 'unknown') {
      void authStore.restore(api);
    }
  }, [api, auth.status]);

  // 已登录用户直接进入工作空间，避免重复登录。
  useEffect(() => {
    if (auth.status === 'authenticated') {
      navigate('/chat', { replace: true });
    }
  }, [auth.status, navigate]);

  /**
   * 提交登录。
   *
   * @param values 表单值
   */
  const handleFinish = async (values: LoginRequest): Promise<void> => {
    setSubmitting(true);
    setLoginError(null);
    try {
      const session = await api.login({
        tenantCode: values.tenantCode.trim(),
        username: values.username.trim(),
        password: values.password,
      });
      authStore.setAuthenticated(session);
      navigate('/chat', { replace: true });
    } catch (error) {
      setLoginError(loginErrorMessage(error));
    } finally {
      setSubmitting(false);
    }
  };

  if (auth.status === 'unknown' || auth.status === 'checking') {
    return (
      <Flex align="center" gap={16} justify="center" style={{ minHeight: '100vh', padding: 24 }}>
        <Spin size="large" />
        <Typography.Text>正在检查登录状态…</Typography.Text>
      </Flex>
    );
  }

  if (auth.status === 'error') {
    return (
      <Flex align="center" justify="center" style={{ minHeight: '100vh', padding: 16 }}>
        <Result
          status="error"
          title="无法确认登录状态"
          subTitle={auth.error ?? '无法连接服务器，请检查网络后重试。'}
          extra={[
            <Button
              key="retry"
              type="primary"
              onClick={() => {
                void authStore.restore(api);
              }}
            >
              重试
            </Button>,
          ]}
        />
      </Flex>
    );
  }

  if (auth.status === 'authenticated') {
    return (
      <Flex align="center" gap={16} justify="center" style={{ minHeight: '100vh', padding: 24 }}>
        <Spin size="large" />
        <Typography.Text>正在进入工作空间…</Typography.Text>
      </Flex>
    );
  }

  return (
    <Flex
      align="center"
      justify="center"
      style={{ minHeight: '100vh', padding: 16, width: '100%' }}
    >
      <Card style={{ maxWidth: 420, width: '100%' }}>
        <Typography.Title level={2} style={{ marginTop: 0 }}>
          登录 Riccon
        </Typography.Title>
        <Typography.Paragraph type="secondary">
          使用企业租户账号进入云工作空间。
        </Typography.Paragraph>

        {auth.reason === 'expired' && (
          <Alert
            showIcon
            description="登录状态已失效，请重新登录。已上传文件与历史会话不会丢失。"
            style={{ marginBottom: 16 }}
            title="会话已过期"
            type="warning"
          />
        )}
        {auth.reason === 'logout' && (
          <Alert
            showIcon
            description="你可以使用企业账号重新登录。"
            style={{ marginBottom: 16 }}
            title="已安全退出"
            type="success"
          />
        )}
        {loginError && (
          <Alert
            showIcon
            aria-live="assertive"
            description={loginError}
            role="alert"
            style={{ marginBottom: 16 }}
            title="登录失败"
            type="error"
          />
        )}

        <Form<LoginRequest>
          aria-busy={submitting}
          autoComplete="on"
          layout="vertical"
          onFinish={(values) => {
            void handleFinish(values);
          }}
          onValuesChange={() => setLoginError(null)}
          requiredMark={false}
        >
          <Form.Item
            label="租户代码"
            name="tenantCode"
            rules={[
              { required: true, message: '请输入租户代码' },
              { max: 64, message: '租户代码不能超过 64 个字符' },
            ]}
          >
            <Input
              autoComplete="organization"
              autoFocus
              disabled={submitting}
              placeholder="例如 acme"
            />
          </Form.Item>
          <Form.Item
            label="账号"
            name="username"
            rules={[
              { required: true, message: '请输入账号' },
              { max: 100, message: '账号不能超过 100 个字符' },
            ]}
          >
            <Input autoComplete="username" disabled={submitting} placeholder="请输入账号" />
          </Form.Item>
          <Form.Item
            label="密码"
            name="password"
            rules={[
              { required: true, message: '请输入密码' },
              { max: 200, message: '密码不能超过 200 个字符' },
            ]}
          >
            <Input.Password
              autoComplete="current-password"
              disabled={submitting}
              placeholder="请输入密码"
            />
          </Form.Item>
          <Button block htmlType="submit" loading={submitting} type="primary">
            登录
          </Button>
        </Form>

        {IS_MOCK_API && (
          <Alert
            showIcon
            description={`租户 ${MOCK_CREDENTIALS.tenantCode}，账号 ${MOCK_CREDENTIALS.username}，密码 ${MOCK_CREDENTIALS.password}。演示数据仅保存在当前浏览器会话中。`}
            style={{ marginTop: 16 }}
            title="演示模式"
            type="info"
          />
        )}

        <Typography.Paragraph style={{ marginBottom: 0, marginTop: 16 }}>
          <Typography.Text type="secondary">
            遇到登录问题，请联系企业管理员。系统不会在提示中区分账号是否存在。
          </Typography.Text>
        </Typography.Paragraph>
      </Card>
    </Flex>
  );
}

/**
 * 把登录异常映射为不泄露账号存在性的文案。
 *
 * @param error 任意异常
 * @returns 中文提示
 */
export function loginErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return '登录失败，请稍后重试。';
  }
  if (error.status === 401) {
    return '租户代码、账号或密码不正确。请检查后重试。';
  }
  if (error.status === 429) {
    return '登录尝试过于频繁，请稍后再试。';
  }
  if (error.status === 403) {
    return '安全校验已失效，请刷新页面后重试。';
  }
  if (error.status === 0) {
    return '无法连接服务器，请检查网络后重试。';
  }
  return describeApiError(error, '登录失败，请稍后重试。');
}
