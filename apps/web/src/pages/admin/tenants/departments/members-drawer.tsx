import { useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Button, Drawer, Empty, Flex, Select, Skeleton, Tag, Typography } from 'antd';
import type { PlatformDepartmentApi } from '@/services/api/client';
import type { Department, DepartmentMember, UserSummary } from '@/services/api/types';
import { describePlatformDepartmentError } from '@/services/workspaces/errors';

/** 部门成员抽屉参数。 */
export interface DepartmentMembersDrawerProps {
  api: PlatformDepartmentApi;
  tenantId: string;
  department: Department;
  onClose: () => void;
  onDepartmentUpdated: (department: Department) => void;
  /** 成员增删后通知父级刷新成员数；负责人变更已由 `onDepartmentUpdated` 更新。 */
  onChanged?: () => void;
}

/**
 * 平台管理员部门成员管理抽屉。
 *
 * 支持加入/移出成员与负责人移交；单主部门下账号已属于其他部门时，
 * 服务端返回 409，界面只展示稳定中文原因。移除当前负责人被禁用，
 * 避免出现“负责人不在本部门”的悬挂状态。
 *
 * @param props API、目标租户、部门与回调
 * @returns 成员管理抽屉
 */
export function DepartmentMembersDrawer({
  api,
  tenantId,
  department,
  onClose,
  onDepartmentUpdated,
  onChanged,
}: DepartmentMembersDrawerProps) {
  const [members, setMembers] = useState<DepartmentMember[]>([]);
  const [users, setUsers] = useState<UserSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [selectedUserId, setSelectedUserId] = useState<string | undefined>();
  const [adding, setAdding] = useState(false);
  const [removingId, setRemovingId] = useState<string | null>(null);
  const [managerSavingId, setManagerSavingId] = useState<string | null>(null);
  const [managerUserId, setManagerUserId] = useState<string | null>(
    department.managerUserId ?? null,
  );

  /**
   * 加载部门成员与目标租户账号。
   */
  const load = useCallback(async (): Promise<void> => {
    setLoading(true);
    setLoadError(null);
    try {
      const [memberPage, userPage] = await Promise.all([
        api.listTenantDepartmentMembers(tenantId, department.id),
        api.listTenantUsers(tenantId),
      ]);
      setMembers(memberPage.items);
      setUsers(userPage.items);
    } catch (failure) {
      setLoadError(describePlatformDepartmentError(failure, '成员列表加载失败，请重试。'));
    } finally {
      setLoading(false);
    }
  }, [api, department.id, tenantId]);

  useEffect(() => {
    void load();
  }, [load]);

  const memberIds = useMemo(() => new Set(members.map((member) => member.id)), [members]);
  const candidates = useMemo(
    () => users.filter((user) => user.status === 'ACTIVE' && !memberIds.has(user.id)),
    [memberIds, users],
  );

  /**
   * 把选中的账号加入部门。
   */
  const handleAdd = async (): Promise<void> => {
    if (!selectedUserId) {
      return;
    }
    setAdding(true);
    setActionError(null);
    try {
      const member = await api.addTenantDepartmentMember(tenantId, department.id, selectedUserId);
      setMembers((previous) => [...previous, member]);
      setSelectedUserId(undefined);
      onChanged?.();
    } catch (failure) {
      setActionError(describePlatformDepartmentError(failure, '加入成员失败，请稍后重试。'));
    } finally {
      setAdding(false);
    }
  };

  /**
   * 把指定账号移出部门。
   *
   * @param member 目标成员
   */
  const handleRemove = async (member: DepartmentMember): Promise<void> => {
    setRemovingId(member.id);
    setActionError(null);
    try {
      await api.removeTenantDepartmentMember(tenantId, department.id, member.id);
      setMembers((previous) => previous.filter((item) => item.id !== member.id));
      onChanged?.();
    } catch (failure) {
      setActionError(describePlatformDepartmentError(failure, '移出成员失败，请稍后重试。'));
    } finally {
      setRemovingId(null);
    }
  };

  /**
   * 任命/移交部门负责人。
   *
   * @param member 目标成员
   */
  const handleTransfer = async (member: DepartmentMember): Promise<void> => {
    setManagerSavingId(member.id);
    setActionError(null);
    try {
      const updated = await api.transferTenantDepartmentManager(tenantId, department.id, member.id);
      setManagerUserId(updated.managerUserId ?? null);
      onDepartmentUpdated(updated);
    } catch (failure) {
      setActionError(describePlatformDepartmentError(failure, '负责人移交失败，请稍后重试。'));
    } finally {
      setManagerSavingId(null);
    }
  };

  return (
    <Drawer onClose={onClose} open size="min(560px, 100vw)" title={`成员管理 · ${department.name}`}>
      <Flex gap={12} orientation="vertical">
        <Alert
          showIcon
          description="账号只能有一个主部门；已属于其他部门的账号需先移出原部门。负责人必须是本部门当前启用的成员。"
          title="单主部门"
          type="info"
        />
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

        {loading ? (
          <Skeleton active paragraph={{ rows: 5 }} title={false} />
        ) : loadError ? (
          <Alert
            showIcon
            action={
              <Button
                onClick={() => {
                  void load();
                }}
                size="small"
              >
                重试
              </Button>
            }
            description={loadError}
            title="成员加载失败"
            type="error"
          />
        ) : (
          <>
            <Flex align="flex-end" gap={8} wrap>
              <div style={{ flex: '1 1 220px', minWidth: 0 }}>
                <label htmlFor="department-member-select">
                  <Typography.Text strong>加入目标租户账号</Typography.Text>
                </label>
                <Select
                  aria-label="加入目标租户账号"
                  id="department-member-select"
                  onChange={(value) => setSelectedUserId(value)}
                  options={candidates.map((user) => ({
                    label: `${user.displayName}（${user.username}）`,
                    value: user.id,
                  }))}
                  placeholder={candidates.length > 0 ? '选择启用账号' : '没有可加入的启用账号'}
                  style={{ width: '100%' }}
                  value={selectedUserId}
                />
              </div>
              <Button
                disabled={!selectedUserId}
                loading={adding}
                onClick={() => {
                  void handleAdd();
                }}
                type="primary"
              >
                加入部门
              </Button>
            </Flex>

            {members.length === 0 ? (
              <Empty description="部门还没有成员" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            ) : (
              <ul aria-label="部门成员" className="department-member-list">
                {members.map((member) => {
                  const isManager = member.id === managerUserId;
                  return (
                    <li className="department-member-row" key={member.id}>
                      <div className="department-member-name">
                        <Typography.Text strong>{member.displayName}</Typography.Text>
                        <Typography.Text type="secondary">{member.username}</Typography.Text>
                        {isManager && <Tag color="gold">负责人</Tag>}
                        {member.status === 'DISABLED' && <Tag>已停用</Tag>}
                      </div>
                      <Flex gap={8} wrap>
                        {!isManager && (
                          <Button
                            aria-label={`设为负责人：${member.displayName}`}
                            loading={managerSavingId === member.id}
                            onClick={() => {
                              void handleTransfer(member);
                            }}
                            size="small"
                          >
                            设为负责人
                          </Button>
                        )}
                        <Button
                          aria-label={`移出成员：${member.displayName}`}
                          danger
                          disabled={isManager}
                          loading={removingId === member.id}
                          onClick={() => {
                            void handleRemove(member);
                          }}
                          size="small"
                          title={isManager ? '先移交负责人后才能移出' : undefined}
                        >
                          移出
                        </Button>
                      </Flex>
                      {isManager && (
                        <Typography.Text type="secondary">
                          当前负责人不能直接移出，请先移交给其他成员。
                        </Typography.Text>
                      )}
                    </li>
                  );
                })}
              </ul>
            )}
          </>
        )}
      </Flex>
    </Drawer>
  );
}
