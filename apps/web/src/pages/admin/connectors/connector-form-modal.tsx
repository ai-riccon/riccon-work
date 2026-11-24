import {
  Alert,
  AutoComplete,
  Button,
  Form,
  Input,
  Modal,
  Radio,
  Select,
  Switch,
  Typography,
} from 'antd';
import { useEffect, useMemo } from 'react';
import type { Department } from '@/services/api/types';
import type {
  Connector,
  ConnectorCredential,
  ConnectorFormSubmission,
  ConnectorVisibility,
} from '@/services/connector';
import { formatCredentialHint } from '@/services/connector';

/** 连接器表单弹窗模式。 */
export type ConnectorFormMode = 'create' | 'edit';

/** 表单值；凭据字段只在弹窗内短暂存在。 */
interface ConnectorFormValues {
  name?: string;
  endpointId?: string;
  bucket?: string;
  prefix?: string;
  region?: string;
  includeExtensions?: string[];
  excludeGlobs?: string[];
  defaultVisibility?: ConnectorVisibility;
  defaultDepartmentId?: string;
  scheduleEnabled?: boolean;
  accessKeyId?: string;
  secretAccessKey?: string;
}

/** 连接器表单弹窗属性。 */
export interface ConnectorFormModalProps {
  /** 模式：创建或编辑。 */
  mode: ConnectorFormMode;
  /** 是否打开；关闭后销毁表单，避免凭据留在 DOM。 */
  open: boolean;
  /** 编辑目标；创建时为空。 */
  connector?: Connector | null;
  /** 本租户部门；只使用 ACTIVE 部门作为 DEPT 选项。 */
  departments: Department[];
  /** 部门加载中。 */
  departmentsLoading?: boolean;
  /** 部门加载失败说明；不影响 TENANT。 */
  departmentsError?: string | null;
  /** 重新加载部门。 */
  onReloadDepartments?: () => void;
  /** 已出现过的端点 ID，仅作为文本输入建议，不代表部署允许列表。 */
  endpointSuggestions?: readonly string[];
  /** 提交中，禁用重复提交。 */
  submitting: boolean;
  /** 服务端错误的固定中文说明。 */
  formError?: string | null;
  /** 取消/关闭。 */
  onCancel: () => void;
  /** 提交归一后的表单值；调用方负责发请求并丢弃凭据。 */
  onSubmit: (values: ConnectorFormSubmission) => void;
}

/** 契约允许的扩展名白名单。 */
const INCLUDE_EXTENSION_OPTIONS = ['pdf', 'docx', 'xlsx', 'png', 'jpg', 'jpeg', 'txt', 'md'].map(
  (value) => ({ value, label: value }),
);

/** endpointId 字符集，与后端 `ConnectorRequestValidator` 一致。 */
const ENDPOINT_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;

/**
 * 把标签数组归一为去重、去空白、最多 50 条。
 *
 * @param values 原始标签
 * @param limit 最大条数
 * @returns 归一后的数组
 */
function normalizeTags(values: readonly string[] | undefined, limit: number): string[] {
  const unique = new Set<string>();
  for (const value of values ?? []) {
    const trimmed = value.trim();
    if (trimmed.length > 0) {
      unique.add(trimmed);
    }
    if (unique.size >= limit) {
      break;
    }
  }
  return [...unique];
}

/**
 * 生成部门选项。
 *
 * 编辑 DEPT 连接器时，历史部门可能已停用或不在当前可见目录；仍保留一个带 ID 的
 * 兜底选项，避免表单打开后看不到当前值。保存时后端会再次校验部门必须 ACTIVE。
 *
 * @param departments 可见部门
 * @param connector 编辑目标
 * @returns Select 选项
 */
function departmentOptions(departments: Department[], connector?: Connector | null) {
  const active = departments
    .filter((department) => department.status === 'ACTIVE')
    .map((department) => ({
      value: department.id,
      label: `${department.name}（${department.id.slice(0, 8)}…）`,
    }));
  const currentId = connector?.defaultDepartmentId;
  if (currentId && !active.some((option) => option.value === currentId)) {
    active.unshift({
      value: currentId,
      label: `当前部门（${currentId.slice(0, 8)}…，可能已停用）`,
    });
  }
  return active;
}

/**
 * 连接器创建/编辑弹窗。
 *
 * 类型固定为 S3；`defaultVisibility` 在创建时不预选，迫使管理员显式选择 TENANT 或
 * DEPT（缺省会被服务端 400 `RICCON-KB-400` 拒绝）。凭据只写不读：编辑时只展示
 * `credentialMaskedHint`，填写新的访问键与密钥即覆盖轮换；页面的请求体之外不保存明文。
 *
 * @param props 模式、部门目录与提交回调
 * @returns 连接器表单弹窗
 */
export function ConnectorFormModal({
  mode,
  open,
  connector,
  departments,
  departmentsLoading = false,
  departmentsError,
  onReloadDepartments,
  endpointSuggestions = [],
  submitting,
  formError,
  onCancel,
  onSubmit,
}: ConnectorFormModalProps) {
  const [form] = Form.useForm<ConnectorFormValues>();
  const visibility = Form.useWatch('defaultVisibility', form);
  const options = useMemo(
    () => departmentOptions(departments, connector),
    [connector, departments],
  );
  const endpointOptions = useMemo(
    () => [...new Set(endpointSuggestions)].map((value) => ({ value })),
    [endpointSuggestions],
  );

  // 每次打开都重置；编辑时回填已保存配置，创建时不预选 defaultVisibility。
  useEffect(() => {
    if (!open) {
      return;
    }
    form.resetFields();
    form.setFieldsValue(
      mode === 'edit' && connector
        ? {
            name: connector.name,
            endpointId: connector.endpointId,
            bucket: connector.config.bucket,
            prefix: connector.config.prefix ?? '',
            region: connector.config.region ?? '',
            includeExtensions: connector.config.includeExtensions ?? [],
            excludeGlobs: connector.config.excludeGlobs ?? [],
            defaultVisibility: connector.defaultVisibility,
            defaultDepartmentId: connector.defaultDepartmentId ?? undefined,
            scheduleEnabled: connector.scheduleEnabled,
            accessKeyId: '',
            secretAccessKey: '',
          }
        : {
            name: '',
            endpointId: '',
            bucket: '',
            prefix: '',
            region: '',
            includeExtensions: [],
            excludeGlobs: [],
            defaultVisibility: undefined,
            defaultDepartmentId: undefined,
            scheduleEnabled: false,
            accessKeyId: '',
            secretAccessKey: '',
          },
    );
  }, [connector, form, mode, open]);

  // 从 DEPT 切到 TENANT 时清空部门，避免提交“TENANT + 部门”的非法组合。
  const handleValuesChange = (changed: Partial<ConnectorFormValues>): void => {
    if (changed.defaultVisibility !== undefined && changed.defaultVisibility !== 'DEPT') {
      form.setFieldValue('defaultDepartmentId', undefined);
    }
  };

  /**
   * 校验凭据成对填写。
   *
   * 空表示不写入/不轮换；只填一个字段无法形成完整凭据，提交前直接阻断。
   *
   * @param _ 规则
   * @param value 当前字段值
   * @returns 校验 Promise
   */
  const validateCredentialPair = async (_: unknown, value: string | undefined) => {
    const accessKeyId = form.getFieldValue('accessKeyId');
    const secretAccessKey = form.getFieldValue('secretAccessKey');
    if ((accessKeyId && !secretAccessKey) || (!accessKeyId && secretAccessKey)) {
      throw new Error('访问键 ID 与密钥必须同时填写；留空表示不写入或轮换凭据。');
    }
    if (value && value !== value.trim()) {
      throw new Error('凭据不能包含首尾空白。');
    }
  };

  /**
   * 归一表单值并提交。
   *
   * @param values 表单值
   */
  const handleFinish = (values: ConnectorFormValues): void => {
    const includeExtensions = normalizeTags(values.includeExtensions, 8);
    const excludeGlobs = normalizeTags(values.excludeGlobs, 50);
    const prefix = (values.prefix ?? '').trim();
    const region = (values.region ?? '').trim();
    const credential: ConnectorCredential | undefined =
      values.accessKeyId && values.secretAccessKey
        ? { accessKeyId: values.accessKeyId, secretAccessKey: values.secretAccessKey }
        : undefined;
    onSubmit({
      name: (values.name ?? '').trim(),
      endpointId: (values.endpointId ?? '').trim(),
      config: {
        bucket: (values.bucket ?? '').trim(),
        ...(prefix.length > 0 ? { prefix } : {}),
        ...(region.length > 0 ? { region } : {}),
        ...(includeExtensions.length > 0 ? { includeExtensions } : {}),
        ...(excludeGlobs.length > 0 ? { excludeGlobs } : {}),
      },
      defaultVisibility: values.defaultVisibility as ConnectorVisibility,
      defaultDepartmentId:
        values.defaultVisibility === 'DEPT' ? values.defaultDepartmentId : undefined,
      scheduleEnabled: values.scheduleEnabled ?? false,
      ...(credential ? { credential } : {}),
    });
  };

  return (
    <Modal
      cancelText="取消"
      confirmLoading={submitting}
      destroyOnHidden
      okText={mode === 'create' ? '创建连接器' : '保存修改'}
      onCancel={onCancel}
      onOk={() => form.submit()}
      open={open}
      styles={{ body: { maxHeight: '68vh', overflowY: 'auto' } }}
      title={mode === 'create' ? '新建知识库连接器' : `编辑连接器「${connector?.name ?? ''}」`}
      width="min(760px, 100vw)"
    >
      {mode === 'create' ? (
        <Alert
          description="创建后默认为“暂停”，不会自动同步；请先做连接测试并确认配置，再启用或立即同步。默认可见范围必须显式选择。"
          showIcon
          style={{ marginBottom: 16 }}
          title="创建后先测试再启用"
          type="info"
        />
      ) : (
        <Alert
          description="类型固定为 S3，不能修改；配置保存使用 PATCH，未修改字段保持不变。凭据留空表示不轮换。"
          showIcon
          style={{ marginBottom: 16 }}
          title="部分更新与不可变类型"
          type="info"
        />
      )}

      {formError && (
        <Alert
          aria-live="assertive"
          description={formError}
          role="alert"
          showIcon
          style={{ marginBottom: 16 }}
          title="保存未完成"
          type="error"
        />
      )}

      <Form
        clearOnDestroy
        form={form}
        layout="vertical"
        onFinish={handleFinish}
        onValuesChange={handleValuesChange}
        requiredMark
      >
        <Form.Item label="类型">
          <Typography.Text>S3 兼容目录（固定，不可修改）</Typography.Text>
        </Form.Item>

        <Form.Item
          label="连接器名称"
          name="name"
          rules={[
            { required: true, whitespace: true, message: '请填写连接器名称。' },
            { max: 120, message: '连接器名称不能超过 120 个字符。' },
          ]}
        >
          <Input autoComplete="off" maxLength={120} placeholder="例如：销售合同归档" />
        </Form.Item>

        <Form.Item
          extra="只填写部署允许列表 RICCON_CONNECTOR_ENDPOINTS 中的端点 ID（1–64 位字母、数字、点、下划线或短横线），不是 URL；未放行的 ID 会被服务端以 400 拒绝。"
          label="端点 ID（endpointId）"
          name="endpointId"
          rules={[
            { required: true, message: '请填写部署允许列表中的端点 ID。' },
            {
              pattern: ENDPOINT_ID_PATTERN,
              message: '端点 ID 需以字母或数字开头，只能包含字母、数字、点、下划线或短横线。',
            },
            { max: 64, message: '端点 ID 不能超过 64 个字符。' },
          ]}
        >
          <AutoComplete
            aria-label="端点 ID（部署允许列表中的标识，不是 URL）"
            options={endpointOptions}
            placeholder="例如 internal-s3"
          />
        </Form.Item>

        <Form.Item
          label="Bucket"
          name="bucket"
          rules={[
            { required: true, whitespace: true, message: '请填写 S3 bucket。' },
            { max: 255, message: 'bucket 不能超过 255 个字符。' },
          ]}
        >
          <Input autoComplete="off" maxLength={255} placeholder="例如：sales-archive" />
        </Form.Item>

        <Form.Item
          extra="缺省根前缀；只允许同步该前缀下的对象。日志与审计不记录对象 key。"
          label="对象前缀（prefix）"
          name="prefix"
          rules={[{ max: 1000, message: '前缀不能超过 1000 个字符。' }]}
        >
          <Input autoComplete="off" maxLength={1000} placeholder="例如：contracts/2026/" />
        </Form.Item>

        <Form.Item
          extra="留空使用部署默认区域。"
          label="区域（region）"
          name="region"
          rules={[{ max: 64, message: '区域不能超过 64 个字符。' }]}
        >
          <Input autoComplete="off" maxLength={64} placeholder="例如：cn-north-1" />
        </Form.Item>

        <Form.Item
          extra="留空使用部署白名单（pdf/docx/xlsx/png/jpg/jpeg/txt/md）。"
          label="允许同步的扩展名"
          name="includeExtensions"
        >
          <Select
            aria-label="允许同步的扩展名"
            maxCount={8}
            mode="multiple"
            options={INCLUDE_EXTENSION_OPTIONS}
            optionFilterProp="label"
            placeholder="默认全部白名单格式"
          />
        </Form.Item>

        <Form.Item
          extra="每行一个或回车添加，最多 50 条；命中排除规则的对象不会下载。"
          label="排除的 glob 模式"
          name="excludeGlobs"
          rules={[
            {
              validator: async (_: unknown, value: string[] | undefined) => {
                if ((value ?? []).some((item) => item.trim().length > 255)) {
                  throw new Error('单条 glob 模式不能超过 255 个字符。');
                }
              },
            },
          ]}
        >
          <Select
            aria-label="排除的 glob 模式"
            maxCount={50}
            mode="tags"
            notFoundContent={null}
            placeholder="例如：**/tmp/**、draft-*"
            suffix={null}
            tokenSeparators={[',', '\n']}
          />
        </Form.Item>

        <Form.Item
          label="默认可见范围"
          name="defaultVisibility"
          rules={[
            { required: true, message: '请显式选择 TENANT 或 DEPT；缺省会被服务端 400 拒绝。' },
          ]}
        >
          <Radio.Group
            aria-label="默认可见范围"
            options={[
              { value: 'TENANT', label: 'TENANT（本租户全员可见）' },
              { value: 'DEPT', label: 'DEPT（仅指定部门可见）' },
            ]}
          />
        </Form.Item>

        {visibility === 'DEPT' && (
          <Form.Item
            extra={
              departmentsError
                ? `部门目录加载失败：${departmentsError}`
                : '只能选择启用部门；导入文档按该部门可见。'
            }
            label="默认部门"
            name="defaultDepartmentId"
            rules={[{ required: true, message: '默认可见范围为 DEPT 时必须选择启用部门。' }]}
          >
            <Select
              aria-label="默认部门"
              loading={departmentsLoading}
              notFoundContent={
                departmentsError ? (
                  <span>
                    部门目录不可用。
                    {onReloadDepartments && (
                      <Button onClick={onReloadDepartments} size="small" type="link">
                        重试
                      </Button>
                    )}
                  </span>
                ) : (
                  '没有启用部门'
                )
              }
              options={options}
              optionFilterProp="label"
              placeholder="选择启用部门"
              showSearch
            />
          </Form.Item>
        )}

        <Form.Item
          extra="开启后参与部署固定周期同步；周期由部署配置决定，不支持 per-connector cron。关闭后仍可手动同步。"
          label="参与定时同步"
          name="scheduleEnabled"
          valuePropName="checked"
        >
          <Switch checkedChildren="开启" unCheckedChildren="关闭" />
        </Form.Item>

        {mode === 'edit' && (
          <Alert
            description={`当前凭据：${formatCredentialHint(
              connector?.credentialMaskedHint,
            )}。只显示掩码；填写新的访问键 ID 与密钥将覆盖写入（轮换），旧密文不保留。留空表示不修改。`}
            showIcon
            style={{ marginBottom: 16 }}
            title="凭据只写不读"
            type="warning"
          />
        )}

        <Form.Item
          extra={
            mode === 'create'
              ? '可选：创建时一并写入凭据；只写不读，响应只返回掩码。'
              : '可选：填写两项即轮换凭据；旧密文不保留。'
          }
          label="访问键 ID（accessKeyId）"
          name="accessKeyId"
          rules={[{ validator: validateCredentialPair }]}
        >
          <Input autoComplete="off" maxLength={256} placeholder="只写，不回显" />
        </Form.Item>

        <Form.Item
          extra="只随本次提交发送；页面、日志与审计都不会出现明文。"
          label="访问密钥（secretAccessKey）"
          name="secretAccessKey"
          rules={[{ validator: validateCredentialPair }]}
        >
          <Input.Password autoComplete="new-password" maxLength={1024} placeholder="只写，不回显" />
        </Form.Item>

        <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
          凭据经服务端信封加密保存（purpose=connector-credential）；只有 Worker 在内存中解密使用。
          连接器软删除后密文保留 30 天供误删恢复，随后由运维维护任务清空。
        </Typography.Paragraph>
      </Form>
    </Modal>
  );
}
