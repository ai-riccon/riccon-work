import { useCallback, useEffect, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Link } from '@umijs/max';
import { Alert, Button, Empty, Flex, Result, Skeleton, Tag, Typography } from 'antd';
import { api as defaultApi } from '@/services/api';
import type { PlatformDepartmentApi } from '@/services/api/client';
import { ApiError, describeApiError } from '@/services/api/errors';
import type { TenantSummary } from '@/services/api/types';
import './tenants.css';

/** 平台管理员租户列表页参数。 */
export interface AdminTenantsPageProps {
  /** 可注入的 API，便于组件测试。 */
  api?: PlatformDepartmentApi;
}

/** 页面加载阶段。 */
type TenantsPhase = 'loading' | 'ready' | 'empty' | 'forbidden' | 'error';

/**
 * 平台管理员租户列表。
 *
 * 只展示租户元数据与部门管理入口，不读取任何租户业务内容。
 * 已暂停租户保留在列表中但禁用部门入口，避免进入后才收到 409。
 *
 * @param props API 注入
 * @returns 租户列表页面
 */
export default function AdminTenantsPage({ api = defaultApi }: AdminTenantsPageProps) {
  const [phase, setPhase] = useState<TenantsPhase>('loading');
  const [tenants, setTenants] = useState<TenantSummary[]>([]);
  const [error, setError] = useState<string | null>(null);

  /**
   * 读取平台租户列表。
   */
  const load = useCallback(async (): Promise<void> => {
    setPhase('loading');
    setError(null);
    try {
      const page = await api.listTenants();
      setTenants(page.items);
      setPhase(page.items.length === 0 ? 'empty' : 'ready');
    } catch (loadError) {
      if (loadError instanceof ApiError && loadError.status === 403) {
        setPhase('forbidden');
        setError('只有平台管理员可以查看租户与部门管理入口。');
        return;
      }
      setPhase('error');
      setError(describeApiError(loadError, '租户列表加载失败，请稍后重试。'));
    }
  }, [api]);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <PageContainer subTitle="平台管理员维护租户组织元数据，不读取租户业务内容" title="租户与部门">
      {phase === 'loading' && <Skeleton active paragraph={{ rows: 6 }} />}

      {phase === 'forbidden' && (
        <Result
          status="403"
          subTitle={error ?? '当前账号无权访问平台租户管理。'}
          title="没有访问权限"
          extra={
            <Button onClick={() => void load()} type="primary">
              重试
            </Button>
          }
        />
      )}

      {phase === 'error' && (
        <Result
          status="error"
          subTitle={error ?? '请检查网络后重试。'}
          title="租户列表加载失败"
          extra={
            <Button onClick={() => void load()} type="primary">
              重试
            </Button>
          }
        />
      )}

      {phase === 'empty' && (
        <Empty description="还没有租户；租户由平台开通流程创建。">
          <Button onClick={() => void load()}>刷新</Button>
        </Empty>
      )}

      {phase === 'ready' && (
        <Flex gap={12} orientation="vertical">
          <Alert
            showIcon
            description="平台管理员只能管理目标租户的部门、成员归属与负责人，不能读取会话、文件、报告、产物、知识库正文或审计明细。"
            title="组织元数据边界"
            type="info"
          />
          <ul aria-label="租户列表" className="tenant-list">
            {tenants.map((tenant) => {
              const active = tenant.status === 'ACTIVE';
              return (
                <li className="tenant-list-item" key={tenant.id}>
                  <div className="tenant-list-main">
                    <Typography.Text strong>{tenant.name}</Typography.Text>
                    <Tag color={active ? 'green' : 'orange'}>{active ? '启用' : '已暂停'}</Tag>
                    <Typography.Text type="secondary">
                      审计保留 {tenant.retentionDays} 天
                    </Typography.Text>
                  </div>
                  {active ? (
                    <Link
                      aria-label={`管理部门：${tenant.name}`}
                      className="tenant-department-link"
                      to={`/admin/tenants/${tenant.id}/departments`}
                    >
                      管理部门
                    </Link>
                  ) : (
                    <Typography.Text type="secondary">
                      租户已暂停，部门管理不可用；请先恢复租户状态。
                    </Typography.Text>
                  )}
                </li>
              );
            })}
          </ul>
        </Flex>
      )}
    </PageContainer>
  );
}
