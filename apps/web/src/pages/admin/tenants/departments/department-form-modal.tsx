import { useState } from 'react';
import { Alert, Form, Input, Modal } from 'antd';
import type { PlatformDepartmentApi } from '@/services/api/client';
import type { Department } from '@/services/api/types';
import { describePlatformDepartmentError } from '@/services/workspaces/errors';

/** 部门创建/改名弹窗参数。 */
export interface DepartmentFormModalProps {
  api: PlatformDepartmentApi;
  tenantId: string;
  mode: 'create' | 'rename';
  department?: Department;
  onClose: () => void;
  onSaved: (department: Department, mode: 'create' | 'rename') => void;
}

/** 部门名称表单值。 */
interface DepartmentFormValues {
  name: string;
}

/**
 * 部门创建/改名弹窗。
 *
 * 两种操作共享同一名称校验（必填、最多 100 字符）；名称冲突由服务端返回
 * 409 `RICCON-DEPT-409`，这里显示稳定中文原因，不猜测或静默改名。
 *
 * @param props API、模式与回调
 * @returns 部门表单弹窗
 */
export function DepartmentFormModal({
  api,
  tenantId,
  mode,
  department,
  onClose,
  onSaved,
}: DepartmentFormModalProps) {
  const [form] = Form.useForm<DepartmentFormValues>();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const creating = mode === 'create';

  /**
   * 提交创建/改名请求。
   *
   * @param values 表单值
   */
  const handleFinish = async (values: DepartmentFormValues): Promise<void> => {
    setSubmitting(true);
    setError(null);
    try {
      const saved =
        creating || !department
          ? await api.createTenantDepartment(tenantId, { name: values.name.trim() })
          : await api.renameTenantDepartment(tenantId, department.id, {
              name: values.name.trim(),
            });
      onSaved(saved, mode);
    } catch (failure) {
      setError(describePlatformDepartmentError(failure, '部门保存失败，请稍后重试。'));
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
      okText={creating ? '创建部门' : '保存名称'}
      onCancel={onClose}
      onOk={() => form.submit()}
      open
      title={creating ? '创建部门' : `重命名部门 · ${department?.name ?? ''}`}
    >
      {error && (
        <Alert
          showIcon
          description={error}
          style={{ marginBottom: 12 }}
          title="部门未保存"
          type="error"
        />
      )}
      <Form
        form={form}
        initialValues={{ name: department?.name ?? '' }}
        layout="vertical"
        onFinish={(values) => {
          void handleFinish(values);
        }}
        preserve={false}
      >
        <Form.Item
          label="部门名称"
          name="name"
          rules={[
            { required: true, message: '请输入部门名称。' },
            { max: 100, message: '部门名称不能超过 100 个字符。' },
          ]}
        >
          <Input maxLength={100} placeholder="例如：销售部" />
        </Form.Item>
      </Form>
    </Modal>
  );
}
