import { Alert, Button, Form, Radio, Select, Space, Spin, Typography } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import type { Department, UserSummary } from '@/services/api/types';
import {
  KNOWLEDGE_PERMISSIONS,
  describeKnowledgeError,
  hasKnowledgePermission,
  knowledgeVisibilityLabel,
} from '@/services/knowledge';
import type {
  KnowledgeApi,
  KnowledgeDocument,
  KnowledgeDocumentAcl,
  KnowledgeDocumentAclUpdate,
  KnowledgeUserDirectory,
  KnowledgeVisibility,
} from '@/services/knowledge';
import type { SessionInfo } from '@/services/api/types';

/** ACL 编辑表单值。 */
interface AclFormValues {
  visibility: KnowledgeVisibility;
  departmentId?: string | null;
  userIds?: string[];
}

/** ACL 编辑器属性。 */
export interface KnowledgeAclEditorProps {
  /** 文档。 */
  document: KnowledgeDocument;
  /** 当前 ACL。 */
  acl: KnowledgeDocumentAcl;
  /** 可管理（ACTIVE）部门；来自空间管理读取。 */
  departments: readonly Department[];
  /** 用户目录；进入“指定用户”时按需加载。 */
  userDirectory: KnowledgeUserDirectory;
  /** 知识库 API。 */
  api: KnowledgeApi;
  /** 当前会话，用于过滤创建人。 */
  session: SessionInfo | null;
  /** 保存成功回调。 */
  onSaved: (document: KnowledgeDocument) => void;
  /** 取消编辑。 */
  onCancel: () => void;
}

/**
 * 把 ACL 转换为表单初始值。
 *
 * `USERS` 名单中的创建人条目由服务端隐式保证，不进入可编辑选择；
 * 避免用户误以为移除创建人标签就能撤销其访问。
 *
 * @param acl 当前 ACL
 * @param creatorUserId 创建人 ID
 * @returns 表单值
 */
export function toAclFormValues(
  acl: KnowledgeDocumentAcl,
  creatorUserId: string | null | undefined,
): AclFormValues {
  return {
    visibility: acl.visibility,
    departmentId: acl.visibility === 'DEPT' ? (acl.departmentId ?? null) : null,
    userIds:
      acl.visibility === 'USERS'
        ? acl.users
            .filter((user) => !user.creator && user.userId !== creatorUserId)
            .map((user) => user.userId)
        : [],
  };
}

/**
 * 根据表单值构造整体替换请求。
 *
 * `TENANT` 只发送 visibility，显式清空部门与名单；`DEPT` 只发送单个部门；
 * `USERS` 发送去重后的名单（最多 200 人）。创建人始终由服务端追加，不进入请求。
 *
 * @param values 表单值
 * @param creatorUserId 创建人 ID
 * @returns 请求体
 */
export function buildAclUpdate(
  values: AclFormValues,
  creatorUserId: string | null | undefined,
): KnowledgeDocumentAclUpdate {
  switch (values.visibility) {
    case 'DEPT':
      return { visibility: 'DEPT', departmentId: values.departmentId ?? null };
    case 'USERS':
      return {
        visibility: 'USERS',
        userIds: [...new Set((values.userIds ?? []).filter((id) => id !== creatorUserId))],
      };
    case 'TENANT':
    default:
      return { visibility: 'TENANT' };
  }
}

/**
 * 文档可见范围编辑器。
 *
 * 安全与一致性：
 * 1. 保存前必须选择范围；`DEPT` 必须选择单个启用部门；
 * 2. 保存会同时影响列表、检索、引用与知识库下载，界面在提交前明示；
 * 3. 不做乐观更新，服务端成功后再刷新 ACL 与文档；
 * 4. 创建人始终可见，不能通过名单移除。
 *
 * @param props 文档、ACL、目录与回调
 * @returns ACL 编辑器
 */
export function KnowledgeAclEditor({
  document,
  acl,
  departments,
  userDirectory,
  api,
  session,
  onSaved,
  onCancel,
}: KnowledgeAclEditorProps) {
  const [form] = Form.useForm<AclFormValues>();
  const visibility = Form.useWatch('visibility', form) ?? acl.visibility;
  const selectedUserIds = Form.useWatch('userIds', form) ?? [];
  const [users, setUsers] = useState<UserSummary[] | null>(null);
  const [usersLoading, setUsersLoading] = useState(false);
  const [usersError, setUsersError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  const creatorUserId = acl.creatorUserId ?? null;
  const canManageAcl = hasKnowledgePermission(
    session?.permissions,
    KNOWLEDGE_PERMISSIONS.aclManage,
  );
  const activeDepartments = useMemo(
    () => departments.filter((department) => department.status === 'ACTIVE'),
    [departments],
  );

  // ACL 异步加载完成后同步表单；不能在首次渲染时一次性 initialValues，否则父组件刷新会丢失最新值。
  useEffect(() => {
    form.setFieldsValue(toAclFormValues(acl, creatorUserId));
  }, [acl, creatorUserId, form]);

  /**
   * 按需加载可授权用户；只在用户切到“指定用户”时请求，避免无权限页面产生 403 噪音。
   */
  const loadUsers = async (): Promise<void> => {
    if (users !== null || usersLoading) {
      return;
    }
    setUsersLoading(true);
    setUsersError(null);
    try {
      const page = await userDirectory.listUsers();
      setUsers(page);
    } catch (error) {
      setUsersError(
        error instanceof Error ? descriptionForUserLoad(error) : '用户列表加载失败，请稍后重试。',
      );
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
   * 提交整体替换。
   *
   * @param values 表单值
   */
  const handleFinish = async (values: AclFormValues): Promise<void> => {
    setSaving(true);
    setSaveError(null);
    try {
      const updated = await api.updateDocumentAcl(
        document.id,
        buildAclUpdate(values, creatorUserId),
      );
      onSaved(updated);
    } catch (error) {
      setSaveError(describeKnowledgeError(error));
    } finally {
      setSaving(false);
    }
  };

  const userOptions = (users ?? [])
    .filter((user) => user.id !== creatorUserId)
    .map((user) => ({
      label: user.displayName ? `${user.displayName}（${user.username}）` : user.username,
      value: user.id,
    }));

  return (
    <section
      aria-labelledby="knowledge-acl-editor-title"
      className="knowledge-acl-editor"
      data-testid="knowledge-acl-form"
    >
      <Typography.Title id="knowledge-acl-editor-title" level={4}>
        修改可见范围
      </Typography.Title>
      <Alert
        description="保存后，下一次列表、检索、引用与知识库下载请求立即按新范围判定；知识库下载会同时授予这些用户源文件下载能力。创建人始终可见。"
        showIcon
        style={{ marginBottom: 12 }}
        title="影响提示"
        type="warning"
      />
      <Form
        form={form}
        layout="vertical"
        onFinish={(values) => {
          void handleFinish(values);
        }}
        requiredMark
      >
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
            ? '本租户全部启用员工可见；保存时会清空部门与显式名单。'
            : visibility === 'DEPT'
              ? '仅所选启用部门的成员可见；成员加入/移出部门后下一次请求生效。'
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
              options={activeDepartments.map((department) => ({
                label: department.name,
                value: department.id,
              }))}
              placeholder="选择单个启用部门"
              showSearch
              optionFilterProp="label"
            />
          </Form.Item>
        )}

        {visibility === 'DEPT' && activeDepartments.length === 0 && (
          <Alert
            description="当前没有可管理的启用部门，无法保存“指定部门”；请先联系平台管理员维护部门。"
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

        {saveError && (
          <Alert
            data-testid="knowledge-acl-error"
            description={saveError}
            showIcon
            style={{ marginBottom: 12 }}
            title="可见范围未保存"
            type="error"
          />
        )}

        <Space wrap>
          <Button
            data-testid="knowledge-acl-save"
            disabled={visibility === 'DEPT' && activeDepartments.length === 0}
            htmlType="submit"
            loading={saving}
            type="primary"
          >
            保存可见范围
          </Button>
          <Button disabled={saving} onClick={onCancel}>
            取消
          </Button>
        </Space>
        {!canManageAcl && acl.creatorUserId !== session?.user.id && (
          <Typography.Paragraph style={{ marginTop: 12 }} type="secondary">
            只有创建人或租户管理员可以修改可见范围。
          </Typography.Paragraph>
        )}
      </Form>
    </section>
  );
}

/**
 * 把用户目录错误归一为固定中文文案。
 *
 * @param error 任意异常
 * @returns 中文说明
 */
function descriptionForUserLoad(error: Error): string {
  const apiError = error as Error & { status?: number };
  if (apiError.status === 403) {
    return '没有读取账号列表的权限；请联系租户管理员授权后再编辑名单。';
  }
  if (apiError.status === 401) {
    return '登录状态已失效，请重新登录。';
  }
  return '用户列表加载失败，请稍后重试。';
}
