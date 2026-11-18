import { useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Button, Drawer, Empty, Flex, Skeleton, Tag, Typography } from 'antd';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import type { WorkspaceManagementApi } from '@/services/api/client';
import type { Department, ManagedWorkspace, Page, Workspace } from '@/services/api/types';
import { describeWorkspaceError } from '@/services/workspaces/errors';
import {
  canManageWorkspace,
  isReadOnlyWorkspace,
  workspaceVisibilityLabel,
} from '@/services/workspaces/visibility';
import { CreateSpaceModal } from './create-space-modal';
import { RenameSpaceModal } from './rename-space-modal';
import { SpaceMembersModal } from './space-members-modal';
import { SpaceVisibilityModal } from './space-visibility-modal';
import './workspace-sharing.css';

/** 工作空间共享管理抽屉参数。 */
export interface WorkspaceSharingDrawerProps {
  api: WorkspaceManagementApi;
  activeWorkspace: Workspace | null;
  manageableDepartments: Department[];
  departmentError: string | null;
  onClose: () => void;
  onChanged?: () => void;
  /** 重命名成功后同步聊天页空间切换器。 */
  onWorkspaceRenamed?: (workspace: ManagedWorkspace) => void;
}

/**
 * 工作空间共享管理抽屉。
 *
 * 职责：
 * 1. 列出当前用户可访问的团队空间，历史 TENANT 空间明确标记只读；
 * 2. 租户管理员/部门负责人创建部门空间，并在 DEPT/CUSTOM 间切换；
 * 3. CUSTOM 空间增删显式成员；
 * 4. 普通成员不会打开本抽屉（入口由页面按角色隐藏）。
 *
 * @param props API、部门上下文与回调
 * @returns 共享管理抽屉
 */
export function WorkspaceSharingDrawer({
  api,
  activeWorkspace,
  manageableDepartments,
  departmentError,
  onClose,
  onChanged,
  onWorkspaceRenamed,
}: WorkspaceSharingDrawerProps) {
  const [spaces, setSpaces] = useState<ManagedWorkspace[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [renameTarget, setRenameTarget] = useState<ManagedWorkspace | null>(null);
  const [visibilityTarget, setVisibilityTarget] = useState<ManagedWorkspace | null>(null);
  const [membersTarget, setMembersTarget] = useState<ManagedWorkspace | null>(null);

  const manageableIds = useMemo(
    () => new Set(manageableDepartments.map((department) => department.id)),
    [manageableDepartments],
  );

  /**
   * 加载团队空间。
   *
   * 内容可见谓词（`GET /workspaces?kind=TEAM`）不能覆盖“负责人未把自己加入 CUSTOM”的场景，
   * 因此同时读取每个可管理部门的元数据列表（`GET /departments/{id}/workspaces`）并合并去重。
   * 部门列表失败时保留内容可见结果；全部失败才展示错误，避免一个辅助请求遮蔽管理入口。
   */
  const load = useCallback(async (): Promise<void> => {
    setLoading(true);
    setLoadError(null);
    try {
      const requests: Promise<Page<ManagedWorkspace>>[] = [api.listTeamWorkspaces()];
      manageableDepartments.forEach((department) => {
        requests.push(api.listDepartmentWorkspaces(department.id));
      });
      const results = await Promise.allSettled(requests);
      const merged = new Map<string, ManagedWorkspace>();
      let firstFailure: unknown = null;
      results.forEach((result) => {
        if (result.status === 'fulfilled') {
          result.value.items.forEach((workspace) => {
            if (!merged.has(workspace.id)) {
              merged.set(workspace.id, workspace);
            }
          });
        } else if (firstFailure === null) {
          firstFailure = result.reason;
        }
      });
      if (merged.size === 0 && firstFailure !== null) {
        throw firstFailure;
      }
      setSpaces([...merged.values()]);
    } catch (failure) {
      setLoadError(describeWorkspaceError(failure, '空间列表加载失败，请重试。'));
    } finally {
      setLoading(false);
    }
  }, [api, manageableDepartments]);

  useEffect(() => {
    void load();
  }, [load]);

  const personalActive = activeWorkspace?.kind === 'PERSONAL';
  const canCreate = manageableDepartments.length > 0;

  /**
   * 处理新建空间成功。
   *
   * @param workspace 新建空间
   */
  const handleCreated = useCallback(
    (workspace: ManagedWorkspace): void => {
      setSpaces((previous) => [workspace, ...previous]);
      setCreateOpen(false);
      setNotice(`空间“${workspace.name}”已创建。`);
      onChanged?.();
    },
    [onChanged],
  );

  /**
   * 处理可见性变更成功。
   *
   * @param workspace 更新后的空间
   */
  const handleVisibilityUpdated = useCallback(
    (workspace: ManagedWorkspace): void => {
      setSpaces((previous) =>
        previous.map((item) => (item.id === workspace.id ? workspace : item)),
      );
      setVisibilityTarget(null);
      setNotice(`空间“${workspace.name}”的可见范围已更新。`);
      onChanged?.();
    },
    [onChanged],
  );

  /**
   * 处理重命名成功。
   *
   * @param workspace 更新后的空间
   */
  const handleRenamed = useCallback(
    (workspace: ManagedWorkspace): void => {
      setSpaces((previous) =>
        previous.map((item) => (item.id === workspace.id ? workspace : item)),
      );
      setRenameTarget(null);
      setNotice(`空间已重命名为“${workspace.name}”。`);
      onWorkspaceRenamed?.(workspace);
    },
    [onWorkspaceRenamed],
  );

  const sortedSpaces = useMemo(
    () =>
      [...spaces].sort((left, right) => {
        const leftLabel = left.departmentName ?? '';
        const rightLabel = right.departmentName ?? '';
        return leftLabel.localeCompare(rightLabel) || left.name.localeCompare(right.name);
      }),
    [spaces],
  );

  return (
    <>
      <Drawer
        onClose={onClose}
        open
        size="min(720px, 100vw)"
        title="工作空间共享"
        extra={
          <Button
            aria-label="刷新空间列表"
            icon={<ReloadOutlined />}
            loading={loading}
            onClick={() => {
              void load();
            }}
            size="small"
          >
            刷新
          </Button>
        }
      >
        <Flex gap={12} orientation="vertical">
          <Alert
            showIcon
            description="部门空间只对“本部门当前启用成员”或“指定成员”可见；成员被移出部门或部门被停用后，下一次请求即不可访问。"
            title="空间可见范围"
            type="info"
          />
          {personalActive && (
            <Alert
              showIcon
              description="当前是个人空间，仅你本人可见；个人空间不提供成员管理。"
              title="个人空间"
              type="info"
            />
          )}
          {departmentError && (
            <Alert showIcon description={departmentError} title="部门信息加载失败" type="warning" />
          )}
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

          <Flex align="center" gap={8} justify="space-between" wrap>
            <div>
              <Typography.Text strong>可管理部门</Typography.Text>
              <Typography.Paragraph style={{ margin: 0 }} type="secondary">
                {manageableDepartments.length > 0
                  ? manageableDepartments.map((department) => department.name).join('、')
                  : '没有可管理的启用部门；请联系平台管理员创建部门并任命负责人。'}
              </Typography.Paragraph>
            </div>
            <Button
              aria-label="创建部门空间"
              disabled={!canCreate}
              icon={<PlusOutlined />}
              onClick={() => setCreateOpen(true)}
              type="primary"
            >
              创建部门空间
            </Button>
          </Flex>

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
              title="空间列表加载失败"
              type="error"
            />
          ) : sortedSpaces.length === 0 ? (
            <Empty
              description={
                canCreate
                  ? '还没有可访问的团队空间；点击“创建部门空间”开始。'
                  : '还没有可访问的团队空间。'
              }
              image={Empty.PRESENTED_IMAGE_SIMPLE}
            />
          ) : (
            <ul aria-label="团队空间列表" className="workspace-sharing-list">
              {sortedSpaces.map((space) => {
                const manageable = canManageWorkspace(space, manageableIds);
                const readOnly = isReadOnlyWorkspace(space);
                return (
                  <li className="workspace-sharing-item" key={space.id}>
                    <div className="workspace-sharing-title">
                      <Typography.Text strong>{space.name}</Typography.Text>
                      <Tag color={space.visibility === 'TENANT' ? 'orange' : 'blue'}>
                        {workspaceVisibilityLabel(space.visibility)}
                      </Tag>
                      {readOnly && <Tag>只读</Tag>}
                    </div>
                    <Typography.Text type="secondary">
                      {space.departmentName ? `部门：${space.departmentName}` : '无部门'}
                      {space.description ? ` · ${space.description}` : ''}
                    </Typography.Text>
                    {space.visibility === 'TENANT' && (
                      <Typography.Text type="secondary">
                        M02 历史全租户空间：可见性不可转换，M03 不提供成员管理。
                      </Typography.Text>
                    )}
                    {!readOnly && !manageable && (
                      <Typography.Text type="secondary">
                        你只有访问权限，管理入口仅对租户管理员或本部门负责人可见。
                      </Typography.Text>
                    )}
                    {manageable && (
                      <Flex gap={8} wrap>
                        {space.visibility === 'CUSTOM' && (
                          <Button onClick={() => setMembersTarget(space)} size="small">
                            管理成员
                          </Button>
                        )}
                        <Button onClick={() => setRenameTarget(space)} size="small">
                          重命名
                        </Button>
                        <Button onClick={() => setVisibilityTarget(space)} size="small">
                          改可见性
                        </Button>
                      </Flex>
                    )}
                  </li>
                );
              })}
            </ul>
          )}

          <Typography.Paragraph style={{ margin: 0 }} type="secondary">
            部门由平台管理员维护；部门负责人只能管理本部门空间，不能调整部门成员归属或创建部门。
          </Typography.Paragraph>
        </Flex>
      </Drawer>

      {createOpen && (
        <CreateSpaceModal
          api={api}
          departments={manageableDepartments}
          onClose={() => setCreateOpen(false)}
          onCreated={handleCreated}
        />
      )}
      {renameTarget && (
        <RenameSpaceModal
          api={api}
          onClose={() => setRenameTarget(null)}
          onRenamed={handleRenamed}
          workspace={renameTarget}
        />
      )}
      {visibilityTarget && (
        <SpaceVisibilityModal
          api={api}
          onClose={() => setVisibilityTarget(null)}
          onUpdated={handleVisibilityUpdated}
          workspace={visibilityTarget}
        />
      )}
      {membersTarget && (
        <SpaceMembersModal
          api={api}
          onClose={() => setMembersTarget(null)}
          workspace={membersTarget}
        />
      )}
    </>
  );
}
