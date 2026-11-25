/**
 * 编辑器与契约/后端一致的客户端输入上限。
 *
 * 这些上限同时服务表单校验（`node-form.ts`）、图校验（`validation.ts`）与页面级操作
 * （另存副本名称），集中定义避免三处各写一份数字后与 `contracts/openapi.yaml` 漂移。
 * 服务端仍是最终守卫；客户端提前拦截只是避免“可提交但必然 400”的体验缺陷
 * （REV-TASK-0230-001 F-002）。
 */

/** 条件 `IN` 值集合上限；对应 OpenAPI `WorkflowConditionRight.values.maxItems`。 */
export const MAX_CONDITION_IN_VALUES = 20;

/** 审批人用户 ID 上限；对应 OpenAPI `WorkflowNodeConfig.assigneeUserIds.maxItems`。 */
export const MAX_ASSIGNEE_USER_IDS = 50;

/** 流程名称上限；对应 OpenAPI 创建/草稿/副本请求的 `name.maxLength`。 */
export const MAX_WORKFLOW_NAME = 200;

/** 另存副本名称后缀。 */
const COPY_NAME_SUFFIX = ' 副本';

/**
 * 生成不超过契约上限的副本名称。
 *
 * 原流程名允许 200 个字符，直接追加“ 副本”会超出 `WorkflowDuplicateRequest.name`
 * 的 200 字符上限并导致必然 400；这里按字符（而非 UTF-16 码元）截断原名，
 * 保留完整后缀，避免把 emoji 等代理对截断成非法字符串。
 *
 * @param baseName 当前编辑器中的流程名，可为空
 * @param fallbackName 详情中的流程名，可为空
 * @returns 1–200 字符的副本名称
 */
export function duplicateWorkflowName(
  baseName: string | null | undefined,
  fallbackName: string | null | undefined,
): string {
  const base = (baseName ?? '').trim() || (fallbackName ?? '').trim() || '流程';
  const suffixLength = [...COPY_NAME_SUFFIX].length;
  const truncated = [...base].slice(0, MAX_WORKFLOW_NAME - suffixLength).join('');
  return `${truncated}${COPY_NAME_SUFFIX}`;
}
