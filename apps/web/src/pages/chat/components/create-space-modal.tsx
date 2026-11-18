import { useCallback, useState } from 'react';
import { Alert, Form, Input, Modal, Radio, Select, Typography } from 'antd';
import type { WorkspaceManagementApi } from '@/services/api/client';
import type { Department, ManagedWorkspace } from '@/services/api/types';
import { describeWorkspaceError } from '@/services/workspaces/errors';

/** 创建部门空间弹窗参数。 */
export interface CreateSpaceModalProps {
  api: WorkspaceManagementApi;
  departments: Department[];
  onClose: () => void;
  onCreated: (workspace: ManagedWorkspace) => void;
}

/** 创建表单值。 */
interface CreateSpaceFormValues {
  departmentId: string;
  name: string;
  visibility: 'DEPT' | 'CUSTOM';
}

/**
 * 创建部门空间弹窗。
 *
 * 部门只能从当前用户可管理的启用部门中选择；可见性只提供 DEPT/CUSTOM，
 * 不提供历史 TENANT（M03 不允许新建）。
 *
 * @param props API、可管理部门与回调
 * @returns 创建空间弹窗
 */
export function CreateSpaceModal({ api, departments, onClose, onCreated }: CreateSpaceModalProps) {
  const [form] = Form.useForm<CreateSpaceFormValues>();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  /**
   * 提交创建请求。
   *
   * @param values 表单值
   */
  const handleFinish = useCallback(
    async (values: CreateSpaceFormValues): Promise<void> => {
      setSubmitting(true);
      setError(null);
      try {
        const workspace = await api.createDepartmentWorkspace(values.departmentId, {
          name: values.name.trim(),
          visibility: values.visibility,
        });
        onCreated(workspace);
      } catch (submitError) {
        setError(describeWorkspaceError(submitError, '空间创建失败，请稍后重试。'));
      } finally {
        setSubmitting(false);
      }
    },
    [api, onCreated, setError, setSubmitting],
  );

  return (
    <Modal
      afterClose={() => {
        form.resetFields();
        setError(null);
      }}
      cancelText="取消"
      confirmLoading={submitting}
      destroyOnHidden
      okText="创建空间"
      onCancel={onClose}
      onOk={() => form.submit()}
      open
      title="创建部门空间"
    >
      {error && (
        <Alert
          showIcon
          description={error}
          style={{ marginBottom: 12 }}
          title="空间未创建"
          type="error"
        />
      )}
      <Form
        form={form}
        initialValues={{ visibility: 'DEPT' }}
        layout="vertical"
        onFinish={(values) => {
          void handleFinish(values);
        }}
        preserve={false}
      >
        <Form.Item
          label="所属部门"
          name="departmentId"
          rules={[{ required: true, message: '请选择所属部门。' }]}
        >
          <Select
            aria-label="所属部门"
            options={departments.map((department) => ({
              label: department.name,
              value: department.id,
            }))}
            placeholder="选择可管理的启用部门"
          />
        </Form.Item>
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
        <Form.Item label="可见范围" name="visibility">
          <Radio.Group aria-label="空间可见范围">
            <Radio value="DEPT">本部门成员（当前部门全部启用成员可见）</Radio>
            <Radio value="CUSTOM">指定成员（创建后手动添加）</Radio>
          </Radio.Group>
        </Form.Item>
        <Typography.Text type="secondary">
          创建后可在“工作空间共享”中调整可见性或管理指定成员。
        </Typography.Text>
      </Form>
    </Modal>
  );
}
