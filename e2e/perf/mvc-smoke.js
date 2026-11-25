// M04 性能冒烟（PERF-M04-001/002）。
//
// 运行方式（推荐）：由 `scripts/real-stack/run.mjs --mode=perf` 编排，脚本会自动注入
// 环境快照、登录账号、工作空间与 50 并发 profile。也可以直接：
//
//   PERF_M04_ENV_JSON='{"chatMaxStreams":50,"dbPoolSize":25,"fakeModel":1}' \
//   PERF_M04_BASE_URL=http://127.0.0.1:18080 \
//   k6 run e2e/perf/mvc-smoke.js
//
// 场景（PERF_M04_SCENARIO / PERF_M05_SCENARIO）：
// - first_token：50 并发首字 p95 ≤ 3s；
// - failover：50 并发 + 首字前故障注入（PERF_M04_FAILURE_MODE）后仍 100% 成功；
// - quota：开关 true + 限额=已用+1，50 并发恰好 1 成功/49 个 429（零超卖）；
// - cost：30/180 天成本查询 p95 ≤ 2s（需要 B7 cost API）；
// - knowledge（M05）：50 并发聊天（内部触发 10 万 chunk 检索）+ 列表/详情/ACL 读/
//   下载 + 单 VU ACL 写 + 不可见文档 404；首字 p95 ≤3s、稳态 JSON API p95 ≤500ms。
//   检索 p95 不在 k6 内断言，由 run.mjs 从 MySQL slow_log 汇总并写回报告。
//
// 测量口径：`m04_api_ms` 只统计稳态 JSON API（GET /runs、成本查询）；登录、workspace 列表、
// 会话创建、开关/配额 setup 与 cleanup 不计入，避免认证 bcrypt 与每 VU 初始化把业务 API p95 拉偏。
// 聊天提交是 SSE：TTFB 单独记入 `m04_chat_submit_ttfb_ms`，完整流记入 `m04_chat_stream_ms`；
// 它受首字 p95 ≤ 3s 预算约束，不并入 500ms 的 JSON API 预算。阈值不变。
//
// 退出码：阈值失败或前置环境不满足时 k6 非零；这是 gate 信号，不做告警降级。
// 报告：写 `PERF_M04_REPORT_FILE` 或 `PERF_M04_REPORT_DIR/mvc-smoke-<scenario>.json`，
// 内含 host CPU/内存、镜像 tag、k6 版本、脚本 sha、启动命令与 mock 所在主机规格。

import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

/** 当前场景；M05 知识库场景用 PERF_M05_SCENARIO，其余沿用 M04 变量。 */
const scenario = (__ENV.PERF_M05_SCENARIO || __ENV.PERF_M04_SCENARIO || 'first_token').trim();

/** API 根地址。 */
const baseUrl = (__ENV.PERF_M04_BASE_URL || 'http://127.0.0.1:18080').replace(/\/$/, '');

/** 账号与空间；由 seed 的 real-stack-seed.json 派生。 */
const tenant = __ENV.PERF_M04_TENANT || 'tenant-alpha';
const member = __ENV.PERF_M04_MEMBER || 'alpha-member';
const memberPassword = __ENV.PERF_M04_MEMBER_PASSWORD || 'alpha-member-password-12';
const adminUser = __ENV.PERF_M04_ADMIN || 'alpha-admin';
const adminPassword = __ENV.PERF_M04_ADMIN_PASSWORD || 'alpha-admin-password-12';
const platformTenant = __ENV.PERF_M04_PLATFORM_TENANT || 'platform';
const platformUser = __ENV.PERF_M04_PLATFORM_USER || 'platform-admin';
const platformPassword = __ENV.PERF_M04_PLATFORM_PASSWORD || 'test-bootstrap-password-12';
const workspaceId = __ENV.PERF_M04_WORKSPACE_ID || '';
const failureMode = (__ENV.PERF_M04_FAILURE_MODE || 'server_error').trim();

/**
 * M05 知识库场景输入；由 run.mjs 在 seed 后注入。
 *
 * `PERF_M05_QUERY` 必须是能在 10 万 chunk 上命中一部分数据的稳定词；
 * `PERF_M05_DOC_ID` 为员工可见文档，`PERF_M05_HIDDEN_DOC_ID` 为仅管理员可见
 * （员工必须 404），`PERF_M05_ACL_DOC_ID` 为 ACL 写目标，`PERF_M05_DOWNLOAD_DOC_ID`
 * 为可下载的真实上传文档。缺任一输入直接失败，避免把空场景当通过。
 */
const m05DocumentId = __ENV.PERF_M05_DOC_ID || '';
const m05AclDocumentId = __ENV.PERF_M05_ACL_DOC_ID || '';
const m05HiddenDocumentId = __ENV.PERF_M05_HIDDEN_DOC_ID || '';
const m05DownloadDocumentId = __ENV.PERF_M05_DOWNLOAD_DOC_ID || '';
const m05Query = __ENV.PERF_M05_QUERY || 'QXZJKW';

/**
 * M05b 连接器同步分支标记；同步/索引本身由 `scripts/real-stack/run.mjs` 编排，
 * k6 只负责在连接器文档入库后制造 50 并发检索/聊天负载并把连接器元数据写进报告。
 * 该分支默认关闭，不改变既有 M05 knowledge 场景的并发与查询语义。
 */
const m05ConnectorEnabled = __ENV.PERF_M05_CONNECTOR === '1';
const m05Connector = m05ConnectorEnabled
  ? {
      objects: Number(__ENV.PERF_M05_CONNECTOR_OBJECTS || '0'),
      connectorId: __ENV.PERF_M05_CONNECTOR_ID || '',
      syncId: __ENV.PERF_M05_CONNECTOR_SYNC_ID || '',
      bucket: __ENV.PERF_M05_CONNECTOR_BUCKET || '',
      prefix: __ENV.PERF_M05_CONNECTOR_PREFIX || '',
    }
  : null;

/**
 * ACL 写场景开关；M05b 正式取证把 ACL 写 residual 单列时设置 `PERF_M05_ACL_WRITES=0`，
 * k6 不再启动 ACL 读写场景，也不对缺席的 ACL 指标做阈值断言。
 */
const m05AclWrites = Number(__ENV.PERF_M05_ACL_WRITES ?? '10');
if (!Number.isInteger(m05AclWrites) || m05AclWrites < 0) {
  fail(`PERF_M05_ACL_WRITES must be a non-negative integer, got ${__ENV.PERF_M05_ACL_WRITES}`);
}
const m05AclWriteEnabled = m05AclWrites > 0;

const m05AclUserIds = (() => {
  try {
    const parsed = JSON.parse(__ENV.PERF_M05_ACL_USER_IDS || '[]');
    return Array.isArray(parsed)
      ? parsed.filter((value) => typeof value === 'string' && value)
      : [];
  } catch (error) {
    return [];
  }
})();

/**
 * PERF-M06-001 输入：混合 J1/J2/M06 负载。
 *
 * workflowId 为只读运行夹具（仅自己能运行），adminWorkflowId 专供 save/validate/publish 写路径；
 * `mcpToolId` 用于 J2 报告任务。缺任一输入直接失败，避免空负载当通过。
 */
const m06WorkflowId = __ENV.PERF_M06_WORKFLOW_ID || '';
const m06WorkflowName = __ENV.PERF_M06_WORKFLOW_NAME || 'PERF-M06';
const m06AdminWorkflowId = __ENV.PERF_M06_ADMIN_WORKFLOW_ID || '';
const m06McpToolId = __ENV.PERF_M06_MCP_TOOL_ID || '';
const m06J1Vus = Number(__ENV.PERF_M06_J1_VUS || '25');
const m06J2Vus = Number(__ENV.PERF_M06_J2_VUS || '3');
const m06StudioVus = Number(__ENV.PERF_M06_STUDIO_VUS || '21');
const m06Editor = __ENV.PERF_M06_EDITOR || 'alpha-editor';
const m06EditorPassword = __ENV.PERF_M06_EDITOR_PASSWORD || 'alpha-editor-password-12';

/** 自定义指标；标签只保留场景/操作，不含租户或用户高基数标签。 */
const firstTokenMs = new Trend('m04_first_token_ms', true);
const chatStreamMs = new Trend('m04_chat_stream_ms', true);
/** 聊天提交 TTFB（SSE 首字节，含 Worker started 等待）；独立于 500ms JSON API 预算。 */
const chatSubmitTtfbMs = new Trend('m04_chat_submit_ttfb_ms', true);
const runDetailMs = new Trend('m04_run_detail_ms', true);
const apiMs = new Trend('m04_api_ms', true);
const degradedSuccess = new Rate('m04_degraded_success');
const quotaAllowed = new Counter('m04_quota_allowed');
const quotaRejected = new Counter('m04_quota_rejected');
const costQueryMs = new Trend('m04_cost_query_ms', true);
const costQueryOk = new Rate('m04_cost_query_ok');

/** M05 指标：首字/API/ACL 写/下载与 ACL 负向检查。检索 p95 由 run.mjs 从 MySQL slow_log 计算。 */
const m05FirstTokenMs = new Trend('m05_first_token_ms', true);
const m05FirstTokenOk = new Rate('m05_first_token_ok');
const m05ChatStreamMs = new Trend('m05_chat_stream_ms', true);
const m05ChatTtfbMs = new Trend('m05_chat_submit_ttfb_ms', true);
const m05ChatCitations = new Rate('m05_chat_citations');
const m05ApiMs = new Trend('m05_api_ms', true);
const m05ListMs = new Trend('m05_list_ms', true);
const m05DetailMs = new Trend('m05_detail_ms', true);
const m05ApiOk = new Rate('m05_api_ok');
const m05ListOk = new Rate('m05_list_ok');
const m05DetailOk = new Rate('m05_detail_ok');
const m05AclGetOk = new Rate('m05_acl_get_ok');
const m05RunDetailMs = new Trend('m05_run_detail_ms', true);
const m05AclWriteMs = new Trend('m05_acl_write_ms', true);
const m05AclWriteOk = new Rate('m05_acl_write_ok');
const m05AclGetMs = new Trend('m05_acl_get_ms', true);
const m05DownloadMs = new Trend('m05_download_ms', true);
const m05DownloadOk = new Rate('m05_download_ok');
const m05Denied404 = new Rate('m05_denied_404');

/** PERF-M06-001 指标：J1 首字、Studio JSON API、运行准入/完成、J2 报告与 429 拒绝。 */
const m06J1FirstTokenMs = new Trend('m06_j1_first_token_ms', true);
const m06J1FirstTokenOk = new Rate('m06_j1_first_token_ok');
const m06ApiMs = new Trend('m06_api_ms', true);
const m06RunAdmissionMs = new Trend('m06_run_admission_ms', true);
const m06RunOk = new Rate('m06_run_ok');
const m06RunRejected = new Counter('m06_run_rejected');
const m06RunCompletedOk = new Rate('m06_run_completed_ok');
const m06J2Ok = new Rate('m06_j2_ok');
const m06J2DurationMs = new Trend('m06_j2_duration_ms', true);

/**
 * 解析并校验环境快照；缺少 profile 证据时直接失败，避免把默认 pool/stream 当成 perf 结果。
 *
 * @returns {object} 环境快照
 */
function loadEnvSnapshot() {
  const raw = __ENV.PERF_M04_ENV_JSON;
  if (!raw) {
    fail(
      'PERF_M04_ENV_JSON is required: use scripts/real-stack/run.mjs --mode=perf or provide a full env snapshot',
    );
  }
  let snapshot;
  try {
    snapshot = JSON.parse(raw);
  } catch (error) {
    fail(`PERF_M04_ENV_JSON must be valid JSON: ${error}`);
  }
  const streams = Number(snapshot.chatMaxStreams);
  const pool = Number(snapshot.dbPoolSize);
  const fakeModel = String(snapshot.fakeModel);
  if (!(streams >= 50)) {
    fail(`RICCON_CHAT_MAX_STREAMS must be >= 50, got ${snapshot.chatMaxStreams}`);
  }
  if (!(pool >= 25)) {
    fail(`RICCON_DB_POOL_SIZE must be >= 25, got ${snapshot.dbPoolSize}`);
  }
  if (fakeModel !== '1') {
    fail(`RICCON_FAKE_MODEL must be 1, got ${snapshot.fakeModel}`);
  }
  return snapshot;
}

const envSnapshot = loadEnvSnapshot();

if (scenario === 'knowledge') {
  requireKnowledgeInputs();
  requireConnectorInputs();
}
if (scenario === 'workflow') {
  requireWorkflowInputs();
}

/** 场景级阈值；未列出的场景只记录指标，不伪造通过。 */
function buildThresholds() {
  if (scenario === 'first_token') {
    return {
      m04_first_token_ms: ['p(95)<3000'],
      m04_api_ms: ['p(95)<500'],
    };
  }
  if (scenario === 'failover') {
    return {
      m04_first_token_ms: ['p(95)<3000'],
      m04_api_ms: ['p(95)<500'],
      m04_degraded_success: ['rate>0.99'],
    };
  }
  if (scenario === 'quota') {
    return {
      m04_quota_allowed: ['count==1'],
      m04_quota_rejected: ['count==49'],
      m04_quota_cleanup: ['rate==1'],
    };
  }
  if (scenario === 'cost') {
    return {
      m04_cost_query_ms: ['p(95)<2000'],
      m04_cost_query_ok: ['rate==1'],
    };
  }
  if (scenario === 'workflow') {
    return {
      // J1 首字使用 run 详情的 modelInvocation.firstTokenMs，不是 SSE TTFB。
      m06_j1_first_token_ms: ['p(95)<3000'],
      m06_j1_first_token_ok: ['rate>0.99'],
      // Studio 列表/详情/保存/发布/运行准入/审批查询/历史查询；不含 SSE TTFB。
      m06_api_ms: ['p(95)<500'],
      m06_run_admission_ms: ['p(95)<500'],
      // 已接受的运行必须全部成功完成；429 单独计数，不伪装成失败或通过。
      m06_run_ok: ['rate>0.99'],
      m06_run_completed_ok: ['rate>0.99'],
      m06_j2_ok: ['rate>0.99'],
    };
  }
  if (scenario === 'knowledge') {
    // 检索 p95 不在 k6 阈值内：ADR-0023 §9 要求先量测，超 500ms 由 run.mjs 标记
    // decisionRequired 并触发 FTS/OpenSearch 决策，不在这里静默放宽或伪造阈值。
    const thresholds = {
      m05_first_token_ms: ['p(95)<3000'],
      m05_first_token_ok: ['rate>0.99'],
      // 注意：k6 对 SSE 是整段缓冲，timings.waiting 接近完整流，不能当作首字预算。
      // 检索对首字的影响通过 run.mjs 的 retrieval p95 + 服务端首字 p95 估算并在报告标注。
      m05_api_ms: ['p(95)<500'],
      m05_api_ok: ['rate>0.99'],
      m05_list_ok: ['rate>0.99'],
      m05_detail_ok: ['rate>0.99'],
      m05_download_ok: ['rate>0.99'],
      m05_denied_404: ['rate==1'],
      m05_chat_citations: ['rate>0.99'],
    };
    if (m05AclWriteEnabled) {
      // ACL 写/读只在独立场景启用时断言；`PERF_M05_ACL_WRITES=0` 时指标不存在，
      // 不能把“未执行”当成通过，也不能让缺失指标制造假失败。
      thresholds.m05_acl_write_ms = ['p(95)<500'];
      thresholds.m05_acl_write_ok = ['rate==1'];
      thresholds.m05_acl_get_ms = ['p(95)<500'];
      thresholds.m05_acl_get_ok = ['rate==1'];
    }
    return thresholds;
  }
  fail(`unknown PERF_M04_SCENARIO: ${scenario}`);
}

/**
 * 构造 k6 options；不同场景使用独立执行器，避免配额/故障状态互相污染。
 *
 * @returns {object} k6 options
 */
function buildOptions() {
  const duration = __ENV.PERF_M04_DURATION || '45s';
  const scenarios = {
    first_token: {
      first_token: { executor: 'constant-vus', vus: 50, duration, exec: 'firstToken' },
    },
    failover: {
      failover: { executor: 'constant-vus', vus: 50, duration, exec: 'failover' },
    },
    quota: {
      quota: { executor: 'per-vu-iterations', vus: 1, iterations: 1, exec: 'quotaContention' },
    },
    cost: {
      cost: {
        executor: 'constant-vus',
        vus: 2,
        duration: __ENV.PERF_M04_COST_DURATION || '15s',
        exec: 'costQuery',
      },
    },
    workflow: {
      // 50 VU 混合：J1 问答 25 + J2 报告 3 + M06 运行 21 + save/publish 写路径 1。
      // 审批等待不占执行并发；M06 运行准入受 tenantConcurrency 限制，429 单列。
      m06_j1: {
        executor: 'constant-vus',
        vus: m06J1Vus,
        duration: __ENV.PERF_M04_DURATION || '45s',
        exec: 'workflowJ1',
      },
      m06_j2: {
        executor: 'constant-vus',
        vus: m06J2Vus,
        duration: __ENV.PERF_M04_DURATION || '45s',
        exec: 'workflowJ2',
      },
      m06_studio: {
        executor: 'constant-vus',
        vus: m06StudioVus,
        duration: __ENV.PERF_M04_DURATION || '45s',
        exec: 'workflowStudio',
      },
      m06_write: {
        executor: 'constant-vus',
        vus: 1,
        duration: __ENV.PERF_M04_DURATION || '45s',
        exec: 'workflowWrite',
      },
    },
    knowledge: {
      // 主负载：50 VU 同时做知识库聊天（内部触发检索）、列表、详情与下载；
      // ACL 写由独立单 VU 场景并行执行，避免把写频率混入员工读路径。
      knowledge: {
        executor: 'constant-vus',
        vus: 50,
        duration: __ENV.PERF_M05_DURATION || '60s',
        exec: 'knowledgeWorkload',
      },
      ...(m05AclWriteEnabled
        ? {
            knowledge_acl_write: {
              executor: 'per-vu-iterations',
              vus: 1,
              iterations: m05AclWrites,
              // 避开 50 VU 同时登录的 bcrypt/凭据行锁冷启动，只测稳态 ACL 写。
              startTime: __ENV.PERF_M05_ACL_START || '15s',
              maxDuration: '60s',
              exec: 'knowledgeAclWrite',
            },
          }
        : {}),
    },
  };
  return {
    scenarios: scenarios[scenario] ?? scenarios.first_token,
    thresholds: buildThresholds(),
    batch: 50,
    batchPerHost: 50,
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'max', 'count'],
  };
}

export const options = buildOptions();

/** 每个 VU 的会话缓存；k6 模块作用域按 VU 隔离。 */
let session = null;
let conversationId = null;

/**
 * 清空 VU 默认 cookie jar。
 *
 * k6 会把响应 Set-Cookie 存入 VU 级 jar，并在请求时与显式 Cookie 头合并；平台/租户/员工
 * 共用同一 VU 时，后登录角色会覆盖显式 Cookie（V2 F-004）。本脚本所有会话都通过显式
 * Cookie 头传递，因此每次请求前清空 jar 即可实现角色隔离。
 */
function clearCookieJar() {
  http.cookieJar().clear(baseUrl);
}

/**
 * 解析响应中的会话 Cookie 与登录 CSRF。
 *
 * @param {object} response k6 响应
 * @returns {{cookie: string, csrf: string}} 会话材料
 */
function sessionFromResponse(response) {
  const cookies = response.cookies || {};
  const names = Object.keys(cookies);
  const cookie = names.map((name) => `${name}=${cookies[name][0].value}`).join('; ');
  let csrf = '';
  try {
    csrf = response.json().csrfToken || '';
  } catch (error) {
    csrf = '';
  }
  return { cookie, csrf };
}

/**
 * 登录并返回会话；k6 不跨 VU 共享 Cookie，因此每个场景显式建会话。
 *
 * @param {string} tenantCode 租户
 * @param {string} username 用户名
 * @param {string} password 密码
 * @returns {{cookie: string, csrf: string}} 会话材料
 */
function login(tenantCode, username, password) {
  clearCookieJar();
  const csrfResponse = http.get(`${baseUrl}/api/v1/auth/csrf`, {
    tags: { kind: 'api', op: 'csrf' },
  });
  if (!check(csrfResponse, { 'csrf 200': (r) => r.status === 200 })) {
    fail(`csrf failed: ${csrfResponse.status}`);
  }
  const anonymous = sessionFromResponse(csrfResponse);
  const csrfToken = csrfResponse.json().token;
  // 登录请求只带匿名会话的显式 Cookie；先清空 jar，避免同名 Cookie 合并。
  clearCookieJar();
  const loginResponse = http.post(
    `${baseUrl}/api/v1/auth/login`,
    JSON.stringify({ tenantCode, username, password }),
    {
      headers: {
        'content-type': 'application/json',
        'x-csrf-token': csrfToken,
        cookie: anonymous.cookie,
      },
      tags: { kind: 'api', op: 'login' },
    },
  );
  if (!check(loginResponse, { '登录 200': (r) => r.status === 200 })) {
    fail(`login failed for ${tenantCode}/${username}: ${loginResponse.status}`);
  }
  const authenticated = sessionFromResponse(loginResponse);
  // 登录完成后清空 jar；后续请求按角色显式 Cookie，互不覆盖。
  clearCookieJar();
  return {
    cookie: authenticated.cookie || anonymous.cookie,
    csrf: authenticated.csrf,
  };
}

/**
 * 校验会话身份与角色，作为 Cookie 隔离的运行时自检。
 *
 * @param {object} current 会话
 * @param {string} label 角色标签
 * @param {string} expectedTenant 期望租户代码
 * @param {string} expectedUser 期望用户名
 * @param {string} expectedRole 期望角色码
 */
function assertSession(current, label, expectedTenant, expectedUser, expectedRole) {
  const response = api(
    current,
    'GET',
    '/api/v1/auth/session',
    undefined,
    { tags: { kind: 'api', op: 'session' } },
    false,
  );
  if (response.status !== 200) {
    fail(`${label} session check failed: ${response.status} ${response.body}`);
  }
  const body = response.json();
  const actualTenant = body.tenant?.id;
  const actualUser = body.user?.username;
  if (actualTenant !== expectedTenant || actualUser !== expectedUser) {
    fail(
      `${label} session mismatch: tenant=${actualTenant} user=${actualUser}; ` +
        `expected ${expectedTenant}/${expectedUser}`,
    );
  }
  if (!Array.isArray(body.roles) || !body.roles.includes(expectedRole)) {
    fail(`${label} role mismatch: ${JSON.stringify(body.roles)}; expected ${expectedRole}`);
  }
}

/**
 * 带会话的 JSON 请求。
 *
 * @param {object} current 会话
 * @param {string} method HTTP 方法
 * @param {string} path 路径
 * @param {object} [body] JSON 体
 * @param {object} [params] k6 参数覆盖
 * @param {boolean} [measure] 是否计入 API 指标；认证/工作空间/会话/setup 传 false
 * @param {'m04'|'m05'} [series] 指标序列；M05 场景写 m05_api_ms，避免污染 M04 基线
 * @returns {object} k6 响应
 */
function api(current, method, path, body, params = {}, measure = true, series = 'm04') {
  // 每次请求前清空 jar，确保只发送当前角色的显式 Cookie（V2 F-004）。
  clearCookieJar();
  const headers = {
    cookie: current.cookie,
    'content-type': 'application/json',
  };
  if (!['GET', 'HEAD'].includes(method)) {
    headers['x-csrf-token'] = current.csrf;
  }
  const { headers: paramHeaders, tags: paramTags, ...rest } = params;
  const response = http.request(
    method,
    `${baseUrl}${path}`,
    body === undefined ? null : JSON.stringify(body),
    {
      ...rest,
      headers: paramHeaders || headers,
      tags: { kind: 'api', ...(paramTags || {}) },
    },
  );
  const kind = paramTags?.kind || 'api';
  if (measure && kind === 'api') {
    if (series === 'm05') {
      m05ApiMs.add(response.timings.duration, { op: paramTags?.op || 'api' });
    } else {
      apiMs.add(response.timings.duration, { op: paramTags?.op || 'api' });
    }
  }
  return response;
}

/**
 * 读取当前成员的工作空间；env 没提供时回退到列表。
 *
 * @param {object} current 会话
 * @returns {string} 工作空间 ID
 */
function resolveWorkspace(current) {
  if (workspaceId) {
    return workspaceId;
  }
  const response = api(current, 'GET', '/api/v1/workspaces?page=1&size=100', undefined, {}, false);
  if (response.status !== 200) {
    fail(`list workspaces failed: ${response.status}`);
  }
  const workspace = response.json().items.find((item) => item.status === 'ACTIVE');
  if (!workspace) {
    fail('no active workspace for perf member');
  }
  return workspace.id;
}

/**
 * 确保 VU 会话与对话存在。
 *
 * @returns {{session: object, conversationId: string}} 上下文
 */
function ensureContext() {
  if (!session) {
    session = login(tenant, member, memberPassword);
  }
  if (!conversationId) {
    const response = api(
      session,
      'POST',
      `/api/v1/workspaces/${resolveWorkspace(session)}/conversations`,
      {
        title: 'PERF-M04 冒烟会话',
      },
      {},
      false,
    );
    if (response.status !== 201) {
      fail(`create conversation failed: ${response.status} ${response.body}`);
    }
    conversationId = response.json().id;
  }
  return { session, conversationId };
}

/**
 * 发送一次消息并等待 SSE 完成；返回 runId 与服务端首字延迟。
 *
 * @param {object} current 会话
 * @param {string} conversation 对话 ID
 * @param {string} tag 场景标签
 * @param {string} [content] 消息内容；M05 用可命中知识库的查询词
 * @param {'m04'|'m05'} [series] 指标序列
 * @returns {{status: number, runId: string|null, body: string, duration: number}} 结果
 */
function sendChat(current, conversation, tag, content, series = 'm04') {
  const startedAt = Date.now();
  const message = content || 'PERF-M04 首字与降级冒烟，请返回固定演示内容。';
  const response = api(
    current,
    'POST',
    `/api/v1/conversations/${conversation}/messages`,
    {
      content: message,
      clientMessageId: `perf-${__VU}-${__ITER}-${Date.now()}`,
    },
    {
      headers: {
        cookie: current.cookie,
        'content-type': 'application/json',
        'x-csrf-token': current.csrf,
        'idempotency-key': `perf-${__VU}-${__ITER}-${Date.now()}`,
      },
      tags: { kind: 'sse', op: tag },
      timeout: __ENV.PERF_M04_SSE_TIMEOUT || '30s',
    },
  );
  const duration = Date.now() - startedAt;
  // 聊天提交是 SSE 端点：`ChatService.send` 返回前会等待 Worker 的 started 事件，
  // TTFB 含 Worker 排队/路由（本矩阵中约 1.4s），不能并入 500ms 的 JSON API 预算；
  // 这里单独记录 TTFB，完整流时长仍由聊天流指标记录。
  if (series === 'm05') {
    m05ChatStreamMs.add(duration, { scenario: tag });
    m05ChatTtfbMs.add(response.timings.waiting, { scenario: tag });
  } else {
    chatStreamMs.add(duration, { scenario: tag });
    chatSubmitTtfbMs.add(response.timings.waiting, { scenario: tag });
  }
  let runId = null;
  if (response.status === 200) {
    const match = /"runId":"([0-9a-f-]{36})"/.exec(response.body || '');
    runId = match ? match[1] : null;
  }
  return { status: response.status, runId, body: response.body || '', duration };
}

/**
 * 读取运行详情中的服务端首字延迟。
 *
 * @param {object} current 会话
 * @param {string} runId 运行 ID
 * @param {'m04'|'m05'} [series] 指标序列
 * @returns {{ok: boolean, firstToken: number|null, status: string|null}} 结果
 */
function readFirstToken(current, runId, series = 'm04') {
  if (!runId) {
    return { ok: false, firstToken: null, status: null };
  }
  const startedAt = Date.now();
  const response = api(current, 'GET', `/api/v1/runs/${runId}`, undefined, {
    tags: { kind: 'api', op: 'run-detail' },
  });
  const detailMs = Date.now() - startedAt;
  if (series === 'm05') {
    m05RunDetailMs.add(detailMs, { op: 'run-detail' });
  } else {
    runDetailMs.add(detailMs, { op: 'run-detail' });
  }
  if (!check(response, { '运行详情 200': (r) => r.status === 200 })) {
    return { ok: false, firstToken: null, status: null };
  }
  const detail = response.json();
  const invocation = (detail.modelInvocations || []).find((item) => item.purpose === 'CHAT');
  const value = invocation ? invocation.firstTokenMs : null;
  return {
    ok: Boolean(invocation && invocation.status === 'SUCCEEDED' && Number.isInteger(value)),
    firstToken: Number.isInteger(value) ? value : null,
    status: invocation ? invocation.status : null,
  };
}

/**
 * 校验 PERF-M06-001 输入；缺失直接失败，不跑空负载。
 */
function requireWorkflowInputs() {
  const missing = [];
  if (!m06WorkflowId) {
    missing.push('PERF_M06_WORKFLOW_ID');
  }
  if (!m06AdminWorkflowId) {
    missing.push('PERF_M06_ADMIN_WORKFLOW_ID');
  }
  if (!m06McpToolId) {
    missing.push('PERF_M06_MCP_TOOL_ID');
  }
  if (!workspaceId) {
    missing.push('PERF_M04_WORKSPACE_ID');
  }
  if (missing.length > 0) {
    fail(`workflow scenario requires ${missing.join(', ')}; run scripts/real-stack/run.mjs --mode=perf with PERF_M06_SCENARIO=workflow`);
  }
  if (m06J1Vus + m06J2Vus + m06StudioVus + 1 !== 50) {
    fail(`M06 mixed load must total 50 VUs, got ${m06J1Vus + m06J2Vus + m06StudioVus + 1}`);
  }
}

/** M06 编辑器会话缓存（每 VU 独立）。 */
let m06Session = null;
/** J2 审批管理员会话缓存。 */
let j2AdminSession = null;

/**
 * 确保 M06 编辑器会话。
 *
 * @returns {object} 会话
 */
function m06EnsureSession() {
  if (!m06Session) {
    m06Session = login(tenant, m06Editor, m06EditorPassword);
  }
  return m06Session;
}

/**
 * 确保 J2 审批管理员会话。
 *
 * @returns {object} 会话
 */
function j2EnsureApprover() {
  if (!j2AdminSession) {
    j2AdminSession = login(tenant, adminUser, adminPassword);
  }
  return j2AdminSession;
}

/**
 * Studio JSON 请求；记录 m06_api_ms，不并入 M04 API 序列。
 *
 * @param {object} current 会话
 * @param {string} method HTTP 方法
 * @param {string} path 路径
 * @param {object} [body] JSON 体
 * @param {string} op 操作标签
 * @param {object} [extraHeaders] 额外请求头
 * @returns {object} k6 响应
 */
function m06Request(current, method, path, body, op, extraHeaders = {}) {
  const headers = {
    cookie: current.cookie,
    'content-type': 'application/json',
    ...(method === 'GET' ? {} : { 'x-csrf-token': current.csrf }),
    ...extraHeaders,
  };
  const startedAt = Date.now();
  const response = api(current, method, path, body, { tags: { kind: 'api', op }, headers }, false);
  m06ApiMs.add(Date.now() - startedAt, { op });
  return response;
}

/**
 * J1 混合负载：发送聊天并读取模型首字（不是 SSE TTFB）。
 */
export function workflowJ1() {
  const current = ensureContext();
  const chat = sendChat(current.session, current.conversationId, 'm06-j1');
  const first = readFirstToken(current.session, chat.runId, 'm04');
  const ok = chat.status === 200 && first.ok;
  m06J1FirstTokenOk.add(ok);
  if (first.ok) {
    m06J1FirstTokenMs.add(first.firstToken, { scenario: 'workflow' });
  }
  check(first, {
    'M06 混合 J1 首字存在且成功': (value) => value.ok,
  });
}

/**
 * J2 混合负载：创建报告任务 → 等待审批 → 管理员批准 → 等待产物/引用。
 */
export function workflowJ2() {
  const current = ensureContext();
  const startedAt = Date.now();
  const topic = `PERF-M06 J2 ${__VU}-${__ITER}-${Date.now()}`;
  const created = api(
    current.session,
    'POST',
    `/api/v1/workspaces/${workspaceId}/research-tasks`,
    { topic, mcpToolId: m06McpToolId },
    {
      headers: {
        cookie: current.session.cookie,
        'content-type': 'application/json',
        'x-csrf-token': current.session.csrf,
        'idempotency-key': `perf-m06-j2-${__VU}-${__ITER}-${Date.now()}`,
      },
      tags: { kind: 'api', op: 'j2-create' },
    },
    false,
  );
  let ok = created.status === 201;
  let taskId = ok ? created.json().id : null;
  if (ok) {
    const approver = j2EnsureApprover();
    let terminal = false;
    for (let attempt = 0; attempt < 120 && !terminal; attempt += 1) {
      const detail = api(
        current.session,
        'GET',
        `/api/v1/research-tasks/${taskId}`,
        undefined,
        { tags: { kind: 'api', op: 'j2-detail' } },
        false,
      );
      if (detail.status !== 200) {
        ok = false;
        break;
      }
      const body = detail.json();
      if (body.status === 'WAITING_APPROVAL') {
        const approval = (body.approvals || []).find((item) => item.status === 'PENDING');
        if (!approval) {
          ok = false;
          break;
        }
        const decided = api(
          approver,
          'POST',
          `/api/v1/approvals/${approval.id}/decision`,
          { decision: 'APPROVE', comment: 'PERF-M06 自动审批' },
          {
            headers: {
              cookie: approver.cookie,
              'content-type': 'application/json',
              'x-csrf-token': approver.csrf,
              'idempotency-key': `perf-m06-j2-approve-${approval.id}`,
            },
            tags: { kind: 'api', op: 'j2-approve' },
          },
          false,
        );
        if (decided.status !== 200) {
          ok = false;
          break;
        }
      } else if (body.status === 'SUCCEEDED') {
        ok = ok && Boolean(body.artifact);
        terminal = true;
      } else if (['FAILED', 'CANCELLED'].includes(body.status)) {
        ok = false;
        terminal = true;
      }
      if (!terminal) {
        sleep(0.5);
      }
    }
    if (!terminal) {
      ok = false;
    }
  }
  m06J2Ok.add(ok);
  m06J2DurationMs.add(Date.now() - startedAt, { scenario: 'workflow' });
  check({ ok }, { 'M06 混合 J2 报告成功': (value) => value.ok });
}

/**
 * M06 混合负载：列表/详情/历史/审批查询 + 运行准入与完成；429 单独计数。
 */
export function workflowStudio() {
  const current = m06EnsureSession();
  const list = m06Request(current, 'GET', '/api/v1/workflows?view=MINE&page=1&size=20', undefined, 'workflow-list');
  check(list, { 'Studio 列表 200': (response) => response.status === 200 });
  const detail = m06Request(
    current,
    'GET',
    `/api/v1/workflows/${m06WorkflowId}`,
    undefined,
    'workflow-detail',
  );
  check(detail, { 'Studio 详情 200': (response) => response.status === 200 });
  const history = m06Request(
    current,
    'GET',
    '/api/v1/workflow-runs?page=1&size=20',
    undefined,
    'workflow-history',
  );
  check(history, { '运行历史 200': (response) => response.status === 200 });
  const inbox = m06Request(
    current,
    'GET',
    '/api/v1/workflow-approvals?page=1&size=20',
    undefined,
    'workflow-approvals',
  );
  check(inbox, { '审批查询 200': (response) => response.status === 200 });

  const admissionStartedAt = Date.now();
  const admission = m06Request(
    current,
    'POST',
    `/api/v1/workflows/${m06WorkflowId}/runs`,
    { inputs: { topic: `PERF-M06 ${__VU}-${__ITER}` } },
    'workflow-run-admission',
    { 'Idempotency-Key': `perf-m06-run-${__VU}-${__ITER}-${Date.now()}` },
  );
  m06RunAdmissionMs.add(Date.now() - admissionStartedAt, { op: 'workflow-run-admission' });
  if (admission.status === 429) {
    m06RunRejected.add(1);
    let code = '';
    try {
      code = admission.json().code || '';
    } catch (error) {
      code = '';
    }
    check({ code }, { '并发上限提示 RICCON-WF-429': (value) => value.code === 'RICCON-WF-429' });
    return;
  }
  if (admission.status !== 201) {
    m06RunOk.add(false);
    m06RunCompletedOk.add(false);
    fail(`run admission failed: ${admission.status} ${admission.body}`);
  }
  m06RunOk.add(true);
  const runId = admission.json().id;
  let completed = false;
  for (let attempt = 0; attempt < 180; attempt += 1) {
    const run = api(
      current,
      'GET',
      `/api/v1/workflow-runs/${runId}`,
      undefined,
      { tags: { kind: 'api', op: 'workflow-run-detail' } },
      false,
    );
    if (run.status !== 200) {
      break;
    }
    const body = run.json();
    if (body.status === 'SUCCEEDED') {
      const attemptsPerNode = {};
      for (const node of body.nodes || []) {
        attemptsPerNode[node.nodeId] = Math.max(attemptsPerNode[node.nodeId] || 0, node.attempt || 1);
      }
      completed =
        Object.values(attemptsPerNode).every((value) => value === 1) &&
        body.resultStatus === 'SAVED' &&
        body.workflowId === m06WorkflowId;
      break;
    }
    if (['FAILED', 'CANCELLED'].includes(body.status)) {
      break;
    }
    sleep(0.5);
  }
  m06RunCompletedOk.add(completed);
  check({ completed }, { '运行成功且无重复节点执行': (value) => value.completed });
}

/**
 * M06 保存/校验/发布写路径（1 VU）；覆盖 Studio API p95 的写操作。
 */
export function workflowWrite() {
  const current = m06EnsureSession();
  const detail = m06Request(
    current,
    'GET',
    `/api/v1/workflows/${m06AdminWorkflowId}`,
    undefined,
    'workflow-write-detail',
  );
  if (!check(detail, { '写路径详情 200': (response) => response.status === 200 })) {
    return;
  }
  const body = detail.json();
  const saved = m06Request(
    current,
    'PUT',
    `/api/v1/workflows/${m06AdminWorkflowId}/draft`,
    { version: body.draftVersion, name: `${m06WorkflowName} 写路径`, graph: body.draftGraph },
    'workflow-save',
  );
  check(saved, { '保存草稿 200': (response) => response.status === 200 });
  const validated = m06Request(
    current,
    'POST',
    `/api/v1/workflows/${m06AdminWorkflowId}/validate?runScope=ONLY_SELF`,
    undefined,
    'workflow-validate',
  );
  if (!check(validated, { '校验 200': (response) => response.status === 200 })) {
    return;
  }
  const published = m06Request(
    current,
    'POST',
    `/api/v1/workflows/${m06AdminWorkflowId}/publish`,
    { runScope: 'ONLY_SELF' },
    'workflow-publish',
    { 'Idempotency-Key': `perf-m06-publish-${__VU}-${__ITER}-${Date.now()}` },
  );
  check(published, { '发布 201': (response) => response.status === 201 });
}

/**
 * 首个场景：50 并发首字 p95。
 */
export function firstToken() {
  const current = ensureContext();
  const chat = sendChat(current.session, current.conversationId, 'first-token');
  if (chat.status !== 200) {
    fail(`chat failed: ${chat.status}`);
  }
  const result = readFirstToken(current.session, chat.runId);
  if (result.ok) {
    firstTokenMs.add(result.firstToken, { scenario: 'first-token' });
  }
  check(result, {
    首字存在且成功: (value) => value.ok,
    '首字 ≤ 3000ms': (value) => value.ok && value.firstToken <= 3000,
  });
}

/**
 * 校验 M05 场景输入；缺失时立即失败，避免用空文档 ID 跑出假绿。
 */
function requireKnowledgeInputs() {
  const missing = [];
  if (!m05DocumentId) {
    missing.push('PERF_M05_DOC_ID');
  }
  if (m05AclWriteEnabled) {
    if (!m05AclDocumentId) {
      missing.push('PERF_M05_ACL_DOC_ID');
    }
    if (m05AclUserIds.length === 0) {
      missing.push('PERF_M05_ACL_USER_IDS');
    }
  }
  if (!m05HiddenDocumentId) {
    missing.push('PERF_M05_HIDDEN_DOC_ID');
  }
  if (!m05DownloadDocumentId) {
    missing.push('PERF_M05_DOWNLOAD_DOC_ID');
  }
  if (missing.length > 0) {
    fail(
      `knowledge scenario requires ${missing.join(', ')}; run scripts/real-stack/run.mjs --mode=perf with PERF_M05_SCENARIO=knowledge`,
    );
  }
}

/**
 * 校验 M05b 连接器分支输入；启用标记时必须由 run.mjs 注入完整元数据，
 * 避免手工只设 `PERF_M05_CONNECTOR=1` 就得到一份没有同步证据的假绿报告。
 */
function requireConnectorInputs() {
  if (!m05ConnectorEnabled) {
    return;
  }
  const missing = [];
  if (!Number.isInteger(m05Connector.objects) || m05Connector.objects < 1) {
    missing.push('PERF_M05_CONNECTOR_OBJECTS');
  }
  if (!m05Connector.connectorId) {
    missing.push('PERF_M05_CONNECTOR_ID');
  }
  if (!m05Connector.syncId) {
    missing.push('PERF_M05_CONNECTOR_SYNC_ID');
  }
  if (missing.length > 0) {
    fail(
      `M05b connector branch requires ${missing.join(', ')}; run scripts/real-stack/run.mjs --mode=perf with PERF_M05_SCENARIO=knowledge PERF_M05_CONNECTOR=1`,
    );
  }
}

/**
 * 读取 M05 文档详情（计入 m05_api_ms；ACL 不可见时返回 404）。
 *
 * @param {object} current 会话
 * @param {string} documentId 文档 ID
 * @param {string} op 指标标签
 * @param {boolean} [measure] 是否计入 m05_api_ms 与 m05_detail_ms
 * @returns {{status: number, body: string}} 精简结果
 */
function knowledgeDetail(current, documentId, op, measure = true) {
  const startedAt = Date.now();
  const response = api(
    current,
    'GET',
    `/api/v1/knowledge/documents/${documentId}`,
    undefined,
    { tags: { kind: 'api', op } },
    measure,
    'm05',
  );
  if (measure) {
    m05DetailMs.add(Date.now() - startedAt, { op });
  }
  return { status: response.status, body: response.body || '' };
}

/**
 * M05 主负载：50 VU 并发聊天（内部触发 10 万 chunk 检索）、列表、详情、ACL 读、下载与 ACL 负向检查。
 *
 * 检索耗时不在 HTTP 响应里单独暴露；本函数只负责制造并发与记录首字/API 指标，
 * 检索 p95 由 run.mjs 从 MySQL slow_log 中同一条 LIKE 语句的执行时间计算。
 */
export function knowledgeWorkload() {
  const current = ensureContext();
  const chat = sendChat(current.session, current.conversationId, 'knowledge-chat', m05Query, 'm05');
  const chatOk = chat.status === 200;
  m05ChatCitations.add(
    chatOk &&
      /riccon\.chat\.citation\.v1/.test(chat.body) &&
      /"sourceType"\s*:\s*"KNOWLEDGE"/.test(chat.body),
  );
  const first = readFirstToken(current.session, chat.runId, 'm05');
  m05FirstTokenOk.add(chatOk && first.ok);
  if (first.ok) {
    m05FirstTokenMs.add(first.firstToken, { scenario: 'knowledge' });
  }
  check(first, {
    知识库聊天首字存在且成功: (value) => value.ok,
  });

  // 列表/详情计入 500ms 的稳态 JSON API 预算。
  const listStartedAt = Date.now();
  const list = api(
    current.session,
    'GET',
    '/api/v1/knowledge/documents?page=1&size=20',
    undefined,
    { tags: { kind: 'api', op: 'knowledge-list' } },
    true,
    'm05',
  );
  m05ListMs.add(Date.now() - listStartedAt, { op: 'knowledge-list' });
  m05ApiOk.add(list.status === 200);
  m05ListOk.add(list.status === 200);
  const detail = knowledgeDetail(current.session, m05DocumentId, 'knowledge-detail');
  m05ApiOk.add(detail.status === 200);
  m05DetailOk.add(detail.status === 200);

  // 下载是流式代理，单独记录时长与成功率，不与 500ms JSON 预算混算。
  const downloadStartedAt = Date.now();
  const download = api(
    current.session,
    'GET',
    `/api/v1/knowledge/documents/${m05DownloadDocumentId}/download`,
    undefined,
    {
      responseType: 'binary',
      tags: { kind: 'download', op: 'knowledge-download' },
      timeout: __ENV.PERF_M05_DOWNLOAD_TIMEOUT || '30s',
    },
    false,
    'm05',
  );
  m05DownloadMs.add(Date.now() - downloadStartedAt, { op: 'knowledge-download' });
  const downloadBytes = download.body ? (download.body.byteLength ?? download.body.length ?? 0) : 0;
  const downloadContentLength = Number(
    download.headers?.['Content-Length'] ?? download.headers?.['content-length'] ?? 0,
  );
  m05DownloadOk.add(download.status === 200 && (downloadBytes > 0 || downloadContentLength > 0));

  // ACL 负向：仅管理员可见的文档，员工详情与下载都必须统一 404。
  const hiddenDetail = knowledgeDetail(
    current.session,
    m05HiddenDocumentId,
    'knowledge-hidden',
    false,
  );
  const hiddenDownload = api(
    current.session,
    'GET',
    `/api/v1/knowledge/documents/${m05HiddenDocumentId}/download`,
    undefined,
    {
      responseType: 'binary',
      tags: { kind: 'acl-negative', op: 'knowledge-hidden-download' },
    },
    false,
    'm05',
  );
  m05Denied404.add(hiddenDetail.status === 404 && hiddenDownload.status === 404);
  if (hiddenDetail.status !== 404 || hiddenDownload.status !== 404) {
    console.error(
      `knowledge hidden ACL mismatch: detail=${hiddenDetail.status} download=${hiddenDownload.status}`,
    );
  }
  if (download.status !== 200 || downloadBytes + downloadContentLength <= 0) {
    console.error(
      `knowledge download unexpected: status=${download.status} bytes=${downloadBytes} contentLength=${downloadContentLength} headers=${JSON.stringify(download.headers)}`,
    );
  }

  check(
    {
      list: list.status,
      detail: detail.status,
      download: download.status,
      hiddenDetail: hiddenDetail.status,
      hiddenDownload: hiddenDownload.status,
    },
    {
      '知识库列表 200': (value) => value.list === 200,
      '知识库详情 200': (value) => value.detail === 200,
      '知识库下载 200': (value) => value.download === 200,
      '不可见文档详情 404': (value) => value.hiddenDetail === 404,
      '不可见文档下载 404': (value) => value.hiddenDownload === 404,
    },
  );

  // 控制请求节奏：50 VU 在 60s 内保持并发，但不把同一租户打到百万请求级。
  sleep(Number(__ENV.PERF_M05_THINK_TIME || '0.8'));
}

/**
 * ACL 写场景：单 VU 在并行读负载下反复整体替换 USERS 名单，验证 ACL 写 p95。
 *
 * 目标文档始终保留成员授权，避免把员工正在读的文档切到不可见后污染首字指标。
 */
export function knowledgeAclWrite() {
  const admin = login(tenant, adminUser, adminPassword);
  const who = api(admin, 'GET', '/api/v1/auth/session', undefined, {}, false, 'm05');
  if (who.status !== 200 || who.json()?.user?.username !== adminUser) {
    console.error(
      `knowledge ACL session mismatch: status=${who.status} user=${who.json()?.user?.username} expected=${adminUser}`,
    );
  }
  // ACL 读要求 knowledge.acl.manage，只有管理员能读；员工读被拒不是性能信号。
  const aclStartedAt = Date.now();
  const acl = api(
    admin,
    'GET',
    `/api/v1/knowledge/documents/${m05AclDocumentId}/acl`,
    undefined,
    { tags: { kind: 'api', op: 'knowledge-acl-get' } },
    false,
    'm05',
  );
  m05AclGetMs.add(Date.now() - aclStartedAt, { op: 'knowledge-acl-get' });
  m05AclGetOk.add(acl.status === 200);
  if (acl.status !== 200) {
    console.error(`knowledge ACL get failed: status=${acl.status} body=${acl.body}`);
  }
  const targetUserIds =
    __ITER % 2 === 0
      ? m05AclUserIds
      : m05AclUserIds.slice(0, Math.max(1, m05AclUserIds.length - 1));
  const startedAt = Date.now();
  const response = api(
    admin,
    'PUT',
    `/api/v1/knowledge/documents/${m05AclDocumentId}/acl`,
    { visibility: 'USERS', departmentId: null, userIds: targetUserIds },
    { tags: { kind: 'api', op: 'knowledge-acl-write' } },
    false,
    'm05',
  );
  m05AclWriteMs.add(Date.now() - startedAt, { op: 'knowledge-acl-write' });
  const ok = response.status === 200;
  m05AclWriteOk.add(ok);
  check(response, {
    'ACL 写 200': (value) => value.status === 200,
  });
  if (!ok) {
    console.error(`knowledge ACL write failed: status=${response.status} body=${response.body}`);
  }
}

/**
 * 降级矩阵场景：首字前故障后仍成功（具体故障由 run.mjs 设置 fake provider key 场景）。
 */
export function failover() {
  const current = ensureContext();
  const chat = sendChat(current.session, current.conversationId, `failover-${failureMode}`);
  const result = readFirstToken(current.session, chat.runId);
  const success = chat.status === 200 && result.ok;
  degradedSuccess.add(success, { mode: failureMode });
  if (result.ok) {
    firstTokenMs.add(result.firstToken, { scenario: `failover-${failureMode}` });
  }
  check(success, {
    [`降级后成功（${failureMode}）`]: (value) => value,
  });
}

/**
 * 配额争用场景：限额=已用+1，50 并发提交恰好 1 成功、49 个 429。
 *
 * setup 先把开关置 true 并设置限额；teardown 恢复开关与限额，避免污染后续场景。
 *
 * @returns {object} setup 数据
 */
export function quotaContention() {
  const platform = login(platformTenant, platformUser, platformPassword);
  const admin = login(tenant, adminUser, adminPassword);
  const memberSession = login(tenant, member, memberPassword);

  // Cookie 隔离自检：三个角色必须是不同会话，且身份/角色与登录账号一致。
  if (new Set([platform.cookie, admin.cookie, memberSession.cookie]).size !== 3) {
    fail('platform/admin/member sessions share the same cookie; cookie jar isolation is broken');
  }
  assertSession(platform, 'platform', platformTenant, platformUser, 'PLATFORM_ADMIN');
  assertSession(admin, 'tenant-admin', tenant, adminUser, 'TENANT_ADMIN');
  assertSession(memberSession, 'member', tenant, member, 'MEMBER');

  const switches = api(
    platform,
    'GET',
    '/api/v1/admin/model-gateway/switches',
    undefined,
    {},
    false,
  );
  if (switches.status !== 200) {
    fail(`read switches failed: ${switches.status}`);
  }
  const switchState = switches.json();
  const originalSwitch = switchState.quotaEnforcementEnabled;

  let enabledState = null;
  let originalLimits = null;
  try {
    const enabled = api(
      platform,
      'PUT',
      '/api/v1/admin/model-gateway/switches',
      {
        version: switchState.version,
        routingMode: switchState.routingMode,
        failoverEnabled: switchState.failoverEnabled,
        quotaEnforcementEnabled: true,
      },
      {},
      false,
    );
    if (enabled.status !== 200) {
      fail(`enable quota failed: ${enabled.status} ${enabled.body}`);
    }
    enabledState = enabled.json();

    const quotas = api(
      platform,
      'GET',
      '/api/v1/admin/quotas?page=1&size=100',
      undefined,
      {},
      false,
    );
    if (quotas.status !== 200) {
      fail(`read quotas failed: ${quotas.status}`);
    }
    const row = quotas
      .json()
      .items.find((item) => item.tenantId === tenant || item.tenantCode === tenant);
    if (!row) {
      fail(`tenant ${tenant} quota row missing`);
    }
    const daily = row.windows.find((window) => window.window === 'DAILY');
    const monthly = row.windows.find((window) => window.window === 'MONTHLY');

    // 前序 first_token/failover 场景即使关闭执行也会累计用量；用户日窗口可能已超过
    // seed 限额（1000）。两级都设置“剩余=1”，否则用户窗口会先拒绝全部 50 请求，
    // 无法证明租户级零超卖。
    const userQuotas = api(
      admin,
      'GET',
      '/api/v1/quotas/users?page=1&size=100',
      undefined,
      {},
      false,
    );
    if (userQuotas.status !== 200) {
      fail(`read user quotas failed: ${userQuotas.status}`);
    }
    const userRow = (userQuotas.json().items || []).find((item) => item.username === member);
    if (!userRow) {
      fail(`user ${member} quota row missing`);
    }
    const userDaily = (userRow.windows || []).find((window) => window.window === 'DAILY');
    const userMonthly = (userRow.windows || []).find((window) => window.window === 'MONTHLY');

    originalLimits = {
      userId: userRow.userId,
      tenant: { daily: daily.limit, monthly: monthly.limit },
      user: { daily: userDaily.limit, monthly: userMonthly.limit },
    };

    const limitUpdate = api(
      platform,
      'PUT',
      `/api/v1/admin/tenants/${tenant}/quotas`,
      { limits: { daily: daily.used + 1, monthly: monthly.limit } },
      {},
      false,
    );
    if (limitUpdate.status !== 200) {
      fail(`set quota failed: ${limitUpdate.status} ${limitUpdate.body}`);
    }
    const userLimitUpdate = api(
      platform,
      'PUT',
      `/api/v1/admin/tenants/${tenant}/users/${userRow.userId}/quotas`,
      { limits: { daily: userDaily.used + 1, monthly: userMonthly.limit } },
      {},
      false,
    );
    if (userLimitUpdate.status !== 200) {
      fail(`set user quota failed: ${userLimitUpdate.status} ${userLimitUpdate.body}`);
    }

    const workspace = resolveWorkspace(memberSession);
    const conversation = api(
      memberSession,
      'POST',
      `/api/v1/workspaces/${workspace}/conversations`,
      { title: 'PERF-M04 配额争用会话' },
      {},
      false,
    );
    if (conversation.status !== 201) {
      fail(`create quota conversation failed: ${conversation.status}`);
    }

    // 50 个不同幂等键的并发提交；只有 1 个能通过原子准入。
    const requests = [];
    for (let index = 0; index < 50; index += 1) {
      const suffix = `${__VU}-${__ITER}-${index}-${Date.now()}`;
      requests.push({
        method: 'POST',
        url: `${baseUrl}/api/v1/conversations/${conversation.json().id}/messages`,
        body: JSON.stringify({
          content: 'PERF-M04 配额争用请求',
          clientMessageId: `quota-${suffix}`,
        }),
        params: {
          headers: {
            cookie: memberSession.cookie,
            'content-type': 'application/json',
            'x-csrf-token': memberSession.csrf,
            'idempotency-key': `quota-${suffix}`,
          },
          tags: { kind: 'api', op: 'quota-submit' },
          timeout: '30s',
        },
      });
    }
    clearCookieJar();
    const responses = http.batch(requests);
    let allowed = 0;
    let rejected = 0;
    let other = 0;
    const rejectionCodes = new Set();
    for (const response of responses) {
      if (response.status === 200) {
        allowed += 1;
      } else if (response.status === 429) {
        rejected += 1;
        try {
          rejectionCodes.add(response.json().code || 'MISSING_CODE');
        } catch (error) {
          rejectionCodes.add('UNPARSEABLE');
        }
      } else {
        other += 1;
      }
    }
    quotaAllowed.add(allowed);
    quotaRejected.add(rejected);
    const rejectionCodeList = [...rejectionCodes].sort().join(',');
    check(
      { allowed, rejected, other, rejectionCodeList },
      {
        '恰好 1 个成功': (value) => value.allowed === 1,
        '恰好 49 个 429': (value) => value.rejected === 49,
        '没有 5xx/其他状态': (value) => value.other === 0,
        '拒绝码均为 RICCON-QUOTA-429': (value) => value.rejectionCodeList === 'RICCON-QUOTA-429',
      },
    );
  } finally {
    let cleanupOk = true;
    // 恢复执行开关与两级限额原值；任何恢复失败都让 m04_quota_cleanup 失败。
    if (enabledState) {
      const restoredSwitches = api(
        platform,
        'PUT',
        '/api/v1/admin/model-gateway/switches',
        {
          version: enabledState.version,
          routingMode: enabledState.routingMode,
          failoverEnabled: enabledState.failoverEnabled,
          quotaEnforcementEnabled: originalSwitch,
        },
        {},
        false,
      );
      cleanupOk = cleanupOk && restoredSwitches.status === 200;
      if (restoredSwitches.status !== 200) {
        console.error(
          `quota cleanup switch failed: status=${restoredSwitches.status} body=${restoredSwitches.body}`,
        );
      }
    }
    if (originalLimits) {
      const restoreTenant = api(
        platform,
        'PUT',
        `/api/v1/admin/tenants/${tenant}/quotas`,
        { limits: originalLimits.tenant },
        {},
        false,
      );
      const restoreUser = api(
        platform,
        'PUT',
        `/api/v1/admin/tenants/${tenant}/users/${originalLimits.userId}/quotas`,
        { limits: originalLimits.user },
        {},
        false,
      );
      cleanupOk = cleanupOk && restoreTenant.status === 200 && restoreUser.status === 200;
      if (restoreTenant.status !== 200 || restoreUser.status !== 200) {
        console.error(
          `quota cleanup limits failed: tenant=${restoreTenant.status} user=${restoreUser.status}`,
        );
      }
    }
    // m04_quota_cleanup 由自定义 Rate 记录；阈值在 buildThresholds 中断言为 1。
    addQuotaCleanup(cleanupOk);
  }
}

/** 配额清理结果指标；单独定义避免在 options 之前引用。 */
const quotaCleanup = new Rate('m04_quota_cleanup');

/**
 * 记录配额清理结果。
 *
 * @param {boolean} ok 是否成功
 */
function addQuotaCleanup(ok) {
  quotaCleanup.add(ok);
}

/**
 * 成本查询场景：30/180 天、租户与平台聚合 p95。
 */
export function costQuery() {
  const admin = login(tenant, adminUser, adminPassword);
  const today = new Date();
  const format = (date) => date.toISOString().slice(0, 10);
  const from = (days) => format(new Date(today.getTime() - days * 24 * 60 * 60 * 1000));
  const queries = [
    `/api/v1/costs?from=${from(29)}&to=${format(today)}&groupBy=DAY`,
    `/api/v1/costs?from=${from(179)}&to=${format(today)}&groupBy=MODEL`,
    '/api/v1/costs/invocations?page=1&size=20',
  ];
  for (const path of queries) {
    const startedAt = Date.now();
    const response = api(admin, 'GET', path, undefined, {
      tags: { kind: 'api', op: 'cost-report' },
    });
    costQueryMs.add(Date.now() - startedAt, { op: 'cost-report' });
    const ok = response.status === 200;
    costQueryOk.add(ok, { op: 'cost-report' });
    if (!ok) {
      console.error(
        `cost query failed ${path}: ${response.status}; B7 cost API must be implemented`,
      );
    }
  }
  // 控制查询速率，避免 15s 内产生数万次成本查询把测试栈压垮。
  sleep(1);
}

/**
 * k6 汇总：输出 JSON 报告，附带环境快照与场景元数据。
 *
 * @param {object} data k6 summary data
 * @returns {object} 文件名 → 内容
 */
export function handleSummary(data) {
  const reportDir = __ENV.PERF_M04_REPORT_DIR || '.';
  const suffix = scenario === 'failover' ? `-${failureMode}` : '';
  const file =
    __ENV.PERF_M05_REPORT_FILE ||
    __ENV.PERF_M04_REPORT_FILE ||
    `${reportDir}/mvc-smoke-${scenario}${suffix}.json`;
  const metrics = {};
  for (const [name, metric] of Object.entries(data.metrics)) {
    if (
      name.startsWith('m04_') ||
      name.startsWith('m05_') ||
      name === 'http_req_duration' ||
      name === 'http_req_failed' ||
      name === 'iterations'
    ) {
      metrics[name] = metric.values;
    }
  }
  const report = {
    scenario,
    failureMode: scenario === 'failover' ? failureMode : null,
    generatedAt: new Date().toISOString(),
    baseUrl,
    env: envSnapshot,
    thresholds: buildThresholds(),
    metrics,
  };
  if (scenario === 'workflow') {
    return {
      // J1 首字使用 run 详情的 modelInvocation.firstTokenMs，不是 SSE TTFB。
      m06_j1_first_token_ms: ['p(95)<3000'],
      m06_j1_first_token_ok: ['rate>0.99'],
      // Studio 列表/详情/保存/发布/运行准入/审批查询/历史查询；不含 SSE TTFB。
      m06_api_ms: ['p(95)<500'],
      m06_run_admission_ms: ['p(95)<500'],
      // 已接受的运行必须全部成功完成；429 单独计数，不伪装成失败或通过。
      m06_run_ok: ['rate>0.99'],
      m06_run_completed_ok: ['rate>0.99'],
      m06_j2_ok: ['rate>0.99'],
    };
  }
  if (scenario === 'knowledge') {
    report.m05 = {
      query: m05Query,
      documentId: m05DocumentId,
      aclDocumentId: m05AclDocumentId,
      hiddenDocumentId: m05HiddenDocumentId,
      downloadDocumentId: m05DownloadDocumentId,
      aclUserCount: m05AclUserIds.length,
      concurrency: {
        knowledgeVus: 50,
        aclWriteVus: m05AclWriteEnabled ? 1 : 0,
      },
      duration: __ENV.PERF_M05_DURATION || '60s',
      // M05b 连接器同步结果由 run.mjs 在 k6 结束后写回；这里只记录本次负载对应的
      // 连接器元数据，便于单看 k6 报告也能区分“普通 M05 段”和“M05b 整条复验”。
      connector: m05Connector
        ? {
            ...m05Connector,
            note: '同步/索引/队列等待由 scripts/real-stack/run.mjs 在 k6 前量测并写回同目录报告',
          }
        : null,
      note: '检索 p95 由 scripts/real-stack/run.mjs 从 MySQL slow_log 汇总后写回报告；k6 只负责并发与首字/API/ACL/下载指标',
    };
  }
  return {
    [file]: `${JSON.stringify(report, null, 2)}\n`,
    stdout: `[perf-${scenario === 'knowledge' ? 'm05' : 'm04'}] scenario=${scenario}${suffix} report=${file}\n`,
  };
}
