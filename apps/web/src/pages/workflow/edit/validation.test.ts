import { describe, expect, it } from 'vitest';
import type { McpTool } from '@/services/api/types';
import type {
  WorkflowConditionRight,
  WorkflowGraph,
  WorkflowGraphNode,
} from '../list/workflow-api';
import {
  fromServerIssue,
  issueCodeLabel,
  nodeLabel,
  validateGraph,
  type EditorIssueCode,
} from './validation';

/**
 * 编辑器客户端校验测试。
 *
 * 断言与后端 WorkflowValidator 的结构规则一致；不存在的目录项（模型路由/工具授权）
 * 由服务端 /validate 补充，这里只验证工具目录已知时的写工具审批前驱规则。
 */

/** 构造节点。 */
function node(
  id: string,
  type: WorkflowGraphNode['type'],
  config: WorkflowGraphNode['config'] = {},
): WorkflowGraphNode {
  return { id, type, name: id, config, position: { x: 1, y: 1 } };
}

/** 最小有效图：开始 → 检索 → 结束。 */
function validGraph(): WorkflowGraph {
  return {
    schemaVersion: 1,
    nodes: [
      node('start', 'START'),
      node('retrieval', 'RETRIEVAL', { queryTemplate: '主题', topK: 5 }),
      node('end', 'END', { resultFrom: 'retrieval', resultTitle: '报告' }),
    ],
    edges: [
      { id: 'e1', source: 'start', target: 'retrieval' },
      { id: 'e2', source: 'retrieval', target: 'end' },
    ],
  };
}

/** 判断问题列表是否包含指定码。 */
function hasCode(issues: ReturnType<typeof validateGraph>, code: EditorIssueCode): boolean {
  return issues.some((issue) => issue.code === code);
}

/** 构造含 IN 条件的图：开始 → 条件 → 两个结束。 */
function conditionGraph(right: WorkflowConditionRight): WorkflowGraph {
  return {
    schemaVersion: 1,
    nodes: [
      node('start', 'START'),
      node('cond', 'CONDITION', {
        condition: {
          left: { source: 'RUN_INPUT', path: 'topic', valueType: 'NUMBER' },
          operator: 'IN',
          right,
        },
      }),
      node('end-true', 'END', { resultFrom: 'cond' }),
      node('end-false', 'END', { resultFrom: 'cond' }),
    ],
    edges: [
      { id: 'e1', source: 'start', target: 'cond' },
      { id: 'e2', source: 'cond', target: 'end-true', branch: 'TRUE' },
      { id: 'e3', source: 'cond', target: 'end-false', branch: 'FALSE' },
    ],
  };
}

/** 授权工具。 */
const APPROVAL_TOOL: McpTool = {
  id: '01990000-0000-7000-8000-000000000601',
  name: 'write_tool',
  riskLevel: 'WRITE',
  requiresApproval: true,
  status: 'ACTIVE',
};

describe('validateGraph', () => {
  it('最小有效图返回空问题列表', () => {
    expect(validateGraph(validGraph(), [])).toEqual([]);
  });

  it('缺少开始/结束与多个开始节点', () => {
    const missing = validateGraph({ schemaVersion: 1, nodes: [], edges: [] });
    expect(hasCode(missing, 'MISSING_START')).toBe(true);
    expect(hasCode(missing, 'MISSING_END')).toBe(true);
    const multiple = validGraph();
    multiple.nodes.unshift(node('start-2', 'START'));
    const issues = validateGraph(multiple, []);
    expect(hasCode(issues, 'MULTIPLE_START')).toBe(true);
  });

  it('检测环节点并定位', () => {
    const graph: WorkflowGraph = {
      schemaVersion: 1,
      nodes: [
        node('start', 'START'),
        node('a', 'MODEL', { promptTemplate: 'a', outputKind: 'TEXT' }),
        node('b', 'MODEL', { promptTemplate: 'b', outputKind: 'TEXT' }),
        node('end', 'END', { resultFrom: 'b' }),
      ],
      edges: [
        { id: 'e1', source: 'start', target: 'a' },
        { id: 'e2', source: 'a', target: 'b' },
        { id: 'e3', source: 'b', target: 'a' },
      ],
    };
    const issues = validateGraph(graph, []);
    expect(hasCode(issues, 'GRAPH_CYCLE')).toBe(true);
    expect(issues.find((issue) => issue.code === 'GRAPH_CYCLE')?.nodeId).toBe('a');
  });

  it('孤立节点与入边过多', () => {
    const graph = validGraph();
    graph.nodes.push(node('orphan', 'MODEL', { promptTemplate: 'p', outputKind: 'TEXT' }));
    graph.edges.push({ id: 'e3', source: 'start', target: 'end' });
    const issues = validateGraph(graph, []);
    expect(hasCode(issues, 'ORPHAN_NODE')).toBe(true);
    expect(hasCode(issues, 'INDEGREE_EXCEEDED')).toBe(true);
  });

  it('条件节点必须有 TRUE/FALSE 出边，普通节点不能带 branch', () => {
    const graph: WorkflowGraph = {
      schemaVersion: 1,
      nodes: [
        node('start', 'START'),
        node('cond', 'CONDITION', {
          condition: {
            left: { source: 'RUN_INPUT', path: 'topic', valueType: 'STRING' },
            operator: 'EQ',
            right: { valueType: 'STRING', value: 'x' },
          },
        }),
        node('end', 'END', { resultFrom: 'start' }),
      ],
      edges: [
        { id: 'e1', source: 'start', target: 'cond' },
        { id: 'e2', source: 'cond', target: 'end', branch: 'TRUE' },
      ],
    };
    const issues = validateGraph(graph, []);
    expect(hasCode(issues, 'CONDITION_BRANCH_INVALID')).toBe(true);
    expect(hasCode(issues, 'RESULT_INVALID')).toBe(true);
  });

  it('节点名称与键非法会被拦截', () => {
    const graph = validGraph();
    graph.nodes[0].name = '  ';
    graph.nodes[1].id = 'bad id';
    graph.edges[1].target = 'bad id';
    graph.nodes[2].config.resultFrom = 'bad id';
    const issues = validateGraph(graph, []);
    expect(hasCode(issues, 'NODE_NAME_REQUIRED')).toBe(true);
    expect(hasCode(issues, 'NODE_ID_INVALID')).toBe(true);
  });

  it('检索、模型与结束节点必填配置', () => {
    const graph: WorkflowGraph = {
      schemaVersion: 1,
      nodes: [
        node('start', 'START'),
        node('retrieval', 'RETRIEVAL'),
        node('model', 'MODEL', { outputKind: 'CLASSIFICATION' }),
        node('end', 'END'),
      ],
      edges: [
        { id: 'e1', source: 'start', target: 'retrieval' },
        { id: 'e2', source: 'retrieval', target: 'model' },
        { id: 'e3', source: 'model', target: 'end' },
      ],
    };
    const issues = validateGraph(graph, []);
    expect(issues.filter((issue) => issue.code === 'REQUIRED_CONFIG')).toHaveLength(5);
  });

  it('审批节点：角色/用户二选一、动作与目标直接后继', () => {
    const graph: WorkflowGraph = {
      schemaVersion: 1,
      nodes: [
        node('start', 'START'),
        node('approval', 'APPROVAL', {
          assigneeType: 'ROLE',
          assigneeRole: 'TENANT_ADMIN',
          assigneeUserIds: ['u1'],
          actionKind: 'MCP_TOOL',
        }),
        node('tool', 'MCP_TOOL', { toolId: APPROVAL_TOOL.id }),
        node('end', 'END', { resultFrom: 'start' }),
      ],
      edges: [
        { id: 'e1', source: 'start', target: 'approval' },
        { id: 'e2', source: 'approval', target: 'tool' },
        { id: 'e3', source: 'tool', target: 'end' },
      ],
    };
    const issues = validateGraph(graph, [APPROVAL_TOOL]);
    expect(hasCode(issues, 'APPROVAL_CONFIG')).toBe(true);
    expect(hasCode(issues, 'RESULT_INVALID')).toBe(true);
  });

  it('写工具缺少审批前驱时给出审批配置问题', () => {
    const graph: WorkflowGraph = {
      schemaVersion: 1,
      nodes: [
        node('start', 'START'),
        node('tool', 'MCP_TOOL', { toolId: APPROVAL_TOOL.id }),
        node('end', 'END', { resultFrom: 'start' }),
      ],
      edges: [
        { id: 'e1', source: 'start', target: 'tool' },
        { id: 'e2', source: 'tool', target: 'end' },
      ],
    };
    const issues = validateGraph(graph, [APPROVAL_TOOL]);
    expect(
      issues.some((issue) => issue.code === 'APPROVAL_CONFIG' && issue.nodeId === 'tool'),
    ).toBe(true);
  });

  it('条件左值引用上游与受控字段', () => {
    const graph: WorkflowGraph = {
      schemaVersion: 1,
      nodes: [
        node('start', 'START'),
        node('model', 'MODEL', { promptTemplate: 'p', outputKind: 'TEXT' }),
        node('cond', 'CONDITION', {
          condition: {
            left: {
              source: 'NODE_OUTPUT',
              nodeId: 'model',
              path: 'classificationLabel',
              valueType: 'STRING',
            },
            operator: 'EQ',
            right: { valueType: 'STRING', value: 'x' },
          },
        }),
        node('end', 'END', { resultFrom: 'model' }),
      ],
      edges: [
        { id: 'e1', source: 'start', target: 'model' },
        { id: 'e2', source: 'model', target: 'cond' },
        { id: 'e3', source: 'cond', target: 'end', branch: 'TRUE' },
        { id: 'e4', source: 'cond', target: 'end', branch: 'FALSE' },
      ],
    };
    const issues = validateGraph(graph, []);
    expect(
      issues.some(
        (issue) =>
          issue.code === 'CONDITION_BRANCH_INVALID' && issue.field === 'config.condition.left.path',
      ),
    ).toBe(true);
  });

  it('IN 比较值限制数量与声明类型（F-002）', () => {
    const valid = validateGraph(
      conditionGraph({
        valueType: 'NUMBER',
        values: [1, 2, 3],
      }),
      [],
    );
    expect(valid).toEqual([]);

    const tooMany = validateGraph(
      conditionGraph({
        valueType: 'NUMBER',
        values: Array.from({ length: 21 }, (_, index) => index),
      }),
      [],
    );
    expect(
      tooMany.some(
        (issue) =>
          issue.code === 'CONDITION_BRANCH_INVALID' &&
          issue.field === 'config.condition.right.values' &&
          issue.message.includes('最多 20 个'),
      ),
    ).toBe(true);

    const mismatched = validateGraph(
      conditionGraph({
        valueType: 'NUMBER',
        values: [1, '2'],
      }),
      [],
    );
    expect(
      mismatched.some(
        (issue) =>
          issue.code === 'CONDITION_BRANCH_INVALID' &&
          issue.field === 'config.condition.right.values' &&
          issue.message.includes('类型必须与条件值类型一致'),
      ),
    ).toBe(true);
  });

  it('按人审批最多 50 名审批人（F-002）', () => {
    const graph = (count: number): WorkflowGraph => ({
      schemaVersion: 1,
      nodes: [
        node('start', 'START'),
        node('approval', 'APPROVAL', {
          assigneeType: 'USER',
          assigneeUserIds: Array.from(
            { length: count },
            (_, index) => `01990000-0000-7000-8000-${String(index).padStart(12, '0')}`,
          ),
          actionKind: 'CONTINUE',
        }),
        node('end', 'END', { resultFrom: 'approval' }),
      ],
      edges: [
        { id: 'e1', source: 'start', target: 'approval' },
        { id: 'e2', source: 'approval', target: 'end' },
      ],
    });
    expect(validateGraph(graph(50), [])).toEqual([]);
    expect(
      validateGraph(graph(51), []).some(
        (issue) =>
          issue.code === 'APPROVAL_CONFIG' &&
          issue.field === 'config.assigneeUserIds' &&
          issue.message.includes('最多 50 名'),
      ),
    ).toBe(true);
  });
});

describe('问题展示辅助', () => {
  it('服务端问题映射为中文并保留原始说明', () => {
    const mapped = fromServerIssue({
      code: 'REQUIRED_CONFIG',
      nodeId: 'retrieval',
      field: 'config.topK',
      message: 'RETRIEVAL node requires topK',
    });
    expect(mapped.message).toBe('节点缺少必填配置，请补充。（检索命中数）');
    expect(mapped.serverMessage).toBe('RETRIEVAL node requires topK');
  });

  it('节点标签优先返回名称，问题码有中文标签', () => {
    const graph = validGraph();
    expect(nodeLabel(graph, 'retrieval')).toBe('retrieval（retrieval）');
    expect(nodeLabel(graph, null)).toBe('流程');
    expect(nodeLabel(graph, 'missing')).toBe('missing');
    expect(issueCodeLabel('GRAPH_CYCLE')).toBe('流程存在环');
  });
});
