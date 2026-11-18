import { useState } from 'react';
import { Alert, Modal, Radio, Typography } from 'antd';
import type { WorkspaceManagementApi } from '@/services/api/client';
import type { ManagedWorkspace } from '@/services/api/types';
import { describeWorkspaceError } from '@/services/workspaces/errors';

/** 可见性修改弹窗参数。 */
export interface SpaceVisibilityModalProps {
  api: WorkspaceManagementApi;
  workspace: ManagedWorkspace;
  onClose: () => void;
  onUpdated: (workspace: ManagedWorkspace) => void;
}

/** 修改弹窗只接受可转换的两种可见性。 */
type EditableVisibility = 'DEPT' | 'CUSTOM';

/**
 * 判断空间当前可见性是否可编辑。
 *
 * @param workspace 空间
 * @returns 是否可编辑
 */
function editableVisibility(workspace: ManagedWorkspace): EditableVisibility {
  return workspace.visibility === 'CUSTOM' ? 'CUSTOM' : 'DEPT';
}

/**
 * 可见性修改弹窗。
 *
 * 只在 DEPT/CUSTOM 之间切换；历史 TENANT 空间不会打开该弹窗。
 * DEPT→CUSTOM 会清空显式成员，CUSTOM→DEPT 会清空名单并恢复全部门可见，
 * 因此在提交前用 Alert 明确告知影响。
 *
 * @param props API、空间与回调
 * @returns 可见性修改弹窗
 */
export function SpaceVisibilityModal({
  api,
  workspace,
  onClose,
  onUpdated,
}: SpaceVisibilityModalProps) {
  const [visibility, setVisibility] = useState<EditableVisibility>(editableVisibility(workspace));
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const changed = visibility !== editableVisibility(workspace);
  const warning =
    changed && visibility === 'CUSTOM'
      ? '切换为“指定成员”后，本部门成员不再自动可见；原有显式成员名单会被清空，请保存后重新添加成员。'
      : changed && visibility === 'DEPT'
        ? '切换为“本部门成员”后，全部当前部门启用成员可见；显式成员名单会被清空。'
        : null;

  /**
   * 提交可见性变更。
   */
  const handleSubmit = async (): Promise<void> => {
    setSubmitting(true);
    setError(null);
    try {
      const updated = await api.updateWorkspace(workspace.id, { visibility });
      onUpdated(updated);
    } catch (submitError) {
      setError(describeWorkspaceError(submitError, '可见性修改失败，请稍后重试。'));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      cancelText="取消"
      confirmLoading={submitting}
      okButtonProps={{ disabled: !changed }}
      okText="保存可见性"
      onCancel={onClose}
      onOk={() => {
        void handleSubmit();
      }}
      open
      title={`修改可见性 · ${workspace.name}`}
    >
      {error && (
        <Alert
          showIcon
          description={error}
          style={{ marginBottom: 12 }}
          title="可见性未修改"
          type="error"
        />
      )}
      <Radio.Group
        aria-label="选择空间可见范围"
        onChange={(event) => setVisibility(event.target.value as EditableVisibility)}
        value={visibility}
      >
        <Radio value="DEPT">本部门成员</Radio>
        <Radio value="CUSTOM">指定成员</Radio>
      </Radio.Group>
      {warning && (
        <Alert
          showIcon
          description={warning}
          style={{ marginTop: 12 }}
          title="切换会改变可见范围"
          type="warning"
        />
      )}
      <Typography.Paragraph style={{ margin: '12px 0 0' }} type="secondary">
        历史全租户空间只读，不能转换可见性；跨部门搬移空间不在 M03 范围内。
      </Typography.Paragraph>
    </Modal>
  );
}
