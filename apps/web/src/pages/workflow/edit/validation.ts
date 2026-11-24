import type { McpTool } from '@/services/api/types';
import { ancestorIds, isUuid, normalizeGraphForSave, successorIds } from './graph';
import type {
  WorkflowGraph,
  WorkflowGraphNode,
  WorkflowNodeConfig,
  WorkflowValidationCode,
  WorkflowValidationIssue,
} from '../list/workflow-api';

/**
 * 编辑器客户端校验与问题文案。
 *
 * 与后端 `WorkflowValidator` 保持同一结构口径（开始/结束、入出度、无环、可达、必填配置、
 * 条件分支、审批动作绑定）；模型路由链、工具授权与审批人数量等目录信息无法在客户端
 * 完整判定，保存后由 `POST /validate` 补充。两层结果共用同一展示组件与定位入口。
 *
 * 为什么需要客户端一层：保存草稿接口在非法图时返回 ProblemDetail（最多 20 条英文摘要），
 * 而不是结构化 issues；先本地拦截可以让用户在表单内看到中文问题，并避免无意义的请求。
 */

/** 客户端补充的问题码；与后端稳定枚举并列使用。 */
export type EditorIssueCode =
  WorkflowValidationCode | 'NODE_NAME_REQUIRED' | 'NODE_ID_INVALID' | 'EDGE_FORMAT_INVALID';

/** 面板展示的问题。 */
export interface EditorIssue {
  code: EditorIssueCode;
  nodeId?: string | null;
  field?: string | null;
  /** 中文主文案。 */
  message: string;
  /** 服务端原始说明，仅作诊断提示，不替换中文主文案。 */
  serverMessage?: string;
}

/** 问题码中文名。 */
const ISSUE_CODE_LABELS: Record<EditorIssueCode, string> = {
  NODE_NAME_REQUIRED: '节点名称',
  NODE_ID_INVALID: '节点键',
  EDGE_FORMAT_INVALID: '连线',
  GRAPH_CYCLE: '流程存在环',
  MISSING_START: '缺少开始节点',
  MULTIPLE_START: '多个开始节点',
  MISSING_END: '缺少结束节点',
  ORPHAN_NODE: '节点不可达',
  INDEGREE_EXCEEDED: '入边过多',
  EDGE_INVALID: '连线不合法',
  CONDITION_BRANCH_INVALID: '条件分支不合法',
  REQUIRED_CONFIG: '缺少必填配置',
  NODE_LIMIT: '节点数超限',
  EDGE_LIMIT: '连线数超限',
  MODEL_UNAVAILABLE: '模型不可用',
  TOOL_NOT_AUTHORIZED: '工具未授权',
  TOOL_DISABLED: '工具已停用',
  APPROVAL_CONFIG: '审批配置不合法',
  NO_APPROVER: '没有可用审批人',
  RESULT_INVALID: '结果来源不合法',
  TEMPLATE_INVALID: '模板不合法',
};

/** 服务端问题码对应的中文说明。 */
const ISSUE_MESSAGES: Record<WorkflowValidationCode, string> = {
  GRAPH_CYCLE: '流程中存在环，请调整连线。',
  MISSING_START: '流程必须恰好包含一个开始节点。',
  MULTIPLE_START: '流程只能有一个开始节点。',
  MISSING_END: '流程至少需要一个结束节点。',
  ORPHAN_NODE: '节点无法从开始节点到达，请补充连线。',
  INDEGREE_EXCEEDED: '节点有多条入边，请只保留一条。',
  EDGE_INVALID: '连线不合法，请检查源节点、目标节点与方向。',
  CONDITION_BRANCH_INVALID: '条件分支必须恰好有一条 TRUE 和一条 FALSE 连线。',
  REQUIRED_CONFIG: '节点缺少必填配置，请补充。',
  NODE_LIMIT: '流程节点数超过 50 个上限。',
  EDGE_LIMIT: '流程连线数超过 100 条上限。',
  MODEL_UNAVAILABLE: '所选模型不可用或不在 REPORT 路由链中，请重新选择。',
  TOOL_NOT_AUTHORIZED: '所选 MCP 工具未授权或不在当前目录中。',
  TOOL_DISABLED: '所选 MCP 工具已停用，请选择其他工具。',
  APPROVAL_CONFIG: '审批人、动作类型或目标节点配置不合法。',
  NO_APPROVER: '审批节点没有可用的非发起人审批人，请调整审批人。',
  RESULT_INVALID: '结果来源必须是已执行的上游节点。',
  TEMPLATE_INVALID: '模板不合法，请重新创建流程。',
};

/** 常见配置字段的中文名。 */
const FIELD_LABELS: Record<string, string> = {
  name: '节点名称',
  'config.promptTemplate': '提示词模板',
  'config.queryTemplate': '检索查询',
  'config.topK': '检索命中数',
  'config.modelId': '模型',
  'config.outputKind': '输出形态',
  'config.labels': '分类标签',
  'config.toolId': 'MCP 工具',
  'config.assigneeType': '审批人类型',
  'config.assigneeRole': '审批角色',
  'config.assigneeUserIds': '审批人',
  'config.actionKind': '审批动作',
  'config.targetNodeKey': '目标节点键',
  'config.condition': '条件配置',
  'config.condition.operator': '条件算子',
  'config.condition.right.value': '比较值',
  'config.condition.right.values': '比较值集合',
  'config.condition.left.nodeId': '上游节点',
  'config.condition.left.path': '条件字段',
  'config.resultFrom': '结果来源',
  nodes: '节点列表',
  edges: '连线列表',
};

/** 节点 ID/键 pattern，与契约一致。 */
const NODE_KEY_PATTERN = /^[A-Za-z0-9_-]{1,64}$/;

/**
 * 返回问题码中文标签。
 *
 * @param code 问题码
 * @returns 中文标签
 */
export function issueCodeLabel(code: EditorIssueCode): string {
  return ISSUE_CODE_LABELS[code];
}

/**
 * 把节点 ID 映射为可读名称。
 *
 * @param graph 图
 * @param nodeId 节点 ID
 * @returns 节点名称或原 ID
 */
export function nodeLabel(graph: WorkflowGraph, nodeId: string | null | undefined): string {
  if (!nodeId) {
    return '流程';
  }
  const node = graph.nodes.find((current) => current.id === nodeId);
  return node ? `${node.name}（${node.id}）` : nodeId;
}

/**
 * 将服务端问题转换为中文展示项。
 *
 * @param issue 服务端问题
 * @returns 客户端展示项
 */
export function fromServerIssue(issue: WorkflowValidationIssue): EditorIssue {
  const base = ISSUE_MESSAGES[issue.code] ?? '流程校验未通过，请检查配置。';
  const field = issue.field ? FIELD_LABELS[issue.field] : undefined;
  return {
    code: issue.code,
    nodeId: issue.nodeId,
    field: issue.field,
    message: field ? `${base}（${field}）` : base,
    serverMessage: issue.message,
  };
}

/**
 * 校验草稿图。
 *
 * @param graph 原始图；内部会先按保存口径规范化
 * @param tools 已加载的授权工具目录；为空时跳过工具可用性判断
 * @returns 客户端问题列表；空数组表示结构可保存
 */
export function validateGraph(graph: WorkflowGraph, tools: McpTool[] = []): EditorIssue[] {
  const normalized = normalizeGraphForSave(graph);
  const issues: EditorIssue[] = [];
  const nodes = new Map<string, WorkflowGraphNode>();
  const duplicates = new Set<string>();

  for (const node of normalized.nodes) {
    if (nodes.has(node.id)) {
      duplicates.add(node.id);
    }
    nodes.set(node.id, node);
  }

  const push = (
    code: EditorIssueCode,
    nodeId: string | null,
    field: string | null,
    message: string,
  ): void => {
    issues.push({ code, nodeId, field, message });
  };

  if (normalized.nodes.length > 50) {
    push('NODE_LIMIT', null, 'nodes', ISSUE_MESSAGES.NODE_LIMIT);
  }
  if (normalized.edges.length > 100) {
    push('EDGE_LIMIT', null, 'edges', ISSUE_MESSAGES.EDGE_LIMIT);
  }

  for (const node of normalized.nodes) {
    if (!NODE_KEY_PATTERN.test(node.id)) {
      push(
        'NODE_ID_INVALID',
        node.id,
        'id',
        '节点键只能包含字母、数字、下划线和连字符，长度 1–64。',
      );
    }
    if (!node.name.trim()) {
      push('NODE_NAME_REQUIRED', node.id, 'name', '请填写节点名称。');
    }
  }
  for (const id of duplicates) {
    push('NODE_ID_INVALID', id, 'id', `节点键 ${id} 重复，请重新生成。`);
  }

  const starts = normalized.nodes.filter((node) => node.type === 'START');
  const ends = normalized.nodes.filter((node) => node.type === 'END');
  if (starts.length === 0) {
    push('MISSING_START', null, 'nodes', ISSUE_MESSAGES.MISSING_START);
  } else if (starts.length > 1) {
    starts.slice(1).forEach((node) => {
      push('MULTIPLE_START', node.id, 'type', ISSUE_MESSAGES.MULTIPLE_START);
    });
  }
  if (ends.length === 0) {
    push('MISSING_END', null, 'nodes', ISSUE_MESSAGES.MISSING_END);
  }

  const outgoing = new Map<string, typeof normalized.edges>();
  const incoming = new Map<string, typeof normalized.edges>();
  nodes.forEach((_, id) => {
    outgoing.set(id, []);
    incoming.set(id, []);
  });

  normalized.edges.forEach((edge, index) => {
    const source = nodes.get(edge.source);
    const target = nodes.get(edge.target);
    const field = `edges[${index}]`;
    if (!source || !target) {
      push('EDGE_FORMAT_INVALID', null, field, '连线的源节点或目标节点不存在，请重新选择。');
      return;
    }
    outgoing.get(source.id)?.push(edge);
    incoming.get(target.id)?.push(edge);
    if (source.type === 'END') {
      push('EDGE_INVALID', source.id, field, '结束节点不能有出边。');
    }
    if (target.type === 'START') {
      push('EDGE_INVALID', target.id, field, '开始节点不能有入边。');
    }
    if (source.type === 'CONDITION') {
      if (!edge.branch) {
        push(
          'CONDITION_BRANCH_INVALID',
          source.id,
          `${field}.branch`,
          '条件节点的每条出边都必须选择 TRUE 或 FALSE。',
        );
      }
    }
  });

  for (const node of normalized.nodes) {
    const outs = outgoing.get(node.id) ?? [];
    const ins = incoming.get(node.id) ?? [];
    if (ins.length > 1) {
      push('INDEGREE_EXCEEDED', node.id, 'edges', '节点有多条入边，请只保留一条。');
    }
    if (node.type === 'CONDITION') {
      if (outs.length !== 2) {
        push(
          'CONDITION_BRANCH_INVALID',
          node.id,
          'edges',
          '条件节点必须恰好有两条出边（TRUE 与 FALSE）。',
        );
      } else {
        const trueCount = outs.filter((edge) => edge.branch === 'TRUE').length;
        const falseCount = outs.filter((edge) => edge.branch === 'FALSE').length;
        if (trueCount !== 1 || falseCount !== 1) {
          push(
            'CONDITION_BRANCH_INVALID',
            node.id,
            'edges',
            '条件节点必须恰好有一条 TRUE 和一条 FALSE 出边。',
          );
        }
      }
    } else if (node.type !== 'END' && outs.length !== 1) {
      push('EDGE_INVALID', node.id, 'edges', '除结束节点外，每个节点必须恰好有一条出边。');
    }
  }

  // Kahn 拓扑排序检测环；从 URL/服务端读到的图也可能带环，不能假设 UI 永不产生。
  const indegree = new Map<string, number>();
  nodes.forEach((_, id) => indegree.set(id, (incoming.get(id) ?? []).length));
  const queue = [...nodes.keys()].filter((id) => (indegree.get(id) ?? 0) === 0);
  let processed = 0;
  while (queue.length > 0) {
    const current = queue.shift() as string;
    processed += 1;
    for (const edge of outgoing.get(current) ?? []) {
      if (!indegree.has(edge.target)) {
        continue;
      }
      const remaining = (indegree.get(edge.target) ?? 0) - 1;
      indegree.set(edge.target, remaining);
      if (remaining === 0) {
        queue.push(edge.target);
      }
    }
  }
  if (processed < nodes.size) {
    const cycleNode = [...indegree.entries()].find(([, degree]) => degree > 0)?.[0] ?? null;
    push('GRAPH_CYCLE', cycleNode, 'edges', ISSUE_MESSAGES.GRAPH_CYCLE);
  }

  if (starts.length === 1) {
    const reached = new Set<string>([starts[0].id]);
    const bfs = [starts[0].id];
    while (bfs.length > 0) {
      const current = bfs.shift() as string;
      for (const edge of outgoing.get(current) ?? []) {
        if (!reached.has(edge.target) && nodes.has(edge.target)) {
          reached.add(edge.target);
          bfs.push(edge.target);
        }
      }
    }
    for (const node of normalized.nodes) {
      if (!reached.has(node.id)) {
        push('ORPHAN_NODE', node.id, 'edges', ISSUE_MESSAGES.ORPHAN_NODE);
      }
    }
  }

  const toolMap = new Map(tools.map((tool) => [tool.id, tool]));
  for (const node of normalized.nodes) {
    const config = node.config;
    if (config.targetNodeKey && !NODE_KEY_PATTERN.test(config.targetNodeKey)) {
      push(
        node.type === 'APPROVAL' ? 'APPROVAL_CONFIG' : 'REQUIRED_CONFIG',
        node.id,
        'config.targetNodeKey',
        '目标节点键只能包含字母、数字、下划线和连字符，长度 1–64。',
      );
    }
    validateNodeConfig(node, config, nodes, outgoing, incoming, toolMap, push);
  }

  return issues;
}

/**
 * 按节点类型校验必填配置与引用关系。
 *
 * @param node 节点
 * @param config 清洗后的配置
 * @param nodes 节点索引
 * @param outgoing 出边邻接表
 * @param incoming 入边邻接表
 * @param tools 工具目录
 * @param push 问题收集函数
 */
function validateNodeConfig(
  node: WorkflowGraphNode,
  config: WorkflowNodeConfig,
  nodes: Map<string, WorkflowGraphNode>,
  outgoing: Map<string, WorkflowGraph['edges']>,
  incoming: Map<string, WorkflowGraph['edges']>,
  tools: Map<string, McpTool>,
  push: (
    code: EditorIssueCode,
    nodeId: string | null,
    field: string | null,
    message: string,
  ) => void,
): void {
  switch (node.type) {
    case 'START':
      return;
    case 'END': {
      const resultFrom = config.resultFrom;
      if (!resultFrom) {
        push('REQUIRED_CONFIG', node.id, 'config.resultFrom', '结束节点必须选择结果来源。');
        return;
      }
      const source = nodes.get(resultFrom);
      if (
        !source ||
        source.type === 'START' ||
        !ancestorIds(normalizeGraphFromMaps(nodes, outgoing), node.id).has(resultFrom)
      ) {
        push('RESULT_INVALID', node.id, 'config.resultFrom', ISSUE_MESSAGES.RESULT_INVALID);
      }
      return;
    }
    case 'RETRIEVAL':
      if (!config.queryTemplate) {
        push('REQUIRED_CONFIG', node.id, 'config.queryTemplate', '检索节点必须填写查询模板。');
      }
      if (config.topK == null) {
        push('REQUIRED_CONFIG', node.id, 'config.topK', '检索节点必须填写命中数。');
      } else if (!Number.isInteger(config.topK) || config.topK < 1 || config.topK > 20) {
        push('REQUIRED_CONFIG', node.id, 'config.topK', '检索命中数必须是 1 到 20 的整数。');
      }
      return;
    case 'MODEL':
      if (!config.promptTemplate) {
        push('REQUIRED_CONFIG', node.id, 'config.promptTemplate', '模型节点必须填写提示词模板。');
      }
      if (!config.outputKind) {
        push('REQUIRED_CONFIG', node.id, 'config.outputKind', '模型节点必须选择输出形态。');
      } else if (config.outputKind === 'CLASSIFICATION' && (config.labels?.length ?? 0) === 0) {
        push('REQUIRED_CONFIG', node.id, 'config.labels', '分类输出至少需要一个标签。');
      }
      if (config.modelId && !isUuid(config.modelId)) {
        push('MODEL_UNAVAILABLE', node.id, 'config.modelId', '模型 ID 必须是 UUID。');
      }
      return;
    case 'MCP_TOOL': {
      if (!config.toolId) {
        push('REQUIRED_CONFIG', node.id, 'config.toolId', 'MCP 工具节点必须选择已授权工具。');
        return;
      }
      if (!isUuid(config.toolId)) {
        push('TOOL_NOT_AUTHORIZED', node.id, 'config.toolId', '工具 ID 必须是 UUID。');
        return;
      }
      const tool = tools.get(config.toolId);
      if (tool) {
        if (tool.status === 'DISABLED') {
          push('TOOL_DISABLED', node.id, 'config.toolId', ISSUE_MESSAGES.TOOL_DISABLED);
          return;
        }
        if (tool.requiresApproval) {
          const ins = incoming.get(node.id) ?? [];
          const predecessor = ins.length === 1 ? nodes.get(ins[0].source) : undefined;
          if (
            !predecessor ||
            predecessor.type !== 'APPROVAL' ||
            predecessor.config.actionKind !== 'MCP_TOOL' ||
            predecessor.config.targetNodeKey !== node.id
          ) {
            push(
              'APPROVAL_CONFIG',
              node.id,
              'config.toolId',
              '写工具必须紧跟一个审批节点，且审批动作设为 MCP_TOOL、目标指向本节点。',
            );
          }
        }
      }
      return;
    }
    case 'APPROVAL': {
      if (!config.assigneeType) {
        push('APPROVAL_CONFIG', node.id, 'config.assigneeType', '审批节点必须选择审批人类型。');
      }
      if (!config.actionKind) {
        push('APPROVAL_CONFIG', node.id, 'config.actionKind', '审批节点必须选择解锁动作。');
      }
      if (config.assigneeType === 'ROLE') {
        if (!config.assigneeRole) {
          push('APPROVAL_CONFIG', node.id, 'config.assigneeRole', '按角色审批必须选择审批角色。');
        }
        if (config.assigneeUserIds && config.assigneeUserIds.length > 0) {
          push(
            'APPROVAL_CONFIG',
            node.id,
            'config.assigneeUserIds',
            '按角色审批不能同时指定具体审批人。',
          );
        }
      } else if (config.assigneeType === 'USER') {
        if (!config.assigneeUserIds || config.assigneeUserIds.length === 0) {
          push(
            'APPROVAL_CONFIG',
            node.id,
            'config.assigneeUserIds',
            '按人审批至少需要指定一名审批人。',
          );
        }
        if (config.assigneeRole) {
          push('APPROVAL_CONFIG', node.id, 'config.assigneeRole', '按人审批不能同时设置审批角色。');
        }
      }
      const successors = successorIds(normalizeGraphFromMaps(nodes, outgoing), node.id);
      const successor = successors.length === 1 ? successors[0] : undefined;
      const successorType = successor ? nodes.get(successor)?.type : undefined;
      const target = config.targetNodeKey ?? undefined;
      if (config.actionKind === 'MCP_TOOL') {
        if (!target) {
          push(
            'APPROVAL_CONFIG',
            node.id,
            'config.targetNodeKey',
            'MCP 工具审批必须指定目标节点键。',
          );
        } else if (target !== successor) {
          push(
            'APPROVAL_CONFIG',
            node.id,
            'config.targetNodeKey',
            '目标节点键必须指向直接后继节点。',
          );
        } else if (successorType !== 'MCP_TOOL') {
          push('APPROVAL_CONFIG', node.id, 'config.targetNodeKey', '目标节点必须是 MCP 工具节点。');
        }
      } else if (config.actionKind === 'MODEL') {
        if (target && target !== successor) {
          push(
            'APPROVAL_CONFIG',
            node.id,
            'config.targetNodeKey',
            '目标节点键必须指向直接后继节点。',
          );
        } else if (!target && successorType && successorType !== 'MODEL') {
          push('APPROVAL_CONFIG', node.id, 'config.actionKind', '模型审批必须直接后继模型节点。');
        }
      } else if (config.actionKind === 'CONTINUE' && target && target !== successor) {
        push(
          'APPROVAL_CONFIG',
          node.id,
          'config.targetNodeKey',
          '目标节点键必须指向直接后继节点。',
        );
      }
      return;
    }
    case 'CONDITION': {
      const condition = config.condition;
      if (!condition) {
        push('REQUIRED_CONFIG', node.id, 'config.condition', '条件节点必须配置条件。');
        return;
      }
      if (condition.left.valueType !== condition.right.valueType) {
        push('CONDITION_BRANCH_INVALID', node.id, 'config.condition', '条件左右值类型必须一致。');
      }
      const numericOperator = ['GT', 'GTE', 'LT', 'LTE'].includes(condition.operator);
      if (numericOperator && condition.left.valueType !== 'NUMBER') {
        push(
          'CONDITION_BRANCH_INVALID',
          node.id,
          'config.condition.operator',
          '大于/小于类算子要求值类型为数字。',
        );
      }
      if (condition.operator === 'IN') {
        if (!condition.right.values || condition.right.values.length === 0) {
          push(
            'CONDITION_BRANCH_INVALID',
            node.id,
            'config.condition.right.values',
            'IN 算子至少需要一个值。',
          );
        }
        if (condition.right.value != null) {
          push(
            'CONDITION_BRANCH_INVALID',
            node.id,
            'config.condition.right.value',
            'IN 算子不能同时设置单值。',
          );
        }
      } else {
        if (condition.right.value == null) {
          push(
            'CONDITION_BRANCH_INVALID',
            node.id,
            'config.condition.right.value',
            '条件算子需要一个比较值。',
          );
        }
        if (condition.right.values && condition.right.values.length > 0) {
          push(
            'CONDITION_BRANCH_INVALID',
            node.id,
            'config.condition.right.values',
            '只有 IN 算子可以使用值集合。',
          );
        }
      }
      if (condition.left.source === 'NODE_OUTPUT') {
        const upstream = condition.left.nodeId;
        if (!upstream) {
          push(
            'CONDITION_BRANCH_INVALID',
            node.id,
            'config.condition.left.nodeId',
            '节点输出条件必须选择上游节点。',
          );
        } else {
          const source = nodes.get(upstream);
          if (!source) {
            push(
              'CONDITION_BRANCH_INVALID',
              node.id,
              'config.condition.left.nodeId',
              '上游节点不存在。',
            );
          } else if (!ancestorIds(normalizeGraphFromMaps(nodes, outgoing), node.id).has(upstream)) {
            push(
              'CONDITION_BRANCH_INVALID',
              node.id,
              'config.condition.left.nodeId',
              '条件引用的节点必须是上游节点。',
            );
          } else if (!pathAllowed(condition.left.path, source)) {
            push(
              'CONDITION_BRANCH_INVALID',
              node.id,
              'config.condition.left.path',
              '该字段不允许从所选上游节点读取。',
            );
          }
        }
      } else if (condition.left.nodeId) {
        push(
          'CONDITION_BRANCH_INVALID',
          node.id,
          'config.condition.left.nodeId',
          '运行输入条件不能指定上游节点。',
        );
      }
      return;
    }
    default:
      return;
  }
}

/**
 * 从节点索引与出边表还原最小图，供 `ancestorIds` 复用。
 *
 * @param nodes 节点索引
 * @param outgoing 出边表
 * @returns 只含 id/edges 的图
 */
function normalizeGraphFromMaps(
  nodes: Map<string, WorkflowGraphNode>,
  outgoing: Map<string, WorkflowGraph['edges']>,
): WorkflowGraph {
  return {
    schemaVersion: 1,
    nodes: [...nodes.values()],
    edges: [...outgoing.values()].flat(),
  };
}

/**
 * 判断受控输出字段是否匹配来源节点类型。
 *
 * @param path 输出字段
 * @param node 来源节点
 * @returns 是否允许
 */
function pathAllowed(path: string, node: WorkflowGraphNode): boolean {
  switch (path) {
    case 'hitCount':
      return node.type === 'RETRIEVAL';
    case 'classificationLabel':
      return node.type === 'MODEL' && node.config.outputKind === 'CLASSIFICATION';
    case 'decision':
      return node.type === 'APPROVAL';
    case 'status':
      return node.type === 'MCP_TOOL';
    default:
      return false;
  }
}
