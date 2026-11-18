import { ApiError, describeApiError } from '@/services/api/errors';

/**
 * 部门/空间管理错误文案。
 *
 * 服务端 409 的 `detail` 是稳定英文原因码（ADR-0018 第 7.3 节），
 * 前端只翻译已知原因；未知原因统一回落为中文通用文案，
 * 不把英文内部原因或后端堆栈直接展示给用户。
 */
const DEPARTMENT_DETAIL_MESSAGES: Record<string, string> = {
  'department name already exists':
    '部门名称已存在（含已停用部门），请换一个名称或重新启用原部门。',
  'department is disabled': '部门已停用，请先启用后再修改。',
  'member account is not active': '该账号已停用，不能加入部门。',
  'member already belongs to this department': '该账号已在本部门，无需重复加入。',
  'member already belongs to another department': '该账号已属于其他部门；请先从原部门移出。',
  'member already belongs to a department': '该账号已属于其他部门；请先从原部门移出。',
  'department manager must be transferred before removal':
    '不能直接移出当前负责人，请先转移负责人。',
  'target user must be an active member of the department': '负责人必须是本部门当前启用的成员。',
  'target tenant is not active': '目标租户已暂停，部门管理暂时不可用。',
};

/** 空间管理 409 原因码到中文文案。 */
const WORKSPACE_DETAIL_MESSAGES: Record<string, string> = {
  'workspace member already exists': '该成员已在空间内，无需重复添加。',
  'personal workspace does not support explicit members': '个人空间仅本人可见，不支持成员管理。',
  'explicit members are only supported for CUSTOM workspaces':
    '只有“指定成员”空间支持显式成员管理。',
  'visibility of this workspace cannot be changed': '历史全租户空间的可见性不可转换。',
  'only DEPT or CUSTOM visibility is allowed': '可见性只能选择“本部门成员”或“指定成员”。',
  'department is not active': '所属部门已停用，不能创建或修改空间。',
  'at least one of name, description or visibility is required': '请至少修改一个字段。',
};

/**
 * 读取 ProblemDetail 的稳定原因。
 *
 * `toApiError` 已把 `detail` 写入 `message`；优先使用结构化 `problem.detail`，
 * 测试注入的 ApiError 可能只有 message，因此两者都接受。
 *
 * @param error 已归一化的 ApiError
 * @returns 原始 detail 或错误消息
 */
function errorDetail(error: ApiError): string {
  return error.problem?.detail ?? error.message;
}

/**
 * 把平台管理员部门接口错误转成可读中文。
 *
 * @param error 任意异常
 * @param fallback 未识别异常的兜底文案
 * @returns 面向用户的文案
 */
export function describePlatformDepartmentError(
  error: unknown,
  fallback = '部门操作失败，请稍后重试。',
): string {
  if (!(error instanceof ApiError)) {
    return fallback;
  }

  const detail = errorDetail(error);
  const mapped = DEPARTMENT_DETAIL_MESSAGES[detail];
  if (mapped) {
    return mapped;
  }

  switch (error.status) {
    case 0:
      return describeApiError(error, fallback);
    case 400:
      return '请求参数不符合部门管理要求，请检查后重试。';
    case 401:
      return '登录状态已失效，请重新登录。';
    case 403:
      return '没有部门管理权限；只有平台管理员可以管理租户部门。';
    case 404:
      return '目标租户或部门不存在，或不属于该租户。';
    case 409:
      return '部门状态冲突，请刷新后重试。';
    default:
      return describeApiError(error, fallback);
  }
}

/**
 * 把租户内空间管理错误转成可读中文。
 *
 * @param error 任意异常
 * @param fallback 未识别异常的兜底文案
 * @returns 面向用户的文案
 */
export function describeWorkspaceError(
  error: unknown,
  fallback = '空间操作失败，请稍后重试。',
): string {
  if (!(error instanceof ApiError)) {
    return fallback;
  }

  const detail = errorDetail(error);
  const mapped = WORKSPACE_DETAIL_MESSAGES[detail];
  if (mapped) {
    return mapped;
  }

  switch (error.status) {
    case 0:
      return describeApiError(error, fallback);
    case 400:
      return '请求不符合空间管理规则，请检查后重试。';
    case 401:
      return '登录状态已失效，请重新登录。';
    case 403:
      return '你不是该空间的管理者，无法执行此操作。';
    case 404:
      return '空间或部门不存在，或你没有管理权限。';
    case 409:
      return '成员或空间状态冲突，请刷新后重试。';
    default:
      return describeApiError(error, fallback);
  }
}
