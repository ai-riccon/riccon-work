import type { WorkflowDefinition, WorkflowNodeType, WorkflowRunScope } from './workflow-api';

/**
 * 编排 Studio 的展示规则与权限常量。
 *
 * 页面只消费这里的文案映射；服务端契约或 PRODUCT 固定文案变化时只改一处，
 * 避免列表页、编辑器与校验面板出现不一致的中文口径。
 */

/** M06 权限码；与平台 domain `PermissionCodes` 保持一致。 */
export const WORKFLOW_PERMISSIONS = {
  /** 读取流程/详情。 */
  read: 'workflow.read',
  /** 创建、保存、另存副本。 */
  edit: 'workflow.edit',
  /** 发布版本与停用/启用。 */
  publish: 'workflow.publish',
  /** 启动运行。 */
  run: 'workflow.run',
  /** 审批待办。 */
  approve: 'workflow.approve',
  /** 移交所有权与全部流程管理。 */
  manage: 'workflow.manage',
} as const;

/**
 * 判断会话权限是否包含指定编排权限。
 *
 * 权限未加载时按无权限处理；前端隐藏入口不代替服务端鉴权。
 *
 * @param permissions 会话权限
 * @param permission 目标权限码
 * @returns 是否具备
 */
export function hasWorkflowPermission(
  permissions: readonly string[] | undefined,
  permission: string,
): boolean {
  return permissions?.includes(permission) ?? false;
}

/** 节点类型中文名。 */
export const NODE_TYPE_LABELS: Record<WorkflowNodeType, string> = {
  START: '开始',
  END: '结束',
  RETRIEVAL: '知识库检索',
  MODEL: '模型',
  MCP_TOOL: 'MCP 工具',
  APPROVAL: '人工审批',
  CONDITION: '条件分支',
};

/** 节点类型下拉选项；顺序与 DAG 常见编排顺序一致。 */
export const NODE_TYPE_OPTIONS = (Object.keys(NODE_TYPE_LABELS) as WorkflowNodeType[]).map(
  (value) => ({ value, label: NODE_TYPE_LABELS[value] }),
);

/** 运行范围中文名。 */
export const RUN_SCOPE_LABELS: Record<WorkflowRunScope, string> = {
  ONLY_SELF: '仅自己',
  DEPARTMENT: '本部门',
  TENANT: '本租户',
};

/**
 * 返回运行范围中文名。
 *
 * @param scope 运行范围
 * @returns 中文文案
 */
export function runScopeLabel(scope: WorkflowRunScope): string {
  return RUN_SCOPE_LABELS[scope];
}

/** 流程展示状态的文案与颜色。 */
export interface WorkflowDisplayStatus {
  label: string;
  color: 'default' | 'blue' | 'green' | 'orange';
}

/**
 * 派生列表/编辑器共用的流程状态。
 *
 * 规则：停用优先；从未发布显示“草稿”；已发布但有未发布修改显示“有未发布修改”；
 * 其余显示“已发布”。状态只用于展示，运行资格仍由服务端权限与发布范围决定。
 *
 * @param workflow 流程摘要
 * @returns 展示状态
 */
export function workflowDisplayStatus(
  workflow: Pick<WorkflowDefinition, 'status' | 'publishedVersionNo' | 'hasUnpublishedChanges'>,
): WorkflowDisplayStatus {
  if (workflow.status === 'DISABLED') {
    return { label: '已停用', color: 'default' };
  }
  if (workflow.publishedVersionNo == null) {
    return { label: '草稿', color: 'blue' };
  }
  if (workflow.hasUnpublishedChanges) {
    return { label: '有未发布修改', color: 'orange' };
  }
  return { label: '已发布', color: 'green' };
}

/**
 * 格式化版本摘要。
 *
 * @param workflow 流程摘要
 * @returns 例如 `草稿 v3 · 发布 v2` 或 `草稿 v1 · 尚未发布`
 */
export function workflowVersionSummary(
  workflow: Pick<WorkflowDefinition, 'draftVersion' | 'publishedVersionNo'>,
): string {
  const draft = `草稿 v${workflow.draftVersion}`;
  return workflow.publishedVersionNo == null
    ? `${draft} · 尚未发布`
    : `${draft} · 发布 v${workflow.publishedVersionNo}`;
}
