import { PageContainer, ProCard } from '@ant-design/pro-components';
import { Link } from '@umijs/max';
import { Alert, Statistic, Typography } from 'antd';

/**
 * 管理端总览占位页。
 *
 * 指标均为静态占位，用于验证 ProLayout + PageContainer 的布局链路；
 * 真实租户、模型与审计指标在 M02/M04 从 API 读取。
 *
 * @returns 管理端总览页面
 */
export default function AdminOverviewPage() {
  return (
    <PageContainer subTitle="M01 骨架：以下指标为静态占位" title="管理总览">
      <Alert
        showIcon
        description="租户、模型、知识库与审计能力将在 M02–M07 分模块接入；当前页面只验证管理端布局与导航。"
        message="骨架阶段提示"
        style={{ marginBottom: 16 }}
        type="info"
      />
      <ProCard gutter={16} wrap>
        <ProCard colSpan={{ xs: 24, md: 8 }} title="租户">
          <Statistic suffix="个" title="已接入租户（占位）" value={1} />
        </ProCard>
        <ProCard colSpan={{ xs: 24, md: 8 }} title="模型">
          <Statistic suffix="个" title="可用模型（占位）" value={0} />
        </ProCard>
        <ProCard colSpan={{ xs: 24, md: 8 }} title="审计">
          <Statistic suffix="条" title="今日事件（占位）" value={0} />
        </ProCard>
      </ProCard>
      <ProCard style={{ marginTop: 16 }} title="下一步">
        <Typography.Paragraph>
          员工端工作空间占位页位于{' '}
          <Link style={{ textDecoration: 'underline' }} to="/chat">
            /chat
          </Link>
          ，用于验证 Ant Design X 的会话布局。
        </Typography.Paragraph>
        <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
          真实指标必须带 tenantId 作用域，并在服务端完成权限校验后返回。
        </Typography.Paragraph>
      </ProCard>
    </PageContainer>
  );
}
