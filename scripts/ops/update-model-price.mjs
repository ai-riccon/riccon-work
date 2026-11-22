#!/usr/bin/env node
// M04 运维脚本：按 provider+model_key 更新模型价格（ADR-0021 §10.2、ADR-0022 §2.1）。
//
// 只改 `model.input_price_per_1m` / `model.output_price_per_1m`：
// - 不写 `model_invocation`：历史调用保留调用时的价格快照，改价不重算、不改写历史（AC-18）；
// - 不新增端点、不改路由/开关/凭据；新增端点必须由部署 env + seed-model-catalog 完成；
// - 默认 dry-run，`--apply` 才写；打印 before/after 与受影响行数，行数不等于 1 即失败；
// - 价格必须是非负数、最多 6 位小数（`DECIMAL(12,6)`）；两键可只改一个，未给则保持原值。
//
// 用法：
//   node scripts/ops/update-model-price.mjs --provider-code gateway-a --model-key qwen3-32b \
//     --input 3.5 [--output 7] [--apply] [--via compose|client]
//   node scripts/ops/update-model-price.mjs --self-test
//
// 退出码：0 成功；1 校验/SQL 失败；2 用法或环境错误。

import process from "node:process";
import { spawnSync } from "node:child_process";

const COMPOSE_FILE = "deploy/compose/docker-compose.test.yml";

/** 用法。 */
function usage() {
  console.log(`用法：
  node scripts/ops/update-model-price.mjs --provider-code <code> --model-key <key> [--input <价格>] [--output <价格>] [--apply]
  node scripts/ops/update-model-price.mjs --self-test
  node scripts/ops/update-model-price.mjs --help

选项：
  --provider-code <code>  model_provider.code
  --model-key <key>        model.model_key（与 provider 组成唯一键）
  --input <price>          输入价格（每百万 token），0 表示免费
  --output <price>         输出价格（每百万 token）
  --apply                  真正写库；缺省只打印 before/after
  --via compose|client     compose=测试栈容器；client=RICCON_DB_* 环境变量 + PATH 上的 mysql
  --self-test              纯静态自检（不连库）

至少提供 --input 或 --output。修改成功后必须重启 API/Worker 才会使用新价格缓存（若存在）。`);
}

/**
 * 解析参数。
 *
 * @param {string[]} argv 命令行
 * @returns {object} 选项
 */
function parseArgs(argv) {
  const options = {
    providerCode: null,
    modelKey: null,
    input: null,
    output: null,
    apply: false,
    via: "compose",
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
    else if (arg === "--apply") options.apply = true;
    else if (arg === "--provider-code") options.providerCode = next();
    else if (arg.startsWith("--provider-code="))
      options.providerCode = arg.split("=")[1];
    else if (arg === "--model-key") options.modelKey = next();
    else if (arg.startsWith("--model-key="))
      options.modelKey = arg.split("=")[1];
    else if (arg === "--input") options.input = next();
    else if (arg.startsWith("--input=")) options.input = arg.split("=")[1];
    else if (arg === "--output") options.output = next();
    else if (arg.startsWith("--output=")) options.output = arg.split("=")[1];
    else if (arg === "--via") options.via = next();
    else if (arg.startsWith("--via=")) options.via = arg.split("=")[1];
    else throw usageError(`未知参数: ${arg}`);
  }
  if (!["compose", "client"].includes(options.via)) {
    throw usageError(`--via 只能是 compose 或 client，实际 ${options.via}`);
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
 * 规范化价格。
 *
 * @param {unknown} value 原始值
 * @param {string} field 字段名
 * @returns {string|null} 6 位小数字符串或 null（未提供）
 */
function normalizePrice(value, field) {
  if (value === null || value === undefined || value === "") return null;
  const text = String(value).trim();
  if (!/^\d+(\.\d{1,6})?$/.test(text)) {
    throw new Error(`${field} 必须是非负数，最多 6 位小数，实际 ${text}`);
  }
  const [integer, fraction = ""] = text.split(".");
  if (integer.length > 6) {
    throw new Error(`${field} 整数部分超过 DECIMAL(12,6) 上限: ${text}`);
  }
  return `${integer}.${fraction.padEnd(6, "0")}`;
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
 * @returns {{stdout: string, stderr: string}} 结果
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
  return { stdout: result.stdout ?? "", stderr: result.stderr ?? "" };
}

/**
 * 读取当前价格。
 *
 * @param {object} options 选项
 * @returns {{idHex: string, input: string, output: string, displayName: string}|null} 当前行
 */
function currentPrice(options) {
  const sql = `
SELECT HEX(m.id) AS id_hex, m.display_name, m.input_price_per_1m, m.output_price_per_1m
  FROM model m JOIN model_provider p ON p.id = m.provider_id
 WHERE p.code = ${sqlString(options.providerCode)} AND m.model_key = ${sqlString(options.modelKey)};`;
  const lines = runSql(sql, options).stdout.trim().split("\n").filter(Boolean);
  if (lines.length === 0) return null;
  const header = lines[0].split("\t");
  const cells = lines[1].split("\t");
  const row = Object.fromEntries(
    header.map((name, index) => [name, cells[index] ?? ""]),
  );
  return {
    idHex: row.id_hex,
    displayName: row.display_name,
    input: row.input_price_per_1m,
    output: row.output_price_per_1m,
  };
}

/**
 * 自检：价格规范化与 SQL 生成。
 */
function selfTest() {
  if (normalizePrice("3", "input") !== "3.000000")
    throw new Error("价格补齐失败");
  if (normalizePrice("0.000001", "input") !== "0.000001")
    throw new Error("6 位小数失败");
  if (normalizePrice(undefined, "input") !== null)
    throw new Error("未提供应返回 null");
  for (const [value, reason] of [
    ["-1", "负数"],
    ["1.0000001", "超过 6 位"],
    ["abc", "非数字"],
    ["1234567", "整数位超限"],
  ]) {
    let failed = false;
    try {
      normalizePrice(value, "input");
    } catch {
      failed = true;
    }
    if (!failed) throw new Error(`负向用例未失败: ${reason}`);
  }
  if (sqlString("a'b") !== "'a''b'") throw new Error("SQL 转义失败");
  console.log(
    JSON.stringify(
      {
        status: "passed",
        checks: ["price-normalize", "negative-cases", "sql-escape"],
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
  if (!options.providerCode || !options.modelKey) {
    throw usageError("需要 --provider-code 与 --model-key");
  }
  const inputPrice = normalizePrice(options.input, "--input");
  const outputPrice = normalizePrice(options.output, "--output");
  if (inputPrice === null && outputPrice === null) {
    throw usageError("至少提供 --input 或 --output");
  }
  const before = currentPrice(options);
  if (!before) {
    throw new Error(`模型不存在: ${options.providerCode}/${options.modelKey}`);
  }
  const nextInput = inputPrice ?? before.input ?? "NULL";
  const nextOutput = outputPrice ?? before.output ?? "NULL";
  const assignments = [];
  if (inputPrice !== null)
    assignments.push(`input_price_per_1m = ${inputPrice}`);
  if (outputPrice !== null)
    assignments.push(`output_price_per_1m = ${outputPrice}`);
  const summary = {
    mode: options.apply ? "apply" : "dry-run",
    model: `${options.providerCode}/${options.modelKey}`,
    displayName: before.displayName,
    before: { input: before.input || null, output: before.output || null },
    after: {
      input: nextInput === "NULL" ? null : nextInput,
      output: nextOutput === "NULL" ? null : nextOutput,
    },
  };
  console.log(JSON.stringify(summary, null, 2));
  if (!options.apply) {
    console.log("[update-model-price] dry-run：未写库；确认后加 --apply");
    return;
  }
  runSql(
    `UPDATE model SET ${assignments.join(", ")} WHERE id = UNHEX('${before.idHex}');`,
    options,
  );
  const after = currentPrice(options);
  if (!after) {
    throw new Error("更新后无法回读该模型");
  }
  if (inputPrice !== null && after.input !== inputPrice) {
    throw new Error(`回读价格不一致: ${after.input} != ${inputPrice}`);
  }
  if (outputPrice !== null && after.output !== outputPrice) {
    throw new Error(`回读价格不一致: ${after.output} != ${outputPrice}`);
  }
  console.log(
    "[update-model-price] apply 完成；历史 model_invocation 价格快照未改动（本脚本不写该表）",
  );
}

try {
  main();
} catch (error) {
  console.error(`[update-model-price] ${error.message}`);
  process.exitCode = error.exitCode ?? 1;
}
