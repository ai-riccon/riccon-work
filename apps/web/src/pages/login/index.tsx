import { useNavigate } from '@umijs/max';
import { Alert, Button, Card, Divider, Flex, Form, Input, Result, Spin, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import type { RicconApi } from '@/services/api/client';
import { ApiError, describeApiError } from '@/services/api/errors';
import { api as defaultApi } from '@/services/api';
import type { LoginRequest } from '@/services/api/types';
import { oidcAuthApi as defaultOidcApi } from '@/services/auth';
import type { OidcAuthApi } from '@/services/auth/oidc-api';
import { describeOidcProviderError } from '@/services/auth/oidc-api';
import type { LogoutLandingState } from '@/services/auth/federated-logout';
import {
  beginFederatedLogoutOnce,
  clearFederatedLogout,
  defaultTopLevelNavigator,
  hasFederatedLogoutAttempted,
  parseLogoutLanding,
  peekFederatedLogoutUrl,
  replaceWithPendingLanding,
} from '@/services/auth/federated-logout';
import type { TopLevelNavigator } from '@/services/auth/federated-logout';
import { describeOidcLoginError } from '@/services/auth/oidc-errors';
import type { OidcErrorPresentation } from '@/services/auth/oidc-errors';
import { authStore, useAuth } from '@/services/auth/auth-store';

/** 登录页可注入依赖，便于组件测试不发起真实网络请求或浏览器跳转。 */
export interface LoginPageProps {
  api?: RicconApi;
  /** 企业账号登录能力；未注入时使用默认真实客户端。 */
  oidcApi?: OidcAuthApi;
  navigate?: (to: string, options?: { replace?: boolean }) => void;
  /** 地址查询串；默认读取 window.location.search，测试可直接注入。 */
  search?: string;
  /** 顶层导航函数；默认 window.location.assign，测试注入以避免 jsdom 跳转。 */
  assign?: TopLevelNavigator;
}

/** providers 查询后的行内提示。 */
interface ProviderNotice {
  type: 'info' | 'error';
  title: string;
  description: string;
}

/**
 * 读取当前地址查询串。
 *
 * @param injected 测试注入值
 * @returns 带或不带 `?` 的查询串
 */
function resolveSearch(injected: string | undefined): string {
  if (injected !== undefined) {
    return injected;
  }
  return typeof window === 'undefined' ? '' : window.location.search;
}

/**
 * 员工登录页。
 *
 * 流程：获取会话状态 → 输入租户代码/账号/密码 → 登录 → 进入 /chat。
 * 企业账号入口只按 tenantCode 查询 providers；未知租户与未开通统一显示“未开通”，不泄露租户存在性。
 * 错误文案遵循 M02-AC-01：凭据失败不区分租户、账号或密码，避免账号枚举。
 * 联合登出落地只陈述本地结果；只有 IdP 回跳 `federated=1` 时才声称企业会话已结束。
 *
 * @param props API、OIDC 客户端、导航、查询串与顶层导航注入
 * @returns 登录界面
 */
export default function LoginPage({
  api = defaultApi,
  oidcApi = defaultOidcApi,
  navigate: injectedNavigate,
  search: injectedSearch,
  assign = defaultTopLevelNavigator,
}: LoginPageProps) {
  const umiNavigate = useNavigate();
  const navigate = injectedNavigate ?? umiNavigate;
  const auth = useAuth();
  const [form] = Form.useForm<LoginRequest>();
  const [submitting, setSubmitting] = useState(false);
  const [loginError, setLoginError] = useState<string | null>(null);
  const [providerLoading, setProviderLoading] = useState(false);
  const [providerNotice, setProviderNotice] = useState<ProviderNotice | null>(null);

  const search = resolveSearch(injectedSearch);
  const landing = useMemo<LogoutLandingState>(() => parseLogoutLanding(search), [search]);
  const oidcError = useMemo<OidcErrorPresentation | null>(
    () =>
      describeOidcLoginError(
        new URLSearchParams(search.startsWith('?') ? search : `?${search}`).get('oidcError'),
      ),
    [search],
  );
  const federatedUrl = peekFederatedLogoutUrl();
  const [federatedAttempted, setFederatedAttempted] = useState(() => hasFederatedLogoutAttempted());

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
   * 联合登出落地：本地退出已由服务端完成，这里只在下一个渲染 tick 自动尝试一次顶层导航。
   *
   * 不在登出请求的回调里直接跳转，是为了先渲染“已退出本地会话”；
   * IdP 不可达时浏览器离开失败，用户后退仍能看到本地退出提示与手动重试入口。
   */
  useEffect(() => {
    if (landing.federated === 'done') {
      // IdP 已回跳确认结束企业会话，清理暂存地址，避免后续误导航。
      clearFederatedLogout();
      return;
    }
    if (!landing.loggedOut && auth.reason !== 'logout') {
      return;
    }
    if (!federatedUrl) {
      return;
    }
    replaceWithPendingLanding();
    const navigated = beginFederatedLogoutOnce(assign);
    if (navigated) {
      setFederatedAttempted(true);
    }
  }, [assign, auth.reason, federatedUrl, landing.federated, landing.loggedOut]);

  /**
   * 提交内置账号登录。
   *
   * @param values 表单值
   */
  const handleFinish = async (values: LoginRequest): Promise<void> => {
    setSubmitting(true);
    setLoginError(null);
    setProviderNotice(null);
    try {
      const session = await api.login({
        tenantCode: values.tenantCode.trim(),
        username: values.username.trim(),
        password: values.password,
      });
      // 登录成功即清除旧的联合登出暂存，避免之后匿名访问登录页时误跳转。
      clearFederatedLogout();
      authStore.setAuthenticated(session);
      navigate('/chat', { replace: true });
    } catch (error) {
      setLoginError(loginErrorMessage(error));
    } finally {
      setSubmitting(false);
    }
  };

  /**
   * 查询租户企业账号登录可用性并跳转授权入口。
   *
   * 只按 tenantCode 查询；authorize 由浏览器顶层导航打开，服务端会在会话中保存
   * state/nonce/PKCE。未知租户与未配置租户使用同一提示，不泄露租户存在性。
   */
  const handleEnterpriseLogin = async (): Promise<void> => {
    setProviderNotice(null);
    let tenantCode = '';
    try {
      const values = await form.validateFields(['tenantCode']);
      tenantCode = String(values.tenantCode ?? '').trim();
    } catch {
      // 表单错误由 antd 就地展示；这里不再叠加提示，避免重复播报。
      return;
    }

    setProviderLoading(true);
    try {
      const provider = await oidcApi.getProvider(tenantCode);
      if (!provider.enabled) {
        setProviderNotice({
          type: 'info',
          title: '该租户未开通企业账号登录',
          description: '请确认租户代码，或改用内置账号登录；如不确定请联系管理员。',
        });
        return;
      }
      assign(oidcApi.authorizeUrl(tenantCode));
    } catch (error) {
      setProviderNotice({
        type: 'error',
        title: '无法使用企业账号',
        description: describeOidcProviderError(error),
      });
    } finally {
      setProviderLoading(false);
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

  const showLogoutNotice =
    landing.loggedOut || auth.reason === 'logout' || (federatedAttempted && federatedUrl !== null);

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

        {showLogoutNotice && (
          <LogoutNotice
            landing={landing}
            attempted={federatedAttempted}
            federatedUrl={federatedUrl}
            onRetry={() => {
              if (federatedUrl) {
                assign(federatedUrl);
              }
            }}
          />
        )}

        {oidcError && (
          <Alert
            aria-live="assertive"
            description={
              <>
                {oidcError.description}
                <br />
                {oidcError.nextStep}
              </>
            }
            role="alert"
            showIcon
            style={{ marginBottom: 16 }}
            title={oidcError.title}
            type={oidcError.type}
          />
        )}

        {auth.reason === 'expired' && (
          <Alert
            showIcon
            description="登录状态已失效，请重新登录。已上传文件与历史会话不会丢失。"
            style={{ marginBottom: 16 }}
            title="会话已过期"
            type="warning"
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
          form={form}
          layout="vertical"
          onFinish={(values) => {
            void handleFinish(values);
          }}
          onValuesChange={() => {
            setLoginError(null);
            setProviderNotice(null);
          }}
          requiredMark={false}
        >
          <Form.Item
            label="租户代码"
            name="tenantCode"
            rules={[
              { required: true, message: '请输入租户代码' },
              { max: 64, message: '租户代码不能超过 64 个字符' },
              {
                pattern: /^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/,
                message: '租户代码只能包含字母、数字、下划线和连字符，且不能以符号开头',
              },
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

        <Divider plain>或</Divider>

        <Button
          aria-describedby="enterprise-login-help"
          block
          htmlType="button"
          loading={providerLoading}
          onClick={() => {
            void handleEnterpriseLogin();
          }}
        >
          企业账号
        </Button>
        <Typography.Paragraph
          id="enterprise-login-help"
          style={{ marginBottom: 0, marginTop: 8 }}
          type="secondary"
        >
          使用租户配置的企业身份提供方登录；账号需由管理员预先关联，不会按邮箱自动绑定。
        </Typography.Paragraph>

        {providerNotice && (
          <Alert
            aria-live="polite"
            description={providerNotice.description}
            showIcon
            style={{ marginTop: 16 }}
            title={providerNotice.title}
            type={providerNotice.type}
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
 * 联合登出落地提示。
 *
 * 文案边界：只有 `federated=1`（IdP 已回跳）才声称“企业账号会话已结束”；
 * 自动导航失败或未配置 end-session 时只陈述“已退出本地会话”，不声称 IdP 会话结束。
 *
 * @param props 落地状态、是否已尝试导航、暂存地址与手动重试回调
 * @returns 提示节点；无需展示时返回 null
 */
function LogoutNotice({
  landing,
  attempted,
  federatedUrl,
  onRetry,
}: {
  landing: LogoutLandingState;
  attempted: boolean;
  federatedUrl: string | null;
  onRetry: () => void;
}) {
  if (landing.federated === 'done') {
    return (
      <Alert
        description="企业身份服务已确认会话结束。你现在可以重新登录。"
        showIcon
        style={{ marginBottom: 16 }}
        title="已退出本地会话和企业账号"
        type="success"
      />
    );
  }

  if (landing.federated === 'pending' && federatedUrl && !attempted) {
    return (
      <Alert
        description="正在前往企业账号中心结束企业会话。若页面没有自动跳转，可稍候使用下方的“企业账号”或内置账号继续。"
        showIcon
        style={{ marginBottom: 16 }}
        title="已退出本地会话"
        type="info"
      />
    );
  }

  if (federatedUrl) {
    return (
      <Alert
        action={
          <Button onClick={onRetry} size="small">
            重试退出企业账号
          </Button>
        }
        description="本地会话已结束。企业账号中心的会话可能仍然有效，页面无法确认其状态，请手动退出或稍后重试。"
        showIcon
        style={{ marginBottom: 16 }}
        title="已退出本地会话"
        type="warning"
      />
    );
  }

  return (
    <Alert
      description={
        landing.federated === 'pending'
          ? '本地会话已结束。未能自动结束企业账号会话，企业账号中心可能仍保持登录，请手动退出。'
          : '本地会话已结束。企业账号会话（如有）可能仍然有效；如需结束，请在企业账号中心退出。'
      }
      showIcon
      style={{ marginBottom: 16 }}
      title="已退出本地会话"
      type={landing.federated === 'pending' ? 'warning' : 'success'}
    />
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
