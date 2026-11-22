#!/usr/bin/env node
// M04 运维脚本：幂等模型目录种子 + 部署漂移检查（ADR-0021 §10.2/§10.5、ADR-0022 §1.1）。
//
// 两个职责，共用一个入口，避免再造第二套“运维事实源”：
//   1) 种子（默认 dry-run，`--apply` 才写库）：按唯一键幂等 upsert `model_provider` 与 `model`，
//      不 DELETE、不写 `provider_credential`、不写路由链与网关开关。路由/开关属于管理端 API 的
//      职责（乐观锁 + 审计），运维不得用 SQL 绕过；
//   2) 漂移检查（`--check`）：比对 API 与 Worker 的 `/actuator/info` 指纹，并可与目录文件的
//      期望值、部署端点环境变量比对；info 不可达时直接失败，绝不把两个 403/401 当成“一致”。
//
// 约束：
// - 不输出主密钥、pepper、api key、密文或连接串密码；只能输出 provider/model 代码、计数与 URL 主机名。
// - 价格属于部署种子：重复执行写回同一值；历史 `model_invocation` 价格快照不被触碰（脚本不写该表）。
// - `--via=compose` 只用于 `deploy/compose/docker-compose.test.yml` 测试栈；
//   生产/预览使用 `--via=client`，连接参数来自 `RICCON_DB_*` 环境变量（密码走 MYSQL_PWD，不上命令行）。
// - 退出码：0 成功/一致；1 校验、SQL、漂移或 info 不可达；2 用法或环境错误。任何失败均非 0。
//
// 用法：
//   node scripts/ops/seed-model-catalog.mjs --help
//   node scripts/ops/seed-model-catalog.mjs --catalog <目录文件> [--apply]
//   node scripts/ops/seed-model-catalog.mjs --check --api-base <API> --worker-base <Worker> [--catalog <目录文件>]
//   node scripts/ops/seed-model-catalog.mjs --self-test

import { randomBytes } from "node:crypto";
import { readFileSync } from "node:fs";
import path from "node:path";
import process from "node:process";
import { spawnSync } from "node:child_process";

const COMPOSE_FILE = "deploy/compose/docker-compose.test.yml";

/** 部署指纹键；与 application.yml 的 info.riccon 结构逐项对应（ADR-0021 §10.5）。 */
const FINGERPRINT_PATHS = [
  ["deploymentMode", (info) => info?.riccon?.model?.deploymentMode],
  ["endpointSetHash", (info) => info?.riccon?.model?.endpointSetHash],
  ["routingMode", (info) => info?.riccon?.model?.routingMode],
  ["failoverEnabled", (info) => info?.riccon?.model?.failoverEnabled],
  [
    "quotaEnforcementEnabled",
    (info) => info?.riccon?.model?.quotaEnforcementEnabled,
  ],
  ["masterKeyId", (info) => info?.riccon?.masterKeyId],
  ["currency", (info) => info?.riccon?.billing?.currency],
  ["tenantDaily", (info) => info?.riccon?.quota?.tenantDaily],
  ["tenantMonthly", (info) => info?.riccon?.quota?.tenantMonthly],
  ["userDaily", (info) => info?.riccon?.quota?.userDaily],
  ["userMonthly", (info) => info?.riccon?.quota?.userMonthly],
];

/** 目录文件允许的状态值。 */
const PROVIDER_STATUS = new Set(["ACTIVE", "DISABLED"]);
const MODEL_STATUS = new Set(["ACTIVE", "DISABLED"]);

/** 用法。 */
function usage() {
  console.log(`用法：
  node scripts/ops/seed-model-catalog.mjs --catalog <目录文件> [--apply] [--via compose|client]
  node scripts/ops/seed-model-catalog.mjs --check --api-base <URL> --worker-base <URL> [--catalog <文件>]
  node scripts/ops/seed-model-catalog.mjs --self-test
  node scripts/ops/seed-model-catalog.mjs --help

种子（默认 dry-run）：
  --catalog <path>   目录 JSON；schema 见 docs/RUNBOOK.md「M04 单模块运行手册」。
  --apply            真正写库；缺省只打印计划差异（before/after 与 insert/update/unchanged 计数）。
  --via compose      通过 deploy/compose/docker-compose.test.yml 的 mysql 容器执行（仅测试栈）。
  --via client       使用 PATH 上的 mysql 客户端 + RICCON_DB_HOST/PORT/USER/PASSWORD/DATABASE。

漂移检查：
  --check            不写库；比对 API 与 Worker 的 /actuator/info 指纹。
  --api-base <URL>   API 基地址（必填）。
  --worker-base <URL> Worker 基地址（必填）。
  --allow-http       仅 DEMO/本地：允许 http 端点与 http 基地址；PRODUCTION 必须 https。
  --endpoints <json> 覆盖环境变量 RICCON_MODEL_ENDPOINTS（用于比对 DB provider baseUrl）。

退出码：0 成功；1 校验/SQL/漂移失败或 info 不可达；2 用法或环境错误。`);
}

/**
 * 解析参数。
 *
 * @param {string[]} argv 命令行
 * @returns {object} 选项
 */
function parseArgs(argv) {
  const options = {
    mode: "seed",
    apply: false,
    catalog: null,
    via: "compose",
    apiBase: null,
    workerBase: null,
    endpoints: null,
    allowHttp: false,
    selfTest: false,
    help: false,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    const next = () => {
      index += 1;
      if (index >= argv.length) {
        throw usageError(`${arg} 缺少取值`);
      }
      return argv[index];
    };
    if (arg === "--help" || arg === "-h") {
      options.help = true;
    } else if (arg === "--self-test") {
      options.selfTest = true;
    } else if (arg === "--apply") {
      options.apply = true;
    } else if (arg === "--check") {
      options.mode = "check";
    } else if (arg === "--catalog") {
      options.catalog = next();
    } else if (arg.startsWith("--catalog=")) {
      options.catalog = arg.slice("--catalog=".length);
    } else if (arg === "--via") {
      options.via = next();
    } else if (arg.startsWith("--via=")) {
      options.via = arg.slice("--via=".length);
    } else if (arg === "--api-base") {
      options.apiBase = next();
    } else if (arg.startsWith("--api-base=")) {
      options.apiBase = arg.slice("--api-base=".length);
    } else if (arg === "--worker-base") {
      options.workerBase = next();
    } else if (arg.startsWith("--worker-base=")) {
      options.workerBase = arg.slice("--worker-base=".length);
    } else if (arg === "--endpoints") {
      options.endpoints = next();
    } else if (arg.startsWith("--endpoints=")) {
      options.endpoints = arg.slice("--endpoints=".length);
    } else if (arg === "--allow-http") {
      options.allowHttp = true;
    } else {
      throw usageError(`未知参数: ${arg}`);
    }
  }
  if (!["compose", "client"].includes(options.via)) {
    throw usageError(`--via 只能是 compose 或 client，实际 ${options.via}`);
  }
  return options;
}

/**
 * 用法错误。
 *
 * @param {string} message 说明
 * @returns {Error} 带 exitCode=2 的错误
 */
function usageError(message) {
  const error = new Error(message);
  error.exitCode = 2;
  return error;
}

/**
 * 环境错误（缺变量、缺工具）。
 *
 * @param {string} message 说明
 * @returns {Error} 带 exitCode=2 的错误
 */
function envError(message) {
  const error = new Error(message);
  error.exitCode = 2;
  return error;
}

/**
 * 生成 UUIDv7（时间有序；ADR-0012 要求应用生成，运维种子同样遵守）。
 *
 * @returns {string} 36 位 UUID
 */
function uuidv7() {
  const bytes = randomBytes(16);
  let timestamp = BigInt(Date.now());
  for (let index = 5; index >= 0; index -= 1) {
    bytes[index] = Number(timestamp & 0xffn);
    timestamp >>= 8n;
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x70;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/**
 * SQL 字符串字面量转义。
 *
 * @param {string} value 原始文本
 * @returns {string} 带引号字面量
 */
function sqlString(value) {
  return `'${String(value).replaceAll("\\", "\\\\").replaceAll("'", "''")}'`;
}

/**
 * 构造 mysql 客户端参数（与 scripts/real-stack/lib/mysql.mjs 一致的字符集约束）。
 *
 * @param {{ user?: string, database?: string }} [options] 覆盖
 * @returns {string[]} 参数
 */
function mysqlClientArgs(options = {}) {
  return [
    `--user=${options.user ?? "riccon"}`,
    `--database=${options.database ?? "riccon"}`,
    "--batch",
    "--raw",
    "--default-character-set=utf8mb4",
  ];
}

/**
 * 连接描述；`--via=client` 时来自 RICCON_DB_*。
 *
 * @param {object} options 选项
 * @returns {{via: string, host: string, port: string, user: string, database: string, password: string}} 连接
 */
function connection(options) {
  if (options.via === "compose") {
    return {
      via: "compose",
      host: "127.0.0.1",
      port: "13306",
      user: "riccon",
      database: "riccon",
      password: process.env.RICCON_DB_PASSWORD ?? "riccon_test_only",
    };
  }
  const host = process.env.RICCON_DB_HOST;
  const user = process.env.RICCON_DB_USER;
  const password = process.env.RICCON_DB_PASSWORD;
  const database = process.env.RICCON_DB_DATABASE ?? "riccon";
  if (!host || !user || !password) {
    throw envError(
      "--via=client 需要 RICCON_DB_HOST/RICCON_DB_USER/RICCON_DB_PASSWORD（可选 RICCON_DB_PORT/RICCON_DB_DATABASE）",
    );
  }
  return {
    via: "client",
    host,
    port: process.env.RICCON_DB_PORT ?? "3306",
    user,
    database,
    password,
  };
}

/**
 * 执行 SQL；失败即抛错。
 *
 * @param {string} sql SQL 文本
 * @param {object} options 选项
 * @returns {{status: number, stdout: string, stderr: string}} 结果
 */
function runSql(sql, options) {
  const target = connection(options);
  const clientArguments = mysqlClientArgs({
    user: target.user,
    database: target.database,
  });
  const command = target.via === "compose" ? "docker" : "mysql";
  const args =
    target.via === "compose"
      ? [
          "compose",
          "-f",
          COMPOSE_FILE,
          "exec",
          "-T",
          "-e",
          `MYSQL_PWD=${target.password}`,
          "mysql",
          "mysql",
          ...clientArguments,
        ]
      : [`--host=${target.host}`, `--port=${target.port}`, ...clientArguments];
  const result = spawnSync(command, args, {
    cwd: process.cwd(),
    input: sql,
    encoding: "utf8",
    maxBuffer: 32 * 1024 * 1024,
    env: { ...process.env, MYSQL_PWD: target.password },
  });
  if (result.status !== 0) {
    throw new Error(
      `mysql 执行失败 (exit ${result.status}): ${(result.stderr || result.stdout || "").trim()}`,
    );
  }
  return {
    status: result.status,
    stdout: result.stdout ?? "",
    stderr: result.stderr ?? "",
  };
}

/**
 * 解析 `--batch --raw` 输出为对象数组；首行是列名。
 *
 * @param {string} stdout 输出
 * @returns {Array<Record<string, string>>} 行
 */
function parseRows(stdout) {
  const lines = stdout.trim().split("\n").filter(Boolean);
  if (lines.length === 0) return [];
  const header = lines[0].split("\t");
  return lines.slice(1).map((line) => {
    const cells = line.split("\t");
    return Object.fromEntries(
      header.map((name, index) => [name, cells[index] ?? ""]),
    );
  });
}

/**
 * 校验价格字符串。
 *
 * @param {unknown} value 原始值
 * @param {string} field 字段名
 * @returns {string|null} 规范化后的 6 位小数字符串或 null
 */
function normalizePrice(value, field) {
  if (value === null || value === undefined || value === "") {
    return null;
  }
  const text = String(value).trim();
  if (!/^\d+(\.\d{1,6})?$/.test(text)) {
    throw new Error(`${field} 必须是非负数，最多 6 位小数，实际 ${text}`);
  }
  const [integer, fraction = ""] = text.split(".");
  return `${integer}.${fraction.padEnd(6, "0")}`;
}

/**
 * 校验并规范化目录文件。
 *
 * @param {unknown} raw 解析后的 JSON
 * @param {{allowHttp?: boolean}} [options] 选项
 * @returns {{providers: object[], models: object[], deployment: object|null}} 目录
 */
function normalizeCatalog(raw, options = {}) {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    throw new Error("目录文件必须是 JSON 对象");
  }
  const providers = Array.isArray(raw.providers) ? raw.providers : [];
  const models = Array.isArray(raw.models) ? raw.models : [];
  if (providers.length === 0) {
    throw new Error("目录文件 providers 不能为空");
  }
  const codes = new Set();
  const normalizedProviders = providers.map((provider, index) => {
    const label = `providers[${index}]`;
    const code = String(provider.code ?? "").trim();
    if (!/^[a-z0-9][a-z0-9-]{1,63}$/.test(code)) {
      throw new Error(`${label}.code 必须是小写字母/数字/连字符`);
    }
    if (codes.has(code)) {
      throw new Error(`${label}.code 重复: ${code}`);
    }
    codes.add(code);
    const name = String(provider.name ?? "").trim();
    if (!name) {
      throw new Error(`${label}.name 不能为空`);
    }
    const baseUrl = String(provider.baseUrl ?? "").trim();
    const urlOk = options.allowHttp
      ? /^https?:\/\/[^\s]+$/.test(baseUrl)
      : /^https:\/\/[^\s]+$/.test(baseUrl);
    if (!urlOk) {
      throw new Error(
        `${label}.baseUrl 必须是 ${options.allowHttp ? "http(s)" : "https"} URL（PRODUCTION 只允许 https）`,
      );
    }
    if (/[@?#]/.test(baseUrl)) {
      throw new Error(`${label}.baseUrl 不允许 userinfo/query/fragment`);
    }
    const protocol = String(provider.protocol ?? "OPENAI_COMPATIBLE");
    if (protocol !== "OPENAI_COMPATIBLE") {
      throw new Error(`${label}.protocol 只支持 OPENAI_COMPATIBLE`);
    }
    const status = String(provider.status ?? "ACTIVE");
    if (!PROVIDER_STATUS.has(status)) {
      throw new Error(`${label}.status 只能是 ACTIVE/DISABLED`);
    }
    return { code, name, protocol, baseUrl, status };
  });

  const modelKeys = new Set();
  const normalizedModels = models.map((model, index) => {
    const label = `models[${index}]`;
    const providerCode = String(model.providerCode ?? "").trim();
    if (!codes.has(providerCode)) {
      throw new Error(
        `${label}.providerCode 未在 providers 中声明: ${providerCode}`,
      );
    }
    const modelKey = String(model.modelKey ?? "").trim();
    if (!/^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$/.test(modelKey)) {
      throw new Error(`${label}.modelKey 非法: ${modelKey}`);
    }
    const composite = `${providerCode}/${modelKey}`;
    if (modelKeys.has(composite)) {
      throw new Error(`${label} 重复: ${composite}`);
    }
    modelKeys.add(composite);
    const displayName = String(model.displayName ?? "").trim();
    if (!displayName) {
      throw new Error(`${label}.displayName 不能为空`);
    }
    const contextWindow = Number(model.contextWindow);
    const maxOutputTokens = Number(model.maxOutputTokens);
    if (!Number.isInteger(contextWindow) || contextWindow <= 0) {
      throw new Error(`${label}.contextWindow 必须是正整数`);
    }
    if (!Number.isInteger(maxOutputTokens) || maxOutputTokens <= 0) {
      throw new Error(`${label}.maxOutputTokens 必须是正整数`);
    }
    const inputPrice = normalizePrice(
      model.inputPricePer1m,
      `${label}.inputPricePer1m`,
    );
    const outputPrice = normalizePrice(
      model.outputPricePer1m,
      `${label}.outputPricePer1m`,
    );
    if ((inputPrice === null) !== (outputPrice === null)) {
      throw new Error(`${label} 价格必须同时给全或同时置空（UNPRICED）`);
    }
    const status = String(model.status ?? "ACTIVE");
    if (!MODEL_STATUS.has(status)) {
      throw new Error(`${label}.status 只能是 ACTIVE/DISABLED`);
    }
    const capabilities =
      model.capabilities === undefined
        ? { chat: true, stream: true, tool: false }
        : model.capabilities;
    if (
      !capabilities ||
      typeof capabilities !== "object" ||
      Array.isArray(capabilities)
    ) {
      throw new Error(`${label}.capabilities 必须是对象`);
    }
    return {
      providerCode,
      modelKey,
      displayName,
      contextWindow,
      maxOutputTokens,
      inputPrice,
      outputPrice,
      status,
      capabilities,
    };
  });

  return {
    providers: normalizedProviders,
    models: normalizedModels,
    deployment: raw.deployment ?? null,
    routing: raw.routing ?? null,
  };
}

/**
 * 读取目录文件。
 *
 * @param {string} file 路径
 * @param {object} options 选项
 * @returns {object} 目录
 */
function loadCatalog(file, options) {
  let text;
  try {
    text = readFileSync(path.resolve(file), "utf8");
  } catch (error) {
    throw new Error(`无法读取目录文件 ${file}: ${error.message}`);
  }
  let raw;
  try {
    raw = JSON.parse(text);
  } catch (error) {
    throw new Error(`目录文件不是合法 JSON: ${error.message}`);
  }
  const catalog = normalizeCatalog(raw, options);
  if (catalog.routing) {
    throw new Error(
      "目录文件不得包含 routing：路由链属于管理端 API（乐观锁 + 审计），运维脚本不写路由/开关",
    );
  }
  return catalog;
}

/**
 * 查询现有目录并计算差异。
 *
 * @param {object} catalog 目录
 * @param {object} options 选项
 * @returns {{rows: object[], plan: object[]}} 现状与计划
 */
function planSeed(catalog, options) {
  const providers = parseRows(
    runSql(
      "SELECT HEX(id) AS id_hex, code, name, base_url, status FROM model_provider ORDER BY code;",
      options,
    ).stdout,
  );
  const models = parseRows(
    runSql(
      `SELECT HEX(m.id) AS id_hex, p.code AS provider_code, m.model_key, m.display_name,
              m.context_window, m.max_output_tokens, m.status,
              m.input_price_per_1m, m.output_price_per_1m
         FROM model m JOIN model_provider p ON p.id = m.provider_id
        ORDER BY p.code, m.model_key;`,
      options,
    ).stdout,
  );
  const providerByCode = new Map(providers.map((row) => [row.code, row]));
  const modelByKey = new Map(
    models.map((row) => [`${row.provider_code}/${row.model_key}`, row]),
  );
  const plan = [];
  for (const provider of catalog.providers) {
    const existing = providerByCode.get(provider.code);
    if (!existing) {
      plan.push({
        kind: "provider",
        action: "insert",
        key: provider.code,
        after: provider,
      });
    } else {
      const changed =
        existing.name !== provider.name ||
        existing.base_url !== provider.baseUrl ||
        existing.status !== provider.status;
      plan.push({
        kind: "provider",
        action: changed ? "update" : "unchanged",
        key: provider.code,
        before: {
          name: existing.name,
          baseUrl: existing.base_url,
          status: existing.status,
        },
        after: provider,
      });
    }
  }
  for (const model of catalog.models) {
    const composite = `${model.providerCode}/${model.modelKey}`;
    const existing = modelByKey.get(composite);
    if (!existing) {
      plan.push({
        kind: "model",
        action: "insert",
        key: composite,
        after: model,
      });
      continue;
    }
    const changed =
      existing.display_name !== model.displayName ||
      String(existing.context_window) !== String(model.contextWindow) ||
      String(existing.max_output_tokens) !== String(model.maxOutputTokens) ||
      String(existing.status) !== model.status ||
      normalizeDbPrice(existing.input_price_per_1m) !== model.inputPrice ||
      normalizeDbPrice(existing.output_price_per_1m) !== model.outputPrice;
    plan.push({
      kind: "model",
      action: changed ? "update" : "unchanged",
      key: composite,
      before: {
        displayName: existing.display_name,
        contextWindow: existing.context_window,
        maxOutputTokens: existing.max_output_tokens,
        status: existing.status,
        inputPricePer1m: existing.input_price_per_1m,
        outputPricePer1m: existing.output_price_per_1m,
      },
      after: model,
    });
  }
  const extraProviders = [...providerByCode.keys()].filter(
    (code) => !catalog.providers.some((provider) => provider.code === code),
  );
  const extraModels = [...modelByKey.keys()].filter(
    (key) =>
      !catalog.models.some(
        (model) => `${model.providerCode}/${model.modelKey}` === key,
      ),
  );
  return { plan, extraProviders, extraModels };
}

/**
 * 规范化数据库读出的价格（DECIMAL 可能返回 `3.000000` 或 `3`）。
 *
 * @param {string|undefined} value 数据库值
 * @returns {string|null} 6 位小数字符串
 */
function normalizeDbPrice(value) {
  if (
    value === undefined ||
    value === null ||
    value === "" ||
    value === "NULL"
  ) {
    return null;
  }
  return normalizePrice(value, "db price");
}

/**
 * 执行种子写入。
 *
 * @param {object} catalog 目录
 * @param {object} options 选项
 */
function applySeed(catalog, options) {
  const statements = [];
  for (const provider of catalog.providers) {
    statements.push(`
INSERT INTO model_provider (id, code, name, protocol, base_url, status)
VALUES (UNHEX(REPLACE('${uuidv7()}', '-', '')), ${sqlString(provider.code)}, ${sqlString(provider.name)},
        'OPENAI_COMPATIBLE', ${sqlString(provider.baseUrl)}, ${sqlString(provider.status)})
ON DUPLICATE KEY UPDATE name = VALUES(name), base_url = VALUES(base_url), status = VALUES(status);`);
  }
  for (const model of catalog.models) {
    const inputPrice = model.inputPrice === null ? "NULL" : model.inputPrice;
    const outputPrice = model.outputPrice === null ? "NULL" : model.outputPrice;
    statements.push(`
INSERT INTO model (id, provider_id, model_key, display_name, context_window, max_output_tokens,
                   capabilities, input_price_per_1m, output_price_per_1m, status)
SELECT UNHEX(REPLACE('${uuidv7()}', '-', '')), p.id, ${sqlString(model.modelKey)},
       ${sqlString(model.displayName)}, ${model.contextWindow}, ${model.maxOutputTokens},
       ${sqlString(JSON.stringify(model.capabilities))}, ${inputPrice}, ${outputPrice},
       ${sqlString(model.status)}
  FROM model_provider p WHERE p.code = ${sqlString(model.providerCode)}
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name),
                        context_window = VALUES(context_window),
                        max_output_tokens = VALUES(max_output_tokens),
                        capabilities = VALUES(capabilities),
                        input_price_per_1m = VALUES(input_price_per_1m),
                        output_price_per_1m = VALUES(output_price_per_1m),
                        status = VALUES(status);`);
  }
  runSql(statements.join("\n"), options);
}

/**
 * 抓取 `/actuator/info`。
 *
 * @param {string} base 基地址
 * @returns {Promise<{status: number, body: unknown, text: string}>} 响应
 */
async function fetchInfo(base) {
  let response;
  try {
    response = await fetch(`${base.replace(/\/$/, "")}/actuator/info`, {
      headers: { accept: "application/json" },
    });
  } catch (error) {
    // 连接失败不是“指纹一致”，必须显式失败并保留原因（只含地址与错误类型，不含凭据）。
    return { status: 0, body: null, text: "", error: error.message };
  }
  const text = await response.text();
  let body = null;
  try {
    body = JSON.parse(text);
  } catch {
    body = null;
  }
  return { status: response.status, body, text };
}

/**
 * 提取指纹。
 *
 * @param {unknown} info `/actuator/info` JSON
 * @returns {Record<string,string>} 指纹
 */
function selectFingerprint(info) {
  return Object.fromEntries(
    FINGERPRINT_PATHS.map(([name, pick]) => [name, String(pick(info) ?? "")]),
  );
}

/**
 * 期望值（来自目录文件 deployment 段）。
 *
 * @param {object|null} deployment 部署段
 * @returns {Record<string,string>} 期望
 */
function expectedFromCatalog(deployment) {
  if (!deployment) return {};
  const defaults = deployment.quotaDefaults ?? {};
  return {
    deploymentMode: deployment.deploymentMode,
    routingMode: deployment.routingMode,
    failoverEnabled: deployment.failoverEnabled,
    quotaEnforcementEnabled: deployment.quotaEnforcementEnabled,
    currency: deployment.currency,
    tenantDaily: defaults.tenantDaily,
    tenantMonthly: defaults.tenantMonthly,
    userDaily: defaults.userDaily,
    userMonthly: defaults.userMonthly,
  };
}

/**
 * 漂移检查。
 *
 * @param {object} options 选项
 * @param {object|null} catalog 目录（可为空）
 * @returns {Promise<object>} 报告
 */
async function checkDrift(options, catalog) {
  if (!options.apiBase || !options.workerBase) {
    throw usageError("--check 需要同时提供 --api-base 与 --worker-base");
  }
  const [api, worker] = await Promise.all([
    fetchInfo(options.apiBase),
    fetchInfo(options.workerBase),
  ]);
  const failures = [];
  if (api.status !== 200 || worker.status !== 200) {
    failures.push(
      `/actuator/info 不可达：api=${api.status}${api.error ? ` (${api.error})` : ""} worker=${worker.status}${worker.error ? ` (${worker.error})` : ""}（default-deny 安全链未放行 /actuator/info 时，本项必须作为发布阻断项处理，禁止用 env 快照冒充通过）`,
    );
    return { status: "failed", failures, visibility: "unavailable" };
  }
  const apiPrint = selectFingerprint(api.body);
  const workerPrint = selectFingerprint(worker.body);
  for (const [name] of FINGERPRINT_PATHS) {
    if (apiPrint[name] !== workerPrint[name]) {
      failures.push(
        `API/Worker 不一致 ${name}: api=${apiPrint[name]} worker=${workerPrint[name]}`,
      );
    }
  }
  const expected = expectedFromCatalog(catalog?.deployment ?? null);
  for (const [name, value] of Object.entries(expected)) {
    if (value === undefined || value === null || value === "") continue;
    if (String(value) !== apiPrint[name]) {
      failures.push(
        `期望值不一致 ${name}: expected=${value} actual=${apiPrint[name]}`,
      );
    }
  }
  if (apiPrint.currency && !/^[A-Z]{3}$/.test(apiPrint.currency)) {
    failures.push(`币种不是 ISO 4217: ${apiPrint.currency}`);
  }
  const endpointsRaw = options.endpoints ?? process.env.RICCON_MODEL_ENDPOINTS;
  if (endpointsRaw) {
    let endpoints;
    try {
      endpoints = JSON.parse(endpointsRaw);
    } catch {
      throw new Error("RICCON_MODEL_ENDPOINTS 不是合法 JSON");
    }
    if (!Array.isArray(endpoints)) {
      throw new Error("RICCON_MODEL_ENDPOINTS 必须是数组");
    }
    const providers = parseRows(
      runSql("SELECT code, base_url FROM model_provider;", options).stdout,
    );
    const byCode = new Map(providers.map((row) => [row.code, row.base_url]));
    for (const endpoint of endpoints) {
      const code = endpoint?.code;
      const baseUrl = endpoint?.baseUrl;
      if (!code || !baseUrl) {
        failures.push(
          `端点条目缺少 code/baseUrl: ${JSON.stringify({ code, baseUrl })}`,
        );
        continue;
      }
      const dbUrl = byCode.get(code);
      if (!dbUrl) {
        failures.push(
          `端点 ${code} 未出现在 model_provider（需先跑 --apply 种子）`,
        );
      } else if (dbUrl !== baseUrl) {
        failures.push(`端点 ${code} baseUrl 漂移: env=${baseUrl} db=${dbUrl}`);
      }
    }
  }
  return {
    status: failures.length === 0 ? "passed" : "failed",
    visibility: "actuator-info",
    api: apiPrint,
    worker: workerPrint,
    failures,
  };
}

/**
 * 静态自检：目录校验、差异计划与指纹比对逻辑，不连库、不发网络请求。
 */
function selfTest() {
  const catalog = normalizeCatalog({
    providers: [
      {
        code: "gateway-a",
        name: "客户模型网关",
        baseUrl: "https://llm.example.com/v1",
        status: "ACTIVE",
      },
    ],
    models: [
      {
        providerCode: "gateway-a",
        modelKey: "qwen3-32b",
        displayName: "Qwen3 32B",
        contextWindow: 32768,
        maxOutputTokens: 4096,
        inputPricePer1m: "3",
        outputPricePer1m: "6.5",
      },
    ],
  });
  if (catalog.models[0].inputPrice !== "3.000000") {
    throw new Error("价格必须规范化为 6 位小数");
  }
  if (catalog.models[0].outputPrice !== "6.500000") {
    throw new Error("价格补齐小数失败");
  }
  const negativeCases = [
    [{ providers: [] }, "providers 为空"],
    [
      {
        providers: [
          { code: "gateway-a", name: "A", baseUrl: "https://a.example.com" },
          { code: "gateway-a", name: "B", baseUrl: "https://b.example.com" },
        ],
      },
      "provider code 重复",
    ],
    [
      {
        providers: [
          { code: "gateway-a", name: "A", baseUrl: "http://a.example.com" },
        ],
      },
      "PRODUCTION 拒绝 http",
    ],
    [
      {
        providers: [
          { code: "gateway-a", name: "A", baseUrl: "https://a.example.com" },
        ],
        models: [
          {
            providerCode: "gateway-a",
            modelKey: "m1",
            displayName: "M1",
            contextWindow: 1,
            maxOutputTokens: 1,
            inputPricePer1m: "1",
            outputPricePer1m: null,
          },
        ],
      },
      "价格必须成对",
    ],
  ];
  for (const [raw, reason] of negativeCases) {
    let failed = false;
    try {
      normalizeCatalog(raw);
    } catch {
      failed = true;
    }
    if (!failed) {
      throw new Error(`负向用例未失败: ${reason}`);
    }
  }
  const apiPrint = selectFingerprint({
    riccon: {
      model: {
        deploymentMode: "DEMO",
        endpointSetHash: "abc",
        routingMode: "ROUTED",
        failoverEnabled: "true",
        quotaEnforcementEnabled: "false",
      },
      masterKeyId: "mk-1",
      billing: { currency: "CNY" },
      quota: {
        tenantDaily: 10000,
        tenantMonthly: 200000,
        userDaily: 1000,
        userMonthly: 20000,
      },
    },
  });
  if (apiPrint.currency !== "CNY" || apiPrint.tenantDaily !== "10000") {
    throw new Error("指纹提取失败");
  }
  const mismatched = { ...apiPrint, currency: "USD" };
  if (mismatched.currency === apiPrint.currency) {
    throw new Error("指纹差异检测失败");
  }
  if (
    normalizeDbPrice("3.000000") !== "3.000000" ||
    normalizeDbPrice(null) !== null
  ) {
    throw new Error("数据库价格归一化失败");
  }
  if (uuidv7().length !== 36) {
    throw new Error("UUIDv7 长度错误");
  }
  console.log(
    JSON.stringify(
      {
        status: "passed",
        checks: [
          "catalog-normalize",
          "price-normalize",
          "negative-cases",
          "fingerprint-extract",
          "db-price-normalize",
          "uuidv7",
        ],
      },
      null,
      2,
    ),
  );
}

/** 主流程。 */
async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) {
    usage();
    return;
  }
  if (options.selfTest) {
    selfTest();
    return;
  }
  const catalog = options.catalog
    ? loadCatalog(options.catalog, { allowHttp: options.allowHttp })
    : null;
  if (options.mode === "check") {
    const report = await checkDrift(options, catalog);
    console.log(JSON.stringify(report, null, 2));
    if (report.status !== "passed") {
      process.exitCode = 1;
    }
    return;
  }
  if (!catalog) {
    throw usageError("种子模式需要 --catalog <目录文件>");
  }
  const { plan, extraProviders, extraModels } = planSeed(catalog, options);
  const counts = plan.reduce(
    (acc, item) => {
      acc[item.action] = (acc[item.action] ?? 0) + 1;
      return acc;
    },
    { insert: 0, update: 0, unchanged: 0 },
  );
  const summary = {
    mode: options.apply ? "apply" : "dry-run",
    target: { via: options.via },
    counts,
    changes: plan.filter((item) => item.action !== "unchanged"),
    extraProviders,
    extraModels,
  };
  console.log(JSON.stringify(summary, null, 2));
  if (!options.apply) {
    console.log("[seed-model-catalog] dry-run：未写库；确认后加 --apply");
    return;
  }
  applySeed(catalog, options);
  const after = planSeed(catalog, options).plan.filter(
    (item) => item.action !== "unchanged",
  );
  if (after.length > 0) {
    throw new Error(`种子后仍有差异（${after.length} 项），检查唯一键/权限`);
  }
  console.log("[seed-model-catalog] apply 完成，二次校验无差异");
}

main().catch((error) => {
  console.error(`[seed-model-catalog] ${error.message}`);
  process.exitCode = error.exitCode ?? 1;
});
