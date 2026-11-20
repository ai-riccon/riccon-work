#!/usr/bin/env node
// 真实栈安全与审计复核：跨租户拒绝、审计覆盖、append-only、密钥不落日志、契约端点缺口。
//
// 退出码只在“必须成立”的断言失败时非零；契约声明的端点缺失记录为 gaps，由评审定级。

import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { ApiClient } from "./lib/http.mjs";
import { binaryUuid, queryValue, runSql, uuidHex } from "./lib/mysql.mjs";

const root = process.cwd();
const seedPath =
  process.env.REAL_SEED_OUT ??
  path.join(root, ".riccon/evidence/TASK-0024/real-stack-seed.json");
const evidenceDir = path.dirname(seedPath);
const seed = JSON.parse(readFileSync(seedPath, "utf8"));
const journey = JSON.parse(
  readFileSync(path.join(evidenceDir, "real-stack-j2.json"), "utf8"),
);
const masterKey = process.env.RICCON_MASTER_KEY;
/** 假模型 key 只来自 env；seed 输出不含明文。 */
const modelKeys = process.env.REAL_MODEL_KEYS
  ? Object.values(JSON.parse(process.env.REAL_MODEL_KEYS))
      .flat()
      .map((entry) => entry.key)
  : [];

/** 记录观察结果，不直接决定退出码。 */
const gaps = [];
const observations = {};

// ---------- 跨租户 ----------
const alpha = new ApiClient(seed.apiBase);
await alpha.login(
  seed.tenants.alpha,
  "alpha-member",
  "alpha-member-password-12",
);
const beta = new ApiClient(seed.apiBase);
await beta.login(seed.tenants.beta, "beta-member", "beta-member-password-12");

const alphaConversation = await alpha.post(
  `/api/v1/workspaces/${seed.workspaces.alpha.id}/conversations`,
  {
    title: "跨租户验证会话",
  },
);
assert.equal(alphaConversation.status, 201);
const crossChecks = [
  ["conversation", `/api/v1/conversations/${alphaConversation.data.id}`],
  ["task", `/api/v1/research-tasks/${journey.approvedTaskId}`],
  ["artifact", `/api/v1/artifacts/${journey.approvedArtifactId}`],
  [
    "artifactVersion",
    `/api/v1/artifacts/${journey.approvedArtifactId}/versions/1`,
  ],
  [
    "artifactDownload",
    `/api/v1/artifacts/${journey.approvedArtifactId}/versions/1/download-url`,
  ],
  [
    "artifactContent",
    `/api/v1/artifacts/${journey.approvedArtifactId}/versions/1/content`,
  ],
  [
    "knowledgeDocument",
    `/api/v1/knowledge/documents/${seed.knowledge.documentId}`,
  ],
  ["file", `/api/v1/files/${seed.knowledge.sourceFileId}`],
];
for (const [name, url] of crossChecks) {
  const response = await beta.get(url);
  assert.equal(
    response.status,
    404,
    `cross-tenant ${name} must be 404, got ${response.status} ${response.text}`,
  );
  assert.ok(
    !response.text.includes("2025 中国企业协同办公"),
    `cross-tenant ${name} leaked content`,
  );
}
// ---------- 同源产物正文端点：响应头、正文与不暴露对象存储 ----------
const artifactContent = await alpha.get(
  `/api/v1/artifacts/${journey.approvedArtifactId}/versions/1/content`,
);
assert.equal(
  artifactContent.status,
  200,
  `artifact content must be served same-origin: ${artifactContent.status} ${artifactContent.text}`,
);
const contentBytes = Buffer.byteLength(artifactContent.text, "utf8");
assert.match(
  artifactContent.headers.get("content-type") ?? "",
  /^text\/markdown/,
  "artifact content must be text/markdown",
);
assert.equal(
  Number(artifactContent.headers.get("content-length")),
  contentBytes,
  "Content-Length must match body bytes",
);
assert.equal(
  artifactContent.headers.get("cache-control"),
  "no-store",
  "artifact content must not be cached",
);
assert.equal(
  artifactContent.headers.get("x-content-type-options"),
  "nosniff",
  "artifact content must disable MIME sniffing",
);
for (const marker of ["# ", "## 摘要", "## 引用清单", "MCP 工具"]) {
  assert.ok(
    artifactContent.text.includes(marker),
    `artifact content must contain ${marker}`,
  );
}
assert.ok(
  !artifactContent.text.includes("tenants/"),
  "artifact content must not expose storage keys",
);
observations.artifactContent = {
  bytes: contentBytes,
  contentType: artifactContent.headers.get("content-type"),
  cacheControl: artifactContent.headers.get("cache-control"),
};

const betaApprovals = await beta.get("/api/v1/approvals?page=1&size=100");
assert.equal(betaApprovals.status, 200);
assert.equal(
  betaApprovals.data.total,
  0,
  "cross-tenant approval list must be empty",
);
const betaCreateInAlphaWorkspace = await beta.post(
  `/api/v1/workspaces/${seed.workspaces.alpha.id}/research-tasks`,
  { topic: "跨租户工作空间创建测试", mcpToolId: seed.catalog.mcpToolId },
  { "Idempotency-Key": `cross-ws-${Date.now()}` },
);
assert.equal(
  betaCreateInAlphaWorkspace.status,
  404,
  "cross-tenant workspace create must be 404",
);
const betaUseAlphaTool = await beta.post(
  `/api/v1/workspaces/${seed.workspaces.beta.id}/research-tasks`,
  { topic: "跨租户工具授权测试", mcpToolId: seed.catalog.mcpToolId },
  { "Idempotency-Key": `cross-tool-${Date.now()}` },
);
observations.crossTenantToolCreate = `${betaUseAlphaTool.status} ${betaUseAlphaTool.data?.code ?? ""}`;

// 在 beta 自己的租户上传并删除一个文件，补齐 file.deleted 审计。
const betaUpload = await beta.upload(seed.workspaces.beta.id, {
  filename: "beta-audit.docx",
  mimeType:
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  content: readFileSync(path.join(root, "e2e/real/fixtures/market.docx")),
});
assert.equal(betaUpload.status, 201);
const betaDelete = await beta.delete(`/api/v1/files/${betaUpload.data.id}`);
assert.equal(betaDelete.status, 204);

// ---------- 审计覆盖 ----------
const requiredActions = [
  "auth.login.succeeded",
  "user.created",
  "tenant.created",
  "file.uploaded",
  "file.deleted",
  "session.created",
  "message.sent",
  "model.invocation.started",
  "model.invocation.completed",
  "mcp.tool.invoked",
  "approval.decided",
  "research.task.created",
  "artifact.saved",
];
const actionRows = runSql(`
SELECT action, COUNT(*) AS total FROM audit_event GROUP BY action ORDER BY action;
`).stdout;
const auditCounts = Object.fromEntries(
  actionRows
    .trim()
    .split("\n")
    .slice(1)
    .map((line) => {
      const [action, total] = line.split("\t");
      return [action, Number(total)];
    }),
);
for (const action of requiredActions) {
  assert.ok((auditCounts[action] ?? 0) > 0, `audit action missing: ${action}`);
}

// append-only：数据库触发器必须拒绝 UPDATE/DELETE。
const updateAttempt = runSql(
  "UPDATE audit_event SET outcome='FAILURE' WHERE action='artifact.saved';",
  { allowFailure: true },
);
const deleteAttempt = runSql(
  "DELETE FROM audit_event WHERE action='artifact.saved';",
  { allowFailure: true },
);
assert.notEqual(
  updateAttempt.status,
  0,
  "audit_event UPDATE must be rejected by trigger",
);
assert.notEqual(
  deleteAttempt.status,
  0,
  "audit_event DELETE must be rejected by trigger",
);
assert.match(updateAttempt.stderr ?? "", /append-only|1644/);
assert.match(deleteAttempt.stderr ?? "", /append-only|1644/);

// 审计租户字段必须与实际租户一致，不能出现空租户。
const invalidTenant = runSql(
  "SELECT COUNT(*) AS total FROM audit_event WHERE tenant_id = '' OR tenant_id IS NULL;",
)
  .stdout.trim()
  .split("\n")
  .pop();
assert.equal(invalidTenant, "0", "audit rows must carry tenant id");

// ---------- 密钥不落日志/明文入库 ----------
const logFiles = [
  process.env.REAL_API_LOG,
  process.env.REAL_WORKER_LOG,
  process.env.REAL_MODEL_LOG,
].filter(Boolean);
const leakedLogs = [];
for (const logFile of logFiles) {
  const text = readFileSync(logFile, "utf8");
  for (const apiKey of modelKeys) {
    if (text.includes(apiKey)) {
      leakedLogs.push(`${logFile}:model-key`);
    }
  }
  if (masterKey && text.includes(masterKey)) {
    leakedLogs.push(`${logFile}:master-key`);
  }
}
assert.deepEqual(
  leakedLogs,
  [],
  `secrets leaked into logs: ${leakedLogs.join(",")}`,
);
const credentialPlaintext = runSql(
  modelKeys.length === 0
    ? "SELECT 0 AS total;"
    : modelKeys
        .map(
          (apiKey) =>
            `SELECT COUNT(*) FROM provider_credential WHERE ciphertext LIKE '%${apiKey.replaceAll("'", "''")}%';`,
        )
        .join("\n"),
)
  .stdout.trim()
  .split("\n")
  .pop();
assert.equal(
  credentialPlaintext,
  "0",
  "provider credential must not store plaintext",
);

// ---------- 已实现契约端点：MCP 工具目录与运行详情 ----------
const alphaTools = await alpha.get("/api/v1/mcp-tools?page=1&size=100");
assert.equal(
  alphaTools.status,
  200,
  `mcp-tools must be implemented: ${alphaTools.status} ${alphaTools.text}`,
);
const grantedTool = (alphaTools.data?.items ?? []).find(
  (item) => item.id === seed.catalog.mcpToolId,
);
assert.ok(grantedTool, "mcp-tools must include the tenant-granted tool");
assert.equal(grantedTool.name, "market_data");
assert.equal(grantedTool.riskLevel, "READ");
assert.equal(grantedTool.requiresApproval, true);
assert.equal(grantedTool.status, "ACTIVE");
const betaTools = await beta.get("/api/v1/mcp-tools?page=1&size=100");
assert.equal(betaTools.status, 200);
assert.equal(betaTools.data?.total, 0, "beta must not see alpha tool grants");

/**
 * 把 MySQL HEX(BINARY(16)) 转成带连字符的 UUID。
 *
 * @param {string} hex 32 位十六进制
 * @returns {string} UUID
 */
function hexToUuid(hex) {
  const value = hex.trim().toLowerCase();
  assert.match(value, /^[0-9a-f]{32}$/);
  return `${value.slice(0, 8)}-${value.slice(8, 12)}-${value.slice(12, 16)}-${value.slice(16, 20)}-${value.slice(20)}`;
}

const j1RunHex = queryValue(`
SELECT HEX(id) FROM agent_run
 WHERE tenant_id = '${seed.tenants.alpha}' AND kind = 'CHAT'
 ORDER BY created_at DESC, id DESC LIMIT 1;
`);
const j1RunId = hexToUuid(j1RunHex);
const j1Run = await alpha.get(`/api/v1/runs/${j1RunId}`);
assert.equal(
  j1Run.status,
  200,
  `J1 run detail must be implemented: ${j1Run.status} ${j1Run.text}`,
);
assert.equal(j1Run.data?.kind, "CHAT");
assert.ok(
  (j1Run.data?.modelInvocations ?? []).length >= 1,
  "J1 run detail must expose model invocations",
);
const j1ChatInvocation = j1Run.data.modelInvocations.find(
  (invocation) => invocation.purpose === "CHAT",
);
assert.ok(j1ChatInvocation, "J1 run must have a CHAT model invocation");
assert.equal(j1ChatInvocation.status, "SUCCEEDED");
assert.ok(
  Number.isInteger(j1ChatInvocation.firstTokenMs) &&
    j1ChatInvocation.firstTokenMs < 3000,
  `J1 firstTokenMs ${j1ChatInvocation.firstTokenMs} must be < 3000`,
);
assert.ok(
  j1ChatInvocation.durationMs >= j1ChatInvocation.firstTokenMs,
  "J1 duration must not be shorter than first token",
);
const dbFirstToken = Number(
  queryValue(`
SELECT first_token_ms FROM model_invocation
 WHERE tenant_id = '${seed.tenants.alpha}' AND purpose = 'CHAT'
 ORDER BY started_at DESC LIMIT 1;
`),
);
assert.ok(
  Number.isInteger(dbFirstToken) && dbFirstToken >= 0 && dbFirstToken < 3000,
  `DB first_token_ms ${dbFirstToken} must be < 3000`,
);

const j2Run = await alpha.get(`/api/v1/runs/${journey.approvedRunId}`);
assert.equal(
  j2Run.status,
  200,
  `J2 run detail must be implemented: ${j2Run.status} ${j2Run.text}`,
);
assert.equal(j2Run.data?.kind, "RESEARCH_REPORT");
assert.ok(
  (j2Run.data?.modelInvocations ?? []).length >= 1,
  "J2 run detail must expose model invocations",
);

const betaJ1Run = await beta.get(`/api/v1/runs/${j1RunId}`);
assert.equal(
  betaJ1Run.status,
  404,
  `cross-tenant J1 run must be 404, got ${betaJ1Run.status}`,
);
const betaJ2Run = await beta.get(`/api/v1/runs/${journey.approvedRunId}`);
assert.equal(
  betaJ2Run.status,
  404,
  `cross-tenant J2 run must be 404, got ${betaJ2Run.status}`,
);
observations.j1RunId = j1RunId;
observations.j1FirstTokenMs = j1ChatInvocation.firstTokenMs;
observations.j1InvocationDurationMs = j1ChatInvocation.durationMs;
observations.dbFirstTokenMs = dbFirstToken;
observations.crossTenantRuns = `${betaJ1Run.status}/${betaJ2Run.status}`;

// ---------- 同源正文 2 MiB 上限：数据库元数据已超限时直接 413，且不泄露对象 key ----------
const oversizedArtifactId = randomUUID();
const oversizedVersionId = randomUUID();
const oversizedStorageKey = `tenants/${seed.tenants.alpha}/artifacts/${oversizedArtifactId}/v1.md`;
const workspaceId = seed.workspaces.alpha.id;
const ownerUserId = seed.workspaces.alpha.ownerUserId;
runSql(`
INSERT INTO artifact (
  tenant_id, id, workspace_id, research_task_id, conversation_id, kind, title,
  status, current_version, created_by, created_at, updated_at
) VALUES (
  '${seed.tenants.alpha}', ${binaryUuid(oversizedArtifactId)}, ${binaryUuid(workspaceId)},
  NULL, NULL, 'REPORT', '超限正文验证产物', 'SAVED', 1, ${binaryUuid(ownerUserId)}, NOW(6), NOW(6)
);
INSERT INTO artifact_version (
  tenant_id, id, artifact_id, version, storage_key, content_type, size_bytes,
  sha256, summary, created_by, created_at
) VALUES (
  '${seed.tenants.alpha}', ${binaryUuid(oversizedVersionId)}, ${binaryUuid(oversizedArtifactId)},
  1, '${oversizedStorageKey}', 'text/markdown', 2097153,
  '0000000000000000000000000000000000000000000000000000000000000000',
  '超限正文', ${binaryUuid(ownerUserId)}, NOW(6)
);
`);
const oversizedContent = await alpha.get(
  `/api/v1/artifacts/${oversizedArtifactId}/versions/1/content`,
);
assert.equal(
  oversizedContent.status,
  413,
  `content over 2 MiB must be 413: ${oversizedContent.status} ${oversizedContent.text}`,
);
assert.equal(oversizedContent.data?.code, "RICCON-ARTIFACT-413");
assert.ok(
  !oversizedContent.text.includes(oversizedStorageKey),
  "413 response must not expose storage key",
);
observations.oversizedContent = {
  status: oversizedContent.status,
  code: oversizedContent.data?.code,
};
runSql(`
DELETE FROM artifact_version
 WHERE tenant_id = '${seed.tenants.alpha}' AND id = ${binaryUuid(oversizedVersionId)};
DELETE FROM artifact
 WHERE tenant_id = '${seed.tenants.alpha}' AND id = ${binaryUuid(oversizedArtifactId)};
`);

// ---------- 契约声明的端点缺口 ----------
const contractEndpoints = [
  "/api/v1/models",
  "/api/v1/admin/model-providers",
  "/api/v1/admin/models",
  "/api/v1/admin/mcp-servers",
  "/api/v1/admin/mcp-tools",
];
for (const endpoint of contractEndpoints) {
  const response = await alpha.get(endpoint);
  if (response.status === 404) {
    gaps.push({
      endpoint,
      status: response.status,
      code: response.data?.code ?? null,
    });
  }
}

// MCP 客户端补偿控制核对：server 为 STDIO 白名单且工具 READ + 必须审批。
const mcpPolicy = runSql(`
SELECT s.transport, s.code, t.risk_level, t.requires_approval
  FROM mcp_server s JOIN mcp_tool t ON t.server_id = s.id
 WHERE s.code = 'riccon-builtin';
`).stdout.trim();
assert.match(mcpPolicy, /STDIO\triccon-builtin\tREAD\t1/);

// =============================================================================
// M04：模型目录/路由/key/配额/成本 + 假 provider 场景 + API/Worker 指纹
// =============================================================================
const platform = new ApiClient(seed.apiBase);
await platform.login(
  "platform",
  seed.users.platformAdmin,
  seed.passwords?.platformAdmin ?? "test-bootstrap-password-12",
);
const tenantAdmin = new ApiClient(seed.apiBase);
await tenantAdmin.login(
  seed.tenants.alpha,
  "alpha-admin",
  "alpha-admin-password-12",
);

/** M04 观察结果；不打印 key/主密钥。 */
const m04 = {
  fakeScenarios: {},
  catalog: {},
  routing: {},
  credentials: {},
  switches: {},
  quota: {},
  cost: {},
  fingerprint: {},
};

/**
 * 直接调用假 provider，验证场景确定性；不经过 Worker。
 *
 * @param {string} scenario 场景
 * @param {object} [options] signal/body
 * @returns {Promise<Response>} 响应
 */
async function fakeModelCall(scenario, options = {}) {
  return fetch(
    `${process.env.REAL_MODEL_CONTROL ?? "http://127.0.0.1:18990"}/v1/chat/completions`,
    {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-fake-model-scenario": scenario,
      },
      body: JSON.stringify(
        options.body ?? {
          model: "fake-chat-primary",
          stream: true,
          messages: [{ role: "user", content: "M04 真实栈确定性检查" }],
        },
      ),
      redirect: "manual",
      signal: options.signal,
    },
  );
}

// ---------- 假 provider：302/401/429/5xx/超时/断流/缺 usage ----------
for (const [scenario, expectedStatus] of [
  ["redirect", 302],
  ["unauthorized", 401],
  ["rate_limited", 429],
  ["server_error", 500],
  ["unavailable", 503],
]) {
  const response = await fakeModelCall(scenario);
  assert.equal(
    response.status,
    expectedStatus,
    `fake provider ${scenario} must be ${expectedStatus}, got ${response.status}`,
  );
  const text = await response.text();
  if (expectedStatus !== 302) {
    const payload = JSON.parse(text);
    assert.equal(
      payload.error.code,
      `fake_${scenario}`,
      `fake provider ${scenario} must expose fixed error code`,
    );
  }
  m04.fakeScenarios[scenario] = expectedStatus;
}
const okResponse = await fakeModelCall("ok");
assert.equal(okResponse.status, 200);
const okBody = await okResponse.text();
assert.ok(
  okBody.includes('"prompt_tokens":128'),
  "fake provider ok must expose fixed usage",
);
assert.ok(okBody.includes("[DONE]"), "fake provider ok must terminate SSE");
const missingUsageBody = await (await fakeModelCall("missing_usage")).text();
assert.ok(
  !missingUsageBody.includes('"usage"'),
  "missing_usage must omit usage",
);
assert.ok(
  missingUsageBody.includes("[DONE]"),
  "missing_usage must still terminate SSE",
);
let streamBreakBody = "";
try {
  streamBreakBody = await (await fakeModelCall("stream_break")).text();
} catch {
  // 上游断流是预期行为。
}
assert.ok(
  !streamBreakBody.includes("[DONE]"),
  "stream_break must not send DONE",
);
const timeoutController = new AbortController();
setTimeout(() => timeoutController.abort(), 300);
let timeoutAborted = false;
try {
  await fakeModelCall("timeout", { signal: timeoutController.signal });
} catch (error) {
  timeoutAborted =
    error?.name === "AbortError" || String(error).includes("aborted");
}
assert.ok(
  timeoutAborted,
  "fake provider timeout must not respond before client timeout",
);
m04.fakeScenarios.timeout = "client-abort";
m04.fakeScenarios.stream_break = "interrupted";
m04.fakeScenarios.missing_usage = "no-usage";
const fakeLog = await (
  await fetch(
    `${process.env.REAL_MODEL_CONTROL ?? "http://127.0.0.1:18990"}/__log__`,
  )
).json();
assert.ok(
  fakeLog.requests.every((entry) => !Object.hasOwn(entry, "authorization")),
  "fake provider log must not store Authorization",
);

// ---------- 目录：providers/models 与员工目录脱敏 ----------
const providers = await platform.get(
  "/api/v1/admin/model-providers?page=1&size=100",
);
assert.equal(
  providers.status,
  200,
  `admin model-providers must be 200: ${providers.status}`,
);
assert.ok(
  providers.data.items.length >= 2,
  "at least two fake providers must be seeded",
);
const primaryProvider = providers.data.items.find(
  (item) => item.code === "fake-primary",
);
const secondaryProvider = providers.data.items.find(
  (item) => item.code === "fake-secondary",
);
assert.ok(
  primaryProvider && secondaryProvider,
  "fake-primary/fake-secondary must be seeded",
);
for (const provider of [primaryProvider, secondaryProvider]) {
  assert.equal(
    provider.allowed,
    true,
    `${provider.code} must be allowed by deployment config`,
  );
  assert.equal(
    provider.environmentLabel,
    "DEMO_ONLY",
    `${provider.code} must be DEMO_ONLY`,
  );
  assert.ok(
    provider.activeCredentialCount >= 1,
    `${provider.code} must have an active key`,
  );
  assert.ok(provider.modelCount >= 1, `${provider.code} must have a model`);
}
const immutableBaseUrl = await platform.request(
  "PUT",
  `/api/v1/admin/model-providers/${primaryProvider.id}`,
  { body: { baseUrl: "https://changed.example.test/v1" } },
);
assert.equal(immutableBaseUrl.status, 400, "baseUrl must be immutable");
assert.equal(immutableBaseUrl.data?.code, "RICCON-MODEL-400");
assert.equal(
  immutableBaseUrl.data?.reason,
  "ENDPOINT_IMMUTABLE_DEPLOYMENT_MANAGED",
);

const adminModels = await platform.get("/api/v1/admin/models?page=1&size=100");
assert.equal(
  adminModels.status,
  200,
  `admin models must be 200: ${adminModels.status}`,
);
const primaryModel = adminModels.data.items.find(
  (item) => item.modelKey === "fake-chat-primary",
);
const secondaryModel = adminModels.data.items.find(
  (item) => item.modelKey === "fake-chat-secondary",
);
const disabledModel = adminModels.data.items.find(
  (item) => item.modelKey === "fake-chat-disabled",
);
assert.ok(
  primaryModel && secondaryModel && disabledModel,
  "seeded models must be listed",
);
assert.equal(primaryModel.pricingStatus, "PRICED");
assert.equal(primaryModel.inputPricePer1M, "1.500000");
assert.equal(primaryModel.outputPricePer1M, "3.000000");
assert.equal(primaryModel.priceCurrency, "CNY");
assert.equal(disabledModel.status, "DISABLED");
const employeeModels = await alpha.get("/api/v1/models?page=1&size=100");
assert.equal(employeeModels.status, 200, "employee model catalog must be 200");
for (const model of employeeModels.data.items) {
  assert.ok(
    !Object.hasOwn(model, "inputPricePer1M"),
    "employee catalog must not expose price",
  );
  assert.ok(
    !Object.hasOwn(model, "providerCode"),
    "employee catalog must not expose provider",
  );
  assert.ok(
    !Object.hasOwn(model, "baseUrl"),
    "employee catalog must not expose baseUrl",
  );
}
m04.catalog = {
  providers: providers.data.items.map((item) => ({
    code: item.code,
    allowed: item.allowed,
    environmentLabel: item.environmentLabel,
  })),
  models: adminModels.data.items.map((item) => ({
    modelKey: item.modelKey,
    pricingStatus: item.pricingStatus,
    status: item.status,
  })),
  employeeCatalogLeaks: 0,
};

// ---------- 路由：读/乐观锁/空链/未知模型/保存与恢复 ----------
const routing = await platform.get("/api/v1/admin/model-routing");
assert.equal(
  routing.status,
  200,
  `model-routing must be 200: ${routing.status}`,
);
assert.equal(
  routing.data.items.length,
  2,
  "routing must return CHAT and REPORT chains",
);
const chatChain = routing.data.items.find((item) => item.purpose === "CHAT");
const reportChain = routing.data.items.find(
  (item) => item.purpose === "REPORT",
);
assert.ok(chatChain && reportChain, "CHAT/REPORT chains must exist");
assert.ok(
  chatChain.entries.length >= 2,
  "CHAT chain must contain seeded entries",
);
assert.ok(
  chatChain.entries.some((entry) => entry.status === "DISABLED"),
  "disabled model must stay visible in the chain",
);
assert.equal(chatChain.entries[0].environmentLabel, "DEMO_ONLY");
const stale = await platform.request(
  "PUT",
  "/api/v1/admin/model-routing/CHAT",
  {
    body: {
      version: chatChain.version + 1,
      modelIds: chatChain.entries.map((entry) => entry.modelId),
    },
  },
);
assert.equal(stale.status, 409, "stale routing version must be 409");
assert.equal(stale.data?.code, "RICCON-MODEL-409");
const emptyChain = await platform.request(
  "PUT",
  "/api/v1/admin/model-routing/CHAT",
  {
    body: { version: chatChain.version, modelIds: [] },
  },
);
assert.equal(emptyChain.status, 400, "empty chain must be 400");
assert.equal(emptyChain.data?.code, "RICCON-MODEL-400");
const unknownModel = await platform.request(
  "PUT",
  "/api/v1/admin/model-routing/CHAT",
  {
    body: {
      version: chatChain.version,
      modelIds: ["ffffffff-ffff-4fff-8fff-ffffffffffff"],
    },
  },
);
assert.equal(unknownModel.status, 400, "unknown model must be 400");
const originalOrder = chatChain.entries.map((entry) => entry.modelId);
const reordered = [...originalOrder].reverse();
const saved = await platform.request(
  "PUT",
  "/api/v1/admin/model-routing/CHAT",
  {
    body: { version: chatChain.version, modelIds: reordered },
  },
);
assert.equal(
  saved.status,
  200,
  `valid routing save must be 200: ${saved.status} ${saved.text}`,
);
assert.deepEqual(
  saved.data.entries.map((entry) => entry.modelId),
  reordered,
  "saved chain must preserve requested order",
);
const reverted = await platform.request(
  "PUT",
  "/api/v1/admin/model-routing/CHAT",
  {
    body: { version: saved.data.version, modelIds: originalOrder },
  },
);
assert.equal(reverted.status, 200, "routing restore must succeed");
assert.deepEqual(
  reverted.data.entries.map((entry) => entry.modelId),
  originalOrder,
  "routing restore must preserve original order",
);
const routingAudit = Number(
  queryValue(
    "SELECT COUNT(*) FROM audit_event WHERE action = 'model.routing.updated';",
  ),
);
assert.ok(
  routingAudit >= 2,
  `model.routing.updated audit must be recorded, got ${routingAudit}`,
);
m04.routing = {
  chatEntries: originalOrder.length,
  reportEntries: reportChain.entries.length,
  version: reverted.data.version,
  auditEvents: routingAudit,
};

// ---------- key 池：掩码、幂等停用/恢复、fingerprint 去重 ----------
const credentials = await platform.get(
  `/api/v1/admin/model-providers/${secondaryProvider.id}/credentials?page=1&size=100`,
);
assert.equal(credentials.status, 200, "credential list must be 200");
assert.ok(
  credentials.data.items.length >= 1,
  "secondary provider must have a seeded key",
);
const credential = credentials.data.items[0];
assert.match(
  credential.maskedHint,
  /^\*{4}.{4}$/,
  "maskedHint must only expose last 4 chars",
);
assert.ok(
  !Object.hasOwn(credential, "apiKey"),
  "credential response must not expose apiKey",
);
assert.ok(
  !Object.hasOwn(credential, "ciphertext"),
  "credential response must not expose ciphertext",
);
const disableOnce = await platform.post(
  `/api/v1/admin/model-providers/${secondaryProvider.id}/credentials/${credential.id}/disable`,
);
assert.equal(disableOnce.status, 200);
assert.equal(
  disableOnce.data.changed,
  true,
  "first disable must report changed=true",
);
const disableTwice = await platform.post(
  `/api/v1/admin/model-providers/${secondaryProvider.id}/credentials/${credential.id}/disable`,
);
assert.equal(disableTwice.status, 200);
assert.equal(
  disableTwice.data.changed,
  false,
  "second disable must be idempotent",
);
const enabledAgain = await platform.post(
  `/api/v1/admin/model-providers/${secondaryProvider.id}/credentials/${credential.id}/enable`,
);
assert.equal(enabledAgain.status, 200);
assert.equal(enabledAgain.data.status, "ACTIVE");
const providerKeys = process.env.REAL_MODEL_KEYS
  ? JSON.parse(process.env.REAL_MODEL_KEYS)
  : {};
const primaryKey = providerKeys["fake-primary"]?.[0]?.key;
if (primaryKey) {
  const duplicate = await platform.post(
    `/api/v1/admin/model-providers/${primaryProvider.id}/credentials`,
    { label: "重复 key", apiKey: primaryKey },
  );
  assert.equal(duplicate.status, 409, "duplicate fingerprint must be 409");
  assert.equal(duplicate.data?.reason, "DUPLICATE_CREDENTIAL");
  assert.ok(!duplicate.text.includes(primaryKey), "409 must not echo the key");
}
const credentialPlaintextRows = Number(
  queryValue(
    modelKeys.length === 0
      ? "SELECT 0;"
      : `SELECT COUNT(*) FROM provider_credential WHERE ${modelKeys
          .map((key) => `ciphertext LIKE '%${key.replaceAll("'", "''")}%'`)
          .join(" OR ")};`,
  ),
);
assert.equal(
  credentialPlaintextRows,
  0,
  "credentials must be encrypted at rest",
);
m04.credentials = {
  maskedHint: credential.maskedHint,
  idempotentDisable: true,
  duplicateRejected: Boolean(primaryKey),
};

// ---------- 开关：读写与恢复 ----------
const switches = await platform.get("/api/v1/admin/model-gateway/switches");
assert.equal(switches.status, 200, "switches must be 200");
assert.equal(
  switches.data.source,
  "DATABASE",
  "run.mjs must not override switches via env",
);
const switchSave = await platform.request(
  "PUT",
  "/api/v1/admin/model-gateway/switches",
  {
    body: {
      version: switches.data.version,
      routingMode: switches.data.routingMode,
      failoverEnabled: switches.data.failoverEnabled,
      quotaEnforcementEnabled: switches.data.quotaEnforcementEnabled,
    },
  },
);
assert.equal(
  switchSave.status,
  200,
  `switch save must be 200: ${switchSave.status} ${switchSave.text}`,
);
assert.equal(
  switchSave.data.version,
  switches.data.version + 1,
  "switch version must increment",
);
const switchRestore = await platform.request(
  "PUT",
  "/api/v1/admin/model-gateway/switches",
  {
    body: {
      version: switchSave.data.version,
      routingMode: switches.data.routingMode,
      failoverEnabled: switches.data.failoverEnabled,
      quotaEnforcementEnabled: false,
    },
  },
);
assert.equal(switchRestore.status, 200, "switch restore must succeed");
m04.switches = {
  routingMode: switchRestore.data.routingMode,
  failoverEnabled: switchRestore.data.failoverEnabled,
  quotaEnforcementEnabled: switchRestore.data.quotaEnforcementEnabled,
};

// ---------- 配额：租户/平台边界、限额更新与恢复 ----------
const tenantQuota = await tenantAdmin.get("/api/v1/quotas");
assert.equal(
  tenantQuota.status,
  200,
  `tenant quota must be 200: ${tenantQuota.status}`,
);
assert.equal(
  tenantQuota.data.windows.length,
  2,
  "tenant quota must expose daily+monthly",
);
for (const window of tenantQuota.data.windows) {
  assert.match(
    window.resetsAt,
    /\+08:00$/,
    "resetsAt must carry +08:00 offset",
  );
}
const memberQuota = await alpha.get("/api/v1/quotas");
assert.equal(memberQuota.status, 403, "employee must not read tenant quota");
const userQuotas = await tenantAdmin.get(
  "/api/v1/quotas/users?page=1&size=100",
);
assert.equal(userQuotas.status, 200, "tenant user quota list must be 200");
assert.ok(
  userQuotas.data.items.length >= 1,
  "tenant user quotas must include seeded users",
);
const platformQuotas = await platform.get(
  "/api/v1/admin/quotas?page=1&size=100",
);
assert.equal(platformQuotas.status, 200, "platform quota list must be 200");
for (const row of platformQuotas.data.items) {
  assert.ok(
    !Object.hasOwn(row, "userId"),
    "platform quota row must not expose userId",
  );
  assert.ok(
    !Object.hasOwn(row, "username"),
    "platform quota row must not expose username",
  );
  assert.ok(
    !Object.hasOwn(row, "userDisplayName"),
    "platform quota row must not expose user name",
  );
}
const alphaRow = platformQuotas.data.items.find(
  (row) =>
    row.tenantId === seed.tenants.alpha ||
    row.tenantCode === seed.tenants.alpha,
);
assert.ok(alphaRow, "platform quota list must include tenant-alpha");
const dailyState = alphaRow.windows.find((window) => window.window === "DAILY");
const monthlyState = alphaRow.windows.find(
  (window) => window.window === "MONTHLY",
);
const quotaUpdate = await platform.request(
  "PUT",
  `/api/v1/admin/tenants/${seed.tenants.alpha}/quotas`,
  {
    body: {
      limits: { daily: dailyState.used + 1, monthly: monthlyState.limit },
    },
  },
);
assert.equal(
  quotaUpdate.status,
  200,
  `tenant quota update must be 200: ${quotaUpdate.status} ${quotaUpdate.text}`,
);
assert.equal(quotaUpdate.data.windows[0].limit, dailyState.used + 1);
const quotaRestore = await platform.request(
  "PUT",
  `/api/v1/admin/tenants/${seed.tenants.alpha}/quotas`,
  {
    body: { limits: { daily: dailyState.limit, monthly: monthlyState.limit } },
  },
);
assert.equal(quotaRestore.status, 200, "tenant quota restore must succeed");
const crossTenantUser = await platform.request(
  "PUT",
  `/api/v1/admin/tenants/${seed.tenants.beta}/users/${seed.userIds.alphaMember}/quotas`,
  { body: { limits: { daily: 1, monthly: 1 } } },
);
assert.equal(
  crossTenantUser.status,
  404,
  "cross-tenant user quota write must be 404",
);
const invalidQuota = await platform.request(
  "PUT",
  `/api/v1/admin/tenants/${seed.tenants.alpha}/quotas`,
  { body: { limits: { daily: -1, monthly: 1 } } },
);
assert.equal(invalidQuota.status, 400, "negative quota must be 400");
m04.quota = {
  tenantWindows: tenantQuota.data.windows.length,
  platformRows: platformQuotas.data.items.length,
  employeeRejected: memberQuota.status,
};

// ---------- 成本：B7 未实现时记录 gap；实现后断言形状与平台无用户字段 ----------
const tenantCost = await tenantAdmin.get("/api/v1/costs?groupBy=DAY");
if (tenantCost.status === 404) {
  gaps.push({
    endpoint: "/api/v1/costs",
    status: 404,
    reason: "B7 cost API not implemented yet",
  });
  m04.cost = { status: "not-implemented" };
} else {
  assert.equal(
    tenantCost.status,
    200,
    `tenant cost must be 200: ${tenantCost.status} ${tenantCost.text}`,
  );
  assert.equal(
    tenantCost.data.disclaimer,
    "成本为按调用时价格快照估算，不是供应商账单。",
  );
  assert.ok(
    Array.isArray(tenantCost.data.items),
    "tenant cost items must be an array",
  );
  const platformCost = await platform.get("/api/v1/admin/costs?groupBy=TENANT");
  assert.equal(platformCost.status, 200, "platform cost must be 200");
  for (const item of platformCost.data.items) {
    assert.ok(
      !Object.hasOwn(item, "userId"),
      "platform cost bucket must not expose userId",
    );
  }
  const memberCost = await alpha.get("/api/v1/costs");
  assert.equal(memberCost.status, 403, "employee must not read cost reports");
  m04.cost = { status: "passed", currency: tenantCost.data.currency };
}

// ---------- API/Worker /actuator/info 指纹一致 ----------
// 默认拒绝安全链未放行 /actuator/info 时记录为 gap（B10 preflight 需要可访问方式），
// 但绝不把两个 401 当成“指纹一致”。同一份 env 注入由 run.mjs 的 sharedModelEnv 保证，
// 运行期模型调用成功进一步证明 Worker 的解密/端点配置可用。
if (process.env.REAL_WORKER_BASE) {
  const apiResponse = await fetch(`${seed.apiBase}/actuator/info`);
  const workerResponse = await fetch(
    `${process.env.REAL_WORKER_BASE}/actuator/info`,
  );
  if (apiResponse.status === 200 && workerResponse.status === 200) {
    const apiInfo = await apiResponse.json();
    const workerInfo = await workerResponse.json();
    const select = (info) => ({
      deploymentMode: info?.riccon?.model?.deploymentMode,
      endpointSetHash: info?.riccon?.model?.endpointSetHash,
      routingMode: info?.riccon?.model?.routingMode,
      failoverEnabled: info?.riccon?.model?.failoverEnabled,
      quotaEnforcementEnabled: info?.riccon?.model?.quotaEnforcementEnabled,
      masterKeyId: info?.riccon?.masterKeyId,
      currency: info?.riccon?.billing?.currency,
      tenantDaily: info?.riccon?.quota?.tenantDaily,
      tenantMonthly: info?.riccon?.quota?.tenantMonthly,
      userDaily: info?.riccon?.quota?.userDaily,
      userMonthly: info?.riccon?.quota?.userMonthly,
    });
    const api = select(apiInfo);
    const worker = select(workerInfo);
    assert.equal(
      api.deploymentMode,
      "DEMO",
      "API deployment mode must be DEMO",
    );
    assert.equal(api.currency, "CNY", "billing currency must be CNY");
    assert.ok(api.endpointSetHash, "endpointSetHash must be injected");
    for (const key of Object.keys(api)) {
      assert.equal(
        String(api[key]),
        String(worker[key]),
        `API/Worker info.${key} must match`,
      );
    }
    m04.fingerprint = {
      matched: true,
      deploymentMode: api.deploymentMode,
      currency: api.currency,
    };
  } else {
    gaps.push({
      endpoint: "/actuator/info",
      status: `${apiResponse.status}/${workerResponse.status}`,
      reason:
        "default-deny security chain blocks /actuator/info; B10 preflight needs an allowed read path",
    });
    m04.fingerprint = {
      matched: false,
      visibility: "default-deny",
      apiStatus: apiResponse.status,
      workerStatus: workerResponse.status,
    };
  }
} else {
  gaps.push({
    endpoint: "/actuator/info",
    status: null,
    reason: "REAL_WORKER_BASE not provided",
  });
}

// ---------- 数据库结构：路由/开关/凭据与审计 ----------
const m04Db = runSql(`
SELECT 'routing_chat' AS item, COUNT(*) AS total FROM model_routing_entry WHERE purpose='CHAT'
UNION ALL SELECT 'routing_report', COUNT(*) FROM model_routing_entry WHERE purpose='REPORT'
UNION ALL SELECT 'switches', COUNT(*) FROM model_gateway_switch
UNION ALL SELECT 'platform_credentials', COUNT(*) FROM provider_credential WHERE tenant_id='platform'
UNION ALL SELECT 'routing_audit', COUNT(*) FROM audit_event WHERE action='model.routing.updated';
`).stdout;
assert.match(m04Db, /routing_chat\t3/);
assert.match(m04Db, /routing_report\t2/);
assert.match(m04Db, /switches\t3/);
m04.database = m04Db.trim();

// ---------- 目录中文展示名：种子必须经 utf8mb4 写入，否则路由页/模型页读到双写乱码 ----------
const catalogModels = seed.catalog?.models ?? [];
assert.ok(catalogModels.length > 0, "seed catalog must include models");
for (const model of catalogModels) {
  assert.match(
    model.displayName ?? "",
    /[\u4e00-\u9fff]/,
    `seed catalog model ${model.modelKey} must carry a Chinese displayName`,
  );
}
const catalogTextRows = runSql(`
SELECT 'model' AS kind, LOWER(HEX(id)) AS id_hex, display_name AS text_value FROM model
UNION ALL SELECT 'provider', LOWER(HEX(id)), name FROM model_provider
UNION ALL SELECT 'credential', LOWER(HEX(id)), label FROM provider_credential
 WHERE tenant_id = 'platform';
`).stdout;
const storedText = new Map();
for (const line of catalogTextRows.split("\n")) {
  if (line.trim() === "") {
    continue;
  }
  const [kind, idHex, ...text] = line.split("\t");
  storedText.set(`${kind}:${idHex}`, text.join("\t"));
}
const textMismatches = [];
/**
 * 比对一条目录文本；预期值来自 seed 输出的常量，不是数据库回读。
 *
 * @param {string} kind model|provider|credential
 * @param {string} id UUID
 * @param {string} expected 预期中文
 */
const expectCatalogText = (kind, id, expected) => {
  const stored = storedText.get(`${kind}:${uuidHex(id)}`);
  if (stored !== expected) {
    textMismatches.push({ kind, id, expected, actual: stored ?? null });
  }
};
for (const model of catalogModels) {
  expectCatalogText("model", model.id, model.displayName);
}
for (const provider of seed.catalog?.providers ?? []) {
  if (provider.name) {
    expectCatalogText("provider", provider.id, provider.name);
  }
}
for (const entries of Object.values(seed.catalog?.credentials ?? {})) {
  for (const entry of entries) {
    if (entry.label) {
      expectCatalogText("credential", entry.id, entry.label);
    }
  }
}
assert.deepEqual(
  textMismatches,
  [],
  `catalog display text must read back as the seeded Chinese, not mojibake: ${JSON.stringify(textMismatches)}`,
);
m04.textIntegrity = {
  status: "passed",
  models: catalogModels.length,
  providers: seed.catalog?.providers?.length ?? 0,
};
observations.m04 = m04;

const summary = {
  status: "passed",
  generatedAt: new Date().toISOString(),
  auditActions: auditCounts,
  mcpTools: {
    alphaTotal: alphaTools.data?.total ?? null,
    betaTotal: betaTools.data?.total ?? null,
    grantedToolId: grantedTool.id,
  },
  runs: {
    j1RunId,
    j2RunId: journey.approvedRunId,
    j1FirstTokenMs: j1ChatInvocation.firstTokenMs,
    j1DurationMs: j1ChatInvocation.durationMs,
    dbFirstTokenMs: dbFirstToken,
  },
  appendOnly: {
    updateExit: updateAttempt.status,
    deleteExit: deleteAttempt.status,
    updateError: (updateAttempt.stderr ?? "").trim().split("\n").slice(-1)[0],
    deleteError: (deleteAttempt.stderr ?? "").trim().split("\n").slice(-1)[0],
  },
  crossTenantChecks: crossChecks.map(([name, url]) => ({
    name,
    url,
    status: 404,
  })),
  observations,
  gaps,
};
writeFileSync(
  path.join(evidenceDir, "real-stack-checks.json"),
  `${JSON.stringify(summary, null, 2)}\n`,
);
console.log(JSON.stringify(summary, null, 2));
