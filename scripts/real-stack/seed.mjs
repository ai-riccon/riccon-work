#!/usr/bin/env node
// 真实栈 seed：创建两个租户与账号，并写入模型/MCP 目录与加密凭据。
//
// 约束：
// - 租户与账号走公开 Admin API，证明 API 契约可完成开通；
// - 模型/MCP 目录与凭据 M02 没有公开写接口（平台只读），因此用测试栈 SQL 写入；
// - 凭据使用与 Worker 相同的 AES-256-GCM 信封格式（ADR-0006），主密钥只来自环境变量；
// - 脚本输出只含 ID 与状态，不打印主密钥、DEK 或假 API key。

import { randomBytes, randomUUID, createCipheriv } from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import { ApiClient } from "./lib/http.mjs";
import { binaryUuid, runSql, sqlString } from "./lib/mysql.mjs";

const apiBase = process.env.REAL_API_BASE ?? "http://127.0.0.1:18080";
const repoRoot = process.env.REAL_REPO_ROOT ?? process.cwd();
const outFile =
  process.env.REAL_SEED_OUT ??
  ".riccon/evidence/TASK-0024/real-stack-seed.json";
const masterKeyBase64 = process.env.RICCON_MASTER_KEY;
if (!masterKeyBase64) {
  throw new Error("RICCON_MASTER_KEY is required (Base64 32 bytes)");
}
const masterKey = Buffer.from(masterKeyBase64, "base64");
if (masterKey.length !== 32) {
  throw new Error("RICCON_MASTER_KEY must decode to 32 bytes");
}

const ADMIN = {
  tenant: "platform",
  username: "platform-admin",
  password: "test-bootstrap-password-12",
};

/** 生成 AES-256-GCM 密文；返回 nonce + ciphertext + tag。 */
function gcmSeal(key, nonce, plaintext, aad) {
  const cipher = createCipheriv("aes-256-gcm", key, nonce);
  cipher.setAAD(Buffer.from(aad, "utf8"));
  const encrypted = Buffer.concat([cipher.update(plaintext), cipher.final()]);
  return Buffer.concat([encrypted, cipher.getAuthTag()]);
}

/**
 * 生成租户数据密钥与凭据密文。
 *
 * @param {string} apiKey 假模型 API key
 */
function sealCredential(apiKey) {
  const tenantId = "platform";
  const purpose = "model-credential";
  const keyId = "mk-dek-1";
  const aad = `${tenantId}|${purpose}|${keyId}|1`;

  const dek = randomBytes(32);
  const wrapNonce = randomBytes(12);
  const wrappedDek = gcmSeal(masterKey, wrapNonce, dek, aad);

  const credentialNonce = randomBytes(12);
  const credentialPayload = gcmSeal(
    dek,
    credentialNonce,
    Buffer.from(apiKey, "utf8"),
    aad,
  );
  const ciphertext = `enc:v1:${keyId}:${Buffer.concat([credentialNonce, credentialPayload]).toString("base64")}`;
  return {
    keyId,
    purpose,
    wrapNonce,
    wrappedDek,
    ciphertext,
  };
}

/**
 * 创建租户；已存在视为成功。
 */
async function ensureTenant(admin, id, name) {
  const response = await admin.post("/api/v1/admin/tenants", { id, name });
  if (response.status !== 201 && response.status !== 409) {
    throw new Error(
      `create tenant ${id} failed: ${response.status} ${response.text}`,
    );
  }
  return response.data;
}

/**
 * 创建租户账号；已存在时从列表读取。
 */
async function ensureUser(
  admin,
  tenantId,
  username,
  displayName,
  password,
  roles,
) {
  const response = await admin.post(`/api/v1/admin/tenants/${tenantId}/users`, {
    username,
    displayName,
    password,
    roles,
  });
  if (response.status === 201) {
    return response.data;
  }
  if (response.status === 409) {
    const list = await admin.get(
      `/api/v1/admin/tenants/${tenantId}/users?page=1&size=100`,
    );
    const found = list.data?.items?.find((item) => item.username === username);
    if (found) {
      return found;
    }
  }
  throw new Error(
    `create user ${username} failed: ${response.status} ${response.text}`,
  );
}

/** 以成员身份登录并读取个人工作空间。 */
async function loginAndWorkspace(tenantCode, username, password) {
  const client = new ApiClient(apiBase);
  const session = await client.login(tenantCode, username, password);
  const workspaces = await client.get("/api/v1/workspaces?page=1&size=100");
  if (workspaces.status !== 200) {
    throw new Error(
      `list workspaces failed: ${workspaces.status} ${workspaces.text}`,
    );
  }
  const items = workspaces.data?.items ?? [];
  const workspace = items.find((item) => item.status === "ACTIVE");
  if (!workspace) {
    throw new Error(`no active workspace for ${tenantCode}/${username}`);
  }
  return { client, session, workspace, workspaces: items };
}

const admin = new ApiClient(apiBase);
await admin.login(ADMIN.tenant, ADMIN.username, ADMIN.password);

const tenantA = "tenant-alpha";
const tenantB = "tenant-beta";
await ensureTenant(admin, tenantA, "Alpha 演示企业");
await ensureTenant(admin, tenantB, "Beta 演示企业");

const users = {
  alphaAdmin: await ensureUser(
    admin,
    tenantA,
    "alpha-admin",
    "Alpha 管理员",
    "alpha-admin-password-12",
    ["TENANT_ADMIN"],
  ),
  alphaMember: await ensureUser(
    admin,
    tenantA,
    "alpha-member",
    "Alpha 员工",
    "alpha-member-password-12",
    ["MEMBER"],
  ),
  betaMember: await ensureUser(
    admin,
    tenantB,
    "beta-member",
    "Beta 员工",
    "beta-member-password-12",
    ["MEMBER"],
  ),
};

const alpha = await loginAndWorkspace(
  tenantA,
  "alpha-member",
  "alpha-member-password-12",
);
const beta = await loginAndWorkspace(
  tenantB,
  "beta-member",
  "beta-member-password-12",
);

// 知识库文档：租户管理员上传真实 DOCX 并登记，Worker 异步解析/索引。
// J2 检索阶段依赖 INDEXED 的知识库分块，因此 seed 必须等待索引完成，避免用例与后台任务竞态。
const alphaAdmin = new ApiClient(apiBase);
await alphaAdmin.login(tenantA, "alpha-admin", "alpha-admin-password-12");
const adminWorkspaces = await alphaAdmin.get(
  "/api/v1/workspaces?page=1&size=100",
);
const adminWorkspace = adminWorkspaces.data.items.find(
  (item) => item.status === "ACTIVE",
);
if (!adminWorkspace) {
  throw new Error("no workspace for alpha-admin");
}
const docxPath =
  process.env.REAL_DOCX_PATH ??
  path.join(repoRoot, "e2e/real/fixtures/market.docx");
const upload = await alphaAdmin.upload(adminWorkspace.id, {
  filename: "market.docx",
  mimeType:
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  content: readFileSync(docxPath),
});
if (upload.status !== 201) {
  throw new Error(
    `knowledge source upload failed: ${upload.status} ${upload.text}`,
  );
}
const sourceFileId = upload.data.id;
let fileParseStatus = "PENDING";
for (let attempt = 0; attempt < 120; attempt += 1) {
  const file = await alphaAdmin.get(`/api/v1/files/${sourceFileId}`);
  fileParseStatus = file.data?.parse?.status;
  if (fileParseStatus === "SUCCEEDED") {
    break;
  }
  if (fileParseStatus === "FAILED") {
    throw new Error("knowledge source file parsing failed");
  }
  await new Promise((resolve) => setTimeout(resolve, 500));
}
if (fileParseStatus !== "SUCCEEDED") {
  throw new Error(`knowledge source file did not parse: ${fileParseStatus}`);
}
const knowledge = await alphaAdmin.post("/api/v1/knowledge/documents", {
  title: "2025 中国企业协同办公市场研究",
  fileId: sourceFileId,
});
if (knowledge.status !== 202 && knowledge.status !== 201) {
  throw new Error(
    `knowledge document creation failed: ${knowledge.status} ${knowledge.text}`,
  );
}
const knowledgeDocumentId = knowledge.data.id;
let knowledgeStatus = knowledge.data.status;
for (let attempt = 0; attempt < 180; attempt += 1) {
  const document = await alphaAdmin.get(
    `/api/v1/knowledge/documents/${knowledgeDocumentId}`,
  );
  knowledgeStatus = document.data?.status;
  if (knowledgeStatus === "INDEXED") {
    break;
  }
  if (knowledgeStatus === "FAILED") {
    throw new Error("knowledge indexing failed");
  }
  await new Promise((resolve) => setTimeout(resolve, 500));
}
if (knowledgeStatus !== "INDEXED") {
  throw new Error(
    `knowledge document did not reach INDEXED: ${knowledgeStatus}`,
  );
}

// 模型与 MCP 目录使用部署级固定 UUID；重复运行前会清空数据库，因此不需要 upsert。
const providerId = "0192f0a0-0000-7000-8000-000000000001";
const modelId = "0192f0a0-0000-7000-8000-000000000002";
const mcpServerId = "0192f0a0-0000-7000-8000-000000000003";
const mcpToolId = "0192f0a0-0000-7000-8000-000000000004";
const fakeApiKey = "fake-model-api-key-real-stack";
const credential = sealCredential(fakeApiKey);
const mcpCommand = `node ${path.join(repoRoot, "scripts/real-stack/mcp-server.mjs")}`;
if (mcpCommand.length > 500) {
  throw new Error("mcp command exceeds 512-character column budget");
}

const sql = `
DELETE FROM mcp_tool_grant;
DELETE FROM mcp_tool;
DELETE FROM mcp_server;
DELETE FROM model;
DELETE FROM provider_credential;
DELETE FROM tenant_data_key;
DELETE FROM model_provider;

INSERT INTO model_provider (id, code, name, protocol, base_url, status)
VALUES (${binaryUuid(providerId)}, 'fake-openai', 'Fake OpenAI Compatible', 'OPENAI_COMPATIBLE',
        'http://127.0.0.1:18990/v1', 'ACTIVE');

INSERT INTO model (id, provider_id, model_key, display_name, context_window, max_output_tokens, capabilities, status)
VALUES (${binaryUuid(modelId)}, ${binaryUuid(providerId)}, 'fake-chat', '假模型（真实栈验证）', 32768, 2048,
        JSON_OBJECT('chat', TRUE, 'stream', TRUE, 'tool', TRUE), 'ACTIVE');

INSERT INTO tenant_data_key (tenant_id, key_id, purpose, wrapped_dek, wrap_nonce, algorithm, master_key_id)
VALUES ('platform', ${sqlString(credential.keyId)}, ${sqlString(credential.purpose)},
        UNHEX('${Buffer.from(credential.wrappedDek).toString("hex")}'),
        UNHEX('${credential.wrapNonce.toString("hex")}'), 'AES-256-GCM', 'mk-1');

INSERT INTO provider_credential (tenant_id, id, provider_id, credential_type, ciphertext, key_id, master_key_id, version, status)
VALUES ('platform', ${binaryUuid(randomUUID())}, ${binaryUuid(providerId)}, 'API_KEY',
        ${sqlString(credential.ciphertext)}, ${sqlString(credential.keyId)}, 'mk-1', 1, 'ACTIVE');

INSERT INTO mcp_server (id, code, name, transport, command, status)
VALUES (${binaryUuid(mcpServerId)}, 'riccon-builtin', 'Riccon 内置市场数据', 'STDIO',
        ${sqlString(mcpCommand)}, 'ACTIVE');

INSERT INTO mcp_tool (id, server_id, name, description, input_schema, risk_level, requires_approval, status)
VALUES (${binaryUuid(mcpToolId)}, ${binaryUuid(mcpServerId)}, 'market_data',
        '只读市场数据查询工具（真实栈验证）',
        JSON_OBJECT('type', 'object', 'properties', JSON_OBJECT('query', JSON_OBJECT('type', 'string')),
                    'required', JSON_ARRAY('query')),
        'READ', TRUE, 'ACTIVE');

INSERT INTO mcp_tool_grant (tenant_id, mcp_tool_id, enabled)
VALUES (${sqlString(tenantA)}, ${binaryUuid(mcpToolId)}, TRUE);
`;

runSql(sql, { repoRoot });

// 二次校验：目录与授权确实可读（直接查库，不依赖未实现的目录 API）。
const counts = runSql(
  `
SELECT 'model' AS item, COUNT(*) AS total FROM model WHERE status='ACTIVE'
UNION ALL SELECT 'mcp_tool', COUNT(*) FROM mcp_tool WHERE status='ACTIVE'
UNION ALL SELECT 'grant_a', COUNT(*) FROM mcp_tool_grant WHERE tenant_id='${tenantA}' AND enabled=TRUE
UNION ALL SELECT 'grant_b', COUNT(*) FROM mcp_tool_grant WHERE tenant_id='${tenantB}' AND enabled=TRUE;
`,
  { repoRoot },
).stdout;

const state = {
  apiBase,
  generatedAt: new Date().toISOString(),
  tenants: { alpha: tenantA, beta: tenantB },
  users: {
    platformAdmin: ADMIN.username,
    alphaAdmin: users.alphaAdmin.username,
    alphaMember: "alpha-member",
    betaMember: "beta-member",
  },
  passwords: {
    alphaAdmin: "alpha-admin-password-12",
    alphaMember: "alpha-member-password-12",
    betaMember: "beta-member-password-12",
  },
  tenantIds: { alpha: tenantA, beta: tenantB },
  workspaces: { alpha: alpha.workspace, beta: beta.workspace },
  catalog: { providerId, modelId, mcpServerId, mcpToolId },
  knowledge: {
    documentId: knowledgeDocumentId,
    status: knowledgeStatus,
    sourceFileId,
    title: "2025 中国企业协同办公市场研究",
  },
  fakeApiKey,
  dbCounts: counts.trim(),
};
mkdirSync(path.dirname(outFile), { recursive: true });
writeFileSync(outFile, `${JSON.stringify(state, null, 2)}\n`);
console.log(
  JSON.stringify(
    {
      seed: outFile,
      workspaces: { alpha: alpha.workspace.id, beta: beta.workspace.id },
      catalog: state.catalog,
      dbCounts: state.dbCounts,
    },
    null,
    2,
  ),
);
