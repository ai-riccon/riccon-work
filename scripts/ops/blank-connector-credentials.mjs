#!/usr/bin/env node
// M05b O2b 运维脚本：连接器软删除 30 天后置空凭据密文并审计（ADR-0023 §6.4/§10）。
//
// 语义：
// - 只处理 `kb_connector.deleted_at IS NOT NULL AND deleted_at <= now - 30 天`
//   且仍有 `credential_ciphertext` 的连接器；
// - 置空 `credential_ciphertext` / `credential_key_id` / `credential_masked_hint` 三列，
//   并在**同一事务**追加 `knowledge.connector.credential.changed` 审计（actor=SYSTEM）；
// - 不触碰 ACTIVE/PAUSED/ERROR（未软删除）连接器，也不处理未满 30 天的软删除行；
// - 不读、不打印、不解密凭据明文；不动物文档正文、对象存储、连接器行本身。
//
// 边界（ADR-0023 §6.4）：
// - M05b 不提供在线清除端点；本脚本是 M09 维护任务的执行体，调度与生产物理清理由 M09 负责；
// - 置空不可逆：密文删除后只能由管理员重新录入源端凭据，不能靠备份里的旧密文恢复业务；
// - 审计 append-only（V6 触发器），脚本只 INSERT，不 UPDATE/DELETE 审计行。
//
// 安全与幂等：
// - 默认 dry-run（只读统计）；只有 `--apply` 才写库；
// - 条件更新：`credential_ciphertext IS NOT NULL` 保证重复 apply 不再改行、不再写审计；
// - 每批一个事务：逐行更新后仅当 ROW_COUNT()=1 才写审计；批末用 CHECK 约束断言
//   “审计条数 = 实际置空行数”，不一致即 SQL 报错回滚，绝不半写；
// - 分批判量上限：`--batch-size`（默认 100）× `--max-batches`（默认 100）= 最多 10 000 行/次，
//   超出留待下次运行，报告 `truncated=true`；
// - 退出码：0 成功；1 SQL/校验失败；2 用法或环境错误。
//
// 用法：
//   node scripts/ops/blank-connector-credentials.mjs
//   node scripts/ops/blank-connector-credentials.mjs --apply
//   node scripts/ops/blank-connector-credentials.mjs --apply --via=client --database riccon
//   node scripts/ops/blank-connector-credentials.mjs --self-test

import { createHash, randomBytes } from "node:crypto";
import process from "node:process";
import { spawnSync } from "node:child_process";

const COMPOSE_FILE = "deploy/compose/docker-compose.test.yml";

/** 合同 `data.retention` 与 ADR-0023 §6.4 的凭据恢复窗口（天）。 */
const DEFAULT_RETENTION_DAYS = 30;

/** 审计动作（ADR-0023 §5.6/§6.4：凭据写入或轮换；SYSTEM 置空复用同一动作）。 */
const AUDIT_ACTION = "knowledge.connector.credential.changed";

/** SYSTEM 维护任务的稳定 trace ID，用于区分管理员轮换（runbook 记录）。 */
const AUDIT_TRACE_ID = "connector-credential-blank";

/** 单次 dry-run 输出的 ID 样本上限。 */
const SAMPLE_LIMIT = 20;

/** 用法。 */
function usage() {
  console.log(`用法：
  node scripts/ops/blank-connector-credentials.mjs [--dry-run] [选项]
  node scripts/ops/blank-connector-credentials.mjs --apply [选项]
  node scripts/ops/blank-connector-credentials.mjs --self-test
  node scripts/ops/blank-connector-credentials.mjs --help

说明：
  把软删除已满 30 天、仍有 credential_ciphertext 的连接器三列置空，并在同事务写
  ${AUDIT_ACTION} 审计。默认 dry-run 只读统计，不做任何写入。

选项：
  --apply               执行置空与审计；缺省/--dry-run 只读
  --dry-run             显式只读（与 --apply 互斥）
  --retention-days <n>  恢复窗口天数，默认 ${DEFAULT_RETENTION_DAYS}
  --batch-size <n>      每批行数，默认 100
  --max-batches <n>     单次运行最多批数，默认 100（100 × 100 = 10 000 行上限）
  --via compose|client  DB 通道：compose=测试栈容器；client=RICCON_DB_* + PATH 上的 mysql
  --database <db>       覆盖数据库名（默认 riccon；演练可用 riccon_test_<ns>_api）
  --self-test           纯静态自检（不连库）`);
}

/**
 * 解析参数。
 *
 * @param {string[]} argv 命令行参数
 * @returns {object} 选项
 */
function parseArgs(argv) {
  const options = {
    apply: false,
    retentionDays: DEFAULT_RETENTION_DAYS,
    batchSize: 100,
    maxBatches: 100,
    via: "compose",
    database: null,
    selfTest: false,
    help: false,
  };
  let applySeen = false;
  let dryRunSeen = false;
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    const next = () => {
      index += 1;
      if (index >= argv.length) throw usageError(`${arg} 缺少取值`);
      return argv[index];
    };
    if (arg === "--help" || arg === "-h") options.help = true;
    else if (arg === "--self-test") options.selfTest = true;
    else if (arg === "--apply") {
      options.apply = true;
      applySeen = true;
    } else if (arg === "--dry-run") {
      options.apply = false;
      dryRunSeen = true;
    } else if (arg === "--retention-days")
      options.retentionDays = Number(next());
    else if (arg.startsWith("--retention-days="))
      options.retentionDays = Number(arg.split("=")[1]);
    else if (arg === "--batch-size") options.batchSize = Number(next());
    else if (arg.startsWith("--batch-size="))
      options.batchSize = Number(arg.split("=")[1]);
    else if (arg === "--max-batches") options.maxBatches = Number(next());
    else if (arg.startsWith("--max-batches="))
      options.maxBatches = Number(arg.split("=")[1]);
    else if (arg === "--via") options.via = next();
    else if (arg.startsWith("--via=")) options.via = arg.split("=")[1];
    else if (arg === "--database") options.database = next();
    else if (arg.startsWith("--database="))
      options.database = arg.split("=")[1];
    else throw usageError(`未知参数: ${arg}`);
  }
  if (applySeen && dryRunSeen) {
    throw usageError("--apply 与 --dry-run 不能同时使用");
  }
  if (!["compose", "client"].includes(options.via)) {
    throw usageError(`--via 只能是 compose 或 client，实际 ${options.via}`);
  }
  if (!Number.isInteger(options.retentionDays) || options.retentionDays <= 0) {
    throw usageError("--retention-days 必须是正整数");
  }
  if (!Number.isInteger(options.batchSize) || options.batchSize <= 0) {
    throw usageError("--batch-size 必须是正整数");
  }
  if (options.batchSize > 1000) {
    throw usageError("--batch-size 上限 1000（单事务过大易触发锁等待）");
  }
  if (!Number.isInteger(options.maxBatches) || options.maxBatches <= 0) {
    throw usageError("--max-batches 必须是正整数");
  }
  if (
    options.database !== null &&
    !/^[A-Za-z0-9_]{1,64}$/.test(options.database)
  ) {
    throw usageError("--database 只允许字母、数字与下划线");
  }
  return options;
}

/** @param {string} message 说明 @returns {Error} 用法错误 */
function usageError(message) {
  const error = new Error(message);
  error.exitCode = 2;
  return error;
}

/** @param {string} message 说明 @returns {Error} 环境错误 */
function envError(message) {
  const error = new Error(message);
  error.exitCode = 2;
  return error;
}

/**
 * 连接参数；与 `rewrap-credentials.mjs` 保持同一套 env。
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
      database: options.database ?? "riccon",
      password: process.env.RICCON_DB_PASSWORD ?? "riccon_test_only",
    };
  }
  const host = process.env.RICCON_DB_HOST;
  const user = process.env.RICCON_DB_USER;
  const password = process.env.RICCON_DB_PASSWORD;
  if (!host || !user || !password) {
    throw envError(
      "--via=client 需要 RICCON_DB_HOST/RICCON_DB_USER/RICCON_DB_PASSWORD",
    );
  }
  return {
    via: "client",
    host,
    port: process.env.RICCON_DB_PORT ?? "3306",
    user,
    database: options.database ?? process.env.RICCON_DB_DATABASE ?? "riccon",
    password,
  };
}

/**
 * 执行 SQL 脚本（单会话，支持多语句）。
 *
 * @param {string} sql SQL 文本
 * @param {object} options 选项
 * @returns {string} stdout
 */
function runSql(sql, options) {
  const target = connection(options);
  const clientArguments = [
    `--user=${target.user}`,
    `--database=${target.database}`,
    "--batch",
    "--raw",
    "--default-character-set=utf8mb4",
  ];
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
    input: sql,
    encoding: "utf8",
    maxBuffer: 32 * 1024 * 1024,
    env: { ...process.env, MYSQL_PWD: target.password },
  });
  if (result.error) {
    throw new Error(`无法启动 ${command}: ${result.error.message}`);
  }
  if (result.status !== 0) {
    throw new Error(
      `mysql 执行失败 (exit ${result.status}): ${(result.stderr || result.stdout || "").trim()}`,
    );
  }
  return result.stdout ?? "";
}

/**
 * 解析 batch 输出。
 *
 * @param {string} stdout 输出
 * @returns {Array<Record<string,string>>} 行
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

/** @param {string} value 文本 @returns {string} SQL 字面量 */
function sqlString(value) {
  return `'${String(value).replaceAll("\\", "\\\\").replaceAll("'", "''")}'`;
}

/**
 * 格式化为 MySQL `DATETIME(6)` 的 UTC 字面量（ADR-0008/0012：库内无时区，按 UTC 解释）。
 *
 * @param {Date} date 时间
 * @returns {string} `YYYY-MM-DD HH:mm:ss.ffffff`
 */
function sqlUtcTimestamp(date) {
  const iso = date.toISOString();
  const [day, rest] = iso.split("T");
  const [clock, millis] = rest.replace("Z", "").split(".");
  return `${day} ${clock}.${String(millis).padEnd(6, "0")}`;
}

/**
 * 计算恢复窗口截止时间；只有 `deleted_at <= cutoff` 才算到期。
 *
 * @param {Date} now 现在
 * @param {number} retentionDays 窗口天数
 * @returns {Date} 截止时间
 */
function cutoffOf(now, retentionDays) {
  return new Date(now.getTime() - retentionDays * 24 * 60 * 60 * 1000);
}

/**
 * BINARY(16) → UUID 文本。
 *
 * @param {string} hex 32 位十六进制
 * @returns {string} UUID
 */
function hexToUuid(hex) {
  const text = hex.toLowerCase();
  return `${text.slice(0, 8)}-${text.slice(8, 12)}-${text.slice(12, 16)}-${text.slice(16, 20)}-${text.slice(20)}`;
}

/**
 * 生成 UUIDv7（48 位毫秒时间戳 + version 7 + variant 10），与审计 `event_id` 约定一致（ADR-0012）。
 *
 * @param {number} timeMs 毫秒时间戳
 * @returns {{uuid: string, hex: string}} UUID 文本与 32 位十六进制
 */
function uuidv7(timeMs) {
  const bytes = randomBytes(16);
  const timestamp = BigInt(timeMs);
  for (let index = 0; index < 6; index += 1) {
    bytes[index] = Number((timestamp >> BigInt(8 * (5 - index))) & 0xffn);
  }
  bytes[6] = 0x70 | (bytes[6] & 0x0f);
  bytes[8] = 0x80 | (bytes[8] & 0x3f);
  const hex = Buffer.from(bytes).toString("hex");
  return { uuid: hexToUuid(hex), hex };
}

/**
 * batch 输出里的计数：NULL 在 `--raw` 下是字符串 `NULL`，统一归一为 0。
 *
 * @param {string|undefined} value 单元格
 * @returns {number} 计数
 */
function toCount(value) {
  const numeric = Number(value ?? 0);
  return Number.isFinite(numeric) ? numeric : 0;
}

/**
 * 读取四类行的计数与到期行样本（只读）。
 *
 * @param {object} options 选项
 * @param {Date} cutoff 截止时间
 * @returns {object} 统计
 */
function loadSummary(options, cutoff) {
  const cutoffSql = sqlString(sqlUtcTimestamp(cutoff));
  const stats =
    parseRows(
      runSql(
        `SELECT
          SUM(deleted_at IS NOT NULL AND deleted_at <= ${cutoffSql} AND credential_ciphertext IS NOT NULL) AS due_with_ciphertext,
          SUM(deleted_at IS NOT NULL AND deleted_at <= ${cutoffSql} AND credential_ciphertext IS NULL) AS due_without_ciphertext,
          SUM(deleted_at IS NOT NULL AND deleted_at > ${cutoffSql} AND credential_ciphertext IS NOT NULL) AS within_window_with_ciphertext,
          SUM(deleted_at IS NULL AND credential_ciphertext IS NOT NULL) AS active_with_ciphertext,
          COUNT(*) AS total
        FROM kb_connector;`,
        options,
      ),
    )[0] ?? {};
  const byTenant = parseRows(
    runSql(
      `SELECT tenant_id, COUNT(*) AS due
         FROM kb_connector
        WHERE deleted_at IS NOT NULL AND deleted_at <= ${cutoffSql} AND credential_ciphertext IS NOT NULL
        GROUP BY tenant_id
        ORDER BY tenant_id;`,
      options,
    ),
  ).map((row) => ({ tenantId: row.tenant_id, count: Number(row.due) }));
  const samples = parseRows(
    runSql(
      `SELECT tenant_id, HEX(id) AS id_hex
         FROM kb_connector
        WHERE deleted_at IS NOT NULL AND deleted_at <= ${cutoffSql} AND credential_ciphertext IS NOT NULL
        ORDER BY deleted_at, tenant_id, id
        LIMIT ${SAMPLE_LIMIT};`,
      options,
    ),
  ).map((row) => ({ tenantId: row.tenant_id, id: hexToUuid(row.id_hex) }));
  const dueWithCiphertext = toCount(stats.due_with_ciphertext);
  const digest = createHash("sha256")
    .update(
      samples.map((sample) => `${sample.tenantId}/${sample.id}`).join("\n"),
    )
    .digest("hex")
    .slice(0, 16);
  return {
    dueWithCiphertext,
    dueWithoutCiphertext: toCount(stats.due_without_ciphertext),
    withinWindowWithCiphertext: toCount(stats.within_window_with_ciphertext),
    activeWithCiphertext: toCount(stats.active_with_ciphertext),
    total: toCount(stats.total),
    byTenant,
    sampleIds: samples,
    sampleDigest: digest,
    sampleTruncated: dueWithCiphertext > samples.length,
  };
}

/**
 * 读取一批到期候选行（含审计需要的非敏感字段；不含密文）。
 *
 * @param {object} options 选项
 * @param {Date} cutoff 截止时间
 * @returns {Array<object>} 候选行
 */
function loadBatch(options, cutoff) {
  const cutoffSql = sqlString(sqlUtcTimestamp(cutoff));
  return parseRows(
    runSql(
      `SELECT tenant_id, HEX(id) AS id_hex, type,
              COALESCE(credential_key_id, '') AS credential_key_id,
              COALESCE(credential_masked_hint, '') AS credential_masked_hint
         FROM kb_connector
        WHERE deleted_at IS NOT NULL AND deleted_at <= ${cutoffSql} AND credential_ciphertext IS NOT NULL
        ORDER BY deleted_at, tenant_id, id
        LIMIT ${options.batchSize};`,
      options,
    ),
  ).map((row) => ({
    tenantId: row.tenant_id,
    idHex: row.id_hex,
    id: hexToUuid(row.id_hex),
    type: row.type,
    credentialKeyId: row.credential_key_id,
    maskedHint: row.credential_masked_hint,
  }));
}

/**
 * 构造一批“逐行条件更新 + 同事务审计 + 计数断言”的脚本。
 *
 * @param {Array<object>} rows 候选行
 * @param {Date} cutoff 截止时间
 * @param {Date} now 现在
 * @returns {string} SQL 脚本
 */
function buildBatchScript(rows, cutoff, now) {
  const cutoffSql = sqlString(sqlUtcTimestamp(cutoff));
  const nowSql = sqlString(sqlUtcTimestamp(now));
  const statements = [
    "START TRANSACTION;",
    "SET @total := 0;",
    "SET @audited := 0;",
  ];
  rows.forEach((row, index) => {
    const event = uuidv7(now.getTime());
    const details = JSON.stringify({
      connectorId: row.id,
      type: row.type,
      credentialKeyId: row.credentialKeyId ?? "",
      maskedHint: row.maskedHint ?? "",
    });
    statements.push(
      `UPDATE kb_connector
          SET credential_ciphertext = NULL,
              credential_key_id = NULL,
              credential_masked_hint = NULL
        WHERE tenant_id = ${sqlString(row.tenantId)}
          AND id = UNHEX('${row.idHex}')
          AND deleted_at IS NOT NULL
          AND deleted_at <= ${cutoffSql}
          AND credential_ciphertext IS NOT NULL;`,
      `SET @b${index} := ROW_COUNT();`,
      `SET @total := @total + @b${index};`,
      `INSERT INTO audit_event (
          event_id, occurred_at, tenant_id, actor_type, actor_id, action,
          target_type, target_id, outcome, trace_id, request_id, source_ip,
          details, schema_version
        )
        SELECT UNHEX('${event.hex}'), ${nowSql}, ${sqlString(row.tenantId)}, 'SYSTEM', 'system',
               '${AUDIT_ACTION}', 'KNOWLEDGE_CONNECTOR', ${sqlString(row.id)}, 'SUCCESS',
               '${AUDIT_TRACE_ID}', NULL, NULL, ${sqlString(details)}, 1
         WHERE @b${index} = 1;`,
      `SET @a${index} := ROW_COUNT();`,
      `SET @audited := @audited + @a${index};`,
    );
  });
  // 临时表 DDL 不触发隐式提交；CHECK 失败 → 语句报错 → mysql 非 0 退出 → 连接关闭回滚本批。
  statements.push(
    "CREATE TEMPORARY TABLE tmp_blank_credential_guard (ok TINYINT NOT NULL, CONSTRAINT chk_blank_credential_guard CHECK (ok = 1));",
    "INSERT INTO tmp_blank_credential_guard (ok) VALUES (@total = @audited);",
    "DROP TEMPORARY TABLE tmp_blank_credential_guard;",
    "SELECT @total AS processed, @audited AS auditEvents;",
    "COMMIT;",
  );
  return statements.join("\n");
}

/**
 * 纯静态自检：UUIDv7、截止时间、SQL 转义与脚本结构。
 */
function selfTest() {
  const now = new Date("2026-11-24T12:00:00.000Z");
  const cutoff = cutoffOf(now, DEFAULT_RETENTION_DAYS);
  if (cutoff.toISOString() !== "2026-10-25T12:00:00.000Z") {
    throw new Error(`30 天截止时间错误: ${cutoff.toISOString()}`);
  }
  if (sqlUtcTimestamp(cutoff) !== "2026-10-25 12:00:00.000000") {
    throw new Error(`DATETIME(6) 格式错误: ${sqlUtcTimestamp(cutoff)}`);
  }
  const event = uuidv7(now.getTime());
  if (
    !/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(
      event.uuid,
    )
  ) {
    throw new Error(`UUIDv7 格式错误: ${event.uuid}`);
  }
  if (event.uuid.replaceAll("-", "") !== event.hex || event.hex.length !== 32) {
    throw new Error("UUIDv7 hex 与文本不一致");
  }
  if (sqlString("a'b\\c") !== "'a''b\\\\c'") {
    throw new Error("SQL 转义错误");
  }
  const script = buildBatchScript(
    [
      {
        tenantId: "tenant-a",
        idHex: "0192f0a0000070008000000000000101",
        id: "0192f0a0-0000-7000-8000-000000000101",
        type: "S3",
        credentialKeyId: "mk-dek-1",
        maskedHint: "AKIA…7F2",
      },
    ],
    cutoff,
    now,
  );
  for (const marker of [
    "START TRANSACTION;",
    "credential_ciphertext = NULL",
    "deleted_at <= '2026-10-25 12:00:00.000000'",
    AUDIT_ACTION,
    "'SYSTEM', 'system'",
    AUDIT_TRACE_ID,
    "WHERE @b0 = 1;",
    "CHECK (ok = 1)",
    "COMMIT;",
  ]) {
    if (!script.includes(marker)) throw new Error(`批次脚本缺少: ${marker}`);
  }
  if (/credential_ciphertext\s*,\s*[^)]*UNHEX/.test(script)) {
    throw new Error("批次脚本不应读取密文列值");
  }
  console.log(
    JSON.stringify(
      {
        status: "passed",
        checks: [
          "cutoff-30d",
          "datetime6-utc",
          "uuidv7",
          "sql-escape",
          "batch-script-guard",
          "no-ciphertext-read",
        ],
      },
      null,
      2,
    ),
  );
}

/** 主流程。 */
function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) {
    usage();
    return;
  }
  if (options.selfTest) {
    selfTest();
    return;
  }
  const now = new Date();
  const cutoff = cutoffOf(now, options.retentionDays);
  const report = {
    mode: options.apply ? "apply" : "dry-run",
    database: connection(options).database,
    retentionDays: options.retentionDays,
    now: now.toISOString(),
    cutoff: cutoff.toISOString(),
    batchSize: options.batchSize,
    maxBatches: options.maxBatches,
  };
  const before = loadSummary(options, cutoff);
  report.before = before;
  if (!options.apply) {
    report.status = "ok";
    console.log(JSON.stringify(report, null, 2));
    return;
  }
  let processed = 0;
  let auditEvents = 0;
  let batches = 0;
  while (batches < options.maxBatches) {
    const rows = loadBatch(options, cutoff);
    if (rows.length === 0) break;
    const [summary] = parseRows(
      runSql(buildBatchScript(rows, cutoff, now), options),
    );
    const changed = Number(summary?.processed);
    const audited = Number(summary?.auditEvents);
    if (!Number.isInteger(changed) || !Number.isInteger(audited)) {
      throw new Error("批处理未返回计数；事务已回滚，请检查数据库");
    }
    if (changed !== audited) {
      // SQL 侧 CHECK 断言应先拦住；走到这里说明结果不可信，立即失败。
      throw new Error(
        `审计与置空行数不一致：processed=${changed} auditEvents=${audited}`,
      );
    }
    processed += changed;
    auditEvents += audited;
    batches += 1;
    if (rows.length < options.batchSize) break;
  }
  const after = loadSummary(options, cutoff);
  report.batches = batches;
  report.processed = processed;
  report.auditEvents = auditEvents;
  report.after = after;
  report.truncated =
    after.dueWithCiphertext > 0 && batches >= options.maxBatches;
  report.status = "ok";
  console.log(JSON.stringify(report, null, 2));
  if (report.truncated) {
    console.warn(
      `[blank-connector-credentials] 达到单次上限 ${options.maxBatches} 批，仍有 ${after.dueWithCiphertext} 行到期；请再次运行或调高 --max-batches`,
    );
  }
}

try {
  main();
} catch (error) {
  console.error(`[blank-connector-credentials] ${error.message}`);
  process.exitCode = error.exitCode ?? 1;
}
