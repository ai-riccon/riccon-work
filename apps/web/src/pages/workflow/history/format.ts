import { ApiError, describeApiError } from '@/services/api/errors';
import type { SessionInfo } from '@/services/api/types';
import { formatOffsetDateTime } from '@/services/api/time';
import { formatShortId } from '../approvals/format';
import {
  hasWorkflowPermission,
  RUN_SCOPE_LABELS,
  runScopeLabel,
  WORKFLOW_PERMISSIONS,
} from '../list/format';
import type { WorkflowDefinition, WorkflowRunScope, WorkflowVersion } from '../list/workflow-api';
import {
  approvalCountsText,
  approvalStatusLabel,
  formatDuration,
  isActiveRunStatus,
  isTerminalRunStatus,
  RUN_STATUS_COLORS,
  RUN_STATUS_LABELS,
} from '../run/format';
import type { WorkflowNodeRun, WorkflowRunStatus } from '../run/run-api';
import type { ListRunsInput, WorkflowRunSummary } from './history-api';

/**
 * 运行历史与发布页的展示规则、固定文案与纯逻辑。
 *
 * 页面只消费这里的映射与格式化函数；状态颜色复用运行详情页的同一映射，运行范围复用流程列表页的
 * 同一映射，避免三个页面出现不一致的中文口径。这里只做展示与客户端预校验，权限、范围与资源可见性
 * 最终仍由服务端判定。
 */

/** 每页条数；与合同分页上限和列表页保持一致。 */
export const PAGE_SIZE = 20;

/** 日期控件的值只依赖 `toDate`，避免页面直接引入 dayjs 运行时。 */
export interface DayjsLike {
  toDate(): Date;
}

/** UUID 格式；与后端查询参数校验口径一致。 */
export const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** 运行草稿输入允许的键；与 `WorkflowGraphNode.ID_PATTERN` 一致。 */
const RUN_INPUT_KEY_PATTERN = /^[A-Za-z0-9_-]{1,64}$/;

/** 运行输入最大键数，与契约 `maxProperties: 16` 一致。 */
const RUN_INPUT_MAX_KEYS = 16;

/** 运行输入最大字节数，与后端 64 KiB 限制一致。 */
const RUN_INPUT_MAX_BYTES = 64 * 1024;

/** 运行历史筛选表单值。 */
export interface RunFilterValues {
  workflowId?: string | null;
  versionNo?: number | null;
  status?: WorkflowRunStatus | 'ALL' | null;
  requesterUserId?: string | null;
  onlyMine?: boolean | null;
  from?: DayjsLike | null;
  to?: DayjsLike | null;
}

/** 筛选转换结果；错误用于在表单内提示，不发请求。 */
export interface BuiltRunFilter {
  filter: ListRunsInput;
  error: string | null;
}

/** 部门目录加载状态；用于区分“正在加载/加载失败/已加载”，避免静默回退发布范围。 */
export type DepartmentsState = 'idle' | 'loading' | 'loaded' | 'failed';

/** 发布范围选项；`disabled` 与 `reason` 同时用于控件与说明文案。 */
export interface PublishScopeOption {
  value: WorkflowRunScope;
  label: string;
  disabled: boolean;
  reason?: string;
}

/** 发布范围评估上下文。 */
export interface PublishScopeContext {
  /** 是否租户管理员；与后端 `WorkflowPublishScopeValidator` 同口径。 */
  tenantAdmin: boolean;
  /** 当前选择的范围。 */
  runScope: WorkflowRunScope;
  /** 当前选择的部门 ID；非 DEPARTMENT 时为空。 */
  departmentId?: string | null;
  /** 部门目录加载状态。 */
  departmentsState: DepartmentsState;
  /** 当前账号负责的启用部门 ID。 */
  managedDepartmentIds: string[];
  /** 最近发布版本的范围与部门；用于保留旧范围并支持“保持最近范围”发布。 */
  latestVersionScope?: WorkflowRunScope | null;
  latestVersionDepartmentId?: string | null;
}

/** 发布范围评估结果；`blockedReason` 非空时禁止提交。 */
export interface PublishScopeEvaluation {
  allowed: boolean;
  warning: string | null;
  blockedReason: string | null;
}

/** 运行状态筛选选项。 */
export const RUN_STATUS_OPTIONS = (Object.keys(RUN_STATUS_LABELS) as WorkflowRunStatus[]).map(
  (value) => ({ value, label: RUN_STATUS_LABELS[value] }),
);

/** 运行历史空态固定文案：结果为空时给出清除筛选入口。 */
export const RUN_HISTORY_EMPTY_MESSAGE =
  '当前筛选条件下没有运行。可以清除筛选或调整时间范围后再试。';

/** 运行历史固定说明：排序与数据边界。 */
export const RUN_HISTORY_SORT_NOTE =
  '按开始时间倒序（服务端排序），每页 20 条；列表只返回运行元数据与审批聚合，不返回结果正文或输入原文。';

/** 编排关闭时运行历史/发布区的固定说明。 */
export const WORKFLOW_UNAVAILABLE_MESSAGE =
  '编排功能已关闭，已有运行记录与发布版本仍可查看；新运行、重试与发布暂时不可用。请联系运维人员开启编排功能。';

/** 无发布权限时的固定说明。 */
export const PUBLISH_READONLY_MESSAGE =
  '你没有发布权限，只能查看已发布版本。发布与停用/启用仅流程所有者或租户管理员可操作。';

/** 发布成功但服务端返回既有版本时的固定说明。 */
export const PUBLISH_IDEMPOTENT_MESSAGE =
  '当前草稿、范围与最近发布版本一致，服务端返回已有版本，不会产生重复版本。';

/**
 * 判断会话是否具备租户管理员能力。
 *
 * 与后端 actor 构造同口径：`workflow.manage` 对应租户管理员；前端不根据角色名推断，
 * 避免角色权限调整后出现两套口径。
 *
 * @param session 会话或空
 * @returns 是否租户管理员
 */
export function isTenantAdmin(session: SessionInfo | null | undefined): boolean {
  return hasWorkflowPermission(session?.permissions, WORKFLOW_PERMISSIONS.manage);
}

/**
 * 把日期格式化为北京时间、带 `+08:00` 偏移的 ISO 8601 字符串。
 *
 * 服务端 `from`/`to` 只接受带偏移的 date-time；DatePicker 返回浏览器本地时刻，
 * 这里统一转换到团队时区，避免浏览器时区不同导致过滤窗口漂移。
 *
 * @param date 任意时刻
 * @returns 例如 `2026-11-25T13:00:00+08:00`
 */
export function formatBeijingIso(date: Date): string {
  const parts = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(date);
  const pick = (type: Intl.DateTimeFormatPartTypes): string =>
    parts.find((part) => part.type === type)?.value ?? '';
  return `${pick('year')}-${pick('month')}-${pick('day')}T${pick('hour')}:${pick(
    'minute',
  )}:${pick('second')}+08:00`;
}

/**
 * 把筛选表单值转换为运行历史查询参数。
 *
 * 空值一律省略，避免把 `status=ALL` 或空流程 ID 当成有效过滤条件；`onlyMine` 优先于手工输入，
 * 用当前账号 ID 覆盖发起人字段。时间范围在客户端先校验，避免必然的 400。
 *
 * @param values 表单值
 * @param currentUserId 当前账号 ID
 * @returns 查询参数与错误文案
 */
export function buildRunFilter(values: RunFilterValues, currentUserId: string): BuiltRunFilter {
  const filter: ListRunsInput = {};
  const workflowId = values.workflowId?.trim();
  if (workflowId) {
    filter.workflowId = workflowId;
  }
  if (values.versionNo != null) {
    if (!Number.isInteger(values.versionNo) || values.versionNo < 1) {
      return { filter, error: '版本号必须是大于等于 1 的整数。' };
    }
    filter.versionNo = values.versionNo;
  }
  if (values.status && values.status !== 'ALL') {
    filter.status = values.status;
  }
  if (values.onlyMine) {
    filter.requesterUserId = currentUserId;
  } else {
    const requester = values.requesterUserId?.trim();
    if (requester) {
      if (!UUID_PATTERN.test(requester)) {
        return { filter, error: '发起人用户 ID 必须是合法 UUID。' };
      }
      filter.requesterUserId = requester;
    }
  }
  const from = values.from?.toDate();
  const to = values.to?.toDate();
  if (from && to && from.getTime() > to.getTime()) {
    return { filter, error: '开始时间不能晚于结束时间。' };
  }
  if (from) {
    filter.from = formatBeijingIso(from);
  }
  if (to) {
    filter.to = formatBeijingIso(to);
  }
  return { filter, error: null };
}

/**
 * 描述当前运行筛选条件。
 *
 * 用于空态和列表顶部说明，帮助用户理解“为什么没有结果”；不展示内部 ID 之外的敏感信息。
 *
 * @param filter 已应用的筛选条件
 * @returns 中文摘要
 */
export function describeRunFilter(filter: ListRunsInput): string {
  const parts: string[] = [];
  if (filter.workflowId) {
    parts.push(`流程 ${formatShortId(filter.workflowId, 12)}`);
  }
  if (filter.versionNo != null) {
    parts.push(`版本 v${filter.versionNo}`);
  }
  if (filter.status) {
    parts.push(`状态 ${RUN_STATUS_LABELS[filter.status]}`);
  }
  if (filter.requesterUserId) {
    parts.push(`发起人 ${formatShortId(filter.requesterUserId, 12)}`);
  }
  if (filter.from || filter.to) {
    parts.push(
      `时间 ${filter.from ? formatOffsetDateTime(filter.from) : '最早'} 至 ${
        filter.to ? formatOffsetDateTime(filter.to) : '当前'
      }`,
    );
  }
  return parts.length > 0 ? parts.join('；') : '全部可见运行';
}

/**
 * 转换流程摘要为下拉选项所需的稳定结构。
 *
 * @param workflows 流程摘要
 * @returns 选项数组；同一 ID 只保留一条
 */
export function toWorkflowOptions(workflows: WorkflowDefinition[]): WorkflowDefinition[] {
  const byId = new Map<string, WorkflowDefinition>();
  for (const workflow of workflows) {
    if (!byId.has(workflow.id)) {
      byId.set(workflow.id, workflow);
    }
  }
  return [...byId.values()];
}

/**
 * 构造发布范围选项。
 *
 * 与 `WorkflowPublishScopeValidator` 同口径：
 * - 租户管理员只能发布仅自己/本租户，不能选择本部门；
 * - 非租户管理员只能发布仅自己/本部门，且本部门需由部门目录确认；
 * - 部门目录加载失败或为空时禁用本部门，并在 `reason` 中说明原因，不静默回退。
 *
 * @param input 角色与部门目录状态
 * @returns 范围选项
 */
export function buildScopeOptions(input: {
  tenantAdmin: boolean;
  departmentsState: DepartmentsState;
  managedDepartmentCount: number;
}): PublishScopeOption[] {
  const departmentReason = (() => {
    if (input.tenantAdmin) {
      return '租户管理员只能发布“仅自己”或“本租户”范围，不能选择本部门。';
    }
    if (input.departmentsState === 'failed') {
      return '部门目录加载失败，暂时无法确认你负责的部门；请重试目录或改选“仅自己”。';
    }
    if (input.departmentsState !== 'loaded') {
      return '正在加载部门目录，暂时无法选择本部门范围。';
    }
    if (input.managedDepartmentCount === 0) {
      return '你不是任何启用部门的负责人，只能发布“仅自己”范围。';
    }
    return undefined;
  })();

  return [
    { value: 'ONLY_SELF', label: RUN_SCOPE_LABELS.ONLY_SELF, disabled: false },
    {
      value: 'DEPARTMENT',
      label: RUN_SCOPE_LABELS.DEPARTMENT,
      disabled: departmentReason != null,
      ...(departmentReason ? { reason: departmentReason } : {}),
    },
    {
      value: 'TENANT',
      label: RUN_SCOPE_LABELS.TENANT,
      disabled: !input.tenantAdmin,
      ...(input.tenantAdmin ? {} : { reason: '只有租户管理员可以发布“本租户”范围。' }),
    },
  ];
}

/**
 * 评估当前发布范围是否可提交。
 *
 * 关键约束（关闭 REV-TASK-0230-001 F-001/F-005）：
 * 1. 重新打开 DEPARTMENT 范围流程时，部门目录未加载不得把范围重置为 ONLY_SELF；
 * 2. 若所选部门正是最近发布版本的部门，即使目录仍在加载/失败也保留该范围，
 *    由二次确认提示“服务端会再次校验”，而不是静默改变范围；
 * 3. 租户管理员选择 DEPARTMENT 时直接阻断，避免必然 400；
 * 4. 目录已加载但所选部门不在当前负责列表且不是最近范围时阻断。
 *
 * @param context 发布范围上下文
 * @returns 是否允许、警告与阻断原因
 */
export function evaluatePublishScope(context: PublishScopeContext): PublishScopeEvaluation {
  const { runScope, tenantAdmin } = context;
  if (runScope === 'ONLY_SELF') {
    return { allowed: true, warning: null, blockedReason: null };
  }
  if (runScope === 'TENANT') {
    return tenantAdmin
      ? { allowed: true, warning: null, blockedReason: null }
      : {
          allowed: false,
          warning: null,
          blockedReason: '只有租户管理员可以发布“本租户”范围。',
        };
  }

  if (tenantAdmin) {
    return {
      allowed: false,
      warning: null,
      blockedReason: '租户管理员不能发布“本部门”范围，请改选“仅自己”或“本租户”。',
    };
  }
  const departmentId = context.departmentId?.trim();
  if (!departmentId) {
    return { allowed: false, warning: null, blockedReason: '请选择你负责的部门。' };
  }
  const latestMatches =
    context.latestVersionScope === 'DEPARTMENT' &&
    context.latestVersionDepartmentId === departmentId;

  if (context.departmentsState === 'loaded') {
    if (context.managedDepartmentIds.includes(departmentId)) {
      return { allowed: true, warning: null, blockedReason: null };
    }
    if (latestMatches) {
      return {
        allowed: true,
        warning:
          '最近发布版本使用的部门不在你当前负责的启用部门中；发布前请确认负责人关系，服务端会再次校验，可能拒绝本次发布。',
        blockedReason: null,
      };
    }
    return {
      allowed: false,
      warning: null,
      blockedReason: '所选部门不在你当前负责的启用部门中，请重新选择。',
    };
  }
  if (context.departmentsState === 'failed') {
    if (latestMatches) {
      return {
        allowed: true,
        warning:
          '部门目录加载失败，无法确认负责人关系；将按最近发布版本的部门提交，服务端会再次校验，可能拒绝本次发布。',
        blockedReason: null,
      };
    }
    return {
      allowed: false,
      warning: null,
      blockedReason: '部门目录加载失败，暂时无法确认部门范围；请重试目录或改选“仅自己”后再发布。',
    };
  }
  if (latestMatches) {
    return {
      allowed: true,
      warning: '部门目录仍在加载；已保留最近发布版本的本部门范围，发布前会再次确认。',
      blockedReason: null,
    };
  }
  return {
    allowed: false,
    warning: null,
    blockedReason: '正在加载部门目录，暂时无法确认本部门范围。',
  };
}

/**
 * 描述发布版本的范围。
 *
 * @param version 发布版本
 * @param departments 已加载的部门（用于显示名称；为空时显示短 ID）
 * @returns 中文范围说明
 */
export function describeVersionScope(
  version: Pick<WorkflowVersion, 'runScope' | 'departmentId'>,
  departments: Array<{ id: string; name: string }>,
): string {
  if (version.runScope !== 'DEPARTMENT') {
    return runScopeLabel(version.runScope);
  }
  const department = departments.find((item) => item.id === version.departmentId);
  return `${runScopeLabel(version.runScope)}（${
    department?.name ?? formatShortId(version.departmentId, 12)
  }）`;
}

/**
 * 描述运行审批聚合与计数。
 *
 * @param run 运行摘要
 * @returns 例如 `已批准 · 待审批 0 · 已批准 1 · 已拒绝 0` 或 `无审批节点`
 */
export function describeRunApproval(run: WorkflowRunSummary): string {
  const counts = approvalCountsText(run);
  if (counts === '无审批节点') {
    return counts;
  }
  return run.approvalStatus ? `${approvalStatusLabel(run.approvalStatus)} · ${counts}` : counts;
}

/**
 * 判断当前账号是否可以取消/重试该运行。
 *
 * 服务端只允许发起人与租户管理员；`workflow.run` 权限由调用方先判断。
 *
 * @param run 运行摘要
 * @param session 会话
 * @returns 是否可操作
 */
export function canOperateRun(
  run: Pick<WorkflowRunSummary, 'requesterUserId'>,
  session: SessionInfo | null | undefined,
): boolean {
  if (!session) {
    return false;
  }
  return run.requesterUserId === session.user.id || isTenantAdmin(session);
}

/**
 * 判断运行状态是否可取消。
 *
 * @param status 运行状态
 * @returns PENDING/RUNNING/WAITING_APPROVAL 可取消
 */
export function canCancelRun(status: WorkflowRunStatus): boolean {
  return isActiveRunStatus(status);
}

/**
 * 返回每个节点最新一次尝试中仍失败的节点。
 *
 * 运行详情包含同一节点的全部尝试；重试只允许最新尝试失败的节点，
 * 因此旧尝试的失败记录不能出现在重试选择器中。
 *
 * @param nodes 节点运行记录
 * @returns 最新尝试失败且可重试的节点
 */
export function latestFailedNodes(nodes: WorkflowNodeRun[]): WorkflowNodeRun[] {
  const latestByNode = new Map<string, WorkflowNodeRun>();
  for (const node of nodes) {
    const existing = latestByNode.get(node.nodeId);
    if (!existing || node.attempt > existing.attempt) {
      latestByNode.set(node.nodeId, node);
    }
  }
  return [...latestByNode.values()].filter((node) => node.status === 'FAILED');
}

/**
 * 判断错误是否表示编排总开关关闭。
 *
 * @param error 任意异常
 * @returns 是否为 503 `RICCON-WF-503`
 */
export function isWorkflowUnavailable(error: unknown): boolean {
  return error instanceof ApiError && error.status === 503 && error.code === 'RICCON-WF-503';
}

/**
 * 描述发布失败原因。
 *
 * 400 不回显服务端英文校验细节，只引导回编辑器修正；其余状态给出可执行下一步。
 *
 * @param error 任意异常
 * @returns 中文说明
 */
export function describePublishFailure(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.status) {
      case 400:
        return '发布校验未通过：请在编辑器执行校验并修正结构、配置、审批人或运行范围后再发布。';
      case 403:
        return '只有流程所有者或租户管理员可以发布该流程。';
      case 404:
        return '流程不存在，或当前账号无权访问该流程。';
      case 409:
        return '流程已被其他操作修改，请刷新后重试。';
      case 503:
        return '编排功能已关闭，暂时不能发布；已有版本仍可查看。';
      default:
        return describeApiError(error, '发布失败，请稍后重试。');
    }
  }
  return '发布失败，请稍后重试。';
}

/**
 * 描述停用/启用失败原因。
 *
 * @param error 任意异常
 * @returns 中文说明
 */
export function describeStatusFailure(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.status) {
      case 403:
        return '只有流程所有者或租户管理员可以停用或启用该流程。';
      case 404:
        return '流程不存在，或当前账号无权访问该流程。';
      case 409:
        return '流程状态已被其他操作改变，请刷新后重试。';
      case 503:
        return '编排功能已关闭，暂时不能变更流程状态。';
      default:
        return describeApiError(error, '操作失败，请稍后重试。');
    }
  }
  return '操作失败，请稍后重试。';
}

/**
 * 描述取消/重试失败原因。
 *
 * @param error 任意异常
 * @param action 操作类型
 * @returns 中文说明
 */
export function describeRunControlFailure(error: unknown, action: 'cancel' | 'retry'): string {
  const operation = action === 'cancel' ? '取消' : '重试';
  if (error instanceof ApiError) {
    switch (error.status) {
      case 400:
        return `${operation}请求参数不正确，请刷新后重试。`;
      case 403:
        return `只有运行发起人或租户管理员可以${operation}该运行。`;
      case 404:
        return '运行不存在，或当前账号无权访问。';
      case 409:
        return action === 'cancel'
          ? '运行已结束或状态已变化，不能取消；请刷新后查看最新状态。'
          : '运行或节点状态已变化（可能已重试、已取消或已结束），请刷新后重试。';
      case 429:
        return '并发运行已达上限，请稍后重试。';
      case 503:
        return '编排功能已关闭，当前不能执行该操作；已有运行记录仍可查看。';
      default:
        return describeApiError(error, `${operation}失败，请稍后重试。`);
    }
  }
  return `${operation}失败，请稍后重试。`;
}

/**
 * 描述创建运行失败原因。
 *
 * @param error 任意异常
 * @returns 中文说明
 */
export function describeCreateRunFailure(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.status) {
      case 400:
        return '运行请求未通过校验：请检查运行输入是否为 JSON 对象、键名合法且不超过 16 个键。';
      case 403:
        return '你没有运行该流程的权限，或当前账号不在该版本的运行范围内。';
      case 404:
        return '流程或所选版本不可见，可能已停用、删除或不在你的运行范围。';
      case 409:
        return '流程状态已变化或所选版本不可运行，请刷新后重试。';
      case 429:
        return '同时运行的流程已达上限，请等待已有运行结束后重试。';
      case 503:
        return '编排功能已关闭，暂时不能创建运行；已有运行记录仍可查看。';
      default:
        return describeApiError(error, '创建运行失败，请稍后重试。');
    }
  }
  return '创建运行失败，请稍后重试。';
}

/** 运行输入解析结果。 */
export interface ParsedRunInputs {
  inputs?: Record<string, unknown>;
  error: string | null;
}

/**
 * 解析运行输入 JSON。
 *
 * 只允许 JSON 对象；键必须匹配后端 `[A-Za-z0-9_-]{1,64}`，最多 16 个键且不超过 64 KiB。
 * 这些限制与 `WorkflowRunRequestParser` 对齐，避免用户提交后才收到 400。
 *
 * @param raw 文本框原文
 * @returns 解析后的输入或错误
 */
export function parseRunInputs(raw: string): ParsedRunInputs {
  const text = raw.trim();
  if (!text) {
    return { inputs: {}, error: null };
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return { inputs: undefined, error: '运行输入必须是合法的 JSON 对象。' };
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return { inputs: undefined, error: '运行输入必须是 JSON 对象，例如 {"topic":"市场"}。' };
  }
  const entries = Object.entries(parsed as Record<string, unknown>);
  if (entries.length > RUN_INPUT_MAX_KEYS) {
    return { inputs: undefined, error: '运行输入最多 16 个键。' };
  }
  for (const [key] of entries) {
    if (!RUN_INPUT_KEY_PATTERN.test(key)) {
      return {
        inputs: undefined,
        error: '运行输入的键只能包含字母、数字、下划线或短横线，长度 1–64。',
      };
    }
  }
  const serialized = JSON.stringify(parsed);
  if (new TextEncoder().encode(serialized).length > RUN_INPUT_MAX_BYTES) {
    return { inputs: undefined, error: '运行输入不能超过 64 KiB。' };
  }
  return { inputs: parsed as Record<string, unknown>, error: null };
}

/**
 * 返回版本下拉的展示文案。
 *
 * @param version 发布版本
 * @param departments 部门目录
 * @returns 例如 `v2 · 本部门（销售部） · 2026-11-25 09:00:00 +08:00`
 */
export function versionOptionLabel(
  version: WorkflowVersion,
  departments: Array<{ id: string; name: string }>,
): string {
  return `v${version.versionNo} · ${describeVersionScope(version, departments)} · ${formatOffsetDateTime(
    version.publishedAt,
  )}`;
}

/**
 * 返回运行卡片的耗时说明。
 *
 * @param run 运行摘要
 * @returns 耗时或占位符
 */
export function runDurationText(run: WorkflowRunSummary): string {
  return formatDuration(run.durationMs);
}

/**
 * 返回运行是否仍在推进；页面据此决定是否展示取消入口。
 *
 * @param status 运行状态
 * @returns 是否活跃
 */
export function runIsActive(status: WorkflowRunStatus): boolean {
  return isActiveRunStatus(status);
}

/**
 * 返回运行是否已结束；页面据此决定是否展示重试入口。
 *
 * @param status 运行状态
 * @returns 是否终态
 */
export function runIsTerminal(status: WorkflowRunStatus): boolean {
  return isTerminalRunStatus(status);
}

/** 运行状态颜色；页面与测试复用同一映射。 */
export { RUN_STATUS_COLORS, RUN_STATUS_LABELS };
