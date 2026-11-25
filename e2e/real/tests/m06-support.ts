import AxeBuilder from '@axe-core/playwright';
import { expect, type Locator, type Page } from '@playwright/test';
import { readSeed, type RealSeed } from './real-session';

/**
 * M06 真实栈 E2E 共享工具。
 *
 * <p>测试通过浏览器同源 `page.request` 准备流程/运行/审批夹具，再用真实 UI 验证用户可见结果；
 * 准备请求与 UI 使用同一 Cookie/CSRF 语义，不绕过认证、权限、租户过滤或状态机。</p>
 */

/** M06 路由；与 `apps/web/config/routes.ts` 一致。 */
export const M06_ROUTES = {
  list: '/workflows/list',
  editor: (workflowId: string) => `/workflows/${encodeURIComponent(workflowId)}/edit`,
  templates: '/workflows/templates',
  approvals: '/workflows/approvals',
  approval: (approvalId: string) => `/workflows/approvals/${encodeURIComponent(approvalId)}`,
  runs: '/workflows/runs',
  run: (runId: string) => `/workflows/runs/${encodeURIComponent(runId)}`,
} as const;

/** 流程状态。 */
export type WorkflowStatus = 'ACTIVE' | 'DISABLED';

/** 运行范围；与契约一致。 */
export type WorkflowRunScope = 'ONLY_SELF' | 'DEPARTMENT' | 'TENANT';

/** 图节点；只声明 E2E 使用的字段。 */
export interface GraphNode {
  id: string;
  type: 'START' | 'END' | 'RETRIEVAL' | 'MODEL' | 'MCP_TOOL' | 'APPROVAL' | 'CONDITION';
  name: string;
  config: Record<string, unknown>;
  position?: { x: number; y: number };
}

/** 图连线。 */
export interface GraphEdge {
  id: string;
  source: string;
  target: string;
  branch?: 'TRUE' | 'FALSE' | null;
}

/** 流程图。 */
export interface WorkflowGraph {
  schemaVersion: 1;
  nodes: GraphNode[];
  edges: GraphEdge[];
}

/** 流程详情里 E2E 关心的字段。 */
export interface WorkflowDetail {
  id: string;
  name: string;
  ownerUserId: string;
  status: WorkflowStatus;
  draftVersion: number;
  publishedVersionId?: string | null;
  publishedVersionNo?: number | null;
  hasUnpublishedChanges: boolean;
  latestVersion?: {
    id: string;
    versionNo: number;
    runScope: WorkflowRunScope;
    departmentId?: string | null;
    publishedBy: string;
    publishedAt: string;
    nodeCount: number;
  } | null;
  draftGraph?: WorkflowGraph | null;
  canEdit: boolean;
  canPublish: boolean;
  canRun: boolean;
}

/** 运行详情里 E2E 关心的字段。 */
export interface RunDetail {
  id: string;
  workflowId: string;
  workflowName: string;
  workflowVersionId: string;
  versionNo: number;
  requesterUserId: string;
  status: string;
  currentNodeId?: string | null;
  approvalStatus?: string | null;
  errorCode?: string | null;
  errorMessage?: string | null;
  resultStatus?: string | null;
  createdAt: string;
  updatedAt: string;
  retentionExpiresAt: string;
  pendingApprovalCount: number;
  approvedApprovalCount: number;
  rejectedApprovalCount: number;
  nodes: Array<{
    nodeId: string;
    nodeName: string;
    nodeType: string;
    attempt: number;
    status: string;
    inputSummary?: string | null;
    outputSummary?: string | null;
    errorCode?: string | null;
    startedAt?: string | null;
    finishedAt?: string | null;
    durationMs?: number | null;
  }>;
  approvals: Array<{
    id: string;
    nodeName: string;
    status: string;
    decidedBy?: string | null;
    decisionComment?: string | null;
  }>;
  citations: Array<{ sourceId: string; sourceStatus?: string | null }>;
  result?: { text?: string | null; truncated?: boolean } | null;
}

/** 审批列表项。 */
export interface ApprovalItem {
  id: string;
  workflowId: string;
  workflowName: string;
  nodeName: string;
  status: string;
  requestedBy: string;
  createdAt: string;
  assigneeUserIds?: string[];
}

/** 分页响应。 */
export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

/** API 响应。 */
export interface ApiResult<T> {
  status: number;
  body: T;
  text: string;
}

/** 读取 seed 并返回强类型快照。 */
export function seed(): RealSeed {
  return readSeed();
}

/** 读取真实栈 Web 基址。 */
export function webBase(): string {
  return process.env.REAL_WEB_BASE ?? 'http://127.0.0.1:8092';
}

/**
 * 以指定账号登录；不预置 Cookie，走真实登录表单。
 *
 * @param page Playwright 页面
 * @param username 账号
 * @param password 密码
 * @param tenantCode 租户代码；默认 seed 的 Alpha 租户
 */
export async function loginAs(
  page: Page,
  username: string,
  password: string,
  tenantCode = seed().tenants.alpha,
): Promise<void> {
  await page.goto('/login');
  await page.getByPlaceholder('例如 acme').fill(tenantCode);
  await page.getByPlaceholder('请输入账号').fill(username);
  await page.getByPlaceholder('请输入密码').fill(password);
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect(page).toHaveURL(/\/chat$/);
  await expect(page.getByRole('heading', { name: 'Riccon 工作空间' })).toBeVisible();
}

/** 读取当前会话 CSRF 令牌。 */
export async function csrfToken(page: Page): Promise<string> {
  const response = await page.request.get('/api/v1/auth/csrf');
  expect(response.ok(), '读取 CSRF 失败').toBe(true);
  return ((await response.json()) as { token: string }).token;
}

/**
 * 通过真实登录接口建立会话；用于平台管理员等无法进入员工端工作空间的角色。
 *
 * <p>与 UI 登录走同一 `/api/v1/auth/login`、CSRF 与会话 Cookie；后续请求仍由真实安全链鉴权。</p>
 *
 * @param page Playwright 页面（使用同一 context 的 Cookie）
 * @param tenantCode 租户代码
 * @param username 账号
 * @param password 密码
 */
export async function loginApi(
  page: Page,
  tenantCode: string,
  username: string,
  password: string,
): Promise<void> {
  const token = await csrfToken(page);
  const response = await page.request.post('/api/v1/auth/login', {
    headers: { 'X-CSRF-TOKEN': token, 'Content-Type': 'application/json' },
    data: { tenantCode, username, password },
  });
  expect(response.status(), `API 登录失败：${await response.text()}`).toBe(200);
}

/**
 * 通过浏览器同源请求调用 API；保留 Cookie 与 CSRF 语义。
 *
 * @param page Playwright 页面
 * @param method HTTP 方法
 * @param path API 路径
 * @param body 可选 JSON 请求体
 * @param headers 额外请求头
 * @returns 状态、JSON 与原始文本
 */
export async function api<T = unknown>(
  page: Page,
  method: 'GET' | 'POST' | 'PUT' | 'DELETE',
  path: string,
  body?: unknown,
  headers: Record<string, string> = {},
): Promise<ApiResult<T>> {
  const requestHeaders: Record<string, string> = {
    Accept: 'application/json',
    ...headers,
  };
  if (method !== 'GET') {
    requestHeaders['X-CSRF-TOKEN'] = await csrfToken(page);
    requestHeaders['Content-Type'] = 'application/json';
  }
  const response = await page.request.fetch(path, {
    method,
    headers: requestHeaders,
    data: body === undefined ? undefined : body,
  });
  const text = await response.text();
  let parsed: unknown = null;
  if (text) {
    try {
      parsed = JSON.parse(text);
    } catch {
      parsed = text;
    }
  }
  return { status: response.status(), body: parsed as T, text };
}

/** 断言 2xx；失败时输出响应体。 */
export function expectOk<T>(result: ApiResult<T>, label: string): T {
  expect(result.status, `${label}: ${result.text}`).toBeGreaterThanOrEqual(200);
  expect(result.status, `${label}: ${result.text}`).toBeLessThan(300);
  return result.body;
}

/** 创建流程草稿。 */
export async function createWorkflow(
  page: Page,
  name: string,
  templateId?: string,
): Promise<WorkflowDetail> {
  const result = await api<WorkflowDetail>(
    page,
    'POST',
    '/api/v1/workflows',
    {
      name,
      ...(templateId ? { templateId } : {}),
    },
    { 'Idempotency-Key': `e2e-${Date.now()}-${Math.random().toString(16).slice(2)}` },
  );
  expect(result.status, `创建流程 ${name}: ${result.text}`).toBe(201);
  return result.body;
}

/** 读取流程详情。 */
export async function getWorkflow(page: Page, workflowId: string): Promise<WorkflowDetail> {
  return expectOk(
    await api<WorkflowDetail>(page, 'GET', `/api/v1/workflows/${workflowId}`),
    `读取流程 ${workflowId}`,
  );
}

/** 保存草稿；返回详情。 */
export async function saveDraft(
  page: Page,
  workflowId: string,
  version: number,
  graph: WorkflowGraph,
  name?: string,
): Promise<WorkflowDetail> {
  const result = await api<WorkflowDetail>(page, 'PUT', `/api/v1/workflows/${workflowId}/draft`, {
    version,
    ...(name ? { name } : {}),
    graph,
  });
  expect(result.status, `保存草稿 ${workflowId}: ${result.text}`).toBe(200);
  return result.body;
}

/** 校验草稿；返回 issues。 */
export async function validateWorkflow(
  page: Page,
  workflowId: string,
  scope: { runScope?: WorkflowRunScope; departmentId?: string } = {},
): Promise<{ valid: boolean; issues: Array<{ code: string; nodeId?: string | null }> }> {
  const params = new URLSearchParams();
  if (scope.runScope) {
    params.set('runScope', scope.runScope);
  }
  if (scope.departmentId) {
    params.set('departmentId', scope.departmentId);
  }
  const query = params.toString() ? `?${params.toString()}` : '';
  return expectOk(
    await api<{ valid: boolean; issues: Array<{ code: string; nodeId?: string | null }> }>(
      page,
      'POST',
      `/api/v1/workflows/${workflowId}/validate${query}`,
    ),
    `校验流程 ${workflowId}`,
  );
}

/** 发布流程。 */
export async function publishWorkflow(
  page: Page,
  workflowId: string,
  runScope: WorkflowRunScope,
  departmentId?: string,
): Promise<WorkflowDetail['latestVersion']> {
  const result = await api<{
    id: string;
    versionNo: number;
    runScope: WorkflowRunScope;
    departmentId?: string | null;
    publishedBy: string;
    publishedAt: string;
    nodeCount: number;
  }>(
    page,
    'POST',
    `/api/v1/workflows/${workflowId}/publish`,
    {
      runScope,
      ...(departmentId ? { departmentId } : {}),
    },
    { 'Idempotency-Key': `e2e-publish-${Date.now()}-${Math.random().toString(16).slice(2)}` },
  );
  expect(result.status, `发布流程 ${workflowId}: ${result.text}`).toBe(201);
  return result.body;
}

/** 创建运行。 */
export async function createRun(
  page: Page,
  workflowId: string,
  inputs: Record<string, unknown>,
  versionId?: string,
): Promise<{ id: string; status: string; versionNo: number }> {
  const result = await api<{ id: string; status: string; versionNo: number }>(
    page,
    'POST',
    `/api/v1/workflows/${workflowId}/runs`,
    { ...(versionId ? { versionId } : {}), inputs },
    { 'Idempotency-Key': `e2e-run-${Date.now()}-${Math.random().toString(16).slice(2)}` },
  );
  expect(result.status, `创建运行 ${workflowId}: ${result.text}`).toBe(201);
  return result.body;
}

/** 读取运行详情。 */
export async function getRun(page: Page, runId: string): Promise<RunDetail> {
  return expectOk(
    await api<RunDetail>(page, 'GET', `/api/v1/workflow-runs/${runId}`),
    `读取运行 ${runId}`,
  );
}

/**
 * 轮询运行直到满足条件。
 *
 * @param page Playwright 页面
 * @param runId 运行 ID
 * @param predicate 完成条件
 * @param timeoutMs 超时；默认 120 秒
 * @returns 最终详情
 */
export async function waitForRun(
  page: Page,
  runId: string,
  predicate: (detail: RunDetail) => boolean,
  timeoutMs = 120_000,
): Promise<RunDetail> {
  const deadline = Date.now() + timeoutMs;
  let last: RunDetail | null = null;
  while (Date.now() < deadline) {
    try {
      last = await getRun(page, runId);
      if (predicate(last)) {
        return last;
      }
    } catch {
      // 运行详情短暂 404 不应中断轮询；最终超时会给出最后状态。
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(`等待运行 ${runId} 超时；最后状态=${JSON.stringify(last)}`);
}

/** 列出审批待办。 */
export async function listApprovals(page: Page): Promise<PageResponse<ApprovalItem>> {
  return expectOk(
    await api<PageResponse<ApprovalItem>>(
      page,
      'GET',
      '/api/v1/workflow-approvals?page=1&size=100',
    ),
    '读取审批待办',
  );
}

/** 读取审批详情。 */
export async function getApproval(page: Page, approvalId: string): Promise<ApprovalItem> {
  return expectOk(
    await api<ApprovalItem>(page, 'GET', `/api/v1/workflow-approvals/${approvalId}`),
    `读取审批 ${approvalId}`,
  );
}

/** 提交审批决定。 */
export async function decideApproval(
  page: Page,
  approvalId: string,
  decision: 'APPROVE' | 'REJECT',
  comment?: string,
): Promise<ApiResult<ApprovalItem>> {
  return api<ApprovalItem>(
    page,
    'POST',
    `/api/v1/workflow-approvals/${approvalId}/decision`,
    { decision, ...(comment === undefined ? {} : { comment }) },
    { 'Idempotency-Key': `e2e-decision-${Date.now()}-${Math.random().toString(16).slice(2)}` },
  );
}

/** 从内置模板复制流程；模板 ID 固定为 V15 种子。 */
export async function copyBuiltInTemplate(
  page: Page,
  templateId = '01990000-0000-7000-8000-000000000601',
): Promise<WorkflowDetail> {
  const result = await api<WorkflowDetail>(
    page,
    'POST',
    `/api/v1/workflow-templates/${templateId}/copy`,
    {},
    { 'Idempotency-Key': `e2e-copy-${Date.now()}-${Math.random().toString(16).slice(2)}` },
  );
  expect(result.status, `复制模板: ${result.text}`).toBe(201);
  return result.body;
}

/** 构造五节点审批图：开始 → 检索 → 模型 → 审批 → 结束。 */
export function approvalGraph(approverUserId: string): WorkflowGraph {
  return {
    schemaVersion: 1,
    nodes: [
      { id: 'start', type: 'START', name: '开始', config: {} },
      {
        id: 'retrieval',
        type: 'RETRIEVAL',
        name: '检索知识库',
        config: { queryTemplate: '{{input.topic}}', topK: 5 },
      },
      {
        id: 'model',
        type: 'MODEL',
        name: '生成结果',
        config: { promptTemplate: '根据检索结果整理：{{input.topic}}', outputKind: 'TEXT' },
      },
      {
        id: 'approval',
        type: 'APPROVAL',
        name: '人工审批',
        config: { assigneeType: 'USER', assigneeUserIds: [approverUserId], actionKind: 'CONTINUE' },
      },
      {
        id: 'end',
        type: 'END',
        name: '结束',
        config: { resultFrom: 'model', resultTitle: 'E2E 结果' },
      },
    ],
    edges: [
      { id: 'e1', source: 'start', target: 'retrieval' },
      { id: 'e2', source: 'retrieval', target: 'model' },
      { id: 'e3', source: 'model', target: 'approval' },
      { id: 'e4', source: 'approval', target: 'end' },
    ],
  };
}

/** 构造无审批简单图：开始 → 检索 → 模型 → 结束。 */
export function simpleGraph(): WorkflowGraph {
  return {
    schemaVersion: 1,
    nodes: [
      { id: 'start', type: 'START', name: '开始', config: {} },
      {
        id: 'retrieval',
        type: 'RETRIEVAL',
        name: '检索知识库',
        config: { queryTemplate: '{{input.topic}}', topK: 5 },
      },
      {
        id: 'model',
        type: 'MODEL',
        name: '生成结果',
        config: { promptTemplate: '整理主题：{{input.topic}}', outputKind: 'TEXT' },
      },
      {
        id: 'end',
        type: 'END',
        name: '结束',
        config: { resultFrom: 'model', resultTitle: 'E2E 结果' },
      },
    ],
    edges: [
      { id: 'e1', source: 'start', target: 'retrieval' },
      { id: 'e2', source: 'retrieval', target: 'model' },
      { id: 'e3', source: 'model', target: 'end' },
    ],
  };
}

/** 构造条件图：检索命中数 ≥1 走命中分支，否则走未命中分支（两个独立 END）。 */
export function conditionGraph(): WorkflowGraph {
  return {
    schemaVersion: 1,
    nodes: [
      { id: 'start', type: 'START', name: '开始', config: {} },
      {
        id: 'retrieval',
        type: 'RETRIEVAL',
        name: '检索知识库',
        config: { queryTemplate: '{{input.topic}}', topK: 5 },
      },
      {
        id: 'condition',
        type: 'CONDITION',
        name: '命中判断',
        config: {
          condition: {
            left: {
              source: 'NODE_OUTPUT',
              nodeId: 'retrieval',
              path: 'hitCount',
              valueType: 'NUMBER',
            },
            operator: 'GTE',
            right: { valueType: 'NUMBER', value: 1 },
          },
        },
      },
      {
        id: 'modelTrue',
        type: 'MODEL',
        name: '命中分支模型',
        config: { promptTemplate: '命中：{{input.topic}}', outputKind: 'TEXT' },
      },
      {
        id: 'modelFalse',
        type: 'MODEL',
        name: '未命中分支模型',
        config: { promptTemplate: '未命中：{{input.topic}}', outputKind: 'TEXT' },
      },
      {
        id: 'endTrue',
        type: 'END',
        name: '命中结束',
        config: { resultFrom: 'modelTrue', resultTitle: '命中结果' },
      },
      {
        id: 'endFalse',
        type: 'END',
        name: '未命中结束',
        config: { resultFrom: 'modelFalse', resultTitle: '未命中结果' },
      },
    ],
    edges: [
      { id: 'e1', source: 'start', target: 'retrieval' },
      { id: 'e2', source: 'retrieval', target: 'condition' },
      { id: 'e3', source: 'condition', target: 'modelTrue', branch: 'TRUE' },
      { id: 'e4', source: 'modelTrue', target: 'endTrue' },
      { id: 'e5', source: 'condition', target: 'modelFalse', branch: 'FALSE' },
      { id: 'e6', source: 'modelFalse', target: 'endFalse' },
    ],
  };
}

/**
 * 构造可恢复失败图：检索 → 模型 B（前 3 次模型调用失败）→ 模型 C → 结束。
 *
 * <p>提示词包含假模型的一次性故障标记；重试 B 时第 4 次模型调用恢复，用于验证上游不重跑、
 * 下游继续执行。</p>
 */
export function retryGraph(): WorkflowGraph {
  return {
    schemaVersion: 1,
    nodes: [
      { id: 'start', type: 'START', name: '开始', config: {} },
      {
        id: 'retrieval',
        type: 'RETRIEVAL',
        name: '检索知识库',
        config: { queryTemplate: '{{input.topic}}', topK: 5 },
      },
      {
        id: 'draft',
        type: 'MODEL',
        name: '失败模型',
        config: { promptTemplate: '__WF_FAIL_3__ 生成草稿：{{input.topic}}', outputKind: 'TEXT' },
      },
      {
        id: 'report',
        type: 'MODEL',
        name: '后续模型',
        config: { promptTemplate: '继续生成报告：{{input.topic}}', outputKind: 'TEXT' },
      },
      {
        id: 'end',
        type: 'END',
        name: '结束',
        config: { resultFrom: 'report', resultTitle: '重试结果' },
      },
    ],
    edges: [
      { id: 'e1', source: 'start', target: 'retrieval' },
      { id: 'e2', source: 'retrieval', target: 'draft' },
      { id: 'e3', source: 'draft', target: 'report' },
      { id: 'e4', source: 'report', target: 'end' },
    ],
  };
}

/** 构造运行时条件求值失败图：RUN_INPUT topic 声明为 NUMBER，但运行输入是字符串。 */
export function invalidConditionGraph(): WorkflowGraph {
  return {
    schemaVersion: 1,
    nodes: [
      { id: 'start', type: 'START', name: '开始', config: {} },
      {
        id: 'condition',
        type: 'CONDITION',
        name: '类型不符条件',
        config: {
          condition: {
            left: { source: 'RUN_INPUT', path: 'topic', valueType: 'NUMBER' },
            operator: 'GT',
            right: { valueType: 'NUMBER', value: 3 },
          },
        },
      },
      {
        id: 'modelTrue',
        type: 'MODEL',
        name: '不应执行真分支',
        config: { promptTemplate: '不应执行', outputKind: 'TEXT' },
      },
      {
        id: 'modelFalse',
        type: 'MODEL',
        name: '不应执行假分支',
        config: { promptTemplate: '不应执行', outputKind: 'TEXT' },
      },
      {
        id: 'endTrue',
        type: 'END',
        name: '真分支结束',
        config: { resultFrom: 'modelTrue', resultTitle: '失败结果' },
      },
      {
        id: 'endFalse',
        type: 'END',
        name: '假分支结束',
        config: { resultFrom: 'modelFalse', resultTitle: '失败结果' },
      },
    ],
    edges: [
      { id: 'e1', source: 'start', target: 'condition' },
      { id: 'e2', source: 'condition', target: 'modelTrue', branch: 'TRUE' },
      { id: 'e3', source: 'modelTrue', target: 'endTrue' },
      { id: 'e4', source: 'condition', target: 'modelFalse', branch: 'FALSE' },
      { id: 'e5', source: 'modelFalse', target: 'endFalse' },
    ],
  };
}

/** 构造写工具审批图：开始 → 审批（角色）→ MCP 工具 → 结束。 */
export function toolGraph(toolId: string, approverRole: string): WorkflowGraph {
  return {
    schemaVersion: 1,
    nodes: [
      { id: 'start', type: 'START', name: '开始', config: {} },
      {
        id: 'approval',
        type: 'APPROVAL',
        name: '审批取数',
        config: {
          assigneeType: 'ROLE',
          assigneeRole: approverRole,
          actionKind: 'MCP_TOOL',
          targetNodeKey: 'tool',
        },
      },
      {
        id: 'tool',
        type: 'MCP_TOOL',
        name: '取数工具',
        config: { toolId, argsTemplate: { query: '{{input.topic}}' } },
      },
      {
        id: 'end',
        type: 'END',
        name: '结束',
        config: { resultFrom: 'tool', resultTitle: '工具结果' },
      },
    ],
    edges: [
      { id: 'e1', source: 'start', target: 'approval' },
      { id: 'e2', source: 'approval', target: 'tool' },
      { id: 'e3', source: 'tool', target: 'end' },
    ],
  };
}

/**
 * 断言页面 375px 无横向滚动。
 *
 * @param page Playwright 页面
 */
export async function expectNoHorizontalScroll(page: Page): Promise<void> {
  const metrics = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }));
  expect(metrics.scrollWidth, '页面出现横向滚动').toBeLessThanOrEqual(metrics.clientWidth);
}

/**
 * 断言当前页面 axe 无 serious/critical。
 *
 * @param page Playwright 页面
 * @param label 场景标签
 */
export async function expectAxeClean(page: Page, label: string): Promise<void> {
  const result = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze();
  const violations = result.violations.filter((violation) =>
    ['serious', 'critical'].includes(violation.impact ?? ''),
  );
  expect(
    violations.map(
      (violation) =>
        `${label}: ${violation.id} (${violation.impact}) ${violation.nodes
          .map((node) => node.target.join(' '))
          .join(' | ')}`,
    ),
  ).toEqual([]);
}

/**
 * 鼠标选择 AntD Select 选项。
 *
 * @param page Playwright 页面
 * @param trigger 触发器
 * @param optionText 选项文本
 */
export async function selectOption(
  page: Page,
  trigger: Locator,
  optionText: string | RegExp,
): Promise<void> {
  await trigger.click();
  const dropdown = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').last();
  await expect(dropdown).toBeVisible();
  const option = dropdown
    .locator('.ant-select-item-option')
    .filter({ hasText: optionText })
    .first();
  await expect(option).toBeVisible();
  await option.click();
}

/**
 * 键盘导航到目标元素：从当前焦点开始按 Tab 直到聚焦目标。
 *
 * <p>仅使用 Tab 键移动焦点；调用方负责用 Enter/Space/字符键操作。找不到目标时失败，
 * 不静默跳过，保证“键盘可用”不是靠程序化 focus 伪造。</p>
 *
 * @param page Playwright 页面
 * @param target 目标元素
 * @param maxTabs 最大 Tab 次数；默认 250
 */
export async function tabTo(page: Page, target: Locator, maxTabs = 250): Promise<void> {
  for (let index = 0; index < maxTabs; index += 1) {
    const focused = await page.evaluate(
      (expected) => {
        const active = document.activeElement as HTMLElement | null;
        if (!active) {
          return false;
        }
        return active === expected;
      },
      await target.elementHandle(),
    );
    if (focused) {
      return;
    }
    await page.keyboard.press('Tab');
  }
  throw new Error('键盘导航在限定次数内未到达目标元素');
}

/**
 * 轮询等待文本出现（用于异步通知/列表刷新）。
 *
 * @param locator 目标定位器
 */
export async function waitForVisible(locator: Locator): Promise<void> {
  await expect(locator).toBeVisible({ timeout: 30_000 });
}

/** 按工作流名在历史页选择流程。 */
export async function selectWorkflowInHistory(page: Page, workflowName: string): Promise<void> {
  const trigger = page.getByLabel('选择流程');
  await selectOption(page, trigger, workflowName);
  await expect(page.getByTestId('workflow-release-panel')).toBeVisible();
}

/** 在历史页点击“运行流程”并填写输入。 */
export async function runWorkflowViaUi(
  page: Page,
  input: Record<string, unknown>,
): Promise<string> {
  await page.getByRole('button', { name: '运行流程' }).click();
  const modal = page.locator('.ant-modal').filter({ hasText: '运行流程' }).last();
  await expect(modal).toBeVisible();
  await modal.getByLabel('运行输入 JSON').fill(JSON.stringify(input));
  await modal.getByRole('button', { name: '提交运行' }).click();
  await page.waitForURL(/\/workflows\/runs\/[0-9a-f-]{36}$/);
  const match = page.url().match(/\/workflows\/runs\/([0-9a-f-]{36})$/);
  if (!match) {
    throw new Error(`运行后未进入详情页：${page.url()}`);
  }
  return match[1];
}
