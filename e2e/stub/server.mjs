/**
 * Riccon E2E stub API。
 *
 * 定位：端到端测试专用的独立 HTTP 服务，**不是**应用内 mock。它不进入 Web 构建产物，
 * 只由 Playwright 的 webServer 启动，通过开发代理 `/api` 被浏览器访问，从而同时验证：
 * - 真实 `ApiClient` 的 Cookie/CSRF/ProblemDetail/分页/幂等键路径；
 * - 真实 `fetch` + `ReadableStream` 的 SSE 增量、停止与断线；
 * - multipart 上传、解析轮询、审批状态机与产物预签名下载。
 *
 * 边界：实现 M02 J1/J2、M03/M10/M15 页面与 M04 模型路由/key/配额/成本页面使用的 OpenAPI 子集；
 * 数据全部为合成内容，存活于进程内存。M04 数据由 `POST /__stub__/m04-seed` 填充，空态用
 * `{"empty":true}`；错误态用 `POST /__stub__/scenario` 的 `m04-*-500` / `m04-switches-env-override`。
 * 测试之间通过 `POST /__stub__/reset` 重置；控制端点不属于产品契约。
 *
 * 运行：`node e2e/stub/server.mjs`（Node 内置模块，无第三方依赖）。
 */
import { randomUUID } from 'node:crypto';
import http from 'node:http';

/** 监听地址与端口；Playwright 通过 STUB_PORT 指定，默认与 Web 端口区分。 */
const PORT = Number(process.env.STUB_PORT ?? 8124);
const HOST = process.env.STUB_HOST ?? '127.0.0.1';

/** 会话 Cookie 名；与真实 API 的 HttpOnly 会话语义一致，stub 仅用于 E2E。 */
const SESSION_COOKIE = 'RICCON_STUB_SESSION';

/** E2E 演示凭据；与 Playwright 用例共享，不含真实账号。 */
const DEMO_CREDENTIALS = {
  tenantCode: 'acme',
  username: 'demo',
  password: 'demo1234',
};

/** 文件约束与格式表，与 M02 契约和前端校验一致。 */
const MAX_FILE_BYTES = 200 * 1024 * 1024;
const MAX_FILES_PER_CONVERSATION = 5;
const EXTENSION_FORMATS = {
  '.pdf': 'PDF',
  '.docx': 'DOCX',
  '.xlsx': 'XLSX',
  '.png': 'PNG',
  '.jpg': 'JPEG',
  '.jpeg': 'JPEG',
};

/** 解析推进时间：400ms 进入 RUNNING，900ms 进入终态。 */
const PARSE_RUNNING_AFTER_MS = 400;
const PARSE_FINISH_AFTER_MS = 900;

/** SSE 增量间隔；E2E 有足够时间在流结束前点击停止。 */
const DELTA_INTERVAL_MS = 350;

/** J2 阶段推进时间；与前端 900ms 轮询配合，1–2 秒内可见阶段切换。 */
const RESEARCH_RETRIEVAL_MS = 500;
const RESEARCH_TOOL_MS = 600;
const RESEARCH_GENERATION_MS = 800;

/** 合成 ID；只用于 E2E 断言，不对应任何真实对象。 */
const DEMO_WORKSPACE_ID = '11111111-1111-4111-8111-111111111111';
const DEMO_USER_ID = '22222222-2222-4222-8222-222222222222';
const DEMO_TENANT_ID = 'tenant-acme';
const DEMO_MCP_TOOL_ID = '44444444-4444-4444-8444-444444444444';
const DEMO_KNOWLEDGE_ID_ONE = '55555555-5555-4555-8555-555555555555';
const DEMO_KNOWLEDGE_ID_TWO = '66666666-6666-4666-8666-666666666666';
const DEMO_MODEL_ID = '33333333-3333-4333-8333-333333333333';

/** M03 stub 账号与租户；只用于 E2E 合成数据，不对应真实身份。 */
const M03_TENANT_ACME = 'tenant-acme';
const M03_TENANT_BETA = 'tenant-beta';
const M03_TENANT_SUSPENDED = 'tenant-suspended';
const M03_PLATFORM_TENANT = 'platform';
const M03_ADMIN_USER_ID = '77777777-7777-4777-8777-777777777777';
const M03_MANAGER_USER_ID = '88888888-8888-4888-8888-888888888888';
const M03_OUTSIDER_USER_ID = '99999999-9999-4999-8999-999999999999';
const M03_ADMIN_USERNAME = 'admin';
const M03_MANAGER_USERNAME = 'manager';
const M03_OUTSIDER_USERNAME = 'outsider';
const M03_PLATFORM_USERNAME = 'platform-admin';
const M03_DEMO_DISPLAY_NAME = '演示员工';
const M03_MEMBER_USER_ID = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';
const M03_MEMBER_USERNAME = 'wangmin';
const M03_MEMBER_DISPLAY_NAME = '王敏';
const M03_MANAGER_DISPLAY_NAME = '李强';
const M03_OUTSIDER_DISPLAY_NAME = '赵敏';
const M03_ADMIN_DISPLAY_NAME = '王芳';
const M03_PLATFORM_DISPLAY_NAME = '赵蕾';
const M03_SALES_DEPARTMENT_ID = 'dept-sales';
const M03_MARKETING_DEPARTMENT_ID = 'dept-marketing';
const M03_SALES_WORKSPACE_ID = 'ws-sales-project';
const M03_ARCHIVED_DEPARTMENT_ID = 'dept-archived';
const M03_TENANT_WORKSPACE_ID = 'ws-legacy-tenant';
const M03_DEPT_RESEARCH_TASK_ID = 'research-dept-1';
const M03_UNASSIGNED_USER_ID = 'f1f1f1f1-f1f1-4f1f-8f1f-f1f1f1f1f1f1';
const M03_DISABLED_USER_ID = 'f2f2f2f2-f2f2-4f2f-8f2f-f2f2f2f2f2f2';

/** M15 来源 IdP 列表账号；与 M10 后端脱敏展示字段对齐，只用于 E2E 合成数据。 */
const M15_OIDC_USER_A_ID = 'a1a1a1a1-a1a1-4a1a-8a1a-a1a1a1a1a1a1';
const M15_OIDC_USER_B_ID = 'b1b1b1b1-b1b1-4b1b-8b1b-b1b1b1b1b1b1';
const M15_OIDC_USER_A_NAME = 'oidc-alpha';
const M15_OIDC_USER_B_NAME = 'oidc-beta';
const M15_OIDC_USER_A_DISPLAY_NAME = '甲企业员工';
const M15_OIDC_USER_B_DISPLAY_NAME = '乙企业员工';
const M15_PROVIDER_A = 'Acme 企业 IdP';
const M15_PROVIDER_B = 'idp-b.example.com:8443';
const M03_DEPT_CONVERSATION_ID = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd';
const M03_DEPT_FILE_ID = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee';
const M03_SOURCE_UNAVAILABLE_FILE_ID = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const M03_SOURCE_UNAVAILABLE_FILE_NAME = '销售部市场分析.pdf';

/** M04 合成目录 UUID；E2E 只依赖这些稳定 ID。 */
const M04_PROVIDER_PRIMARY = '91919191-9191-4191-8191-000000000001';
const M04_PROVIDER_SECONDARY = '91919191-9191-4191-8191-000000000002';
const M04_PROVIDER_DISALLOWED = '91919191-9191-4191-8191-000000000003';
const M04_MODEL_PRIMARY = '92929292-9292-4292-8292-000000000001';
const M04_MODEL_SECONDARY = '92929292-9292-4292-8292-000000000002';
const M04_MODEL_DISABLED = '92929292-9292-4292-8292-000000000003';
const M04_MODEL_UNPRICED = '92929292-9292-4292-8292-000000000004';

/** 配额重置时间；固定值让页面快照稳定（Asia/Shanghai，带偏移）。 */
const M04_DAILY_RESET = '2026-11-22T00:00:00+08:00';
const M04_MONTHLY_RESET = '2026-12-01T00:00:00+08:00';

/** 成本免责声明；与 OpenAPI const 逐字一致。 */
const M04_COST_DISCLAIMER = '成本为按调用时价格快照估算，不是供应商账单。';

/** M05 知识库合成文档 ID；E2E 只依赖这些稳定 UUID。 */
const M05_DOC_EXCEL_MULTI = 'b5b5b5b5-b5b5-45b5-85b5-000000000001';
const M05_DOC_EXCEL_TRUNCATED = 'b5b5b5b5-b5b5-45b5-85b5-000000000002';
const M05_DOC_EXCEL_CORRUPT = 'b5b5b5b5-b5b5-45b5-85b5-000000000003';
const M05_DOC_OCR_OK = 'b5b5b5b5-b5b5-45b5-85b5-000000000004';
const M05_DOC_OCR_LOW = 'b5b5b5b5-b5b5-45b5-85b5-000000000005';
const M05_DOC_OCR_PARTIAL = 'b5b5b5b5-b5b5-45b5-85b5-000000000006';
const M05_DOC_OCR_FAILED = 'b5b5b5b5-b5b5-45b5-85b5-000000000007';
const M05_DOC_OCR_DISABLED = 'b5b5b5b5-b5b5-45b5-85b5-000000000008';
const M05_DOC_ACL_USERS = 'b5b5b5b5-b5b5-45b5-85b5-000000000009';
const M05_DOC_ACL_DEPT = 'b5b5b5b5-b5b5-45b5-85b5-00000000000a';
const M05_DOC_ACL_CREATOR = 'b5b5b5b5-b5b5-45b5-85b5-00000000000b';
const M05_DOC_TENANT = 'b5b5b5b5-b5b5-45b5-85b5-00000000000c';
const M05_DOC_EXPIRED = 'b5b5b5b5-b5b5-45b5-85b5-00000000000d';
const M05_DOC_NO_SOURCE = 'b5b5b5b5-b5b5-45b5-85b5-00000000000e';

/** M05 合成文件 ID；知识库下载只暴露文档 ID，不暴露这些文件 ID。 */
const M05_FILE_EXCEL = 'c5c5c5c5-c5c5-45c5-85c5-000000000001';
const M05_FILE_OCR = 'c5c5c5c5-c5c5-45c5-85c5-000000000002';
const M05_FILE_ACL = 'c5c5c5c5-c5c5-45c5-85c5-000000000003';

/** 知识库保留到期时间；固定值让页面断言稳定（Asia/Shanghai，带偏移）。 */
const M05_RETENTION_EXPIRES = '2027-05-22T10:00:00+08:00';
const M05_RETENTION_EXPIRED = '2026-11-01T00:00:00+08:00';

/**
 * E2E 登录账号表；key 为 `tenantCode/username`。
 *
 * 平台管理员只用于 /admin 部门管理页；租户管理员、负责人与普通成员用于空间共享与知识库边界。
 */
const M03_CREDENTIALS = {
  'acme/demo': {
    password: 'demo1234',
    persona: 'member',
    userId: DEMO_USER_ID,
    username: 'demo',
    displayName: M03_DEMO_DISPLAY_NAME,
    roles: ['MEMBER'],
    permissions: ['workspace.read', 'conversation.read', 'department.read', 'knowledge.read'],
    tenantId: M03_TENANT_ACME,
    tenantName: '示例企业',
  },
  'acme/wangmin': {
    password: 'wangmin1234',
    persona: 'member',
    userId: M03_MEMBER_USER_ID,
    username: M03_MEMBER_USERNAME,
    displayName: M03_MEMBER_DISPLAY_NAME,
    roles: ['MEMBER'],
    permissions: ['workspace.read', 'conversation.read', 'department.read', 'knowledge.read'],
    tenantId: M03_TENANT_ACME,
    tenantName: '示例企业',
  },
  'acme/unassigned': {
    password: 'unassigned1234',
    persona: 'member',
    userId: M03_UNASSIGNED_USER_ID,
    username: 'unassigned',
    displayName: '未分配员工',
    roles: ['MEMBER'],
    permissions: ['workspace.read', 'conversation.read', 'department.read', 'knowledge.read'],
    tenantId: M03_TENANT_ACME,
    tenantName: '示例企业',
  },
  'acme/disabled': {
    password: 'disabled1234',
    persona: 'member',
    userId: M03_DISABLED_USER_ID,
    username: 'disabled',
    displayName: '停用员工',
    roles: ['MEMBER'],
    permissions: ['workspace.read', 'conversation.read', 'department.read', 'knowledge.read'],
    tenantId: M03_TENANT_ACME,
    tenantName: '示例企业',
  },
  'acme/manager': {
    password: 'manager1234',
    persona: 'manager',
    userId: M03_MANAGER_USER_ID,
    username: M03_MANAGER_USERNAME,
    displayName: M03_MANAGER_DISPLAY_NAME,
    roles: ['MEMBER'],
    permissions: ['workspace.read', 'conversation.read', 'department.read', 'knowledge.read'],
    tenantId: M03_TENANT_ACME,
    tenantName: '示例企业',
  },
  'acme/admin': {
    password: 'admin1234',
    persona: 'tenant-admin',
    userId: M03_ADMIN_USER_ID,
    username: M03_ADMIN_USERNAME,
    displayName: M03_ADMIN_DISPLAY_NAME,
    roles: ['TENANT_ADMIN'],
    permissions: [
      'workspace.read',
      'workspace.write',
      'conversation.read',
      'department.read',
      'knowledge.read',
      'knowledge.manage',
      'knowledge.acl.manage',
      'tenant.user.manage',
      'quota.read',
      'cost.read',
      'model.read',
    ],
    tenantId: M03_TENANT_ACME,
    tenantName: '示例企业',
  },
  'acme/outsider': {
    password: 'outsider1234',
    persona: 'outsider',
    userId: M03_OUTSIDER_USER_ID,
    username: M03_OUTSIDER_USERNAME,
    displayName: M03_OUTSIDER_DISPLAY_NAME,
    roles: ['MEMBER'],
    permissions: ['workspace.read', 'conversation.read', 'department.read', 'knowledge.read'],
    tenantId: M03_TENANT_ACME,
    tenantName: '示例企业',
  },
  'platform/platform-admin': {
    password: 'platform1234',
    persona: 'platform-admin',
    userId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    username: M03_PLATFORM_USERNAME,
    displayName: M03_PLATFORM_DISPLAY_NAME,
    roles: ['PLATFORM_ADMIN'],
    permissions: [
      'platform.tenant.manage',
      'platform.department.manage',
      'platform.model.read',
      'platform.model.manage',
      'platform.quota.manage',
      'platform.cost.read',
    ],
    tenantId: M03_PLATFORM_TENANT,
    tenantName: '平台',
  },
};

/** 合成时间基准，保证刷新后详情时间稳定可读。 */
const BASE_TIME = '2026-11-17T08:00:00.000Z';

/** 会话表：sid -> { authenticated, csrfToken }。 */
const sessions = new Map();

/** 活动 SSE 运行：runId -> run。 */
const activeRuns = new Map();

/** 当前 E2E 场景开关；只影响错误路径用例。 */
let scenario = 'none';

/** 自增计数，用于 traceId。 */
let traceCounter = 0;

/** 业务状态；reset 时整体替换。 */
let state = createInitialState();

/**
 * 创建 M04 空状态；页面在调用 `/__stub__/m04-seed` 前显示空态。
 *
 * @returns {object} M04 状态
 */
function createM04State() {
  return {
    seeded: false,
    empty: false,
    providers: [],
    models: [],
    /** 内存凭据；apiKey 只用于同请求去重，不进入任何响应。 */
    credentials: [],
    routing: {
      CHAT: { purpose: 'CHAT', version: 1, entries: [], updatedAt: BASE_TIME, updatedBy: null },
      REPORT: { purpose: 'REPORT', version: 1, entries: [], updatedAt: BASE_TIME, updatedBy: null },
    },
    switches: {
      routingMode: 'ROUTED',
      failoverEnabled: true,
      quotaEnforcementEnabled: true,
      version: 1,
      source: 'DATABASE',
      envOverride: null,
      updatedAt: BASE_TIME,
      updatedBy: null,
    },
    tenantQuotas: {},
    userQuotas: {},
    costInvocations: [],
  };
}

/**
 * 填充确定性的 M04 合成数据；重复调用结果相同。
 *
 * @param {boolean} [empty] 是否只保留空目录/空列表
 */
function seedM04State(empty = false) {
  const createdAt = BASE_TIME;
  const providers = [
    {
      id: M04_PROVIDER_PRIMARY,
      code: 'fake-primary',
      name: '假模型主供应商',
      protocol: 'OPENAI_COMPATIBLE',
      baseUrl: 'http://127.0.0.1:18990/v1',
      environmentLabel: 'DEMO_ONLY',
      allowed: true,
      status: 'ACTIVE',
    },
    {
      id: M04_PROVIDER_SECONDARY,
      code: 'fake-secondary',
      name: '假模型备用供应商',
      protocol: 'OPENAI_COMPATIBLE',
      baseUrl: 'http://127.0.0.1:18990/v1-secondary',
      environmentLabel: 'PRODUCTION',
      allowed: true,
      status: 'ACTIVE',
    },
    {
      id: M04_PROVIDER_DISALLOWED,
      code: 'fake-disallowed',
      name: '未进入部署允许列表的端点',
      protocol: 'OPENAI_COMPATIBLE',
      baseUrl: 'http://127.0.0.1:18990/v1-disallowed',
      environmentLabel: 'DEMO_ONLY',
      allowed: false,
      status: 'DISABLED',
    },
  ];
  const models = [
    {
      id: M04_MODEL_PRIMARY,
      modelKey: 'fake-chat-primary',
      displayName: '假模型-主',
      providerId: M04_PROVIDER_PRIMARY,
      contextWindow: 32768,
      maxOutputTokens: 2048,
      capabilities: { chat: true, stream: true, tool: true },
      inputPricePer1M: '1.500000',
      outputPricePer1M: '3.000000',
      priceCurrency: 'CNY',
      status: 'ACTIVE',
      createdAt,
      updatedAt: createdAt,
    },
    {
      id: M04_MODEL_SECONDARY,
      modelKey: 'fake-chat-secondary',
      displayName: '假模型-备用',
      providerId: M04_PROVIDER_SECONDARY,
      contextWindow: 32768,
      maxOutputTokens: 2048,
      capabilities: { chat: true, stream: true },
      inputPricePer1M: '2.000000',
      outputPricePer1M: '4.000000',
      priceCurrency: 'CNY',
      status: 'ACTIVE',
      createdAt,
      updatedAt: createdAt,
    },
    {
      id: M04_MODEL_DISABLED,
      modelKey: 'fake-chat-disabled',
      displayName: '假模型-停用示例',
      providerId: M04_PROVIDER_PRIMARY,
      contextWindow: 16384,
      maxOutputTokens: 1024,
      capabilities: { chat: true, stream: true },
      inputPricePer1M: null,
      outputPricePer1M: null,
      priceCurrency: null,
      status: 'DISABLED',
      createdAt,
      updatedAt: createdAt,
    },
    {
      id: M04_MODEL_UNPRICED,
      modelKey: 'fake-chat-unpriced',
      displayName: '假模型-未定价示例',
      providerId: M04_PROVIDER_SECONDARY,
      contextWindow: 16384,
      maxOutputTokens: 1024,
      capabilities: { chat: true, stream: true },
      inputPricePer1M: null,
      outputPricePer1M: null,
      priceCurrency: null,
      status: 'ACTIVE',
      createdAt,
      updatedAt: createdAt,
    },
  ];
  const credentials = [
    {
      id: '93939393-9393-4393-8393-000000000001',
      providerId: M04_PROVIDER_PRIMARY,
      label: '主 key',
      apiKey: 'stub-primary-key-0001',
      maskedHint: '****0001',
      status: 'ACTIVE',
      priority: 10,
      version: 1,
      consecutiveFailures: 0,
      cooldownUntil: null,
      createdAt,
      updatedAt: createdAt,
      rotatedAt: null,
      lastUsedAt: '2026-11-20T10:00:00+08:00',
    },
    {
      id: '93939393-9393-4393-8393-000000000002',
      providerId: M04_PROVIDER_PRIMARY,
      label: '备用 key',
      apiKey: 'stub-primary-key-0002',
      maskedHint: '****0002',
      status: 'ACTIVE',
      priority: 20,
      version: 1,
      consecutiveFailures: 0,
      cooldownUntil: null,
      createdAt,
      updatedAt: createdAt,
      rotatedAt: null,
      lastUsedAt: null,
    },
    {
      id: '93939393-9393-4393-8393-000000000003',
      providerId: M04_PROVIDER_SECONDARY,
      label: '备用供应商 key',
      apiKey: 'stub-secondary-key-0001',
      maskedHint: '****0001',
      status: 'ACTIVE',
      priority: 10,
      version: 2,
      consecutiveFailures: 0,
      cooldownUntil: null,
      createdAt,
      updatedAt: createdAt,
      rotatedAt: null,
      lastUsedAt: null,
    },
  ];

  const routing = {
    CHAT: {
      purpose: 'CHAT',
      version: 4,
      entries: [M04_MODEL_PRIMARY, M04_MODEL_SECONDARY, M04_MODEL_DISABLED],
      updatedAt: '2026-11-20T09:00:00+08:00',
      updatedBy: null,
    },
    REPORT: {
      purpose: 'REPORT',
      version: 2,
      entries: [M04_MODEL_PRIMARY],
      updatedAt: '2026-11-20T09:00:00+08:00',
      updatedBy: null,
    },
  };

  const tenantQuotas = {
    [M03_TENANT_ACME]: {
      daily: { limit: 1000, used: 120, source: 'EXPLICIT' },
      monthly: { limit: 20000, used: 3400, source: 'EXPLICIT' },
    },
    [M03_TENANT_BETA]: {
      daily: { limit: null, used: 5, source: 'EXPLICIT_UNLIMITED' },
      monthly: { limit: null, used: 10, source: 'EXPLICIT_UNLIMITED' },
    },
  };
  const userQuotas = {
    [DEMO_USER_ID]: {
      tenantId: M03_TENANT_ACME,
      daily: { limit: 100, used: 42, source: 'EXPLICIT' },
      monthly: { limit: 2000, used: 500, source: 'EXPLICIT' },
    },
    [M03_MEMBER_USER_ID]: {
      tenantId: M03_TENANT_ACME,
      daily: { limit: 50, used: 3, source: 'EXPLICIT' },
      monthly: { limit: 500, used: 12, source: 'EXPLICIT' },
    },
    [M03_ADMIN_USER_ID]: {
      tenantId: M03_TENANT_ACME,
      daily: { limit: 500, used: 0, source: 'DEPLOYMENT_DEFAULT' },
      monthly: { limit: 5000, used: 0, source: 'DEPLOYMENT_DEFAULT' },
    },
  };

  const costInvocations = [
    {
      id: '94949494-9494-4494-8494-000000000001',
      tenantId: M03_TENANT_ACME,
      modelId: M04_MODEL_PRIMARY,
      modelDisplayName: '假模型-主',
      userId: DEMO_USER_ID,
      userDisplayName: M03_DEMO_DISPLAY_NAME,
      startedAt: '2026-11-20T09:30:00+08:00',
      day: '2026-11-20',
      promptTokens: 1200,
      completionTokens: 800,
      inputPricePer1M: '1.500000',
      outputPricePer1M: '3.000000',
      priceCurrency: 'CNY',
      amount: '0.004200',
      pricingStatus: 'PRICED',
    },
    {
      id: '94949494-9494-4494-8494-000000000002',
      tenantId: M03_TENANT_ACME,
      modelId: M04_MODEL_UNPRICED,
      modelDisplayName: '假模型-未定价示例',
      userId: M03_MEMBER_USER_ID,
      userDisplayName: M03_MEMBER_DISPLAY_NAME,
      startedAt: '2026-11-20T11:00:00+08:00',
      day: '2026-11-20',
      promptTokens: 600,
      completionTokens: 300,
      inputPricePer1M: null,
      outputPricePer1M: null,
      priceCurrency: null,
      amount: null,
      pricingStatus: 'UNPRICED',
    },
    {
      id: '94949494-9494-4494-8494-000000000003',
      tenantId: M03_TENANT_ACME,
      modelId: M04_MODEL_PRIMARY,
      modelDisplayName: '假模型-主',
      userId: DEMO_USER_ID,
      userDisplayName: M03_DEMO_DISPLAY_NAME,
      startedAt: '2026-11-19T16:20:00+08:00',
      day: '2026-11-19',
      promptTokens: null,
      completionTokens: null,
      inputPricePer1M: '1.500000',
      outputPricePer1M: '3.000000',
      priceCurrency: 'CNY',
      amount: null,
      pricingStatus: 'USAGE_UNKNOWN',
    },
    {
      id: '94949494-9494-4494-8494-000000000004',
      tenantId: M03_TENANT_BETA,
      modelId: M04_MODEL_SECONDARY,
      modelDisplayName: '假模型-备用',
      userId: M15_OIDC_USER_B_ID,
      userDisplayName: M15_OIDC_USER_B_DISPLAY_NAME,
      startedAt: '2026-11-20T14:15:00+08:00',
      day: '2026-11-20',
      promptTokens: 2000,
      completionTokens: 1000,
      inputPricePer1M: '2.000000',
      outputPricePer1M: '4.000000',
      priceCurrency: 'CNY',
      amount: '0.008000',
      pricingStatus: 'PRICED',
    },
  ];

  state.m04 = {
    seeded: true,
    empty,
    providers: empty ? [] : providers,
    models: empty ? [] : models,
    credentials: empty ? [] : credentials,
    routing: empty
      ? {
          CHAT: { purpose: 'CHAT', version: 1, entries: [], updatedAt: BASE_TIME, updatedBy: null },
          REPORT: {
            purpose: 'REPORT',
            version: 1,
            entries: [],
            updatedAt: BASE_TIME,
            updatedBy: null,
          },
        }
      : routing,
    switches: {
      routingMode: 'ROUTED',
      failoverEnabled: true,
      quotaEnforcementEnabled: true,
      version: 7,
      source: 'DATABASE',
      envOverride: null,
      updatedAt: '2026-11-20T09:00:00+08:00',
      updatedBy: null,
    },
    tenantQuotas: empty ? {} : tenantQuotas,
    userQuotas: empty ? {} : userQuotas,
    costInvocations: empty ? [] : costInvocations,
  };
}

/**
 * 创建初始业务状态。
 *
 * @returns 空会话、空任务与一条授权 MCP 工具的合成状态
 */
function createInitialState() {
  return {
    workspace: {
      id: DEMO_WORKSPACE_ID,
      kind: 'PERSONAL',
      name: '我的工作空间',
      description: '用于 J1/J2 E2E 的个人工作空间（合成数据）',
      ownerUserId: DEMO_USER_ID,
      status: 'ACTIVE',
      createdAt: BASE_TIME,
      updatedAt: BASE_TIME,
    },
    conversations: [],
    messages: {},
    files: [],
    parseOutcomes: {},
    runs: {},
    researchTasks: [],
    mcpTools: [
      {
        id: DEMO_MCP_TOOL_ID,
        serverId: null,
        name: 'market_data_lookup',
        description: '只读市场数据查询（E2E stub 合成数据，不连接真实外部系统）',
        riskLevel: 'READ',
        requiresApproval: true,
        status: 'ACTIVE',
      },
    ],
    researchIdempotency: {},
    // M03：平台租户元数据、部门与团队空间；所有写操作只改内存。
    tenants: [
      {
        id: M03_TENANT_ACME,
        name: '示例企业',
        status: 'ACTIVE',
        plan: 'standard',
        retentionDays: 180,
        createdAt: BASE_TIME,
      },
      {
        id: M03_TENANT_BETA,
        name: 'Beta 演示企业',
        status: 'ACTIVE',
        plan: 'standard',
        retentionDays: 180,
        createdAt: BASE_TIME,
      },
      {
        id: M03_TENANT_SUSPENDED,
        name: '暂停租户',
        status: 'SUSPENDED',
        plan: 'standard',
        retentionDays: 180,
        createdAt: BASE_TIME,
      },
    ],
    users: [
      {
        id: DEMO_USER_ID,
        tenantId: M03_TENANT_ACME,
        username: 'demo',
        displayName: M03_DEMO_DISPLAY_NAME,
        email: null,
        status: 'ACTIVE',
        roles: ['MEMBER'],
        lastLoginAt: BASE_TIME,
        createdAt: BASE_TIME,
        departmentId: null,
      },
      {
        id: M03_MEMBER_USER_ID,
        tenantId: M03_TENANT_ACME,
        username: M03_MEMBER_USERNAME,
        displayName: M03_MEMBER_DISPLAY_NAME,
        email: null,
        status: 'ACTIVE',
        roles: ['MEMBER'],
        lastLoginAt: BASE_TIME,
        createdAt: BASE_TIME,
        departmentId: null,
      },
      {
        id: M03_UNASSIGNED_USER_ID,
        tenantId: M03_TENANT_ACME,
        username: 'unassigned',
        displayName: '未分配员工',
        email: null,
        status: 'ACTIVE',
        roles: ['MEMBER'],
        lastLoginAt: BASE_TIME,
        createdAt: BASE_TIME,
        departmentId: null,
      },
      {
        id: M03_DISABLED_USER_ID,
        tenantId: M03_TENANT_ACME,
        username: 'disabled',
        displayName: '停用员工',
        email: null,
        status: 'ACTIVE',
        roles: ['MEMBER'],
        lastLoginAt: BASE_TIME,
        createdAt: BASE_TIME,
        departmentId: null,
      },
      {
        id: M03_MANAGER_USER_ID,
        tenantId: M03_TENANT_ACME,
        username: M03_MANAGER_USERNAME,
        displayName: M03_MANAGER_DISPLAY_NAME,
        email: null,
        status: 'ACTIVE',
        roles: ['MEMBER'],
        lastLoginAt: BASE_TIME,
        createdAt: BASE_TIME,
        departmentId: null,
      },
      {
        id: M03_OUTSIDER_USER_ID,
        tenantId: M03_TENANT_ACME,
        username: M03_OUTSIDER_USERNAME,
        displayName: M03_OUTSIDER_DISPLAY_NAME,
        email: null,
        status: 'ACTIVE',
        roles: ['MEMBER'],
        lastLoginAt: BASE_TIME,
        createdAt: BASE_TIME,
        departmentId: null,
      },
      {
        id: M03_ADMIN_USER_ID,
        tenantId: M03_TENANT_ACME,
        username: M03_ADMIN_USERNAME,
        displayName: M03_ADMIN_DISPLAY_NAME,
        email: null,
        status: 'ACTIVE',
        roles: ['TENANT_ADMIN'],
        lastLoginAt: BASE_TIME,
        createdAt: BASE_TIME,
        departmentId: null,
      },
      {
        id: M15_OIDC_USER_A_ID,
        tenantId: M03_TENANT_ACME,
        username: M15_OIDC_USER_A_NAME,
        displayName: M15_OIDC_USER_A_DISPLAY_NAME,
        email: null,
        status: 'ACTIVE',
        roles: ['MEMBER'],
        // 服务端下发脱敏展示名；前端只原样展示，不拼接完整 issuer。
        authSource: 'OIDC',
        identityProvider: M15_PROVIDER_A,
        lastLoginAt: BASE_TIME,
        createdAt: BASE_TIME,
        departmentId: null,
      },
      {
        id: M15_OIDC_USER_B_ID,
        tenantId: M03_TENANT_ACME,
        username: M15_OIDC_USER_B_NAME,
        displayName: M15_OIDC_USER_B_DISPLAY_NAME,
        email: null,
        status: 'ACTIVE',
        roles: ['MEMBER'],
        authSource: 'OIDC',
        identityProvider: M15_PROVIDER_B,
        lastLoginAt: BASE_TIME,
        createdAt: BASE_TIME,
        departmentId: null,
      },
    ],
    departments: [],
    teamWorkspaces: [],
    workspaceMembers: {},
    // M04：模型路由/key/配额/成本页面的合成状态；由 /__stub__/m04-seed 填充。
    m04: createM04State(),
    // M05：知识库文档、ACL 与审计；由 /__stub__/m05-seed 填充。
    knowledge: { seeded: false, documents: [], audit: [] },
  };
}

/**
 * 生成唯一 ID。
 *
 * @param prefix 兼容旧运行环境的可读前缀
 * @returns UUID 或带前缀的稳定 ID
 */
function nextId(prefix) {
  return typeof globalThis.crypto?.randomUUID === 'function'
    ? globalThis.crypto.randomUUID()
    : `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

/**
 * 生成 traceId。
 *
 * @returns 可读的 trace 标识
 */
function nextTraceId() {
  traceCounter += 1;
  return `stub-trace-${Date.now()}-${traceCounter}`;
}

/**
 * 生成 64 位十六进制伪哈希；E2E 不校验真实内容哈希。
 *
 * @param seed 种子文本
 * @returns 64 位十六进制字符串
 */
function pseudoSha256(seed) {
  let hash = 2166136261;
  for (let index = 0; index < seed.length; index += 1) {
    hash ^= seed.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  const chunk = (hash >>> 0).toString(16).padStart(8, '0');
  return chunk.repeat(8).slice(0, 64);
}

/**
 * 重置业务状态、会话与活动运行；供每个 E2E 用例开始时调用。
 */
function resetAll() {
  for (const run of activeRuns.values()) {
    if (run.timer !== null) {
      clearInterval(run.timer);
    }
  }
  activeRuns.clear();
  sessions.clear();
  scenario = 'none';
  state = createInitialState();
}

/**
 * 构造当前会话响应。
 *
 * @param session 会话记录
 * @returns OpenAPI SessionInfo
 */
/** 员工端完整权限；部门管理入口仍由服务端角色与部门负责人关系决定。 */
const MEMBER_PERMISSIONS = [
  'workspace.read',
  'conversation.read',
  'conversation.write',
  'file.read',
  'file.upload',
  'report.read',
  'report.create',
  'report.approve',
  'artifact.read',
  'mcp.read',
  'department.read',
  'knowledge.read',
  'model.read',
];

/**
 * 构造当前会话响应。
 *
 * @param session 会话记录
 * @returns OpenAPI SessionInfo
 */
function sessionInfo(session) {
  const account = session.account ?? M03_CREDENTIALS['acme/demo'];
  const isMemberPersona = ['member', 'manager', 'outsider'].includes(account.persona);
  const tenant = state.tenants.find((item) => item.id === account.tenantId);
  return {
    authenticated: true,
    user: {
      id: account.userId,
      username: account.username,
      displayName: account.displayName,
      email: null,
      status: 'ACTIVE',
      roles: account.roles,
      // stub 的内置账号等价于 LOCAL；OIDC 账号的脱敏展示名只由服务端字段驱动。
      authSource: account.authSource ?? 'LOCAL',
      ...(account.identityProvider ? { identityProvider: account.identityProvider } : {}),
      lastLoginAt: BASE_TIME,
      createdAt: BASE_TIME,
    },
    tenant: {
      id: account.tenantId,
      name: tenant?.name ?? account.tenantName,
      status: tenant?.status ?? 'ACTIVE',
      retentionDays: tenant?.retentionDays ?? 180,
      createdAt: BASE_TIME,
    },
    roles: account.roles,
    permissions: isMemberPersona ? MEMBER_PERMISSIONS : account.permissions,
    csrfToken: session.csrfToken,
    expiresAt: new Date(Date.now() + 30 * 60 * 1000).toISOString(),
  };
}

/** 分页响应。 */
function page(items) {
  return {
    page: 1,
    size: 100,
    total: items.length,
    totalPages: items.length > 0 ? 1 : 0,
    items,
  };
}

/**
 * 构造 RFC 9457 ProblemDetail。
 *
 * @param status HTTP 状态
 * @param code 稳定错误码
 * @param title 短标题
 * @param detail 可读原因
 * @returns 问题详情对象
 */
function problem(status, code, title, detail) {
  return {
    body: {
      type: 'about:blank',
      title,
      status,
      detail,
      code,
      traceId: nextTraceId(),
    },
    status,
    contentType: 'application/problem+json; charset=utf-8',
  };
}

/** 成功 JSON 响应。 */
function json(data, status = 200) {
  return { body: data, status, contentType: 'application/json; charset=utf-8' };
}

/**
 * 解析 Cookie 头。
 *
 * @param header Cookie 请求头
 * @returns 名称到值的映射
 */
function parseCookies(header) {
  const result = {};
  if (!header) {
    return result;
  }
  for (const part of header.split(';')) {
    const separator = part.indexOf('=');
    if (separator === -1) {
      continue;
    }
    result[part.slice(0, separator).trim()] = decodeURIComponent(part.slice(separator + 1).trim());
  }
  return result;
}

/**
 * 读取请求会话。
 *
 * @param req Node 请求
 * @returns 会话记录或 null
 */
function readSession(req) {
  const sid = parseCookies(req.headers.cookie)[SESSION_COOKIE];
  return sid ? (sessions.get(sid) ?? null) : null;
}

/**
 * 确保请求带 Cookie 会话；匿名请求会建立仅承载 CSRF 的会话。
 *
 * @param req Node 请求
 * @returns 会话记录（可能未登录）
 */
function ensureSession(req) {
  const existing = readSession(req);
  if (existing) {
    return { session: existing, created: false };
  }
  const sid = nextId('session');
  const session = { authenticated: false, csrfToken: `stub-csrf-${nextId('csrf')}` };
  sessions.set(sid, session);
  return { session, created: true, sid };
}

/**
 * 清除会话。
 *
 * @param req Node 请求
 */
function dropSession(req) {
  const sid = parseCookies(req.headers.cookie)[SESSION_COOKIE];
  if (sid) {
    sessions.delete(sid);
  }
}

/**
 * 校验登录态。
 *
 * @param session 会话记录
 * @returns 401 响应或 null
 */
function requireAuth(session) {
  if (!session?.authenticated) {
    return problem(401, 'RICCON-AUTH-401', '未登录', '请先登录后再访问。');
  }
  return null;
}

/**
 * 校验 CSRF 头。
 *
 * @param session 会话记录
 * @param req Node 请求
 * @returns 403 响应或 null
 */
function requireCsrf(session, req) {
  if (!session || req.headers['x-csrf-token'] !== session.csrfToken) {
    return problem(403, 'RICCON-AUTH-403', '安全令牌无效', '请刷新页面后重试。');
  }
  return null;
}

/**
 * 读取 JSON 请求体。
 *
 * @param raw 原始字节
 * @returns 解析后的对象；非法 JSON 返回 null
 */
function readJson(raw) {
  if (!raw || raw.length === 0) {
    return null;
  }
  try {
    return JSON.parse(raw.toString('utf8'));
  } catch {
    return null;
  }
}

/**
 * 判断文件字节是否为允许的格式；只校验扩展名与声明 MIME，内容为合成数据。
 *
 * @param filename 文件名
 * @param mimeType 声明 MIME
 * @returns 格式或 null
 */
function formatForFile(filename, mimeType) {
  const dot = filename.lastIndexOf('.');
  if (dot <= 0) {
    return null;
  }
  const format = EXTENSION_FORMATS[filename.slice(dot).toLowerCase()];
  if (!format) {
    return null;
  }
  const declared = (mimeType ?? '').toLowerCase();
  const allowed = {
    '.pdf': ['application/pdf'],
    '.docx': ['application/vnd.openxmlformats-officedocument.wordprocessingml.document'],
    '.xlsx': ['application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'],
    '.png': ['image/png'],
    '.jpg': ['image/jpeg', 'image/jpg'],
    '.jpeg': ['image/jpeg', 'image/jpg'],
  }[filename.slice(dot).toLowerCase()];
  if (declared && declared !== 'application/octet-stream' && !allowed.includes(declared)) {
    return null;
  }
  return format;
}

/**
 * 解析 multipart/form-data 请求体。
 *
 * 只需要 J1 上传的 `file` 与 `conversationId` 两个字段；按 boundary 切分，
 * 不做通用 MIME 解析，避免为 E2E stub 引入第三方依赖。
 *
 * @param body 原始请求体
 * @param contentType Content-Type 头
 * @returns 字段与文件；无法解析时返回 null
 */
function parseMultipart(body, contentType) {
  const match = /boundary=(?:"([^"]+)"|([^;]+))/i.exec(contentType ?? '');
  if (!match || !body) {
    return null;
  }
  const boundary = Buffer.from(`--${match[1] ?? match[2]}`);
  const fields = {};
  const files = {};

  let cursor = body.indexOf(boundary);
  while (cursor !== -1) {
    const next = body.indexOf(boundary, cursor + boundary.length);
    if (next === -1) {
      break;
    }
    let part = body.subarray(cursor + boundary.length, next);
    // 去掉分隔符后的 CRLF 与下一个 boundary 前的 CRLF。
    if (part[0] === 13 && part[1] === 10) {
      part = part.subarray(2);
    }
    if (part.length >= 2 && part[part.length - 2] === 13 && part[part.length - 1] === 10) {
      part = part.subarray(0, part.length - 2);
    }
    // 结束分隔符 `--` 不是有效 part。
    if (part.length > 2 && part[0] === 45 && part[1] === 45) {
      cursor = next;
      continue;
    }
    const headerEnd = part.indexOf('\r\n\r\n');
    if (headerEnd !== -1) {
      const headerText = part.subarray(0, headerEnd).toString('utf8');
      const data = part.subarray(headerEnd + 4);
      const nameMatch = /name="([^"]*)"/i.exec(headerText);
      const filenameMatch = /filename="([^"]*)"/i.exec(headerText);
      const typeMatch = /content-type:\s*([^\r\n]+)/i.exec(headerText);
      if (nameMatch) {
        if (filenameMatch) {
          files[nameMatch[1]] = {
            filename: filenameMatch[1],
            contentType: typeMatch?.[1]?.trim() ?? 'application/octet-stream',
            data,
          };
        } else {
          fields[nameMatch[1]] = data.toString('utf8');
        }
      }
    }
    cursor = next;
  }

  return { fields, files };
}

/**
 * 按真实时间推进文件解析状态。
 */
function advanceParses() {
  const now = Date.now();
  for (const file of state.files) {
    if (!file.parse || (file.parse.status !== 'PENDING' && file.parse.status !== 'RUNNING')) {
      continue;
    }
    const startedAt = file.parse.startedAt ? Date.parse(file.parse.startedAt) : now;
    const elapsed = Math.max(0, now - startedAt);
    if (elapsed < PARSE_RUNNING_AFTER_MS) {
      continue;
    }
    if (elapsed < PARSE_FINISH_AFTER_MS && file.parse.status !== 'RUNNING') {
      file.parse.status = 'RUNNING';
      continue;
    }
    if (elapsed >= PARSE_FINISH_AFTER_MS) {
      const outcome = state.parseOutcomes[file.id];
      if (outcome?.fail) {
        file.parse.status = 'FAILED';
        file.parse.errorCode = 'RICCON-FILE-500';
        file.parse.errorMessage = '文件内容无法解析，请确认文件未加密或未损坏后重试。';
      } else {
        file.parse.status = 'SUCCEEDED';
        file.parse.pageCount = file.format === 'PDF' ? 12 : null;
        file.parse.sheetCount = file.format === 'XLSX' ? 3 : null;
        file.parse.chunkCount = 8;
        file.parse.errorCode = null;
        file.parse.errorMessage = null;
      }
      file.parse.finishedAt = new Date(now).toISOString();
    }
  }
}

/**
 * 根据文件格式生成引用定位。
 *
 * @param file 已解析文件
 * @param ordinal 引用序号
 * @returns OpenAPI Citation
 */
function citationForFile(file, ordinal) {
  const base = {
    ordinal,
    sourceType: 'FILE',
    fileId: file.id,
    knowledgeDocumentId: null,
    chunkId: null,
    title: file.originalName,
    snippet: `演示引用片段：${file.originalName} 中与问题相关的内容。`,
    score: 0.82,
  };
  switch (file.format) {
    case 'XLSX':
      return {
        ...base,
        locatorType: 'SHEET',
        sheetName: '销售数据',
        cellRange: 'B2:D8',
        pageNo: null,
        paragraphNo: null,
      };
    case 'DOCX':
      return {
        ...base,
        locatorType: 'PARAGRAPH',
        paragraphNo: 5,
        pageNo: null,
        sheetName: null,
        cellRange: null,
      };
    case 'PNG':
    case 'JPEG':
      return {
        ...base,
        locatorType: 'PARAGRAPH',
        paragraphNo: 1,
        pageNo: null,
        sheetName: null,
        cellRange: null,
      };
    case 'PDF':
    default:
      return {
        ...base,
        locatorType: 'PAGE',
        pageNo: 3,
        sheetName: null,
        cellRange: null,
        paragraphNo: null,
      };
  }
}

/**
 * 构造一条跨部门知识库来源引用；源文件对当前账号不可访问，用于验证 404 文案。
 *
 * @returns 引用快照
 */
function unavailableSourceCitation() {
  return {
    ordinal: 1,
    sourceType: 'KNOWLEDGE',
    fileId: M03_SOURCE_UNAVAILABLE_FILE_ID,
    knowledgeDocumentId: DEMO_KNOWLEDGE_ID_ONE,
    chunkId: 'chunk-other-department',
    title: M03_SOURCE_UNAVAILABLE_FILE_NAME,
    snippet: '演示引用片段：该文档属于其他部门的共享空间，引用可展示但源文件不可访问。',
    score: 0.76,
    locatorType: 'PAGE',
    pageNo: 3,
    paragraphNo: null,
    sheetName: null,
    cellRange: null,
  };
}

/**
 * 生成合成回答。
 *
 * @param question 用户问题
 * @param citations 引用
 * @returns 回答全文
 */
function buildAnswer(question, citations) {
  if (citations.length === 0) {
    return '当前知识库未检索到相关资料。你可以先上传文件，或用更具体的关键词重新提问。';
  }
  const first = citations[0];
  const locator =
    first.locatorType === 'SHEET'
      ? `工作表 ${first.sheetName ?? ''}`
      : first.locatorType === 'PAGE'
        ? `第 ${first.pageNo ?? 1} 页`
        : `第 ${first.paragraphNo ?? 1} 段`;
  return [
    `根据你上传的文件《${first.title}》，我找到以下信息：`,
    '',
    `1. 文件已完成解析，可用于回答与「${question.slice(0, 40)}」相关的问题。`,
    `2. 引用位置：${locator}。`,
    '3. 回答内容仅用于演示 J1 流式与引用链路，不代表真实检索结果。',
    '',
    '如需进一步分析，可以继续追问，或上传更多文件。',
  ].join('\n');
}

/**
 * 把回答切成小段，模拟流式输出。
 *
 * @param answer 回答全文
 * @returns 文本片段
 */
function splitAnswer(answer) {
  const size = 24;
  const chunks = [];
  for (let index = 0; index < answer.length; index += size) {
    chunks.push(answer.slice(index, index + size));
  }
  return chunks;
}

/**
 * 构造 SSE 事件信封。
 *
 * @param runId 运行 ID
 * @param messageId 助手消息 ID
 * @param eventType 契约事件类型
 * @param data 事件数据
 * @returns ChatEvent
 */
function createEvent(runId, messageId, eventType, data) {
  return {
    eventId: nextId('event'),
    eventType,
    version: 1,
    occurredAt: new Date().toISOString(),
    tenantId: DEMO_TENANT_ID,
    runId,
    messageId,
    traceId: nextTraceId(),
    data,
  };
}

/**
 * 序列化 SSE 帧。
 *
 * @param event 事件
 * @returns `event:` + `data:` + 空行
 */
function encodeEvent(event) {
  return `event: ${event.eventType}\ndata: ${JSON.stringify(event)}\n\n`;
}

/**
 * 结束运行并持久化助手消息。
 *
 * @param run 活动运行
 * @param status 消息终态
 */
function finalizeRun(run, status) {
  const messages = state.messages[run.conversationId] ?? [];
  const assistant = messages.find((message) => message.id === run.assistantId);
  if (assistant) {
    assistant.content = run.content;
    assistant.status = status;
    assistant.citations = run.citations;
    assistant.runId = run.runId;
  }
  const conversation = state.conversations.find((item) => item.id === run.conversationId);
  if (conversation) {
    conversation.lastMessageAt = new Date().toISOString();
    conversation.updatedAt = conversation.lastMessageAt;
  }

  // 运行详情与消息终态保持一致：完成/失败/停止分别映射 SUCCEEDED/FAILED/CANCELLED。
  const detail = state.runs[run.runId];
  if (detail) {
    const finishedAt = new Date().toISOString();
    const invocation = detail.modelInvocations[0];
    detail.finishedAt = finishedAt;
    detail.status =
      status === 'COMPLETED' ? 'SUCCEEDED' : status === 'FAILED' ? 'FAILED' : 'CANCELLED';
    if (status === 'FAILED') {
      detail.errorCode = 'RICCON-MODEL-500';
      detail.errorMessage = '合成模型调用失败，请重试。';
    }
    if (invocation) {
      invocation.status =
        status === 'COMPLETED' ? 'SUCCEEDED' : status === 'FAILED' ? 'FAILED' : 'CANCELLED';
      // 失败在首字前终止，不能伪造首字延迟；其他终态保留合成的流式指标。
      invocation.firstTokenMs = status === 'FAILED' ? null : DELTA_INTERVAL_MS;
      invocation.durationMs = Math.max(
        1,
        Date.parse(finishedAt) - Date.parse(invocation.startedAt),
      );
      invocation.promptTokens = 128;
      invocation.completionTokens = Math.max(0, Math.ceil(run.content.length / 2));
      invocation.errorCode = status === 'FAILED' ? 'RICCON-MODEL-500' : null;
      invocation.finishedAt = finishedAt;
    }
  }
}

/**
 * 关闭一次运行；幂等，确保定时器、消息终态与活动表一致。
 *
 * @param run 活动运行
 * @param status 消息终态
 */
function finishRun(run, status) {
  if (run.closed) {
    return;
  }
  run.closed = true;
  if (run.timer !== null) {
    clearInterval(run.timer);
    run.timer = null;
  }
  finalizeRun(run, status);
  activeRuns.delete(run.runId);
}

/**
 * 处理“发送消息并流式回答”。
 *
 * @param req Node 请求
 * @param res Node 响应
 * @param conversation 会话
 * @param raw 已读取的请求体
 */
/**
 * 构造 M05 场景引用；用于在浏览器中验证 Excel/OCR 定位、低置信、遮蔽与无源文件。
 *
 * 只有显式切换 stub 场景时才返回；默认仍由上传文件生成引用，避免影响 J1 既有用例。
 *
 * @returns 引用快照或 null
 */
function m05ScenarioCitation() {
  switch (scenario) {
    case 'm05-citation-excel':
      return {
        ordinal: 1,
        sourceType: 'KNOWLEDGE',
        knowledgeDocumentId: M05_DOC_EXCEL_MULTI,
        fileId: M05_FILE_EXCEL,
        chunkId: null,
        title: '销售数据.xlsx',
        snippet: '销售数据!B2:D10：华东区季度销售额 128 万元，同比增长 18%。',
        score: 0.91,
        locatorType: 'SHEET',
        sheetName: '销售数据',
        cellRange: 'B2:D10',
        pageNo: null,
        paragraphNo: null,
        bbox: null,
        ocrConfidence: null,
        sourceStatus: 'AVAILABLE',
      };
    case 'm05-citation-ocr':
      return {
        ordinal: 1,
        sourceType: 'KNOWLEDGE',
        knowledgeDocumentId: M05_DOC_OCR_OK,
        fileId: M05_FILE_OCR,
        chunkId: null,
        title: '扫描合同.png',
        snippet: '甲方应在 2026 年 12 月 31 日前完成验收。',
        score: 0.88,
        locatorType: 'PAGE',
        sheetName: null,
        cellRange: null,
        pageNo: 2,
        paragraphNo: null,
        bbox: { x: 120, y: 340, w: 480, h: 200 },
        ocrConfidence: 0.93,
        sourceStatus: 'AVAILABLE',
      };
    case 'm05-citation-ocr-low':
      return {
        ordinal: 1,
        sourceType: 'KNOWLEDGE',
        knowledgeDocumentId: M05_DOC_OCR_LOW,
        fileId: M05_FILE_OCR,
        chunkId: null,
        title: '模糊票据.jpg',
        snippet: '报销金额：壹仟贰佰元（识别结果可能不准确）。',
        score: 0.61,
        locatorType: 'PAGE',
        sheetName: null,
        cellRange: null,
        pageNo: 1,
        paragraphNo: null,
        bbox: { x: 40, y: 60, w: 300, h: 120 },
        ocrConfidence: 0.52,
        sourceStatus: 'AVAILABLE',
      };
    case 'm05-citation-no-access':
      return {
        ordinal: 1,
        sourceType: 'KNOWLEDGE',
        knowledgeDocumentId: M05_DOC_ACL_USERS,
        fileId: M05_FILE_ACL,
        chunkId: null,
        title: '',
        snippet: '',
        score: null,
        locatorType: null,
        sheetName: null,
        cellRange: null,
        pageNo: null,
        paragraphNo: null,
        bbox: null,
        ocrConfidence: null,
        sourceStatus: 'NO_ACCESS',
      };
    case 'm05-citation-deleted':
      return {
        ordinal: 1,
        sourceType: 'KNOWLEDGE',
        knowledgeDocumentId: M05_DOC_EXPIRED,
        fileId: M05_FILE_ACL,
        chunkId: null,
        title: '',
        snippet: '',
        score: null,
        locatorType: null,
        sheetName: null,
        cellRange: null,
        pageNo: null,
        paragraphNo: null,
        bbox: null,
        ocrConfidence: null,
        sourceStatus: 'DELETED',
      };
    case 'm05-citation-no-source':
      return {
        ordinal: 1,
        sourceType: 'KNOWLEDGE',
        knowledgeDocumentId: null,
        fileId: null,
        chunkId: null,
        title: '种子语料：行业知识',
        snippet: '行业知识片段：企业协同办公渗透率持续提升。',
        score: 0.7,
        locatorType: 'PARAGRAPH',
        sheetName: null,
        cellRange: null,
        pageNo: null,
        paragraphNo: 3,
        bbox: null,
        ocrConfidence: null,
        sourceStatus: 'AVAILABLE',
      };
    default:
      return null;
  }
}

/**
 * 构造 M05-AC-17/18 的配额 429 响应；重置时间按当前时间 +3 小时，保证相对时间可断言。
 *
 * @returns ProblemDetail 响应
 */
function m05QuotaProblem() {
  return {
    body: {
      type: 'about:blank',
      title: '配额超限',
      status: 429,
      code: 'RICCON-QUOTA-429',
      traceId: nextTraceId(),
      detail: '已达日限额，请稍后恢复。',
      scopeType: 'TENANT',
      window: 'DAILY',
      resetsAt: new Date(Date.now() + 3 * 3_600_000).toISOString(),
    },
    status: 429,
    contentType: 'application/problem+json; charset=utf-8',
  };
}

function handleSendMessage(req, res, conversation, raw) {
  if (scenario === 'm05-quota-429') {
    writeResult(res, m05QuotaProblem());
    return;
  }
  const body = readJson(raw);
  const content = typeof body?.content === 'string' ? body.content.trim() : '';
  const clientMessageId = typeof body?.clientMessageId === 'string' ? body.clientMessageId : '';
  if (!content || content.length > 20000 || clientMessageId.length < 8) {
    writeResult(res, problem(400, 'RICCON-CHAT-400', '消息不合法', '消息内容为空或超出长度限制。'));
    return;
  }

  const requestedFileIds = Array.isArray(body?.fileIds)
    ? body.fileIds.filter((id) => typeof id === 'string')
    : [];
  if (requestedFileIds.length > MAX_FILES_PER_CONVERSATION) {
    writeResult(res, problem(400, 'RICCON-CHAT-400', '附件过多', '单条消息最多附带 5 个文件。'));
    return;
  }

  advanceParses();
  const files = requestedFileIds
    .map((fileId) => state.files.find((file) => file.id === fileId))
    .filter(Boolean)
    .slice(0, MAX_FILES_PER_CONVERSATION);
  const usableFiles = files.filter((file) => file.parse?.status === 'SUCCEEDED');

  const messages = state.messages[conversation.id] ?? [];
  const nextSeq = messages.reduce((max, message) => Math.max(max, message.seq), 0) + 1;
  const userMessage = {
    id: nextId('message'),
    conversationId: conversation.id,
    seq: nextSeq,
    role: 'USER',
    content,
    status: 'COMPLETED',
    clientMessageId,
    citations: [],
    createdAt: new Date().toISOString(),
  };
  const assistantId = nextId('message');
  const assistantMessage = {
    id: assistantId,
    conversationId: conversation.id,
    seq: nextSeq + 1,
    role: 'ASSISTANT',
    content: '',
    status: 'STREAMING',
    citations: [],
    createdAt: new Date().toISOString(),
  };
  state.messages[conversation.id] = [...messages, userMessage, assistantMessage];

  if (!conversation.title) {
    conversation.title = content.length > 20 ? `${content.slice(0, 20)}…` : content;
  }
  conversation.updatedAt = new Date().toISOString();
  conversation.lastMessageAt = conversation.updatedAt;

  const scenarioCitation = m05ScenarioCitation();
  const citations =
    scenario === 'citation-source-404'
      ? [unavailableSourceCitation()]
      : scenarioCitation
        ? [scenarioCitation]
        : usableFiles.slice(0, 2).map((file, index) => citationForFile(file, index + 1));
  const wantsNoCitation = /无引用|无资料|没有资料/.test(content);
  const effectiveCitations = wantsNoCitation ? [] : citations;
  const answer = buildAnswer(content, effectiveCitations);
  const answerChunks = splitAnswer(answer);
  const wantsFailure = /失败|出错|error/i.test(content);

  const runId = nextId('run');
  const run = {
    runId,
    conversationId: conversation.id,
    assistantId,
    messageId: assistantId,
    content: '',
    citations: [],
    queue: [
      createEvent(runId, assistantId, 'riccon.chat.started.v1', {
        modelId: DEMO_MODEL_ID,
        modelKey: 'stub-fake-model',
      }),
      ...(effectiveCitations.length > 0
        ? [
            createEvent(runId, assistantId, 'riccon.chat.citation.v1', {
              citations: effectiveCitations,
            }),
          ]
        : []),
      ...answerChunks.map((delta) =>
        createEvent(runId, assistantId, 'riccon.chat.delta.v1', { delta }),
      ),
      wantsFailure
        ? createEvent(runId, assistantId, 'riccon.chat.failed.v1', {
            code: 'RICCON-MODEL-500',
            message: '合成模型调用失败，请重试。',
            retryable: true,
          })
        : createEvent(runId, assistantId, 'riccon.chat.completed.v1', {
            finishReason: 'stop',
            firstTokenMs: DELTA_INTERVAL_MS,
          }),
    ],
    cursor: 0,
    timer: null,
    closed: false,
  };
  activeRuns.set(runId, run);

  // 运行详情快照独立于活动 SSE 运行保存；完成后仍可通过 GET /api/v1/runs/{runId} 读取。
  const startedAt = new Date().toISOString();
  state.runs[runId] = {
    id: runId,
    kind: 'CHAT',
    status: 'RUNNING',
    currentStage: null,
    attempt: 1,
    errorCode: null,
    errorMessage: null,
    startedAt,
    finishedAt: null,
    modelInvocations: [
      {
        id: nextId('invocation'),
        modelId: DEMO_MODEL_ID,
        modelKey: 'stub-fake-model',
        purpose: 'CHAT',
        status: 'STARTED',
        firstTokenMs: null,
        durationMs: null,
        promptTokens: null,
        completionTokens: null,
        errorCode: null,
        startedAt,
        finishedAt: null,
      },
    ],
    stages: [],
  };

  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-store',
    Connection: 'keep-alive',
    'X-Accel-Buffering': 'no',
  });

  let controllerClosed = false;
  const push = (event) => {
    if (controllerClosed) {
      return;
    }
    try {
      res.write(encodeEvent(event));
    } catch {
      controllerClosed = true;
    }
  };
  const close = () => {
    if (controllerClosed) {
      return;
    }
    controllerClosed = true;
    try {
      res.end();
    } catch {
      // 客户端已断开时无需再次结束响应。
    }
  };

  run.timer = setInterval(() => {
    const event = run.queue[run.cursor];
    run.cursor += 1;
    if (!event) {
      finishRun(run, 'COMPLETED');
      close();
      return;
    }
    if (event.eventType === 'riccon.chat.delta.v1') {
      run.content += event.data.delta;
    } else if (event.eventType === 'riccon.chat.citation.v1') {
      run.citations = [...run.citations, ...event.data.citations];
    }
    push(event);
    if (event.eventType === 'riccon.chat.completed.v1') {
      finishRun(run, 'COMPLETED');
      close();
    } else if (event.eventType === 'riccon.chat.failed.v1') {
      finishRun(run, 'FAILED');
      close();
    }
  }, DELTA_INTERVAL_MS);

  // 客户端 abort（用户停止或刷新）时保留已生成内容，并结束服务端运行。
  req.on('close', () => {
    if (!run.closed) {
      finishRun(run, 'STOPPED');
    }
    controllerClosed = true;
  });
}

/* -------------------------------------------------------------------------- */
/* J2 报告任务                                                                 */
/* -------------------------------------------------------------------------- */

/**
 * 根据主题选择演示路径；关键词只服务 E2E 失败/重试场景。
 *
 * @param topic 调研主题
 * @returns success | tool-failure | generation-failure | no-sources
 */
function researchModeForTopic(topic) {
  if (/工具失败|超时/.test(topic)) {
    return 'tool-failure';
  }
  if (/生成失败/.test(topic)) {
    return 'generation-failure';
  }
  if (/无结果|没有资料/.test(topic)) {
    return 'no-sources';
  }
  return 'success';
}

/** 构造两条知识库命中。 */
function buildDemoSources() {
  return [
    {
      ordinal: 1,
      sourceType: 'KNOWLEDGE',
      knowledgeDocumentId: DEMO_KNOWLEDGE_ID_ONE,
      chunkId: null,
      locatorType: 'PARAGRAPH',
      paragraphNo: 4,
      title: '知识库：2025 中国企业协同办公市场研究',
      snippet: '2025 年国内协同办公市场规模约为 320 亿元，同比增长 18%，头部厂商份额继续集中。',
      score: 0.92,
    },
    {
      ordinal: 2,
      sourceType: 'KNOWLEDGE',
      knowledgeDocumentId: DEMO_KNOWLEDGE_ID_TWO,
      chunkId: null,
      locatorType: 'PARAGRAPH',
      paragraphNo: 9,
      title: '知识库：协同办公行业竞争格局摘录',
      snippet: '中腰部厂商通过行业解决方案与私有化部署差异化竞争，客户续费率是主要观察指标。',
      score: 0.84,
    },
  ];
}

/**
 * 构造审批记录。
 *
 * @param task 内部任务
 * @param attempt 工具阶段尝试号
 * @param now 当前毫秒时间
 * @returns 待审批记录
 */
function createResearchApproval(task, attempt, now) {
  const paramsPreview = {
    market: task.task.topic.slice(0, 60),
    region: '华东',
    period: '2025',
    fields: 'market_size,growth_rate',
  };
  return {
    id: nextId('approval'),
    researchTaskId: task.task.id,
    runId: task.task.currentRunId ?? nextId('run'),
    stage: 'TOOL',
    attempt,
    status: 'PENDING',
    actionType: 'MCP_TOOL_CALL',
    toolName: 'market_data_lookup',
    paramsPreview,
    paramsSha256: pseudoSha256(JSON.stringify(paramsPreview)),
    requestedBy: DEMO_USER_ID,
    decidedBy: null,
    decidedAt: null,
    decisionComment: null,
    expiresAt: new Date(now + 15 * 60 * 1000).toISOString(),
    createdAt: new Date(now).toISOString(),
  };
}

/**
 * 生成报告 Markdown。
 *
 * @param title 标题
 * @param sources 检索来源
 * @param invocation 成功的工具调用
 * @returns 报告正文
 */
function buildReportBody(title, sources, invocation) {
  const sourceTitles = sources.map((source) => `${source.title}（[${source.ordinal}]）`);
  return [
    `# ${title}`,
    '',
    '## 摘要',
    '本报告基于租户知识库检索与一次经人工审批的只读市场数据调用生成，结论仅用于演示，不代表真实市场数据。',
    '',
    '## 市场规模与增长',
    '2025 年国内协同办公市场规模约为 320 亿元，同比增长 18%[1]。',
    '- 头部厂商依靠生态与私有化交付能力保持份额',
    '- 中腰部厂商通过行业方案与数据工具提升续费率',
    '',
    '## 竞争格局',
    '竞争焦点从通用功能转向行业数据、权限隔离与可审计的 Agent 能力[2]。',
    invocation
      ? `MCP 工具 ${invocation.toolName} 返回的演示数据用于核对市场规模与增速[3]。`
      : '本次报告未使用外部工具数据。',
    '',
    '## 结论与建议',
    '建议先以部门级知识库和只读工具验证闭环，再逐步开放写操作与跨部门共享。',
    '',
    '## 数据来源',
    ...sourceTitles.map((item) => `- ${item}`),
    ...(invocation
      ? [`- MCP 工具 ${invocation.toolName}（耗时 ${invocation.durationMs ?? 0} ms）`]
      : []),
  ].join('\n');
}

/**
 * 确保任务只有一份产物；重试沿用同一产物，不重复递增版本。
 *
 * @param task 内部任务
 * @param now 当前毫秒时间
 */
function ensureResearchArtifact(task, now) {
  if (task.artifact) {
    return;
  }
  const invocation =
    [...task.invocations].reverse().find((item) => item.status === 'SUCCEEDED') ?? null;
  const title = `${task.task.topic} · 市场调研报告`.slice(0, 300);
  const body = buildReportBody(title, task.sources, invocation);
  const nowIso = new Date(now).toISOString();
  const citations = [
    ...task.sources.map((source) => ({
      ordinal: source.ordinal,
      sourceType: 'KNOWLEDGE',
      knowledgeDocumentId: source.knowledgeDocumentId ?? null,
      title: source.title,
      snippet: source.snippet,
      locatorType: source.locatorType ?? null,
      pageNo: source.pageNo ?? null,
      sheetName: source.sheetName ?? null,
      paragraphNo: source.paragraphNo ?? null,
    })),
    ...(invocation
      ? [
          {
            ordinal: task.sources.length + 1,
            sourceType: 'MCP',
            mcpInvocationId: invocation.id,
            title: `MCP：${invocation.toolName}`,
            snippet: '返回 3 条市场规模与增速记录（演示数据）。',
            locatorType: null,
            pageNo: null,
            sheetName: null,
            paragraphNo: null,
          },
        ]
      : []),
  ];
  const artifactId = nextId('artifact');
  task.artifact = {
    id: artifactId,
    workspaceId: task.task.workspaceId,
    researchTaskId: task.task.id,
    kind: 'REPORT',
    title,
    status: 'SAVED',
    currentVersion: 1,
    createdAt: nowIso,
    updatedAt: nowIso,
    currentVersionDetail: {
      id: nextId('artifact-version'),
      version: 1,
      contentType: 'text/markdown',
      sizeBytes: Math.max(1, body.length),
      sha256: pseudoSha256(body),
      summary: '报告基于知识库检索与经审批的市场数据工具生成，覆盖市场规模、竞争格局与建议。',
      createdAt: nowIso,
      citations,
    },
  };
  task.artifactBody = body;
}

/**
 * 完成检索阶段；无结果时任务失败且不进入生成。
 *
 * @param task 内部任务
 * @param now 当前毫秒时间
 */
function completeResearchRetrieval(task, now) {
  const nowIso = new Date(now).toISOString();
  const record = task.stages.find((stage) => stage.stage === 'RETRIEVAL' && stage.attempt === 1);
  if (record) {
    record.finishedAt = nowIso;
  }
  if (task.mode === 'no-sources') {
    if (record) {
      record.status = 'FAILED';
      record.errorCode = 'RICCON-RESEARCH-404';
    }
    task.task.status = 'FAILED';
    task.task.currentStage = null;
    task.task.failureCode = 'RICCON-RESEARCH-404';
    task.task.failureMessage = '未检索到相关资料，请调整主题后重新创建任务。';
    task.task.finishedAt = nowIso;
    task.phase = 'failed';
    return;
  }
  if (record) {
    record.status = 'SUCCEEDED';
    record.details = { hitCount: 2 };
  }
  task.sources = buildDemoSources();
  task.task.status = 'WAITING_APPROVAL';
  task.task.currentStage = 'APPROVAL';
  task.approvals.push(createResearchApproval(task, 1, now));
  task.phase = 'waiting-approval';
  task.phaseStartedAtMs = now;
}

/**
 * 完成工具阶段；成功进入生成，失败保留记录并标记任务失败。
 *
 * @param task 内部任务
 * @param now 当前毫秒时间
 */
function completeResearchTool(task, now) {
  const nowIso = new Date(now).toISOString();
  const invocation = task.invocations.find((item) => item.status === 'STARTED');
  const record = task.stages
    .filter((stage) => stage.stage === 'TOOL' && stage.status === 'RUNNING')
    .sort((left, right) => right.attempt - left.attempt)[0];

  if (task.mode === 'tool-failure') {
    if (record) {
      record.status = 'FAILED';
      record.errorCode = 'RICCON-MCP-504';
      record.finishedAt = nowIso;
    }
    if (invocation) {
      invocation.status = 'FAILED';
      invocation.errorCode = 'RICCON-MCP-504';
      invocation.durationMs = Math.max(0, now - Date.parse(invocation.startedAt));
      invocation.finishedAt = nowIso;
    }
    task.task.status = 'FAILED';
    task.task.currentStage = null;
    task.task.failureCode = 'RICCON-MCP-504';
    task.task.failureMessage = '外部数据工具响应超时（合成），请重试工具阶段。';
    task.task.finishedAt = nowIso;
    task.phase = 'failed';
    return;
  }

  if (record) {
    record.status = 'SUCCEEDED';
    record.finishedAt = nowIso;
    record.details = {
      ...(record.details ?? {}),
      resultSummary: '返回 3 条市场规模与增速记录（演示数据）。',
    };
  }
  if (invocation) {
    invocation.status = 'SUCCEEDED';
    invocation.resultSha256 = pseudoSha256('demo-market-data-result');
    invocation.durationMs = Math.max(0, now - Date.parse(invocation.startedAt));
    invocation.finishedAt = nowIso;
  }
  task.task.status = 'RUNNING';
  task.task.currentStage = 'GENERATION';
  const attempt = task.stages.filter((stage) => stage.stage === 'GENERATION').length + 1;
  task.stages.push({
    stage: 'GENERATION',
    attempt,
    status: 'RUNNING',
    startedAt: nowIso,
    finishedAt: null,
    details: null,
  });
  task.phase = 'generation';
  task.phaseStartedAtMs = now;
}

/**
 * 完成生成阶段；失败保留阶段记录，成功产出唯一产物。
 *
 * @param task 内部任务
 * @param now 当前毫秒时间
 */
function completeResearchGeneration(task, now) {
  const nowIso = new Date(now).toISOString();
  const record = task.stages
    .filter((stage) => stage.stage === 'GENERATION' && stage.status === 'RUNNING')
    .sort((left, right) => right.attempt - left.attempt)[0];

  if (task.mode === 'generation-failure') {
    if (record) {
      record.status = 'FAILED';
      record.errorCode = 'RICCON-MODEL-500';
      record.finishedAt = nowIso;
    }
    task.task.status = 'FAILED';
    task.task.currentStage = null;
    task.task.failureCode = 'RICCON-MODEL-500';
    task.task.failureMessage = '报告生成失败（合成），已完成阶段记录已保留。';
    task.task.finishedAt = nowIso;
    task.phase = 'failed';
    return;
  }

  if (record) {
    record.status = 'SUCCEEDED';
    record.finishedAt = nowIso;
  }
  ensureResearchArtifact(task, now);
  task.task.status = 'SUCCEEDED';
  task.task.currentStage = null;
  task.task.finishedAt = nowIso;
  task.phase = 'done';
}

/**
 * 按真实时间推进所有任务；每次读取前调用，循环补完多个已到期阶段。
 */
function advanceResearchTasks() {
  const now = Date.now();
  for (const task of state.researchTasks) {
    let progressed = true;
    while (progressed) {
      progressed = false;
      if (task.phase === 'retrieval' && now - task.phaseStartedAtMs >= RESEARCH_RETRIEVAL_MS) {
        completeResearchRetrieval(task, now);
        progressed = true;
      } else if (task.phase === 'tool' && now - task.phaseStartedAtMs >= RESEARCH_TOOL_MS) {
        completeResearchTool(task, now);
        progressed = true;
      } else if (
        task.phase === 'generation' &&
        now - task.phaseStartedAtMs >= RESEARCH_GENERATION_MS
      ) {
        completeResearchGeneration(task, now);
        progressed = true;
      }
    }
  }
}

/** 构造任务摘要。 */
function researchTaskSummary(task) {
  return { ...task.task };
}

/** 阶段排序：有开始时间在前，其后按尝试号。 */
function compareStages(left, right) {
  const leftTime = left.startedAt ? Date.parse(left.startedAt) : Number.MAX_SAFE_INTEGER;
  const rightTime = right.startedAt ? Date.parse(right.startedAt) : Number.MAX_SAFE_INTEGER;
  if (leftTime !== rightTime) {
    return leftTime - rightTime;
  }
  return left.attempt - right.attempt;
}

/** 构造任务详情；只暴露契约字段。 */
function researchTaskDetail(task) {
  return {
    ...task.task,
    stages: [...task.stages].sort(compareStages),
    approvals: [...task.approvals].sort(
      (left, right) => Date.parse(left.createdAt) - Date.parse(right.createdAt),
    ),
    mcpInvocations: [...task.invocations].sort(
      (left, right) => Date.parse(left.startedAt) - Date.parse(right.startedAt),
    ),
    sources: [...task.sources],
    artifact: task.artifact,
  };
}

/** 查找任务。 */
function findResearchTask(taskId) {
  return state.researchTasks.find((item) => item.task.id === taskId);
}

/**
 * 创建报告任务。
 *
 * @param body 请求体
 * @param idempotencyKey 幂等键
 * @returns 响应
 */
function createResearchTask(body, idempotencyKey, workspaceId) {
  if (idempotencyKey.length < 8 || idempotencyKey.length > 128) {
    return problem(
      400,
      'RICCON-RESEARCH-400',
      '缺少幂等键',
      '创建报告任务必须带有效的 Idempotency-Key。',
    );
  }
  const idempotencyKeyForUser = `${DEMO_USER_ID}:${idempotencyKey}`;
  const existingId = state.researchIdempotency[idempotencyKeyForUser];
  if (existingId) {
    const existing = findResearchTask(existingId);
    if (existing) {
      return json(researchTaskSummary(existing), 201);
    }
  }

  const topic = typeof body?.topic === 'string' ? body.topic.trim() : '';
  const mcpToolId = typeof body?.mcpToolId === 'string' ? body.mcpToolId : '';
  if (topic.length < 4 || topic.length > 500) {
    return problem(400, 'RICCON-RESEARCH-400', '主题不合法', '调研主题需为 4–500 个字符。');
  }
  const tool = state.mcpTools.find((item) => item.id === mcpToolId && item.status === 'ACTIVE');
  if (!tool) {
    return problem(403, 'RICCON-MCP-403', '工具不可用', '所选 MCP 工具未授权或已停用。');
  }

  const now = Date.now();
  const nowIso = new Date(now).toISOString();
  const taskId = nextId('research');
  const task = {
    task: {
      id: taskId,
      workspaceId,
      topic,
      status: 'RUNNING',
      currentStage: 'RETRIEVAL',
      mcpToolId,
      currentRunId: nextId('run'),
      failureCode: null,
      failureMessage: null,
      createdAt: nowIso,
      startedAt: nowIso,
      finishedAt: null,
    },
    mode: researchModeForTopic(topic),
    phase: 'retrieval',
    phaseStartedAtMs: now,
    stages: [
      {
        stage: 'RETRIEVAL',
        attempt: 1,
        status: 'RUNNING',
        startedAt: nowIso,
        finishedAt: null,
        details: { query: topic.slice(0, 80) },
      },
    ],
    approvals: [],
    invocations: [],
    sources: [],
    artifact: null,
    artifactBody: null,
  };
  state.researchTasks.unshift(task);
  state.researchIdempotency[idempotencyKeyForUser] = taskId;
  return json(researchTaskSummary(task), 201);
}

/**
 * 应用审批决定。
 *
 * @param approvalId 审批 ID
 * @param decision APPROVE | REJECT
 * @param comment 备注
 * @returns 响应
 */
function decideResearchApproval(approvalId, decision, comment) {
  advanceResearchTasks();
  const task = state.researchTasks.find((item) =>
    item.approvals.some((approval) => approval.id === approvalId),
  );
  const approval = task?.approvals.find((item) => item.id === approvalId);
  if (!task || !approval) {
    return problem(404, 'RICCON-TENANT-404', '审批不存在', '审批不存在或已被处理。');
  }
  if (approval.status !== 'PENDING') {
    const alreadyDecided = approval.status === (decision === 'APPROVE' ? 'APPROVED' : 'REJECTED');
    return alreadyDecided
      ? json(approval)
      : problem(409, 'RICCON-APPROVAL-409', '审批已处理', '该审批已作出相反决定。');
  }

  const now = Date.now();
  const nowIso = new Date(now).toISOString();
  approval.status = decision === 'APPROVE' ? 'APPROVED' : 'REJECTED';
  approval.decidedBy = DEMO_USER_ID;
  approval.decidedAt = nowIso;
  approval.decisionComment = comment;

  if (decision === 'REJECT') {
    task.stages.push({
      stage: 'APPROVAL',
      attempt: approval.attempt,
      status: 'FAILED',
      errorCode: 'RICCON-APPROVAL-REJECTED',
      startedAt: approval.createdAt,
      finishedAt: nowIso,
      details: null,
    });
    task.stages.push({
      stage: 'TOOL',
      attempt: approval.attempt,
      status: 'SKIPPED',
      startedAt: null,
      finishedAt: null,
      details: null,
    });
    task.stages.push({
      stage: 'GENERATION',
      attempt: 1,
      status: 'SKIPPED',
      startedAt: null,
      finishedAt: null,
      details: null,
    });
    task.task.status = 'CANCELLED';
    task.task.currentStage = null;
    task.task.failureCode = 'RICCON-APPROVAL-REJECTED';
    task.task.failureMessage = '审批被拒绝，任务已终止，未生成报告。';
    task.task.finishedAt = nowIso;
    task.phase = 'cancelled';
    return json(approval);
  }

  task.stages.push({
    stage: 'APPROVAL',
    attempt: approval.attempt,
    status: 'SUCCEEDED',
    startedAt: approval.createdAt,
    finishedAt: nowIso,
    details: { approvalId: approval.id },
  });
  task.stages.push({
    stage: 'TOOL',
    attempt: approval.attempt,
    status: 'RUNNING',
    startedAt: nowIso,
    finishedAt: null,
    details: { toolName: approval.toolName, paramsPreview: approval.paramsPreview },
  });
  const invocationId = nextId('invocation');
  const runningToolRecord = task.stages
    .filter((stage) => stage.stage === 'TOOL' && stage.status === 'RUNNING')
    .sort((left, right) => right.attempt - left.attempt)[0];
  if (runningToolRecord?.details) {
    runningToolRecord.details.invocationId = invocationId;
  }
  task.invocations.push({
    id: invocationId,
    toolName: approval.toolName,
    status: 'STARTED',
    paramsSha256: approval.paramsSha256,
    resultSha256: null,
    durationMs: null,
    errorCode: null,
    startedAt: nowIso,
    finishedAt: null,
  });
  task.task.status = 'RUNNING';
  task.task.currentStage = 'TOOL';
  task.task.failureCode = null;
  task.task.failureMessage = null;
  task.task.finishedAt = null;
  task.phase = 'tool';
  task.phaseStartedAtMs = now;
  return json(approval);
}

/**
 * 重试失败阶段。
 *
 * @param taskId 任务 ID
 * @param fromStage 请求阶段
 * @returns 响应
 */
function retryResearchTask(taskId, fromStage) {
  advanceResearchTasks();
  const task = findResearchTask(taskId);
  if (!task) {
    return problem(404, 'RICCON-TENANT-404', '任务不存在', '报告任务不存在或已被删除。');
  }
  if (task.task.status !== 'FAILED') {
    return problem(409, 'RICCON-RESEARCH-409', '任务不可重试', '只有失败任务可以重试。');
  }
  const lastFailedStage = [...task.stages]
    .reverse()
    .find((stage) => stage.status === 'FAILED')?.stage;
  const stage = fromStage ?? lastFailedStage ?? null;
  if (stage !== 'TOOL' && stage !== 'GENERATION') {
    return problem(
      409,
      'RICCON-RESEARCH-409',
      '阶段不可重试',
      '检索阶段失败需调整主题后重新创建任务。',
    );
  }

  const now = Date.now();
  const nowIso = new Date(now).toISOString();
  task.task.status = stage === 'TOOL' ? 'WAITING_APPROVAL' : 'RUNNING';
  task.task.currentStage = stage === 'TOOL' ? 'APPROVAL' : 'GENERATION';
  task.task.failureCode = null;
  task.task.failureMessage = null;
  task.task.finishedAt = null;
  task.mode = 'success';

  if (stage === 'TOOL') {
    const attempt =
      Math.max(
        0,
        ...task.stages.filter((item) => item.stage === 'TOOL').map((item) => item.attempt),
      ) + 1;
    task.approvals.push(createResearchApproval(task, attempt, now));
    task.phase = 'waiting-approval';
    task.phaseStartedAtMs = now;
  } else {
    const attempt =
      Math.max(
        0,
        ...task.stages.filter((item) => item.stage === 'GENERATION').map((item) => item.attempt),
      ) + 1;
    task.stages.push({
      stage: 'GENERATION',
      attempt,
      status: 'RUNNING',
      startedAt: nowIso,
      finishedAt: null,
      details: null,
    });
    task.phase = 'generation';
    task.phaseStartedAtMs = now;
  }
  return json(researchTaskSummary(task), 202);
}

/* -------------------------------------------------------------------------- */
/* HTTP 路由与传输                                                             */
/* -------------------------------------------------------------------------- */

/**
 * 写响应。
 *
 * @param res Node 响应
 * @param result `{status, body, contentType}` 或 SSE 已自行写出
 */
function writeResult(res, result) {
  const payload = Buffer.from(JSON.stringify(result.body));
  res.writeHead(result.status, {
    'Content-Type': result.contentType ?? 'application/json; charset=utf-8',
    'Content-Length': payload.length,
    'Cache-Control': 'no-store',
  });
  res.end(payload);
}

/**
 * 读取请求体。
 *
 * @param req Node 请求
 * @returns 原始字节
 */
function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let total = 0;
    req.on('data', (chunk) => {
      total += chunk.length;
      // 预留 multipart 头开销；超过上限直接拒绝，避免 E2E 误传超大文件占满内存。
      if (total > MAX_FILE_BYTES + 1024 * 1024) {
        reject(Object.assign(new Error('body too large'), { code: 'TOO_LARGE' }));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

/**
 * 解析请求 URL。
 *
 * @param req Node 请求
 * @returns pathname 与查询参数
 */
function parseUrl(req) {
  return new URL(req.url ?? '/', `http://${req.headers.host ?? `${HOST}:${PORT}`}`);
}

/**
 * 处理控制端点；不属于产品 OpenAPI，供 Playwright 隔离用例与制造错误场景。
 *
 * @param req Node 请求
 * @param res Node 响应
 * @param pathname 路径
 * @param raw 请求体
 * @returns 是否已处理
 */
function handleControl(req, res, pathname, raw) {
  if (pathname === '/__stub__/health' && req.method === 'GET') {
    writeResult(res, json({ ok: true }));
    return true;
  }
  if (pathname === '/__stub__/reset' && req.method === 'POST') {
    resetAll();
    writeResult(res, json({ ok: true }));
    return true;
  }
  if (pathname === '/__stub__/scenario' && req.method === 'POST') {
    const body = readJson(raw);
    scenario = typeof body?.name === 'string' ? body.name : 'none';
    writeResult(res, json({ ok: true, scenario }));
    return true;
  }
  if (pathname === '/__stub__/m03-seed' && req.method === 'POST') {
    seedM03State();
    writeResult(res, json({ ok: true, departments: state.departments.length }));
    return true;
  }
  if (pathname === '/__stub__/m05-seed' && req.method === 'POST') {
    seedM05State();
    writeResult(res, json({ ok: true, documents: state.knowledge.documents.length }));
    return true;
  }
  if (pathname === '/__stub__/m05-audit' && req.method === 'GET') {
    writeResult(res, json({ ok: true, items: state.knowledge.audit }));
    return true;
  }
  if (pathname === '/__stub__/m04-seed' && req.method === 'POST') {
    const body = readJson(raw);
    seedM04State(body?.empty === true);
    writeResult(
      res,
      json({
        ok: true,
        empty: state.m04.empty,
        providers: state.m04.providers.length,
        models: state.m04.models.length,
        credentials: state.m04.credentials.length,
        routing: {
          CHAT: state.m04.routing.CHAT.entries.length,
          REPORT: state.m04.routing.REPORT.entries.length,
        },
      }),
    );
    return true;
  }
  if (pathname === '/__stub__/revoke-sessions' && req.method === 'POST') {
    // 模拟会话在服务端失效：保留业务数据，只清空会话表。
    sessions.clear();
    writeResult(res, json({ ok: true }));
    return true;
  }
  return false;
}

/**
 * 处理预签名对象下载；对象地址是绝对 URL，因此需要 CORS。
 *
 * 覆盖 J1 文件下载与 J2 产物正文；文件内容为合成字节，仅验证预签名链路。
 *
 * @param req Node 请求
 * @param res Node 响应
 * @param pathname 路径
 * @returns 是否已处理
 */
function handleObject(req, res, pathname) {
  const fileMatch = /^\/objects\/files\/([^/]+)$/.exec(pathname);
  if (fileMatch && req.method === 'GET') {
    const file = state.files.find((item) => item.id === decodeURIComponent(fileMatch[1]));
    if (!file) {
      const result = problem(404, 'RICCON-TENANT-404', '文件不存在', '文件不存在或已被删除。');
      const payload = Buffer.from(JSON.stringify(result.body));
      res.writeHead(404, {
        'Content-Type': result.contentType,
        'Content-Length': payload.length,
        'Access-Control-Allow-Origin': '*',
        'Cache-Control': 'no-store',
      });
      res.end(payload);
      return true;
    }
    // 上传内容不落盘，下载返回可识别的合成字节；仅用于验证预签名下载链路。
    const payload = Buffer.from(`Riccon E2E stub file: ${file.originalName}\n`, 'utf8');
    res.writeHead(200, {
      'Content-Type': file.mimeType || 'application/octet-stream',
      'Content-Length': payload.length,
      'Content-Disposition': `attachment; filename="${encodeURIComponent(file.originalName)}"`,
      'Access-Control-Allow-Origin': '*',
      'Cache-Control': 'no-store',
    });
    res.end(payload);
    return true;
  }

  const match = /^\/objects\/artifacts\/([^/]+)\/versions\/(\d+)\/content$/.exec(pathname);
  if (!match || req.method !== 'GET') {
    return false;
  }
  const artifactId = decodeURIComponent(match[1]);
  const version = Number(match[2]);
  const task = state.researchTasks.find(
    (item) => item.artifact?.id === artifactId && item.artifact.currentVersion === version,
  );
  if (!task?.artifactBody) {
    const result = problem(
      404,
      'RICCON-TENANT-404',
      '产物正文不存在',
      '产物正文不存在或已被删除。',
    );
    const payload = Buffer.from(JSON.stringify(result.body));
    res.writeHead(404, {
      'Content-Type': result.contentType,
      'Content-Length': payload.length,
      'Access-Control-Allow-Origin': '*',
      'Cache-Control': 'no-store',
    });
    res.end(payload);
    return true;
  }
  const payload = Buffer.from(task.artifactBody, 'utf8');
  res.writeHead(200, {
    'Content-Type': 'text/markdown; charset=utf-8',
    'Content-Length': payload.length,
    'Access-Control-Allow-Origin': '*',
    'Cache-Control': 'no-store',
  });
  res.end(payload);
  return true;
}

/**
 * M04 分页响应。
 *
 * @param {object[]} items 全部条目
 * @param {URLSearchParams} searchParams 查询参数
 * @returns {object} PageMeta + items
 */
function m04Page(items, searchParams) {
  const page = Math.max(1, Number(searchParams.get('page') ?? 1));
  const size = Math.min(100, Math.max(1, Number(searchParams.get('size') ?? 20)));
  const start = (page - 1) * size;
  return {
    page,
    size,
    total: items.length,
    totalPages: items.length === 0 ? 0 : Math.ceil(items.length / size),
    items: items.slice(start, start + size),
  };
}

/**
 * 平台管理员校验。
 *
 * @param {object} session 会话
 * @returns {object|null} 403 响应或 null
 */
function requirePlatformAdmin(session) {
  if (currentAccount(session)?.persona !== 'platform-admin') {
    return problem(
      403,
      'RICCON-AUTH-403',
      '没有权限',
      '只有平台管理员可以管理模型网关与平台配额。',
    );
  }
  return null;
}

/**
 * 租户管理员校验；平台管理员访问租户只读端点同样拒绝。
 *
 * @param {object} session 会话
 * @returns {object|null} 403 响应或 null
 */
function requireTenantAdmin(session) {
  if (currentAccount(session)?.persona !== 'tenant-admin') {
    return problem(403, 'RICCON-AUTH-403', '没有权限', '只有租户管理员可以读取本租户配额与成本。');
  }
  return null;
}

/**
 * M04 错误态注入；只影响浏览器 E2E 的 5xx 状态。
 *
 * @param {string} kind 页面类型
 * @param {string} code 稳定错误码
 * @returns {object|null} 500 响应或 null
 */
function m04ScenarioError(kind, code) {
  if (scenario !== `m04-${kind}-500`) {
    return null;
  }
  return problem(500, code, '服务暂不可用', 'E2E 注入的 M04 错误态。');
}

/**
 * 供应商响应。
 *
 * @param {object} provider 供应商
 * @returns {object} OpenAPI ModelProvider
 */
function providerResponse(provider) {
  const m04 = state.m04;
  return {
    id: provider.id,
    code: provider.code,
    name: provider.name,
    protocol: provider.protocol,
    baseUrl: provider.baseUrl,
    environmentLabel: provider.environmentLabel,
    allowed: provider.allowed,
    activeCredentialCount: m04.credentials.filter(
      (credential) => credential.providerId === provider.id && credential.status === 'ACTIVE',
    ).length,
    modelCount: m04.models.filter((model) => model.providerId === provider.id).length,
    status: provider.status,
  };
}

/**
 * 平台模型响应。
 *
 * @param {object} model 模型
 * @returns {object} OpenAPI AdminModel
 */
function adminModelResponse(model) {
  const provider = state.m04.providers.find((item) => item.id === model.providerId);
  const priced = model.inputPricePer1M !== null && model.outputPricePer1M !== null;
  return {
    id: model.id,
    modelKey: model.modelKey,
    displayName: model.displayName,
    providerId: model.providerId,
    providerCode: provider?.code ?? 'unknown',
    environmentLabel: provider?.environmentLabel ?? 'PRODUCTION',
    contextWindow: model.contextWindow,
    maxOutputTokens: model.maxOutputTokens,
    capabilities: model.capabilities ?? null,
    inputPricePer1M: model.inputPricePer1M,
    outputPricePer1M: model.outputPricePer1M,
    priceCurrency: model.priceCurrency,
    pricingStatus: priced ? 'PRICED' : 'UNPRICED',
    status: model.status,
  };
}

/**
 * 员工可见模型；不返回 provider、价格或 baseUrl。
 *
 * @param {object} model 模型
 * @returns {object} OpenAPI EmployeeModel
 */
function employeeModelResponse(model) {
  return {
    id: model.id,
    modelKey: model.modelKey,
    displayName: model.displayName,
    contextWindow: model.contextWindow,
    maxOutputTokens: model.maxOutputTokens,
    capabilities: model.capabilities ?? null,
    status: model.status,
  };
}

/**
 * 路由链响应。
 *
 * @param {string} purpose CHAT|REPORT
 * @returns {object} OpenAPI ModelRouting
 */
function routingResponse(purpose) {
  const chain = state.m04.routing[purpose];
  const entries = chain.entries
    .map((modelId, index) => {
      const model = state.m04.models.find((item) => item.id === modelId);
      if (!model) {
        return null;
      }
      const provider = state.m04.providers.find((item) => item.id === model.providerId);
      return {
        position: index + 1,
        modelId: model.id,
        displayName: model.displayName,
        status: model.status,
        environmentLabel: provider?.environmentLabel ?? 'PRODUCTION',
      };
    })
    .filter(Boolean);
  return {
    purpose,
    version: chain.version,
    entries,
    updatedAt: chain.updatedAt,
    updatedBy: chain.updatedBy,
  };
}

/**
 * 凭据掩码响应；永不返回 apiKey/ciphertext。
 *
 * @param {object} credential 凭据
 * @param {boolean} [changed] 状态变更接口的结果
 * @returns {object} OpenAPI ModelCredential
 */
function credentialResponse(credential, changed) {
  return {
    id: credential.id,
    providerId: credential.providerId,
    label: credential.label,
    maskedHint: credential.maskedHint,
    status: credential.status,
    priority: credential.priority,
    version: credential.version,
    consecutiveFailures: credential.consecutiveFailures,
    cooldownUntil: credential.cooldownUntil,
    ...(changed === undefined ? {} : { changed }),
    createdAt: credential.createdAt,
    updatedAt: credential.updatedAt,
    rotatedAt: credential.rotatedAt,
    lastUsedAt: credential.lastUsedAt,
  };
}

/**
 * 网关开关响应。
 *
 * @returns {object} OpenAPI ModelGatewaySwitches
 */
function switchResponse() {
  return { ...state.m04.switches };
}

/**
 * 配额窗口响应。
 *
 * @param {object} daily 日窗口
 * @param {object} monthly 月窗口
 * @returns {object[]} 两个 QuotaWindowState
 */
function quotaWindows(daily, monthly) {
  return [
    {
      window: 'DAILY',
      limit: daily.limit,
      used: daily.used,
      resetsAt: M04_DAILY_RESET,
      source: daily.source,
    },
    {
      window: 'MONTHLY',
      limit: monthly.limit,
      used: monthly.used,
      resetsAt: M04_MONTHLY_RESET,
      source: monthly.source,
    },
  ];
}

/**
 * 租户配额响应。
 *
 * @param {string} tenantId 租户代码
 * @param {boolean} includeName 是否包含租户名
 * @returns {object} OpenAPI TenantQuota
 */
function tenantQuotaResponse(tenantId, includeName) {
  const quota = state.m04.tenantQuotas[tenantId] ?? {
    daily: { limit: null, used: 0, source: 'DEPLOYMENT_DEFAULT' },
    monthly: { limit: null, used: 0, source: 'DEPLOYMENT_DEFAULT' },
  };
  const tenant = state.tenants.find((item) => item.id === tenantId);
  return {
    tenantId,
    tenantCode: tenantId,
    tenantName: includeName ? (tenant?.name ?? null) : null,
    windows: quotaWindows(quota.daily, quota.monthly),
  };
}

/**
 * 用户配额响应。
 *
 * @param {object} user 用户
 * @returns {object} OpenAPI UserQuota
 */
function userQuotaResponse(user) {
  const quota = state.m04.userQuotas[user.id] ?? {
    daily: { limit: null, used: 0, source: 'DEPLOYMENT_DEFAULT' },
    monthly: { limit: null, used: 0, source: 'DEPLOYMENT_DEFAULT' },
  };
  return {
    userId: user.id,
    username: user.username,
    displayName: user.displayName,
    windows: quotaWindows(quota.daily, quota.monthly),
  };
}

/**
 * 成本 micros 转换；金额字符串按 6 位小数解析，避免浮点求和误差。
 *
 * @param {string|null} amount 金额
 * @returns {number} micros
 */
function amountToMicros(amount) {
  if (!amount) {
    return 0;
  }
  const [integer, fraction = ''] = amount.split('.');
  return Number(integer) * 1_000_000 + Number(fraction.padEnd(6, '0').slice(0, 6));
}

/**
 * micros 转 6 位小数金额。
 *
 * @param {number} micros micros
 * @returns {string} 金额
 */
function microsToAmount(micros) {
  return (micros / 1_000_000).toFixed(6);
}

/**
 * 成本聚合；按 groupBy 生成 buckets 与 totals。
 *
 * @param {object[]} invocations 调用
 * @param {string} groupBy USER|MODEL|DAY|TENANT
 * @param {boolean} tenantView 是否租户报告
 * @returns {object} totals 与 items
 */
function costBuckets(invocations, groupBy, tenantView) {
  const buckets = new Map();
  const totals = {
    currency: 'CNY',
    amount: 0,
    pricedCalls: 0,
    unpricedCalls: 0,
    usageUnknownCalls: 0,
    currencyMismatchCalls: 0,
    unbucketedCalls: 0,
  };
  for (const invocation of invocations) {
    let key;
    let extras;
    if (groupBy === 'USER') {
      key = invocation.userId;
      extras = {
        userId: invocation.userId,
        userDisplayName: invocation.userDisplayName,
        tenantId: tenantView ? null : invocation.tenantId,
        tenantCode: tenantView ? null : invocation.tenantId,
      };
    } else if (groupBy === 'MODEL') {
      key = invocation.modelId;
      extras = {
        modelId: invocation.modelId,
        modelDisplayName: invocation.modelDisplayName,
        tenantId: tenantView ? null : invocation.tenantId,
        tenantCode: tenantView ? null : invocation.tenantId,
      };
    } else if (groupBy === 'DAY') {
      key = invocation.day;
      extras = {
        day: invocation.day,
        tenantId: tenantView ? null : invocation.tenantId,
        tenantCode: tenantView ? null : invocation.tenantId,
      };
    } else {
      key = invocation.tenantId;
      extras = { tenantId: invocation.tenantId, tenantCode: invocation.tenantId };
    }
    if (!buckets.has(key)) {
      buckets.set(key, {
        key: String(key),
        tenantId: null,
        tenantCode: null,
        modelId: null,
        modelDisplayName: null,
        ...(tenantView ? { userId: null, userDisplayName: null } : {}),
        day: null,
        amount: 0,
        pricedCalls: 0,
        unpricedCalls: 0,
        usageUnknownCalls: 0,
      });
    }
    const bucket = buckets.get(key);
    if (extras.modelId !== undefined) {
      bucket.modelId = extras.modelId;
      bucket.modelDisplayName = extras.modelDisplayName;
    }
    if (extras.day !== undefined) {
      bucket.day = extras.day;
    }
    if (tenantView && extras.userId !== undefined) {
      bucket.userId = extras.userId;
      bucket.userDisplayName = extras.userDisplayName;
    }
    if (!tenantView) {
      bucket.tenantId = extras.tenantId;
      bucket.tenantCode = extras.tenantCode;
    }
    if (invocation.pricingStatus === 'PRICED') {
      bucket.pricedCalls += 1;
      bucket.amount += amountToMicros(invocation.amount);
      totals.pricedCalls += 1;
      totals.amount += amountToMicros(invocation.amount);
    } else if (invocation.pricingStatus === 'UNPRICED') {
      bucket.unpricedCalls += 1;
      totals.unpricedCalls += 1;
    } else {
      bucket.usageUnknownCalls += 1;
      totals.usageUnknownCalls += 1;
    }
  }
  const items = [...buckets.values()].map((bucket) => ({
    ...bucket,
    amount: microsToAmount(bucket.amount),
  }));
  return { totals: { ...totals, amount: microsToAmount(totals.amount) }, items };
}

/**
 * 成本报告页。
 *
 * @param {object[]} invocations 调用
 * @param {string} groupBy 聚合维度
 * @param {boolean} tenantView 是否租户报告
 * @param {URLSearchParams} searchParams 查询
 * @returns {object} OpenAPI TenantCostReport / PlatformCostReport
 */
function costReport(invocations, groupBy, tenantView, searchParams) {
  const { totals, items } = costBuckets(invocations, groupBy, tenantView);
  const page = m04Page(items, searchParams);
  return {
    ...page,
    from: searchParams.get('from') ?? '2026-10-23',
    to: searchParams.get('to') ?? '2026-11-21',
    groupBy,
    currency: 'CNY',
    currencyMinorUnit: 2,
    disclaimer: M04_COST_DISCLAIMER,
    totals,
    items: page.items,
  };
}

/**
 * 成本明细响应。
 *
 * @param {object} invocation 调用
 * @returns {object} OpenAPI CostInvocation
 */
function costInvocationResponse(invocation) {
  return {
    id: invocation.id,
    modelId: invocation.modelId,
    modelDisplayName: invocation.modelDisplayName,
    userId: invocation.userId,
    userDisplayName: invocation.userDisplayName,
    startedAt: invocation.startedAt,
    promptTokens: invocation.promptTokens,
    completionTokens: invocation.completionTokens,
    inputPricePer1M: invocation.inputPricePer1M,
    outputPricePer1M: invocation.outputPricePer1M,
    priceCurrency: invocation.priceCurrency,
    amount: invocation.amount,
    pricingStatus: invocation.pricingStatus,
  };
}

/**
 * 解析配额限额：null 表示明确不限，0 等价不限但仍为 EXPLICIT。
 *
 * @param {unknown} value 限额
 * @returns {{limit: number|null, source: string}} 解析结果
 * @throws {Error} 非法值
 */
function parseQuotaLimit(value) {
  if (value === null) {
    return { limit: null, source: 'EXPLICIT_UNLIMITED' };
  }
  if (!Number.isInteger(value) || value < 0) {
    throw new Error('limit must be a non-negative integer or null');
  }
  return { limit: value, source: 'EXPLICIT' };
}

/**
 * M04 API 路由；不属于 M02 stub 子集，只服务 B11a/B11b 的模型/配额/成本页面。
 *
 * @param {object} req Node 请求
 * @param {object} res Node 响应
 * @param {URL} url 解析后的 URL
 * @param {Buffer} raw 请求体
 * @param {object} session 会话
 * @returns {boolean} 是否已处理
 */
function handleM04Api(req, res, url, raw, session) {
  const { pathname, searchParams } = url;
  const method = req.method ?? 'GET';
  const m04 = state.m04;

  // 员工可见模型目录：任何已登录角色可读，不返回价格/供应商。
  if (pathname === '/api/v1/models' && method === 'GET') {
    const items = m04.empty ? [] : m04.models.filter((model) => model.status === 'ACTIVE');
    writeResult(res, json(m04Page(items.map(employeeModelResponse), searchParams)));
    return true;
  }

  // ---------- 平台模型目录 ----------
  if (pathname === '/api/v1/admin/model-providers' && method === 'GET') {
    const forbidden = requirePlatformAdmin(session);
    if (forbidden) {
      writeResult(res, forbidden);
      return true;
    }
    const failure = m04ScenarioError('providers', 'RICCON-MODEL-500');
    if (failure) {
      writeResult(res, failure);
      return true;
    }
    writeResult(res, json(m04Page(m04.providers.map(providerResponse), searchParams)));
    return true;
  }
  const providerMatch = /^\/api\/v1\/admin\/model-providers\/([^/]+)$/.exec(pathname);
  if (providerMatch && method === 'PUT') {
    const forbidden = requirePlatformAdmin(session);
    if (forbidden) {
      writeResult(res, forbidden);
      return true;
    }
    const csrfError = requireCsrf(session, req);
    if (csrfError) {
      writeResult(res, csrfError);
      return true;
    }
    const provider = m04.providers.find((item) => item.id === decodeURIComponent(providerMatch[1]));
    if (!provider) {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '端点不存在', '端点不存在。'));
      return true;
    }
    const body = readJson(raw);
    if (body && Object.hasOwn(body, 'baseUrl')) {
      const failure = problem(
        400,
        'RICCON-MODEL-400',
        '参数无效',
        'baseUrl 是部署管理字段，不可通过 API 修改。',
      );
      failure.body.reason = 'ENDPOINT_IMMUTABLE_DEPLOYMENT_MANAGED';
      failure.body.field = 'baseUrl';
      writeResult(res, failure);
      return true;
    }
    if (body && Object.hasOwn(body, 'environmentLabel')) {
      const failure = problem(
        400,
        'RICCON-MODEL-400',
        '参数无效',
        'environmentLabel 由部署配置管理。',
      );
      failure.body.reason = 'ENDPOINT_IMMUTABLE_DEPLOYMENT_MANAGED';
      failure.body.field = 'environmentLabel';
      writeResult(res, failure);
      return true;
    }
    const name = typeof body?.name === 'string' ? body.name.trim() : '';
    if (!name) {
      writeResult(res, problem(400, 'RICCON-MODEL-400', '参数无效', 'name 不能为空。'));
      return true;
    }
    provider.name = name;
    writeResult(res, json(providerResponse(provider)));
    return true;
  }

  if (pathname === '/api/v1/admin/models' && method === 'GET') {
    const forbidden = requirePlatformAdmin(session);
    if (forbidden) {
      writeResult(res, forbidden);
      return true;
    }
    const failure = m04ScenarioError('models', 'RICCON-MODEL-500');
    if (failure) {
      writeResult(res, failure);
      return true;
    }
    writeResult(res, json(m04Page(m04.models.map(adminModelResponse), searchParams)));
    return true;
  }
  const modelMatch = /^\/api\/v1\/admin\/models\/([^/]+)$/.exec(pathname);
  if (modelMatch && method === 'PATCH') {
    const forbidden = requirePlatformAdmin(session);
    if (forbidden) {
      writeResult(res, forbidden);
      return true;
    }
    const csrfError = requireCsrf(session, req);
    if (csrfError) {
      writeResult(res, csrfError);
      return true;
    }
    const model = m04.models.find((item) => item.id === decodeURIComponent(modelMatch[1]));
    if (!model) {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '模型不存在', '模型不存在。'));
      return true;
    }
    const body = readJson(raw);
    if (!['ACTIVE', 'DISABLED'].includes(body?.status)) {
      writeResult(
        res,
        problem(400, 'RICCON-MODEL-400', '参数无效', 'status 必须是 ACTIVE 或 DISABLED。'),
      );
      return true;
    }
    model.status = body.status;
    model.updatedAt = new Date().toISOString();
    writeResult(res, json(adminModelResponse(model)));
    return true;
  }

  // ---------- 路由链 ----------
  if (pathname === '/api/v1/admin/model-routing' && method === 'GET') {
    const forbidden = requirePlatformAdmin(session);
    if (forbidden) {
      writeResult(res, forbidden);
      return true;
    }
    const failure = m04ScenarioError('routing', 'RICCON-MODEL-500');
    if (failure) {
      writeResult(res, failure);
      return true;
    }
    writeResult(res, json({ items: [routingResponse('CHAT'), routingResponse('REPORT')] }));
    return true;
  }
  const routingMatch = /^\/api\/v1\/admin\/model-routing\/([^/]+)$/.exec(pathname);
  if (routingMatch && method === 'PUT') {
    const forbidden = requirePlatformAdmin(session);
    if (forbidden) {
      writeResult(res, forbidden);
      return true;
    }
    const csrfError = requireCsrf(session, req);
    if (csrfError) {
      writeResult(res, csrfError);
      return true;
    }
    const purpose = decodeURIComponent(routingMatch[1]).toUpperCase();
    if (!['CHAT', 'REPORT'].includes(purpose)) {
      writeResult(
        res,
        problem(400, 'RICCON-MODEL-400', '参数无效', 'purpose 必须是 CHAT 或 REPORT。'),
      );
      return true;
    }
    const body = readJson(raw);
    if (!body || !Number.isInteger(body.version) || !Array.isArray(body.modelIds)) {
      writeResult(res, problem(400, 'RICCON-MODEL-400', '参数无效', 'version 与 modelIds 必填。'));
      return true;
    }
    const chain = m04.routing[purpose];
    if (body.version !== chain.version) {
      const failure = problem(409, 'RICCON-MODEL-409', '版本冲突', '路由配置已被其他管理员修改。');
      failure.body.reason = 'VERSION_CONFLICT';
      failure.body.currentVersion = chain.version;
      writeResult(res, failure);
      return true;
    }
    if (body.modelIds.length < 1 || body.modelIds.length > 20) {
      writeResult(
        res,
        problem(400, 'RICCON-MODEL-400', '参数无效', '路由链必须包含 1..20 个模型。'),
      );
      return true;
    }
    const unique = new Set(body.modelIds);
    if (unique.size !== body.modelIds.length) {
      writeResult(res, problem(400, 'RICCON-MODEL-400', '参数无效', '路由链模型不能重复。'));
      return true;
    }
    for (const modelId of body.modelIds) {
      if (!m04.models.some((model) => model.id === modelId)) {
        const failure = problem(400, 'RICCON-MODEL-400', '参数无效', '路由链包含未知模型。');
        failure.body.reason = 'UNKNOWN_MODEL';
        writeResult(res, failure);
        return true;
      }
    }
    chain.entries = [...body.modelIds];
    chain.version += 1;
    chain.updatedAt = new Date().toISOString();
    chain.updatedBy = currentAccount(session)?.userId ?? null;
    writeResult(res, json(routingResponse(purpose)));
    return true;
  }

  // ---------- key 池 ----------
  const credentialsMatch =
    /^\/api\/v1\/admin\/model-providers\/([^/]+)\/credentials(?:\/([^/]+)(?:\/(rotate|disable|enable))?)?$/.exec(
      pathname,
    );
  if (credentialsMatch) {
    const forbidden = requirePlatformAdmin(session);
    if (forbidden) {
      writeResult(res, forbidden);
      return true;
    }
    const providerId = decodeURIComponent(credentialsMatch[1]);
    const credentialId = credentialsMatch[2] ? decodeURIComponent(credentialsMatch[2]) : null;
    const action = credentialsMatch[3] ?? null;
    const provider = m04.providers.find((item) => item.id === providerId);
    if (!provider) {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '端点不存在', '端点不存在。'));
      return true;
    }
    if (!credentialId && method === 'GET') {
      const failure = m04ScenarioError('credentials', 'RICCON-MODEL-500');
      if (failure) {
        writeResult(res, failure);
        return true;
      }
      const items = m04.credentials.filter((credential) => credential.providerId === providerId);
      writeResult(
        res,
        json(
          m04Page(
            items.map((item) => credentialResponse(item)),
            searchParams,
          ),
        ),
      );
      return true;
    }
    const csrfError = requireCsrf(session, req);
    if (csrfError) {
      writeResult(res, csrfError);
      return true;
    }
    if (!credentialId && method === 'POST') {
      const body = readJson(raw);
      const label = typeof body?.label === 'string' ? body.label.trim() : '';
      const apiKey = typeof body?.apiKey === 'string' ? body.apiKey : '';
      const priority = body?.priority ?? 100;
      if (!label || label.length > 64) {
        writeResult(res, problem(400, 'RICCON-MODEL-400', '参数无效', 'label 必须为 1..64 字符。'));
        return true;
      }
      if (apiKey.length < 16 || apiKey.length > 512) {
        writeResult(
          res,
          problem(400, 'RICCON-MODEL-400', '参数无效', 'apiKey 必须为 16..512 字符。'),
        );
        return true;
      }
      if (!Number.isInteger(priority) || priority < 1 || priority > 1000) {
        writeResult(res, problem(400, 'RICCON-MODEL-400', '参数无效', 'priority 必须为 1..1000。'));
        return true;
      }
      if (m04.credentials.some((credential) => credential.apiKey === apiKey)) {
        const failure = problem(409, 'RICCON-MODEL-409', '凭据冲突', '相同 key 已存在。');
        failure.body.reason = 'DUPLICATE_CREDENTIAL';
        writeResult(res, failure);
        return true;
      }
      const now = new Date().toISOString();
      const credential = {
        id: nextId('credential'),
        providerId,
        label,
        apiKey,
        maskedHint: `****${apiKey.slice(-4)}`,
        status: 'ACTIVE',
        priority,
        version: 1,
        consecutiveFailures: 0,
        cooldownUntil: null,
        createdAt: now,
        updatedAt: now,
        rotatedAt: null,
        lastUsedAt: null,
      };
      m04.credentials.push(credential);
      writeResult(res, json(credentialResponse(credential), 201));
      return true;
    }
    const credential = m04.credentials.find(
      (item) => item.providerId === providerId && item.id === credentialId,
    );
    if (!credential) {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '凭据不存在', '凭据不存在。'));
      return true;
    }
    const body = action === 'rotate' ? readJson(raw) : null;
    if (action === 'rotate' && method === 'POST') {
      const apiKey = typeof body?.apiKey === 'string' ? body.apiKey : '';
      if (apiKey.length < 16 || apiKey.length > 512) {
        writeResult(
          res,
          problem(400, 'RICCON-MODEL-400', '参数无效', 'apiKey 必须为 16..512 字符。'),
        );
        return true;
      }
      if (m04.credentials.some((item) => item.apiKey === apiKey)) {
        const failure = problem(409, 'RICCON-MODEL-409', '凭据冲突', '相同 key 已存在。');
        failure.body.reason = 'DUPLICATE_CREDENTIAL';
        writeResult(res, failure);
        return true;
      }
      const now = new Date().toISOString();
      credential.status = 'DISABLED';
      credential.updatedAt = now;
      const rotated = {
        ...credential,
        id: nextId('credential'),
        label:
          typeof body?.label === 'string' && body.label.trim()
            ? body.label.trim()
            : credential.label,
        apiKey,
        maskedHint: `****${apiKey.slice(-4)}`,
        status: 'ACTIVE',
        version: credential.version + 1,
        consecutiveFailures: 0,
        cooldownUntil: null,
        createdAt: now,
        updatedAt: now,
        rotatedAt: now,
        lastUsedAt: null,
      };
      m04.credentials.push(rotated);
      writeResult(res, json(credentialResponse(rotated, true)));
      return true;
    }
    if (action === 'disable' && method === 'POST') {
      if (credential.status === 'DISABLED') {
        writeResult(res, json(credentialResponse(credential, false)));
        return true;
      }
      credential.status = 'DISABLED';
      credential.version += 1;
      credential.updatedAt = new Date().toISOString();
      writeResult(res, json(credentialResponse(credential, true)));
      return true;
    }
    if (action === 'enable' && method === 'POST') {
      if (credential.status === 'ACTIVE') {
        writeResult(res, json(credentialResponse(credential, false)));
        return true;
      }
      credential.status = 'ACTIVE';
      credential.version += 1;
      credential.consecutiveFailures = 0;
      credential.cooldownUntil = null;
      credential.updatedAt = new Date().toISOString();
      writeResult(res, json(credentialResponse(credential, true)));
      return true;
    }
    writeResult(res, problem(404, 'RICCON-NOT-FOUND', '接口不存在', '凭据子操作不存在。'));
    return true;
  }

  // ---------- 网关开关 ----------
  if (pathname === '/api/v1/admin/model-gateway/switches' && method === 'GET') {
    const forbidden = requirePlatformAdmin(session);
    if (forbidden) {
      writeResult(res, forbidden);
      return true;
    }
    const failure = m04ScenarioError('switches', 'RICCON-MODEL-500');
    if (failure) {
      writeResult(res, failure);
      return true;
    }
    if (scenario === 'm04-switches-env-override') {
      m04.switches.source = 'ENVIRONMENT';
      m04.switches.envOverride = { routingMode: 'BASELINE' };
    }
    writeResult(res, json(switchResponse()));
    return true;
  }
  if (pathname === '/api/v1/admin/model-gateway/switches' && method === 'PUT') {
    const forbidden = requirePlatformAdmin(session);
    if (forbidden) {
      writeResult(res, forbidden);
      return true;
    }
    const csrfError = requireCsrf(session, req);
    if (csrfError) {
      writeResult(res, csrfError);
      return true;
    }
    if (scenario === 'm04-switches-env-override' || m04.switches.source === 'ENVIRONMENT') {
      const failure = problem(
        409,
        'RICCON-MODEL-409',
        '环境变量覆盖生效',
        '部署环境覆盖开关时不可在线修改。',
      );
      failure.body.reason = 'ENV_OVERRIDE_ACTIVE';
      writeResult(res, failure);
      return true;
    }
    const body = readJson(raw);
    if (
      !body ||
      !Number.isInteger(body.version) ||
      !['ROUTED', 'BASELINE'].includes(body.routingMode) ||
      typeof body.failoverEnabled !== 'boolean' ||
      typeof body.quotaEnforcementEnabled !== 'boolean'
    ) {
      writeResult(res, problem(400, 'RICCON-MODEL-400', '参数无效', '开关请求字段不完整。'));
      return true;
    }
    if (body.version !== m04.switches.version) {
      const failure = problem(409, 'RICCON-MODEL-409', '版本冲突', '开关已被其他管理员修改。');
      failure.body.reason = 'VERSION_CONFLICT';
      writeResult(res, failure);
      return true;
    }
    m04.switches = {
      routingMode: body.routingMode,
      failoverEnabled: body.failoverEnabled,
      quotaEnforcementEnabled: body.quotaEnforcementEnabled,
      version: m04.switches.version + 1,
      source: 'DATABASE',
      envOverride: null,
      updatedAt: new Date().toISOString(),
      updatedBy: currentAccount(session)?.userId ?? null,
    };
    writeResult(res, json(switchResponse()));
    return true;
  }

  // ---------- 配额 ----------
  if (pathname === '/api/v1/quotas' && method === 'GET') {
    const forbidden = requireTenantAdmin(session);
    if (forbidden) {
      writeResult(res, forbidden);
      return true;
    }
    const failure = m04ScenarioError('quota', 'RICCON-QUOTA-503');
    if (failure) {
      writeResult(res, failure);
      return true;
    }
    writeResult(res, json(tenantQuotaResponse(currentAccount(session).tenantId, false)));
    return true;
  }
  if (pathname === '/api/v1/quotas/users' && method === 'GET') {
    const forbidden = requireTenantAdmin(session);
    if (forbidden) {
      writeResult(res, forbidden);
      return true;
    }
    const failure = m04ScenarioError('quota', 'RICCON-QUOTA-503');
    if (failure) {
      writeResult(res, failure);
      return true;
    }
    const tenantId = currentAccount(session).tenantId;
    const users = state.users.filter(
      (user) => user.tenantId === tenantId && !targetM04Excluded(user),
    );
    writeResult(res, json(m04Page(users.map(userQuotaResponse), searchParams)));
    return true;
  }
  if (pathname === '/api/v1/admin/quotas' && method === 'GET') {
    const forbidden = requirePlatformAdmin(session);
    if (forbidden) {
      writeResult(res, forbidden);
      return true;
    }
    const failure = m04ScenarioError('quota', 'RICCON-QUOTA-503');
    if (failure) {
      writeResult(res, failure);
      return true;
    }
    const rows = m04.empty
      ? []
      : state.tenants
          .filter((tenant) => tenant.id !== M03_PLATFORM_TENANT)
          .map((tenant) => tenantQuotaResponse(tenant.id, true));
    writeResult(res, json(m04Page(rows, searchParams)));
    return true;
  }
  const tenantQuotaMatch = /^\/api\/v1\/admin\/tenants\/([^/]+)\/quotas$/.exec(pathname);
  if (tenantQuotaMatch && method === 'PUT') {
    const forbidden = requirePlatformAdmin(session);
    if (forbidden) {
      writeResult(res, forbidden);
      return true;
    }
    const csrfError = requireCsrf(session, req);
    if (csrfError) {
      writeResult(res, csrfError);
      return true;
    }
    const tenantId = decodeURIComponent(tenantQuotaMatch[1]);
    const tenant = state.tenants.find((item) => item.id === tenantId);
    if (!tenant) {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '租户不存在', '目标租户不存在。'));
      return true;
    }
    if (tenant.status !== 'ACTIVE') {
      writeResult(res, problem(409, 'RICCON-QUOTA-409', '租户已暂停', '停用租户不可修改配额。'));
      return true;
    }
    try {
      const body = readJson(raw);
      const daily = parseQuotaLimit(body?.limits?.daily ?? null);
      const monthly = parseQuotaLimit(body?.limits?.monthly ?? null);
      m04.tenantQuotas[tenantId] = { daily, monthly };
    } catch (error) {
      writeResult(res, problem(400, 'RICCON-QUOTA-400', '参数无效', String(error)));
      return true;
    }
    writeResult(res, json(tenantQuotaResponse(tenantId, true)));
    return true;
  }
  const userQuotaMatch = /^\/api\/v1\/admin\/tenants\/([^/]+)\/users\/([^/]+)\/quotas$/.exec(
    pathname,
  );
  if (userQuotaMatch && method === 'PUT') {
    const forbidden = requirePlatformAdmin(session);
    if (forbidden) {
      writeResult(res, forbidden);
      return true;
    }
    const csrfError = requireCsrf(session, req);
    if (csrfError) {
      writeResult(res, csrfError);
      return true;
    }
    const tenantId = decodeURIComponent(userQuotaMatch[1]);
    const userId = decodeURIComponent(userQuotaMatch[2]);
    const tenant = state.tenants.find((item) => item.id === tenantId);
    if (!tenant) {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '租户不存在', '目标租户不存在。'));
      return true;
    }
    const user = state.users.find((item) => item.tenantId === tenantId && item.id === userId);
    if (!user) {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '用户不存在', '目标用户不属于该租户。'));
      return true;
    }
    try {
      const body = readJson(raw);
      const daily = parseQuotaLimit(body?.limits?.daily ?? null);
      const monthly = parseQuotaLimit(body?.limits?.monthly ?? null);
      m04.userQuotas[user.id] = { tenantId, daily, monthly };
    } catch (error) {
      writeResult(res, problem(400, 'RICCON-QUOTA-400', '参数无效', String(error)));
      return true;
    }
    writeResult(res, json(userQuotaResponse(user)));
    return true;
  }

  // ---------- 成本 ----------
  if (pathname === '/api/v1/costs' && method === 'GET') {
    const forbidden = requireTenantAdmin(session);
    if (forbidden) {
      writeResult(res, forbidden);
      return true;
    }
    const failure = m04ScenarioError('cost', 'RICCON-COST-503');
    if (failure) {
      writeResult(res, failure);
      return true;
    }
    const groupBy = searchParams.get('groupBy') ?? 'DAY';
    if (!['USER', 'MODEL', 'DAY'].includes(groupBy)) {
      writeResult(
        res,
        problem(400, 'RICCON-COST-400', '参数无效', 'groupBy 必须是 USER/MODEL/DAY。'),
      );
      return true;
    }
    const tenantId = currentAccount(session).tenantId;
    const invocations = m04.costInvocations.filter((item) => item.tenantId === tenantId);
    writeResult(res, json(costReport(invocations, groupBy, true, searchParams)));
    return true;
  }
  if (pathname === '/api/v1/costs/invocations' && method === 'GET') {
    const forbidden = requireTenantAdmin(session);
    if (forbidden) {
      writeResult(res, forbidden);
      return true;
    }
    const failure = m04ScenarioError('cost', 'RICCON-COST-503');
    if (failure) {
      writeResult(res, failure);
      return true;
    }
    const tenantId = currentAccount(session).tenantId;
    const userId = searchParams.get('userId');
    const modelId = searchParams.get('modelId');
    let invocations = m04.costInvocations.filter((item) => item.tenantId === tenantId);
    if (userId) {
      if (!state.users.some((user) => user.tenantId === tenantId && user.id === userId)) {
        writeResult(res, problem(404, 'RICCON-TENANT-404', '用户不存在', '目标用户不属于本租户。'));
        return true;
      }
      invocations = invocations.filter((item) => item.userId === userId);
    }
    if (modelId) {
      invocations = invocations.filter((item) => item.modelId === modelId);
    }
    writeResult(res, json(m04Page(invocations.map(costInvocationResponse), searchParams)));
    return true;
  }
  if (pathname === '/api/v1/admin/costs' && method === 'GET') {
    const forbidden = requirePlatformAdmin(session);
    if (forbidden) {
      writeResult(res, forbidden);
      return true;
    }
    const failure = m04ScenarioError('cost', 'RICCON-COST-503');
    if (failure) {
      writeResult(res, failure);
      return true;
    }
    const groupBy = searchParams.get('groupBy') ?? 'TENANT';
    if (!['TENANT', 'MODEL', 'DAY'].includes(groupBy)) {
      writeResult(
        res,
        problem(400, 'RICCON-COST-400', '参数无效', 'groupBy 必须是 TENANT/MODEL/DAY。'),
      );
      return true;
    }
    const tenantFilter = searchParams.get('tenantId');
    let invocations = m04.costInvocations;
    if (tenantFilter) {
      invocations = invocations.filter((item) => item.tenantId === tenantFilter);
    }
    writeResult(res, json(costReport(invocations, groupBy, false, searchParams)));
    return true;
  }

  return false;
}

/**
 * 过滤 M04 页面不应展示的合成用户（OIDC 脱敏账号只用于 M15）。
 *
 * @param {object} user 用户
 * @returns {boolean} 是否排除
 */
function targetM04Excluded(user) {
  return user.username === 'oidc-alpha' || user.username === 'oidc-beta';
}

/**
 * 处理 API 路由。
 *
 * @param req Node 请求
 * @param res Node 响应
 * @param url 解析后的 URL
 * @param raw 请求体
 * @returns 是否已处理
 */

/**
 * 读取当前会话账号。
 *
 * @param session 会话
 * @returns 账号对象或 null
 */
function currentAccount(session) {
  return session?.account ?? null;
}

/**
 * 查找租户部门。
 *
 * @param tenantId 租户
 * @param departmentId 部门
 * @returns 部门或 undefined
 */
function findDepartment(tenantId, departmentId) {
  return state.departments.find(
    (department) => department.tenantId === tenantId && department.id === departmentId,
  );
}

/**
 * 统计部门当前启用成员数。
 *
 * @param departmentId 部门
 * @returns 成员数
 */
function departmentMemberCount(departmentId) {
  return state.users.filter(
    (user) => user.departmentId === departmentId && user.status === 'ACTIVE',
  ).length;
}

/**
 * 部门响应；只含组织元数据与展示名。
 *
 * @param department 部门
 * @returns Department
 */
function departmentResponse(department) {
  const manager = department.managerUserId
    ? state.users.find((user) => user.id === department.managerUserId)
    : null;
  return {
    id: department.id,
    name: department.name,
    status: department.status,
    managerUserId: department.managerUserId ?? null,
    managerDisplayName: manager?.displayName ?? null,
    memberCount: departmentMemberCount(department.id),
    createdAt: department.createdAt,
    updatedAt: department.updatedAt,
  };
}

/**
 * 部门成员响应；不复用 UserSummary，避免泄露邮箱、角色与最近登录。
 *
 * @param user 账号
 * @returns DepartmentMember
 */
function departmentMemberResponse(user) {
  return {
    id: user.id,
    username: user.username,
    displayName: user.displayName,
    status: user.status,
  };
}

/**
 * 按账号范围列出部门：普通员工只看本人部门，租户管理员看全部。
 *
 * @param account 当前账号
 * @returns 部门列表
 */
function scopedDepartments(account) {
  if (!account) {
    return [];
  }
  if (account.persona === 'tenant-admin') {
    return state.departments.filter((department) => department.tenantId === account.tenantId);
  }
  if (account.persona === 'platform-admin') {
    return [];
  }
  const user = state.users.find((item) => item.id === account.userId);
  return state.departments.filter(
    (department) =>
      department.tenantId === account.tenantId && department.id === user?.departmentId,
  );
}

/**
 * 判断账号是否可管理指定部门：租户管理员或该部门负责人。
 *
 * @param account 账号
 * @param department 部门
 * @returns 可管理返回 true
 */
function canManageDepartment(account, department) {
  if (!account || !department || department.tenantId !== account.tenantId) {
    return false;
  }
  return account.persona === 'tenant-admin' || department.managerUserId === account.userId;
}

/**
 * 判断团队空间是否可被账号访问。
 *
 * @param account 账号
 * @param workspace 团队空间
 * @returns 可访问返回 true
 */
function canAccessTeamWorkspace(account, workspace) {
  if (!account || workspace.tenantId !== account.tenantId) {
    return false;
  }
  const user = state.users.find((item) => item.id === account.userId);
  if (!user?.departmentId) {
    return false;
  }
  if (workspace.visibility === 'TENANT') {
    return true;
  }
  if (workspace.departmentId !== user.departmentId) {
    return false;
  }
  const department = findDepartment(account.tenantId, user.departmentId);
  if (!department || department.status !== 'ACTIVE') {
    return false;
  }
  if (workspace.visibility === 'DEPT') {
    return true;
  }
  if (workspace.visibility === 'CUSTOM') {
    return (state.workspaceMembers[workspace.id] ?? []).includes(account.userId);
  }
  return false;
}

/**
 * 判断空间 ID 是否存在于当前 stub 状态。
 *
 * @param workspaceId 空间 ID
 * @returns 存在返回 true
 */
function isKnownWorkspaceId(workspaceId) {
  return (
    workspaceId === state.workspace.id ||
    state.teamWorkspaces.some((workspace) => workspace.id === workspaceId)
  );
}

/**
 * 判断当前账号是否有权读取指定空间（用于报告任务与详情）。
 *
 * @param account 当前账号
 * @param workspaceId 空间 ID
 * @returns 可访问返回 true
 */
function canAccessWorkspace(account, workspaceId) {
  if (workspaceId === state.workspace.id) {
    return true;
  }
  const workspace = state.teamWorkspaces.find((item) => item.id === workspaceId);
  return workspace ? canAccessTeamWorkspace(account, workspace) : false;
}

/**
 * 团队空间响应。
 *
 * @param workspace 团队空间
 * @returns ManagedWorkspace
 */
function workspaceResponse(workspace) {
  const department = workspace.departmentId
    ? state.departments.find((item) => item.id === workspace.departmentId)
    : null;
  return {
    id: workspace.id,
    kind: 'TEAM',
    name: workspace.name,
    description: workspace.description ?? null,
    ownerUserId: workspace.ownerUserId,
    departmentId: workspace.departmentId ?? null,
    departmentName: department?.name ?? null,
    visibility: workspace.visibility,
    status: workspace.status ?? 'ACTIVE',
    createdAt: workspace.createdAt,
    updatedAt: workspace.updatedAt,
  };
}

/**
 * 当前账号可见的团队空间。
 *
 * @param account 账号
 * @returns 团队空间列表
 */
function accessibleTeamWorkspaces(account) {
  return state.teamWorkspaces.filter((workspace) => canAccessTeamWorkspace(account, workspace));
}

/**
 * 当前账号可见的部门空间管理列表；负责人和租户管理员能看到本部门全部空间。
 *
 * @param account 账号
 * @param department 部门
 * @returns 空间列表
 */
function manageableDepartmentWorkspaces(account, department) {
  if (!canManageDepartment(account, department)) {
    return [];
  }
  return state.teamWorkspaces.filter(
    (workspace) =>
      workspace.tenantId === account.tenantId && workspace.departmentId === department.id,
  );
}

/**
 * 账号所属租户的启用账号列表。
 *
 * @param tenantId 租户
 * @returns 用户列表
 */
function tenantUsers(tenantId) {
  return state.users.filter((user) => user.tenantId === tenantId && user.status === 'ACTIVE');
}

/**
 * 账号摘要响应。
 *
 * 与 OpenAPI `UserSummary` 对齐：`authSource` 默认 LOCAL；`identityProvider` 只在有脱敏展示名时出现，
 * 不输出完整 issuer、subject、邮箱或令牌。stub 数据全部为合成内容。
 *
 * @param user 账号
 * @returns 账号摘要
 */
function userSummaryResponse(user) {
  return {
    id: user.id,
    username: user.username,
    displayName: user.displayName,
    email: user.email ?? null,
    status: user.status,
    roles: user.roles,
    authSource: user.authSource ?? 'LOCAL',
    ...(user.identityProvider ? { identityProvider: user.identityProvider } : {}),
    lastLoginAt: user.lastLoginAt ?? null,
    createdAt: user.createdAt,
  };
}

/**
 * 播种 M03 演示数据：销售部/市场部、负责人、普通成员与一个部门空间。
 *
 * 只由 E2E 控制端点调用，不在 reset 默认状态中，避免影响 M02 用例。
 */
function seedM03State() {
  state.departments = [
    {
      id: M03_SALES_DEPARTMENT_ID,
      tenantId: M03_TENANT_ACME,
      name: '销售部',
      status: 'ACTIVE',
      managerUserId: M03_MANAGER_USER_ID,
      createdAt: BASE_TIME,
      updatedAt: BASE_TIME,
    },
    {
      id: M03_MARKETING_DEPARTMENT_ID,
      tenantId: M03_TENANT_ACME,
      name: '市场部',
      status: 'ACTIVE',
      managerUserId: null,
      createdAt: BASE_TIME,
      updatedAt: BASE_TIME,
    },
    {
      id: M03_ARCHIVED_DEPARTMENT_ID,
      tenantId: M03_TENANT_ACME,
      name: '综合部',
      status: 'DISABLED',
      managerUserId: null,
      createdAt: BASE_TIME,
      updatedAt: BASE_TIME,
    },
  ];
  state.users = state.users.map((user) => {
    if (user.id === M03_MEMBER_USER_ID || user.id === M03_MANAGER_USER_ID) {
      return { ...user, departmentId: M03_SALES_DEPARTMENT_ID };
    }
    if (user.id === M03_OUTSIDER_USER_ID) {
      return { ...user, departmentId: M03_MARKETING_DEPARTMENT_ID };
    }
    if (user.id === M03_DISABLED_USER_ID) {
      return { ...user, departmentId: M03_ARCHIVED_DEPARTMENT_ID };
    }
    return user;
  });
  state.teamWorkspaces = [
    {
      id: M03_SALES_WORKSPACE_ID,
      tenantId: M03_TENANT_ACME,
      name: '销售部项目空间',
      description: '销售部共享空间（E2E 合成数据）',
      ownerUserId: M03_MANAGER_USER_ID,
      departmentId: M03_SALES_DEPARTMENT_ID,
      visibility: 'DEPT',
      status: 'ACTIVE',
      createdAt: BASE_TIME,
      updatedAt: BASE_TIME,
    },
    {
      id: M03_TENANT_WORKSPACE_ID,
      tenantId: M03_TENANT_ACME,
      name: '历史全租户空间',
      description: 'M02 遗留 TENANT 空间（E2E 合成数据）',
      ownerUserId: M03_MANAGER_USER_ID,
      departmentId: null,
      visibility: 'TENANT',
      status: 'ACTIVE',
      createdAt: BASE_TIME,
      updatedAt: BASE_TIME,
    },
  ];
  state.workspaceMembers = {};
  const seededAt = BASE_TIME;
  state.conversations = [
    {
      id: M03_DEPT_CONVERSATION_ID,
      workspaceId: M03_SALES_WORKSPACE_ID,
      title: '销售部部门会话',
      status: 'ACTIVE',
      lastMessageAt: seededAt,
      createdAt: seededAt,
      updatedAt: seededAt,
    },
  ];
  state.messages[M03_DEPT_CONVERSATION_ID] = [
    {
      id: 'message-dept-user-1',
      conversationId: M03_DEPT_CONVERSATION_ID,
      seq: 1,
      role: 'USER',
      content: '请汇总销售部本周客户进展',
      status: 'COMPLETED',
      citations: [],
      createdAt: seededAt,
    },
    {
      id: 'message-dept-assistant-1',
      conversationId: M03_DEPT_CONVERSATION_ID,
      seq: 2,
      role: 'ASSISTANT',
      content: '销售部本周新增 3 家重点客户，详见客户清单。',
      status: 'COMPLETED',
      citations: [],
      createdAt: seededAt,
    },
  ];
  state.files = [
    {
      id: M03_DEPT_FILE_ID,
      workspaceId: M03_SALES_WORKSPACE_ID,
      conversationId: M03_DEPT_CONVERSATION_ID,
      originalName: '销售部客户清单.pdf',
      format: 'PDF',
      mimeType: 'application/pdf',
      sizeBytes: 48,
      sha256: pseudoSha256('销售部客户清单.pdf:48'),
      status: 'AVAILABLE',
      parse: {
        id: 'parse-dept-file-1',
        attempt: 1,
        status: 'SUCCEEDED',
        parserVersion: 'stub-parser-1',
        pageCount: 1,
        sheetCount: null,
        chunkCount: 2,
        errorCode: null,
        errorMessage: null,
        startedAt: seededAt,
        finishedAt: seededAt,
      },
      createdAt: seededAt,
      deletedAt: null,
    },
  ];
  const deptResearchTask = {
    task: {
      id: M03_DEPT_RESEARCH_TASK_ID,
      workspaceId: M03_SALES_WORKSPACE_ID,
      topic: '销售部季度市场调研',
      status: 'SUCCEEDED',
      currentStage: null,
      mcpToolId: DEMO_MCP_TOOL_ID,
      currentRunId: 'run-dept-research-1',
      failureCode: null,
      failureMessage: null,
      createdAt: BASE_TIME,
      startedAt: BASE_TIME,
      finishedAt: BASE_TIME,
    },
    mode: 'success',
    phase: 'done',
    phaseStartedAtMs: Date.parse(BASE_TIME),
    stages: [
      {
        stage: 'RETRIEVAL',
        attempt: 1,
        status: 'SUCCEEDED',
        startedAt: BASE_TIME,
        finishedAt: BASE_TIME,
        details: { query: '销售部季度市场调研' },
      },
    ],
    approvals: [],
    invocations: [
      {
        id: 'invocation-dept-1',
        toolName: 'market_data_lookup',
        status: 'SUCCEEDED',
        paramsSha256: pseudoSha256('销售部季度市场调研'),
        resultSha256: pseudoSha256('销售部季度市场调研-result'),
        durationMs: 120,
        errorCode: null,
        startedAt: BASE_TIME,
        finishedAt: BASE_TIME,
      },
    ],
    sources: [
      {
        ordinal: 1,
        sourceType: 'KNOWLEDGE',
        knowledgeDocumentId: DEMO_KNOWLEDGE_ID_ONE,
        title: '销售部客户清单',
        snippet: '销售部重点客户与季度采购计划摘要。',
        locatorType: 'PAGE',
        pageNo: 1,
        paragraphNo: null,
        sheetName: null,
      },
    ],
    artifact: null,
    artifactBody: null,
  };
  ensureResearchArtifact(deptResearchTask, Date.parse(BASE_TIME));
  state.researchTasks = [deptResearchTask];
}

/**
 * 播种 M05 知识库合成状态。
 *
 * 覆盖 Excel 多表/空表/隐藏表/截断、OCR 四态、ACL 三种范围、创建人例外、已到期文档与无源文件语料。
 * 部门与成员关系只在缺失时补齐，避免覆盖 M03 用例自己播种的数据。
 */
function seedM05State() {
  if (state.departments.length === 0) {
    state.departments = [
      {
        id: M03_SALES_DEPARTMENT_ID,
        tenantId: M03_TENANT_ACME,
        name: '销售部',
        status: 'ACTIVE',
        managerUserId: M03_MANAGER_USER_ID,
        createdAt: BASE_TIME,
        updatedAt: BASE_TIME,
      },
      {
        id: M03_MARKETING_DEPARTMENT_ID,
        tenantId: M03_TENANT_ACME,
        name: '市场部',
        status: 'ACTIVE',
        managerUserId: null,
        createdAt: BASE_TIME,
        updatedAt: BASE_TIME,
      },
      {
        id: M03_ARCHIVED_DEPARTMENT_ID,
        tenantId: M03_TENANT_ACME,
        name: '综合部',
        status: 'DISABLED',
        managerUserId: null,
        createdAt: BASE_TIME,
        updatedAt: BASE_TIME,
      },
    ];
    state.users = state.users.map((user) => {
      if (user.id === M03_MEMBER_USER_ID || user.id === M03_MANAGER_USER_ID) {
        return { ...user, departmentId: M03_SALES_DEPARTMENT_ID };
      }
      if (user.id === M03_OUTSIDER_USER_ID) {
        return { ...user, departmentId: M03_MARKETING_DEPARTMENT_ID };
      }
      if (user.id === M03_DISABLED_USER_ID) {
        return { ...user, departmentId: M03_ARCHIVED_DEPARTMENT_ID };
      }
      return user;
    });
  }

  const createdAt = BASE_TIME;
  /**
   * 构造知识库文档；未给出的字段使用 M05 默认值。
   *
   * @param overrides 覆盖字段
   * @returns 内部文档记录
   */
  const document = (overrides) => ({
    tenantId: M03_TENANT_ACME,
    sourceType: 'UPLOAD',
    status: 'INDEXED',
    checksum: pseudoSha256(overrides.title ?? 'm05-document'),
    mimeType: 'application/pdf',
    sizeBytes: 4096,
    errorCode: null,
    errorMessage: null,
    indexedAt: createdAt,
    createdAt,
    visibility: 'TENANT',
    departmentId: null,
    userIds: [],
    creatorUserId: M03_ADMIN_USER_ID,
    parseEngine: 'PDFBOX',
    warnings: [],
    parseSummary: null,
    sourceFileAccessLimited: false,
    retentionExpiresAt: M05_RETENTION_EXPIRES,
    retentionDays: 180,
    retentionPolicy: 'DEPLOYMENT_DEFAULT',
    deletedAt: null,
    deletedReason: null,
    ...overrides,
  });

  state.knowledge = {
    seeded: true,
    documents: [
      document({
        id: M05_DOC_EXCEL_MULTI,
        title: '销售数据.xlsx',
        fileId: M05_FILE_EXCEL,
        mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        sizeBytes: 8192,
        parseEngine: 'POI',
        warnings: ['HIDDEN_SHEETS_SKIPPED:1'],
        parseSummary: {
          sheets: [
            {
              name: '销售数据',
              cellRange: 'A1:F20',
              empty: false,
              indexed: true,
              indexedRange: 'A1:F20',
            },
            {
              name: '汇总',
              cellRange: 'A1:D8',
              empty: false,
              indexed: true,
              indexedRange: 'A1:D8',
            },
            { name: '空表', cellRange: null, empty: true, indexed: true, indexedRange: null },
          ],
          hiddenSheetCount: 1,
          ocr: null,
        },
      }),
      document({
        id: M05_DOC_EXCEL_TRUNCATED,
        title: '明细大表.xlsx',
        fileId: M05_FILE_EXCEL,
        mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        sizeBytes: 16384,
        parseEngine: 'POI',
        warnings: ['ROWS_TRUNCATED:明细'],
        parseSummary: {
          sheets: [
            {
              name: '明细',
              cellRange: 'A1:Z200000',
              empty: false,
              indexed: true,
              indexedRange: 'A1:Z50000',
            },
            {
              name: '附件',
              cellRange: 'A1:B10',
              empty: false,
              indexed: false,
              indexedRange: null,
            },
          ],
          hiddenSheetCount: 0,
          ocr: null,
        },
      }),
      document({
        id: M05_DOC_EXCEL_CORRUPT,
        title: '损坏数据.xlsx',
        fileId: M05_FILE_EXCEL,
        mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        sizeBytes: 512,
        status: 'FAILED',
        errorCode: 'KB_PARSE_CORRUPT',
        errorMessage: 'at java.util.zip.ZipFile.read(ZipFile.java:1)',
        indexedAt: null,
        parseEngine: 'POI',
      }),
      document({
        id: M05_DOC_OCR_OK,
        title: '扫描合同.png',
        fileId: M05_FILE_OCR,
        mimeType: 'image/png',
        sizeBytes: 20480,
        parseEngine: 'TESSERACT',
        parseSummary: {
          sheets: [],
          hiddenSheetCount: 0,
          ocr: { averageConfidence: 0.93, lowConfidencePages: [], failedPages: [] },
        },
      }),
      document({
        id: M05_DOC_OCR_LOW,
        title: '模糊票据.jpg',
        fileId: M05_FILE_OCR,
        mimeType: 'image/jpeg',
        sizeBytes: 18432,
        parseEngine: 'TESSERACT',
        warnings: ['LOW_CONFIDENCE', 'LOW_CONFIDENCE_PAGES:[1]'],
        parseSummary: {
          sheets: [],
          hiddenSheetCount: 0,
          ocr: { averageConfidence: 0.52, lowConfidencePages: [1], failedPages: [] },
        },
      }),
      document({
        id: M05_DOC_OCR_PARTIAL,
        title: '多页扫描.pdf',
        fileId: M05_FILE_OCR,
        mimeType: 'application/pdf',
        sizeBytes: 30720,
        parseEngine: 'TESSERACT',
        warnings: ['FAILED_PAGES:[2]'],
        parseSummary: {
          sheets: [],
          hiddenSheetCount: 0,
          ocr: { averageConfidence: 0.88, lowConfidencePages: [], failedPages: [2] },
        },
      }),
      document({
        id: M05_DOC_OCR_FAILED,
        title: '全失败扫描.pdf',
        fileId: M05_FILE_OCR,
        mimeType: 'application/pdf',
        sizeBytes: 10240,
        status: 'FAILED',
        errorCode: 'KB_OCR_FAILED',
        errorMessage: 'OCR 识别失败（已脱敏）。',
        indexedAt: null,
        parseEngine: 'TESSERACT',
      }),
      document({
        id: M05_DOC_OCR_DISABLED,
        title: '图片-OCR关闭.png',
        fileId: M05_FILE_OCR,
        mimeType: 'image/png',
        sizeBytes: 9216,
        status: 'FAILED',
        errorCode: 'KB_OCR_DISABLED',
        errorMessage: null,
        indexedAt: null,
        parseEngine: 'NONE',
      }),
      document({
        id: M05_DOC_ACL_USERS,
        title: '销售制度.docx',
        fileId: M05_FILE_ACL,
        mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        sizeBytes: 6144,
        visibility: 'USERS',
        userIds: [M03_MEMBER_USER_ID],
        parseEngine: 'PDFBOX',
      }),
      document({
        id: M05_DOC_ACL_DEPT,
        title: '部门手册.pdf',
        fileId: M05_FILE_ACL,
        visibility: 'DEPT',
        departmentId: M03_SALES_DEPARTMENT_ID,
      }),
      document({
        id: M05_DOC_ACL_CREATOR,
        title: '李强的方案.docx',
        fileId: M05_FILE_ACL,
        mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        visibility: 'USERS',
        userIds: [M03_MEMBER_USER_ID],
        creatorUserId: M03_MANAGER_USER_ID,
        parseEngine: 'PDFBOX',
      }),
      document({
        id: M05_DOC_TENANT,
        title: '全员公告.pdf',
        fileId: M05_FILE_ACL,
      }),
      document({
        id: M05_DOC_EXPIRED,
        title: '过期合同.pdf',
        fileId: M05_FILE_ACL,
        status: 'DELETED',
        deletedAt: '2026-11-01T00:00:00+08:00',
        deletedReason: 'RETENTION',
        retentionExpiresAt: M05_RETENTION_EXPIRED,
      }),
      document({
        id: M05_DOC_NO_SOURCE,
        title: '种子语料：行业知识',
        sourceType: 'SEED',
        fileId: null,
        creatorUserId: null,
        parseEngine: 'NONE',
      }),
    ],
    audit: [],
  };
}

/**
 * 返回账号的有效权限；与 sessionInfo 的员工权限收敛保持一致。
 *
 * @param account 登录账号
 * @returns 权限码数组
 */
function m05Permissions(account) {
  if (!account) {
    return [];
  }
  const isMemberPersona = ['member', 'manager', 'outsider'].includes(account.persona);
  return isMemberPersona ? MEMBER_PERMISSIONS : (account.permissions ?? []);
}

/**
 * 判断账号是否可读取知识库文档（含创建人与租户管理员管理性读取）。
 *
 * @param account 登录账号
 * @param document 文档
 * @returns 可见返回 true
 */
function m05CanSee(account, document) {
  if (!account || document.tenantId !== account.tenantId || document.status === 'DELETED') {
    return false;
  }
  if (document.creatorUserId && document.creatorUserId === account.userId) {
    return true;
  }
  if (m05Permissions(account).includes('knowledge.acl.manage')) {
    return true;
  }
  if (document.visibility === 'TENANT') {
    return true;
  }
  if (document.visibility === 'DEPT') {
    const user = state.users.find((item) => item.id === account.userId);
    const department = findDepartment(document.tenantId, document.departmentId);
    return department?.status === 'ACTIVE' && user?.departmentId === document.departmentId;
  }
  if (document.visibility === 'USERS') {
    return document.userIds.includes(account.userId);
  }
  return false;
}

/**
 * 判断账号是否通过自身范围（而非管理性读取）可见文档。
 *
 * @param account 登录账号
 * @param document 文档
 * @returns 自然可见返回 true
 */
function m05NaturallyVisible(account, document) {
  if (!account) {
    return false;
  }
  if (document.creatorUserId && document.creatorUserId === account.userId) {
    return true;
  }
  if (document.visibility === 'TENANT') {
    return true;
  }
  if (document.visibility === 'DEPT') {
    const user = state.users.find((item) => item.id === account.userId);
    const department = findDepartment(document.tenantId, document.departmentId);
    return department?.status === 'ACTIVE' && user?.departmentId === document.departmentId;
  }
  return document.visibility === 'USERS' && document.userIds.includes(account.userId);
}

/**
 * 返回文档的 ACL 响应。
 *
 * @param document 文档
 * @returns OpenAPI KnowledgeDocumentAcl
 */
function m05AclResponse(document) {
  const users = [];
  if (document.visibility === 'USERS') {
    if (document.creatorUserId) {
      users.push({
        userId: document.creatorUserId,
        displayName: m05DisplayName(document.creatorUserId),
        creator: true,
      });
    }
    for (const userId of document.userIds) {
      if (userId !== document.creatorUserId) {
        users.push({ userId, displayName: m05DisplayName(userId) });
      }
    }
  }
  return {
    visibility: document.visibility,
    departmentId: document.visibility === 'DEPT' ? document.departmentId : null,
    users,
    creatorUserId: document.creatorUserId,
  };
}

/**
 * 返回账号展示名；未知账号回退到 ID。
 *
 * @param userId 账号 ID
 * @returns 展示名
 */
function m05DisplayName(userId) {
  return state.users.find((item) => item.id === userId)?.displayName ?? userId;
}

/**
 * 构造文档响应；不返回对象 key、存储端点或分块正文。
 *
 * @param document 文档
 * @returns OpenAPI KnowledgeDocument
 */
function m05DocumentResponse(document) {
  return {
    id: document.id,
    title: document.title,
    sourceType: document.sourceType,
    fileId: document.fileId ?? null,
    status: document.status,
    checksum: document.checksum,
    mimeType: document.mimeType,
    sizeBytes: document.sizeBytes,
    errorCode: document.errorCode ?? null,
    errorMessage: document.errorMessage ?? null,
    indexedAt: document.indexedAt ?? null,
    createdAt: document.createdAt,
    visibility: document.visibility,
    departmentId: document.visibility === 'DEPT' ? document.departmentId : null,
    aclUserCount: document.visibility === 'USERS' ? document.userIds.length : 0,
    parseEngine: document.parseEngine,
    warnings: document.warnings ?? [],
    parseSummary: document.parseSummary ?? null,
    sourceFileAccessLimited: document.sourceFileAccessLimited === true,
    retentionExpiresAt: document.retentionExpiresAt ?? null,
    retentionDays: document.retentionDays ?? 180,
    retentionPolicy: document.retentionPolicy ?? 'DEPLOYMENT_DEFAULT',
  };
}

/**
 * 写入一条 M05 合成审计；只供 E2E 通过控制端点断言动作序列，不代表真实审计实现。
 *
 * @param action 审计动作
 * @param account 操作者
 * @param document 目标文档
 * @param extra 附加字段
 */
function m05Audit(action, account, document, extra = {}) {
  state.knowledge.audit.push({
    action,
    tenantId: account?.tenantId ?? document?.tenantId ?? M03_TENANT_ACME,
    actorId: account?.userId ?? null,
    actorName: account?.displayName ?? '系统',
    targetId: document?.id ?? null,
    targetTitle: document?.title ?? null,
    reason: extra.reason ?? null,
    result: 'SUCCESS',
    createdAt: new Date().toISOString(),
  });
}

/**
 * 处理 M05 知识库 API 子集；只实现 E2E 需要的员工端与 ACL 管理路径。
 *
 * @param req Node 请求
 * @param res Node 响应
 * @param url 解析后的 URL
 * @param raw 请求体
 * @param session 当前会话
 * @returns 是否已处理
 */
function handleM05Api(req, res, url, raw, session) {
  const { pathname, searchParams } = url;
  const method = req.method ?? 'GET';
  const account = currentAccount(session);
  if (!pathname.startsWith('/api/v1/knowledge/documents')) {
    return false;
  }
  if (!account) {
    writeResult(res, problem(401, 'RICCON-AUTH-401', '未登录', '请先登录后再访问。'));
    return true;
  }
  if (!m05Permissions(account).includes('knowledge.read')) {
    writeResult(res, problem(403, 'RICCON-AUTH-403', '没有权限', '当前账号没有知识库读取权限。'));
    return true;
  }

  // 列表：按 ACL 过滤；管理性读取不返回总数差异，统一只返回可见项。
  if (pathname === '/api/v1/knowledge/documents' && method === 'GET') {
    if (scenario === 'm05-list-403') {
      writeResult(res, problem(403, 'RICCON-AUTH-403', '没有权限', '当前账号没有知识库读取权限。'));
      return true;
    }
    if (scenario === 'm05-list-500') {
      writeResult(res, problem(500, 'RICCON-COMMON-500', '服务暂不可用', '知识库暂时不可用。'));
      return true;
    }
    const pageNo = Math.max(1, Number.parseInt(searchParams.get('page') ?? '1', 10) || 1);
    const size = Math.min(
      100,
      Math.max(1, Number.parseInt(searchParams.get('size') ?? '20', 10) || 20),
    );
    const status = searchParams.get('status');
    const visible = state.knowledge.documents
      .filter((document) => m05CanSee(account, document))
      .filter((document) => !status || document.status === status);
    if (
      m05Permissions(account).includes('knowledge.acl.manage') &&
      visible.some((document) => !m05NaturallyVisible(account, document))
    ) {
      m05Audit('knowledge.document.admin_read', account, null, { reason: 'MANAGEMENT_READ' });
    }
    const start = (pageNo - 1) * size;
    const items = visible.slice(start, start + size).map(m05DocumentResponse);
    writeResult(
      res,
      json({
        page: pageNo,
        size,
        total: visible.length,
        totalPages: visible.length === 0 ? 0 : Math.ceil(visible.length / size),
        items,
      }),
    );
    return true;
  }

  // 登记文档：AC-06 端点路径；E2E 只用 API 校验 RBAC 与默认 TENANT。
  if (pathname === '/api/v1/knowledge/documents' && method === 'POST') {
    const csrfError = requireCsrf(session, req);
    if (csrfError) {
      writeResult(res, csrfError);
      return true;
    }
    if (!m05Permissions(account).includes('knowledge.manage')) {
      writeResult(res, problem(403, 'RICCON-AUTH-403', '没有权限', '只有租户管理员可以登记文档。'));
      return true;
    }
    const body = readJson(raw);
    const title = typeof body?.title === 'string' ? body.title.trim() : '';
    if (!title || typeof body?.fileId !== 'string') {
      writeResult(res, problem(400, 'RICCON-KB-400', '参数无效', '标题与来源文件不能为空。'));
      return true;
    }
    const created = {
      id: nextId('knowledge-document'),
      tenantId: account.tenantId,
      title,
      sourceType: 'UPLOAD',
      fileId: body.fileId,
      status: 'PENDING',
      checksum: pseudoSha256(`${title}:${body.fileId}`),
      mimeType: 'application/pdf',
      sizeBytes: 1024,
      errorCode: null,
      errorMessage: null,
      indexedAt: null,
      createdAt: new Date().toISOString(),
      visibility: body.visibility ?? 'TENANT',
      departmentId: body.departmentId ?? null,
      userIds: Array.isArray(body.userIds) ? body.userIds : [],
      creatorUserId: account.userId,
      parseEngine: 'PDFBOX',
      warnings: [],
      parseSummary: null,
      sourceFileAccessLimited: false,
      retentionExpiresAt: M05_RETENTION_EXPIRES,
      retentionDays: 180,
      retentionPolicy: 'DEPLOYMENT_DEFAULT',
      deletedAt: null,
      deletedReason: null,
    };
    state.knowledge.documents.push(created);
    writeResult(res, json(m05DocumentResponse(created), 202));
    return true;
  }

  const aclMatch = /^\/api\/v1\/knowledge\/documents\/([^/]+)\/acl$/.exec(pathname);
  const downloadMatch = /^\/api\/v1\/knowledge\/documents\/([^/]+)\/download$/.exec(pathname);
  const reindexMatch = /^\/api\/v1\/knowledge\/documents\/([^/]+)\/reindex$/.exec(pathname);
  const detailMatch = /^\/api\/v1\/knowledge\/documents\/([^/]+)$/.exec(pathname);

  const resolveDocument = () => {
    const documentId = decodeURIComponent(
      (aclMatch ?? downloadMatch ?? reindexMatch ?? detailMatch)?.[1] ?? '',
    );
    return state.knowledge.documents.find((document) => document.id === documentId) ?? null;
  };

  const document = resolveDocument();
  const isManager = m05Permissions(account).includes('knowledge.acl.manage');
  const isCreator = document?.creatorUserId === account.userId;
  // 重复删除幂等：已软删除文档对创建人/租户管理员仍返回 204，且不重复写审计。
  if (
    detailMatch &&
    method === 'DELETE' &&
    document &&
    document.tenantId === account.tenantId &&
    document.status === 'DELETED'
  ) {
    if (isManager || isCreator) {
      res.writeHead(204, { 'Cache-Control': 'no-store' });
      res.end();
    } else {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '文档不存在', '文档不存在或已被删除。'));
    }
    return true;
  }
  if (!document || !m05CanSee(account, document)) {
    writeResult(res, problem(404, 'RICCON-TENANT-404', '文档不存在', '文档不存在或已被删除。'));
    return true;
  }

  const managementRead = isManager && !m05NaturallyVisible(account, document);
  if (managementRead) {
    m05Audit('knowledge.document.admin_read', account, document, { reason: 'MANAGEMENT_READ' });
  }

  if (aclMatch && method === 'GET') {
    if (!isManager && !isCreator) {
      writeResult(
        res,
        problem(403, 'RICCON-AUTH-403', '没有权限', '没有读取该文档可见范围的权限。'),
      );
      return true;
    }
    writeResult(res, json(m05AclResponse(document)));
    return true;
  }

  if (aclMatch && method === 'PUT') {
    const csrfError = requireCsrf(session, req);
    if (csrfError) {
      writeResult(res, csrfError);
      return true;
    }
    if (!isManager && !isCreator) {
      writeResult(
        res,
        problem(403, 'RICCON-AUTH-403', '没有权限', '没有修改该文档可见范围的权限。'),
      );
      return true;
    }
    const body = readJson(raw);
    const visibility = body?.visibility;
    if (!['TENANT', 'DEPT', 'USERS'].includes(visibility)) {
      writeResult(
        res,
        problem(400, 'RICCON-KB-400', '可见范围无效', '可见范围必须是 TENANT、DEPT 或 USERS。'),
      );
      return true;
    }
    if (visibility === 'DEPT') {
      const department = findDepartment(account.tenantId, body?.departmentId);
      if (!department || department.status !== 'ACTIVE') {
        writeResult(res, problem(400, 'RICCON-KB-400', '部门无效', '请选择单个启用部门。'));
        return true;
      }
    }
    if (visibility === 'USERS') {
      const userIds = Array.isArray(body?.userIds) ? body.userIds : [];
      if (userIds.length > 200 || new Set(userIds).size !== userIds.length) {
        writeResult(res, problem(400, 'RICCON-KB-400', '名单无效', '名单最多 200 人且不能重复。'));
        return true;
      }
      if (
        userIds.some(
          (userId) =>
            !state.users.some((user) => user.id === userId && user.tenantId === account.tenantId),
        )
      ) {
        writeResult(res, problem(400, 'RICCON-KB-400', '账号无效', '名单只能包含本租户账号。'));
        return true;
      }
    }
    const before = `${document.visibility}:${document.departmentId ?? ''}:${document.userIds.join(',')}`;
    document.visibility = visibility;
    document.departmentId = visibility === 'DEPT' ? body.departmentId : null;
    document.userIds =
      visibility === 'USERS'
        ? body.userIds.filter((userId) => userId !== document.creatorUserId)
        : [];
    const after = `${document.visibility}:${document.departmentId ?? ''}:${document.userIds.join(',')}`;
    m05Audit('knowledge.acl.changed', account, document, { reason: 'ACL', before, after });
    writeResult(res, json(m05DocumentResponse(document)));
    return true;
  }

  if (downloadMatch && method === 'GET') {
    if (scenario === 'm05-download-502') {
      writeResult(
        res,
        problem(502, 'RICCON-KB-502', '源文件读取失败', '源文件读取失败，请稍后重试。'),
      );
      return true;
    }
    if (!document.fileId) {
      writeResult(res, problem(404, 'RICCON-KB-404', '没有源文件', '该文档没有可下载的源文件。'));
      return true;
    }
    const body = Buffer.from(`riccon stub knowledge bytes: ${document.title}`);
    const filename = encodeURIComponent(document.title);
    res.writeHead(200, {
      'Content-Type': document.mimeType,
      'Content-Disposition': `attachment; filename*=UTF-8''${filename}`,
      'Cache-Control': 'private, no-store',
      'X-Content-Type-Options': 'nosniff',
      'Content-Length': String(body.length),
    });
    res.end(body);
    return true;
  }

  if (reindexMatch && method === 'POST') {
    const csrfError = requireCsrf(session, req);
    if (csrfError) {
      writeResult(res, csrfError);
      return true;
    }
    if (!m05Permissions(account).includes('knowledge.manage')) {
      writeResult(
        res,
        problem(403, 'RICCON-AUTH-403', '没有权限', '只有租户管理员可以触发重索引。'),
      );
      return true;
    }
    if (scenario === 'm05-reindex-503') {
      writeResult(
        res,
        problem(503, 'RICCON-KB-503', '索引不可用', '索引服务暂不可用，请稍后重试。'),
      );
      return true;
    }
    if (
      scenario === 'm05-reindex-409' ||
      document.status === 'DELETED' ||
      document.status === 'INDEXING'
    ) {
      writeResult(res, problem(409, 'RICCON-KB-409', '状态冲突', '文档状态已变化，请刷新后重试。'));
      return true;
    }
    document.status = 'INDEXING';
    document.errorCode = null;
    document.errorMessage = null;
    document.indexedAt = null;
    m05Audit('knowledge.document.reindexed', account, document, { reason: 'REINDEX' });
    writeResult(res, json(m05DocumentResponse(document), 202));
    return true;
  }

  if (detailMatch && method === 'GET') {
    writeResult(res, json(m05DocumentResponse(document)));
    return true;
  }

  if (detailMatch && method === 'DELETE') {
    const csrfError = requireCsrf(session, req);
    if (csrfError) {
      writeResult(res, csrfError);
      return true;
    }
    if (!isManager && !isCreator) {
      writeResult(res, problem(403, 'RICCON-AUTH-403', '没有权限', '没有删除该文档的权限。'));
      return true;
    }
    if (document.status !== 'DELETED') {
      document.status = 'DELETED';
      document.deletedAt = new Date().toISOString();
      document.deletedReason = 'USER';
      m05Audit('knowledge.document.deleted', account, document, { reason: 'USER' });
    }
    res.writeHead(204, { 'Cache-Control': 'no-store' });
    res.end();
    return true;
  }

  return false;
}

function handleApi(req, res, url, raw) {
  const { pathname, searchParams } = url;
  const method = req.method ?? 'GET';

  if (pathname === '/api/v1/auth/csrf' && method === 'GET') {
    const { session, created, sid } = ensureSession(req);
    if (created) {
      res.setHeader(
        'Set-Cookie',
        `${SESSION_COOKIE}=${encodeURIComponent(sid)}; Path=/; HttpOnly; SameSite=Lax`,
      );
    }
    writeResult(res, json({ headerName: 'X-CSRF-TOKEN', token: session.csrfToken }));
    return true;
  }

  if (pathname === '/api/v1/auth/login' && method === 'POST') {
    const { session, created, sid } = ensureSession(req);
    const csrfError = requireCsrf(session, req);
    if (csrfError) {
      writeResult(res, csrfError);
      return true;
    }
    const body = readJson(raw);
    const key = `${body?.tenantCode}/${body?.username}`;
    const account = M03_CREDENTIALS[key];
    if (!account || body?.password !== account.password) {
      writeResult(res, problem(401, 'RICCON-AUTH-001', '登录失败', '租户代码、账号或密码不正确。'));
      return true;
    }
    session.authenticated = true;
    session.account = account;
    session.csrfToken = `stub-csrf-${nextId('csrf')}`;
    if (created) {
      res.setHeader(
        'Set-Cookie',
        `${SESSION_COOKIE}=${encodeURIComponent(sid)}; Path=/; HttpOnly; SameSite=Lax`,
      );
    }
    writeResult(res, json(sessionInfo(session)));
    return true;
  }

  const session = readSession(req);
  if (pathname === '/api/v1/auth/session' && method === 'GET') {
    const authError = requireAuth(session);
    writeResult(res, authError ?? json(sessionInfo(session)));
    return true;
  }

  if (pathname === '/api/v1/auth/logout' && method === 'POST') {
    const csrfError = requireCsrf(session, req);
    if (csrfError) {
      writeResult(res, csrfError);
      return true;
    }
    dropSession(req);
    res.writeHead(204, { 'Cache-Control': 'no-store' });
    res.end();
    return true;
  }

  const authError = requireAuth(session);
  if (authError) {
    writeResult(res, authError);
    return true;
  }

  // ---------- M05：知识库文档/ACL/下载/删除/重索引 ----------
  if (handleM05Api(req, res, url, raw, session)) {
    return true;
  }

  // ---------- M04：模型路由/key/配额/成本（B11a/B11b 复用） ----------
  if (handleM04Api(req, res, url, raw, session)) {
    return true;
  }

  // ---------- M03：平台部门管理 ----------
  const adminTenantMatch =
    /^\/api\/v1\/admin\/tenants\/([^/]+)\/departments(?:\/([^/]+))?(?:\/(disable|enable|members|manager))?(?:\/([^/]+))?$/.exec(
      pathname,
    );
  if (pathname === '/api/v1/admin/tenants' && method === 'GET') {
    const account = currentAccount(session);
    if (account?.persona !== 'platform-admin') {
      writeResult(res, problem(403, 'RICCON-AUTH-403', '没有权限', '只有平台管理员可以管理租户。'));
      return true;
    }
    writeResult(res, json(page(state.tenants)));
    return true;
  }

  // M15：租户管理员账号列表；只返回当前租户账号元数据与脱敏来源 IdP。
  if (pathname === '/api/v1/users' && method === 'GET') {
    const account = currentAccount(session);
    if (account?.persona !== 'tenant-admin') {
      writeResult(res, problem(403, 'RICCON-AUTH-403', '没有权限', '只有租户管理员可以管理账号。'));
      return true;
    }
    writeResult(res, json(page(tenantUsers(account.tenantId).map(userSummaryResponse))));
    return true;
  }

  const adminUserListMatch = /^\/api\/v1\/admin\/tenants\/([^/]+)\/users$/.exec(pathname);
  if (adminUserListMatch && method === 'GET') {
    const account = currentAccount(session);
    if (account?.persona !== 'platform-admin') {
      writeResult(res, problem(403, 'RICCON-AUTH-403', '没有权限', '只有平台管理员可以管理部门。'));
      return true;
    }
    const tenantId = decodeURIComponent(adminUserListMatch[1]);
    writeResult(res, json(page(tenantUsers(tenantId).map(userSummaryResponse))));
    return true;
  }

  if (adminTenantMatch) {
    const account = currentAccount(session);
    if (account?.persona !== 'platform-admin') {
      writeResult(res, problem(403, 'RICCON-AUTH-403', '没有权限', '只有平台管理员可以管理部门。'));
      return true;
    }
    const tenantId = decodeURIComponent(adminTenantMatch[1]);
    const departmentId = adminTenantMatch[2] ? decodeURIComponent(adminTenantMatch[2]) : null;
    const action = adminTenantMatch[3] ?? null;
    const suffix = adminTenantMatch[4] ? decodeURIComponent(adminTenantMatch[4]) : null;
    const tenant = state.tenants.find((item) => item.id === tenantId);
    if (!tenant) {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '租户不存在', '目标租户不存在。'));
      return true;
    }
    if (tenant.status !== 'ACTIVE') {
      writeResult(
        res,
        problem(409, 'RICCON-CONFLICT-409', '租户已暂停', '目标租户已暂停，部门管理暂不可用。'),
      );
      return true;
    }
    if (!departmentId && method === 'GET') {
      writeResult(
        res,
        json(
          page(
            state.departments
              .filter((department) => department.tenantId === tenantId)
              .sort(
                (left, right) =>
                  left.name.localeCompare(right.name, 'zh-CN') || left.id.localeCompare(right.id),
              )
              .map(departmentResponse),
          ),
        ),
      );
      return true;
    }
    if (!departmentId && method === 'POST') {
      const csrfError = requireCsrf(session, req);
      if (csrfError) {
        writeResult(res, csrfError);
        return true;
      }
      const body = readJson(raw);
      const name = typeof body?.name === 'string' ? body.name.trim() : '';
      if (!name) {
        writeResult(res, problem(400, 'RICCON-REQUEST-400', '参数无效', '部门名称不能为空。'));
        return true;
      }
      if (
        state.departments.some(
          (department) => department.tenantId === tenantId && department.name === name,
        )
      ) {
        writeResult(
          res,
          problem(409, 'RICCON-DEPT-409', '部门名称冲突', '同一租户内部门名称必须唯一。'),
        );
        return true;
      }
      const now = new Date().toISOString();
      const department = {
        id: `dept-${nextId('dept')}`,
        tenantId,
        name,
        status: 'ACTIVE',
        managerUserId: null,
        createdAt: now,
        updatedAt: now,
      };
      state.departments = [...state.departments, department];
      writeResult(res, json(departmentResponse(department), 201));
      return true;
    }

    const department = departmentId ? findDepartment(tenantId, departmentId) : null;
    if (!department) {
      writeResult(
        res,
        problem(404, 'RICCON-TENANT-404', '部门不存在', '部门不存在或不属于该租户。'),
      );
      return true;
    }

    if (!action && method === 'PATCH') {
      const csrfError = requireCsrf(session, req);
      if (csrfError) {
        writeResult(res, csrfError);
        return true;
      }
      if (department.status !== 'ACTIVE') {
        writeResult(
          res,
          problem(409, 'RICCON-DEPT-409', '部门已停用', '停用部门需先启用后再改名。'),
        );
        return true;
      }
      const body = readJson(raw);
      const name = typeof body?.name === 'string' ? body.name.trim() : '';
      if (!name) {
        writeResult(res, problem(400, 'RICCON-REQUEST-400', '参数无效', '部门名称不能为空。'));
        return true;
      }
      if (
        state.departments.some(
          (item) => item.tenantId === tenantId && item.id !== department.id && item.name === name,
        )
      ) {
        writeResult(
          res,
          problem(409, 'RICCON-DEPT-409', '部门名称冲突', '同一租户内部门名称必须唯一。'),
        );
        return true;
      }
      department.name = name;
      department.updatedAt = new Date().toISOString();
      writeResult(res, json(departmentResponse(department)));
      return true;
    }

    if (!action && method === 'GET' && !departmentId) {
      writeResult(res, json(page([])));
      return true;
    }

    if ((action === 'disable' || action === 'enable') && method === 'POST') {
      const csrfError = requireCsrf(session, req);
      if (csrfError) {
        writeResult(res, csrfError);
        return true;
      }
      department.status = action === 'disable' ? 'DISABLED' : 'ACTIVE';
      department.updatedAt = new Date().toISOString();
      writeResult(res, json(departmentResponse(department)));
      return true;
    }

    if (action === 'members' && !suffix && method === 'GET') {
      const members = state.users
        .filter((user) => user.departmentId === department.id && user.status === 'ACTIVE')
        .map(departmentMemberResponse);
      writeResult(res, json(page(members)));
      return true;
    }

    if (action === 'members' && !suffix && method === 'POST') {
      const csrfError = requireCsrf(session, req);
      if (csrfError) {
        writeResult(res, csrfError);
        return true;
      }
      const body = readJson(raw);
      const user = state.users.find(
        (item) => item.id === body?.userId && item.tenantId === tenantId,
      );
      if (!user) {
        writeResult(res, problem(404, 'RICCON-TENANT-404', '账号不存在', '账号不存在。'));
        return true;
      }
      if (user.departmentId) {
        writeResult(
          res,
          problem(409, 'RICCON-DEPT-409', '账号已属于部门', '同一账号只能属于一个部门。'),
        );
        return true;
      }
      user.departmentId = department.id;
      writeResult(res, json(departmentMemberResponse(user), 201));
      return true;
    }

    if (action === 'members' && suffix && method === 'DELETE') {
      const csrfError = requireCsrf(session, req);
      if (csrfError) {
        writeResult(res, csrfError);
        return true;
      }
      if (department.managerUserId === suffix) {
        writeResult(res, problem(409, 'RICCON-DEPT-409', '负责人不能移出', '请先移交负责人。'));
        return true;
      }
      const user = state.users.find((item) => item.id === suffix);
      if (user?.departmentId === department.id) {
        user.departmentId = null;
      }
      res.writeHead(204, { 'Cache-Control': 'no-store' });
      res.end();
      return true;
    }

    if (action === 'manager' && method === 'PUT') {
      const csrfError = requireCsrf(session, req);
      if (csrfError) {
        writeResult(res, csrfError);
        return true;
      }
      const body = readJson(raw);
      const user = state.users.find(
        (item) =>
          item.id === body?.userId &&
          item.tenantId === tenantId &&
          item.departmentId === department.id &&
          item.status === 'ACTIVE',
      );
      if (!user) {
        writeResult(
          res,
          problem(404, 'RICCON-TENANT-404', '账号不存在', '负责人必须是本部门当前启用成员。'),
        );
        return true;
      }
      department.managerUserId = user.id;
      department.updatedAt = new Date().toISOString();
      writeResult(res, json(departmentResponse(department)));
      return true;
    }
  }

  // ---------- M03：租户内部门只读与空间共享 ----------
  if (pathname === '/api/v1/departments' && method === 'GET') {
    const account = currentAccount(session);
    writeResult(res, json(page(scopedDepartments(account).map(departmentResponse))));
    return true;
  }

  const departmentMembersMatch = /^\/api\/v1\/departments\/([^/]+)\/members$/.exec(pathname);
  if (departmentMembersMatch && method === 'GET') {
    const account = currentAccount(session);
    const departmentId = decodeURIComponent(departmentMembersMatch[1]);
    const department = findDepartment(account?.tenantId, departmentId);
    const user = state.users.find((item) => item.id === account?.userId);
    const allowed =
      department && (account.persona === 'tenant-admin' || user?.departmentId === departmentId);
    if (!allowed) {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '部门不存在', '部门不存在。'));
      return true;
    }
    const members = state.users
      .filter((item) => item.departmentId === departmentId && item.status === 'ACTIVE')
      .map(departmentMemberResponse);
    writeResult(res, json(page(members)));
    return true;
  }

  const departmentWorkspacesMatch = /^\/api\/v1\/departments\/([^/]+)\/workspaces$/.exec(pathname);
  if (departmentWorkspacesMatch) {
    const account = currentAccount(session);
    const departmentId = decodeURIComponent(departmentWorkspacesMatch[1]);
    const department = findDepartment(account?.tenantId, departmentId);
    if (!department) {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '部门不存在', '部门不存在。'));
      return true;
    }
    if (!canManageDepartment(account, department)) {
      const user = state.users.find((item) => item.id === account?.userId);
      if (user?.departmentId === departmentId) {
        writeResult(res, problem(403, 'RICCON-AUTH-403', '没有权限', '只有负责人可以管理空间。'));
      } else {
        writeResult(res, problem(404, 'RICCON-TENANT-404', '部门不存在', '部门不存在。'));
      }
      return true;
    }
    if (department.status !== 'ACTIVE') {
      writeResult(res, problem(400, 'RICCON-REQUEST-400', '部门已停用', '停用部门不能管理空间。'));
      return true;
    }
    if (method === 'GET') {
      writeResult(
        res,
        json(page(manageableDepartmentWorkspaces(account, department).map(workspaceResponse))),
      );
      return true;
    }
    if (method === 'POST') {
      const csrfError = requireCsrf(session, req);
      if (csrfError) {
        writeResult(res, csrfError);
        return true;
      }
      const body = readJson(raw);
      const visibility = body?.visibility ?? 'DEPT';
      if (visibility !== 'DEPT' && visibility !== 'CUSTOM') {
        writeResult(
          res,
          problem(400, 'RICCON-REQUEST-400', '可见性无效', '只能创建本部门或指定成员空间。'),
        );
        return true;
      }
      const name = typeof body?.name === 'string' ? body.name.trim() : '';
      if (!name) {
        writeResult(res, problem(400, 'RICCON-REQUEST-400', '参数无效', '空间名称不能为空。'));
        return true;
      }
      const now = new Date().toISOString();
      const workspace = {
        id: nextId('workspace'),
        tenantId: account.tenantId,
        name,
        description: typeof body?.description === 'string' ? body.description : null,
        ownerUserId: account.userId,
        departmentId,
        visibility,
        status: 'ACTIVE',
        createdAt: now,
        updatedAt: now,
      };
      state.teamWorkspaces = [...state.teamWorkspaces, workspace];
      writeResult(res, json(workspaceResponse(workspace), 201));
      return true;
    }
  }

  if (pathname === '/api/v1/workspaces' && method === 'GET') {
    const account = currentAccount(session);
    const kind = searchParams.get('kind');
    const items =
      kind === 'PERSONAL'
        ? [state.workspace]
        : kind === 'TEAM'
          ? accessibleTeamWorkspaces(account).map(workspaceResponse)
          : [state.workspace, ...accessibleTeamWorkspaces(account).map(workspaceResponse)];
    writeResult(res, json(page(items)));
    return true;
  }

  if (pathname === '/api/v1/workspaces' && method === 'POST') {
    const account = currentAccount(session);
    if (account?.persona !== 'tenant-admin') {
      writeResult(res, problem(403, 'RICCON-AUTH-403', '没有权限', '只有租户管理员可以创建空间。'));
      return true;
    }
    const csrfError = requireCsrf(session, req);
    if (csrfError) {
      writeResult(res, csrfError);
      return true;
    }
    const body = readJson(raw);
    const department = findDepartment(account.tenantId, body?.departmentId);
    if (!department || department.status !== 'ACTIVE') {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '部门不存在', '部门不存在。'));
      return true;
    }
    const visibility = body?.visibility ?? 'DEPT';
    if (visibility !== 'DEPT' && visibility !== 'CUSTOM') {
      writeResult(res, problem(400, 'RICCON-REQUEST-400', '可见性无效', '可见性无效。'));
      return true;
    }
    const now = new Date().toISOString();
    const workspace = {
      id: nextId('workspace'),
      tenantId: account.tenantId,
      name: typeof body?.name === 'string' ? body.name.trim() : '',
      description: typeof body?.description === 'string' ? body.description : null,
      ownerUserId: account.userId,
      departmentId: department.id,
      visibility,
      status: 'ACTIVE',
      createdAt: now,
      updatedAt: now,
    };
    state.teamWorkspaces = [...state.teamWorkspaces, workspace];
    writeResult(res, json(workspaceResponse(workspace), 201));
    return true;
  }

  const workspaceMatch = /^\/api\/v1\/workspaces\/([^/]+)$/.exec(pathname);
  if (workspaceMatch && (method === 'GET' || method === 'PATCH')) {
    const account = currentAccount(session);
    const workspaceId = decodeURIComponent(workspaceMatch[1]);
    const workspace = state.teamWorkspaces.find(
      (item) => item.id === workspaceId && item.tenantId === account?.tenantId,
    );
    if (!workspace) {
      if (workspaceId === state.workspace.id) {
        writeResult(res, json(state.workspace));
        return true;
      }
      writeResult(res, problem(404, 'RICCON-TENANT-404', '工作空间不存在', '工作空间不存在。'));
      return true;
    }
    if (method === 'GET') {
      if (!canAccessTeamWorkspace(account, workspace)) {
        writeResult(res, problem(404, 'RICCON-TENANT-404', '工作空间不存在', '工作空间不存在。'));
        return true;
      }
      writeResult(res, json(workspaceResponse(workspace)));
      return true;
    }
    const csrfError = requireCsrf(session, req);
    if (csrfError) {
      writeResult(res, csrfError);
      return true;
    }
    const department = findDepartment(account.tenantId, workspace.departmentId);
    if (!canManageDepartment(account, department)) {
      writeResult(
        res,
        canAccessTeamWorkspace(account, workspace)
          ? problem(403, 'RICCON-AUTH-403', '没有权限', '只有负责人或租户管理员可以修改空间。')
          : problem(404, 'RICCON-TENANT-404', '工作空间不存在', '工作空间不存在。'),
      );
      return true;
    }
    if (scenario === 'rename-409') {
      writeResult(
        res,
        problem(409, 'RICCON-DEPT-409', '空间名称冲突', 'workspace name already exists'),
      );
      return true;
    }
    if (scenario === 'rename-403') {
      writeResult(res, problem(403, 'RICCON-AUTH-403', '没有权限', 'forbidden'));
      return true;
    }
    if (scenario === 'rename-404') {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '空间不存在', 'not found'));
      return true;
    }
    const body = readJson(raw);
    if (typeof body?.name === 'string' && body.name.trim()) {
      workspace.name = body.name.trim();
    }
    if (typeof body?.description === 'string') {
      workspace.description = body.description;
    }
    if (typeof body?.visibility === 'string') {
      if (body.visibility !== 'DEPT' && body.visibility !== 'CUSTOM') {
        writeResult(res, problem(400, 'RICCON-REQUEST-400', '可见性无效', '可见性无效。'));
        return true;
      }
      if (body.visibility !== workspace.visibility) {
        workspace.visibility = body.visibility;
        state.workspaceMembers[workspace.id] = [];
      }
    }
    workspace.updatedAt = new Date().toISOString();
    writeResult(res, json(workspaceResponse(workspace)));
    return true;
  }

  const workspaceMembersMatch = /^\/api\/v1\/workspaces\/([^/]+)\/members(?:\/([^/]+))?$/.exec(
    pathname,
  );
  if (workspaceMembersMatch) {
    const account = currentAccount(session);
    const workspaceId = decodeURIComponent(workspaceMembersMatch[1]);
    const targetUserId = workspaceMembersMatch[2]
      ? decodeURIComponent(workspaceMembersMatch[2])
      : null;
    const workspace = state.teamWorkspaces.find(
      (item) => item.id === workspaceId && item.tenantId === account?.tenantId,
    );
    if (!workspace) {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '工作空间不存在', '工作空间不存在。'));
      return true;
    }
    const department = findDepartment(account.tenantId, workspace.departmentId);
    if (!canManageDepartment(account, department)) {
      writeResult(
        res,
        canAccessTeamWorkspace(account, workspace)
          ? problem(403, 'RICCON-AUTH-403', '没有权限', '只有负责人或租户管理员可以管理成员。')
          : problem(404, 'RICCON-TENANT-404', '工作空间不存在', '工作空间不存在。'),
      );
      return true;
    }
    if (!targetUserId && method === 'GET') {
      if (workspace.visibility !== 'CUSTOM') {
        writeResult(res, json(page([])));
        return true;
      }
      const members = (state.workspaceMembers[workspace.id] ?? [])
        .map((userId) => state.users.find((user) => user.id === userId))
        .filter(Boolean)
        .map((user) => ({
          userId: user.id,
          username: user.username,
          displayName: user.displayName,
        }));
      writeResult(res, json(page(members)));
      return true;
    }
    if (!targetUserId && method === 'POST') {
      const csrfError = requireCsrf(session, req);
      if (csrfError) {
        writeResult(res, csrfError);
        return true;
      }
      const body = readJson(raw);
      const user = state.users.find(
        (item) =>
          item.id === body?.userId &&
          item.tenantId === account.tenantId &&
          item.departmentId === workspace.departmentId &&
          item.status === 'ACTIVE',
      );
      if (!user) {
        writeResult(res, problem(404, 'RICCON-TENANT-404', '账号不存在', '账号不存在。'));
        return true;
      }
      const existing = state.workspaceMembers[workspace.id] ?? [];
      if (existing.includes(user.id)) {
        writeResult(res, problem(409, 'RICCON-DEPT-409', '成员已存在', '该账号已是空间显式成员。'));
        return true;
      }
      state.workspaceMembers[workspace.id] = [...existing, user.id];
      writeResult(
        res,
        json({ userId: user.id, username: user.username, displayName: user.displayName }, 201),
      );
      return true;
    }
    if (targetUserId && method === 'DELETE') {
      const csrfError = requireCsrf(session, req);
      if (csrfError) {
        writeResult(res, csrfError);
        return true;
      }
      state.workspaceMembers[workspace.id] = (state.workspaceMembers[workspace.id] ?? []).filter(
        (userId) => userId !== targetUserId,
      );
      res.writeHead(204, { 'Cache-Control': 'no-store' });
      res.end();
      return true;
    }
  }

  // 场景：报告任务列表返回 403，用于验证页面无权限态。
  const researchTasksMatch = /^\/api\/v1\/workspaces\/([^/]+)\/research-tasks$/.exec(pathname);
  if (researchTasksMatch) {
    const researchWorkspaceId = decodeURIComponent(researchTasksMatch[1]);
    if (
      !isKnownWorkspaceId(researchWorkspaceId) ||
      !canAccessWorkspace(currentAccount(session), researchWorkspaceId)
    ) {
      writeResult(
        res,
        problem(404, 'RICCON-TENANT-404', '工作空间不存在', '工作空间不存在或已被删除。'),
      );
      return true;
    }
    if (method === 'GET') {
      if (scenario === 'research-list-403') {
        writeResult(res, problem(403, 'RICCON-PERM-403', '没有权限', '当前账号无权查看报告任务。'));
        return true;
      }
      advanceResearchTasks();
      const status = searchParams.get('status');
      const items = state.researchTasks
        .filter((task) => task.task.workspaceId === researchWorkspaceId)
        .filter((task) => !status || task.task.status === status)
        .map(researchTaskSummary)
        .sort((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt));
      writeResult(res, json(page(items)));
      return true;
    }
    if (method === 'POST') {
      const csrfError = requireCsrf(session, req);
      if (csrfError) {
        writeResult(res, csrfError);
        return true;
      }
      if (scenario === 'm05-quota-429') {
        writeResult(res, m05QuotaProblem());
        return true;
      }
      writeResult(
        res,
        createResearchTask(
          readJson(raw),
          req.headers['idempotency-key'] ?? '',
          researchWorkspaceId,
        ),
      );
      return true;
    }
  }

  const researchTaskMatch = /^\/api\/v1\/research-tasks\/([^/]+)$/.exec(pathname);
  if (researchTaskMatch && method === 'GET') {
    advanceResearchTasks();
    const task = findResearchTask(decodeURIComponent(researchTaskMatch[1]));
    const accessible = task && canAccessWorkspace(currentAccount(session), task.task.workspaceId);
    writeResult(
      res,
      accessible
        ? json(researchTaskDetail(task))
        : problem(404, 'RICCON-TENANT-404', '任务不存在', '报告任务不存在或已被删除。'),
    );
    return true;
  }

  const researchRetryMatch = /^\/api\/v1\/research-tasks\/([^/]+)\/retry$/.exec(pathname);
  if (researchRetryMatch && method === 'POST') {
    const csrfError = requireCsrf(session, req);
    if (csrfError) {
      writeResult(res, csrfError);
      return true;
    }
    const fromStage = readJson(raw)?.fromStage;
    if (
      fromStage !== undefined &&
      fromStage !== null &&
      fromStage !== 'TOOL' &&
      fromStage !== 'GENERATION'
    ) {
      writeResult(
        res,
        problem(400, 'RICCON-RESEARCH-400', '阶段不合法', '仅允许重试 TOOL 或 GENERATION。'),
      );
      return true;
    }
    writeResult(
      res,
      retryResearchTask(decodeURIComponent(researchRetryMatch[1]), fromStage ?? null),
    );
    return true;
  }

  const runDetailMatch = /^\/api\/v1\/runs\/([^/]+)$/.exec(pathname);
  if (runDetailMatch && method === 'GET') {
    const detail = state.runs[decodeURIComponent(runDetailMatch[1])];
    writeResult(
      res,
      detail
        ? json(detail)
        : problem(404, 'RICCON-RUN-404', '运行不存在', '运行不存在或已被删除。'),
    );
    return true;
  }

  if (pathname === '/api/v1/mcp-tools' && method === 'GET') {
    if (scenario === 'mcp-tools-403') {
      writeResult(
        res,
        problem(403, 'RICCON-PERM-403', '没有权限', '当前账号无权使用 MCP 数据工具。'),
      );
      return true;
    }
    writeResult(res, json(page(state.mcpTools.filter((tool) => tool.status === 'ACTIVE'))));
    return true;
  }

  const approvalDecisionMatch = /^\/api\/v1\/approvals\/([^/]+)\/decision$/.exec(pathname);
  if (approvalDecisionMatch && method === 'POST') {
    const csrfError = requireCsrf(session, req);
    if (csrfError) {
      writeResult(res, csrfError);
      return true;
    }
    const body = readJson(raw);
    const decision = body?.decision;
    if (decision !== 'APPROVE' && decision !== 'REJECT') {
      writeResult(
        res,
        problem(400, 'RICCON-APPROVAL-400', '决定不合法', '审批决定必须为 APPROVE 或 REJECT。'),
      );
      return true;
    }
    const rawComment = typeof body?.comment === 'string' ? body.comment.trim() : '';
    if (rawComment.length > 500) {
      writeResult(
        res,
        problem(400, 'RICCON-APPROVAL-400', '备注过长', '审批备注不能超过 500 个字符。'),
      );
      return true;
    }
    // 场景：强制返回 409，验证前端冲突提示与刷新逻辑；审批保持待处理。
    if (scenario === 'approval-409') {
      writeResult(
        res,
        problem(409, 'RICCON-APPROVAL-409', '审批已处理', '该审批已被其他会话处理。'),
      );
      return true;
    }
    writeResult(
      res,
      decideResearchApproval(
        decodeURIComponent(approvalDecisionMatch[1]),
        decision,
        rawComment || null,
      ),
    );
    return true;
  }

  const artifactDownloadMatch =
    /^\/api\/v1\/artifacts\/([^/]+)\/versions\/(\d+)\/download-url$/.exec(pathname);
  if (artifactDownloadMatch && method === 'GET') {
    const artifactId = decodeURIComponent(artifactDownloadMatch[1]);
    const version = Number(artifactDownloadMatch[2]);
    const task = state.researchTasks.find(
      (item) => item.artifact?.id === artifactId && item.artifact.currentVersion === version,
    );
    if (!task?.artifact) {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '产物不存在', '产物或版本不存在。'));
      return true;
    }
    writeResult(
      res,
      json({
        // 绝对地址模拟对象存储预签名 URL；对象路由返回 CORS 头，客户端以 credentials: omit 读取。
        url: `http://${req.headers.host}/objects/artifacts/${encodeURIComponent(artifactId)}/versions/${version}/content`,
        expiresAt: new Date(Date.now() + 5 * 60 * 1000).toISOString(),
      }),
    );
    return true;
  }

  const artifactContentMatch = /^\/api\/v1\/artifacts\/([^/]+)\/versions\/(\d+)\/content$/.exec(
    pathname,
  );
  if (artifactContentMatch && method === 'GET') {
    const artifactId = decodeURIComponent(artifactContentMatch[1]);
    const version = Number(artifactContentMatch[2]);
    const task = state.researchTasks.find(
      (item) => item.artifact?.id === artifactId && item.artifact.currentVersion === version,
    );
    if (!task?.artifact || !task.artifactBody) {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '产物不存在', '产物或版本不存在。'));
      return true;
    }
    // 同源内容端点：模拟 API 代理对象存储，浏览器不直接访问预签名地址。
    const payload = Buffer.from(task.artifactBody, 'utf8');
    res.writeHead(200, {
      'Content-Type':
        task.artifact.currentVersionDetail?.contentType ?? 'text/markdown; charset=utf-8',
      'Content-Length': payload.length,
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
    });
    res.end(payload);
    return true;
  }

  const artifactsMatch = /^\/api\/v1\/workspaces\/([^/]+)\/artifacts$/.exec(pathname);
  if (artifactsMatch && method === 'GET') {
    if (decodeURIComponent(artifactsMatch[1]) !== state.workspace.id) {
      writeResult(
        res,
        problem(404, 'RICCON-TENANT-404', '工作空间不存在', '工作空间不存在或已被删除。'),
      );
      return true;
    }
    advanceResearchTasks();
    const items = state.researchTasks
      .map((task) => task.artifact)
      .filter(Boolean)
      .sort((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt));
    writeResult(res, json(page(items)));
    return true;
  }

  const artifactMatch = /^\/api\/v1\/artifacts\/([^/]+)$/.exec(pathname);
  if (artifactMatch && method === 'GET') {
    advanceResearchTasks();
    const artifact = state.researchTasks
      .map((task) => task.artifact)
      .find((item) => item?.id === decodeURIComponent(artifactMatch[1]));
    writeResult(
      res,
      artifact
        ? json(artifact)
        : problem(404, 'RICCON-TENANT-404', '产物不存在', '产物不存在或已被删除。'),
    );
    return true;
  }

  const artifactVersionsMatch = /^\/api\/v1\/artifacts\/([^/]+)\/versions$/.exec(pathname);
  if (artifactVersionsMatch && method === 'GET') {
    const artifact = state.researchTasks
      .map((task) => task.artifact)
      .find((item) => item?.id === decodeURIComponent(artifactVersionsMatch[1]));
    if (!artifact) {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '产物不存在', '产物不存在或已被删除。'));
      return true;
    }
    writeResult(
      res,
      json(page(artifact.currentVersionDetail ? [artifact.currentVersionDetail] : [])),
    );
    return true;
  }

  const artifactVersionMatch = /^\/api\/v1\/artifacts\/([^/]+)\/versions\/(\d+)$/.exec(pathname);
  if (artifactVersionMatch && method === 'GET') {
    const artifact = state.researchTasks
      .map((task) => task.artifact)
      .find((item) => item?.id === decodeURIComponent(artifactVersionMatch[1]));
    const version = artifact?.currentVersionDetail;
    if (!artifact || !version || version.version !== Number(artifactVersionMatch[2])) {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '产物版本不存在', '产物或版本不存在。'));
      return true;
    }
    writeResult(res, json(version));
    return true;
  }

  const conversationsMatch = /^\/api\/v1\/workspaces\/([^/]+)\/conversations$/.exec(pathname);
  if (conversationsMatch) {
    const workspaceId = decodeURIComponent(conversationsMatch[1]);
    const knownWorkspace =
      workspaceId === state.workspace.id ||
      state.teamWorkspaces.some((item) => item.id === workspaceId);
    if (!knownWorkspace) {
      writeResult(
        res,
        problem(404, 'RICCON-TENANT-404', '工作空间不存在', '工作空间不存在或已被删除。'),
      );
      return true;
    }
    if (method === 'GET') {
      const items = state.conversations
        .filter((item) => item.workspaceId === workspaceId)
        .sort((left, right) => Date.parse(right.updatedAt) - Date.parse(left.updatedAt));
      writeResult(res, json(page(items)));
      return true;
    }
    if (method === 'POST') {
      const csrfError = requireCsrf(session, req);
      if (csrfError) {
        writeResult(res, csrfError);
        return true;
      }
      const body = readJson(raw);
      const now = new Date().toISOString();
      const conversation = {
        id: nextId('conversation'),
        workspaceId,
        title: typeof body?.title === 'string' && body.title.trim() ? body.title.trim() : null,
        status: 'ACTIVE',
        lastMessageAt: null,
        createdAt: now,
        updatedAt: now,
      };
      state.conversations = [conversation, ...state.conversations];
      state.messages[conversation.id] = [];
      writeResult(res, json(conversation, 201));
      return true;
    }
  }

  const conversationMatch = /^\/api\/v1\/conversations\/([^/]+)$/.exec(pathname);
  if (conversationMatch && method === 'GET') {
    const conversationId = decodeURIComponent(conversationMatch[1]);
    const conversation = state.conversations.find((item) => item.id === conversationId);
    if (!conversation) {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '会话不存在', '会话不存在或已被删除。'));
      return true;
    }
    writeResult(res, json({ ...conversation, messages: state.messages[conversationId] ?? [] }));
    return true;
  }

  const messagesMatch = /^\/api\/v1\/conversations\/([^/]+)\/messages$/.exec(pathname);
  if (messagesMatch && method === 'POST') {
    const csrfError = requireCsrf(session, req);
    if (csrfError) {
      writeResult(res, csrfError);
      return true;
    }
    const conversation = state.conversations.find(
      (item) => item.id === decodeURIComponent(messagesMatch[1]),
    );
    if (!conversation) {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '会话不存在', '会话不存在或已被删除。'));
      return true;
    }
    handleSendMessage(req, res, conversation, raw);
    return true;
  }

  const stopMatch = /^\/api\/v1\/conversations\/([^/]+)\/stop$/.exec(pathname);
  if (stopMatch && method === 'POST') {
    const csrfError = requireCsrf(session, req);
    if (csrfError) {
      writeResult(res, csrfError);
      return true;
    }
    const runId = readJson(raw)?.runId;
    const run = typeof runId === 'string' ? activeRuns.get(runId) : undefined;
    // 停止是幂等操作：运行已结束时仍返回 202，避免客户端 abort 与 stop 请求竞速产生噪声错误。
    if (run) {
      finishRun(run, 'STOPPED');
    }
    writeResult(res, json({ stopped: true }, 202));
    return true;
  }

  const filesMatch = /^\/api\/v1\/workspaces\/([^/]+)\/files$/.exec(pathname);
  if (filesMatch) {
    const workspaceId = decodeURIComponent(filesMatch[1]);
    const knownWorkspace =
      workspaceId === state.workspace.id ||
      state.teamWorkspaces.some((item) => item.id === workspaceId);
    if (!knownWorkspace) {
      writeResult(
        res,
        problem(404, 'RICCON-TENANT-404', '工作空间不存在', '工作空间不存在或已被删除。'),
      );
      return true;
    }
    if (method === 'POST') {
      const csrfError = requireCsrf(session, req);
      if (csrfError) {
        writeResult(res, csrfError);
        return true;
      }
      const parsed = parseMultipart(raw, req.headers['content-type']);
      const uploaded = parsed?.files.file;
      const conversationId = parsed?.fields.conversationId ?? '';
      if (!uploaded) {
        writeResult(res, problem(400, 'RICCON-FILE-400', '上传请求无效', '缺少文件。'));
        return true;
      }
      const conversation = state.conversations.find(
        (item) => item.id === conversationId && item.workspaceId === workspaceId,
      );
      if (!conversation) {
        writeResult(res, problem(404, 'RICCON-TENANT-404', '会话不存在', '会话不存在或已被删除。'));
        return true;
      }
      advanceParses();
      const existing = state.files.filter(
        (item) => item.conversationId === conversationId && item.status !== 'DELETED',
      );
      if (existing.length >= MAX_FILES_PER_CONVERSATION) {
        writeResult(
          res,
          problem(409, 'RICCON-FILE-409', '文件数量已达上限', '每个会话最多上传 5 个文件。'),
        );
        return true;
      }
      if (uploaded.data.length > MAX_FILE_BYTES) {
        writeResult(res, problem(413, 'RICCON-FILE-413', '文件过大', '单文件不能超过 200 MB。'));
        return true;
      }
      if (uploaded.data.length === 0) {
        writeResult(res, problem(415, 'RICCON-FILE-415', '不支持的文件', '文件为空。'));
        return true;
      }
      const format = formatForFile(uploaded.filename, uploaded.contentType);
      if (!format) {
        writeResult(
          res,
          problem(
            415,
            'RICCON-FILE-415',
            '不支持的文件',
            '仅支持 PDF、DOCX、XLSX、PNG、JPEG，且扩展名与类型必须一致。',
          ),
        );
        return true;
      }

      const now = new Date().toISOString();
      const fileId = nextId('file');
      const file = {
        id: fileId,
        workspaceId,
        conversationId,
        originalName: uploaded.filename,
        format,
        mimeType: uploaded.contentType,
        sizeBytes: uploaded.data.length,
        sha256: pseudoSha256(`${uploaded.filename}:${uploaded.data.length}`),
        status: 'AVAILABLE',
        parse: {
          id: nextId('parse'),
          attempt: 1,
          status: 'PENDING',
          parserVersion: 'stub-parser-1',
          pageCount: null,
          sheetCount: null,
          chunkCount: null,
          errorCode: null,
          errorMessage: null,
          startedAt: now,
          finishedAt: null,
        },
        createdAt: now,
        deletedAt: null,
      };
      state.files.push(file);
      // 文件名包含 fail/损坏/失败 时首次解析失败，便于覆盖重试路径。
      state.parseOutcomes[fileId] = { fail: /fail|损坏|失败/i.test(uploaded.filename) };
      conversation.updatedAt = now;
      writeResult(res, json(file, 201));
      return true;
    }
    if (method === 'GET') {
      advanceParses();
      const conversationId = searchParams.get('conversationId');
      const items = state.files
        .filter((file) => file.workspaceId === workspaceId)
        .filter((file) => file.status !== 'DELETED')
        .filter((file) => !conversationId || file.conversationId === conversationId)
        .sort((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt));
      writeResult(res, json(page(items)));
      return true;
    }
  }

  const fileMatch = /^\/api\/v1\/files\/([^/]+)$/.exec(pathname);
  if (fileMatch && method === 'GET') {
    advanceParses();
    const file = state.files.find((item) => item.id === decodeURIComponent(fileMatch[1]));
    writeResult(
      res,
      file ? json(file) : problem(404, 'RICCON-TENANT-404', '文件不存在', '文件不存在或已被删除。'),
    );
    return true;
  }

  const retryParseMatch = /^\/api\/v1\/files\/([^/]+)\/parse-retries$/.exec(pathname);
  if (retryParseMatch && method === 'POST') {
    const csrfError = requireCsrf(session, req);
    if (csrfError) {
      writeResult(res, csrfError);
      return true;
    }
    const file = state.files.find((item) => item.id === decodeURIComponent(retryParseMatch[1]));
    if (!file) {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '文件不存在', '文件不存在或已被删除。'));
      return true;
    }
    const now = new Date().toISOString();
    file.parse = {
      id: nextId('parse'),
      attempt: (file.parse?.attempt ?? 0) + 1,
      status: 'PENDING',
      parserVersion: 'stub-parser-1',
      pageCount: null,
      sheetCount: null,
      chunkCount: null,
      errorCode: null,
      errorMessage: null,
      startedAt: now,
      finishedAt: null,
    };
    // 重试会成功，便于验证失败 → 恢复路径。
    state.parseOutcomes[file.id] = { fail: false };
    writeResult(res, json(file, 202));
    return true;
  }

  const downloadMatch = /^\/api\/v1\/files\/([^/]+)\/download-url$/.exec(pathname);
  if (downloadMatch && method === 'GET') {
    const file = state.files.find((item) => item.id === decodeURIComponent(downloadMatch[1]));
    if (!file) {
      writeResult(res, problem(404, 'RICCON-TENANT-404', '文件不存在', '文件不存在或已被删除。'));
      return true;
    }
    writeResult(
      res,
      json({
        url: `http://${req.headers.host}/objects/files/${encodeURIComponent(file.id)}?token=stub`,
        expiresAt: new Date(Date.now() + 5 * 60 * 1000).toISOString(),
      }),
    );
    return true;
  }

  return false;
}

/** HTTP 服务：控制端点 → 对象下载 → API；未匹配返回 404 ProblemDetail。 */
const server = http.createServer((req, res) => {
  const url = parseUrl(req);
  readBody(req)
    .then((raw) => {
      try {
        if (handleControl(req, res, url.pathname, raw)) {
          return;
        }
        if (handleObject(req, res, url.pathname)) {
          return;
        }
        if (handleApi(req, res, url, raw)) {
          return;
        }
        writeResult(res, problem(404, 'RICCON-NOT-FOUND', '接口不存在', 'stub 未实现该接口。'));
      } catch (error) {
        // stub 内部异常要显式暴露给 E2E，不能静默成功。
        console.error('[stub] 处理请求失败', req.method, url.pathname, error);
        writeResult(res, problem(500, 'RICCON-STUB-500', 'stub 内部错误', String(error)));
      }
    })
    .catch((error) => {
      if (error?.code === 'TOO_LARGE') {
        writeResult(res, problem(413, 'RICCON-FILE-413', '文件过大', '单文件不能超过 200 MB。'));
        return;
      }
      console.error('[stub] 读取请求体失败', error);
      try {
        writeResult(res, problem(500, 'RICCON-STUB-500', 'stub 内部错误', String(error)));
      } catch {
        res.destroy();
      }
    });
});

server.listen(PORT, HOST, () => {
  console.log(`[stub] Riccon E2E stub API listening on http://${HOST}:${PORT}`);
});
