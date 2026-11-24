import { describe, expect, it } from 'vitest';
import type { WorkflowGraph, WorkflowGraphNode } from '../list/workflow-api';
import {
  ancestorIds,
  appendEdge,
  appendNode,
  createEdge,
  createNode,
  emptyGraph,
  isUuid,
  moveNode,
  nodeConfigSummary,
  normalizeGraphForSave,
  removeEdge,
  removeNode,
  replaceNode,
  successorIds,
  validateEdgeForm,
} from './graph';

/**
 * 图状态纯函数测试。
 *
 * 重点覆盖保存前规范化（F-003）、节点删除后的引用清理、顺序调整与连线校验；
 * 这些规则如果出错会直接导致服务端 400，而不只是视觉问题。
 */

/** 构造带完整配置的节点。 */
function node(
  id: string,
  type: WorkflowGraphNode['type'],
  config: WorkflowGraphNode['config'] = {},
  position = { x: 1, y: 2 },
): WorkflowGraphNode {
  return { id, type, name: id, config, position };
}

/** 构造一个最小有效图。 */
function sampleGraph(): WorkflowGraph {
  return {
    schemaVersion: 1,
    nodes: [
      node('start', 'START', {}, { x: 0, y: 0 }),
      node('retrieval', 'RETRIEVAL', { queryTemplate: '主题', topK: 5 }, { x: 10, y: 20 }),
      node('end', 'END', { resultFrom: 'retrieval' }, { x: 30, y: 40 }),
    ],
    edges: [
      { id: 'e1', source: 'start', target: 'retrieval' },
      { id: 'e2', source: 'retrieval', target: 'end' },
    ],
  };
}

describe('图状态基础操作', () => {
  it('空图只含 schemaVersion 1', () => {
    expect(emptyGraph()).toEqual({ schemaVersion: 1, nodes: [], edges: [] });
  });

  it('生成不重复且带前缀的节点 ID，并给出有限坐标', () => {
    let graph = emptyGraph();
    const first = createNode('MODEL', graph);
    graph = appendNode(graph, first);
    const second = createNode('MODEL', graph);
    expect(first.id).toBe('model-1');
    expect(second.id).toBe('model-2');
    expect(Number.isFinite(second.position?.x)).toBe(true);
    expect(Number.isFinite(second.position?.y)).toBe(true);
  });

  it('替换、移动与删除节点保持顺序并清理连线', () => {
    let graph = sampleGraph();
    graph = replaceNode(graph, { ...graph.nodes[1], name: '检索已改名' });
    expect(graph.nodes[1].name).toBe('检索已改名');
    graph = moveNode(graph, 'end', -1);
    expect(graph.nodes.map((item) => item.id)).toEqual(['start', 'end', 'retrieval']);
    graph = removeNode(graph, 'retrieval');
    expect(graph.nodes.map((item) => item.id)).toEqual(['start', 'end']);
    expect(graph.edges).toHaveLength(0);
    expect(graph.nodes[1].config.resultFrom).toBeUndefined();
  });

  it('删除被审批引用的节点会清空 targetNodeKey', () => {
    const graph: WorkflowGraph = {
      schemaVersion: 1,
      nodes: [
        node('approval', 'APPROVAL', {
          assigneeType: 'ROLE',
          assigneeRole: 'TENANT_ADMIN',
          actionKind: 'MCP_TOOL',
          targetNodeKey: 'tool',
        }),
        node('tool', 'MCP_TOOL', { toolId: '01990000-0000-7000-8000-000000000601' }),
      ],
      edges: [{ id: 'e1', source: 'approval', target: 'tool' }],
    };
    const next = removeNode(graph, 'tool');
    expect(next.edges).toHaveLength(0);
    expect(next.nodes[0].config.targetNodeKey).toBeUndefined();
  });

  it('创建/删除连线与计算后继、上游', () => {
    let graph = sampleGraph();
    expect(ancestorIds(graph, 'end')).toEqual(new Set(['retrieval', 'start']));
    const edge = createEdge(graph, 'end', 'start');
    expect(edge.id).toBe('e3');
    graph = appendEdge(graph, edge);
    expect(successorIds(graph, 'end')).toEqual(['start']);
    graph = removeEdge(graph, edge.id);
    expect(graph.edges).toHaveLength(2);
  });

  it('UUID 判断兼容大小写', () => {
    expect(isUuid('01990000-0000-7000-8000-000000000601')).toBe(true);
    expect(isUuid('01990000-0000-7000-8000-00000000060Z')).toBe(false);
    expect(isUuid(undefined)).toBe(false);
  });
});

describe('保存前规范化（F-003）', () => {
  it('始终补齐 position x/y，并去掉空文本', () => {
    const graph: WorkflowGraph = {
      schemaVersion: 1,
      nodes: [
        {
          id: ' start ',
          type: 'START',
          name: '  开始  ',
          config: {},
          position: { x: Number.NaN, y: 2 },
        },
        {
          id: 'retrieval',
          type: 'RETRIEVAL',
          name: '检索',
          config: { queryTemplate: '  ', topK: 3 },
          position: null,
        },
        { id: 'end', type: 'END', name: '结束', config: { resultFrom: '', resultTitle: '  ' } },
      ],
      edges: [
        { id: 'e1', source: ' start ', target: 'retrieval', branch: 'TRUE' },
        { id: 'e2', source: 'retrieval', target: 'end' },
      ],
    };
    const normalized = normalizeGraphForSave(graph);
    expect(normalized.nodes[0]).toMatchObject({
      id: 'start',
      name: '开始',
      position: { x: 0, y: 2 },
    });
    expect(normalized.nodes[1].position).toEqual({ x: 0, y: 0 });
    expect(normalized.nodes[1].config).toEqual({ topK: 3 });
    expect(normalized.nodes[2].config).toEqual({});
    expect(normalized.edges[0]).toEqual({ id: 'e1', source: 'start', target: 'retrieval' });
  });

  it('按节点类型清理字段并按条件节点保留 branch', () => {
    const graph: WorkflowGraph = {
      schemaVersion: 1,
      nodes: [
        node('cond', 'CONDITION', {
          condition: {
            left: { source: 'RUN_INPUT', nodeId: 'start', path: ' topic ', valueType: 'STRING' },
            operator: 'IN',
            right: { valueType: 'STRING', value: 'x', values: ['a', '', 'b'] },
          },
        }),
        node('model', 'MODEL', {
          promptTemplate: ' 提示 ',
          outputKind: 'CLASSIFICATION',
          labels: [' 正面 ', ''],
          modelId: null,
        }),
        node('approval', 'APPROVAL', {
          assigneeType: 'ROLE',
          assigneeRole: 'TENANT_ADMIN',
          assigneeUserIds: ['user-1'],
          actionKind: 'MCP_TOOL',
          targetNodeKey: ' tool ',
        }),
      ],
      edges: [
        { id: 'e1', source: 'cond', target: 'model', branch: 'TRUE' },
        { id: 'e2', source: 'cond', target: 'approval', branch: null },
      ],
    };
    const normalized = normalizeGraphForSave(graph);
    expect(normalized.nodes[0].config.condition).toMatchObject({
      left: { source: 'RUN_INPUT', path: 'topic', valueType: 'STRING' },
      operator: 'IN',
      right: { valueType: 'STRING', values: ['a', 'b'] },
    });
    expect(normalized.nodes[0].config.condition?.right.value).toBeUndefined();
    expect(normalized.nodes[1].config).toEqual({
      promptTemplate: '提示',
      outputKind: 'CLASSIFICATION',
      labels: ['正面'],
    });
    expect(normalized.nodes[2].config.assigneeUserIds).toBeUndefined();
    expect(normalized.nodes[2].config.targetNodeKey).toBe('tool');
    expect(normalized.edges[0].branch).toBe('TRUE');
    expect(normalized.edges[1].branch).toBeUndefined();
  });

  it('USER 审批保留用户 ID 并去掉角色', () => {
    const graph: WorkflowGraph = {
      schemaVersion: 1,
      nodes: [
        node('approval', 'APPROVAL', {
          assigneeType: 'USER',
          assigneeRole: 'MEMBER',
          assigneeUserIds: [' 01990000-0000-7000-8000-000000000601 '],
          actionKind: 'CONTINUE',
        }),
      ],
      edges: [],
    };
    const normalized = normalizeGraphForSave(graph);
    expect(normalized.nodes[0].config).toEqual({
      assigneeType: 'USER',
      assigneeUserIds: ['01990000-0000-7000-8000-000000000601'],
      actionKind: 'CONTINUE',
    });
  });
});

describe('节点摘要与连线表单', () => {
  it('各节点类型都有可读摘要', () => {
    expect(nodeConfigSummary(node('start', 'START'))).toBe('流程起点');
    expect(nodeConfigSummary(node('end', 'END', {}))).toBe('未配置结果来源');
    expect(
      nodeConfigSummary(node('retrieval', 'RETRIEVAL', { queryTemplate: 'q', topK: 2 })),
    ).toContain('topK 2');
    expect(
      nodeConfigSummary(node('model', 'MODEL', { outputKind: 'CLASSIFICATION', labels: ['a'] })),
    ).toContain('分类输出');
    expect(nodeConfigSummary(node('tool', 'MCP_TOOL', {}))).toBe('未选择已授权工具');
    expect(nodeConfigSummary(node('approval', 'APPROVAL', {}))).toBe('未设置审批人 · 未设置动作');
    expect(nodeConfigSummary(node('cond', 'CONDITION', {}))).toBe('未配置条件');
    expect(
      nodeConfigSummary(
        node('cond', 'CONDITION', {
          condition: {
            left: { source: 'RUN_INPUT', path: 'topic', valueType: 'STRING' },
            operator: 'EQ',
            right: { valueType: 'STRING', value: 'x' },
          },
        }),
      ),
    ).toContain('topic EQ x');
  });

  it('连线表单拒绝缺失、自连、重复与分支误用', () => {
    const graph = sampleGraph();
    expect(validateEdgeForm(graph, { source: '', target: '' })).toEqual([
      { field: 'source', message: '请选择源节点。' },
      { field: 'target', message: '请选择目标节点。' },
    ]);
    expect(validateEdgeForm(graph, { source: 'start', target: 'start' })).toEqual([
      { field: 'target', message: '源节点与目标节点不能相同。' },
    ]);
    expect(validateEdgeForm(graph, { source: 'start', target: 'retrieval' })).toEqual([
      { field: 'target', message: '相同的连线已存在。' },
    ]);
    expect(validateEdgeForm(graph, { source: 'start', target: 'end', branch: 'TRUE' })).toEqual([
      { field: 'branch', message: '只有条件节点的出边可以设置分支。' },
    ]);
    const conditionGraph: WorkflowGraph = {
      ...graph,
      nodes: [...graph.nodes, node('cond', 'CONDITION')],
    };
    expect(validateEdgeForm(conditionGraph, { source: 'cond', target: 'end' })).toEqual([
      { field: 'branch', message: '条件节点的出边必须选择 TRUE 或 FALSE。' },
    ]);
  });
});
