import type { McpTool } from '@/services/api/types';
import { isUuid, normalizeNodeConfig } from './graph';
import { MAX_ASSIGNEE_USER_IDS, MAX_CONDITION_IN_VALUES } from './limits';
import type {
  WorkflowApprovalActionKind,
  WorkflowApprovalAssigneeType,
  WorkflowCondition,
  WorkflowConditionOperator,
  WorkflowConditionSource,
  WorkflowConditionValueType,
  WorkflowGraphNode,
  WorkflowNodeConfig,
  WorkflowNodeType,
  WorkflowOutputKind,
} from '../list/workflow-api';

/**
 * 节点配置表单的值模型与纯转换。
 *
 * 表单使用扁平字段（而不是直接绑定嵌套 `WorkflowNodeConfig`），原因是：
 * 1. `labels`、`argsTemplate`、条件右值在界面上是文本输入，需要显式解析；
 * 2. 切换节点类型后不相关的字段仍然存在，扁平模型更容易按类型清理；
 * 3. 校验与转换可脱离 React 穷举测试，页面只负责渲染和错误定位。
 */

/** 审批角色可选项；与后端 `WorkflowGraphNodeConfig.ASSIGNEE_ROLES` 一致。 */
export const ASSIGNEE_ROLE_OPTIONS = [
  { value: 'TENANT_ADMIN', label: '租户管理员' },
  { value: 'WORKFLOW_EDITOR', label: '流程编辑者' },
  { value: 'MEMBER', label: '普通员工' },
];

/** 条件算子中文名。 */
const OPERATOR_LABELS: Record<WorkflowConditionOperator, string> = {
  EQ: '等于',
  NE: '不等于',
  GT: '大于',
  GTE: '大于等于',
  LT: '小于',
  LTE: '小于等于',
  IN: '属于集合',
};

/** 条件算子选项。 */
export const CONDITION_OPERATOR_OPTIONS = (
  Object.keys(OPERATOR_LABELS) as WorkflowConditionOperator[]
).map((value) => ({ value, label: `${value}（${OPERATOR_LABELS[value]}）` }));

/** 条件值类型选项。 */
export const CONDITION_VALUE_TYPE_OPTIONS: Array<{
  value: WorkflowConditionValueType;
  label: string;
}> = [
  { value: 'STRING', label: '字符串' },
  { value: 'NUMBER', label: '数字' },
  { value: 'BOOLEAN', label: '布尔值' },
];

/** 条件左值来源选项。 */
export const CONDITION_SOURCE_OPTIONS: Array<{
  value: WorkflowConditionSource;
  label: string;
}> = [
  { value: 'RUN_INPUT', label: '运行输入' },
  { value: 'NODE_OUTPUT', label: '上游节点输出' },
];

/** 受控的上游输出字段；与 ADR-0024 和校验器一致。 */
export const NODE_OUTPUT_PATH_OPTIONS = [
  { value: 'hitCount', label: 'hitCount（检索命中数）' },
  { value: 'classificationLabel', label: 'classificationLabel（分类标签）' },
  { value: 'decision', label: 'decision（审批决定）' },
  { value: 'status', label: 'status（工具调用状态）' },
];

/** 节点配置表单值。 */
export interface NodeFormValues {
  name: string;
  promptTemplate?: string;
  queryTemplate?: string;
  topK?: number;
  modelId?: string;
  outputKind?: WorkflowOutputKind;
  labelsText?: string;
  toolId?: string;
  argsTemplateText?: string;
  assigneeType?: WorkflowApprovalAssigneeType;
  assigneeRole?: string;
  assigneeUserIds?: string[];
  actionKind?: WorkflowApprovalActionKind;
  targetNodeKey?: string;
  conditionLeftSource?: WorkflowConditionSource;
  conditionLeftNodeId?: string;
  conditionLeftPath?: string;
  conditionValueType?: WorkflowConditionValueType;
  conditionOperator?: WorkflowConditionOperator;
  conditionRightValueText?: string;
  conditionRightValuesText?: string;
  resultFrom?: string;
  resultTitle?: string;
}

/** 表单错误；字段名用于 antd `setFields` 定位。 */
export interface NodeFormError {
  field: keyof NodeFormValues;
  message: string;
}

/**
 * 按中英文逗号、顿号或换行拆分文本列表。
 *
 * @param value 原始文本
 * @returns 去空白后的非空项
 */
export function splitList(value: string | null | undefined): string[] {
  if (!value) {
    return [];
  }
  return value
    .split(/[,，、\n]/)
    .map((item) => item.trim())
    .filter(Boolean);
}

/**
 * 把节点配置转换为表单初值。
 *
 * @param node 节点
 * @returns 表单值
 */
export function toNodeFormValues(node: WorkflowGraphNode): NodeFormValues {
  const config = node.config;
  const condition = config.condition;
  return {
    name: node.name,
    promptTemplate: config.promptTemplate ?? undefined,
    queryTemplate: config.queryTemplate ?? undefined,
    topK: config.topK ?? undefined,
    modelId: config.modelId ?? undefined,
    outputKind: config.outputKind,
    labelsText: config.labels?.join('、') ?? undefined,
    toolId: config.toolId ?? undefined,
    argsTemplateText: config.argsTemplate
      ? JSON.stringify(config.argsTemplate, null, 2)
      : undefined,
    assigneeType: config.assigneeType,
    assigneeRole: config.assigneeRole ?? undefined,
    assigneeUserIds: config.assigneeUserIds ?? undefined,
    actionKind: config.actionKind ?? undefined,
    targetNodeKey: config.targetNodeKey ?? undefined,
    conditionLeftSource: condition?.left.source ?? 'RUN_INPUT',
    conditionLeftNodeId: condition?.left.nodeId ?? undefined,
    conditionLeftPath: condition?.left.path ?? undefined,
    conditionValueType: condition?.left.valueType ?? 'STRING',
    conditionOperator: condition?.operator ?? 'EQ',
    conditionRightValueText:
      condition && condition.operator !== 'IN' && condition.right.value != null
        ? String(condition.right.value)
        : undefined,
    conditionRightValuesText:
      condition?.operator === 'IN' ? (condition.right.values ?? []).join('、') : undefined,
    resultFrom: config.resultFrom ?? undefined,
    resultTitle: config.resultTitle ?? undefined,
  };
}

/**
 * 按值类型解析单个条件值。
 *
 * @param text 文本
 * @param valueType 值类型
 * @returns 字符串、数字或布尔；无法解析时返回原字符串，由校验报错
 */
function parseConditionValue(
  text: string | undefined,
  valueType: WorkflowConditionValueType,
): string | number | boolean | undefined {
  if (text === undefined) {
    return undefined;
  }
  const trimmed = text.trim();
  if (valueType === 'NUMBER') {
    const parsed = Number(trimmed);
    return Number.isFinite(parsed) ? parsed : trimmed;
  }
  if (valueType === 'BOOLEAN') {
    if (trimmed.toLowerCase() === 'true') {
      return true;
    }
    if (trimmed.toLowerCase() === 'false') {
      return false;
    }
    return trimmed;
  }
  return text;
}

/**
 * 把表单值转换为节点名称与配置。
 *
 * 调用前应先执行 {@link collectNodeFormErrors}；JSON 参数不合法时这里抛错，
 * 页面会把它显示为表单级错误而不会发送保存请求。
 *
 * @param node 当前节点
 * @param values 表单值
 * @returns 更新后的节点
 */
export function applyNodeFormValues(
  node: WorkflowGraphNode,
  values: NodeFormValues,
): WorkflowGraphNode {
  const name = values.name.trim();
  let config: WorkflowNodeConfig = {};

  switch (node.type) {
    case 'START':
      config = {};
      break;
    case 'END':
      config = { resultFrom: values.resultFrom, resultTitle: values.resultTitle };
      break;
    case 'RETRIEVAL':
      config = { queryTemplate: values.queryTemplate, topK: values.topK };
      break;
    case 'MODEL':
      config = {
        promptTemplate: values.promptTemplate,
        modelId: values.modelId,
        outputKind: values.outputKind,
        labels: values.outputKind === 'CLASSIFICATION' ? splitList(values.labelsText) : undefined,
      };
      break;
    case 'MCP_TOOL': {
      const text = values.argsTemplateText?.trim();
      config = {
        toolId: values.toolId,
        argsTemplate: text ? (JSON.parse(text) as Record<string, unknown>) : undefined,
      };
      break;
    }
    case 'APPROVAL':
      config = {
        assigneeType: values.assigneeType,
        assigneeRole: values.assigneeType === 'ROLE' ? values.assigneeRole : undefined,
        assigneeUserIds:
          values.assigneeType === 'USER' ? (values.assigneeUserIds ?? []) : undefined,
        actionKind: values.actionKind,
        targetNodeKey: values.targetNodeKey,
      };
      break;
    case 'CONDITION': {
      const source = values.conditionLeftSource ?? 'RUN_INPUT';
      const valueType = values.conditionValueType ?? 'STRING';
      const operator = values.conditionOperator ?? 'EQ';
      const right: WorkflowCondition['right'] =
        operator === 'IN'
          ? {
              valueType,
              values: splitList(values.conditionRightValuesText).map(
                (item) => parseConditionValue(item, valueType) as string | number | boolean,
              ),
            }
          : {
              valueType,
              value: parseConditionValue(values.conditionRightValueText, valueType),
            };
      config = {
        condition: {
          left: {
            source,
            nodeId: source === 'NODE_OUTPUT' ? values.conditionLeftNodeId : undefined,
            path: values.conditionLeftPath ?? '',
            valueType,
          },
          operator,
          right,
        },
      };
      break;
    }
    default:
      config = {};
  }

  return {
    ...node,
    name,
    config: normalizeNodeConfig(node.type, config),
  };
}

/**
 * 校验节点配置表单。
 *
 * 与后端 schema/校验器保持同一严格度：必填、UUID、节点键 pattern、数字范围、
 * 条件类型与算子组合等。服务端资源可用性（模型路由链、工具授权、审批人可达）
 * 无法在客户端判定，仍由 `/validate` 补充。
 *
 * @param type 节点类型
 * @param values 表单值
 * @param tools 已加载的授权工具；用于判断写工具是否需要审批前驱
 * @returns 字段级错误列表
 */
export function collectNodeFormErrors(
  type: WorkflowNodeType,
  values: NodeFormValues,
  tools: McpTool[] = [],
): NodeFormError[] {
  const errors: NodeFormError[] = [];
  const name = values.name.trim();
  if (!name) {
    errors.push({ field: 'name', message: '请输入节点名称。' });
  } else if (name.length > 200) {
    errors.push({ field: 'name', message: '节点名称不能超过 200 个字符。' });
  }

  if (type === 'RETRIEVAL') {
    if (!values.queryTemplate?.trim()) {
      errors.push({ field: 'queryTemplate', message: '请填写检索查询模板。' });
    } else if (values.queryTemplate.trim().length > 2000) {
      errors.push({ field: 'queryTemplate', message: '查询模板不能超过 2000 个字符。' });
    }
    if (values.topK === undefined || values.topK === null) {
      errors.push({ field: 'topK', message: '请填写检索命中数。' });
    } else if (!Number.isInteger(values.topK) || values.topK < 1 || values.topK > 20) {
      errors.push({ field: 'topK', message: '检索命中数必须是 1 到 20 的整数。' });
    }
  }

  if (type === 'MODEL') {
    if (!values.promptTemplate?.trim()) {
      errors.push({ field: 'promptTemplate', message: '请填写模型提示词模板。' });
    } else if (values.promptTemplate.length > 8000) {
      errors.push({ field: 'promptTemplate', message: '提示词模板不能超过 8000 个字符。' });
    }
    if (!values.outputKind) {
      errors.push({ field: 'outputKind', message: '请选择模型输出形态。' });
    }
    if (values.modelId && !isUuid(values.modelId)) {
      errors.push({
        field: 'modelId',
        message: '模型 ID 必须是 UUID，或留空使用 REPORT 默认路由。',
      });
    }
    if (values.outputKind === 'CLASSIFICATION') {
      const labels = splitList(values.labelsText);
      if (labels.length === 0) {
        errors.push({ field: 'labelsText', message: '分类输出至少需要一个标签。' });
      } else if (labels.length > 10) {
        errors.push({ field: 'labelsText', message: '分类标签最多 10 个。' });
      } else if (labels.some((label) => label.length > 64)) {
        errors.push({ field: 'labelsText', message: '单个分类标签不能超过 64 个字符。' });
      }
    }
  }

  if (type === 'MCP_TOOL') {
    if (!values.toolId?.trim()) {
      errors.push({ field: 'toolId', message: '请选择已授权的 MCP 工具。' });
    } else if (!isUuid(values.toolId)) {
      errors.push({ field: 'toolId', message: '工具 ID 必须是 UUID。' });
    }
    const text = values.argsTemplateText?.trim();
    if (text) {
      try {
        const parsed: unknown = JSON.parse(text);
        if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
          errors.push({ field: 'argsTemplateText', message: '工具参数模板必须是 JSON 对象。' });
        } else if (Object.keys(parsed).length > 16) {
          errors.push({ field: 'argsTemplateText', message: '工具参数模板最多 16 个顶层键。' });
        }
      } catch {
        errors.push({ field: 'argsTemplateText', message: '工具参数模板不是合法 JSON。' });
      }
    }
    if (
      values.toolId &&
      isUuid(values.toolId) &&
      tools.length > 0 &&
      !tools.some((tool) => tool.id === values.toolId)
    ) {
      errors.push({ field: 'toolId', message: '所选工具不在当前已授权工具目录中。' });
    }
  }

  if (type === 'APPROVAL') {
    if (!values.assigneeType) {
      errors.push({ field: 'assigneeType', message: '请选择审批人类型。' });
    }
    if (values.assigneeType === 'ROLE' && !values.assigneeRole) {
      errors.push({ field: 'assigneeRole', message: '请选择审批角色。' });
    }
    if (values.assigneeType === 'USER') {
      const userIds = (values.assigneeUserIds ?? []).map((id) => id.trim()).filter(Boolean);
      if (userIds.length === 0) {
        errors.push({ field: 'assigneeUserIds', message: '请至少指定一名审批人。' });
      } else if (userIds.some((id) => !isUuid(id))) {
        errors.push({ field: 'assigneeUserIds', message: '审批人 ID 必须是 UUID。' });
      } else if (userIds.length > MAX_ASSIGNEE_USER_IDS) {
        // 契约 assigneeUserIds.maxItems=50；超过时后端会直接拒绝保存。
        errors.push({
          field: 'assigneeUserIds',
          message: `审批人最多 ${MAX_ASSIGNEE_USER_IDS} 人。`,
        });
      }
    }
    if (!values.actionKind) {
      errors.push({ field: 'actionKind', message: '请选择审批解锁的动作类型。' });
    }
    if (values.targetNodeKey && !/^[A-Za-z0-9_-]{1,64}$/.test(values.targetNodeKey.trim())) {
      errors.push({
        field: 'targetNodeKey',
        message: '目标节点键只能包含字母、数字、下划线和连字符，长度 1–64。',
      });
    }
    if (values.actionKind === 'MCP_TOOL' && !values.targetNodeKey?.trim()) {
      errors.push({ field: 'targetNodeKey', message: 'MCP 工具审批必须指定目标节点键。' });
    }
  }

  if (type === 'CONDITION') {
    if (!values.conditionLeftSource) {
      errors.push({ field: 'conditionLeftSource', message: '请选择条件数据来源。' });
    }
    if (values.conditionLeftSource === 'NODE_OUTPUT') {
      if (!values.conditionLeftNodeId?.trim()) {
        errors.push({ field: 'conditionLeftNodeId', message: '请选择上游节点。' });
      } else if (!/^[A-Za-z0-9_-]{1,64}$/.test(values.conditionLeftNodeId.trim())) {
        errors.push({ field: 'conditionLeftNodeId', message: '上游节点键格式不合法。' });
      }
    }
    if (!values.conditionLeftPath?.trim()) {
      errors.push({ field: 'conditionLeftPath', message: '请填写条件字段。' });
    }
    if (!values.conditionValueType) {
      errors.push({ field: 'conditionValueType', message: '请选择条件值类型。' });
    }
    if (!values.conditionOperator) {
      errors.push({ field: 'conditionOperator', message: '请选择条件算子。' });
    }
    const numericOperator = ['GT', 'GTE', 'LT', 'LTE'].includes(values.conditionOperator ?? '');
    if (numericOperator && values.conditionValueType !== 'NUMBER') {
      errors.push({
        field: 'conditionOperator',
        message: '大于/小于类算子要求值类型为数字。',
      });
    }
    if (values.conditionOperator === 'IN') {
      const items = splitList(values.conditionRightValuesText);
      if (items.length === 0) {
        errors.push({ field: 'conditionRightValuesText', message: 'IN 算子至少需要一个值。' });
      } else if (items.length > MAX_CONDITION_IN_VALUES) {
        // 契约 values.maxItems=20；超过时后端拒绝保存，客户端必须先拦截。
        errors.push({
          field: 'conditionRightValuesText',
          message: `IN 比较值集合最多 ${MAX_CONDITION_IN_VALUES} 个。`,
        });
      } else if (
        values.conditionValueType === 'NUMBER' &&
        items.some((item) => !Number.isFinite(Number(item)))
      ) {
        // 后端 WorkflowValidator 会逐个校验 values 与 valueType 一致（CONDITION_BRANCH_INVALID）。
        errors.push({ field: 'conditionRightValuesText', message: 'IN 比较值必须都是数字。' });
      } else if (
        values.conditionValueType === 'BOOLEAN' &&
        items.some((item) => !['true', 'false'].includes(item.toLowerCase()))
      ) {
        errors.push({
          field: 'conditionRightValuesText',
          message: 'IN 比较值必须都是 true 或 false。',
        });
      }
    } else {
      const text = values.conditionRightValueText;
      if (text === undefined || text.trim() === '') {
        errors.push({ field: 'conditionRightValueText', message: '请填写比较值。' });
      } else if (values.conditionValueType === 'NUMBER' && !Number.isFinite(Number(text))) {
        errors.push({ field: 'conditionRightValueText', message: '比较值必须是数字。' });
      } else if (
        values.conditionValueType === 'BOOLEAN' &&
        !['true', 'false'].includes(text.trim().toLowerCase())
      ) {
        errors.push({ field: 'conditionRightValueText', message: '比较值必须是 true 或 false。' });
      }
    }
  }

  if (type === 'END') {
    if (!values.resultFrom?.trim()) {
      errors.push({ field: 'resultFrom', message: '请选择结果来源节点。' });
    }
    if (values.resultTitle && values.resultTitle.length > 200) {
      errors.push({ field: 'resultTitle', message: '结果标题不能超过 200 个字符。' });
    }
  }

  return errors;
}
