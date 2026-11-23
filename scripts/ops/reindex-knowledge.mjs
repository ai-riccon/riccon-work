#!/usr/bin/env node
// M05 运维脚本：知识库索引 v1→v2 重灌与别名切换（ADR-0023 §7.1、IT-KB-113）。
//
// 做什么：
//   1) 按 `knowledge_document.status='INDEXED'` 列出目标文档（可按租户筛选）；
//   2) 为每个文档写入一条 `KNOWLEDGE_INDEX_REQUESTED` outbox 事件（mode=FULL、
//      reason=BACKFILL_V2、幂等键 doc:{id}:index:{attempt}），由 Worker 重建 MySQL 分块并写
//      版本化索引；
//   3) 等待这些事件全部 DONE（DEAD 或无进展即失败）；
//   4) 对比 MySQL 分块数与目标版本索引文档数/分块 ID，确认重灌无重复、无缺失；
//   5) 逐个租户把别名 `riccon-kb-{tenant}` 原子切到目标版本（默认 v2）；`--index-version v1`
//      可回切旧索引，v1 保留供回滚。
//
// 前置条件：
//   - Worker 已用 `RICCON_OPENSEARCH_INDEX_VERSION=<目标版本>` 重启，否则事件写入的是旧版本索引；
//      脚本在切换前会校验目标索引存在，不存在即失败，不会切到空索引；
//   - Worker 正在运行（heavy 通道消费 KNOWLEDGE_INDEX）；脚本只写 outbox，不直接写业务表；
//   - `--via=compose` 需要测试栈 docker compose；`--via=client` 需要 PATH 上的 mysql 与
//      RICCON_DB_HOST/PORT/USER/PASSWORD；OpenSearch 由 RICCON_OPENSEARCH_URL 指定。
//
// 安全与边界：
// - 默认 dry-run：不加 `--apply` 只打印计划和 SQL 数量，不写库、不改别名；
// - 不打印数据库密码、OpenSearch 凭据或文档正文；报告只含 ID/计数/耗时；
// - 跨租户隔离：索引名/别名由租户 ID 派生，SQL 全部带 tenant_id；脚本拒绝非法租户字符；
// - `--alias-only` 只做别名切换/回切（不写事件），用于回滚演练。
//
// 用法：
//   node scripts/ops/reindex-knowledge.mjs --tenant tenant-a --apply
//   node scripts/ops/reindex-knowledge.mjs --all-tenants --apply --index-version v2
//   node scripts/ops/reindex-knowledge.mjs --tenant tenant-a --apply --alias-only --index-version v1
//   node scripts/ops/reindex-knowledge.mjs --self-test
//
// 退出码：0 成功；1 校验/等待失败；2 用法/环境不完整。

import { randomUUID } from "node:crypto";
import process from "node:process";
import { spawnSync } from "node:child_process";

const COMPOSE_FILE = "deploy/compose/docker-compose.test.yml";
const EVENT_TYPE = "riccon.knowledge.index.requested.v1";
const INDEX_VERSION_PATTERN = /^v[0-9]+$/;
const TENANT_PATTERN = /^[A-Za-z0-9_-]{1,64}$/;

/** 用法。 */
function usage() {
  console.log(`用法：
  node scripts/ops/reindex-knowledge.mjs --tenant <id> [--tenant <id>...] --apply [选项]
  node scripts/ops/reindex-knowledge.mjs --all-tenants --apply [选项]
  node scripts/ops/reindex-knowledge.mjs --tenant <id> --apply --alias-only --index-version v1
  node scripts/ops/reindex-knowledge.mjs --self-test
  node scripts/ops/reindex-knowledge.mjs --help

选项：
  --tenant <id>            目标租户，可重复；与 --all-tenants 二选一
  --all-tenants            处理所有存在 INDEXED 文档的租户
  --apply                  执行写库/等待/切别名；缺省只打印计划
  --index-version <v>      目标索引版本，默认 v2；回切旧索引用 v1
  --attempt <n>            幂等尝试号，默认按本次运行时间派生；同一 attempt 重跑不会重复入队
  --batch-size <n>         每批写入的事件数，默认 200
  --via compose|client     数据库通道，默认 compose（测试栈）
  --timeout <ms>           等待事件完成的总超时，默认 90 分钟
  --poll-interval <ms>     轮询间隔，默认 5000
  --alias-only             不写事件，只校验目标索引并切换别名（回滚/演练）
  --no-switch-alias        只重灌不切别名（后续手工切换）
  --max-verify-chunks <n>  逐块 ID 对比上限，默认 50000；超出时只比计数
  --report <path>          报告 JSON 路径；缺省只打印
  --self-test              纯静态自检（不连库/不连 OpenSearch）`);
}

/**
 * 解析命令行。
 *
 * @param {string[]} argv 参数
 * @returns {object} 选项
 */
function parseArgs(argv) {
  const options = {
    tenants: [],
    allTenants: false,
    apply: false,
    indexVersion: "v2",
    attempt: null,
    batchSize: 200,
    via: "compose",
    timeoutMs: 90 * 60 * 1000,
    pollIntervalMs: 5000,
    aliasOnly: false,
    switchAlias: true,
    maxVerifyChunks: 50000,
    report: null,
    selfTest: false,
    help: false,
  };
  for (let index = 0; index < argv.length; index++) {
    const arg = argv[index];
    const next = () => {
      const value = argv[++index];
      if (value === undefined) {
        throw usageError(`参数 ${arg} 缺少值`);
      }
      return value;
    };
    switch (arg) {
      case "--tenant":
        options.tenants.push(next());
        break;
      case "--all-tenants":
        options.allTenants = true;
        break;
      case "--apply":
        options.apply = true;
        break;
      case "--index-version":
        options.indexVersion = next();
        break;
      case "--attempt":
        options.attempt = Number(next());
        break;
      case "--batch-size":
        options.batchSize = Number(next());
        break;
      case "--via":
        options.via = next();
        break;
      case "--timeout":
        options.timeoutMs = Number(next());
        break;
      case "--poll-interval":
        options.pollIntervalMs = Number(next());
        break;
      case "--alias-only":
        options.aliasOnly = true;
        break;
      case "--no-switch-alias":
        options.switchAlias = false;
        break;
      case "--max-verify-chunks":
        options.maxVerifyChunks = Number(next());
        break;
      case "--report":
        options.report = next();
        break;
      case "--self-test":
        options.selfTest = true;
        break;
      case "--help":
        options.help = true;
        break;
      default:
        throw usageError(`未知参数：${arg}`);
    }
  }
  if (options.help) {
    return options;
  }
  if (options.selfTest) {
    return options;
  }
  if (!INDEX_VERSION_PATTERN.test(options.indexVersion)) {
    throw usageError(
      `--index-version 必须是 v1/v2 形式，实际 ${options.indexVersion}`,
    );
  }
  if (options.via !== "compose" && options.via !== "client") {
    throw usageError(`--via 只能是 compose|client，实际 ${options.via}`);
  }
  if (
    !Number.isInteger(options.batchSize) ||
    options.batchSize < 1 ||
    options.batchSize > 1000
  ) {
    throw usageError("--batch-size 必须是 1–1000 的整数");
  }
  if (!Number.isFinite(options.timeoutMs) || options.timeoutMs < 1000) {
    throw usageError("--timeout 必须 >= 1000ms");
  }
  if (
    !Number.isFinite(options.pollIntervalMs) ||
    options.pollIntervalMs < 100
  ) {
    throw usageError("--poll-interval 必须 >= 100ms");
  }
  if (options.allTenants && options.tenants.length > 0) {
    throw usageError("--tenant 与 --all-tenants 不能同时使用");
  }
  if (!options.allTenants && options.tenants.length === 0) {
    throw usageError("必须提供 --tenant 或 --all-tenants");
  }
  for (const tenant of options.tenants) {
    if (!TENANT_PATTERN.test(tenant)) {
      throw usageError(`非法租户 ID：${tenant}`);
    }
  }
  if (options.attempt === null) {
    // 默认用秒级时间戳，既满足正整数字段，又让每次重灌天然使用新的幂等键。
    options.attempt = Math.floor(Date.now() / 1000);
  }
  if (!Number.isInteger(options.attempt) || options.attempt < 1) {
    throw usageError("--attempt 必须是正整数");
  }
  return options;
}

/**
 * 构造用法错误。
 *
 * @param {string} message 原因
 * @returns {Error} 带退出码的错误
 */
function usageError(message) {
  const error = new Error(message);
  error.exitCode = 2;
  return error;
}

/**
 * 连接参数：compose 走测试栈，client 走 RICCON_DB_*。
 *
 * @param {object} options 选项
 * @returns {object} 连接参数
 */
function connection(options) {
  if (options.via === "compose") {
    return {
      via: "compose",
      host: "127.0.0.1",
      port: "13306",
      user: "riccon",
      password: process.env.RICCON_DB_PASSWORD ?? "riccon_test_only",
      database: process.env.RICCON_DB_NAME ?? "riccon",
    };
  }
  const host = process.env.RICCON_DB_HOST;
  const user = process.env.RICCON_DB_USER;
  const password = process.env.RICCON_DB_PASSWORD;
  if (!host || !user || !password) {
    throw usageError(
      "--via=client 需要 RICCON_DB_HOST/RICCON_DB_USER/RICCON_DB_PASSWORD",
    );
  }
  return {
    via: "client",
    host,
    port: process.env.RICCON_DB_PORT ?? "3306",
    user,
    password,
    database: process.env.RICCON_DB_NAME ?? "riccon",
  };
}

/**
 * 执行一条或多条 SQL，返回 stdout。
 *
 * @param {string} sql SQL 文本
 * @param {object} target 连接参数
 * @returns {string} stdout
 */
function runSql(sql, target) {
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
          `--user=${target.user}`,
          "--batch",
          "--raw",
          "--default-character-set=utf8mb4",
          target.database,
        ]
      : [
          `--host=${target.host}`,
          `--port=${target.port}`,
          `--user=${target.user}`,
          "--batch",
          "--raw",
          "--default-character-set=utf8mb4",
          target.database,
        ];
  const result = spawnSync(command, args, {
    input: sql,
    encoding: "utf8",
    maxBuffer: 128 * 1024 * 1024,
    env: { ...process.env, MYSQL_PWD: target.password },
  });
  if (result.error) {
    throw usageError(`无法执行数据库客户端：${result.error.message}`);
  }
  if (result.status !== 0) {
    throw new Error(
      `mysql 执行失败 (exit ${result.status}): ${(result.stderr || "").trim()}`,
    );
  }
  return result.stdout ?? "";
}

/**
 * 转义 SQL 字符串字面量。
 *
 * @param {string} value 原值
 * @returns {string} 单引号包裹的字面量
 */
function sqlString(value) {
  return `'${String(value).replaceAll("\\", "\\\\").replaceAll("'", "''")}'`;
}

/**
 * UUID 字符串转 BINARY(16) 的 SQL 表达式。
 *
 * @param {string} uuid UUID
 * @returns {string} SQL 表达式
 */
function binaryUuid(uuid) {
  return `UNHEX(${sqlString(uuid.replaceAll("-", ""))})`;
}

/**
 * BINARY(16) 的 HEX 输出转 UUID。
 *
 * @param {string} hex 32 位十六进制
 * @returns {string} UUID
 */
function hexToUuid(hex) {
  const value = (hex ?? "").toLowerCase();
  if (!/^[0-9a-f]{32}$/.test(value)) {
    throw new Error(`非法 BINARY(16) HEX：${hex}`);
  }
  return `${value.slice(0, 8)}-${value.slice(8, 12)}-${value.slice(12, 16)}-${value.slice(
    16,
    20,
  )}-${value.slice(20)}`;
}

/**
 * 构造索引事件幂等键（ADR-0023 §7.3）。
 *
 * @param {string} documentId 文档 ID
 * @param {number} attempt 尝试号
 * @returns {string} 幂等键
 */
function idempotencyKey(documentId, attempt) {
  return `doc:${documentId}:index:${attempt}`;
}

/**
 * 构造重灌事件 payload（不含正文/对象 key/凭据）。
 *
 * @param {{documentId: string, fileId: string|null, aclVersion: number}} document 文档
 * @param {number} attempt 尝试号
 * @returns {object} payload
 */
function payloadFor(document, attempt) {
  const payload = {
    documentId: document.documentId,
    attempt,
    mode: "FULL",
    force: false,
    aclVersion: document.aclVersion,
    reason: "BACKFILL_V2",
  };
  if (document.fileId) {
    payload.fileId = document.fileId;
  }
  return payload;
}

/**
 * 构造别名原子切换动作；已指向目标时返回空数组。
 *
 * @param {string[]} previous 当前目标
 * @param {string} target 目标索引
 * @param {string} alias 别名
 * @returns {object[]} _aliases actions
 */
function aliasActions(previous, target, alias) {
  const actions = [];
  for (const index of previous) {
    if (index !== target) {
      actions.push({ remove: { index, alias } });
    }
  }
  if (!previous.includes(target)) {
    actions.push({ add: { index: target, alias } });
  }
  return actions;
}

/**
 * 解析 mysql --batch 输出为二维数组（制表符分隔）。
 *
 * @param {string} stdout 输出
 * @returns {string[][]} 行
 */
function parseRows(stdout) {
  return stdout
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.length > 0 && line !== "NULL")
    .map((line) => line.split("\t"));
}

/**
 * OpenSearch 请求。
 *
 * @param {string} baseUrl 基础地址
 * @param {string} path 路径
 * @param {object} options fetch 选项
 * @returns {Promise<{status: number, body: any}>} 响应
 */
async function openSearch(baseUrl, path, options = {}) {
  const headers = {
    "Content-Type": "application/json",
    ...(options.headers ?? {}),
  };
  const username = process.env.RICCON_OPENSEARCH_USERNAME;
  const password = process.env.RICCON_OPENSEARCH_PASSWORD;
  if (username && password) {
    headers.Authorization = `Basic ${Buffer.from(`${username}:${password}`).toString("base64")}`;
  }
  const response = await fetch(`${baseUrl}${path}`, { ...options, headers });
  const text = await response.text();
  let body = null;
  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      body = text;
    }
  }
  return { status: response.status, body };
}

/**
 * 读取租户的 INDEXED 文档。
 *
 * @param {object} target 连接参数
 * @param {string} tenant 租户
 * @returns {Array<{documentId: string, fileId: string|null, aclVersion: number}>} 文档
 */
function loadDocuments(target, tenant) {
  const sql = `
SELECT HEX(id), IFNULL(HEX(file_id), ''), acl_version
  FROM knowledge_document
 WHERE tenant_id = ${sqlString(tenant)}
   AND status = 'INDEXED'
 ORDER BY created_at, id;
`;
  return parseRows(runSql(sql, target)).map(([id, fileId, aclVersion]) => ({
    documentId: hexToUuid(id),
    fileId: fileId ? hexToUuid(fileId) : null,
    aclVersion: Number(aclVersion),
  }));
}

/**
 * 列出有 INDEXED 文档的租户。
 *
 * @param {object} target 连接参数
 * @returns {string[]} 租户
 */
function loadTenants(target) {
  const sql = `
SELECT DISTINCT tenant_id
  FROM knowledge_document
 WHERE status = 'INDEXED'
 ORDER BY tenant_id;
`;
  return parseRows(runSql(sql, target)).map(([tenant]) => tenant);
}

/**
 * 为租户寻找一个启用账号（agent_run.user_id 外键必需）。
 *
 * @param {object} target 连接参数
 * @param {string} tenant 租户
 * @returns {string|null} 用户 UUID
 */
function loadActor(target, tenant) {
  const sql = `
SELECT HEX(id)
  FROM app_user
 WHERE tenant_id = ${sqlString(tenant)}
   AND status = 'ACTIVE'
 ORDER BY created_at, id
 LIMIT 1;
`;
  const rows = parseRows(runSql(sql, target));
  return rows.length === 0 ? null : hexToUuid(rows[0][0]);
}

/**
 * 查询已存在的幂等键，保证同一 attempt 重跑不重复入队。
 *
 * @param {object} target 连接参数
 * @param {string} tenant 租户
 * @param {string[]} keys 幂等键
 * @returns {Set<string>} 已存在键
 */
function existingKeys(target, tenant, keys) {
  if (keys.length === 0) {
    return new Set();
  }
  const sql = `
SELECT idempotency_key
  FROM agent_run_outbox
 WHERE tenant_id = ${sqlString(tenant)}
   AND idempotency_key IN (${keys.map(sqlString).join(", ")});
`;
  return new Set(parseRows(runSql(sql, target)).map(([key]) => key));
}

/**
 * 写入一批重灌事件。
 *
 * @param {object} target 连接参数
 * @param {string} tenant 租户
 * @param {string} actorId 发起人 UUID
 * @param {Array<object>} documents 文档
 * @param {number} attempt 尝试号
 * @returns {string[]} 新写入的幂等键
 */
function enqueueBatch(target, tenant, actorId, documents, attempt) {
  const keys = documents.map((document) =>
    idempotencyKey(document.documentId, attempt),
  );
  const existing = existingKeys(target, tenant, keys);
  const pending = documents.filter(
    (document) => !existing.has(idempotencyKey(document.documentId, attempt)),
  );
  if (pending.length === 0) {
    return [];
  }
  const statements = ["START TRANSACTION;"];
  for (const document of pending) {
    const runId = randomUUID();
    const eventId = randomUUID();
    const key = idempotencyKey(document.documentId, attempt);
    const payload = JSON.stringify(payloadFor(document, attempt));
    statements.push(`
INSERT INTO agent_run
    (tenant_id, id, kind, user_id, status, attempt, max_attempts, trace_id, created_at, updated_at)
VALUES
    (${sqlString(tenant)}, ${binaryUuid(runId)}, 'KNOWLEDGE_INDEX', ${binaryUuid(actorId)},
     'PENDING', 1, 3, 'reindex-knowledge-v2', NOW(6), NOW(6));
INSERT INTO agent_run_outbox
    (tenant_id, id, run_id, event_type, event_version, idempotency_key, payload, status, attempts, available_at)
VALUES
    (${sqlString(tenant)}, ${binaryUuid(eventId)}, ${binaryUuid(runId)}, ${sqlString(EVENT_TYPE)},
     1, ${sqlString(key)}, ${sqlString(payload)}, 'PENDING', 0, NOW(6));
`);
  }
  statements.push("COMMIT;");
  runSql(statements.join("\n"), target);
  return pending.map((document) =>
    idempotencyKey(document.documentId, attempt),
  );
}

/**
 * 等待事件全部完成。
 *
 * @param {object} target 连接参数
 * @param {string} tenant 租户
 * @param {string[]} keys 幂等键
 * @param {object} options 选项
 * @returns {Promise<object>} 状态计数
 */
async function waitForEvents(target, tenant, keys, options) {
  const deadline = Date.now() + options.timeoutMs;
  const keyList = keys.map(sqlString).join(", ");
  let last = {};
  while (Date.now() <= deadline) {
    const sql = `
SELECT status, COUNT(*)
  FROM agent_run_outbox
 WHERE tenant_id = ${sqlString(tenant)}
   AND idempotency_key IN (${keyList})
 GROUP BY status;
`;
    last = Object.fromEntries(
      parseRows(runSql(sql, target)).map(([status, count]) => [
        status,
        Number(count),
      ]),
    );
    const dead = (last.DEAD ?? 0) + (last.UNKNOWN ?? 0);
    const done = last.DONE ?? 0;
    if (dead > 0) {
      throw new Error(
        `租户 ${tenant} 有 ${dead} 个重灌事件进入 DEAD，停止且不切别名`,
      );
    }
    if (done >= keys.length) {
      return last;
    }
    await sleep(options.pollIntervalMs);
  }
  throw new Error(
    `等待租户 ${tenant} 重灌超时（${options.timeoutMs}ms），最后状态 ${JSON.stringify(last)}`,
  );
}

/**
 * 睡眠。
 *
 * @param {number} ms 毫秒
 * @returns {Promise<void>} 完成
 */
function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * 校验 MySQL 分块与目标版本索引一致。
 *
 * @param {object} target 连接参数
 * @param {string} tenant 租户
 * @param {string} indexName 目标索引
 * @param {object} options 选项
 * @returns {Promise<object>} 校验结果
 */
async function verifyIndex(target, tenant, indexName, options) {
  const sql = `
SELECT COUNT(*), COUNT(DISTINCT document_id)
  FROM knowledge_chunk
 WHERE tenant_id = ${sqlString(tenant)};
`;
  const [[chunkCountRaw, documentCountRaw]] = parseRows(runSql(sql, target));
  const chunkCount = Number(chunkCountRaw);
  const documentCount = Number(documentCountRaw);
  const baseUrl = openSearchBaseUrl();
  const countResponse = await openSearch(baseUrl, `/${indexName}/_count`, {
    method: "POST",
    body: JSON.stringify({ query: { term: { tenant_id: tenant } } }),
  });
  if (countResponse.status !== 200) {
    throw new Error(`读取 ${indexName} 计数失败：HTTP ${countResponse.status}`);
  }
  const indexedCount = Number(countResponse.body?.count ?? -1);
  const result = {
    chunkCount,
    documentCount,
    indexedCount,
    idsCompared: false,
  };
  if (indexedCount !== chunkCount) {
    throw new Error(
      `租户 ${tenant} 分块数不一致：MySQL=${chunkCount}，${indexName}=${indexedCount}`,
    );
  }
  if (chunkCount > 0 && chunkCount <= options.maxVerifyChunks) {
    const idsSql = `
SELECT HEX(id)
  FROM knowledge_chunk
 WHERE tenant_id = ${sqlString(tenant)};
`;
    const mysqlIds = new Set(
      parseRows(runSql(idsSql, target)).map(([id]) => hexToUuid(id)),
    );
    const search = await openSearch(baseUrl, `/${indexName}/_search`, {
      method: "POST",
      body: JSON.stringify({
        size: options.maxVerifyChunks,
        _source: false,
        query: { term: { tenant_id: tenant } },
      }),
    });
    if (search.status !== 200) {
      throw new Error(`读取 ${indexName} 分块 ID 失败：HTTP ${search.status}`);
    }
    const indexedIds = new Set(
      (search.body?.hits?.hits ?? []).map((hit) => hit._id),
    );
    const missing = [...mysqlIds].filter((id) => !indexedIds.has(id));
    const extra = [...indexedIds].filter((id) => !mysqlIds.has(id));
    if (missing.length > 0 || extra.length > 0) {
      throw new Error(
        `租户 ${tenant} 分块 ID 不一致：missing=${missing.length} extra=${extra.length}（示例 ${(missing[0] ?? extra[0]) || "-"}）`,
      );
    }
    result.idsCompared = true;
  }
  return result;
}

/**
 * 切换租户别名并校验结果。
 *
 * @param {string} tenant 租户
 * @param {string} indexVersion 目标版本
 * @param {boolean} apply 是否实际执行
 * @returns {Promise<object>} 切换结果
 */
async function switchAlias(tenant, indexVersion, apply) {
  const prefix = process.env.RICCON_OPENSEARCH_INDEX_PREFIX ?? "riccon-kb";
  const alias = `${prefix}-${tenant.toLowerCase()}`;
  const target = `${prefix}-${indexVersion}-${tenant.toLowerCase()}`;
  const baseUrl = openSearchBaseUrl();
  const head = await openSearch(baseUrl, `/${target}`, { method: "HEAD" });
  if (head.status !== 200) {
    throw new Error(
      `目标索引不存在：${target}（Worker 是否已按 index-version=${indexVersion} 重启？）`,
    );
  }
  const currentResponse = await openSearch(baseUrl, `/_alias/${alias}`, {
    method: "GET",
  });
  const previous =
    currentResponse.status === 404
      ? []
      : Object.keys(currentResponse.body ?? {}).filter(
          (key) => !key.startsWith("_"),
        );
  const actions = aliasActions(previous, target, alias);
  if (!apply || actions.length === 0) {
    return { alias, target, previous, actions, applied: false };
  }
  const response = await openSearch(baseUrl, "/_aliases", {
    method: "POST",
    body: JSON.stringify({ actions }),
  });
  if (response.status !== 200) {
    throw new Error(
      `别名切换失败：HTTP ${response.status} ${JSON.stringify(response.body)}`,
    );
  }
  const after = await openSearch(baseUrl, `/_alias/${alias}`, {
    method: "GET",
  });
  const targets =
    after.status === 404
      ? []
      : Object.keys(after.body ?? {}).filter((key) => !key.startsWith("_"));
  if (targets.length !== 1 || targets[0] !== target) {
    throw new Error(`别名切换后目标异常：${JSON.stringify(targets)}`);
  }
  return { alias, target, previous, actions, applied: true, targets };
}

/**
 * OpenSearch 基础地址。
 *
 * @returns {string} 地址
 */
function openSearchBaseUrl() {
  return (
    process.env.RICCON_OPENSEARCH_URL ?? "http://127.0.0.1:19200"
  ).replace(/\/$/, "");
}

/**
 * 主流程。
 *
 * @param {object} options 选项
 * @returns {Promise<object>} 报告
 */
async function run(options) {
  const target = connection(options);
  const tenants = options.allTenants ? loadTenants(target) : options.tenants;
  const report = {
    at: new Date().toISOString(),
    apply: options.apply,
    indexVersion: options.indexVersion,
    attempt: options.attempt,
    aliasOnly: options.aliasOnly,
    via: options.via,
    tenants: [],
    failures: [],
  };
  if (tenants.length === 0) {
    report.note = "没有需要重灌的 INDEXED 文档租户";
    return report;
  }
  for (const tenant of tenants) {
    if (!TENANT_PATTERN.test(tenant)) {
      throw new Error(`数据库返回非法租户 ID：${tenant}`);
    }
    const tenantReport = { tenant };
    report.tenants.push(tenantReport);
    try {
      if (!options.aliasOnly) {
        const actorId = loadActor(target, tenant);
        if (!actorId) {
          throw new Error(
            `租户 ${tenant} 没有 ACTIVE 用户，无法创建 agent_run（FK）`,
          );
        }
        const documents = loadDocuments(target, tenant);
        tenantReport.documents = documents.length;
        if (!options.apply) {
          tenantReport.plannedEvents = documents.length;
          continue;
        }
        const enqueued = [];
        for (
          let start = 0;
          start < documents.length;
          start += options.batchSize
        ) {
          const batch = documents.slice(start, start + options.batchSize);
          enqueued.push(
            ...enqueueBatch(target, tenant, actorId, batch, options.attempt),
          );
        }
        tenantReport.enqueued = enqueued.length;
        if (documents.length > 0) {
          // 等待本 attempt 的全部幂等键（含上次运行已存在的），避免只等新事件而漏掉在途重灌。
          const keys = documents.map((document) =>
            idempotencyKey(document.documentId, options.attempt),
          );
          tenantReport.outbox = await waitForEvents(
            target,
            tenant,
            keys,
            options,
          );
          tenantReport.verification = await verifyIndex(
            target,
            tenant,
            `${process.env.RICCON_OPENSEARCH_INDEX_PREFIX ?? "riccon-kb"}-${options.indexVersion}-${tenant.toLowerCase()}`,
            options,
          );
        }
      }
      if (options.switchAlias) {
        tenantReport.alias = await switchAlias(
          tenant,
          options.indexVersion,
          options.apply,
        );
      }
    } catch (error) {
      tenantReport.error = error.message;
      report.failures.push({ tenant, error: error.message });
    }
  }
  return report;
}

/**
 * 静态自检：只验证纯函数，不连任何服务。
 *
 * @returns {number} 退出码
 */
function selfTest() {
  const assert = (condition, message) => {
    if (!condition) {
      throw new Error(`self-test failed: ${message}`);
    }
  };
  assert(idempotencyKey("d1", 3) === "doc:d1:index:3", "幂等键格式");
  const payload = payloadFor(
    { documentId: "d1", fileId: "f1", aclVersion: 4 },
    7,
  );
  assert(
    payload.mode === "FULL" && payload.reason === "BACKFILL_V2",
    "payload 模式/原因",
  );
  assert(
    payload.attempt === 7 &&
      payload.aclVersion === 4 &&
      payload.fileId === "f1",
    "payload 字段",
  );
  const withoutFile = payloadFor(
    { documentId: "d1", fileId: null, aclVersion: 1 },
    1,
  );
  assert(!("fileId" in withoutFile), "SEED 文档不写 fileId");
  assert(
    aliasActions(["riccon-kb-v1-t"], "riccon-kb-v2-t", "riccon-kb-t").length ===
      2,
    "v1→v2 需要先移除再添加",
  );
  assert(
    aliasActions(["riccon-kb-v2-t"], "riccon-kb-v2-t", "riccon-kb-t").length ===
      0,
    "已指向目标时不产生动作",
  );
  assert(
    hexToUuid("00112233445566778899aabbccddeeff") ===
      "00112233-4455-6677-8899-aabbccddeeff",
    "HEX→UUID",
  );
  assert(sqlString("a'b") === "'a''b'", "SQL 转义");
  assert(
    TENANT_PATTERN.test("tenant-a_1") && !TENANT_PATTERN.test("tenant/a"),
    "租户字符守卫",
  );
  const parsed = parseArgs([
    "--tenant",
    "tenant-a",
    "--apply",
    "--index-version",
    "v2",
  ]);
  assert(
    parsed.tenants[0] === "tenant-a" &&
      parsed.apply &&
      parsed.indexVersion === "v2",
    "参数解析",
  );
  console.log("reindex-knowledge self-test: OK");
  return 0;
}

// 脚本入口：--help 与 --self-test 不连外部服务。
try {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) {
    usage();
    process.exit(0);
  }
  if (options.selfTest) {
    process.exit(selfTest());
  }
  if (!options.apply) {
    console.log("dry-run：未加 --apply，只打印计划（不写库、不改别名）。");
  }
  const report = await run(options);
  if (options.report) {
    const { writeFileSync, mkdirSync } = await import("node:fs");
    const path = await import("node:path");
    mkdirSync(path.dirname(options.report), { recursive: true });
    writeFileSync(
      options.report,
      `${JSON.stringify(report, null, 2)}\n`,
      "utf8",
    );
    console.log(`报告：${options.report}`);
  }
  console.log(JSON.stringify(report, null, 2));
  process.exit(report.failures.length === 0 ? 0 : 1);
} catch (error) {
  console.error(error.message);
  process.exit(error.exitCode ?? 1);
}
