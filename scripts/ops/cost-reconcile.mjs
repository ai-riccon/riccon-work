#!/usr/bin/env node
// M04 运维脚本：成本对账（API 聚合 vs 数据库快照；ADR-0022 §2.1–§2.5、ADR-0021 §10.2）。
//
// 口径（与 CostJdbc / PlatformCostJdbc 逐条对齐）：
// - 只统计终态调用（SUCCEEDED/FAILED/CANCELLED），跨度按 Beijing 自然日 [from, to]；
// - 金额只汇总 `cost_status='PRICED'` 且 `price_currency` 等于部署币种、`cost_day` 非空的行；
// - 调用量 = priced + currencyMismatch + unpriced + usageUnknown + unbucketed（互斥五类）；
// - 容差（验收口径）：金额差 0.00 CNY；调用量差 ≤0.1% 且差异必须解释（`--explain`）。
//
// 差异处理：
// - 金额按 2 位小数比较（CNY 最小单位），并在报告里同时给出 6 位小数原始差；
// - 调用量差 >0.1% 或有差异但未给 `--explain` 时退出 1；
// - 混合版本窗口（旧 Worker 不写 cost_day）会产生 unbucketedCalls，对账前必须用
//   `scripts/ops/backfill-cost-day.mjs` 回填，或在 `--explain` 里写明窗口。
//
// 用法：
//   node scripts/ops/cost-reconcile.mjs --api-base http://127.0.0.1:18080 --tenant tenant-alpha \
//     [--from 2026-11-01] [--to 2026-11-21] [--via compose|client] [--explain "窗口说明"]
//   node scripts/ops/cost-reconcile.mjs --self-test
//
// 凭据：`RICCON_RECONCILE_USERNAME` / `RICCON_RECONCILE_PASSWORD`（租户管理员，需 cost.read）。
// 退出码：0 在容差内；1 超容差或未解释；2 用法/环境错误。

import process from "node:process";
import { spawnSync } from "node:child_process";

import { ApiClient } from "../real-stack/lib/http.mjs";

const COMPOSE_FILE = "deploy/compose/docker-compose.test.yml";

/** 调用量容差：0.1%。 */
const CALL_RATIO_TOLERANCE = 0.001;

/** 用法。 */
function usage() {
  console.log(`用法：
  node scripts/ops/cost-reconcile.mjs --api-base <URL> --tenant <tenantId> [--from YYYY-MM-DD] [--to YYYY-MM-DD]
      [--via compose|client] [--explain "<差异说明>"] [--currency CNY]
  node scripts/ops/cost-reconcile.mjs --self-test
  node scripts/ops/cost-reconcile.mjs --help

环境变量：
  RICCON_RECONCILE_USERNAME / RICCON_RECONCILE_PASSWORD  租户管理员（cost.read）
  RICCON_DB_*（--via=client）

容差：金额差 0.00（按币种最小单位）；调用量差 ≤0.1% 且必须解释差异。
退出码：0 通过；1 超容差/未解释；2 用法或环境错误。`);
}

/**
 * 解析参数。
 *
 * @param {string[]} argv 命令行
 * @returns {object} 选项
 */
function parseArgs(argv) {
  const options = {
    apiBase: null,
    tenant: null,
    from: null,
    to: null,
    via: "compose",
    explain: null,
    currency: "CNY",
    selfTest: false,
    help: false,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    const next = () => {
      index += 1;
      if (index >= argv.length) throw usageError(`${arg} 缺少取值`);
      return argv[index];
    };
    if (arg === "--help" || arg === "-h") options.help = true;
    else if (arg === "--self-test") options.selfTest = true;
    else if (arg === "--api-base") options.apiBase = next();
    else if (arg.startsWith("--api-base=")) options.apiBase = arg.split("=")[1];
    else if (arg === "--tenant") options.tenant = next();
    else if (arg.startsWith("--tenant=")) options.tenant = arg.split("=")[1];
    else if (arg === "--from") options.from = next();
    else if (arg.startsWith("--from=")) options.from = arg.split("=")[1];
    else if (arg === "--to") options.to = next();
    else if (arg.startsWith("--to=")) options.to = arg.split("=")[1];
    else if (arg === "--via") options.via = next();
    else if (arg.startsWith("--via=")) options.via = arg.split("=")[1];
    else if (arg === "--explain") options.explain = next();
    else if (arg.startsWith("--explain=")) options.explain = arg.split("=")[1];
    else if (arg === "--currency") options.currency = next();
    else if (arg.startsWith("--currency="))
      options.currency = arg.split("=")[1];
    else throw usageError(`未知参数: ${arg}`);
  }
  if (!["compose", "client"].includes(options.via)) {
    throw usageError(`--via 只能是 compose 或 client，实际 ${options.via}`);
  }
  if (!/^[A-Z]{3}$/.test(options.currency)) {
    throw usageError("--currency 必须是 ISO 4217 三位大写代码");
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
 * 读取必需环境变量。
 *
 * @param {string} name 名称
 * @returns {string} 值
 */
function requiredEnv(name) {
  const value = process.env[name];
  if (!value) throw envError(`缺少环境变量 ${name}`);
  return value;
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
    maxBuffer: 16 * 1024 * 1024,
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
 * 北京时间日期 00:00 对应的 UTC 文本（用于 DATETIME 比较）。
 *
 * @param {string} date YYYY-MM-DD（北京时间自然日）
 * @param {number} plusDays 额外天数
 * @returns {string} `YYYY-MM-DD HH:MM:SS`
 */
function beijingStartUtc(date, plusDays = 0) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
    throw new Error(`日期必须是 YYYY-MM-DD: ${date}`);
  }
  const [year, month, day] = date.split("-").map(Number);
  const millis =
    Date.UTC(year, month - 1, day, 0, 0, 0) -
    8 * 3600 * 1000 +
    plusDays * 86400 * 1000;
  return new Date(millis).toISOString().slice(0, 19).replace("T", " ");
}

/**
 * 北京“今天”。
 *
 * @returns {string} YYYY-MM-DD
 */
function beijingToday() {
  return new Date(Date.now() + 8 * 3600 * 1000).toISOString().slice(0, 10);
}

/**
 * 金额字符串转微单位（1e-6）。
 *
 * @param {unknown} value 金额字符串（可能为 null）
 * @returns {bigint} 微单位
 */
function toMicros(value) {
  if (value === null || value === undefined || value === "") return 0n;
  const text = String(value).trim();
  const match = /^(-?)(\d+)(?:\.(\d{1,6}))?$/.exec(text);
  if (!match) throw new Error(`非法金额: ${text}`);
  const sign = match[1] === "-" ? -1n : 1n;
  const integer = BigInt(match[2]);
  const fraction = BigInt((match[3] ?? "").padEnd(6, "0"));
  return sign * (integer * 1_000_000n + fraction);
}

/**
 * 微单位转币种最小单位（2 位，HALF_UP）。
 *
 * @param {bigint} micros 微单位
 * @returns {string} 例如 `0.00`
 */
function microsToMinor(micros) {
  const negative = micros < 0n;
  const absolute = negative ? -micros : micros;
  const cents = (absolute + 5_000n) / 10_000n;
  const integer = cents / 100n;
  const fraction = String(cents % 100n).padStart(2, "0");
  return `${negative ? "-" : ""}${integer}.${fraction}`;
}

/**
 * 微单位转 6 位小数字符串（含符号）。
 *
 * @param {bigint} micros 微单位
 * @returns {string} 6 位小数
 */
function microsToText(micros) {
  const negative = micros < 0n;
  const absolute = negative ? -micros : micros;
  const integer = absolute / 1_000_000n;
  const fraction = String(absolute % 1_000_000n).padStart(6, "0");
  return `${negative ? "-" : ""}${integer}.${fraction}`;
}

/**
 * 调用量差异比例。
 *
 * @param {number} apiCalls API 调用量
 * @param {number} sqlCalls 数据库调用量
 * @returns {number} 比例（0 表示无差异）
 */
function callRatio(apiCalls, sqlCalls) {
  const diff = Math.abs(apiCalls - sqlCalls);
  if (diff === 0) return 0;
  return diff / Math.max(sqlCalls, 1);
}

/**
 * 读取数据库口径合计。
 *
 * @param {object} options 选项
 * @param {string} fromUtc 起始 UTC 文本（含）
 * @param {string} toUtc 结束 UTC 文本（不含）
 * @returns {object} 合计
 */
function sqlTotals(options, fromUtc, toUtc) {
  const sql = `
SELECT
  CAST(COALESCE(SUM(CASE WHEN mi.cost_day IS NOT NULL AND mi.cost_status = 'PRICED'
                          AND mi.price_currency = ${sqlString(options.currency)}
                         THEN mi.cost_amount ELSE 0 END), 0) AS CHAR) AS amount,
  COALESCE(SUM(CASE WHEN mi.cost_day IS NOT NULL AND mi.cost_status = 'PRICED'
                     AND mi.price_currency = ${sqlString(options.currency)}
                    THEN 1 ELSE 0 END), 0) AS priced_calls,
  COALESCE(SUM(CASE WHEN mi.cost_day IS NOT NULL AND mi.cost_status = 'UNPRICED'
                    THEN 1 ELSE 0 END), 0) AS unpriced_calls,
  COALESCE(SUM(CASE WHEN mi.cost_day IS NOT NULL AND mi.cost_status = 'USAGE_UNKNOWN'
                    THEN 1 ELSE 0 END), 0) AS usage_unknown_calls,
  COALESCE(SUM(CASE WHEN mi.cost_day IS NOT NULL AND mi.cost_status = 'PRICED'
                     AND (mi.price_currency IS NULL OR mi.price_currency <> ${sqlString(options.currency)})
                    THEN 1 ELSE 0 END), 0) AS currency_mismatch_calls,
  COALESCE(SUM(CASE WHEN mi.cost_day IS NULL THEN 1 ELSE 0 END), 0) AS unbucketed_calls
FROM model_invocation mi
WHERE mi.tenant_id = ${sqlString(options.tenant)}
  AND mi.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
  AND mi.started_at >= ${sqlString(fromUtc)}
  AND mi.started_at < ${sqlString(toUtc)};`;
  const line = runSql(sql, options).trim().split("\n").pop();
  const [amount, priced, unpriced, usageUnknown, currencyMismatch, unbucketed] =
    line.split("\t");
  return {
    amount,
    pricedCalls: Number(priced),
    unpricedCalls: Number(unpriced),
    usageUnknownCalls: Number(usageUnknown),
    currencyMismatchCalls: Number(currencyMismatch),
    unbucketedCalls: Number(unbucketed),
  };
}

/**
 * 自检：容差与格式纯函数。
 */
function selfTest() {
  if (beijingStartUtc("2026-11-21") !== "2026-11-20 16:00:00") {
    throw new Error("北京日 00:00 转 UTC 失败");
  }
  if (beijingStartUtc("2026-12-01") !== "2026-11-30 16:00:00") {
    throw new Error("月边界换算失败");
  }
  if (beijingStartUtc("2026-11-30", 1) !== "2026-11-30 16:00:00") {
    throw new Error("toExclusive 换算失败");
  }
  if (toMicros("0.003000") !== 3000n || toMicros(null) !== 0n) {
    throw new Error("金额转微单位失败");
  }
  if (microsToMinor(4000n) !== "0.00")
    throw new Error("0.004 应四舍五入为 0.00");
  if (microsToMinor(6000n) !== "0.01")
    throw new Error("0.006 应四舍五入为 0.01");
  if (microsToText(-1500n) !== "-0.001500") throw new Error("负数金额格式失败");
  if (callRatio(1000, 1000) !== 0) throw new Error("相同调用量比例应为 0");
  if (Math.abs(callRatio(1001, 1000) - 0.001) > 1e-12)
    throw new Error("0.1% 边界计算失败");
  if (callRatio(1002, 1000) <= CALL_RATIO_TOLERANCE)
    throw new Error("0.2% 应超过容差");
  console.log(
    JSON.stringify(
      {
        status: "passed",
        checks: [
          "beijing-boundaries",
          "micros-convert",
          "minor-rounding",
          "ratio-tolerance",
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
  if (!options.apiBase || !options.tenant) {
    throw usageError("需要 --api-base 与 --tenant");
  }
  const to = options.to ?? beijingToday();
  const from =
    options.from ??
    new Date(Date.parse(`${to}T00:00:00Z`) - 6 * 86400 * 1000)
      .toISOString()
      .slice(0, 10);
  const fromUtc = beijingStartUtc(from);
  const toUtc = beijingStartUtc(to, 1);

  const client = new ApiClient(options.apiBase);
  await client.login(
    options.tenant,
    requiredEnv("RICCON_RECONCILE_USERNAME"),
    requiredEnv("RICCON_RECONCILE_PASSWORD"),
  );
  const report = await client.get(
    `/api/v1/costs?from=${from}&to=${to}&groupBy=DAY&page=1&size=100`,
  );
  if (report.status !== 200) {
    throw new Error(`成本报告必须 200，实际 ${report.status} ${report.text}`);
  }
  if (report.data.currency !== options.currency) {
    throw new Error(
      `部署币种不一致: API=${report.data.currency} 期望=${options.currency}`,
    );
  }
  const apiTotals = report.data.totals;
  const apiCalls =
    apiTotals.pricedCalls +
    apiTotals.currencyMismatchCalls +
    apiTotals.unpricedCalls +
    apiTotals.usageUnknownCalls +
    apiTotals.unbucketedCalls;
  const sql = sqlTotals(options, fromUtc, toUtc);
  const sqlCalls =
    sql.pricedCalls +
    sql.currencyMismatchCalls +
    sql.unpricedCalls +
    sql.usageUnknownCalls +
    sql.unbucketedCalls;
  const amountDelta = toMicros(apiTotals.amount) - toMicros(sql.amount);
  const ratio = callRatio(apiCalls, sqlCalls);
  const failures = [];
  if (microsToMinor(amountDelta) !== "0.00") {
    failures.push(
      `金额差 ${microsToMinor(amountDelta)} ${options.currency} 超过 0.00 容差（原始差 ${microsToText(amountDelta)}）`,
    );
  }
  if (ratio > CALL_RATIO_TOLERANCE) {
    failures.push(
      `调用量差 ${(ratio * 100).toFixed(4)}% 超过 0.1%（api=${apiCalls} sql=${sqlCalls}）`,
    );
  }
  const hasDifference =
    microsToMinor(amountDelta) !== "0.00" || apiCalls !== sqlCalls;
  if (hasDifference && !options.explain) {
    failures.push("存在差异但未提供 --explain；差异必须解释并记录");
  }
  const result = {
    status: failures.length === 0 ? "passed" : "failed",
    tenant: options.tenant,
    range: { from, to, fromUtc, toUtc },
    currency: options.currency,
    api: { amount: apiTotals.amount, calls: apiCalls, ...apiTotals },
    sql: { amount: sql.amount, calls: sqlCalls, ...sql },
    delta: {
      amount: microsToText(amountDelta),
      amountMinor: microsToMinor(amountDelta),
      callRatio: Number(ratio.toFixed(6)),
    },
    explain: options.explain ?? null,
    failures,
  };
  console.log(JSON.stringify(result, null, 2));
  if (failures.length > 0) {
    process.exitCode = 1;
  }
}

main().catch((error) => {
  console.error(`[cost-reconcile] ${error.message}`);
  process.exitCode = error.exitCode ?? 1;
});
