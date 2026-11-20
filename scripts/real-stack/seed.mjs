#!/usr/bin/env node
// 真实栈种子：租户/账号、M04 模型目录/路由/key/配额/价格，以及（full 模式）MCP 与知识库样例。
//
// 约束：
// - 租户与账号走公开 Admin API，证明 API 契约可完成开通；
// - 模型目录、路由、平台 key、配额与价格使用测试栈 SQL 幂等写入；
// - MySQL 客户端显式 `--default-character-set=utf8mb4`（见 lib/mysql.mjs）；否则容器内
//   客户端按 locale 回退 latin1，中文展示名落库即双写乱码，路由页读到 `å‡æ¨¡åž‹`；
// - 密钥只来自环境变量 `REAL_MODEL_KEYS`，脚本、日志、输出文件都不含明文 key；
// - 凭据使用与 API/Worker 相同的 AES-256-GCM 信封格式（ADR-0006/ADR-0021 §6.5），
//   主密钥与 fingerprint pepper 只来自环境变量；
// - 脚本输出只含 ID、状态、掩码与行数，不打印主密钥、DEK、pepper 或假 API key。
//
// 模式（`--mode`）：
// - full（默认）：账号 + 知识库 + MCP 目录 + M04 目录；用于 M02/M03/M10/M04 真实栈回归；
// - accounts：仅租户/账号/工作空间；用于 perf 前置；
// - catalog：仅 M04 模型目录/路由/key/配额/价格（可重复执行，结果稳定）；
// - perf：accounts + catalog，跳过知识库与 MCP；用于 PERF-M04。
//
// 环境变量：
// - RICCON_MASTER_KEY（必需，Base64 32 字节）
// - RICCON_CREDENTIAL_FINGERPRINT_PEPPER（catalog 模式必需，Base64 32 字节）
// - RICCON_MASTER_KEY_ID（可选，默认 mk-1）
// - REAL_API_BASE（默认 http://127.0.0.1:18080）
// - REAL_MODEL_ENDPOINT_BASE（默认 http://127.0.0.1:18990；provider baseUrl = 该值 + /v1 或 /v1-secondary）
// - REAL_MODEL_KEYS（catalog 模式必需）：{"fake-primary":[{id,label,key,priority}],"fake-secondary":[...]}
// - REAL_REPO_ROOT / REAL_SEED_OUT / REAL_DOCX_PATH / REAL_SKIP_KNOWLEDGE
//
// 退出码：任何播种步骤失败、缺少必需密钥或 SQL 失败均为非零；自检失败同样非零。

import {
  createCipheriv,
  createHmac,
  randomBytes,
  randomUUID,
} from "node:crypto";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import process from "node:process";

import { ApiClient } from "./lib/http.mjs";
import { binaryUuid, runSql, sqlString, uuidHex } from "./lib/mysql.mjs";

/** 平台管理员的本地测试凭据；与 run.mjs 启动参数一致。 */
const ADMIN = {
  tenant: "platform",
  username: "platform-admin",
  password: "test-bootstrap-password-12",
};

/** 租户与账号定义；M02 起被多个真实栈脚本共享。 */
const TENANTS = {
  alpha: "tenant-alpha",
  beta: "tenant-beta",
};

/** 账号定义；password 只用于本地测试栈。 */
const USERS = [
  {
    key: "alphaAdmin",
    tenant: TENANTS.alpha,
    username: "alpha-admin",
    displayName: "Alpha 管理员",
    password: "alpha-admin-password-12",
    roles: ["TENANT_ADMIN"],
  },
  {
    key: "alphaMember",
    tenant: TENANTS.alpha,
    username: "alpha-member",
    displayName: "Alpha 员工",
    password: "alpha-member-password-12",
    roles: ["MEMBER"],
  },
  {
    key: "alphaAdmin2",
    tenant: TENANTS.alpha,
    username: "alpha-admin-2",
    displayName: "Alpha 审批管理员",
    password: "alpha-admin-2-password-12",
    roles: ["TENANT_ADMIN"],
  },
  {
    key: "betaMember",
    tenant: TENANTS.beta,
    username: "beta-member",
    displayName: "Beta 员工",
    password: "beta-member-password-12",
    roles: ["MEMBER"],
  },
];

/** 固定目录 UUID；每次播种都指向同一行，便于重复执行与断言。 */
const CATALOG_IDS = {
  providers: {
    "fake-primary": "0192f0a0-0000-7000-8000-000000000101",
    "fake-secondary": "0192f0a0-0000-7000-8000-000000000102",
  },
  models: {
    primary: "0192f0a0-0000-7000-8000-000000000111",
    secondary: "0192f0a0-0000-7000-8000-000000000112",
    disabled: "0192f0a0-0000-7000-8000-000000000113",
  },
  mcpServerId: "0192f0a0-0000-7000-8000-000000000003",
  mcpToolId: "0192f0a0-0000-7000-8000-000000000004",
};

/** 平台 DEK 标识；catalog 脚本使用主密钥派生的确定性 DEK，保证重复播种可解密。 */
const PLATFORM_DEK_ID = "mk-dek-1";

/** DEK 用途；与 API/Worker 一致。 */
const DEK_PURPOSE = "model-credential";

/** 信封 schema 版本；AAD 以 tenant|purpose|keyId|version 绑定。 */
const ENVELOPE_VERSION = 1;

/** 中文可读性判定：至少一个 CJK 字符，且不含 U+FFFD 替换符。 */
const CJK_PATTERN = /[\u4e00-\u9fff]/;

/**
 * 断言文本是可直接展示的中文；用于自检与落库回读，防止字符集错误静默通过。
 *
 * @param {string} label 字段说明
 * @param {unknown} value 待校验值
 * @returns {void}
 */
function assertChineseText(label, value) {
  if (
    typeof value !== "string" ||
    !CJK_PATTERN.test(value) ||
    value.includes("\uFFFD")
  ) {
    throw new Error(
      `${label} must contain readable Chinese text: ${JSON.stringify(value)}`,
    );
  }
}

/**
 * 打印用法。
 */
function usage() {
  console.log(`用法: node scripts/real-stack/seed.mjs [options]

选项:
  --mode full|accounts|catalog|perf   播种阶段，默认 full
  --self-test                         不连接 API/DB，校验目录定义与密钥 JSON 结构
  --help                              打印本帮助

模式:
  full      租户/账号 + 知识库 + MCP 目录 + M04 模型目录（默认）
  accounts  仅租户/账号/工作空间
  catalog   仅 M04 模型目录/路由/key/配额/价格（幂等）
  perf      accounts + catalog（跳过知识库与 MCP）

必需环境变量:
  RICCON_MASTER_KEY                       Base64 32 字节主密钥
  RICCON_CREDENTIAL_FINGERPRINT_PEPPER    catalog/perf 模式的 Base64 32 字节 pepper
  REAL_MODEL_KEYS                         catalog/perf 模式的 key JSON（不写入输出文件）`);
}

/**
 * 解析命令行参数。
 *
 * @param {string[]} argv 参数
 * @returns {{mode: string, selfTest: boolean, help: boolean}} 解析结果
 */
function parseArgs(argv) {
  const options = { mode: "full", selfTest: false, help: false };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === "--mode") {
      index += 1;
      options.mode = argv[index];
    } else if (arg.startsWith("--mode=")) {
      options.mode = arg.slice("--mode=".length);
    } else if (arg === "--self-test") {
      options.selfTest = true;
    } else if (arg === "--help" || arg === "-h") {
      options.help = true;
    } else {
      throw new Error(`未知参数: ${arg}`);
    }
  }
  if (!["full", "accounts", "catalog", "perf"].includes(options.mode)) {
    throw new Error(`未知模式: ${options.mode}`);
  }
  return options;
}

/**
 * 读取环境变量，空字符串按未设置处理。
 *
 * @param {string} name 变量名
 * @returns {string|undefined} 值
 */
function env(name) {
  const value = process.env[name];
  return value === undefined || value.trim() === "" ? undefined : value.trim();
}

/**
 * 读取 Base64 32 字节密钥。
 *
 * @param {string} name 变量名
 * @param {boolean} required 是否必需
 * @returns {Buffer|null} 32 字节
 */
function readSecret(name, required) {
  const raw = env(name);
  if (!raw) {
    if (required) {
      throw new Error(`${name} is required (Base64 32 bytes)`);
    }
    return null;
  }
  const buffer = Buffer.from(raw, "base64");
  if (buffer.length !== 32) {
    throw new Error(`${name} must decode to 32 bytes`);
  }
  return buffer;
}

/**
 * 解析 `REAL_MODEL_KEYS`；校验 provider、key 长度与字段。
 *
 * @param {string|undefined} raw JSON 文本
 * @param {{requireAll?: boolean}} [options] 是否要求两个 provider 都有 key
 * @returns {Record<string, Array<{id: string, label: string, key: string, priority: number}>>} key 表
 */
function parseModelKeys(raw, options = {}) {
  const requireAll = options.requireAll ?? true;
  if (!raw) {
    if (requireAll) {
      throw new Error("REAL_MODEL_KEYS is required for catalog seeding");
    }
    return {};
  }
  const parsed = JSON.parse(raw);
  if (!parsed || typeof parsed !== "object") {
    throw new Error("REAL_MODEL_KEYS must be a JSON object");
  }
  const result = {};
  for (const [providerCode, entries] of Object.entries(parsed)) {
    if (!["fake-primary", "fake-secondary"].includes(providerCode)) {
      throw new Error(`REAL_MODEL_KEYS has unknown provider: ${providerCode}`);
    }
    if (!Array.isArray(entries) || entries.length === 0) {
      throw new Error(
        `REAL_MODEL_KEYS.${providerCode} must be a non-empty array`,
      );
    }
    result[providerCode] = entries.map((entry, index) => {
      if (typeof entry?.id !== "string" || !/^[0-9a-f-]{36}$/i.test(entry.id)) {
        throw new Error(
          `REAL_MODEL_KEYS.${providerCode}[${index}].id must be a UUID`,
        );
      }
      if (
        typeof entry.label !== "string" ||
        entry.label.length === 0 ||
        entry.label.length > 64
      ) {
        throw new Error(
          `REAL_MODEL_KEYS.${providerCode}[${index}].label is invalid`,
        );
      }
      if (
        typeof entry.key !== "string" ||
        entry.key.length < 16 ||
        entry.key.length > 512
      ) {
        throw new Error(
          `REAL_MODEL_KEYS.${providerCode}[${index}].key must be 16..512 chars`,
        );
      }
      const priority = entry.priority ?? 100;
      if (!Number.isInteger(priority) || priority < 1 || priority > 1000) {
        throw new Error(
          `REAL_MODEL_KEYS.${providerCode}[${index}].priority must be 1..1000`,
        );
      }
      return { id: entry.id, label: entry.label, key: entry.key, priority };
    });
  }
  if (requireAll) {
    for (const code of ["fake-primary", "fake-secondary"]) {
      if (!result[code]?.length) {
        throw new Error(`REAL_MODEL_KEYS.${code} is required`);
      }
    }
  }
  return result;
}

/**
 * 构造 M04 目录定义（不接触密钥）；供真实账号与自检共用。
 *
 * @param {string} endpointBase 假模型回环地址（无尾斜杠）
 * @returns {object} 目录定义
 */
function buildCatalog(endpointBase) {
  const base = endpointBase.replace(/\/$/, "");
  const providers = [
    {
      id: CATALOG_IDS.providers["fake-primary"],
      code: "fake-primary",
      name: "假模型主供应商（真实栈）",
      baseUrl: `${base}/v1`,
      environmentLabel: "DEMO_ONLY",
      status: "ACTIVE",
    },
    {
      id: CATALOG_IDS.providers["fake-secondary"],
      code: "fake-secondary",
      name: "假模型备用供应商（真实栈）",
      baseUrl: `${base}/v1-secondary`,
      environmentLabel: "DEMO_ONLY",
      status: "ACTIVE",
    },
  ];
  const models = [
    {
      id: CATALOG_IDS.models.primary,
      providerCode: "fake-primary",
      modelKey: "fake-chat-primary",
      displayName: "假模型-主（真实栈）",
      contextWindow: 32768,
      maxOutputTokens: 2048,
      inputPrice: "1.500000",
      outputPrice: "3.000000",
      status: "ACTIVE",
    },
    {
      id: CATALOG_IDS.models.secondary,
      providerCode: "fake-secondary",
      modelKey: "fake-chat-secondary",
      displayName: "假模型-备用（真实栈）",
      contextWindow: 32768,
      maxOutputTokens: 2048,
      inputPrice: "2.000000",
      outputPrice: "4.000000",
      status: "ACTIVE",
    },
    {
      id: CATALOG_IDS.models.disabled,
      providerCode: "fake-primary",
      modelKey: "fake-chat-disabled",
      displayName: "假模型-停用示例（真实栈）",
      contextWindow: 16384,
      maxOutputTokens: 1024,
      inputPrice: null,
      outputPrice: null,
      status: "DISABLED",
    },
  ];
  return {
    endpointBase: base,
    providers,
    models,
    routing: {
      CHAT: [
        CATALOG_IDS.models.primary,
        CATALOG_IDS.models.secondary,
        CATALOG_IDS.models.disabled,
      ],
      REPORT: [CATALOG_IDS.models.primary, CATALOG_IDS.models.secondary],
    },
  };
}

/**
 * 自检：只校验目录与密钥 JSON 结构，不连接 API/DB，失败即非零。
 */
function selfTest() {
  const endpointBase =
    env("REAL_MODEL_ENDPOINT_BASE") ?? "http://127.0.0.1:18990";
  const catalog = buildCatalog(endpointBase);
  const providerCodes = new Set(
    catalog.providers.map((provider) => provider.code),
  );
  if (providerCodes.size !== catalog.providers.length) {
    throw new Error("catalog provider codes must be unique");
  }
  const modelIds = new Set(catalog.models.map((model) => model.id));
  if (modelIds.size !== catalog.models.length) {
    throw new Error("catalog model ids must be unique");
  }
  for (const provider of catalog.providers) {
    assertChineseText(`provider ${provider.code} name`, provider.name);
  }
  for (const model of catalog.models) {
    if (!providerCodes.has(model.providerCode)) {
      throw new Error(
        `model ${model.modelKey} references unknown provider ${model.providerCode}`,
      );
    }
    if ((model.inputPrice === null) !== (model.outputPrice === null)) {
      throw new Error(
        `model ${model.modelKey} must be either fully priced or unpriced`,
      );
    }
    assertChineseText(`model ${model.modelKey} displayName`, model.displayName);
  }
  if (sqlString("假模型") !== "'假模型'") {
    throw new Error("sqlString must pass Chinese text through unchanged");
  }
  for (const [purpose, entries] of Object.entries(catalog.routing)) {
    if (!["CHAT", "REPORT"].includes(purpose)) {
      throw new Error(`unknown routing purpose ${purpose}`);
    }
    const unique = new Set(entries);
    if (unique.size !== entries.length) {
      throw new Error(`routing ${purpose} must not contain duplicate models`);
    }
    for (const modelId of entries) {
      if (!modelIds.has(modelId)) {
        throw new Error(
          `routing ${purpose} references unknown model ${modelId}`,
        );
      }
    }
  }
  const sampleKeys = parseModelKeys(
    JSON.stringify({
      "fake-primary": [
        {
          id: randomUUID(),
          label: "自检主 key",
          key: "self-test-primary-key-0001",
          priority: 10,
        },
      ],
      "fake-secondary": [
        {
          id: randomUUID(),
          label: "自检备用 key",
          key: "self-test-secondary-key-0001",
          priority: 10,
        },
      ],
    }),
  );
  const report = {
    status: "passed",
    endpointBase: catalog.endpointBase,
    providers: catalog.providers.map((provider) => ({
      code: provider.code,
      baseUrl: provider.baseUrl,
      environmentLabel: provider.environmentLabel,
    })),
    models: catalog.models.map((model) => ({
      modelKey: model.modelKey,
      status: model.status,
      pricing: model.inputPrice === null ? "UNPRICED" : "PRICED",
    })),
    routing: Object.fromEntries(
      Object.entries(catalog.routing).map(([purpose, entries]) => [
        purpose,
        entries.length,
      ]),
    ),
    keys: Object.fromEntries(
      Object.entries(sampleKeys).map(([code, entries]) => [
        code,
        entries.length,
      ]),
    ),
  };
  console.log(JSON.stringify(report, null, 2));
}

/**
 * 使用 AES-256-GCM 封装一个明文。
 *
 * @param {Buffer} key 32 字节 DEK 或主密钥
 * @param {Buffer} nonce 12 字节 nonce
 * @param {Buffer|string} plaintext 明文
 * @param {string} aad 附加认证数据
 * @returns {Buffer} 密文 + GCM tag
 */
function gcmSeal(key, nonce, plaintext, aad) {
  const cipher = createCipheriv("aes-256-gcm", key, nonce);
  cipher.setAAD(Buffer.from(aad, "utf8"));
  const encrypted = Buffer.concat([cipher.update(plaintext), cipher.final()]);
  return Buffer.concat([encrypted, cipher.getAuthTag()]);
}

/**
 * 由主密钥派生平台 model-credential DEK，保证重复播种使用同一 DEK。
 *
 * @param {Buffer} masterKey 主密钥
 * @returns {Buffer} 32 字节 DEK
 */
function derivePlatformDek(masterKey) {
  return createHmac("sha256", masterKey)
    .update("riccon-real-stack-model-credential-dek-v1")
    .digest();
}

/**
 * 构造 AAD；与 Worker `EnvelopeDecryptor.aad` 逐字一致。
 *
 * @param {string} tenantId 租户
 * @param {string} purpose 用途
 * @param {string} keyId DEK 标识
 * @returns {string} AAD
 */
function envelopeAad(tenantId, purpose, keyId) {
  return `${tenantId}|${purpose}|${keyId}|${ENVELOPE_VERSION}`;
}

/**
 * 计算 fingerprint；与 API `MasterKeyRing.fingerprint` 一致。
 *
 * @param {Buffer} pepper pepper
 * @param {string} providerId provider UUID 字符串
 * @param {string} apiKey 明文 key
 * @returns {string} 64 位十六进制
 */
function fingerprint(pepper, providerId, apiKey) {
  return createHmac("sha256", pepper)
    .update(providerId, "utf8")
    .update(Buffer.from([0]))
    .update(apiKey, "utf8")
    .digest("hex");
}

/**
 * 只保留末 4 位，供日志/输出使用。
 *
 * @param {string} apiKey 明文 key
 * @returns {string} 掩码
 */
function maskHint(apiKey) {
  return `****${apiKey.slice(-4)}`;
}

/**
 * 封一封平台凭据：确定性 DEK + 每次随机 nonce；不返回明文。
 *
 * @param {object} args 参数
 * @returns {{ciphertext: string, wrappedDek: Buffer, wrapNonce: Buffer, keyId: string}} 密文材料
 */
function sealCredential({ masterKey, dek, apiKey }) {
  const aad = envelopeAad("platform", DEK_PURPOSE, PLATFORM_DEK_ID);
  const wrapNonce = randomBytes(12);
  const wrappedDek = gcmSeal(masterKey, wrapNonce, dek, aad);
  const credentialNonce = randomBytes(12);
  const payload = gcmSeal(
    dek,
    credentialNonce,
    Buffer.from(apiKey, "utf8"),
    aad,
  );
  const ciphertext = `enc:v1:${PLATFORM_DEK_ID}:${Buffer.concat([credentialNonce, payload]).toString("base64")}`;
  return { ciphertext, wrappedDek, wrapNonce, keyId: PLATFORM_DEK_ID };
}

/**
 * 创建租户；已存在视为成功。
 *
 * @param {ApiClient} admin 平台管理员客户端
 * @param {string} id 租户代码
 * @param {string} name 展示名
 * @returns {Promise<object>} 响应
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
 *
 * @param {ApiClient} admin 平台管理员客户端
 * @param {string} tenantId 租户
 * @param {string} username 登录名
 * @param {string} displayName 展示名
 * @param {string} password 密码
 * @param {string[]} roles 角色
 * @returns {Promise<object>} 账号
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

/**
 * 以成员身份登录并读取个人工作空间。
 *
 * @param {string} apiBase API 根地址
 * @param {string} tenantCode 租户
 * @param {string} username 用户名
 * @param {string} password 密码
 * @returns {Promise<{client: ApiClient, workspace: object, workspaces: object[]}>} 会话与空间
 */
async function loginAndWorkspace(apiBase, tenantCode, username, password) {
  const client = new ApiClient(apiBase);
  await client.login(tenantCode, username, password);
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
  return { client, workspace, workspaces: items };
}

/**
 * 播种租户、账号与工作空间。
 *
 * @param {object} context 运行上下文
 * @returns {Promise<object>}  seeds 结果
 */
async function seedAccounts(context) {
  const admin = new ApiClient(context.apiBase);
  await admin.login(ADMIN.tenant, ADMIN.username, ADMIN.password);
  await ensureTenant(admin, TENANTS.alpha, "Alpha 演示企业");
  await ensureTenant(admin, TENANTS.beta, "Beta 演示企业");

  const users = {};
  for (const definition of USERS) {
    users[definition.key] = await ensureUser(
      admin,
      definition.tenant,
      definition.username,
      definition.displayName,
      definition.password,
      definition.roles,
    );
  }

  const alpha = await loginAndWorkspace(
    context.apiBase,
    TENANTS.alpha,
    "alpha-member",
    "alpha-member-password-12",
  );
  const beta = await loginAndWorkspace(
    context.apiBase,
    TENANTS.beta,
    "beta-member",
    "beta-member-password-12",
  );
  return {
    users,
    workspaces: { alpha: alpha.workspace, beta: beta.workspace },
  };
}

/**
 * 上传 DOCX 并等待解析/索引；仅 full 模式调用。
 *
 * @param {object} context 运行上下文
 * @returns {Promise<object>} 知识库状态
 */
async function seedKnowledge(context) {
  const alphaAdmin = new ApiClient(context.apiBase);
  await alphaAdmin.login(
    TENANTS.alpha,
    "alpha-admin",
    "alpha-admin-password-12",
  );
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
    env("REAL_DOCX_PATH") ??
    path.join(context.repoRoot, "e2e/real/fixtures/market.docx");
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
  return {
    documentId: knowledgeDocumentId,
    status: knowledgeStatus,
    sourceFileId,
    title: "2025 中国企业协同办公市场研究",
  };
}

/**
 * 播种 MCP 目录与租户授权；仅 full 模式调用。
 *
 * @param {object} context 运行上下文
 * @returns {void}
 */
function seedMcpCatalog(context) {
  const mcpCommand = `node ${path.join(context.repoRoot, "scripts/real-stack/mcp-server.mjs")}`;
  if (mcpCommand.length > 500) {
    throw new Error("mcp command exceeds 512-character column budget");
  }
  runSql(
    `
INSERT INTO mcp_server (id, code, name, transport, command, status)
VALUES (${binaryUuid(CATALOG_IDS.mcpServerId)}, 'riccon-builtin', 'Riccon 内置市场数据', 'STDIO',
        ${sqlString(mcpCommand)}, 'ACTIVE')
ON DUPLICATE KEY UPDATE name = VALUES(name), transport = VALUES(transport),
                        command = VALUES(command), status = VALUES(status);

INSERT INTO mcp_tool (id, server_id, name, description, input_schema, risk_level, requires_approval, status)
VALUES (${binaryUuid(CATALOG_IDS.mcpToolId)}, ${binaryUuid(CATALOG_IDS.mcpServerId)}, 'market_data',
        '只读市场数据查询工具（真实栈验证）',
        JSON_OBJECT('type', 'object', 'properties', JSON_OBJECT('query', JSON_OBJECT('type', 'string')),
                    'required', JSON_ARRAY('query')),
        'READ', TRUE, 'ACTIVE')
ON DUPLICATE KEY UPDATE server_id = VALUES(server_id), description = VALUES(description),
                        input_schema = VALUES(input_schema), risk_level = VALUES(risk_level),
                        requires_approval = VALUES(requires_approval), status = VALUES(status);

INSERT INTO mcp_tool_grant (tenant_id, mcp_tool_id, enabled)
VALUES (${sqlString(TENANTS.alpha)}, ${binaryUuid(CATALOG_IDS.mcpToolId)}, TRUE)
ON DUPLICATE KEY UPDATE enabled = TRUE;
`,
    { repoRoot: context.repoRoot },
  );
}

/**
 * 输出本次播种的目录 ID 与掩码；不含明文密钥。
 *
 * @param {object} catalog 目录定义
 * @param {Record<string, Array<object>>} modelKeys key 表
 * @returns {object} 可写入输出的目录信息
 */
function catalogOutput(catalog, modelKeys) {
  return {
    providerId: CATALOG_IDS.providers["fake-primary"],
    modelId: CATALOG_IDS.models.primary,
    mcpServerId: CATALOG_IDS.mcpServerId,
    mcpToolId: CATALOG_IDS.mcpToolId,
    providers: catalog.providers.map((provider) => ({
      id: provider.id,
      code: provider.code,
      name: provider.name,
      environmentLabel: provider.environmentLabel,
      modelIds: catalog.models
        .filter((model) => model.providerCode === provider.code)
        .map((model) => model.id),
    })),
    models: catalog.models.map((model) => ({
      id: model.id,
      modelKey: model.modelKey,
      displayName: model.displayName,
      status: model.status,
      pricing: model.inputPrice === null ? "UNPRICED" : "PRICED",
    })),
    routing: catalog.routing,
    credentials: Object.fromEntries(
      Object.entries(modelKeys).map(([code, entries]) => [
        code,
        entries.map((entry) => ({
          id: entry.id,
          label: entry.label,
          priority: entry.priority,
          maskedHint: maskHint(entry.key),
        })),
      ]),
    ),
  };
}

/**
 * 回读目录中文文本，确认 mysql 客户端字符集正确（utf8mb4）。
 *
 * 字符集错误会把 UTF-8 字节按 latin1 写入，落库为双写乱码；只比对行数发现不了，
 * 因此逐字段比对预期展示名，任何缺失或乱码都中止播种。
 *
 * @param {object} catalog 目录定义
 * @param {Record<string, Array<object>>} modelKeys key 表
 * @param {string} repoRoot 仓库根
 * @returns {void}
 */
function assertCatalogTextIntegrity(catalog, modelKeys, repoRoot) {
  const expected = new Map();
  for (const provider of catalog.providers) {
    assertChineseText(`provider ${provider.code} name`, provider.name);
    expected.set(`provider:${uuidHex(provider.id)}`, provider.name);
  }
  for (const model of catalog.models) {
    assertChineseText(`model ${model.modelKey} displayName`, model.displayName);
    expected.set(`model:${uuidHex(model.id)}`, model.displayName);
  }
  for (const entries of Object.values(modelKeys)) {
    for (const entry of entries) {
      assertChineseText(`credential ${entry.id} label`, entry.label);
      expected.set(`credential:${uuidHex(entry.id)}`, entry.label);
    }
  }
  const modelIds = catalog.models
    .map((model) => binaryUuid(model.id))
    .join(", ");
  const providerIds = catalog.providers
    .map((provider) => binaryUuid(provider.id))
    .join(", ");
  const credentialIds = Object.values(modelKeys)
    .flat()
    .map((entry) => binaryUuid(entry.id))
    .join(", ");
  const rows = runSql(
    `
SELECT 'model' AS kind, LOWER(HEX(id)) AS id_hex, display_name AS text_value
  FROM model WHERE id IN (${modelIds})
UNION ALL
SELECT 'provider', LOWER(HEX(id)), name
  FROM model_provider WHERE id IN (${providerIds})
UNION ALL
SELECT 'credential', LOWER(HEX(id)), label
  FROM provider_credential WHERE tenant_id = 'platform' AND id IN (${credentialIds});
`,
    { repoRoot },
  ).stdout;
  const actual = new Map();
  for (const line of rows.split("\n")) {
    if (line.trim() === "") {
      continue;
    }
    const [kind, idHex, ...text] = line.split("\t");
    actual.set(`${kind}:${idHex}`, text.join("\t"));
  }
  const mismatches = [];
  for (const [key, value] of expected) {
    const stored = actual.get(key);
    if (stored !== value) {
      mismatches.push({ field: key, expected: value, actual: stored ?? null });
    }
  }
  if (mismatches.length > 0) {
    throw new Error(
      `catalog Chinese text is corrupted (check mysql client charset): ${JSON.stringify(mismatches)}`,
    );
  }
}

/**
 * 幂等播种 M04 模型目录、路由、平台 key、配额与价格。
 *
 * @param {object} context 运行上下文
 * @returns {void}
 */
function seedCatalog(context) {
  const modelKeys = context.modelKeys;
  const catalog = context.catalog;
  const providerIdByCode = new Map(
    catalog.providers.map((provider) => [provider.code, provider.id]),
  );
  const masterKey = context.masterKey;
  const pepper = context.pepper;
  if (!masterKey) {
    throw new Error("RICCON_MASTER_KEY is required for catalog seeding");
  }
  if (!pepper) {
    throw new Error(
      "RICCON_CREDENTIAL_FINGERPRINT_PEPPER is required for catalog seeding",
    );
  }
  const dek = derivePlatformDek(masterKey);

  const statements = [];

  // 1) provider 目录：按 id 幂等 upsert；baseUrl 与部署允许列表保持一致。
  for (const provider of catalog.providers) {
    statements.push(`
INSERT INTO model_provider (id, code, name, protocol, base_url, status)
VALUES (${binaryUuid(provider.id)}, ${sqlString(provider.code)}, ${sqlString(provider.name)},
        'OPENAI_COMPATIBLE', ${sqlString(provider.baseUrl)}, ${sqlString(provider.status)})
ON DUPLICATE KEY UPDATE code = VALUES(code), name = VALUES(name), protocol = VALUES(protocol),
                        base_url = VALUES(base_url), status = VALUES(status);`);
  }

  // 2) 模型与价格：价格属于部署种子，重复执行覆盖为同一值；不触碰历史 model_invocation 快照。
  for (const model of catalog.models) {
    const providerId = providerIdByCode.get(model.providerCode);
    const inputPrice = model.inputPrice === null ? "NULL" : model.inputPrice;
    const outputPrice = model.outputPrice === null ? "NULL" : model.outputPrice;
    statements.push(`
INSERT INTO model (id, provider_id, model_key, display_name, context_window, max_output_tokens,
                   capabilities, input_price_per_1m, output_price_per_1m, status)
VALUES (${binaryUuid(model.id)}, ${binaryUuid(providerId)}, ${sqlString(model.modelKey)},
        ${sqlString(model.displayName)}, ${model.contextWindow}, ${model.maxOutputTokens},
        JSON_OBJECT('chat', TRUE, 'stream', TRUE, 'tool', TRUE), ${inputPrice}, ${outputPrice},
        ${sqlString(model.status)})
ON DUPLICATE KEY UPDATE provider_id = VALUES(provider_id), model_key = VALUES(model_key),
                        display_name = VALUES(display_name), context_window = VALUES(context_window),
                        max_output_tokens = VALUES(max_output_tokens), capabilities = VALUES(capabilities),
                        input_price_per_1m = VALUES(input_price_per_1m),
                        output_price_per_1m = VALUES(output_price_per_1m), status = VALUES(status);`);
  }

  // 3) 路由链：每次都写成同一有序结果；不动 version（种子不是管理员保存）。
  for (const purpose of ["CHAT", "REPORT"]) {
    statements.push(`
INSERT INTO model_routing (purpose) VALUES (${sqlString(purpose)})
ON DUPLICATE KEY UPDATE purpose = purpose;
DELETE FROM model_routing_entry WHERE purpose = ${sqlString(purpose)};`);
    catalog.routing[purpose].forEach((modelId, index) => {
      statements.push(`
INSERT INTO model_routing_entry (purpose, position, model_id)
VALUES (${sqlString(purpose)}, ${index + 1}, ${binaryUuid(modelId)});`);
    });
  }

  // 4) 开关：只在缺失时写入默认值；不覆盖运维已经执行的 false→true 演练结果。
  statements.push(`
INSERT INTO model_gateway_switch (switch_key, switch_value)
VALUES ('routingMode', 'ROUTED'), ('failoverEnabled', 'true'), ('quotaEnforcementEnabled', 'false')
ON DUPLICATE KEY UPDATE switch_key = switch_key;`);

  // 5) 平台 DEK：确定性 DEK + 随机 nonce；重复执行可解密。
  const wrapNonce = randomBytes(12);
  const wrappedDek = gcmSeal(
    masterKey,
    wrapNonce,
    dek,
    envelopeAad("platform", DEK_PURPOSE, PLATFORM_DEK_ID),
  );
  statements.push(`
INSERT INTO tenant_data_key (tenant_id, key_id, purpose, wrapped_dek, wrap_nonce, algorithm, master_key_id)
VALUES ('platform', ${sqlString(PLATFORM_DEK_ID)}, ${sqlString(DEK_PURPOSE)},
        UNHEX('${wrappedDek.toString("hex")}'), UNHEX('${wrapNonce.toString("hex")}'),
        'AES-256-GCM', ${sqlString(context.masterKeyId)})
ON DUPLICATE KEY UPDATE wrapped_dek = VALUES(wrapped_dek), wrap_nonce = VALUES(wrap_nonce),
                        algorithm = VALUES(algorithm), master_key_id = VALUES(master_key_id);`);

  // 6) 平台 key 池：fingerprint 唯一；重复播种只确保行存在，不重写密文与状态。
  for (const [providerCode, entries] of Object.entries(modelKeys)) {
    const providerId = providerIdByCode.get(providerCode);
    for (const entry of entries) {
      const sealed = sealCredential({
        masterKey,
        dek,
        apiKey: entry.key,
      });
      const digest = fingerprint(pepper, providerId, entry.key);
      statements.push(`
INSERT INTO provider_credential (
  tenant_id, id, provider_id, credential_type, ciphertext, key_id, master_key_id, version, status,
  label, fingerprint, masked_hint, priority
) VALUES (
  'platform', ${binaryUuid(entry.id)}, ${binaryUuid(providerId)}, 'API_KEY',
  ${sqlString(sealed.ciphertext)}, ${sqlString(sealed.keyId)}, ${sqlString(context.masterKeyId)}, 1, 'ACTIVE',
  ${sqlString(entry.label)}, ${sqlString(digest)}, ${sqlString(maskHint(entry.key))}, ${entry.priority}
) ON DUPLICATE KEY UPDATE ciphertext = VALUES(ciphertext), key_id = VALUES(key_id),
                        master_key_id = VALUES(master_key_id), label = VALUES(label),
                        masked_hint = VALUES(masked_hint), priority = VALUES(priority);`);
    }
  }

  // 7) 配额：优先使用部署默认；显式行只做首次写入，保留管理员后续修改。
  //    用 SELECT + EXISTS 让 catalog-only 在租户/用户尚不存在时安全跳过，而不是触发外键失败。
  for (const tenant of [TENANTS.alpha, TENANTS.beta]) {
    for (const [windowKind, limit] of [
      ["DAILY", 10000],
      ["MONTHLY", 200000],
    ]) {
      statements.push(`
INSERT IGNORE INTO quota_limit (tenant_id, scope_type, scope_id, user_id, window_kind, limit_count)
SELECT ${sqlString(tenant)}, 'TENANT', ${sqlString(tenant)}, NULL, ${sqlString(windowKind)}, ${limit}
  FROM tenant WHERE id = ${sqlString(tenant)};`);
    }
  }
  if (context.users) {
    for (const username of ["alpha-admin", "alpha-member"]) {
      const user = Object.values(context.users).find(
        (item) => item.username === username,
      );
      if (!user) {
        continue;
      }
      for (const [windowKind, limit] of [
        ["DAILY", 1000],
        ["MONTHLY", 20000],
      ]) {
        statements.push(`
INSERT IGNORE INTO quota_limit (tenant_id, scope_type, scope_id, user_id, window_kind, limit_count)
SELECT ${sqlString(TENANTS.alpha)}, 'USER', ${sqlString(user.id)}, ${binaryUuid(user.id)},
       ${sqlString(windowKind)}, ${limit}
  FROM app_user WHERE tenant_id = ${sqlString(TENANTS.alpha)} AND id = ${binaryUuid(user.id)};`);
      }
    }
  }

  runSql(statements.join("\n"), { repoRoot: context.repoRoot });

  // 中文回读：客户端字符集错误会静默写入双写乱码，只有逐字段回读能发现。
  assertCatalogTextIntegrity(catalog, modelKeys, context.repoRoot);
  context.catalogTextIntegrity = "passed";

  // 二次校验：目录、路由、key 与配额确实可读。
  const counts = runSql(
    `
SELECT 'providers' AS item, COUNT(*) AS total FROM model_provider WHERE status='ACTIVE'
UNION ALL SELECT 'models_active', COUNT(*) FROM model WHERE status='ACTIVE'
UNION ALL SELECT 'models_priced', COUNT(*) FROM model WHERE input_price_per_1m IS NOT NULL
UNION ALL SELECT 'routing_chat', COUNT(*) FROM model_routing_entry WHERE purpose='CHAT'
UNION ALL SELECT 'routing_report', COUNT(*) FROM model_routing_entry WHERE purpose='REPORT'
UNION ALL SELECT 'credentials_active', COUNT(*) FROM provider_credential
          WHERE tenant_id='platform' AND status='ACTIVE'
UNION ALL SELECT 'quota_limits', COUNT(*) FROM quota_limit;
`,
    { repoRoot: context.repoRoot },
  ).stdout;
  context.catalogCounts = counts.trim();
}

/**
 * 写入 seed 状态文件（仅账号阶段写；catalog-only 不覆盖）。
 *
 * @param {object} context 上下文
 * @param {object} accounts accounts 结果
 * @param {object|null} knowledge 知识库结果
 */
function writeState(context, accounts, knowledge) {
  const state = {
    apiBase: context.apiBase,
    generatedAt: new Date().toISOString(),
    mode: context.mode,
    tenants: { alpha: TENANTS.alpha, beta: TENANTS.beta },
    users: {
      platformAdmin: ADMIN.username,
      alphaAdmin: "alpha-admin",
      alphaAdmin2: "alpha-admin-2",
      alphaMember: "alpha-member",
      betaMember: "beta-member",
    },
    userIds: {
      alphaAdmin: accounts.users.alphaAdmin.id,
      alphaAdmin2: accounts.users.alphaAdmin2.id,
      alphaMember: accounts.users.alphaMember.id,
      betaMember: accounts.users.betaMember.id,
    },
    passwords: {
      platformAdmin: ADMIN.password,
      alphaAdmin: "alpha-admin-password-12",
      alphaAdmin2: "alpha-admin-2-password-12",
      alphaMember: "alpha-member-password-12",
      betaMember: "beta-member-password-12",
    },
    tenantIds: { alpha: TENANTS.alpha, beta: TENANTS.beta },
    workspaces: {
      alpha: accounts.workspaces.alpha,
      beta: accounts.workspaces.beta,
    },
    catalog: catalogOutput(context.catalog, context.modelKeys),
    knowledge,
    dbCounts: context.catalogCounts ?? "",
  };
  mkdirSync(path.dirname(context.seedOut), { recursive: true });
  writeFileSync(context.seedOut, `${JSON.stringify(state, null, 2)}\n`);
}

/**
 * 主流程。
 *
 * @param {string[]} argv 参数
 */
async function main(argv) {
  const options = parseArgs(argv);
  if (options.help) {
    usage();
    return;
  }
  const endpointBase =
    env("REAL_MODEL_ENDPOINT_BASE") ?? "http://127.0.0.1:18990";
  const catalog = buildCatalog(endpointBase);
  if (options.selfTest) {
    selfTest();
    return;
  }

  const repoRoot = env("REAL_REPO_ROOT") ?? process.cwd();
  const apiBase = env("REAL_API_BASE") ?? "http://127.0.0.1:18080";
  const mode = options.mode;
  const needsCatalog = mode === "catalog" || mode === "perf" || mode === "full";
  const needsAccounts =
    mode === "accounts" || mode === "perf" || mode === "full";
  const context = {
    repoRoot,
    apiBase,
    mode,
    catalog,
    masterKey: readSecret("RICCON_MASTER_KEY", needsCatalog),
    pepper: readSecret("RICCON_CREDENTIAL_FINGERPRINT_PEPPER", needsCatalog),
    masterKeyId: env("RICCON_MASTER_KEY_ID") ?? "mk-1",
    modelKeys: needsCatalog ? parseModelKeys(env("REAL_MODEL_KEYS")) : {},
    seedOut:
      env("REAL_SEED_OUT") ??
      path.join(repoRoot, ".riccon/evidence/TASK-0024/real-stack-seed.json"),
    catalogCounts: "",
    catalogTextIntegrity: "",
  };

  const summary = { mode, apiBase, catalog: {}, knowledge: null };

  let accounts = null;
  if (needsAccounts) {
    accounts = await seedAccounts(context);
    context.users = accounts.users;
    summary.workspaces = {
      alpha: accounts.workspaces.alpha.id,
      beta: accounts.workspaces.beta.id,
    };
  }

  if (mode === "full" && env("REAL_SKIP_KNOWLEDGE") !== "1") {
    summary.knowledge = await seedKnowledge(context);
  }

  if (needsCatalog) {
    seedCatalog(context);
    summary.catalog = {
      providers: catalog.providers.map((provider) => provider.code),
      models: catalog.models.map((model) => model.modelKey),
      routing: Object.fromEntries(
        Object.entries(catalog.routing).map(([purpose, entries]) => [
          purpose,
          entries.length,
        ]),
      ),
      credentials: Object.fromEntries(
        Object.entries(context.modelKeys).map(([code, entries]) => [
          code,
          entries.length,
        ]),
      ),
      counts: context.catalogCounts,
      textIntegrity: context.catalogTextIntegrity,
    };
  }

  if (mode === "full") {
    seedMcpCatalog(context);
    summary.mcp = {
      serverId: CATALOG_IDS.mcpServerId,
      toolId: CATALOG_IDS.mcpToolId,
    };
  }

  if (needsAccounts) {
    writeState(context, accounts, summary.knowledge);
    summary.seed = context.seedOut;
  }

  console.log(JSON.stringify(summary, null, 2));
}

try {
  await main(process.argv.slice(2));
} catch (error) {
  console.error("[seed] failed:", error);
  process.exitCode = 1;
}
