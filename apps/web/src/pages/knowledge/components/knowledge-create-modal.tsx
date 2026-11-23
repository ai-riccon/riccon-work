import { FileAddOutlined, ReloadOutlined } from '@ant-design/icons';
import {
  Alert,
  Button,
  Checkbox,
  Form,
  Input,
  Modal,
  Radio,
  Select,
  Space,
  Spin,
  Typography,
} from 'antd';
import { useEffect, useState } from 'react';
import type { Department, SessionInfo, UserSummary, WorkspaceFile } from '@/services/api/types';
import { formatBytes } from '@/services/files/file-validation';
import { describeKnowledgeCreateError, knowledgeVisibilityLabel } from '@/services/knowledge';
import type {
  KnowledgeApi,
  KnowledgeDocument,
  KnowledgeDocumentCreateRequest,
  KnowledgeUserDirectory,
  KnowledgeVisibility,
} from '@/services/knowledge';

/** 新文档登记表单值。 */
interface CreateFormValues {
  title: string;
  fileId: string;
  visibility: KnowledgeVisibility;
  departmentId?: string | null;
  userIds?: string[];
  confirmed: boolean;
}

/** 新文档登记弹窗属性。 */
export interface KnowledgeCreateModalProps {
  /** 是否打开。 */
  open: boolean;
  /** 关闭回调。 */
  onClose: () => void;
  /** 知识库 API。 */
  api: KnowledgeApi;
  /** 可管理部门。 */
  departments: readonly Department[];
  /** 用户目录。 */
  userDirectory: KnowledgeUserDirectory;
  /** 当前会话；用于确认创建人与排除自身。 */
  session: SessionInfo | null;
  /** 读取当前工作空间文件；由工作空间页面注入。 */
  listWorkspaceFiles: () => Promise<WorkspaceFile[]>;
  /** 创建成功回调。 */
  onCreated: (document: KnowledgeDocument) => void;
}

/** 生成幂等键；避免网络重试产生重复文档。 */
function createRequestKey(): string {
  if (typeof globalThis.crypto?.randomUUID === 'function') {
    return globalThis.crypto.randomUUID();
  }
  return `kb-create-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

/**
 * 根据表单值构造登记请求。
 *
 * 创建人由服务端从会话派生；USERS 名单过滤当前账号，避免把创建人误列进显式名单。
 *
 * @param values 表单值
 * @param currentUserId 当前账号 ID
 * @returns 登记请求
 */
export function buildKnowledgeCreateRequest(
  values: CreateFormValues,
  currentUserId: string | null | undefined,
): KnowledgeDocumentCreateRequest {
  const base = {
    title: values.title.trim(),
    fileId: values.fileId,
    visibility: values.visibility,
  };
  switch (values.visibility) {
    case 'DEPT':
      return { ...base, departmentId: values.departmentId ?? null };
    case 'USERS':
      return {
        ...base,
        userIds: [...new Set((values.userIds ?? []).filter((id) => id !== currentUserId))],
      };
    case 'TENANT':
    default:
      return base;
  }
}

/**
 * 新文档登记弹窗。
 *
 * 流程：选择工作空间已上传文件 → 填写标题 → 选择可见范围（默认 TENANT）→
 * 勾选影响确认 → 保存。保存前必须确认，避免默认全租户可见被静默接受。
 * 成功后面板刷新列表；解析在后台继续，不阻塞其他文档提问。
 *
 * @param props 弹窗、目录与回调
 * @returns 登记弹窗
 */
export function KnowledgeCreateModal({
  open,
  onClose,
  api,
  departments,
  userDirectory,
  session,
  listWorkspaceFiles,
  onCreated,
}: KnowledgeCreateModalProps) {
  const [form] = Form.useForm<CreateFormValues>();
  const visibility = Form.useWatch('visibility', form) ?? 'TENANT';
  const selectedUserIds = Form.useWatch('userIds', form) ?? [];
  const [files, setFiles] = useState<WorkspaceFile[] | null>(null);
  const [filesLoading, setFilesLoading] = useState(false);
  const [filesError, setFilesError] = useState<string | null>(null);
  const [users, setUsers] = useState<UserSummary[] | null>(null);
  const [usersLoading, setUsersLoading] = useState(false);
  const [usersError, setUsersError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  const currentUserId = session?.user.id ?? null;
  const activeDepartments = departments.filter((department) => department.status === 'ACTIVE');

  /**
   * 读取工作空间文件；只保留未删除文件。
   */
  const loadFiles = async (): Promise<void> => {
    setFilesLoading(true);
    setFilesError(null);
    try {
      const items = await listWorkspaceFiles();
      setFiles(items.filter((file) => file.status !== 'DELETED'));
    } catch {
      setFilesError('工作空间文件加载失败，请稍后重试。');
    } finally {
      setFilesLoading(false);
    }
  };

  // 每次打开重置表单与错误；默认可见范围为 TENANT，但必须勾选影响确认才能提交。
  useEffect(() => {
    if (open) {
      form.resetFields();
      setSubmitError(null);
      void loadFiles();
    }
    // loadFiles 只依赖注入回调；打开时执行一次即可。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, form]);

  /**
   * 进入“指定用户”时按需加载账号目录。
   */
  const loadUsers = async (): Promise<void> => {
    if (users !== null || usersLoading) {
      return;
    }
    setUsersLoading(true);
    setUsersError(null);
    try {
      setUsers(await userDirectory.listUsers());
    } catch {
      setUsersError('用户列表加载失败，请稍后重试。');
    } finally {
      setUsersLoading(false);
    }
  };

  useEffect(() => {
    if (visibility === 'USERS') {
      void loadUsers();
    }
    // loadUsers 是稳定的一次性加载函数；依赖 visibility 即可。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visibility]);

  /**
   * 选择文件后，若标题为空则用文件名（去掉扩展名）预填，减少手工重复输入。
   *
   * @param fileId 选中文件 ID
   */
  const handleFileChange = (fileId: string): void => {
    const file = files?.find((item) => item.id === fileId);
    if (file && !form.getFieldValue('title')) {
      form.setFieldValue('title', file.originalName.replace(/\.[^.]+$/, ''));
    }
  };

  /**
   * 提交登记请求；服务端 202 后刷新列表并进入解析队列。
   *
   * @param values 表单值
   */
  const handleFinish = async (values: CreateFormValues): Promise<void> => {
    setSubmitting(true);
    setSubmitError(null);
    try {
      const created = await api.createDocument(
        buildKnowledgeCreateRequest(values, currentUserId),
        createRequestKey(),
      );
      form.resetFields();
      onCreated(created);
    } catch (error) {
      setSubmitError(describeKnowledgeCreateError(error));
    } finally {
      setSubmitting(false);
    }
  };

  const fileOptions = (files ?? []).map((file) => ({
    label: `${file.originalName} · ${formatBytes(file.sizeBytes)}${
      file.parse?.status === 'FAILED' ? ' · 解析失败，可重试' : ''
    }`,
    value: file.id,
  }));
  const userOptions = (users ?? [])
    .filter((user) => user.id !== currentUserId)
    .map((user) => ({
      label: user.displayName ? `${user.displayName}（${user.username}）` : user.username,
      value: user.id,
    }));

  return (
    <Modal
      data-testid="knowledge-create-modal"
      destroyOnHidden
      footer={null}
      onCancel={onClose}
      open={open}
      title="登记知识库文档"
    >
      <Alert
        description="登记后文档进入解析队列；解析完成前不会产生引用。保存后下一次列表、检索、引用与知识库下载请求按新范围判定。"
        showIcon
        style={{ marginBottom: 12 }}
        title="新文档默认租户全员可见，保存前必须确认影响。"
        type="warning"
      />
      <Form
        form={form}
        initialValues={{ visibility: 'TENANT', confirmed: false }}
        layout="vertical"
        onFinish={(values) => {
          void handleFinish(values);
        }}
        requiredMark
      >
        <Form.Item
          extra="文件来自当前工作空间；如无可用文件，请先在会话中上传。"
          label="源文件"
          name="fileId"
          rules={[{ required: true, message: '请选择工作空间中的文件。' }]}
        >
          <Select
            aria-label="源文件"
            loading={filesLoading}
            notFoundContent={
              filesLoading ? (
                <Spin size="small" />
              ) : filesError ? (
                '文件列表加载失败'
              ) : (
                '没有可登记文件'
              )
            }
            onChange={handleFileChange}
            optionFilterProp="label"
            options={fileOptions}
            placeholder="选择已上传文件"
            showSearch
          />
        </Form.Item>

        <Space align="center" size={8}>
          <Button
            data-testid="knowledge-create-reload-files"
            icon={<ReloadOutlined />}
            onClick={() => {
              void loadFiles();
            }}
            size="small"
            type="link"
          >
            刷新文件列表
          </Button>
          {files !== null && files.length === 0 && (
            <Typography.Text type="secondary">
              当前工作空间没有可登记文件；请先在会话中上传。
            </Typography.Text>
          )}
        </Space>

        {filesError && (
          <Alert
            data-testid="knowledge-create-files-error"
            description={filesError}
            showIcon
            style={{ marginBottom: 12 }}
            title="文件列表加载失败"
            type="error"
          />
        )}

        <Form.Item
          label="标题"
          name="title"
          rules={[
            { required: true, whitespace: true, message: '请输入文档标题。' },
            { max: 300, message: '标题最多 300 个字符。' },
          ]}
        >
          <Input
            aria-label="文档标题"
            maxLength={300}
            placeholder="例如：2026 年销售数据"
            showCount
          />
        </Form.Item>

        <Form.Item
          label="可见范围"
          name="visibility"
          rules={[{ required: true, message: '请选择可见范围。' }]}
        >
          <Radio.Group
            aria-label="可见范围"
            options={(['TENANT', 'DEPT', 'USERS'] as KnowledgeVisibility[]).map((value) => ({
              label: knowledgeVisibilityLabel(value),
              value,
            }))}
          />
        </Form.Item>

        <Typography.Paragraph type="secondary">
          {visibility === 'TENANT'
            ? '本租户全部启用员工可见；保存前必须确认。'
            : visibility === 'DEPT'
              ? '仅所选启用部门的成员可见；部门成员变更后下一次请求生效。'
              : '仅所选账号与创建人可见；名单最多 200 人。'}
        </Typography.Paragraph>

        {visibility === 'DEPT' && (
          <Form.Item
            label="部门"
            name="departmentId"
            rules={[{ required: true, message: '请选择单个启用部门。' }]}
          >
            <Select
              aria-label="可见部门"
              disabled={activeDepartments.length === 0}
              optionFilterProp="label"
              options={activeDepartments.map((department) => ({
                label: department.name,
                value: department.id,
              }))}
              placeholder="选择单个启用部门"
              showSearch
            />
          </Form.Item>
        )}

        {visibility === 'DEPT' && activeDepartments.length === 0 && (
          <Alert
            description="当前没有可管理的启用部门，无法保存“指定部门”；请联系平台管理员维护部门。"
            showIcon
            style={{ marginBottom: 12 }}
            title="没有可用部门"
            type="warning"
          />
        )}

        {visibility === 'USERS' && (
          <Form.Item
            extra="创建人始终可见，无需列入；未选择用户时仅创建人可见。"
            label={`指定用户（${selectedUserIds.length}/200）`}
            name="userIds"
            rules={[{ type: 'array', max: 200, message: '单文档最多 200 个用户。' }]}
          >
            <Select
              aria-label="指定用户"
              loading={usersLoading}
              maxCount={200}
              maxTagCount="responsive"
              mode="multiple"
              notFoundContent={
                usersLoading ? (
                  <Spin size="small" />
                ) : usersError ? (
                  '用户列表加载失败'
                ) : (
                  '没有可授权用户'
                )
              }
              optionFilterProp="label"
              options={userOptions}
              placeholder="选择可见用户"
              showSearch
            />
          </Form.Item>
        )}

        {usersError && visibility === 'USERS' && (
          <Alert
            description={usersError}
            showIcon
            style={{ marginBottom: 12 }}
            title="用户列表加载失败"
            type="error"
          />
        )}

        <Form.Item
          name="confirmed"
          rules={[
            {
              validator: (_rule, value: boolean) =>
                value ? Promise.resolve() : Promise.reject(new Error('请先确认可见范围影响。')),
            },
          ]}
          valuePropName="checked"
        >
          <Checkbox data-testid="knowledge-create-confirm">
            我已确认可见范围影响：保存后列表、检索、引用与知识库下载将按新范围判定。
          </Checkbox>
        </Form.Item>

        {submitError && (
          <Alert
            data-testid="knowledge-create-error"
            description={submitError}
            showIcon
            title="文档未登记"
            type="error"
          />
        )}

        <Button
          data-testid="knowledge-create-submit"
          htmlType="submit"
          icon={<FileAddOutlined />}
          loading={submitting}
          type="primary"
        >
          登记文档
        </Button>
      </Form>
    </Modal>
  );
}
