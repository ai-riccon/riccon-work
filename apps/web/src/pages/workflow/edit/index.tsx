import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons';
import { useNavigate, useParams } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Divider,
  Empty,
  Flex,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Result,
  Segmented,
  Select,
  Skeleton,
  Space,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ApiError, describeApiError } from '@/services/api/errors';
import { formatOffsetDateTime } from '@/services/api/time';
import { useAuth } from '@/services/auth/auth-store';
import type { Department, McpTool } from '@/services/api/types';
import type { EmployeeModel } from '@/services/model/types';
import {
  hasWorkflowPermission,
  NODE_TYPE_LABELS,
  NODE_TYPE_OPTIONS,
  RUN_SCOPE_LABELS,
  runScopeLabel,
  WORKFLOW_PERMISSIONS,
  workflowDisplayStatus,
} from '../list/format';
import {
  workflowApi as defaultWorkflowApi,
  type WorkflowApi,
  type WorkflowDefinitionDetail,
  type WorkflowEdgeBranch,
  type WorkflowGraph,
  type WorkflowGraphNode,
  type WorkflowNodeType,
  type WorkflowRunScope,
  type WorkflowVersion,
} from '../list/workflow-api';
import {
  appendEdge,
  appendNode,
  createEdge,
  createNode,
  emptyGraph,
  moveNode,
  nodeConfigSummary,
  normalizeGraphForSave,
  removeEdge,
  removeNode,
  replaceNode,
  successorIds,
  validateEdgeForm,
  type EdgeFormValues,
} from './graph';
import {
  applyNodeFormValues,
  ASSIGNEE_ROLE_OPTIONS,
  collectNodeFormErrors,
  CONDITION_OPERATOR_OPTIONS,
  CONDITION_SOURCE_OPTIONS,
  CONDITION_VALUE_TYPE_OPTIONS,
  NODE_OUTPUT_PATH_OPTIONS,
  toNodeFormValues,
  type NodeFormValues,
} from './node-form';
import {
  fromServerIssue,
  issueCodeLabel,
  nodeLabel,
  validateGraph,
  type EditorIssue,
} from './validation';
import './editor.css';

/** 编辑器加载阶段。 */
type EditorPhase =
  'loading' | 'ready' | 'readOnly' | 'error' | 'forbidden' | 'notFound' | 'disabled';

/** 校验面板阶段。 */
type PanelPhase = 'idle' | 'validating' | 'valid' | 'invalid' | 'error';

/** 冲突副本的已创建信息；保存失败时可重试而不再新建副本。 */
interface DuplicateState {
  id: string;
  version: number;
}

/** 编辑器可注入依赖，便于组件测试。 */
export interface WorkflowEditorPageProps {
  /** 编排 API；默认同源真实客户端。 */
  api?: WorkflowApi;
  /** 流程 ID；测试注入，正常从路由参数读取。 */
  workflowId?: string;
  /** 导航；测试注入。 */
  navigate?: (to: string) => void;
}

/**
 * 判断错误是否表示编排总开关关闭。
 *
 * @param error 任意异常
 * @returns 是否为 503 `RICCON-WF-503`
 */
function isWorkflowDisabled(error: unknown): boolean {
  return error instanceof ApiError && error.status === 503 && error.code === 'RICCON-WF-503';
}

/**
 * 把错误转换为编辑器可展示的中文消息。
 *
 * @param error 任意异常
 * @param fallback 兜底文案
 * @returns 中文消息
 */
function editorErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError && error.status === 403) {
    return '你没有该流程的编辑权限，请联系租户管理员。';
  }
  return describeApiError(error, fallback);
}

/**
 * 节点配置表单。
 *
 * 以显式“保存节点配置”按钮提交：表单值先经纯函数校验与按类型清洗，再写回图状态。
 * 这样切换节点类型时不会把旧字段静默带入草稿，也让空字符串/未知字段在保存前被清理（F-003）。
 *
 * @param props 当前节点、图、目录与回写回调
 * @returns 节点配置表单
 */
function NodeConfigForm({
  node,
  graph,
  models,
  tools,
  catalogError,
  onApply,
}: {
  node: WorkflowGraphNode;
  graph: WorkflowGraph;
  models: EmployeeModel[];
  tools: McpTool[];
  catalogError: string | null;
  onApply: (node: WorkflowGraphNode) => void;
}) {
  const [form] = Form.useForm<NodeFormValues>();
  const [applyError, setApplyError] = useState<string | null>(null);
  const conditionSource = Form.useWatch('conditionLeftSource', form);
  const conditionOperator = Form.useWatch('conditionOperator', form);
  const outputKind = Form.useWatch('outputKind', form);
  const assigneeType = Form.useWatch('assigneeType', form);
  const actionKind = Form.useWatch('actionKind', form);

  useEffect(() => {
    form.setFieldsValue(toNodeFormValues(node));
    setApplyError(null);
  }, [form, node]);

  /**
   * 校验并应用节点配置。
   *
   * 先清除旧错误，再按类型校验；参数模板 JSON 解析失败时保留表单并显示错误。
   */
  const handleApply = (): void => {
    const values = form.getFieldsValue(true);
    const errors = collectNodeFormErrors(node.type, values, tools);
    const previousErrors = form
      .getFieldsError()
      .filter((field) => field.errors.length > 0)
      .map((field) => ({
        name: field.name[0] as keyof NodeFormValues,
        errors: [] as string[],
      }));
    form.setFields([
      ...previousErrors,
      ...errors.map((error) => ({ name: error.field, errors: [error.message] })),
    ]);
    if (errors.length > 0) {
      setApplyError('节点配置还有问题，请按字段提示修正。');
      return;
    }
    try {
      const updated = applyNodeFormValues(node, values);
      onApply(updated);
      setApplyError(null);
    } catch (error) {
      setApplyError(error instanceof Error ? error.message : '节点配置无法解析，请检查 JSON。');
    }
  };

  /**
   * 把表单恢复为当前已应用的节点配置。
   */
  const handleReset = (): void => {
    form.setFieldsValue(toNodeFormValues(node));
    setApplyError(null);
  };

  const modelOptions = models.map((model) => ({
    value: model.id,
    label: `${model.displayName}（${model.modelKey}）`,
  }));
  const toolOptions = tools.map((tool) => ({
    value: tool.id,
    label: `${tool.name}${tool.requiresApproval ? ' · 需审批' : ''}`,
  }));
  const nodeOptions = graph.nodes
    .filter((current) => current.id !== node.id)
    .map((current) => ({
      value: current.id,
      label: `${current.name}（${current.id}）`,
    }));
  const successorOptions = successorIds(graph, node.id)
    .map((id) => graph.nodes.find((current) => current.id === id))
    .filter((current): current is WorkflowGraphNode => Boolean(current))
    .map((current) => ({
      value: current.id,
      label: `${current.name}（${current.id}）`,
    }));

  return (
    <div id="workflow-node-config">
      <Flex className="workflow-config-heading" wrap align="center" justify="space-between">
        <Typography.Text strong>
          {NODE_TYPE_LABELS[node.type]} · {node.id}
        </Typography.Text>
        <Typography.Text type="secondary">修改后点击“保存节点配置”写回草稿</Typography.Text>
      </Flex>
      {catalogError ? (
        <Alert className="workflow-catalog-warning" type="warning" showIcon title={catalogError} />
      ) : null}
      <Form form={form} layout="vertical" onFinish={handleApply}>
        <Form.Item label="节点名称" name="name" required>
          <Input maxLength={200} placeholder="例如：检索知识库" />
        </Form.Item>

        {node.type === 'RETRIEVAL' ? (
          <>
            <Form.Item
              label="查询模板"
              name="queryTemplate"
              required
              extra="可引用输入变量，例如 {{input.topic}}"
            >
              <Input.TextArea rows={3} maxLength={2000} />
            </Form.Item>
            <Form.Item label="检索命中数（topK）" name="topK" required>
              <InputNumber min={1} max={20} style={{ width: '100%' }} />
            </Form.Item>
          </>
        ) : null}

        {node.type === 'MODEL' ? (
          <>
            <Form.Item label="提示词模板" name="promptTemplate" required>
              <Input.TextArea rows={4} maxLength={8000} />
            </Form.Item>
            <Form.Item label="输出形态" name="outputKind" required>
              <Select
                options={[
                  { value: 'TEXT', label: '文本' },
                  { value: 'CLASSIFICATION', label: '分类' },
                ]}
                placeholder="请选择输出形态"
              />
            </Form.Item>
            {outputKind === 'CLASSIFICATION' ? (
              <Form.Item
                label="分类标签"
                name="labelsText"
                required
                extra="用逗号或顿号分隔，最多 10 个"
              >
                <Input placeholder="例如：正面、中性、负面" />
              </Form.Item>
            ) : null}
            {modelOptions.length > 0 ? (
              <Form.Item
                label="模型（可选）"
                name="modelId"
                extra="留空时使用 REPORT 路由链默认模型"
              >
                <Select
                  allowClear
                  showSearch={{ optionFilterProp: 'label' }}
                  options={modelOptions}
                  placeholder="选择模型或留空"
                />
              </Form.Item>
            ) : (
              <Form.Item
                label="模型 ID（可选）"
                name="modelId"
                extra="模型目录不可用，可直接填写 UUID"
              >
                <Input placeholder="留空使用 REPORT 默认路由" />
              </Form.Item>
            )}
          </>
        ) : null}

        {node.type === 'MCP_TOOL' ? (
          <>
            {toolOptions.length > 0 ? (
              <Form.Item label="授权工具" name="toolId" required>
                <Select
                  allowClear
                  showSearch={{ optionFilterProp: 'label' }}
                  options={toolOptions}
                  placeholder="选择已授权工具"
                />
              </Form.Item>
            ) : (
              <Form.Item
                label="工具 ID"
                name="toolId"
                required
                extra="工具目录不可用，可直接填写 UUID"
              >
                <Input placeholder="输入已授权工具的 UUID" />
              </Form.Item>
            )}
            <Form.Item
              label="工具参数模板（JSON）"
              name="argsTemplateText"
              extra="顶层最多 16 个键；可引用运行输入"
            >
              <Input.TextArea rows={4} placeholder='例如 {"query": "{{input.topic}}"}' />
            </Form.Item>
          </>
        ) : null}

        {node.type === 'APPROVAL' ? (
          <>
            <Form.Item label="审批人类型" name="assigneeType" required>
              <Select
                options={[
                  { value: 'ROLE', label: '按角色' },
                  { value: 'USER', label: '按具体用户' },
                ]}
                placeholder="请选择审批人类型"
              />
            </Form.Item>
            {assigneeType === 'ROLE' ? (
              <Form.Item label="审批角色" name="assigneeRole" required>
                <Select options={ASSIGNEE_ROLE_OPTIONS} placeholder="请选择审批角色" />
              </Form.Item>
            ) : null}
            {assigneeType === 'USER' ? (
              <Form.Item
                label="审批人用户 ID"
                name="assigneeUserIds"
                required
                extra="输入同租户启用成员的 UUID 后回车；最多 50 人"
              >
                <Select
                  mode="tags"
                  tokenSeparators={[',', ' ']}
                  placeholder="输入用户 UUID 后回车"
                />
              </Form.Item>
            ) : null}
            <Form.Item
              label="审批解锁动作"
              name="actionKind"
              required
              extra="MCP 工具审批必须指向直接后继工具节点"
            >
              <Select
                options={[
                  { value: 'MCP_TOOL', label: 'MCP 工具调用' },
                  { value: 'MODEL', label: '模型调用' },
                  { value: 'CONTINUE', label: '继续后续节点' },
                ]}
                placeholder="请选择动作类型"
              />
            </Form.Item>
            <Form.Item label="目标节点键（targetNodeKey）" name="targetNodeKey">
              <Select
                allowClear
                options={successorOptions.length > 0 ? successorOptions : nodeOptions}
                placeholder={
                  actionKind === 'MCP_TOOL' ? 'MCP 工具审批必填，指向直接后继工具节点' : '可选'
                }
              />
            </Form.Item>
          </>
        ) : null}

        {node.type === 'CONDITION' ? (
          <>
            <Form.Item label="数据来源" name="conditionLeftSource" required>
              <Select options={CONDITION_SOURCE_OPTIONS} />
            </Form.Item>
            {conditionSource === 'NODE_OUTPUT' ? (
              <Form.Item label="上游节点" name="conditionLeftNodeId" required>
                <Select
                  options={nodeOptions}
                  showSearch={{ optionFilterProp: 'label' }}
                  placeholder="选择上游节点"
                />
              </Form.Item>
            ) : null}
            <Form.Item label="条件字段" name="conditionLeftPath" required>
              {conditionSource === 'NODE_OUTPUT' ? (
                <Select options={NODE_OUTPUT_PATH_OPTIONS} placeholder="选择受控输出字段" />
              ) : (
                <Input placeholder="运行输入键，例如 topic" maxLength={64} />
              )}
            </Form.Item>
            <Form.Item label="值类型" name="conditionValueType" required>
              <Select options={CONDITION_VALUE_TYPE_OPTIONS} />
            </Form.Item>
            <Form.Item label="比较算子" name="conditionOperator" required>
              <Select options={CONDITION_OPERATOR_OPTIONS} />
            </Form.Item>
            {conditionOperator === 'IN' ? (
              <Form.Item
                label="比较值集合"
                name="conditionRightValuesText"
                required
                extra="用逗号或顿号分隔"
              >
                <Input.TextArea rows={2} />
              </Form.Item>
            ) : (
              <Form.Item label="比较值" name="conditionRightValueText" required>
                <Input placeholder="按值类型填写，例如 3、positive、true" />
              </Form.Item>
            )}
          </>
        ) : null}

        {node.type === 'END' ? (
          <>
            <Form.Item label="结果来源节点" name="resultFrom" required>
              <Select
                options={graph.nodes
                  .filter((current) => current.id !== node.id && current.type !== 'START')
                  .map((current) => ({
                    value: current.id,
                    label: `${current.name}（${current.id}）`,
                  }))}
                placeholder="选择产生结果的节点"
              />
            </Form.Item>
            <Form.Item label="结果标题" name="resultTitle" extra="最多 200 个字符">
              <Input maxLength={200} placeholder="例如：市场调研报告" />
            </Form.Item>
          </>
        ) : null}

        {applyError ? <Alert type="error" showIcon title={applyError} /> : null}
        <Space wrap className="workflow-config-actions">
          <Button type="primary" onClick={handleApply}>
            保存节点配置
          </Button>
          <Button onClick={handleReset}>撤销修改</Button>
        </Space>
      </Form>
    </div>
  );
}

/**
 * 流程编辑器（表单为主路径）。
 *
 * 与后续画布增强（T11）共享同一 `WorkflowGraph` 与草稿 API。页面覆盖 AC-01/02/19/20：
 * 节点增删与配置、连线与条件 TRUE/FALSE、审批人与动作、保存草稿、409 冲突另存副本、
 * 校验面板定位节点、发布入口（含 400 失败提示）、键盘可达与 375px 单列布局。
 *
 * @param props API、流程 ID 与导航注入
 * @returns 流程编辑器界面
 */
export default function WorkflowEditorPage({
  api = defaultWorkflowApi,
  workflowId: injectedWorkflowId,
  navigate: injectedNavigate,
}: WorkflowEditorPageProps) {
  const umiNavigate = useNavigate();
  const navigate = injectedNavigate ?? umiNavigate;
  const params = useParams();
  const workflowId = injectedWorkflowId ?? params.workflowId ?? '';
  const auth = useAuth();
  const session = auth.session;
  const permissions = session?.permissions;
  const canManage = hasWorkflowPermission(permissions, WORKFLOW_PERMISSIONS.manage);

  const [phase, setPhase] = useState<EditorPhase>('loading');
  const [detail, setDetail] = useState<WorkflowDefinitionDetail | null>(null);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [graph, setGraph] = useState<WorkflowGraph>(emptyGraph());
  const [draftVersion, setDraftVersion] = useState(1);
  const [dirty, setDirty] = useState(false);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [addType, setAddType] = useState<WorkflowNodeType>('RETRIEVAL');
  const [models, setModels] = useState<EmployeeModel[]>([]);
  const [tools, setTools] = useState<McpTool[]>([]);
  const [departments, setDepartments] = useState<Department[]>([]);
  const [catalogError, setCatalogError] = useState<string | null>(null);
  const [panelPhase, setPanelPhase] = useState<PanelPhase>('idle');
  const [issues, setIssues] = useState<EditorIssue[]>([]);
  const [panelError, setPanelError] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [validating, setValidating] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [publishOpen, setPublishOpen] = useState(false);
  const [publishError, setPublishError] = useState<string | null>(null);
  const [runScope, setRunScope] = useState<WorkflowRunScope>('ONLY_SELF');
  const [departmentId, setDepartmentId] = useState<string | undefined>(undefined);
  const [conflictOpen, setConflictOpen] = useState(false);
  const [conflictError, setConflictError] = useState<string | null>(null);
  const [conflictBusy, setConflictBusy] = useState(false);
  const [duplicateState, setDuplicateState] = useState<DuplicateState | null>(null);
  const [edgeError, setEdgeError] = useState<string | null>(null);
  const [edgeForm] = Form.useForm<EdgeFormValues>();
  const edgeSourceId = Form.useWatch('source', edgeForm);
  const requestSeq = useRef(0);

  /**
   * 读取流程详情并重置编辑器状态。
   */
  const loadDetail = useCallback(async (): Promise<void> => {
    if (!workflowId) {
      setPhase('notFound');
      return;
    }
    const seq = requestSeq.current + 1;
    requestSeq.current = seq;
    setPhase('loading');
    setSaveError(null);
    setNotice(null);
    try {
      const loaded = await api.getWorkflow(workflowId);
      if (requestSeq.current !== seq) {
        return;
      }
      setDetail(loaded);
      setName(loaded.name);
      setDescription(loaded.description ?? '');
      setDraftVersion(loaded.draftVersion);
      const loadedGraph = normalizeGraphForSave(loaded.draftGraph ?? emptyGraph());
      setGraph(loadedGraph);
      setSelectedNodeId(loadedGraph.nodes[0]?.id ?? null);
      setDirty(false);
      setPanelPhase('idle');
      setIssues([]);
      setPanelError(null);
      setPublishError(null);
      setConflictOpen(false);
      setDuplicateState(null);
      setConflictError(null);
      if (loaded.latestVersion?.runScope) {
        setRunScope(loaded.latestVersion.runScope);
        setDepartmentId(loaded.latestVersion.departmentId ?? undefined);
      }
      setPhase(loaded.canEdit && loaded.draftGraph != null ? 'ready' : 'readOnly');
    } catch (caught) {
      if (requestSeq.current !== seq) {
        return;
      }
      if (isWorkflowDisabled(caught)) {
        setPhase('disabled');
        return;
      }
      if (caught instanceof ApiError && caught.status === 403) {
        setPhase('forbidden');
        return;
      }
      if (caught instanceof ApiError && caught.status === 404) {
        setPhase('notFound');
        return;
      }
      setSaveError(describeApiError(caught, '流程加载失败，请稍后重试。'));
      setPhase('error');
    }
  }, [api, workflowId]);

  useEffect(() => {
    void loadDetail();
  }, [loadDetail]);

  // 目录只加载一次：模型/工具用于下拉，部门用于“本部门”发布范围。
  // WORKFLOW_EDITOR 角色可能没有 model.read/mcp.read/department.read，失败时降级为手动填写 UUID。
  useEffect(() => {
    if (phase !== 'ready') {
      return;
    }
    let cancelled = false;
    const loadCatalogs = async (): Promise<void> => {
      const [modelResult, toolResult, departmentResult] = await Promise.allSettled([
        api.listModels(),
        api.listMcpTools(),
        api.listDepartments(),
      ]);
      if (cancelled) {
        return;
      }
      const failed: string[] = [];
      if (modelResult.status === 'fulfilled') {
        setModels(modelResult.value.items);
      } else {
        setModels([]);
        failed.push('模型');
      }
      if (toolResult.status === 'fulfilled') {
        setTools(toolResult.value.items);
      } else {
        setTools([]);
        failed.push('工具');
      }
      if (departmentResult.status === 'fulfilled') {
        setDepartments(departmentResult.value.items);
      } else {
        setDepartments([]);
      }
      setCatalogError(
        failed.length > 0
          ? `${failed.join('与')}目录加载失败：可直接填写 UUID，或刷新页面重试。`
          : null,
      );
    };
    void loadCatalogs();
    return () => {
      cancelled = true;
    };
  }, [api, phase]);

  const managerDepartments = useMemo(
    () =>
      departments.filter(
        (department) =>
          department.status === 'ACTIVE' && department.managerUserId === session?.user.id,
      ),
    [departments, session?.user.id],
  );

  // 部门目录异步返回或权限变化后，回退不可用的范围选择，避免发布请求被 403 拒绝。
  useEffect(() => {
    if (runScope === 'DEPARTMENT' && managerDepartments.length === 0) {
      setRunScope('ONLY_SELF');
      setDepartmentId(undefined);
    }
    if (runScope === 'TENANT' && !canManage) {
      setRunScope('ONLY_SELF');
    }
  }, [canManage, managerDepartments.length, runScope]);

  const selectedNode = graph.nodes.find((node) => node.id === selectedNodeId) ?? null;
  const liveIssues = useMemo(() => validateGraph(graph, tools), [graph, tools]);
  const display = detail
    ? workflowDisplayStatus({
        status: detail.status,
        publishedVersionNo: detail.publishedVersionNo,
        hasUnpublishedChanges: detail.hasUnpublishedChanges,
      })
    : null;
  const latestVersion: WorkflowVersion | null = detail?.latestVersion ?? null;
  const hasStart = graph.nodes.some((node) => node.type === 'START');

  /**
   * 标记本地修改并清空旧的校验/成功提示。
   */
  const markDirty = (): void => {
    setDirty(true);
    setNotice(null);
    setSaveError(null);
    setPanelPhase('idle');
    setIssues([]);
  };

  /**
   * 用新图替换编辑器状态。
   *
   * @param next 新图
   */
  const updateGraph = (next: WorkflowGraph): void => {
    setGraph(next);
    markDirty();
  };

  /**
   * 添加节点；开始节点只允许一个。
   */
  const handleAddNode = (): void => {
    if (addType === 'START' && hasStart) {
      setSaveError('流程只能有一个开始节点。');
      return;
    }
    const node = createNode(addType, graph);
    updateGraph(appendNode(graph, node));
    setSelectedNodeId(node.id);
  };

  /**
   * 写回节点配置。
   *
   * @param node 更新后的节点
   */
  const handleApplyNode = (node: WorkflowGraphNode): void => {
    updateGraph(replaceNode(graph, node));
    setNotice(null);
  };

  /**
   * 删除节点与关联引用。
   *
   * @param nodeId 节点 ID
   */
  const handleRemoveNode = (nodeId: string): void => {
    updateGraph(removeNode(graph, nodeId));
    if (selectedNodeId === nodeId) {
      setSelectedNodeId(null);
    }
  };

  /**
   * 节点顺序上移/下移。
   *
   * @param nodeId 节点 ID
   * @param offset -1 上移，1 下移
   */
  const handleMoveNode = (nodeId: string, offset: -1 | 1): void => {
    updateGraph(moveNode(graph, nodeId, offset));
  };

  /**
   * 添加连线。
   *
   * @param values 连线表单值
   */
  const handleAddEdge = (values: EdgeFormValues): void => {
    const errors = validateEdgeForm(graph, values);
    if (errors.length > 0) {
      setEdgeError(errors[0].message);
      return;
    }
    const source = graph.nodes.find((node) => node.id === values.source);
    const branch: WorkflowEdgeBranch | undefined =
      source?.type === 'CONDITION' ? values.branch : undefined;
    updateGraph(appendEdge(graph, createEdge(graph, values.source, values.target, branch)));
    setEdgeError(null);
    edgeForm.resetFields();
  };

  /**
   * 删除连线。
   *
   * @param edgeId 边 ID
   */
  const handleRemoveEdge = (edgeId: string): void => {
    updateGraph(removeEdge(graph, edgeId));
  };

  /**
   * 用保存响应刷新本地状态。
   *
   * @param saved 保存后的详情
   */
  const applySavedDetail = (saved: WorkflowDefinitionDetail): void => {
    setDetail(saved);
    setName(saved.name);
    setDescription(saved.description ?? '');
    setDraftVersion(saved.draftVersion);
    const savedGraph = normalizeGraphForSave(saved.draftGraph ?? emptyGraph());
    setGraph(savedGraph);
    setDirty(false);
    setPanelPhase('idle');
    setIssues([]);
    if (selectedNodeId && !savedGraph.nodes.some((node) => node.id === selectedNodeId)) {
      setSelectedNodeId(null);
    }
  };

  /**
   * 在需要时保存草稿；保存前执行客户端校验。
   *
   * @param notify 是否展示“已保存”提示
   * @returns 是否已持久化到服务端（无修改也返回 true）
   */
  const persistIfNeeded = async (notify: boolean): Promise<boolean> => {
    if (!name.trim()) {
      setSaveError('请输入流程名称。');
      return false;
    }
    const localIssues = validateGraph(graph, tools);
    if (localIssues.length > 0) {
      setSaveError(null);
      setIssues(localIssues);
      setPanelPhase('invalid');
      return false;
    }
    if (!dirty) {
      return true;
    }
    setSaving(true);
    setSaveError(null);
    try {
      const saved = await api.saveDraft(workflowId, {
        version: draftVersion,
        name: name.trim(),
        description: description.trim() || null,
        graph: normalizeGraphForSave(graph),
      });
      applySavedDetail(saved);
      if (notify) {
        setNotice(`草稿已保存（版本 v${saved.draftVersion}）。`);
      }
      return true;
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 409) {
        setConflictError(null);
        setConflictOpen(true);
        return false;
      }
      setSaveError(editorErrorMessage(caught, '保存草稿失败，请稍后重试。'));
      return false;
    } finally {
      setSaving(false);
    }
  };

  /**
   * 保存草稿按钮处理。
   */
  const handleSave = async (): Promise<void> => {
    await persistIfNeeded(true);
  };

  /**
   * 校验当前草稿：先保存，再调用 `POST /validate`。
   */
  const handleValidate = async (): Promise<void> => {
    setPanelPhase('validating');
    setPanelError(null);
    setIssues([]);
    setNotice(null);
    if (runScope === 'DEPARTMENT' && !departmentId) {
      setPanelPhase('error');
      setPanelError('请选择发布部门后再校验。');
      return;
    }
    const ready = await persistIfNeeded(false);
    if (!ready) {
      // 本地校验已把面板置为 invalid；只有名称等表单级错误保持 idle。
      setPanelPhase((current) => (current === 'validating' ? 'idle' : current));
      return;
    }
    setValidating(true);
    try {
      const result = await api.validateWorkflow(workflowId, {
        runScope,
        departmentId: runScope === 'DEPARTMENT' ? departmentId : undefined,
      });
      const mapped = result.issues.map(fromServerIssue);
      setIssues(mapped);
      setPanelPhase(result.valid ? 'valid' : 'invalid');
    } catch (caught) {
      setPanelError(editorErrorMessage(caught, '校验失败，请稍后重试。'));
      setPanelPhase('error');
    } finally {
      setValidating(false);
    }
  };

  /**
   * 打开发布确认弹窗。
   */
  const openPublish = (): void => {
    setPublishError(null);
    setPublishOpen(true);
  };

  /**
   * 发布当前草稿：保存 → 按范围校验 → 生成不可变版本。
   */
  const handlePublish = async (): Promise<void> => {
    setPublishError(null);
    setPublishing(true);
    try {
      if (runScope === 'DEPARTMENT' && !departmentId) {
        setPublishError('请选择发布部门。');
        return;
      }
      const ready = await persistIfNeeded(false);
      if (!ready) {
        setPublishOpen(false);
        return;
      }
      const scopeInput = {
        runScope,
        departmentId: runScope === 'DEPARTMENT' ? departmentId : undefined,
      };
      const validation = await api.validateWorkflow(workflowId, scopeInput);
      if (!validation.valid) {
        const mapped = validation.issues.map(fromServerIssue);
        setIssues(mapped);
        setPanelPhase('invalid');
        setPublishError(`流程还有 ${validation.issues.length} 个问题，修正后才能发布。`);
        return;
      }
      const version = await api.publishWorkflow(workflowId, scopeInput);
      setDetail((current) =>
        current
          ? {
              ...current,
              publishedVersionNo: version.versionNo,
              publishedVersionId: version.id,
              latestVersion: version,
              hasUnpublishedChanges: false,
            }
          : current,
      );
      setPublishOpen(false);
      setPanelPhase('valid');
      setIssues([]);
      setNotice(`发布成功：v${version.versionNo}（${runScopeLabel(version.runScope)}）。`);
    } catch (caught) {
      setPublishError(
        caught instanceof ApiError && caught.status === 400
          ? `发布失败（400）：${caught.message}`
          : editorErrorMessage(caught, '发布失败，请稍后重试。'),
      );
    } finally {
      setPublishing(false);
    }
  };

  /**
   * 409 冲突后另存副本：先复制服务端草稿，再把本地图写入副本。
   *
   * T02 的 duplicate 复制的是服务端当前草稿；为了不丢本地修改，副本创建后
   * 立即用本地图覆盖副本草稿。若覆盖失败，保留副本 ID 供重试，避免重复创建。
   */
  const handleDuplicate = async (): Promise<void> => {
    setConflictBusy(true);
    setConflictError(null);
    try {
      let copy = duplicateState;
      if (!copy) {
        const duplicated = await api.duplicateWorkflow(workflowId, {
          name: `${name.trim() || detail?.name || '流程'} 副本`,
        });
        copy = { id: duplicated.id, version: duplicated.draftVersion };
        setDuplicateState(copy);
      }
      const saved = await api.saveDraft(copy.id, {
        version: copy.version,
        name: `${name.trim() || detail?.name || '流程'} 副本`,
        description: description.trim() || null,
        graph: normalizeGraphForSave(graph),
      });
      setConflictOpen(false);
      setDuplicateState(null);
      navigate(`/workflows/${saved.id}/edit`);
    } catch (caught) {
      setConflictError(editorErrorMessage(caught, '另存副本失败，请稍后重试。'));
    } finally {
      setConflictBusy(false);
    }
  };

  /**
   * 放弃本地修改并重新加载服务端草稿。
   */
  const handleDiscard = async (): Promise<void> => {
    setConflictOpen(false);
    setDuplicateState(null);
    setConflictError(null);
    await loadDetail();
  };

  const backButton = (
    <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/workflows')}>
      返回流程列表
    </Button>
  );

  if (phase === 'loading') {
    return (
      <div className="workflow-page" data-testid="workflow-editor-page">
        {backButton}
        <div aria-busy="true" aria-label="正在加载流程编辑器" className="workflow-editor-loading">
          <Skeleton active paragraph={{ rows: 10 }} />
        </div>
      </div>
    );
  }

  if (phase === 'forbidden') {
    return (
      <div className="workflow-page">
        {backButton}
        <Result
          status="403"
          title="没有访问权限"
          subTitle="你没有该流程的访问权限，请联系租户管理员。"
          extra={[
            <Button key="retry" onClick={() => void loadDetail()}>
              重试
            </Button>,
            backButton,
          ]}
        />
      </div>
    );
  }

  if (phase === 'notFound') {
    return (
      <div className="workflow-page">
        {backButton}
        <Result
          status="404"
          title="流程不存在或不可见"
          subTitle="流程可能已被删除，或你没有访问权限。"
          extra={backButton}
        />
      </div>
    );
  }

  if (phase === 'disabled') {
    return (
      <div className="workflow-page">
        {backButton}
        <Result
          status="warning"
          title="编排功能已关闭"
          subTitle="编排功能已关闭，已有运行记录仍可查看。"
          extra={[
            <Button key="retry" type="primary" onClick={() => void loadDetail()}>
              重新加载
            </Button>,
            backButton,
          ]}
        />
      </div>
    );
  }

  if (phase === 'error') {
    return (
      <div className="workflow-page">
        {backButton}
        <Result
          status="error"
          title="流程加载失败"
          subTitle={saveError ?? '请稍后重试。'}
          extra={[
            <Button key="retry" type="primary" onClick={() => void loadDetail()}>
              重试
            </Button>,
            backButton,
          ]}
        />
      </div>
    );
  }

  if (phase === 'readOnly') {
    return (
      <div className="workflow-page">
        {backButton}
        <Result
          status="info"
          title={detail?.name ?? '只读流程'}
          subTitle="只有流程所有者与租户管理员可以编辑草稿。你可以查看流程状态，但不能修改节点或参数。"
          extra={backButton}
        />
      </div>
    );
  }

  const scopeOptions = [
    { label: RUN_SCOPE_LABELS.ONLY_SELF, value: 'ONLY_SELF' },
    {
      label: RUN_SCOPE_LABELS.DEPARTMENT,
      value: 'DEPARTMENT',
      disabled: managerDepartments.length === 0,
    },
    { label: RUN_SCOPE_LABELS.TENANT, value: 'TENANT', disabled: !canManage },
  ];

  return (
    <div className="workflow-page" data-testid="workflow-editor-page">
      <Flex className="workflow-editor-header" wrap gap={8} align="center" justify="space-between">
        <Space wrap size={[8, 8]}>
          {backButton}
          <Button icon={<ReloadOutlined />} onClick={() => void loadDetail()} disabled={saving}>
            重新加载
          </Button>
        </Space>
        <Space wrap size={[8, 8]}>
          <Button onClick={() => void handleSave()} loading={saving} disabled={publishing}>
            保存草稿
          </Button>
          <Button
            aria-label="校验"
            onClick={() => void handleValidate()}
            loading={validating}
            disabled={publishing}
          >
            校验
          </Button>
          <Button
            type="primary"
            aria-label="发布"
            onClick={openPublish}
            disabled={!detail?.canPublish || publishing}
          >
            发布
          </Button>
        </Space>
      </Flex>

      <Flex className="workflow-editor-title" wrap gap={8} align="center">
        <Typography.Title level={2}>{name || detail?.name}</Typography.Title>
        {display ? <Tag color={display.color}>{display.label}</Tag> : null}
        {dirty ? <Tag color="orange">有未保存修改</Tag> : null}
      </Flex>
      <Typography.Text type="secondary" className="workflow-editor-meta">
        草稿 v{draftVersion}
        {latestVersion
          ? ` · 最近发布 v${latestVersion.versionNo}（${formatOffsetDateTime(
              latestVersion.publishedAt,
            )}）`
          : ' · 尚未发布'}
      </Typography.Text>

      {notice ? (
        <Alert className="workflow-editor-alert" type="success" showIcon title={notice} />
      ) : null}
      {saveError ? (
        <Alert className="workflow-editor-alert" type="error" showIcon title={saveError} />
      ) : null}

      <Card title="流程信息" className="workflow-editor-card">
        <Flex vertical gap={12}>
          <div>
            <Typography.Text strong>流程名称</Typography.Text>
            <Input
              aria-label="流程名称"
              value={name}
              maxLength={200}
              onChange={(event) => {
                setName(event.target.value);
                markDirty();
              }}
            />
          </div>
          <div>
            <Typography.Text strong>说明</Typography.Text>
            <Input.TextArea
              aria-label="流程说明"
              value={description}
              maxLength={1000}
              rows={2}
              onChange={(event) => {
                setDescription(event.target.value);
                markDirty();
              }}
            />
          </div>
        </Flex>
      </Card>

      <div className="workflow-editor-grid">
        <Card
          title="节点"
          className="workflow-editor-card"
          extra={
            <Space wrap>
              <Select
                aria-label="节点类型"
                value={addType}
                options={NODE_TYPE_OPTIONS.map((option) => ({
                  ...option,
                  disabled: option.value === 'START' && hasStart,
                }))}
                onChange={(value) => setAddType(value)}
                style={{ minWidth: 130 }}
              />
              <Button onClick={handleAddNode} disabled={addType === 'START' && hasStart}>
                添加节点
              </Button>
            </Space>
          }
        >
          {graph.nodes.length === 0 ? (
            <Empty description="还没有节点。先添加开始节点、业务节点和结束节点。" />
          ) : (
            <ol className="workflow-node-list" aria-label="流程节点列表">
              {graph.nodes.map((node, index) => {
                const nodeIssues = liveIssues.filter((issue) => issue.nodeId === node.id);
                return (
                  <li
                    key={node.id}
                    className={`workflow-node-row${
                      node.id === selectedNodeId ? ' workflow-node-row-selected' : ''
                    }`}
                  >
                    <span className="workflow-node-order" aria-hidden="true">
                      {index + 1}
                    </span>
                    <div className="workflow-node-main">
                      <Button
                        type="link"
                        className="workflow-node-name"
                        aria-pressed={node.id === selectedNodeId}
                        onClick={() => setSelectedNodeId(node.id)}
                      >
                        {node.name}
                      </Button>
                      <Space wrap size={[4, 4]}>
                        <Tag>{NODE_TYPE_LABELS[node.type]}</Tag>
                        <Tag color={nodeIssues.length > 0 ? 'orange' : 'green'}>
                          {nodeIssues.length > 0 ? '待修正' : '已配置'}
                        </Tag>
                      </Space>
                      <Typography.Text type="secondary" className="workflow-node-summary">
                        {nodeConfigSummary(node)}
                      </Typography.Text>
                    </div>
                    <Space wrap size={[4, 4]} className="workflow-node-actions">
                      <Button
                        size="small"
                        aria-label={`上移节点 ${node.name}`}
                        disabled={index === 0}
                        onClick={() => handleMoveNode(node.id, -1)}
                      >
                        上移
                      </Button>
                      <Button
                        size="small"
                        aria-label={`下移节点 ${node.name}`}
                        disabled={index === graph.nodes.length - 1}
                        onClick={() => handleMoveNode(node.id, 1)}
                      >
                        下移
                      </Button>
                      <Popconfirm
                        title={`删除节点“${node.name}”？`}
                        description="关联连线与悬空引用会一并清理。"
                        okText="删除"
                        cancelText="取消"
                        onConfirm={() => handleRemoveNode(node.id)}
                      >
                        <Button size="small" danger aria-label={`删除节点 ${node.name}`}>
                          删除
                        </Button>
                      </Popconfirm>
                    </Space>
                  </li>
                );
              })}
            </ol>
          )}
        </Card>

        <Card title="节点配置" className="workflow-editor-card">
          {selectedNode ? (
            <NodeConfigForm
              key={selectedNode.id}
              node={selectedNode}
              graph={graph}
              models={models}
              tools={tools}
              catalogError={catalogError}
              onApply={handleApplyNode}
            />
          ) : (
            <Empty description="选择左侧节点后配置参数。" />
          )}
        </Card>
      </div>

      <Card title="连线" className="workflow-editor-card">
        <Typography.Paragraph type="secondary">
          连线定义串行执行顺序；条件节点的出边必须分别设置 TRUE 与 FALSE。
        </Typography.Paragraph>
        <Form form={edgeForm} layout="vertical" onFinish={handleAddEdge}>
          <Flex wrap gap={12} align="flex-end">
            <Form.Item
              label="源节点"
              name="source"
              rules={[{ required: true, message: '请选择源节点。' }]}
              className="workflow-edge-field"
            >
              <Select
                aria-label="连线源节点"
                options={graph.nodes.map((node) => ({
                  value: node.id,
                  label: `${node.name}（${NODE_TYPE_LABELS[node.type]}）`,
                }))}
                placeholder="选择源节点"
              />
            </Form.Item>
            <Form.Item
              label="目标节点"
              name="target"
              rules={[{ required: true, message: '请选择目标节点。' }]}
              className="workflow-edge-field"
            >
              <Select
                aria-label="连线目标节点"
                options={graph.nodes.map((node) => ({
                  value: node.id,
                  label: `${node.name}（${NODE_TYPE_LABELS[node.type]}）`,
                }))}
                placeholder="选择目标节点"
              />
            </Form.Item>
            {graph.nodes.find((node) => node.id === edgeSourceId)?.type === 'CONDITION' ? (
              <Form.Item
                label="分支"
                name="branch"
                rules={[{ required: true, message: '请选择 TRUE 或 FALSE。' }]}
                className="workflow-edge-field"
              >
                <Select
                  aria-label="连线的条件分支"
                  options={[
                    { value: 'TRUE', label: 'TRUE（条件成立）' },
                    { value: 'FALSE', label: 'FALSE（条件不成立）' },
                  ]}
                  placeholder="选择分支"
                />
              </Form.Item>
            ) : null}
            <Form.Item>
              <Button type="primary" htmlType="submit">
                添加连线
              </Button>
            </Form.Item>
          </Flex>
        </Form>
        {edgeError ? <Alert type="error" showIcon title={edgeError} /> : null}
        {graph.edges.length === 0 ? (
          <Empty description="还没有连线。添加从开始到结束的连线后才能保存。" />
        ) : (
          <ul className="workflow-edge-list" aria-label="流程连线列表">
            {graph.edges.map((edge) => {
              const source = graph.nodes.find((node) => node.id === edge.source);
              const target = graph.nodes.find((node) => node.id === edge.target);
              return (
                <li key={edge.id} className="workflow-edge-row">
                  <Typography.Text>
                    {source?.name ?? edge.source} → {target?.name ?? edge.target}
                  </Typography.Text>
                  {edge.branch ? <Tag color="blue">{edge.branch}</Tag> : null}
                  <Button
                    size="small"
                    danger
                    aria-label={`删除连线 ${edge.source} 到 ${edge.target}`}
                    onClick={() => handleRemoveEdge(edge.id)}
                  >
                    删除
                  </Button>
                </li>
              );
            })}
          </ul>
        )}
      </Card>

      <Card
        title="校验结果"
        className="workflow-editor-card"
        extra={
          <Button onClick={() => void handleValidate()} loading={validating}>
            重新校验
          </Button>
        }
      >
        <div aria-live="polite">
          {panelPhase === 'idle' ? (
            <Typography.Text type="secondary">
              尚未校验。点击“校验”保存草稿并检查流程。
            </Typography.Text>
          ) : null}
          {panelPhase === 'validating' ? <Skeleton active paragraph={{ rows: 2 }} /> : null}
          {panelPhase === 'valid' ? (
            <Alert type="success" showIcon title="校验通过，可以保存或发布。" />
          ) : null}
          {panelPhase === 'error' ? (
            <Alert type="error" showIcon title={panelError ?? '校验失败，请稍后重试。'} />
          ) : null}
          {panelPhase === 'invalid' ? (
            <>
              <Alert
                type="error"
                showIcon
                title={`流程还有 ${issues.length} 个问题，修正后才能发布。`}
              />
              <ul className="workflow-issue-list" data-testid="workflow-issue-list">
                {issues.map((issue, index) => (
                  <li
                    key={`${issue.code}-${issue.nodeId ?? 'graph'}-${issue.field ?? ''}-${index}`}
                    className="workflow-issue-row"
                  >
                    <Tag color="red">{issueCodeLabel(issue.code)}</Tag>
                    {issue.nodeId ? (
                      <Button
                        type="link"
                        className="workflow-issue-locate"
                        onClick={() => {
                          setSelectedNodeId(issue.nodeId ?? null);
                          document
                            .getElementById('workflow-node-config')
                            ?.scrollIntoView({ block: 'start' });
                        }}
                      >
                        定位到 {nodeLabel(graph, issue.nodeId)}
                      </Button>
                    ) : (
                      <Typography.Text type="secondary">流程级问题</Typography.Text>
                    )}
                    <Typography.Text title={issue.serverMessage}>{issue.message}</Typography.Text>
                  </li>
                ))}
              </ul>
            </>
          ) : null}
        </div>
      </Card>

      <Card title="发布" className="workflow-editor-card">
        <Typography.Paragraph type="secondary">
          发布后生成不可变版本，运行将绑定最近发布版本；编辑草稿不影响已发布版本。
        </Typography.Paragraph>
        <Flex wrap gap={12} align="center">
          <Segmented
            aria-label="运行范围"
            value={runScope}
            options={scopeOptions}
            onChange={(value) => {
              const scope = value as WorkflowRunScope;
              setRunScope(scope);
              if (scope !== 'DEPARTMENT') {
                setDepartmentId(undefined);
              }
            }}
          />
          {runScope === 'DEPARTMENT' ? (
            <Select
              aria-label="发布部门"
              value={departmentId}
              options={managerDepartments.map((department) => ({
                value: department.id,
                label: department.name,
              }))}
              onChange={setDepartmentId}
              placeholder="选择你负责的部门"
              style={{ minWidth: 180 }}
            />
          ) : null}
          <Button type="primary" onClick={openPublish} disabled={!detail?.canPublish}>
            发布版本
          </Button>
        </Flex>
        <Typography.Paragraph type="secondary" className="workflow-scope-hint">
          {runScope === 'DEPARTMENT' && managerDepartments.length === 0
            ? '只有部门负责人可以选择本部门范围。'
            : '仅自己：只有你能运行；本部门：你负责的部门成员可运行；本租户：租户内可运行用户均可运行。'}
        </Typography.Paragraph>
      </Card>

      <Modal
        title="确认发布"
        open={publishOpen}
        okText="发布"
        cancelText="取消"
        okButtonProps={{ 'aria-label': '发布' }}
        confirmLoading={publishing}
        onOk={() => void handlePublish()}
        onCancel={() => {
          if (!publishing) {
            setPublishOpen(false);
          }
        }}
      >
        <Typography.Paragraph>
          将按“{runScopeLabel(runScope)}
          ”范围发布当前草稿，生成不可变版本。发布前会自动保存并执行完整校验。
        </Typography.Paragraph>
        {publishError ? <Alert type="error" showIcon title={publishError} /> : null}
      </Modal>

      <Modal
        title="草稿已被其他会话修改"
        open={conflictOpen}
        closable={!conflictBusy}
        mask={{ closable: false }}
        onCancel={() => {
          if (!conflictBusy) {
            setConflictOpen(false);
            setConflictError(null);
          }
        }}
        footer={[
          <Button
            key="cancel"
            onClick={() => {
              setConflictOpen(false);
              setConflictError(null);
            }}
            disabled={conflictBusy}
          >
            取消
          </Button>,
          <Button key="discard" danger onClick={() => void handleDiscard()} disabled={conflictBusy}>
            放弃本地修改并加载最新草稿
          </Button>,
          <Button
            key="duplicate"
            type="primary"
            onClick={() => void handleDuplicate()}
            loading={conflictBusy}
          >
            另存副本
          </Button>,
        ]}
      >
        <Alert
          type="warning"
          showIcon
          title={`当前草稿版本 v${draftVersion} 已在其他会话被更新。请选择另存副本保留本地修改，或放弃本地修改。`}
        />
        <Divider />
        <Typography.Paragraph type="secondary">
          另存副本会先复制服务端最新草稿，再把当前编辑器内容保存到副本；不会覆盖对方修改。
        </Typography.Paragraph>
        {conflictError ? <Alert type="error" showIcon title={conflictError} /> : null}
      </Modal>
    </div>
  );
}
