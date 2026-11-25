import { NODE_TYPE_LABELS } from '../list/format';
import type {
  WorkflowEdgeBranch,
  WorkflowGraph,
  WorkflowGraphEdge,
  WorkflowGraphNode,
  WorkflowGraphPosition,
  WorkflowNodeConfig,
  WorkflowNodeType,
} from '../list/workflow-api';

/**
 * 表单优先的图状态操作。
 *
 * 画布（T11）与列表/表单（T10）读写同一 `WorkflowGraph`；本文件只提供纯函数，
 * 不依赖 React 或服务端，便于穷举测试结构变更与保存前规范化。
 *
 * 保存前规范化是 REV-TASK-0220-001 F-003 与 REV-TASK-0231-001 F-002 的关闭点：
 * - `position` 可选；只有 x/y 均为有限数字时才保留，缺失/非有限坐标整体省略，交给画布网格回退；
 * - 可选文本字段去空白，空字符串直接省略，避免服务端 `minLength=1` 拒绝；
 * - 条件分支 `branch` 只保留在 CONDITION 出边且值必须是 TRUE/FALSE；
 * - 节点配置按类型只保留相关字段，防止切换类型后残留旧字段污染校验。
 */

/** 节点 ID 前缀；生成可读且满足 `^[A-Za-z0-9_-]{1,64}$` 的键。 */
const NODE_ID_PREFIX: Record<WorkflowNodeType, string> = {
  START: 'start',
  END: 'end',
  RETRIEVAL: 'retrieval',
  MODEL: 'model',
  MCP_TOOL: 'tool',
  APPROVAL: 'approval',
  CONDITION: 'condition',
};

/** 节点类型默认名称。 */
const NODE_DEFAULT_NAME: Record<WorkflowNodeType, string> = {
  START: '开始',
  END: '结束',
  RETRIEVAL: '知识库检索',
  MODEL: '模型生成',
  MCP_TOOL: 'MCP 工具',
  APPROVAL: '人工审批',
  CONDITION: '条件分支',
};

/** 空草稿图；合法草稿也必须由用户补齐开始/结束节点。 */
export function emptyGraph(): WorkflowGraph {
  return { schemaVersion: 1, nodes: [], edges: [] };
}

/**
 * 判断字符串是否为 UUID。
 *
 * @param value 待检查文本
 * @returns 是否为标准 8-4-4-4-12 UUID
 */
export function isUuid(value: string | null | undefined): boolean {
  if (!value) {
    return false;
  }
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
}

/**
 * 去除首尾空白；空白结果返回 undefined。
 *
 * 服务端对可选文本使用 `minLength=1`，因此客户端不能把空字符串发出去。
 *
 * @param value 原始文本
 * @returns 去空白文本或 undefined
 */
function cleanText(value: string | null | undefined): string | undefined {
  const trimmed = value?.trim();
  return trimmed ? trimmed : undefined;
}

/**
 * 按类型生成下一个节点 ID。
 *
 * @param type 节点类型
 * @param graph 当前图
 * @returns 未占用的节点 ID
 */
function nextNodeId(type: WorkflowNodeType, graph: WorkflowGraph): string {
  const prefix = NODE_ID_PREFIX[type];
  let index = 1;
  const used = new Set(graph.nodes.map((node) => node.id));
  while (used.has(`${prefix}-${index}`)) {
    index += 1;
  }
  return `${prefix}-${index}`;
}

/**
 * 按已有边生成下一个边 ID。
 *
 * @param graph 当前图
 * @returns 未占用的边 ID
 */
function nextEdgeId(graph: WorkflowGraph): string {
  let index = 1;
  const used = new Set(graph.edges.map((edge) => edge.id));
  while (used.has(`e${index}`)) {
    index += 1;
  }
  return `e${index}`;
}

/**
 * 为新节点计算不会重叠的初始坐标。
 *
 * @param graph 当前图
 * @returns x/y 均为有限数字的坐标
 */
function defaultPosition(graph: WorkflowGraph): WorkflowGraphPosition {
  const index = graph.nodes.length;
  return { x: 80 + (index % 4) * 220, y: 80 + Math.floor(index / 4) * 140 };
}

/**
 * 创建节点（不修改原图）。
 *
 * @param type 节点类型
 * @param graph 当前图
 * @returns 新节点
 */
export function createNode(type: WorkflowNodeType, graph: WorkflowGraph): WorkflowGraphNode {
  return {
    id: nextNodeId(type, graph),
    type,
    name: NODE_DEFAULT_NAME[type],
    config: {},
    position: defaultPosition(graph),
  };
}

/**
 * 追加节点。
 *
 * @param graph 当前图
 * @param node 新节点
 * @returns 新图
 */
export function appendNode(graph: WorkflowGraph, node: WorkflowGraphNode): WorkflowGraph {
  return { ...graph, nodes: [...graph.nodes, node] };
}

/**
 * 替换节点配置。
 *
 * @param graph 当前图
 * @param node 更新后的节点
 * @returns 新图
 */
export function replaceNode(graph: WorkflowGraph, node: WorkflowGraphNode): WorkflowGraph {
  return {
    ...graph,
    nodes: graph.nodes.map((current) => (current.id === node.id ? node : current)),
  };
}

/**
 * 删除节点并清理所有引用。
 *
 * 除了删除关联边，还清空 END.resultFrom、APPROVAL.targetNodeKey 与
 * CONDITION.left.nodeId 中指向该节点的值，避免用户删除后被校验面板阻断、
 * 或画布/表单显示指向不存在节点的悬空配置。
 *
 * @param graph 当前图
 * @param nodeId 要删除的节点 ID
 * @returns 新图
 */
export function removeNode(graph: WorkflowGraph, nodeId: string): WorkflowGraph {
  return {
    ...graph,
    nodes: graph.nodes
      .filter((node) => node.id !== nodeId)
      .map((node) => {
        const config = node.config;
        const condition = config.condition;
        const conditionReferencesNode =
          condition?.left?.source === 'NODE_OUTPUT' && condition.left.nodeId === nodeId;
        if (
          config.resultFrom !== nodeId &&
          config.targetNodeKey !== nodeId &&
          !conditionReferencesNode
        ) {
          return node;
        }
        return {
          ...node,
          config: {
            ...config,
            ...(config.resultFrom === nodeId ? { resultFrom: undefined } : {}),
            ...(config.targetNodeKey === nodeId ? { targetNodeKey: undefined } : {}),
            ...(conditionReferencesNode && condition
              ? { condition: { ...condition, left: { ...condition.left, nodeId: undefined } } }
              : {}),
          },
        };
      }),
    edges: graph.edges.filter((edge) => edge.source !== nodeId && edge.target !== nodeId),
  };
}

/**
 * 上移或下移节点（只影响编辑列表顺序，不改变连线语义）。
 *
 * @param graph 当前图
 * @param nodeId 节点 ID
 * @param offset -1 上移，1 下移
 * @returns 新图
 */
export function moveNode(graph: WorkflowGraph, nodeId: string, offset: -1 | 1): WorkflowGraph {
  const index = graph.nodes.findIndex((node) => node.id === nodeId);
  const target = index + offset;
  if (index < 0 || target < 0 || target >= graph.nodes.length) {
    return graph;
  }
  const nodes = [...graph.nodes];
  const [current] = nodes.splice(index, 1);
  nodes.splice(target, 0, current);
  return { ...graph, nodes };
}

/**
 * 创建边（不修改原图）。
 *
 * @param graph 当前图
 * @param source 源节点 ID
 * @param target 目标节点 ID
 * @param branch 条件分支；非条件源必须为空
 * @returns 新边
 */
export function createEdge(
  graph: WorkflowGraph,
  source: string,
  target: string,
  branch?: WorkflowEdgeBranch | null,
): WorkflowGraphEdge {
  return {
    id: nextEdgeId(graph),
    source,
    target,
    ...(branch ? { branch } : {}),
  };
}

/**
 * 追加边。
 *
 * @param graph 当前图
 * @param edge 新边
 * @returns 新图
 */
export function appendEdge(graph: WorkflowGraph, edge: WorkflowGraphEdge): WorkflowGraph {
  return { ...graph, edges: [...graph.edges, edge] };
}

/**
 * 删除边。
 *
 * @param graph 当前图
 * @param edgeId 边 ID
 * @returns 新图
 */
export function removeEdge(graph: WorkflowGraph, edgeId: string): WorkflowGraph {
  return { ...graph, edges: graph.edges.filter((edge) => edge.id !== edgeId) };
}

/**
 * 返回节点的直接后继 ID 列表。
 *
 * @param graph 当前图
 * @param nodeId 节点 ID
 * @returns 后继 ID
 */
export function successorIds(graph: WorkflowGraph, nodeId: string): string[] {
  return graph.edges.filter((edge) => edge.source === nodeId).map((edge) => edge.target);
}

/**
 * 返回节点的严格上游节点 ID 集合。
 *
 * 用于校验 END.resultFrom 与 CONDITION.left.nodeId 必须来自上游；
 * 无环图才会终止；存在环时由校验面板单独报告 GRAPH_CYCLE。
 *
 * @param graph 当前图
 * @param nodeId 节点 ID
 * @returns 上游节点 ID 集合
 */
export function ancestorIds(graph: WorkflowGraph, nodeId: string): Set<string> {
  const ancestors = new Set<string>();
  const queue = [nodeId];
  const visited = new Set<string>([nodeId]);
  while (queue.length > 0) {
    const current = queue.shift() as string;
    for (const edge of graph.edges) {
      if (edge.target !== current) {
        continue;
      }
      ancestors.add(edge.source);
      if (!visited.has(edge.source)) {
        visited.add(edge.source);
        queue.push(edge.source);
      }
    }
  }
  return ancestors;
}

/**
 * 规范化节点配置。
 *
 * 只保留与节点类型相关的字段，并去掉空字符串/空数组；该函数同时被表单转换与保存调用，
 * 确保“表单值 → 图状态 → 保存请求”只有一份清洗规则（F-003）。
 *
 * @param type 节点类型
 * @param config 原始配置
 * @returns 清洗后的配置
 */
export function normalizeNodeConfig(
  type: WorkflowNodeType,
  config: WorkflowNodeConfig,
): WorkflowNodeConfig {
  switch (type) {
    case 'START':
      return {};
    case 'END':
      return compact({
        resultFrom: cleanText(config.resultFrom),
        resultTitle: cleanText(config.resultTitle),
      });
    case 'RETRIEVAL':
      return compact({
        queryTemplate: cleanText(config.queryTemplate),
        topK:
          typeof config.topK === 'number' && Number.isFinite(config.topK) ? config.topK : undefined,
      });
    case 'MODEL': {
      const outputKind = config.outputKind;
      const labels =
        outputKind === 'CLASSIFICATION'
          ? (config.labels ?? []).map((label) => label.trim()).filter(Boolean)
          : [];
      return compact({
        promptTemplate: cleanText(config.promptTemplate),
        modelId: cleanText(config.modelId ?? undefined),
        outputKind,
        labels: labels.length > 0 ? labels : undefined,
      });
    }
    case 'MCP_TOOL':
      return compact({
        toolId: cleanText(config.toolId ?? undefined),
        argsTemplate:
          config.argsTemplate && Object.keys(config.argsTemplate).length > 0
            ? config.argsTemplate
            : undefined,
      });
    case 'APPROVAL': {
      const assigneeType = config.assigneeType;
      return compact({
        assigneeType,
        assigneeRole:
          assigneeType === 'ROLE' ? cleanText(config.assigneeRole ?? undefined) : undefined,
        assigneeUserIds:
          assigneeType === 'USER'
            ? (config.assigneeUserIds ?? []).map((id) => id.trim()).filter(Boolean)
            : undefined,
        actionKind: config.actionKind ?? undefined,
        targetNodeKey: cleanText(config.targetNodeKey ?? undefined),
      });
    }
    case 'CONDITION': {
      const condition = config.condition;
      if (!condition) {
        return {};
      }
      const source = condition.left.source;
      return compact({
        condition: {
          left: compact({
            source,
            nodeId:
              source === 'NODE_OUTPUT' ? cleanText(condition.left.nodeId ?? undefined) : undefined,
            path: condition.left.path.trim(),
            valueType: condition.left.valueType,
          }),
          operator: condition.operator,
          right: compact({
            valueType: condition.right.valueType,
            value:
              condition.operator === 'IN'
                ? undefined
                : condition.right.value === undefined
                  ? undefined
                  : condition.right.value,
            values:
              condition.operator === 'IN'
                ? (condition.right.values ?? []).filter((value) => value !== '')
                : undefined,
          }),
        },
      });
    }
    default:
      return {};
  }
}

/**
 * 去掉对象中的 undefined 字段。
 *
 * @param value 原始对象
 * @returns 紧凑对象
 */
function compact<T extends Record<string, unknown>>(value: T): T {
  return Object.fromEntries(
    Object.entries(value).filter(([, entry]) => entry !== undefined && entry !== null),
  ) as T;
}

/**
 * 规范化节点坐标。
 *
 * 契约中 `WorkflowNode.position` 是可选的，V15 内置 market-research 模板也没有坐标。
 * 这里不能把缺失/非有限坐标补成 (0,0)：否则模板创建流程首次打开编辑器时所有节点都会
 * 叠在画布同一位置，并让 `computeCanvasLayout` 的网格回退失效（REV-TASK-0231-001 F-002）。
 * 只有 x/y 同时为有限数字才保留；任一轴无效则整体省略，交由画布做确定性回退。
 *
 * @param position 原始坐标
 * @returns 可持久化的坐标或 undefined
 */
function normalizePosition(
  position: WorkflowGraphPosition | null | undefined,
): WorkflowGraphPosition | undefined {
  if (!position || !Number.isFinite(position.x) || !Number.isFinite(position.y)) {
    return undefined;
  }
  return { x: position.x, y: position.y };
}

/**
 * 规范化整张图，生成可安全提交给后端的草稿。
 *
 * @param graph 当前图
 * @returns 新图；不修改入参
 */
export function normalizeGraphForSave(graph: WorkflowGraph): WorkflowGraph {
  const nodes = graph.nodes.map((node) => {
    const position = normalizePosition(node.position);
    const normalized: WorkflowGraphNode = {
      id: node.id.trim(),
      type: node.type,
      name: node.name.trim(),
      config: normalizeNodeConfig(node.type, node.config),
    };
    // 缺失坐标不写入 position；服务端契约允许省略，画布会用网格回退渲染。
    if (position) {
      normalized.position = position;
    }
    return normalized;
  });
  const typeById = new Map(nodes.map((node) => [node.id, node.type]));
  const edges = graph.edges
    .map((edge) => {
      const sourceType = typeById.get(edge.source.trim());
      const branch =
        sourceType === 'CONDITION' && (edge.branch === 'TRUE' || edge.branch === 'FALSE')
          ? edge.branch
          : undefined;
      return compact({
        id: edge.id.trim(),
        source: edge.source.trim(),
        target: edge.target.trim(),
        branch,
      }) as WorkflowGraphEdge;
    })
    .filter((edge) => edge.source && edge.target);
  return { schemaVersion: 1, nodes, edges };
}

/**
 * 返回节点的简短配置摘要，供节点表展示。
 *
 * @param node 节点
 * @returns 单行摘要
 */
export function nodeConfigSummary(node: WorkflowGraphNode): string {
  const config = node.config;
  switch (node.type) {
    case 'START':
      return '流程起点';
    case 'END':
      return config.resultFrom ? `结果来自 ${config.resultFrom}` : '未配置结果来源';
    case 'RETRIEVAL':
      return config.queryTemplate
        ? `${config.queryTemplate} · topK ${config.topK ?? '—'}`
        : '未配置检索查询';
    case 'MODEL': {
      const output = config.outputKind === 'CLASSIFICATION' ? '分类输出' : '文本输出';
      const model = config.modelId ? `模型 ${shortId(config.modelId)}` : 'REPORT 默认路由';
      const labels =
        config.outputKind === 'CLASSIFICATION' && config.labels?.length
          ? ` · 标签 ${config.labels.join('、')}`
          : '';
      return `${output} · ${model}${labels}`;
    }
    case 'MCP_TOOL':
      return config.toolId ? `工具 ${shortId(config.toolId)}` : '未选择已授权工具';
    case 'APPROVAL': {
      const assignee =
        config.assigneeType === 'ROLE'
          ? `角色 ${config.assigneeRole ?? '—'}`
          : config.assigneeType === 'USER'
            ? `指定 ${config.assigneeUserIds?.length ?? 0} 人`
            : '未设置审批人';
      return `${assignee} · ${config.actionKind ?? '未设置动作'}`;
    }
    case 'CONDITION': {
      const condition = config.condition;
      if (!condition) {
        return '未配置条件';
      }
      return `${condition.left.path || '—'} ${condition.operator} ${
        condition.operator === 'IN'
          ? (condition.right.values ?? []).join('、') || '—'
          : String(condition.right.value ?? '—')
      }`;
    }
    default:
      return NODE_TYPE_LABELS[node.type];
  }
}

/**
 * 缩短 ID 便于在摘要中展示。
 *
 * @param value 原始 ID
 * @returns 前 8 位加省略号
 */
function shortId(value: string): string {
  return value.length <= 8 ? value : `${value.slice(0, 8)}…`;
}

/** 连线表单值。 */
export interface EdgeFormValues {
  source: string;
  target: string;
  branch?: WorkflowEdgeBranch;
}

/** 连线表单错误。 */
export interface EdgeFormError {
  field: keyof EdgeFormValues;
  message: string;
}

/**
 * 校验连线表单。
 *
 * @param graph 当前图
 * @param values 表单值
 * @returns 字段级错误；空数组表示可以添加
 */
export function validateEdgeForm(graph: WorkflowGraph, values: EdgeFormValues): EdgeFormError[] {
  const errors: EdgeFormError[] = [];
  const source = graph.nodes.find((node) => node.id === values.source);
  const target = graph.nodes.find((node) => node.id === values.target);
  if (!source) {
    errors.push({ field: 'source', message: '请选择源节点。' });
  }
  if (!target) {
    errors.push({ field: 'target', message: '请选择目标节点。' });
  }
  if (source && target && source.id === target.id) {
    errors.push({ field: 'target', message: '源节点与目标节点不能相同。' });
  }
  if (source?.type === 'CONDITION' && !values.branch) {
    errors.push({ field: 'branch', message: '条件节点的出边必须选择 TRUE 或 FALSE。' });
  }
  if (source && source.type !== 'CONDITION' && values.branch) {
    errors.push({ field: 'branch', message: '只有条件节点的出边可以设置分支。' });
  }
  const duplicate = graph.edges.some(
    (edge) =>
      edge.source === values.source &&
      edge.target === values.target &&
      (edge.branch ?? null) === (values.branch ?? null),
  );
  if (duplicate) {
    errors.push({ field: 'target', message: '相同的连线已存在。' });
  }
  return errors;
}
