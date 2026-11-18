import { useCallback, useEffect, useMemo, useState } from 'react';
import { PageContainer } from '@ant-design/pro-components';
import { Link, useParams } from '@umijs/max';
import { Alert, Button, Empty, Flex, Modal, Result, Skeleton, Tag, Typography } from 'antd';
import { api as defaultApi } from '@/services/api';
import type { PlatformDepartmentApi } from '@/services/api/client';
import { ApiError, describeApiError } from '@/services/api/errors';
import type { Department } from '@/services/api/types';
import { describePlatformDepartmentError } from '@/services/workspaces/errors';
import { DepartmentFormModal } from './department-form-modal';
import { DepartmentMembersDrawer } from './members-drawer';
import './departments.css';

/** 平台管理员部门管理页参数。 */
export interface TenantDepartmentsPageProps {
  /** 可注入的 API，便于组件测试。 */
  api?: PlatformDepartmentApi;
  /** 目标租户 ID；不传时从路由参数读取。 */
  tenantId?: string;
}

/** 页面加载阶段。 */
type DepartmentsPhase =
  'loading' | 'ready' | 'empty' | 'forbidden' | 'notFound' | 'tenantSuspended' | 'error';

/** 创建/改名弹窗状态。 */
type FormState = { mode: 'create' } | { mode: 'rename'; department: Department } | null;

/**
 * 把部门列表按名称、ID 升序排序，与服务端列表顺序一致。
 *
 * @param departments 部门列表
 * @returns 新数组
 */
function sortDepartments(departments: Department[]): Department[] {
  return [...departments].sort(
    (left, right) => left.name.localeCompare(right.name) || left.id.localeCompare(right.id),
  );
}

/**
 * 平台管理员租户部门管理页。
 *
 * 页面只操作组织元数据：部门、成员归属、负责人；不读取租户业务内容。
 * 所有写操作按服务端结果更新列表，不乐观修改，避免把 409/404 显示成成功。
 *
 * @param props API 与租户 ID 注入
 * @returns 部门管理页
 */
export default function TenantDepartmentsPage({
  api = defaultApi,
  tenantId: injectedTenantId,
}: TenantDepartmentsPageProps) {
  const params = useParams<{ tenantId?: string }>();
  const tenantId = injectedTenantId ?? params.tenantId ?? '';

  const [phase, setPhase] = useState<DepartmentsPhase>('loading');
  const [tenantName, setTenantName] = useState<string | null>(null);
  const [departments, setDepartments] = useState<Department[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [formState, setFormState] = useState<FormState>(null);
  const [toggleTarget, setToggleTarget] = useState<Department | null>(null);
  const [toggling, setToggling] = useState(false);
  const [membersTarget, setMembersTarget] = useState<Department | null>(null);
  const [refreshing, setRefreshing] = useState(false);

  /**
   * 读取目标租户名称与部门列表。
   */
  const load = useCallback(async (): Promise<void> => {
    if (!tenantId) {
      setPhase('notFound');
      setError('缺少租户标识，无法打开部门管理。');
      return;
    }
    setPhase('loading');
    setError(null);
    setActionError(null);
    try {
      // 部门端点只要求 platform.department.manage；租户名只用于标题展示，
      // 读取失败（例如账号缺少 platform.tenant.manage）不能遮蔽部门管理能力。
      const departmentPage = await api.listTenantDepartments(tenantId);
      const items = sortDepartments(departmentPage.items);
      setDepartments(items);
      setPhase(items.length === 0 ? 'empty' : 'ready');
      try {
        const tenantPage = await api.listTenants();
        const tenant = tenantPage.items.find((item) => item.id === tenantId) ?? null;
        setTenantName(tenant?.name ?? null);
      } catch {
        setTenantName(null);
      }
    } catch (loadError) {
      if (loadError instanceof ApiError) {
        if (loadError.status === 403) {
          setPhase('forbidden');
          setError('只有平台管理员可以管理部门；当前账号没有 platform.department.manage 权限。');
          return;
        }
        if (loadError.status === 404) {
          setPhase('notFound');
          setError('目标租户或部门不存在，或不属于该租户。');
          return;
        }
        if (loadError.status === 409) {
          setPhase('tenantSuspended');
          setError('目标租户已暂停，部门管理暂不可用；请先恢复租户状态。');
          return;
        }
      }
      setPhase('error');
      setError(describeApiError(loadError, '部门列表加载失败，请稍后重试。'));
    }
  }, [api, tenantId]);

  useEffect(() => {
    void load();
  }, [load]);

  /**
   * 静默刷新部门列表；失败时保留当前内容并显示操作错误。
   */
  const refresh = useCallback(async (): Promise<void> => {
    setRefreshing(true);
    setActionError(null);
    try {
      const page = await api.listTenantDepartments(tenantId);
      setDepartments(sortDepartments(page.items));
    } catch (failure) {
      setActionError(describePlatformDepartmentError(failure, '部门列表刷新失败，请重试。'));
    } finally {
      setRefreshing(false);
    }
  }, [api, tenantId]);

  /**
   * 处理创建/改名成功。
   *
   * @param saved 保存后的部门
   * @param mode 操作类型
   */
  const handleSaved = useCallback((saved: Department, mode: 'create' | 'rename'): void => {
    setDepartments((previous) => {
      const without = previous.filter((department) => department.id !== saved.id);
      return sortDepartments([...without, saved]);
    });
    setFormState(null);
    // 空态创建成功后必须切换到列表态，否则用户只能看到成功提示而看不到新部门。
    setPhase('ready');
    setNotice(
      mode === 'create' ? `部门“${saved.name}”已创建。` : `部门已重命名为“${saved.name}”。`,
    );
  }, []);

  /**
   * 停用或启用部门。
   */
  const handleToggle = async (): Promise<void> => {
    if (!toggleTarget) {
      return;
    }
    setToggling(true);
    setActionError(null);
    const enabling = toggleTarget.status === 'DISABLED';
    try {
      const updated = enabling
        ? await api.enableTenantDepartment(tenantId, toggleTarget.id)
        : await api.disableTenantDepartment(tenantId, toggleTarget.id);
      setDepartments((previous) =>
        previous.map((department) => (department.id === updated.id ? updated : department)),
      );
      setToggleTarget(null);
      setNotice(enabling ? `部门“${updated.name}”已启用。` : `部门“${updated.name}”已停用。`);
    } catch (failure) {
      setActionError(describePlatformDepartmentError(failure, '部门状态修改失败，请稍后重试。'));
    } finally {
      setToggling(false);
    }
  };

  const activeCount = useMemo(
    () => departments.filter((department) => department.status === 'ACTIVE').length,
    [departments],
  );

  return (
    <PageContainer
      subTitle={
        <span>
          目标租户：{(tenantName ?? tenantId) || '未指定'}；只管理组织元数据，不读取租户业务内容。
        </span>
      }
      title="部门管理"
    >
      <div className="department-admin-page">
        <Flex align="center" gap={8} justify="space-between" wrap>
          <Link className="department-back-link" to="/admin/tenants">
            返回租户列表
          </Link>
          <Flex gap={8} wrap>
            <Button loading={refreshing} onClick={() => void refresh()}>
              刷新
            </Button>
            {(phase === 'ready' || phase === 'empty') && (
              <Button onClick={() => setFormState({ mode: 'create' })} type="primary">
                创建部门
              </Button>
            )}
          </Flex>
        </Flex>

        {phase === 'loading' && <Skeleton active paragraph={{ rows: 7 }} />}

        {phase === 'forbidden' && (
          <Result
            status="403"
            subTitle={error ?? '当前账号无权访问部门管理。'}
            title="没有访问权限"
            extra={
              <Button onClick={() => void load()} type="primary">
                重试
              </Button>
            }
          />
        )}

        {phase === 'notFound' && (
          <Result
            status="404"
            subTitle={error ?? '目标租户或部门不存在。'}
            title="目标不存在"
            extra={
              <Link to="/admin/tenants">
                <Button type="primary">返回租户列表</Button>
              </Link>
            }
          />
        )}

        {phase === 'tenantSuspended' && (
          <Result
            status="warning"
            subTitle={error ?? '目标租户已暂停，部门管理暂不可用。'}
            title="目标租户已暂停"
            extra={
              <Link to="/admin/tenants">
                <Button type="primary">返回租户列表</Button>
              </Link>
            }
          />
        )}

        {phase === 'error' && (
          <Result
            status="error"
            subTitle={error ?? '请检查网络后重试。'}
            title="部门列表加载失败"
            extra={
              <Button onClick={() => void load()} type="primary">
                重试
              </Button>
            }
          />
        )}

        {phase !== 'loading' && (
          <Flex gap={12} orientation="vertical">
            {notice && (
              <Alert
                showIcon
                closable
                description={notice}
                onClose={() => setNotice(null)}
                title="操作成功"
                type="success"
              />
            )}
            {actionError && (
              <Alert
                showIcon
                closable
                description={actionError}
                onClose={() => setActionError(null)}
                title="操作未完成"
                type="error"
              />
            )}
          </Flex>
        )}

        {phase === 'empty' && (
          <Empty description="还没有部门；创建第一个部门后即可分配成员与部门空间。">
            <Button onClick={() => setFormState({ mode: 'create' })} type="primary">
              创建部门
            </Button>
          </Empty>
        )}

        {phase === 'ready' && (
          <Flex gap={12} orientation="vertical">
            <Typography.Text type="secondary">
              共 {departments.length} 个部门，启用 {activeCount}{' '}
              个。停用只冻结状态，不移除成员归属； 重新启用后原成员关系恢复。
            </Typography.Text>
            <ul aria-label="部门列表" className="department-list">
              {departments.map((department) => {
                const active = department.status === 'ACTIVE';
                return (
                  <li className="department-list-item" key={department.id}>
                    <div className="department-list-main">
                      <Typography.Text strong>{department.name}</Typography.Text>
                      <Tag
                        color={active ? 'green' : 'default'}
                        style={
                          active
                            ? { color: '#237804', background: '#f6ffed', borderColor: '#b7eb8f' }
                            : undefined
                        }
                      >
                        {active ? '启用' : '已停用'}
                      </Tag>
                      <Typography.Text>成员 {department.memberCount} 人</Typography.Text>
                      <Typography.Text type="secondary">
                        负责人：{department.managerDisplayName ?? '未任命'}
                      </Typography.Text>
                    </div>
                    <Flex gap={8} wrap>
                      <Button
                        aria-label={`成员管理：${department.name}`}
                        onClick={() => setMembersTarget(department)}
                        size="small"
                      >
                        成员管理
                      </Button>
                      <Button
                        aria-label={`重命名：${department.name}`}
                        disabled={!active}
                        onClick={() => setFormState({ mode: 'rename', department })}
                        size="small"
                        title={active ? undefined : '停用部门需先启用后再改名'}
                      >
                        重命名
                      </Button>
                      <Button
                        aria-label={`${active ? '停用' : '启用'}：${department.name}`}
                        autoInsertSpace={false}
                        danger={active}
                        onClick={() => setToggleTarget(department)}
                        size="small"
                        style={active ? { color: '#a8071a', borderColor: '#ffa39e' } : undefined}
                      >
                        {active ? '停用' : '启用'}
                      </Button>
                    </Flex>
                    {!active && (
                      <Typography.Text type="secondary">
                        停用部门冻结改名；成员归属保留，部门空间在下一次请求起不可访问。
                      </Typography.Text>
                    )}
                  </li>
                );
              })}
            </ul>
          </Flex>
        )}
      </div>

      {formState && (
        <DepartmentFormModal
          api={api}
          department={formState.mode === 'rename' ? formState.department : undefined}
          mode={formState.mode}
          onClose={() => setFormState(null)}
          onSaved={handleSaved}
          tenantId={tenantId}
        />
      )}

      <Modal
        cancelText="取消"
        confirmLoading={toggling}
        okButtonProps={{ danger: toggleTarget?.status === 'ACTIVE' }}
        okText={toggleTarget?.status === 'ACTIVE' ? '停用部门' : '启用部门'}
        onCancel={() => setToggleTarget(null)}
        onOk={() => {
          void handleToggle();
        }}
        open={Boolean(toggleTarget)}
        title={
          toggleTarget?.status === 'ACTIVE'
            ? `停用部门 · ${toggleTarget.name}`
            : `启用部门 · ${toggleTarget?.name ?? ''}`
        }
      >
        {toggleTarget?.status === 'ACTIVE' ? (
          <Typography.Paragraph>
            停用后成员归属保留，但部门空间与对象在下一次请求起不可访问；部门名仍占用，
            请优先重新启用而不是新建同名部门。
          </Typography.Paragraph>
        ) : (
          <Typography.Paragraph>
            启用后恢复原成员关系与部门空间访问；部门名称已占用时不会产生重复部门。
          </Typography.Paragraph>
        )}
      </Modal>

      {membersTarget && (
        <DepartmentMembersDrawer
          api={api}
          department={membersTarget}
          onClose={() => setMembersTarget(null)}
          onChanged={() => {
            void refresh();
          }}
          onDepartmentUpdated={(updated) => {
            setDepartments((previous) =>
              previous.map((department) => (department.id === updated.id ? updated : department)),
            );
            setMembersTarget(updated);
          }}
          tenantId={tenantId}
        />
      )}
    </PageContainer>
  );
}
