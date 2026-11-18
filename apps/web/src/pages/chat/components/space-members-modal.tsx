import { useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Button, Empty, Flex, Modal, Select, Skeleton, Tag, Typography } from 'antd';
import type { WorkspaceManagementApi } from '@/services/api/client';
import type { DepartmentMember, ManagedWorkspace, WorkspaceMember } from '@/services/api/types';
import { describeWorkspaceError } from '@/services/workspaces/errors';

/** CUSTOM 空间成员管理弹窗参数。 */
export interface SpaceMembersModalProps {
  api: WorkspaceManagementApi;
  workspace: ManagedWorkspace;
  onClose: () => void;
}

/**
 * CUSTOM 空间显式成员管理。
 *
 * 可添加的候选只来自空间所属部门；重复加入、跨部门账号等冲突
 * 由服务端返回 409，界面显示稳定中文原因，不展示后端英文细节。
 *
 * @param props API 与空间
 * @returns 成员管理弹窗
 */
export function SpaceMembersModal({ api, workspace, onClose }: SpaceMembersModalProps) {
  const [members, setMembers] = useState<WorkspaceMember[]>([]);
  const [departmentMembers, setDepartmentMembers] = useState<DepartmentMember[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [addUserId, setAddUserId] = useState<string | undefined>();
  const [adding, setAdding] = useState(false);
  const [removingId, setRemovingId] = useState<string | null>(null);

  /**
   * 加载显式成员与部门候选成员。
   */
  const load = useCallback(async (): Promise<void> => {
    setLoading(true);
    setLoadError(null);
    try {
      const departmentId = workspace.departmentId;
      const [memberPage, departmentPage] = await Promise.all([
        api.listWorkspaceMembers(workspace.id),
        departmentId
          ? api.listDepartmentMembers(departmentId)
          : Promise.resolve({ items: [] as DepartmentMember[] }),
      ]);
      setMembers(memberPage.items);
      setDepartmentMembers(departmentPage.items.filter((member) => member.status === 'ACTIVE'));
    } catch (loadFailure) {
      setLoadError(describeWorkspaceError(loadFailure, '成员列表加载失败，请重试。'));
    } finally {
      setLoading(false);
    }
  }, [api, workspace.departmentId, workspace.id]);

  useEffect(() => {
    void load();
  }, [load]);

  const memberIds = useMemo(() => new Set(members.map((member) => member.userId)), [members]);
  const candidates = useMemo(
    () => departmentMembers.filter((member) => !memberIds.has(member.id)),
    [departmentMembers, memberIds],
  );

  /**
   * 把选中的部门成员加入空间。
   */
  const handleAdd = async (): Promise<void> => {
    if (!addUserId) {
      return;
    }
    setAdding(true);
    setActionError(null);
    try {
      const member = await api.addWorkspaceMember(workspace.id, addUserId);
      setMembers((previous) => [...previous, member]);
      setAddUserId(undefined);
    } catch (addFailure) {
      setActionError(describeWorkspaceError(addFailure, '加入成员失败，请稍后重试。'));
    } finally {
      setAdding(false);
    }
  };

  /**
   * 移出指定显式成员。
   *
   * @param userId 目标账号 ID
   */
  const handleRemove = async (userId: string): Promise<void> => {
    setRemovingId(userId);
    setActionError(null);
    try {
      await api.removeWorkspaceMember(workspace.id, userId);
      setMembers((previous) => previous.filter((member) => member.userId !== userId));
    } catch (removeFailure) {
      setActionError(describeWorkspaceError(removeFailure, '移出成员失败，请稍后重试。'));
    } finally {
      setRemovingId(null);
    }
  };

  return (
    <Modal
      cancelText="关闭"
      footer={
        <Button onClick={onClose} type="primary">
          关闭
        </Button>
      }
      onCancel={onClose}
      open
      title={`成员管理 · ${workspace.name}`}
      width="min(560px, 96vw)"
    >
      <Typography.Paragraph type="secondary">
        指定成员空间只对“部门当前启用成员 +
        显式加入”的人可见；移出部门后即使保留成员记录也不再可见。
      </Typography.Paragraph>

      {actionError && (
        <Alert
          showIcon
          closable
          description={actionError}
          onClose={() => setActionError(null)}
          style={{ marginBottom: 12 }}
          title="操作未完成"
          type="error"
        />
      )}

      {loading ? (
        <Skeleton active paragraph={{ rows: 4 }} title={false} />
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
        <Flex gap={12} orientation="vertical">
          <Flex align="flex-end" gap={8} wrap>
            <div style={{ flex: '1 1 220px', minWidth: 0 }}>
              <label htmlFor="workspace-member-select">
                <Typography.Text strong>添加本部门成员</Typography.Text>
              </label>
              <Select
                aria-label="添加本部门成员"
                id="workspace-member-select"
                onChange={(value) => setAddUserId(value)}
                options={candidates.map((member) => ({
                  label: `${member.displayName}（${member.username}）`,
                  value: member.id,
                }))}
                placeholder={candidates.length > 0 ? '选择本部门成员' : '没有可添加的成员'}
                style={{ width: '100%' }}
                value={addUserId}
              />
            </div>
            <Button
              disabled={!addUserId}
              loading={adding}
              onClick={() => {
                void handleAdd();
              }}
              type="primary"
            >
              加入空间
            </Button>
          </Flex>

          {members.length === 0 ? (
            <Empty
              description="还没有显式成员；当前只有空间管理员可见"
              image={Empty.PRESENTED_IMAGE_SIMPLE}
            />
          ) : (
            <ul aria-label="空间显式成员" className="workspace-member-list">
              {members.map((member) => (
                <li className="workspace-member-row" key={member.userId}>
                  <div className="workspace-member-name">
                    <Typography.Text strong>{member.displayName}</Typography.Text>
                    <Typography.Text type="secondary">{member.username}</Typography.Text>
                  </div>
                  <Tag color="blue">显式成员</Tag>
                  <Button
                    aria-label={`移出成员：${member.displayName}`}
                    danger
                    loading={removingId === member.userId}
                    onClick={() => {
                      void handleRemove(member.userId);
                    }}
                    size="small"
                  >
                    移出成员
                  </Button>
                </li>
              ))}
            </ul>
          )}
        </Flex>
      )}
    </Modal>
  );
}
