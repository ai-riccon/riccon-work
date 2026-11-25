import { describe, expect, it } from 'vitest';
import { ApiError } from '@/services/api/errors';
import type { SessionInfo } from '@/services/api/types';
import { DEMO_SESSION } from '@/test/fake-api';
import type { WorkflowNodeRun } from '../run/run-api';
import {
  buildRunFilter,
  buildScopeOptions,
  canCancelRun,
  canOperateRun,
  describeCreateRunFailure,
  describePublishFailure,
  describeRunApproval,
  describeRunControlFailure,
  describeRunFilter,
  describeStatusFailure,
  describeVersionScope,
  evaluatePublishScope,
  formatBeijingIso,
  isTenantAdmin,
  isWorkflowUnavailable,
  latestFailedNodes,
  parseRunInputs,
  runDurationText,
  runIsActive,
  runIsTerminal,
  toWorkflowOptions,
  versionOptionLabel,
} from './format';
import type { WorkflowRunSummary } from './history-api';
import type { WorkflowDefinition, WorkflowVersion } from '../list/workflow-api';

/**
 * 运行历史/发布纯逻辑测试。
 *
 * 覆盖筛选转换、范围选项与评估、运行操作权限、失败节点去重、错误文案和运行输入校验；
 * 组件测试关注交互，这里保证边界规则不依赖渲染。
 */

/** 部门负责人会话。 */
const EDITOR_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  user: { ...DEMO_SESSION.user, id: 'editor-1' },
  roles: ['WORKFLOW_EDITOR'],
  permissions: ['workflow.read', 'workflow.run', 'workflow.publish', 'workflow.edit'],
};

/** 租户管理员会话。 */
const ADMIN_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  user: { ...DEMO_SESSION.user, id: 'admin-1' },
  roles: ['TENANT_ADMIN'],
  permissions: [
    'workflow.read',
    'workflow.run',
    'workflow.publish',
    'workflow.edit',
    'workflow.manage',
  ],
};

/** 构造运行摘要。 */
function makeRun(overrides: Partial<WorkflowRunSummary> = {}): WorkflowRunSummary {
  return {
    id: 'run-1',
    workflowId: 'workflow-1',
    workflowName: '市场调研流程',
    workflowVersionId: 'version-1',
    versionNo: 1,
    requesterUserId: 'user-1',
    status: 'SUCCEEDED',
    resultStatus: 'SAVED',
    createdAt: '2026-11-25T09:00:00+08:00',
    startedAt: '2026-11-25T09:00:01+08:00',
    finishedAt: '2026-11-25T09:00:20+08:00',
    durationMs: 19_000,
    updatedAt: '2026-11-25T09:00:20+08:00',
    pendingApprovalCount: 0,
    approvedApprovalCount: 0,
    rejectedApprovalCount: 0,
    retentionExpiresAt: '2027-05-24T09:00:00+08:00',
    ...overrides,
  };
}

/** 构造节点运行记录。 */
function makeNode(overrides: Partial<WorkflowNodeRun> = {}): WorkflowNodeRun {
  return {
    id: 'node-run-1',
    nodeId: 'model-1',
    nodeType: 'MODEL',
    name: '生成报告',
    status: 'FAILED',
    attempt: 1,
    ...overrides,
  };
}

/** 构造发布版本。 */
function makeVersion(overrides: Partial<WorkflowVersion> = {}): WorkflowVersion {
  return {
    id: 'version-1',
    workflowId: 'workflow-1',
    versionNo: 1,
    runScope: 'ONLY_SELF',
    graphSha256: 'a'.repeat(64),
    nodeCount: 4,
    publishedBy: 'editor-1',
    publishedAt: '2026-11-25T09:00:00+08:00',
    ...overrides,
  };
}

describe('角色与范围', () => {
  it('仅 workflow.manage 视为租户管理员', () => {
    expect(isTenantAdmin(ADMIN_SESSION)).toBe(true);
    expect(isTenantAdmin(EDITOR_SESSION)).toBe(false);
    expect(isTenantAdmin(null)).toBe(false);
  });

  it('租户管理员只能选择仅自己/本租户，本部门被禁用并说明原因', () => {
    const options = buildScopeOptions({
      tenantAdmin: true,
      departmentsState: 'loaded',
      managedDepartmentCount: 0,
    });
    expect(options.find((option) => option.value === 'DEPARTMENT')?.disabled).toBe(true);
    expect(options.find((option) => option.value === 'TENANT')?.disabled).toBe(false);
    expect(options.find((option) => option.value === 'DEPARTMENT')?.reason).toContain('租户管理员');
  });

  it('部门目录加载失败时本部门禁用，不把空目录当成没有部门', () => {
    const options = buildScopeOptions({
      tenantAdmin: false,
      departmentsState: 'failed',
      managedDepartmentCount: 0,
    });
    const department = options.find((option) => option.value === 'DEPARTMENT');
    expect(department?.disabled).toBe(true);
    expect(department?.reason).toContain('部门目录加载失败');
    expect(options.find((option) => option.value === 'TENANT')?.disabled).toBe(true);
  });

  it('部门目录加载中或没有负责部门时给出不同原因', () => {
    expect(
      buildScopeOptions({
        tenantAdmin: false,
        departmentsState: 'loading',
        managedDepartmentCount: 1,
      }).find((option) => option.value === 'DEPARTMENT')?.reason,
    ).toContain('正在加载');
    expect(
      buildScopeOptions({
        tenantAdmin: false,
        departmentsState: 'loaded',
        managedDepartmentCount: 0,
      }).find((option) => option.value === 'DEPARTMENT')?.reason,
    ).toContain('不是任何启用部门的负责人');
    expect(
      buildScopeOptions({
        tenantAdmin: false,
        departmentsState: 'loaded',
        managedDepartmentCount: 1,
      }).find((option) => option.value === 'DEPARTMENT')?.disabled,
    ).toBe(false);
  });

  it('仅自己始终可发布；本租户只允许管理员', () => {
    expect(
      evaluatePublishScope({
        tenantAdmin: false,
        runScope: 'ONLY_SELF',
        departmentsState: 'idle',
        managedDepartmentIds: [],
      }),
    ).toEqual({ allowed: true, warning: null, blockedReason: null });
    expect(
      evaluatePublishScope({
        tenantAdmin: false,
        runScope: 'TENANT',
        departmentsState: 'idle',
        managedDepartmentIds: [],
      }).allowed,
    ).toBe(false);
    expect(
      evaluatePublishScope({
        tenantAdmin: true,
        runScope: 'TENANT',
        departmentsState: 'idle',
        managedDepartmentIds: [],
      }).allowed,
    ).toBe(true);
  });

  it('租户管理员选择本部门直接阻断', () => {
    const result = evaluatePublishScope({
      tenantAdmin: true,
      runScope: 'DEPARTMENT',
      departmentId: 'dept-1',
      departmentsState: 'loaded',
      managedDepartmentIds: ['dept-1'],
    });
    expect(result.allowed).toBe(false);
    expect(result.blockedReason).toContain('租户管理员不能发布');
  });

  it('本部门缺少部门时阻断', () => {
    const result = evaluatePublishScope({
      tenantAdmin: false,
      runScope: 'DEPARTMENT',
      departmentsState: 'loaded',
      managedDepartmentIds: ['dept-1'],
    });
    expect(result.allowed).toBe(false);
    expect(result.blockedReason).toContain('请选择');
  });

  it('目录已加载且部门在负责列表时可发布', () => {
    expect(
      evaluatePublishScope({
        tenantAdmin: false,
        runScope: 'DEPARTMENT',
        departmentId: 'dept-1',
        departmentsState: 'loaded',
        managedDepartmentIds: ['dept-1'],
      }),
    ).toEqual({ allowed: true, warning: null, blockedReason: null });
  });

  it('目录已加载但部门不在负责列表：最近范围给出警告，其他范围阻断', () => {
    const latest = evaluatePublishScope({
      tenantAdmin: false,
      runScope: 'DEPARTMENT',
      departmentId: 'dept-1',
      departmentsState: 'loaded',
      managedDepartmentIds: [],
      latestVersionScope: 'DEPARTMENT',
      latestVersionDepartmentId: 'dept-1',
    });
    expect(latest.allowed).toBe(true);
    expect(latest.warning).toContain('不在你当前负责');
    const other = evaluatePublishScope({
      tenantAdmin: false,
      runScope: 'DEPARTMENT',
      departmentId: 'dept-2',
      departmentsState: 'loaded',
      managedDepartmentIds: [],
      latestVersionScope: 'DEPARTMENT',
      latestVersionDepartmentId: 'dept-1',
    });
    expect(other.allowed).toBe(false);
  });

  it('部门目录失败/加载中时保留最近范围并警告，其他部门阻断', () => {
    for (const state of ['failed', 'loading'] as const) {
      const latest = evaluatePublishScope({
        tenantAdmin: false,
        runScope: 'DEPARTMENT',
        departmentId: 'dept-1',
        departmentsState: state,
        managedDepartmentIds: [],
        latestVersionScope: 'DEPARTMENT',
        latestVersionDepartmentId: 'dept-1',
      });
      expect(latest.allowed).toBe(true);
      expect(latest.warning).not.toBeNull();
      const other = evaluatePublishScope({
        tenantAdmin: false,
        runScope: 'DEPARTMENT',
        departmentId: 'dept-2',
        departmentsState: state,
        managedDepartmentIds: [],
        latestVersionScope: 'DEPARTMENT',
        latestVersionDepartmentId: 'dept-1',
      });
      expect(other.allowed).toBe(false);
    }
  });

  it('范围描述显示部门名称或短 ID', () => {
    expect(describeVersionScope(makeVersion(), [])).toBe('仅自己');
    expect(describeVersionScope(makeVersion({ runScope: 'TENANT' }), [])).toBe('本租户');
    expect(
      describeVersionScope(makeVersion({ runScope: 'DEPARTMENT', departmentId: 'dept-1' }), [
        { id: 'dept-1', name: '销售部' },
      ]),
    ).toBe('本部门（销售部）');
    expect(
      describeVersionScope(makeVersion({ runScope: 'DEPARTMENT', departmentId: 'dept-1' }), []),
    ).toContain('本部门（dept-1');
  });
});

describe('筛选与时间', () => {
  it('格式化北京时间并保留 +08:00 偏移', () => {
    expect(formatBeijingIso(new Date('2026-11-25T05:00:00Z'))).toBe('2026-11-25T13:00:00+08:00');
  });

  it('空筛选转换为空条件', () => {
    expect(buildRunFilter({}, 'user-1')).toEqual({ filter: {}, error: null });
    expect(
      buildRunFilter({ status: 'ALL', onlyMine: false, requesterUserId: '' }, 'user-1'),
    ).toEqual({ filter: {}, error: null });
  });

  it('只看我发起的优先于手工输入', () => {
    const result = buildRunFilter({ onlyMine: true, requesterUserId: 'someone-else' }, 'user-1');
    expect(result).toEqual({ filter: { requesterUserId: 'user-1' }, error: null });
  });

  it('校验发起人 UUID 与版本号', () => {
    expect(buildRunFilter({ requesterUserId: 'not-a-uuid' }, 'user-1').error).toContain(
      '合法 UUID',
    );
    expect(buildRunFilter({ versionNo: 0 }, 'user-1').error).toContain('版本号');
    expect(buildRunFilter({ versionNo: 1.5 }, 'user-1').error).toContain('版本号');
  });

  it('时间范围反过来时返回错误，正常时转换为带偏移 ISO', () => {
    const from = { toDate: () => new Date('2026-11-25T02:00:00Z') };
    const to = { toDate: () => new Date('2026-11-25T01:00:00Z') };
    expect(buildRunFilter({ from, to }, 'user-1').error).toContain('开始时间');
    expect(
      buildRunFilter({ from: { toDate: () => new Date('2026-11-25T01:00:00Z') }, to }, 'user-1')
        .filter,
    ).toEqual({
      requesterUserId: undefined,
      from: '2026-11-25T09:00:00+08:00',
      to: '2026-11-25T09:00:00+08:00',
    });
  });

  it('筛选摘要覆盖流程/版本/状态/发起人/时间', () => {
    const text = describeRunFilter({
      workflowId: '01990000-0000-7000-8000-000000000001',
      versionNo: 2,
      status: 'FAILED',
      requesterUserId: '01990000-0000-7000-8000-000000000002',
      from: '2026-11-25T09:00:00+08:00',
      to: '2026-11-25T10:00:00+08:00',
    });
    expect(text).toContain('流程');
    expect(text).toContain('版本 v2');
    expect(text).toContain('失败');
    expect(text).toContain('发起人');
    expect(text).toContain('时间');
    expect(describeRunFilter({})).toBe('全部可见运行');
  });
});

describe('运行展示与权限', () => {
  it('审批聚合文案包含状态与计数，无审批时明确说明', () => {
    expect(describeRunApproval(makeRun())).toBe('无审批节点');
    expect(
      describeRunApproval(
        makeRun({
          approvalStatus: 'APPROVED',
          pendingApprovalCount: 0,
          approvedApprovalCount: 1,
          rejectedApprovalCount: 0,
        }),
      ),
    ).toBe('已批准 · 待审批 0 · 已批准 1 · 已拒绝 0');
    expect(
      describeRunApproval(
        makeRun({
          pendingApprovalCount: 1,
          approvedApprovalCount: 0,
          rejectedApprovalCount: 0,
        }),
      ),
    ).toContain('待审批 1');
  });

  it('只有发起人或租户管理员可操作运行', () => {
    const run = makeRun({ requesterUserId: 'user-1' });
    expect(
      canOperateRun(run, { ...DEMO_SESSION, user: { ...DEMO_SESSION.user, id: 'user-1' } }),
    ).toBe(true);
    expect(
      canOperateRun(run, { ...DEMO_SESSION, user: { ...DEMO_SESSION.user, id: 'user-2' } }),
    ).toBe(false);
    expect(canOperateRun(run, ADMIN_SESSION)).toBe(true);
    expect(canOperateRun(run, null)).toBe(false);
  });

  it('活跃运行可取消，终态不可取消', () => {
    expect(canCancelRun('PENDING')).toBe(true);
    expect(canCancelRun('RUNNING')).toBe(true);
    expect(canCancelRun('WAITING_APPROVAL')).toBe(true);
    expect(canCancelRun('SUCCEEDED')).toBe(false);
    expect(canCancelRun('FAILED')).toBe(false);
    expect(canCancelRun('CANCELLED')).toBe(false);
  });

  it('重试只保留每个节点最新尝试中的失败节点', () => {
    const nodes = [
      makeNode({ nodeId: 'a', attempt: 1, status: 'FAILED' }),
      makeNode({ nodeId: 'a', attempt: 2, status: 'SUCCEEDED' }),
      makeNode({ nodeId: 'b', attempt: 1, status: 'FAILED' }),
      makeNode({ nodeId: 'b', attempt: 2, status: 'FAILED' }),
      makeNode({ nodeId: 'c', attempt: 1, status: 'SKIPPED' }),
    ];
    expect(latestFailedNodes(nodes).map((node) => node.nodeId)).toEqual(['b']);
    expect(latestFailedNodes([])).toEqual([]);
  });

  it('运行状态分类与耗时文案', () => {
    expect(runIsActive('RUNNING')).toBe(true);
    expect(runIsActive('FAILED')).toBe(false);
    expect(runIsTerminal('CANCELLED')).toBe(true);
    expect(runIsTerminal('PENDING')).toBe(false);
    expect(runDurationText(makeRun())).toBe('19.0 s');
    expect(runDurationText(makeRun({ durationMs: null }))).toBe('—');
  });

  it('流程选项按 ID 去重', () => {
    const workflow = {
      id: 'workflow-1',
      name: '流程一',
      ownerUserId: 'user-1',
      status: 'ACTIVE',
      draftVersion: 1,
      hasUnpublishedChanges: false,
      createdAt: '2026-11-25T08:00:00+08:00',
      updatedAt: '2026-11-25T08:00:00+08:00',
    } as WorkflowDefinition;
    expect(toWorkflowOptions([workflow, { ...workflow, name: '重复' }])).toHaveLength(1);
  });
});

describe('错误文案', () => {
  /** 构造 API 错误。 */
  function apiError(status: number, code = `RICCON-HTTP-${status}`): ApiError {
    return new ApiError('服务端原文', { status, code });
  }

  it('发布失败按状态给出下一步', () => {
    expect(describePublishFailure(apiError(400))).toContain('编辑器');
    expect(describePublishFailure(apiError(403))).toContain('所有者');
    expect(describePublishFailure(apiError(404))).toContain('无权');
    expect(describePublishFailure(apiError(409))).toContain('刷新');
    expect(describePublishFailure(apiError(503))).toContain('编排功能已关闭');
    expect(describePublishFailure(apiError(500))).toContain('服务暂时不可用');
    expect(describePublishFailure(new Error('boom'))).toContain('发布失败');
  });

  it('停用/启用失败按状态给出下一步', () => {
    expect(describeStatusFailure(apiError(403))).toContain('所有者');
    expect(describeStatusFailure(apiError(404))).toContain('无权');
    expect(describeStatusFailure(apiError(409))).toContain('刷新');
    expect(describeStatusFailure(apiError(503))).toContain('编排功能已关闭');
    expect(describeStatusFailure(apiError(500))).toContain('服务暂时不可用');
    expect(describeStatusFailure(new Error('boom'))).toContain('操作失败');
  });

  it('取消/重试失败按状态与动作给出下一步', () => {
    expect(describeRunControlFailure(apiError(403), 'cancel')).toContain('发起人');
    expect(describeRunControlFailure(apiError(409), 'cancel')).toContain('不能取消');
    expect(describeRunControlFailure(apiError(409), 'retry')).toContain('状态已变化');
    expect(describeRunControlFailure(apiError(429), 'retry')).toContain('并发');
    expect(describeRunControlFailure(apiError(503), 'retry')).toContain('编排功能已关闭');
    expect(describeRunControlFailure(apiError(400), 'retry')).toContain('参数');
    expect(describeRunControlFailure(new Error('boom'), 'retry')).toContain('重试失败');
  });

  it('创建运行失败按状态给出下一步', () => {
    expect(describeCreateRunFailure(apiError(400))).toContain('JSON 对象');
    expect(describeCreateRunFailure(apiError(403))).toContain('运行范围');
    expect(describeCreateRunFailure(apiError(404))).toContain('不可见');
    expect(describeCreateRunFailure(apiError(409))).toContain('刷新');
    expect(describeCreateRunFailure(apiError(429))).toContain('上限');
    expect(describeCreateRunFailure(apiError(503))).toContain('编排功能已关闭');
    expect(describeCreateRunFailure(new Error('boom'))).toContain('创建运行失败');
  });

  it('编排关闭只认 503 稳定码', () => {
    expect(isWorkflowUnavailable(apiError(503, 'RICCON-WF-503'))).toBe(true);
    expect(isWorkflowUnavailable(apiError(503, 'RICCON-MODEL-503'))).toBe(false);
    expect(isWorkflowUnavailable(apiError(500))).toBe(false);
  });
});

describe('运行输入解析', () => {
  it('空输入视为无输入', () => {
    expect(parseRunInputs('   ')).toEqual({ inputs: {}, error: null });
  });

  it('解析合法 JSON 对象', () => {
    expect(parseRunInputs('{"topic":"市场"}')).toEqual({
      inputs: { topic: '市场' },
      error: null,
    });
  });

  it('拒绝非法 JSON、非对象与非法键', () => {
    expect(parseRunInputs('{').error).toContain('合法');
    expect(parseRunInputs('[]').error).toContain('JSON 对象');
    expect(parseRunInputs('null').error).toContain('JSON 对象');
    expect(parseRunInputs('{"a b":1}').error).toContain('键只能包含');
  });

  it('限制 16 个键与 64 KiB', () => {
    const many = Object.fromEntries(
      Array.from({ length: 17 }, (_value, index) => [`k${index}`, index]),
    );
    expect(parseRunInputs(JSON.stringify(many)).error).toContain('最多 16 个键');
    expect(parseRunInputs(JSON.stringify({ big: 'x'.repeat(65 * 1024) })).error).toContain(
      '64 KiB',
    );
  });
});

describe('版本展示', () => {
  it('版本选项包含版本号、范围与发布时间', () => {
    expect(versionOptionLabel(makeVersion(), [])).toContain('v1');
    expect(versionOptionLabel(makeVersion(), [])).toContain('仅自己');
    expect(versionOptionLabel(makeVersion(), [])).toContain('+08:00');
  });
});
