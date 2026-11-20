#!/usr/bin/env node
// M04 运维脚本：分批回填 `model_invocation.cost_day`（ADR-0021 §10.2/§10.4、ADR-0022 §2.3）。
//
// 背景：V11 不回溯历史 cost_day（历史行为 NULL，成本报告计入 unbucketedCalls）。需要把历史调用
// 归入北京时间自然日时，用本脚本在维护窗口外分批执行，不阻塞写入、不重算金额、不改 cost_status。
//
// 语义：
// - cost_day = DATE(CONVERT_TZ(started_at, '+00:00', '+08:00'))：started_at 存 UTC，
//   北京时间自然日边界由显式偏移换算（不依赖 MySQL 时区表，也不受会话时区影响）；
// - 幂等：只更新 `cost_day IS NULL` 的行，重复执行第二次更新 0 行；
// - 默认 dry-run：只统计待回填行数与体积，不写库；
// - 分批：`--batch-size`（默认 5000）、`--max-batches`（0=直到完成）、`--pause-ms` 生产节流；
// - 失败或剩余行数不为 0 时退出非 0，不把“部分完成”当成完成。
//
// 用法：
//   node scripts/ops/backfill-cost-day.mjs [--dry-run] [--batch-size 5000] [--tenant tenant-alpha] [--via compose|client]
//   node scripts/ops/backfill-cost-day.mjs --self-test
//
// 退出码：0 完成；1 SQL/校验失败；2 用法或环境错误。

import process from "node:process";
import { spawnSync } from "node:child_process";

const COMPOSE_FILE = "deploy/compose/docker-compose.test.yml";

/** 北京时间相对 UTC 的偏移（小时）。 */
const SHANGHAI_OFFSET_HOURS = 8;

/** 用法。 */
function usage() {
  console.log(`用法：
  node scripts/ops/backfill-cost-day.mjs [--dry-run] [--batch-size <n>] [--max-batches <n>] [--tenant <id>]
  node scripts/ops/backfill-cost-day.mjs --self-test
  node scripts/ops/backfill-cost-day.mjs --help

选项：
  --dry-run         只统计待回填行数/表体积，不写库（默认安全模式）
  --apply           真正分批回填（与 --dry-run 互斥）
  --batch-size <n>  每批行数，默认 5000
  --max-batches <n> 最多执行批次数，0=直到完成（默认 0）
  --pause-ms <n>    批次间暂停毫秒，生产节流用，默认 0
  --tenant <id>     只回填指定租户（可选）
  --via compose|client  compose=测试栈容器；client=RICCON_DB_* + PATH 上的 mysql
  --self-test       纯静态自检（不连库）

判据：结束后 \`SELECT COUNT(*) ... WHERE cost_day IS NULL\` 必须为 0（或只剩被 --tenant 过滤之外的行）。
历史金额/价格/状态不被改写；只写 cost_day。`);
}

/**
 * 解析参数。
 *
 * @param {string[]} argv 命令行
 * @returns {object} 选项
 */
function parseArgs(argv) {
  const options = {
    apply: false,
    batchSize: 5000,
    maxBatches: 0,
    pauseMs: 0,
    tenant: null,
    via: "compose",
    selfTest: false,
    help: false,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    const nextNumber = (name) => {
      index += 1;
      const value = Number(argv[index]);
      if (!Number.isInteger(value) || value < 0)
        throw usageError(`${name} 必须是非负整数`);
      return value;
    };
    if (arg === "--help" || arg === "-h") options.help = true;
    else if (arg === "--self-test") options.selfTest = true;
    else if (arg === "--dry-run") options.apply = false;
    else if (arg === "--apply") options.apply = true;
    else if (arg === "--batch-size") {
      options.batchSize = nextNumber("--batch-size");
      if (options.batchSize === 0) throw usageError("--batch-size 必须大于 0");
    } else if (arg.startsWith("--batch-size="))
      options.batchSize = Number(arg.split("=")[1]);
    else if (arg === "--max-batches")
      options.maxBatches = nextNumber("--max-batches");
    else if (arg.startsWith("--max-batches="))
      options.maxBatches = Number(arg.split("=")[1]);
    else if (arg === "--pause-ms") options.pauseMs = nextNumber("--pause-ms");
    else if (arg.startsWith("--pause-ms="))
      options.pauseMs = Number(arg.split("=")[1]);
    else if (arg === "--tenant") {
      index += 1;
      options.tenant = argv[index];
    } else if (arg.startsWith("--tenant=")) options.tenant = arg.split("=")[1];
    else if (arg === "--via") {
      index += 1;
      options.via = argv[index];
    } else if (arg.startsWith("--via=")) options.via = arg.split("=")[1];
    else throw usageError(`未知参数: ${arg}`);
  }
  if (!["compose", "client"].includes(options.via)) {
    throw usageError(`--via 只能是 compose 或 client，实际 ${options.via}`);
  }
  if (!Number.isInteger(options.batchSize) || options.batchSize <= 0) {
    throw usageError("--batch-size 必须是正整数");
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

/** @param {string} value 文本 @returns {string} SQL 字面量 */
function sqlString(value) {
  return `'${String(value).replaceAll("\\", "\\\\").replaceAll("'", "''")}'`;
}

/**
 * 连接参数。
 *
 * @param {object} options 选项
 * @returns {object} 连接
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
    database: process.env.RICCON_DB_DATABASE ?? "riccon",
    password,
  };
}

/**
 * 执行 SQL。
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
  if (result.status !== 0) {
    throw new Error(
      `mysql 执行失败 (exit ${result.status}): ${(result.stderr || result.stdout || "").trim()}`,
    );
  }
  return result.stdout ?? "";
}

/**
 * 读取单值。
 *
 * @param {string} sql 查询
 * @param {object} options 选项
 * @returns {string} 值
 */
function queryValue(sql, options) {
  const lines = runSql(sql, options).trim().split("\n").filter(Boolean);
  return lines.length === 0 ? "" : lines[lines.length - 1];
}

/**
 * 北京时间自然日（纯函数，供自检核对 SQL 语义）。
 *
 * @param {string|number|Date} instant UTC 瞬时
 * @returns {string} YYYY-MM-DD
 */
function shanghaiDay(instant) {
  const millis = new Date(instant).getTime();
  if (Number.isNaN(millis)) throw new Error(`非法时间: ${instant}`);
  return new Date(millis + SHANGHAI_OFFSET_HOURS * 3600 * 1000)
    .toISOString()
    .slice(0, 10);
}

/**
 * 待回填统计。
 *
 * @param {object} options 选项
 * @returns {{pending: number, tableRows: number, dataLength: number, indexLength: number}} 统计
 */
function stats(options) {
  const tenantFilter = options.tenant
    ? ` AND tenant_id = ${sqlString(options.tenant)}`
    : "";
  const pending = Number(
    queryValue(
      `SELECT COUNT(*) FROM model_invocation WHERE cost_day IS NULL${tenantFilter};`,
      options,
    ),
  );
  const row = runSql(
    `SELECT table_rows, data_length, index_length
       FROM information_schema.tables
      WHERE table_schema = DATABASE() AND table_name = 'model_invocation';`,
    options,
  )
    .trim()
    .split("\n")
    .pop()
    .split("\t");
  return {
    pending,
    tableRows: Number(row[0] ?? 0),
    dataLength: Number(row[1] ?? 0),
    indexLength: Number(row[2] ?? 0),
  };
}

/**
 * 自检：北京日边界与 SQL 形态。
 */
function selfTest() {
  if (shanghaiDay("2026-11-30T15:59:59.999Z") !== "2026-11-30") {
    throw new Error("北京时间 23:59:59.999 应属于当日");
  }
  if (shanghaiDay("2026-11-30T16:00:00.000Z") !== "2026-12-01") {
    throw new Error("北京时间次日 00:00 应属于次日");
  }
  if (shanghaiDay("2026-01-01T00:00:00.000Z") !== "2026-01-01") {
    throw new Error("UTC 零点应属于北京时间当日 08:00");
  }
  const sql = buildUpdateSql({ batchSize: 10, tenant: "tenant-alpha" });
  for (const fragment of [
    "UPDATE model_invocation",
    "cost_day = DATE(CONVERT_TZ(started_at, '+00:00', '+08:00'))",
    "WHERE cost_day IS NULL",
    "ORDER BY started_at",
    "LIMIT 10",
    "tenant_id = 'tenant-alpha'",
  ]) {
    if (!sql.includes(fragment)) {
      throw new Error(`更新 SQL 缺少片段: ${fragment}`);
    }
  }
  console.log(
    JSON.stringify(
      {
        status: "passed",
        checks: ["shanghai-day-boundaries", "update-sql-shape"],
      },
      null,
      2,
    ),
  );
}

/**
 * 构造分批更新语句。
 *
 * @param {{batchSize: number, tenant?: string|null}} input 参数
 * @returns {string} SQL
 */
function buildUpdateSql(input) {
  const tenantFilter = input.tenant
    ? ` AND tenant_id = ${sqlString(input.tenant)}`
    : "";
  return `UPDATE model_invocation
   SET cost_day = DATE(CONVERT_TZ(started_at, '+00:00', '+08:00'))
 WHERE cost_day IS NULL${tenantFilter}
 ORDER BY started_at
 LIMIT ${input.batchSize};`;
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
  const initial = stats(options);
  console.log(
    JSON.stringify(
      {
        mode: options.apply ? "apply" : "dry-run",
        tenant: options.tenant ?? "all",
        pending: initial.pending,
        tableRows: initial.tableRows,
        dataMiB: Number((initial.dataLength / 1024 / 1024).toFixed(2)),
        indexMiB: Number((initial.indexLength / 1024 / 1024).toFixed(2)),
      },
      null,
      2,
    ),
  );
  if (!options.apply) {
    console.log(
      "[backfill-cost-day] dry-run：未写库；确认维护窗口后加 --apply",
    );
    return;
  }
  let batches = 0;
  let updated = 0;
  const startedAt = Date.now();
  while (true) {
    if (options.maxBatches > 0 && batches >= options.maxBatches) break;
    const sql = `${buildUpdateSql(options)}\nSELECT ROW_COUNT() AS updated;`;
    const affected = Number(queryValue(sql, options));
    batches += 1;
    updated += affected;
    const remaining = stats(options).pending;
    console.log(
      JSON.stringify({
        batch: batches,
        updated: affected,
        totalUpdated: updated,
        remaining,
        elapsedMs: Date.now() - startedAt,
      }),
    );
    if (affected === 0 || remaining === 0) break;
    if (options.pauseMs > 0) {
      // 生产节流：同步等待，避免批次连续打满 IO；仅影响本脚本进程。
      spawnSync("sleep", [String(options.pauseMs / 1000)], { stdio: "ignore" });
    }
  }
  const final = stats(options);
  const report = {
    status: final.pending === 0 ? "passed" : "failed",
    batches,
    rowsUpdated: updated,
    remaining: final.pending,
    elapsedMs: Date.now() - startedAt,
  };
  console.log(JSON.stringify(report, null, 2));
  if (final.pending !== 0) {
    process.exitCode = 1;
  }
}

try {
  main();
} catch (error) {
  console.error(`[backfill-cost-day] ${error.message}`);
  process.exitCode = error.exitCode ?? 1;
}
