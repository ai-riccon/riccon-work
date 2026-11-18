import { useState } from 'react';
import { Alert, Form, Input, Modal } from 'antd';
import type { WorkspaceManagementApi } from '@/services/api/client';
import type { ManagedWorkspace } from '@/services/api/types';
import { describeWorkspaceError } from '@/services/workspaces/errors';

/** 空间重命名弹窗参数。 */
export interface RenameSpaceModalProps {
  api: WorkspaceManagementApi;
  workspace: ManagedWorkspace;
  onClose: () => void;
  onRenamed: (workspace: ManagedWorkspace) => void;
}

/** 重命名表单值。 */
interface RenameSpaceFormValues {
  name: string;
}

/**
 * 空间重命名弹窗。
 *
 * 复用 `PATCH /workspaces/{id}` 的 `name` 字段；历史 TENANT 空间不会打开该弹窗。
 * 服务端冲突（409）、无权（403）与不存在（404）使用集中中文映射，不展示后端英文原因。
 *
 * @param props API、空间与回调
 * @returns 重命名弹窗
 */
export function RenameSpaceModal({ api, workspace, onClose, onRenamed }: RenameSpaceModalProps) {
  const [form] = Form.useForm<RenameSpaceFormValues>();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  /**
   * 提交重命名请求。
   *
   * @param values 表单值
   */
  const handleFinish = async (values: RenameSpaceFormValues): Promise<void> => {
    setSubmitting(true);
    setError(null);
    try {
      const updated = await api.updateWorkspace(workspace.id, { name: values.name.trim() });
      onRenamed(updated);
    } catch (failure) {
      setError(describeWorkspaceError(failure, '空间重命名失败，请稍后重试。'));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      afterClose={() => {
        form.resetFields();
        setError(null);
      }}
      cancelText="取消"
      confirmLoading={submitting}
      destroyOnHidden
      okText="保存名称"
      onCancel={onClose}
      onOk={() => form.submit()}
      open
      title={`重命名空间 · ${workspace.name}`}
    >
      {error && (
        <Alert
          showIcon
          description={error}
          style={{ marginBottom: 12 }}
          title="空间未重命名"
          type="error"
        />
      )}
      <Form
        form={form}
        initialValues={{ name: workspace.name }}
        layout="vertical"
        onFinish={(values) => {
          void handleFinish(values);
        }}
        preserve={false}
      >
        <Form.Item
          label="空间名称"
          name="name"
          rules={[
            { required: true, message: '请输入空间名称。' },
            { max: 200, message: '空间名称不能超过 200 个字符。' },
          ]}
        >
          <Input maxLength={200} placeholder="例如：销售部项目空间" />
        </Form.Item>
      </Form>
    </Modal>
  );
}
