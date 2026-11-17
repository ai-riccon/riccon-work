import { Link } from '@umijs/max';
import { Alert, Button, Card, Flex, Form, Input, Typography } from 'antd';

/**
 * 登录页占位。
 *
 * 认证方案（内置账号 + OIDC Authorization Code/PKCE）在 M02 落地；
 * 当前页面不提交任何凭据，也不存在默认账号，避免形成可绕过的登录入口。
 *
 * @returns 居中的登录卡片
 */
export default function LoginPage() {
  return (
    <Flex align="center" justify="center" style={{ minHeight: '100vh', padding: 24 }}>
      <Card style={{ maxWidth: 420, width: '100%' }}>
        <Typography.Title level={3} style={{ marginTop: 0 }}>
          登录 Riccon
        </Typography.Title>
        <Typography.Paragraph type="secondary">
          M01 骨架阶段只展示登录布局，认证在 M02 接入。
        </Typography.Paragraph>
        <Alert
          showIcon
          description="M02 将接入内置账号与 OIDC（Authorization Code + PKCE），并补齐 CSRF、会话固定与登出测试。"
          style={{ marginBottom: 16 }}
          title="登录尚未开放"
          type="warning"
        />
        <Form layout="vertical" requiredMark={false}>
          <Form.Item label="账号" name="username">
            <Input autoComplete="username" disabled placeholder="M02 起可用" />
          </Form.Item>
          <Form.Item label="密码" name="password">
            <Input.Password autoComplete="current-password" disabled placeholder="M02 起可用" />
          </Form.Item>
          <Button block disabled htmlType="submit" type="primary">
            登录
          </Button>
        </Form>
        <Typography.Paragraph style={{ marginBottom: 0, marginTop: 16 }}>
          <Link to="/chat">返回工作空间</Link>
        </Typography.Paragraph>
      </Card>
    </Flex>
  );
}
