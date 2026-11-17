import { PageContainer, ProCard } from '@ant-design/pro-components';
import { Descriptions, Typography } from 'antd';

/** 设置项占位数据：只说明归属模块，避免让人误以为已经可以配置。 */
const SETTINGS_ITEMS = [
  {
    key: 'models',
    label: '模型网关',
    children: 'M04 接入路由、降级、多 key 与配额',
  },
  {
    key: 'knowledge',
    label: '知识库',
    children: 'M05 接入 Excel/图片、OCR 与文档级 ACL',
  },
  {
    key: 'mcp',
    label: 'MCP 工具',
    children: 'M07 接入注册审批、凭据托管与代理审计',
  },
  {
    key: 'org',
    label: '组织与权限',
    children: 'M03 接入组织、数据范围与 OIDC/SSO',
  },
];

/**
 * 管理端系统设置占位页。
 *
 * 页面只展示能力归属，不提供任何可提交表单，避免在认证与 RBAC 落地前出现可操作入口。
 *
 * @returns 管理端设置页面
 */
export default function AdminSettingsPage() {
  return (
    <PageContainer subTitle="M01 骨架：只展示能力归属" title="系统设置">
      <ProCard>
        <Typography.Paragraph>
          以下配置项按模块交付，当前页面不包含可提交表单，所有变更入口在对应模块评审后开放。
        </Typography.Paragraph>
        <Descriptions bordered column={1} items={SETTINGS_ITEMS} size="small" />
      </ProCard>
    </PageContainer>
  );
}
