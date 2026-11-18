#!/usr/bin/env node
// 真实栈安全与审计复核：跨租户拒绝、审计覆盖、append-only、密钥不落日志、契约端点缺口。
//
// 退出码只在“必须成立”的断言失败时非零；契约声明的端点缺失记录为 gaps，由评审定级。

import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { ApiClient } from "./lib/http.mjs";
import { binaryUuid, queryValue, runSql } from "./lib/mysql.mjs";

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
const fakeApiKey = seed.fakeApiKey ?? "fake-model-api-key-real-stack";

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
  if (text.includes(fakeApiKey)) {
    leakedLogs.push(logFile);
  }
  if (masterKey && text.includes(masterKey)) {
    leakedLogs.push(logFile);
  }
}
assert.deepEqual(
  leakedLogs,
  [],
  `secrets leaked into logs: ${leakedLogs.join(",")}`,
);
const credentialPlaintext = runSql(
  `SELECT COUNT(*) FROM provider_credential WHERE ciphertext LIKE '%${fakeApiKey}%';`,
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
