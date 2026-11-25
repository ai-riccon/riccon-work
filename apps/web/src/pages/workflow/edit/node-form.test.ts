import { describe, expect, it } from 'vitest';
import type { McpTool } from '@/services/api/types';
import type { WorkflowGraphNode } from '../list/workflow-api';
import {
  applyNodeFormValues,
  collectNodeFormErrors,
  splitList,
  toNodeFormValues,
  type NodeFormValues,
} from './node-form';

/**
 * 节点表单纯逻辑测试。
 *
 * 覆盖每种节点类型的必填规则、条件类型/算子组合、JSON 参数模板与表单初值转换；
 * 页面只负责把这些错误映射到 antd Form 字段。
 */

/** 构造默认表单值。 */
function values(overrides: Partial<NodeFormValues> = {}): NodeFormValues {
  return { name: '节点', ...overrides };
}

/** 构造节点。 */
function node(
  type: WorkflowGraphNode['type'],
  config: WorkflowGraphNode['config'] = {},
): WorkflowGraphNode {
  return { id: `${type.toLowerCase()}-1`, type, name: '原名称', config, position: { x: 1, y: 1 } };
}

/** 测试用授权工具。 */
const TOOL: McpTool = {
  id: '01990000-0000-7000-8000-000000000601',
  name: 'market_data_lookup',
  riskLevel: 'READ',
  requiresApproval: false,
  status: 'ACTIVE',
};

describe('节点表单值转换', () => {
  it('列表拆分兼容中英文逗号、顿号与换行', () => {
    expect(splitList('a,b，c、d\ne')).toEqual(['a', 'b', 'c', 'd', 'e']);
    expect(splitList(undefined)).toEqual([]);
  });

  it('把条件与标签配置转换为表单初值并可往返', () => {
    const conditionNode = node('CONDITION', {
      condition: {
        left: {
          source: 'NODE_OUTPUT',
          nodeId: 'retrieval-1',
          path: 'hitCount',
          valueType: 'NUMBER',
        },
        operator: 'GTE',
        right: { valueType: 'NUMBER', value: 3 },
      },
    });
    const form = toNodeFormValues(conditionNode);
    expect(form).toMatchObject({
      conditionLeftSource: 'NODE_OUTPUT',
      conditionLeftNodeId: 'retrieval-1',
      conditionLeftPath: 'hitCount',
      conditionValueType: 'NUMBER',
      conditionOperator: 'GTE',
      conditionRightValueText: '3',
    });
    const applied = applyNodeFormValues(conditionNode, form);
    expect(applied.config.condition).toMatchObject({
      left: { source: 'NODE_OUTPUT', nodeId: 'retrieval-1', path: 'hitCount', valueType: 'NUMBER' },
      operator: 'GTE',
      right: { valueType: 'NUMBER', value: 3 },
    });
  });

  it('IN 条件把文本集合解析为声明类型', () => {
    const applied = applyNodeFormValues(
      node('CONDITION'),
      values({
        conditionLeftSource: 'RUN_INPUT',
        conditionLeftPath: 'topic',
        conditionValueType: 'NUMBER',
        conditionOperator: 'IN',
        conditionRightValuesText: '1, 2、3',
      }),
    );
    expect(applied.config.condition?.right).toEqual({
      valueType: 'NUMBER',
      values: [1, 2, 3],
    });
  });

  it('布尔与字符串条件值按类型保留', () => {
    const booleanNode = applyNodeFormValues(
      node('CONDITION'),
      values({
        conditionLeftSource: 'RUN_INPUT',
        conditionLeftPath: 'flag',
        conditionValueType: 'BOOLEAN',
        conditionOperator: 'EQ',
        conditionRightValueText: 'TRUE',
      }),
    );
    expect(booleanNode.config.condition?.right.value).toBe(true);
    const stringNode = applyNodeFormValues(
      node('CONDITION'),
      values({
        conditionLeftSource: 'RUN_INPUT',
        conditionLeftPath: 'topic',
        conditionValueType: 'STRING',
        conditionOperator: 'EQ',
        conditionRightValueText: '市场',
      }),
    );
    expect(stringNode.config.condition?.right.value).toBe('市场');
  });

  it('模型分类输出解析标签，非分类输出丢弃标签', () => {
    const classification = applyNodeFormValues(
      node('MODEL'),
      values({
        outputKind: 'CLASSIFICATION',
        promptTemplate: ' 分类 ',
        labelsText: '正面、中性、负面',
      }),
    );
    expect(classification.config.labels).toEqual(['正面', '中性', '负面']);
    expect(classification.config.promptTemplate).toBe('分类');
    const text = applyNodeFormValues(
      node('MODEL'),
      values({ outputKind: 'TEXT', promptTemplate: '文本', labelsText: '残留' }),
    );
    expect(text.config.labels).toBeUndefined();
  });

  it('MCP 工具解析 JSON 参数模板；非法 JSON 抛错', () => {
    const applied = applyNodeFormValues(
      node('MCP_TOOL'),
      values({ toolId: TOOL.id, argsTemplateText: '{"query":"{{input.topic}}"}' }),
    );
    expect(applied.config.argsTemplate).toEqual({ query: '{{input.topic}}' });
    expect(() =>
      applyNodeFormValues(node('MCP_TOOL'), values({ argsTemplateText: '{bad' })),
    ).toThrow();
  });

  it('审批节点按类型只保留角色或用户 ID', () => {
    const role = applyNodeFormValues(
      node('APPROVAL'),
      values({
        assigneeType: 'ROLE',
        assigneeRole: 'TENANT_ADMIN',
        assigneeUserIds: ['u1'],
        actionKind: 'CONTINUE',
      }),
    );
    expect(role.config.assigneeRole).toBe('TENANT_ADMIN');
    expect(role.config.assigneeUserIds).toBeUndefined();
    const user = applyNodeFormValues(
      node('APPROVAL'),
      values({
        assigneeType: 'USER',
        assigneeRole: 'MEMBER',
        assigneeUserIds: ['u1'],
        actionKind: 'CONTINUE',
      }),
    );
    expect(user.config.assigneeRole).toBeUndefined();
    expect(user.config.assigneeUserIds).toEqual(['u1']);
  });
});

describe('节点表单校验', () => {
  it('名称必填且不超过 200', () => {
    expect(collectNodeFormErrors('START', values({ name: '  ' }))).toEqual([
      { field: 'name', message: '请输入节点名称。' },
    ]);
    expect(collectNodeFormErrors('START', values({ name: 'a'.repeat(201) }))).toEqual([
      { field: 'name', message: '节点名称不能超过 200 个字符。' },
    ]);
  });

  it('检索节点校验查询与 topK 范围', () => {
    expect(collectNodeFormErrors('RETRIEVAL', values())).toEqual([
      { field: 'queryTemplate', message: '请填写检索查询模板。' },
      { field: 'topK', message: '请填写检索命中数。' },
    ]);
    expect(collectNodeFormErrors('RETRIEVAL', values({ queryTemplate: 'q', topK: 0 }))).toEqual([
      { field: 'topK', message: '检索命中数必须是 1 到 20 的整数。' },
    ]);
  });

  it('模型节点校验提示词、输出形态、标签与模型 UUID', () => {
    expect(collectNodeFormErrors('MODEL', values({ modelId: 'bad' }))).toEqual([
      { field: 'promptTemplate', message: '请填写模型提示词模板。' },
      { field: 'outputKind', message: '请选择模型输出形态。' },
      { field: 'modelId', message: '模型 ID 必须是 UUID，或留空使用 REPORT 默认路由。' },
    ]);
    expect(
      collectNodeFormErrors(
        'MODEL',
        values({
          promptTemplate: 'p',
          outputKind: 'CLASSIFICATION',
          labelsText: '',
        }),
      ),
    ).toEqual([{ field: 'labelsText', message: '分类输出至少需要一个标签。' }]);
  });

  it('工具节点校验授权目录与 JSON 模板', () => {
    expect(collectNodeFormErrors('MCP_TOOL', values({ toolId: 'bad' }), [TOOL])).toEqual([
      { field: 'toolId', message: '工具 ID 必须是 UUID。' },
    ]);
    expect(
      collectNodeFormErrors('MCP_TOOL', values({ toolId: TOOL.id, argsTemplateText: '[]' }), [
        TOOL,
      ]),
    ).toEqual([{ field: 'argsTemplateText', message: '工具参数模板必须是 JSON 对象。' }]);
    expect(
      collectNodeFormErrors(
        'MCP_TOOL',
        values({ toolId: '01990000-0000-7000-8000-000000000699' }),
        [TOOL],
      ),
    ).toEqual([{ field: 'toolId', message: '所选工具不在当前已授权工具目录中。' }]);
  });

  it('审批节点校验角色/用户、动作与目标键', () => {
    expect(collectNodeFormErrors('APPROVAL', values({ assigneeType: 'ROLE' }))).toEqual([
      { field: 'assigneeRole', message: '请选择审批角色。' },
      { field: 'actionKind', message: '请选择审批解锁的动作类型。' },
    ]);
    expect(
      collectNodeFormErrors('APPROVAL', values({ assigneeType: 'USER', assigneeUserIds: ['bad'] })),
    ).toEqual([
      { field: 'assigneeUserIds', message: '审批人 ID 必须是 UUID。' },
      { field: 'actionKind', message: '请选择审批解锁的动作类型。' },
    ]);
    expect(
      collectNodeFormErrors(
        'APPROVAL',
        values({
          assigneeType: 'ROLE',
          assigneeRole: 'MEMBER',
          actionKind: 'MCP_TOOL',
          targetNodeKey: 'bad key',
        }),
      ),
    ).toEqual([
      {
        field: 'targetNodeKey',
        message: '目标节点键只能包含字母、数字、下划线和连字符，长度 1–64。',
      },
    ]);
    expect(
      collectNodeFormErrors(
        'APPROVAL',
        values({ assigneeType: 'ROLE', assigneeRole: 'MEMBER', actionKind: 'MCP_TOOL' }),
      ),
    ).toEqual([{ field: 'targetNodeKey', message: 'MCP 工具审批必须指定目标节点键。' }]);
  });

  it('条件节点校验来源、值类型、算子与比较值', () => {
    expect(collectNodeFormErrors('CONDITION', values())).toEqual([
      { field: 'conditionLeftSource', message: '请选择条件数据来源。' },
      { field: 'conditionLeftPath', message: '请填写条件字段。' },
      { field: 'conditionValueType', message: '请选择条件值类型。' },
      { field: 'conditionOperator', message: '请选择条件算子。' },
      { field: 'conditionRightValueText', message: '请填写比较值。' },
    ]);
    expect(
      collectNodeFormErrors(
        'CONDITION',
        values({
          conditionLeftSource: 'NODE_OUTPUT',
          conditionLeftPath: 'hitCount',
          conditionValueType: 'STRING',
          conditionOperator: 'GT',
          conditionRightValueText: '3',
        }),
      ),
    ).toEqual([
      { field: 'conditionLeftNodeId', message: '请选择上游节点。' },
      { field: 'conditionOperator', message: '大于/小于类算子要求值类型为数字。' },
    ]);
    expect(
      collectNodeFormErrors(
        'CONDITION',
        values({
          conditionLeftSource: 'RUN_INPUT',
          conditionLeftPath: 'topic',
          conditionValueType: 'NUMBER',
          conditionOperator: 'IN',
          conditionRightValuesText: '',
        }),
      ),
    ).toEqual([{ field: 'conditionRightValuesText', message: 'IN 算子至少需要一个值。' }]);
    expect(
      collectNodeFormErrors(
        'CONDITION',
        values({
          conditionLeftSource: 'RUN_INPUT',
          conditionLeftPath: 'topic',
          conditionValueType: 'NUMBER',
          conditionOperator: 'EQ',
          conditionRightValueText: 'abc',
        }),
      ),
    ).toEqual([{ field: 'conditionRightValueText', message: '比较值必须是数字。' }]);
  });

  it('IN 比较值限制数量与类型（F-002）', () => {
    const base = {
      conditionLeftSource: 'RUN_INPUT' as const,
      conditionLeftPath: 'topic',
      conditionValueType: 'NUMBER' as const,
      conditionOperator: 'IN' as const,
    };
    const tooMany = Array.from({ length: 21 }, (_, index) => String(index)).join('、');
    expect(
      collectNodeFormErrors('CONDITION', values({ ...base, conditionRightValuesText: tooMany })),
    ).toEqual([{ field: 'conditionRightValuesText', message: 'IN 比较值集合最多 20 个。' }]);
    expect(
      collectNodeFormErrors('CONDITION', values({ ...base, conditionRightValuesText: '1、abc' })),
    ).toEqual([{ field: 'conditionRightValuesText', message: 'IN 比较值必须都是数字。' }]);
    expect(
      collectNodeFormErrors(
        'CONDITION',
        values({
          ...base,
          conditionValueType: 'BOOLEAN',
          conditionRightValuesText: 'true、yes',
        }),
      ),
    ).toEqual([
      { field: 'conditionRightValuesText', message: 'IN 比较值必须都是 true 或 false。' },
    ]);
    expect(
      collectNodeFormErrors('CONDITION', values({ ...base, conditionRightValuesText: '1、2' })),
    ).toEqual([]);
  });

  it('按人审批最多 50 名审批人（F-002）', () => {
    const userIds = (count: number): string[] =>
      Array.from(
        { length: count },
        (_, index) => `01990000-0000-7000-8000-${String(index).padStart(12, '0')}`,
      );
    expect(
      collectNodeFormErrors(
        'APPROVAL',
        values({ assigneeType: 'USER', assigneeUserIds: userIds(50), actionKind: 'CONTINUE' }),
      ),
    ).toEqual([]);
    expect(
      collectNodeFormErrors(
        'APPROVAL',
        values({ assigneeType: 'USER', assigneeUserIds: userIds(51), actionKind: 'CONTINUE' }),
      ),
    ).toEqual([{ field: 'assigneeUserIds', message: '审批人最多 50 人。' }]);
  });

  it('结束节点校验结果来源与标题长度', () => {
    expect(collectNodeFormErrors('END', values())).toEqual([
      { field: 'resultFrom', message: '请选择结果来源节点。' },
    ]);
    expect(
      collectNodeFormErrors('END', values({ resultFrom: 'x', resultTitle: 'a'.repeat(201) })),
    ).toEqual([{ field: 'resultTitle', message: '结果标题不能超过 200 个字符。' }]);
  });
});
