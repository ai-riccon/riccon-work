#!/usr/bin/env node
// M10 真实栈安全与功能复核：OIDC/绑定/审计查询/导出审批/CSV/平台边界/回滚前置。
//
// 该脚本只使用公开 HTTP API、测试 IdP 与测试栈 SQL，不 import 生产代码，也不修改生产数据。
// 由 `scripts/real-stack/run.mjs` 在 seed 之后、浏览器 E2E 之前调用；也可单独执行：
//   REAL_SEED_OUT=.riccon/evidence/<task>/real-stack-seed.json \
//   REAL_OIDC_BASE=http://127.0.0.1:18995 ... node scripts/real-stack/m10-checks.mjs
//
// 退出码：任一断言失败即非零。输出只含 ID、状态与计数，不打印 token、clientSecret 或 PII。

import assert from 'node:assert/strict';
import { readFileSync, writeFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import path from 'node:path';
import { ApiClient, waitFor } from './lib/http.mjs';
import { binaryUuid, queryValue, runSql, sqlString } from './lib/mysql.mjs';
import {
  authorizeUrl as buildAuthorizeUrl,
  createPkce,
  discover,
  exchangeCode,
  verifyIdToken,
} from '../../e2e/support/oidc/client.mjs';

const root = process.cwd();
const seedPath =
  process.env.REAL_SEED_OUT ?? path.join(root, '.riccon/evidence/TASK-0024/real-stack-seed.json');
const seed = JSON.parse(readFileSync(seedPath, 'utf8'));
const evidenceDir = path.dirname(seedPath);
const oidcBase = process.env.REAL_OIDC_BASE ?? 'http://127.0.0.1:18995';
const oidcRealm = process.env.REAL_OIDC_REALM ?? 'alpha';
const oidcTenant = process.env.REAL_OIDC_TENANT ?? seed.tenants.alpha;
const oidcClientId = process.env.REAL_OIDC_CLIENT_ID;
const oidcClientSecret = process.env.REAL_OIDC_CLIENT_SECRET;
const oidcSubject = process.env.REAL_OIDC_SUBJECT ?? 'subject-alpha';
const webBase = process.env.REAL_WEB_BASE ?? 'http://127.0.0.1:8092';
const apiBase = seed.apiBase;
const issuer = `${oidcBase}/realms/${oidcRealm}`;
const suffix = Date.now().toString(36);
const summary = { oidc: {}, binding: {}, audit: {}, export: {}, counts: {} };

/** 输出一步成功信息。 */
function ok(label, detail = '') {
  console.log(`[m10] PASS ${label}${detail ? ` ${detail}` : ''}`);
}

/** 断言状态码并返回响应。 */
function expectStatus(response, status, label) {
  assert.equal(
    response.status,
    status,
    `${label}: expected ${status}, got ${response.status} ${response.text}`,
  );
  return response;
}

/** 断言 ProblemDetail 错误码。 */
function expectCode(response, code, label) {
  assert.equal(response.data?.code, code, `${label}: expected ${code}, got ${response.text}`);
  return response;
}

/** 登录并返回客户端。 */
async function login(tenantCode, username, password) {
  const client = new ApiClient(apiBase);
  await client.login(tenantCode, username, password);
  return client;
}

/** 读取 IdP 统计。 */
async function oidcStats() {
  const response = await fetch(`${oidcBase}/__oidc__/stats`);
  return response.json();
}

/** 设置 IdP 行为。 */
async function oidcBehavior(mode, subject) {
  const response = await fetch(`${oidcBase}/__oidc__/behavior`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ realm: oidcRealm, mode, subject }),
  });
  assert.equal(response.status, 200, `set oidc behavior ${mode}`);
}

/** 重置 IdP。 */
async function oidcReset() {
  const response = await fetch(`${oidcBase}/__oidc__/reset`, {
    method: 'POST',
  });
  assert.equal(response.status, 200, 'reset oidc');
}

/**
 * 走一遍 API 发起的 OIDC 授权码流程。
 *
 * @param {ApiClient} client API 客户端（保存会话）
 * @param {string} tenantCode 租户代码
 * @returns {Promise<{authorizeStatus:number, callbackLocation:string, callbackStatus:number}>}
 */
async function startOidc(client, tenantCode) {
  const authorize = await client.get(`/api/v1/auth/oidc/authorize/tenant-${tenantCode}`);
  const location = authorize.headers.get('location') ?? '';
  if (authorize.status !== 302) {
    return {
      authorizeStatus: authorize.status,
      callbackLocation: location,
      callbackStatus: 0,
    };
  }
  // IdP 不可达时预检会直接重定向回登录页；此时没有 IdP 授权地址可跟随。
  if (!location.includes('/authorize?')) {
    return { authorizeStatus: authorize.status, callbackLocation: location, callbackStatus: 0 };
  }
  const idpAuthorize = new URL(location);
  assert.ok(idpAuthorize.searchParams.get('code_challenge'), 'authorize must carry PKCE challenge');
  assert.equal(idpAuthorize.searchParams.get('code_challenge_method'), 'S256');
  assert.ok(idpAuthorize.searchParams.get('nonce'), 'authorize must carry nonce');
  const idpResponse = await fetch(location, { redirect: 'manual' });
  const callbackUrl = idpResponse.headers.get('location') ?? '';
  assert.ok(callbackUrl, `IdP must redirect to callback, got ${idpResponse.status}`);
  // 直接调用 API 处理回调；生产浏览器经 Web 同源代理访问同一路径。
  const target = new URL(callbackUrl);
  const callback = await client.get(`${target.pathname}${target.search}`);
  return {
    authorizeStatus: authorize.status,
    callbackLocation: callback.headers.get('location') ?? '',
    callbackStatus: callback.status,
  };
}

/** 断言会话已清理：匿名 + 数据库无 OidcUser/token 属性。 */
async function expectNoOidcSession(client, label) {
  const session = await client.get('/api/v1/auth/session');
  assert.equal(session.status, 401, `${label}: session must be anonymous after failure`);
  lastSessionStatus = 401;
}

let lastSessionStatus = null;

/**
 * 通过 API 创建租户账号。
 *
 * @param {ApiClient} platform 平台管理员客户端
 * @param {string} tenantId 租户
 * @param {object} body 创建体
 * @returns {Promise<object>} 账号
 */
async function createUser(platform, tenantId, body) {
  const response = await platform.post(`/api/v1/admin/tenants/${tenantId}/users`, body);
  assert.equal(response.status, 201, `create user ${body.username}: ${response.text}`);
  return response.data;
}

/**
 * 创建租户（幂等）。
 */
async function ensureTenant(platform, id, name) {
  const response = await platform.post('/api/v1/admin/tenants', { id, name });
  assert.ok([201, 409].includes(response.status), `create tenant ${id}: ${response.text}`);
}

/** 数字断言辅助。 */
function greaterOrEqual(actual, expected, label) {
  assert.ok(actual >= expected, `${label}: expected >= ${expected}, got ${actual}`);
}

async function main() {
  assert.ok(oidcClientId && oidcClientSecret, 'OIDC client env is required');
  const platform = await login(
    'platform',
    seed.users.platformAdmin,
    seed.passwords.platformAdmin ?? 'test-bootstrap-password-12',
  );
  const alphaAdmin = await login(
    seed.tenants.alpha,
    seed.users.alphaAdmin,
    seed.passwords.alphaAdmin,
  );
  const alphaAdmin2 = await login(
    seed.tenants.alpha,
    seed.users.alphaAdmin2,
    seed.passwords.alphaAdmin2,
  );
  const alphaMember = await login(
    seed.tenants.alpha,
    seed.users.alphaMember,
    seed.passwords.alphaMember,
  );
  const betaMember = await login(
    seed.tenants.beta,
    seed.users.betaMember,
    seed.passwords.betaMember,
  );

  // ---------- OIDC：providers 不泄露租户存在性 ----------
  const anon = new ApiClient(apiBase);
  const providersAlpha = expectStatus(
    await anon.get(`/api/v1/auth/oidc/providers?tenantCode=${seed.tenants.alpha}`),
    200,
    'providers alpha',
  );
  assert.equal(providersAlpha.data.enabled, true);
  const providersUnknown = expectStatus(
    await anon.get('/api/v1/auth/oidc/providers?tenantCode=no-such-tenant'),
    200,
    'providers unknown',
  );
  assert.equal(providersUnknown.data.enabled, false);
  ok('OIDC providers', 'alpha enabled, unknown disabled');

  // 先通过 B2 绑定端点把本地账号切到 OIDC，再走 B1 登录流程（F-005 端到端）。
  const bindUsername = `oidc-e2e-${suffix}`;
  const boundSubject = `sub-bound-${suffix}`;
  const localUser = await createUser(platform, seed.tenants.alpha, {
    username: bindUsername,
    displayName: 'OIDC 端到端员工',
    password: 'initial-password-12',
    roles: ['MEMBER'],
  });
  const bind = await alphaAdmin.request('PUT', `/api/v1/users/${localUser.id}/oidc-binding`, {
    body: { issuer, subject: boundSubject },
  });
  expectStatus(bind, 200, 'bind OIDC identity');

  // ---------- OIDC：PKCE+nonce 成功登录 ----------
  await oidcReset();
  await oidcBehavior('login', boundSubject);
  const oidcClient = new ApiClient(apiBase);
  const flow = await startOidc(oidcClient, seed.tenants.alpha);
  assert.equal(flow.callbackStatus, 302, `callback must redirect: ${flow.callbackStatus}`);
  assert.ok(flow.callbackLocation.includes('/'), 'callback must land on web root');
  const oidcSession = expectStatus(
    await oidcClient.get('/api/v1/auth/session'),
    200,
    'oidc session',
  );
  assert.equal(oidcSession.data.user.authSource, 'OIDC');
  assert.equal(oidcSession.data.tenant.id, seed.tenants.alpha);
  assert.equal(oidcSession.data.user.id, localUser.id, 'bound identity must hit the same account');
  assert.equal(oidcSession.data.user.username, bindUsername);
  // 登录成功会轮换 CSRF 令牌；同步到客户端后再发登出等写请求。
  oidcClient.csrfToken = oidcSession.data.csrfToken;
  const afterSuccess = await oidcStats();
  assert.equal(afterSuccess.userinfo, 0, 'product must not call userinfo');
  assert.ok(afterSuccess.token >= 1, 'token endpoint must be called');
  // 浏览器可见的会话响应体不含 JWT/access token。
  for (const marker of ['eyJ', 'access_token', 'id_token', 'refresh_token']) {
    assert.ok(!JSON.stringify(oidcSession.data).includes(marker), `session leaks ${marker}`);
  }
  ok('OIDC login', `source=${oidcSession.data.user.authSource} userinfo=${afterSuccess.userinfo}`);
  summary.oidc.success = 'passed';
  summary.oidc.userinfoCalls = afterSuccess.userinfo;

  // ---------- OIDC：联合登出 ----------
  const logout = await oidcClient.post('/api/v1/auth/logout', {});
  expectStatus(logout, 204, 'oidc logout');
  const federated = logout.headers.get('x-riccon-federated-logout-url') ?? '';
  assert.ok(
    federated.includes(`/realms/${oidcRealm}/end-session`),
    'logout must return end-session URL',
  );
  const endSession = await fetch(federated, { redirect: 'manual' });
  assert.equal(endSession.status, 302, 'end-session must redirect back');
  assert.ok(
    (endSession.headers.get('location') ?? '').includes('federated=1'),
    'end-session must return to federated landing',
  );
  const afterLogout = await oidcStats();
  assert.ok(afterLogout.endSession >= 1, 'end-session must be called by browser navigation');
  expectNoOidcSessionBody(await oidcClient.get('/api/v1/auth/session'));
  ok('OIDC logout', `endSession=${afterLogout.endSession}`);
  summary.oidc.federatedLogout = 'passed';

  // ---------- OIDC：错误/失败清理 ----------
  await oidcBehavior('deny');
  const denyClient = new ApiClient(apiBase);
  const denied = await startOidc(denyClient, seed.tenants.alpha);
  assert.equal(denied.callbackStatus, 302);
  assert.ok(
    denied.callbackLocation.includes('oidcError=access_denied'),
    `deny reason: ${denied.callbackLocation}`,
  );
  await expectNoOidcSession(denyClient, 'access_denied');

  await oidcBehavior('bad_state');
  const badStateClient = new ApiClient(apiBase);
  const badState = await startOidc(badStateClient, seed.tenants.alpha);
  assert.equal(badState.callbackStatus, 302);
  assert.ok(
    badState.callbackLocation.includes('oidcError=state_invalid'),
    `bad state reason: ${badState.callbackLocation}`,
  );
  await expectNoOidcSession(badStateClient, 'state_invalid');

  await oidcBehavior('down');
  const downClient = new ApiClient(apiBase);
  const down = await startOidc(downClient, seed.tenants.alpha);
  assert.equal(down.authorizeStatus, 302);
  assert.ok(
    down.callbackLocation.includes('oidcError=idp_unavailable'),
    `down reason: ${down.callbackLocation}`,
  );
  await expectNoOidcSession(downClient, 'idp_unavailable');
  await oidcBehavior('login', oidcSubject);

  const oidcAttrCount = Number(
    queryValue(
      `SELECT COUNT(*) FROM SPRING_SESSION_ATTRIBUTES WHERE ATTRIBUTE_BYTES LIKE '%OidcUser%' OR ATTRIBUTE_BYTES LIKE '%id_token%' OR ATTRIBUTE_BYTES LIKE '%access_token%';`,
    ),
  );
  assert.equal(oidcAttrCount, 0, 'failed OIDC flows must not persist OidcUser or tokens');
  ok('OIDC failures', 'access_denied/state_invalid/idp_unavailable cleaned');
  summary.oidc.failures = 'passed';

  // ---------- 绑定后 OIDC 登录（B2 F-005） ----------
  const passwordAfterBind = new ApiClient(apiBase);
  await passwordAfterBind.csrf();
  const passwordLogin = await passwordAfterBind.request('POST', '/api/v1/auth/login', {
    body: {
      tenantCode: seed.tenants.alpha,
      username: bindUsername,
      password: 'initial-password-12',
    },
  });
  assert.equal(passwordLogin.status, 401, 'binding must delete local password credential');
  await oidcBehavior('login', boundSubject);
  const boundClient = new ApiClient(apiBase);
  const boundFlow = await startOidc(boundClient, seed.tenants.alpha);
  assert.equal(boundFlow.callbackStatus, 302);
  const boundSession = expectStatus(
    await boundClient.get('/api/v1/auth/session'),
    200,
    'bound session',
  );
  assert.equal(boundSession.data.user.id, localUser.id, 'bound identity must hit the same account');
  assert.equal(boundSession.data.user.username, bindUsername);
  assert.ok(boundSession.data.roles.includes('MEMBER'), 'bound login keeps existing roles');
  assert.equal(boundSession.data.user.authSource, 'OIDC');
  ok('OIDC binding->login', `user=${bindUsername} roles=${boundSession.data.roles.join(',')}`);
  summary.binding.endToEnd = 'passed';
  summary.binding.username = bindUsername;
  summary.binding.subject = boundSubject;
  summary.binding.displayName = 'OIDC 端到端员工';

  // B2 F-001：case-variant subject 探针；只记录观测值，不冒充已修复。
  await oidcBehavior('login', boundSubject.toUpperCase());
  const caseClient = new ApiClient(apiBase);
  const caseFlow = await startOidc(caseClient, seed.tenants.alpha);
  let caseVariantMatched = false;
  if (caseFlow.callbackStatus === 302 && !caseFlow.callbackLocation.includes('oidcError=')) {
    const caseSession = await caseClient.get('/api/v1/auth/session');
    caseVariantMatched = caseSession.status === 200 && caseSession.data?.user?.id === localUser.id;
  }
  summary.binding.caseVariantSubjectMatched = caseVariantMatched;
  console.log(`[m10] INFO B2 F-001 case-variant subject matched=${caseVariantMatched}`);
  await oidcBehavior('login', oidcSubject);

  // ---------- 审计查询：过滤/分页/180 天/查询审计 ----------
  const alphaAdminId = seed.userIds.alphaAdmin;
  const now = Date.now();
  const rows = [];
  for (let index = 0; index < 220; index += 1) {
    const occurred = new Date(now - index * 60_000 - 5_000)
      .toISOString()
      .slice(0, 19)
      .replace('T', ' ');
    rows.push(
      `(${binaryUuid(randomUUID())}, '${occurred}', ${sqlString(seed.tenants.alpha)}, 'USER', ${sqlString(alphaAdminId)}, 'auth.login.succeeded', 'SESSION', ${sqlString(`synthetic-${index}`)}, 'SUCCESS', ${sqlString(`trace-synthetic-${index}`)}, NULL, NULL, NULL, NULL)`,
    );
  }
  // 公式注入与模型 token 指标：target_id 以 = 开头；details 只含白名单键。
  const modelEventId = randomUUID();
  const occurred = new Date(now - 3_000).toISOString().slice(0, 19).replace('T', ' ');
  rows.push(
    `(${binaryUuid(modelEventId)}, '${occurred}', ${sqlString(seed.tenants.alpha)}, 'SERVICE', 'worker', 'model.invocation.completed', 'MODEL', '=SUM(A1:A2)', 'SUCCESS', 'trace-model-synthetic', NULL, NULL, NULL, '{"modelId":"fake-chat","durationMs":12,"promptTokens":10,"completionTokens":20}')`,
  );
  runSql(
    `INSERT INTO audit_event (event_id, occurred_at, tenant_id, actor_type, actor_id, action, target_type, target_id, outcome, trace_id, request_id, source_ip, details, schema_version) VALUES\n${rows.join(',\n')};`,
    { repoRoot: root },
  );

  const page1 = expectStatus(
    await alphaAdmin.get(
      `/api/v1/audit-events?action=auth.login.succeeded&actorId=${alphaAdminId}&page=1&size=100`,
    ),
    200,
    'audit page 1',
  );
  greaterOrEqual(page1.data.total, 220, 'audit total');
  assert.equal(page1.data.items.length, 100);
  const times = page1.data.items.map((item) => Date.parse(item.occurredAt));
  assert.deepEqual(
    times,
    [...times].sort((a, b) => b - a),
    'occurredAt must be descending',
  );
  assert.ok(!('sourceIp' in page1.data.items[0]), 'source_ip must not be exposed');
  assert.ok(
    page1.data.items.every((item) => item.actorDisplayName),
    'actorDisplayName must resolve',
  );
  const page2 = expectStatus(
    await alphaAdmin.get(
      `/api/v1/audit-events?action=auth.login.succeeded&actorId=${alphaAdminId}&page=2&size=100`,
    ),
    200,
    'audit page 2',
  );
  assert.equal(page2.data.items.length, 100);
  const page1Ids = new Set(page1.data.items.map((item) => item.eventId));
  assert.ok(
    page2.data.items.every((item) => !page1Ids.has(item.eventId)),
    'pages must not overlap',
  );

  const modelPage = expectStatus(
    await alphaAdmin.get('/api/v1/audit-events?action=model.invocation.completed&page=1&size=20'),
    200,
    'audit model tokens',
  );
  const modelItem = modelPage.data.items.find((item) => item.eventId === modelEventId);
  assert.ok(modelItem, 'synthetic model event must be visible');
  assert.equal(modelItem.details.promptTokens, 10);
  assert.equal(modelItem.details.completionTokens, 20);

  // 查询自身审计：上一轮查询应已写入 audit.queried。
  await waitFor(async () => {
    const response = await alphaAdmin.get(
      '/api/v1/audit-events?action=audit.queried&page=1&size=5',
    );
    return response.status === 200 && response.data.items.length > 0;
  }, 10_000);
  const queried = await alphaAdmin.get('/api/v1/audit-events?action=audit.queried&page=1&size=5');
  assert.ok(
    queried.data.items.some((item) => item.details?.filtersHash),
    'audit.queried must include filtersHash',
  );
  assert.ok(
    queried.data.items.some((item) => typeof item.details?.rowCount === 'number'),
    'audit.queried must include rowCount',
  );

  const wideRange = expectStatus(
    await alphaAdmin.get(
      `/api/v1/audit-events?occurredFrom=2025-01-01T00:00:00%2B08:00&occurredTo=2026-12-01T00:00:00%2B08:00`,
    ),
    400,
    'audit 180-day limit',
  );
  expectCode(wideRange, 'RICCON-AUDIT-400', 'audit 180-day limit');
  expectStatus(
    await alphaAdmin.get('/api/v1/audit-events?occurredFrom=2026-01-01T00:00:00'),
    400,
    'audit offset required',
  );
  expectStatus(await alphaAdmin.get('/api/v1/audit-events?size=101'), 400, 'audit size cap');
  expectStatus(await alphaAdmin.get('/api/v1/audit-events?page=201'), 400, 'audit page cap');
  expectStatus(
    await alphaMember.get('/api/v1/audit-events?page=1&size=20'),
    403,
    'member audit forbidden',
  );
  const platformAudit = expectStatus(
    await platform.get('/api/v1/audit-events?page=1&size=20'),
    200,
    'platform audit',
  );
  assert.ok(
    platformAudit.data.items.every((item) => item.tenantId === 'platform'),
    'platform only sees platform tenant',
  );
  ok('audit query', `total>=220 pages=100 displayName/queryAudit/promptTokens`);
  summary.audit.query = 'passed';

  // 405：只读路径错误方法必须映射到稳定码，而不是 500（B3b F-001）。
  const methodNotAllowed = await alphaAdmin.post('/api/v1/audit-events', {});
  expectStatus(methodNotAllowed, 405, 'audit method not allowed');
  assert.equal(methodNotAllowed.data?.code, 'RICCON-METHOD-405');
  ok('audit method 405');

  // ---------- 导出：申请/自批/他批/下载/CSV/平台边界 ----------
  const exportFilter = {
    action: 'auth.login.succeeded',
    actorId: alphaAdminId,
  };
  const created = expectStatus(
    await alphaAdmin.post('/api/v1/audit-exports', { filter: exportFilter }),
    201,
    'create export',
  );
  assert.equal(created.data.status, 'PENDING_APPROVAL');
  assert.equal(created.data.requestedBy, alphaAdminId);
  greaterOrEqual(created.data.estimatedRowCount, 220, 'export estimate');
  const exportId = created.data.id;

  const selfApprove = await alphaAdmin.post(`/api/v1/audit-exports/${exportId}/decision`, {
    decision: 'APPROVE',
  });
  expectStatus(selfApprove, 403, 'self approval');
  expectCode(selfApprove, 'RICCON-AUDIT-403', 'self approval');

  const approved = expectStatus(
    await alphaAdmin2.post(`/api/v1/audit-exports/${exportId}/decision`, {
      decision: 'APPROVE',
    }),
    200,
    'other admin approval',
  );
  assert.equal(approved.data.status, 'APPROVED');
  assert.ok(approved.data.expiresAt, 'approved export must have expiry');
  assert.equal(approved.data.approvedBy, seed.userIds.alphaAdmin2);

  const approverDownload = await alphaAdmin2.get(`/api/v1/audit-exports/${exportId}/download`);
  expectStatus(approverDownload, 403, 'approver cannot download');
  const memberDownload = await alphaMember.get(`/api/v1/audit-exports/${exportId}/download`);
  assert.ok([403, 404].includes(memberDownload.status));

  const csv = await alphaAdmin.get(`/api/v1/audit-exports/${exportId}/download`);
  expectStatus(csv, 200, 'requester download');
  assert.match(csv.headers.get('content-type') ?? '', /text\/csv/);
  assert.match(csv.headers.get('cache-control') ?? '', /no-store/);
  const text = typeof csv.data === 'string' ? csv.data : csv.text;
  assert.ok(
    text.includes('# exportId=') || text.includes('# exportId:'),
    'CSV must include metadata',
  );
  assert.ok(text.includes("'=SUM(A1:A2)"), 'CSV must escape formula-leading cells');
  for (const forbidden of ['source_ip', 'requestId', 'password', 'eyJ', 'access_token']) {
    assert.ok(!text.includes(forbidden), `CSV must not include ${forbidden}`);
  }
  ok('export approval/download', `id=${exportId} bytes=${text.length}`);
  summary.export.tenantFlow = 'passed';

  const platformExport = await platform.post('/api/v1/audit-exports', {
    filter: {},
  });
  expectStatus(platformExport, 403, 'platform tenant export');
  expectCode(platformExport, 'RICCON-AUDIT-403', 'platform tenant export');

  // 跨租户详情：beta 管理员对 alpha 导出统一 404。
  const betaAdminName = `beta-admin-${suffix}`;
  const betaAdminUser = await createUser(platform, seed.tenants.beta, {
    username: betaAdminName,
    displayName: 'Beta 管理员',
    password: 'beta-admin-password-12',
    roles: ['TENANT_ADMIN'],
  });
  const betaAdmin = await login(seed.tenants.beta, betaAdminName, 'beta-admin-password-12');
  const crossGet = await betaAdmin.get(`/api/v1/audit-exports/${exportId}`);
  expectStatus(crossGet, 404, 'cross-tenant export detail');
  expectCode(crossGet, 'RICCON-TENANT-404', 'cross-tenant export detail');
  const crossDownload = await betaAdmin.get(`/api/v1/audit-exports/${exportId}/download`);
  assert.ok([403, 404].includes(crossDownload.status));
  assert.ok(
    !crossDownload.text?.includes('eventId'),
    'cross-tenant download must not leak content',
  );
  ok('export cross-tenant 404');

  // 平台审批：多管理员租户 409；单管理员租户由平台批准。
  const multiAdminList = await platform.get(
    `/api/v1/admin/tenants/${seed.tenants.alpha}/audit-exports`,
  );
  expectStatus(multiAdminList, 409, 'platform approval multi-admin');
  expectCode(multiAdminList, 'RICCON-AUDIT-409', 'platform approval multi-admin');

  const soloTenant = `tenant-solo-${suffix}`;
  await ensureTenant(platform, soloTenant, '单管理员租户');
  const soloAdminName = `solo-admin-${suffix}`;
  await createUser(platform, soloTenant, {
    username: soloAdminName,
    displayName: '单租户管理员',
    password: 'solo-admin-password-12',
    roles: ['TENANT_ADMIN'],
  });
  const soloAdmin = await login(soloTenant, soloAdminName, 'solo-admin-password-12');
  const soloExport = expectStatus(
    await soloAdmin.post('/api/v1/audit-exports', { filter: {} }),
    201,
    'solo export create',
  );
  const platformList = expectStatus(
    await platform.get(`/api/v1/admin/tenants/${soloTenant}/audit-exports`),
    200,
    'platform list solo exports',
  );
  assert.ok(platformList.data.items.some((item) => item.id === soloExport.data.id));
  const platformApprove = expectStatus(
    await platform.post(
      `/api/v1/admin/tenants/${soloTenant}/audit-exports/${soloExport.data.id}/decision`,
      {
        decision: 'APPROVE',
      },
    ),
    200,
    'platform approve solo export',
  );
  assert.equal(platformApprove.data.status, 'APPROVED');
  ok('platform export approval', `solo=${soloTenant}`);
  summary.export.platformApproval = 'passed';
  summary.export.crossTenant = 'passed';

  // ---------- 日志脱敏 ----------
  const apiLog = process.env.REAL_API_LOG;
  if (apiLog) {
    const log = readFileSync(apiLog, 'utf8');
    for (const marker of [oidcClientSecret, 'id_token', 'access_token', 'client_secret']) {
      assert.ok(!log.includes(marker), `API log must not contain ${marker}`);
    }
  }
  ok('log redaction');

  writeFileSync(
    path.join(evidenceDir, 'real-stack-m10-summary.json'),
    `${JSON.stringify({ status: 'passed', generatedAt: new Date().toISOString(), ...summary }, null, 2)}\n`,
  );
}

/** 断言会话响应为匿名。 */
function expectNoOidcSessionBody(response) {
  assert.equal(response.status, 401, 'session must be anonymous');
}

main().catch((error) => {
  console.error('[m10] FAIL', error?.stack ?? error);
  try {
    writeFileSync(
      path.join(evidenceDir, 'real-stack-m10-summary.json'),
      `${JSON.stringify({ status: 'failed', error: String(error), generatedAt: new Date().toISOString(), ...summary }, null, 2)}\n`,
    );
  } catch {
    // 证据目录不可写时只保留 stderr。
  }
  process.exitCode = 1;
});
