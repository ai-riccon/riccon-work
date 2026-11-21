// M04 性能冒烟（PERF-M04-001/002）。
//
// 运行方式（推荐）：由 `scripts/real-stack/run.mjs --mode=perf` 编排，脚本会自动注入
// 环境快照、登录账号、工作空间与 50 并发 profile。也可以直接：
//
//   PERF_M04_ENV_JSON='{"chatMaxStreams":50,"dbPoolSize":25,"fakeModel":1}' \
//   PERF_M04_BASE_URL=http://127.0.0.1:18080 \
//   k6 run e2e/perf/mvc-smoke.js
//
// 场景（PERF_M04_SCENARIO）：
// - first_token：50 并发首字 p95 ≤ 3s；
// - failover：50 并发 + 首字前故障注入（PERF_M04_FAILURE_MODE）后仍 100% 成功；
// - quota：开关 true + 限额=已用+1，50 并发恰好 1 成功/49 个 429（零超卖）；
// - cost：30/180 天成本查询 p95 ≤ 2s（需要 B7 cost API）。
//
// 退出码：阈值失败或前置环境不满足时 k6 非零；这是 gate 信号，不做告警降级。
// 报告：写 `PERF_M04_REPORT_FILE` 或 `PERF_M04_REPORT_DIR/mvc-smoke-<scenario>.json`，
// 内含 host CPU/内存、镜像 tag、k6 版本、脚本 sha、启动命令与 mock 所在主机规格。

import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

/** 当前场景；由 run.mjs 或 CI 显式指定。 */
const scenario = (__ENV.PERF_M04_SCENARIO || 'first_token').trim();

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

/** 自定义指标；标签只保留场景/操作，不含租户或用户高基数标签。 */
const firstTokenMs = new Trend('m04_first_token_ms', true);
const chatStreamMs = new Trend('m04_chat_stream_ms', true);
const runDetailMs = new Trend('m04_run_detail_ms', true);
const apiMs = new Trend('m04_api_ms', true);
const degradedSuccess = new Rate('m04_degraded_success');
const quotaAllowed = new Counter('m04_quota_allowed');
const quotaRejected = new Counter('m04_quota_rejected');
const costQueryMs = new Trend('m04_cost_query_ms', true);
const costQueryOk = new Rate('m04_cost_query_ok');

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
  const response = api(current, 'GET', '/api/v1/auth/session', undefined, {
    tags: { kind: 'api', op: 'session' },
  });
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
 * @returns {object} k6 响应
 */
function api(current, method, path, body, params = {}) {
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
  if (kind === 'api') {
    apiMs.add(response.timings.duration, { op: paramTags?.op || 'api' });
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
  const response = api(current, 'GET', '/api/v1/workspaces?page=1&size=100');
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
    );
    if (response.status !== 201) {
      fail(`create conversation failed: ${response.status} ${response.text}`);
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
 * @returns {{status: number, runId: string|null, body: string, duration: number}} 结果
 */
function sendChat(current, conversation, tag) {
  const startedAt = Date.now();
  const response = api(
    current,
    'POST',
    `/api/v1/conversations/${conversation}/messages`,
    {
      content: 'PERF-M04 首字与降级冒烟，请返回固定演示内容。',
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
  chatStreamMs.add(duration, { scenario: tag });
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
 * @returns {{ok: boolean, firstToken: number|null, status: string|null}} 结果
 */
function readFirstToken(current, runId) {
  if (!runId) {
    return { ok: false, firstToken: null, status: null };
  }
  const startedAt = Date.now();
  const response = api(current, 'GET', `/api/v1/runs/${runId}`, undefined, {
    tags: { kind: 'api', op: 'run-detail' },
  });
  runDetailMs.add(Date.now() - startedAt, { op: 'run-detail' });
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

  const switches = api(platform, 'GET', '/api/v1/admin/model-gateway/switches');
  if (switches.status !== 200) {
    fail(`read switches failed: ${switches.status}`);
  }
  const switchState = switches.json();
  const originalSwitch = switchState.quotaEnforcementEnabled;

  let enabledState = null;
  let originalLimits = null;
  try {
    const enabled = api(platform, 'PUT', '/api/v1/admin/model-gateway/switches', {
      version: switchState.version,
      routingMode: switchState.routingMode,
      failoverEnabled: switchState.failoverEnabled,
      quotaEnforcementEnabled: true,
    });
    if (enabled.status !== 200) {
      fail(`enable quota failed: ${enabled.status} ${enabled.body}`);
    }
    enabledState = enabled.json();

    const quotas = api(platform, 'GET', '/api/v1/admin/quotas?page=1&size=100');
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
    const userQuotas = api(admin, 'GET', '/api/v1/quotas/users?page=1&size=100');
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

    const limitUpdate = api(platform, 'PUT', `/api/v1/admin/tenants/${tenant}/quotas`, {
      limits: { daily: daily.used + 1, monthly: monthly.limit },
    });
    if (limitUpdate.status !== 200) {
      fail(`set quota failed: ${limitUpdate.status} ${limitUpdate.body}`);
    }
    const userLimitUpdate = api(
      platform,
      'PUT',
      `/api/v1/admin/tenants/${tenant}/users/${userRow.userId}/quotas`,
      { limits: { daily: userDaily.used + 1, monthly: userMonthly.limit } },
    );
    if (userLimitUpdate.status !== 200) {
      fail(`set user quota failed: ${userLimitUpdate.status} ${userLimitUpdate.body}`);
    }

    const workspace = resolveWorkspace(memberSession);
    const conversation = api(memberSession, 'POST', `/api/v1/workspaces/${workspace}/conversations`, {
      title: 'PERF-M04 配额争用会话',
    });
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
        '拒绝码均为 RICCON-QUOTA-429': (value) =>
          value.rejectionCodeList === 'RICCON-QUOTA-429',
      },
    );
  } finally {
    let cleanupOk = true;
    // 恢复执行开关与两级限额原值；任何恢复失败都让 m04_quota_cleanup 失败。
    if (enabledState) {
      const restoredSwitches = api(platform, 'PUT', '/api/v1/admin/model-gateway/switches', {
        version: enabledState.version,
        routingMode: enabledState.routingMode,
        failoverEnabled: enabledState.failoverEnabled,
        quotaEnforcementEnabled: originalSwitch,
      });
      cleanupOk = cleanupOk && restoredSwitches.status === 200;
      if (restoredSwitches.status !== 200) {
        console.error(
          `quota cleanup switch failed: status=${restoredSwitches.status} body=${restoredSwitches.body}`,
        );
      }
    }
    if (originalLimits) {
      const restoreTenant = api(platform, 'PUT', `/api/v1/admin/tenants/${tenant}/quotas`, {
        limits: originalLimits.tenant,
      });
      const restoreUser = api(
        platform,
        'PUT',
        `/api/v1/admin/tenants/${tenant}/users/${originalLimits.userId}/quotas`,
        { limits: originalLimits.user },
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
  const file = __ENV.PERF_M04_REPORT_FILE || `${reportDir}/mvc-smoke-${scenario}${suffix}.json`;
  const metrics = {};
  for (const [name, metric] of Object.entries(data.metrics)) {
    if (
      name.startsWith('m04_') ||
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
  return {
    [file]: `${JSON.stringify(report, null, 2)}\n`,
    stdout: `[perf-m04] scenario=${scenario}${suffix} report=${file}\n`,
  };
}
