#!/usr/bin/env node
// M04 运维脚本：备份恢复演练（ADR-0021 §10.3、ADR-0022 §5、OPS-M04-01）。
//
// 做什么：
//   1) 用 `mysqldump --single-transaction` 从源库（默认 riccon）导出一致性快照（含触发器）；
//   2) 导入到同实例的**一次性演练库**（名字必须以 `riccon_restore_` 开头），不改源库；
//   3) 结构判据：表数一致、审计触发器 2 个、`MAX(flyway_schema_history.version)=11`、
//      `tenant_data_key`/`provider_credential` 非空；
//   4) 可解密判据：复用 `scripts/ops/rewrap-credentials.mjs --verify-only --database <演练库>`，
//      在恢复库上解包 DEK 并解密一行凭据、重算 fingerprint（不打印明文）；
//   5) J1 冒烟（可选但完整演练必须）：`--api-base` 指向已连接演练库的 API，登录 + 提交 1 个 CHAT run；
//   6) RTO 判定：恢复耗时 ≤4h；RPO ≤24h 是备份策略（每 24h 全量 + binlog），报告里记录声明值。
//
// 安全与边界：
// - 目标库名必须匹配 `^riccon_restore_[a-z0-9_]+$`，且 `/^riccon$/` 源库不会被当作目标；
// - 必须显式 `--apply` 才执行 DROP/CREATE/导入；缺省只打印计划；
// - 演练库默认在结束时删除（`--keep` 保留），避免把演练数据当业务数据；
// - 输出不含主密钥/pepper/明文 key；报告只含计数、耗时与判定。
//
// 用法：
//   node scripts/ops/restore-drill.mjs --apply [--source riccon] [--target riccon_restore_drill]
//       [--api-base http://127.0.0.1:18089] [--keep] [--via compose|client]
//   node scripts/ops/restore-drill.mjs --self-test
//
// 退出码：0 演练通过；1 判据失败；2 用法/环境/证据不完整（可用 --allow-partial 记录为 partial）。

import { mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import process from "node:process";
import { spawnSync } from "node:child_process";

import { ApiClient } from "../real-stack/lib/http.mjs";

const COMPOSE_FILE = "deploy/compose/docker-compose.test.yml";

/** 演练库名前缀；只允许在此前缀内 DROP/CREATE。 */
const TARGET_PREFIX = "riccon_restore_";

/** 预期 schema 版本（V11）。 */
const EXPECTED_SCHEMA_VERSION = 11;

/** 审计触发器数量（append-only 两个）。 */
const EXPECTED_TRIGGERS = 2;

/** RTO 上限（小时）。 */
const RTO_LIMIT_HOURS = 4;

/** RPO 声明值（小时）；由备份策略保证，脚本只记录。 */
const RPO_DECLARED_HOURS = 24;

/** 用法。 */
function usage() {
  console.log(`用法：
  node scripts/ops/restore-drill.mjs --apply [--source riccon] [--target riccon_restore_drill]
      [--api-base <URL>] [--keep] [--allow-partial] [--via compose|client] [--dump <path>] [--report <path>]
  node scripts/ops/restore-drill.mjs --self-test
  node scripts/ops/restore-drill.mjs --help

选项：
  --apply            执行导出/建库/导入/判据（缺省只打印计划）
  --source <db>      源库，默认 riccon
  --target <db>      演练库，必须以 ${TARGET_PREFIX} 开头
  --api-base <URL>   J1 冒烟目标（必须连接到演练库）；缺失时演练记 partial
  --keep             演练结束保留演练库（默认删除）
  --allow-partial    允许 partial（缺 J1 或缺可解密校验）并以 0 退出；否则 partial 退出 2
  --dump <path>      复用/保存 dump 文件；缺省写入系统临时目录
  --report <path>    报告路径；默认 .riccon/evidence/<REAL_EVIDENCE_TASK>/restore-drill.json
  --via compose|client  compose=测试栈容器；client=RICCON_DB_* + PATH 上的 mysql/mysqldump
  --self-test        纯静态自检（不连库）

环境变量：
  RICCON_MASTER_KEY  Base64 32 字节主密钥（可解密判据必需）
  RICCON_CREDENTIAL_FINGERPRINT_PEPPER  pepper（fingerprint 校验必需）
  RICCON_DRILL_MEMBER_TENANT/USERNAME/PASSWORD  J1 冒烟凭据（可选）
  RICCON_DB_*（--via=client）`);
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
    source: "riccon",
    target: "riccon_restore_drill",
    apiBase: null,
    keep: false,
    allowPartial: false,
    dump: null,
    report: null,
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
    else if (arg === "--keep") options.keep = true;
    else if (arg === "--allow-partial") options.allowPartial = true;
    else if (arg === "--source") options.source = next();
    else if (arg.startsWith("--source=")) options.source = arg.split("=")[1];
    else if (arg === "--target") options.target = next();
    else if (arg.startsWith("--target=")) options.target = arg.split("=")[1];
    else if (arg === "--api-base") options.apiBase = next();
    else if (arg.startsWith("--api-base=")) options.apiBase = arg.split("=")[1];
    else if (arg === "--dump") options.dump = next();
    else if (arg.startsWith("--dump=")) options.dump = arg.split("=")[1];
    else if (arg === "--report") options.report = next();
    else if (arg.startsWith("--report=")) options.report = arg.split("=")[1];
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
 * 校验演练库名。
 *
 * @param {string} target 目标库
 * @returns {string} 规范化目标库
 */
function assertTargetName(target) {
  if (!new RegExp(`^${TARGET_PREFIX}[a-z0-9_]+$`).test(target ?? "")) {
    throw usageError(
      `--target 必须以 ${TARGET_PREFIX} 开头且只含小写字母/数字/下划线，实际 ${target}`,
    );
  }
  return target;
}

/**
 * 连接参数。
 *
 * @param {object} options 选项
 * @returns {object} 连接
 */
function connection(options, admin = false) {
  if (options.via === "compose") {
    return {
      via: "compose",
      host: "127.0.0.1",
      port: "13306",
      user: admin ? adminUser() : "riccon",
      password: admin
        ? (process.env.RICCON_DB_ADMIN_PASSWORD ??
          process.env.RICCON_DB_ROOT_PASSWORD ??
          "riccon_root_test_only")
        : (process.env.RICCON_DB_PASSWORD ?? "riccon_test_only"),
    };
  }
  const host = process.env.RICCON_DB_HOST;
  const user = admin
    ? (process.env.RICCON_DB_ADMIN_USER ?? process.env.RICCON_DB_USER)
    : process.env.RICCON_DB_USER;
  const password = admin
    ? (process.env.RICCON_DB_ADMIN_PASSWORD ?? process.env.RICCON_DB_PASSWORD)
    : process.env.RICCON_DB_PASSWORD;
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
    password,
  };
}

/**
 * 演练库 DDL/导入使用的管理账号；测试栈默认 root（只用于一次性 riccon_restore_* 库），
 * 生产/预览必须由运维注入具备建库权限的最小账号。
 *
 * @returns {string} 用户名
 */
function adminUser() {
  return (
    process.env.RICCON_DB_ADMIN_USER ??
    (process.env.RICCON_DB_USER ? process.env.RICCON_DB_USER : "root")
  );
}

/**
 * 执行容器内或本机 mysql 客户端。
 *
 * @param {string[]} clientArgs 客户端参数（含可执行文件语义由 command 决定）
 * @param {{input?: string, options: object, command?: "mysql"|"mysqldump"}} input 参数
 * @returns {{status: number, stdout: string, stderr: string}} 结果
 */
function runClient(clientArgs, input) {
  const target = connection(input.options, input.admin === true);
  const command =
    target.via === "compose" ? "docker" : (input.command ?? "mysql");
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
          input.command ?? "mysql",
          ...clientArgs,
        ]
      : [`--host=${target.host}`, `--port=${target.port}`, ...clientArgs];
  return spawnSync(command, args, {
    input: input.input ?? undefined,
    encoding: "utf8",
    maxBuffer: 512 * 1024 * 1024,
    env: { ...process.env, MYSQL_PWD: target.password },
  });
}

/**
 * 执行 SQL 并返回结果；失败抛错。
 *
 * @param {string} sql SQL 文本
 * @param {object} options 选项
 * @returns {string} stdout
 */
function runSql(sql, options, admin = false) {
  const result = runClient(
    [
      `--user=${admin ? adminUser() : (process.env.RICCON_DB_USER ?? "riccon")}`,
      "--batch",
      "--raw",
      "--default-character-set=utf8mb4",
      options.source,
    ],
    { input: sql, options, admin },
  );
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
function queryValue(sql, options, admin = false) {
  const lines = runSql(sql, options, admin).trim().split("\n").filter(Boolean);
  return lines.length === 0 ? "" : lines[lines.length - 1];
}

/**
 * 结构判据（源库与演练库）。
 *
 * @param {string} database 库名
 * @param {object} options 选项
 * @returns {object} 结构
 */
function structure(database, options, admin = false) {
  const db = `'${database.replaceAll("'", "''")}'`;
  /**
   * 容忍“未初始化库”（表不存在）的只读查询：返回 0 而不是抛错，
   * 由调用方给出“源库未初始化”的可操作提示。
   *
   * @param {string} sql 查询
   * @returns {number} 数值
   */
  const safeNumber = (sql) => {
    try {
      return Number(queryValue(sql, options, admin));
    } catch {
      return 0;
    }
  };
  return {
    tables: Number(
      queryValue(
        `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ${db};`,
        options,
        admin,
      ),
    ),
    triggers: Number(
      queryValue(
        `SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = ${db};`,
        options,
        admin,
      ),
    ),
    schemaVersion: safeNumber(
      `SELECT COALESCE(MAX(CAST(version AS UNSIGNED)), 0) FROM \`${database}\`.flyway_schema_history WHERE success = 1;`,
    ),
    dataKeys: safeNumber(`SELECT COUNT(*) FROM \`${database}\`.tenant_data_key;`),
    credentials: safeNumber(
      `SELECT COUNT(*) FROM \`${database}\`.provider_credential;`,
    ),
  };
}

/**
 * 导出源库到文件。
 *
 * @param {object} options 选项
 * @returns {{file: string, bytes: number, ms: number}} 结果
 */
function dump(options) {
  const file =
    options.dump ??
    path.join(os.tmpdir(), `riccon-restore-drill-${Date.now()}.sql`);
  const startedAt = Date.now();
  const result = runClient(
    [
      `--user=${process.env.RICCON_DB_USER ?? "riccon"}`,
      "--single-transaction",
      // 二进制主键（BINARY(16)）必须 hex 输出，否则 UTF-8 文本通道会损坏字节并触发主键冲突。
      "--hex-blob",
      "--routines",
      "--triggers",
      "--no-tablespaces",
      "--default-character-set=utf8mb4",
      options.source,
    ],
    { options, command: "mysqldump" },
  );
  if (result.status !== 0) {
    throw new Error(
      `mysqldump 失败 (exit ${result.status}): ${(result.stderr || "").trim()}`,
    );
  }
  writeFileSync(file, result.stdout, "utf8");
  return {
    file,
    bytes: Buffer.byteLength(result.stdout, "utf8"),
    ms: Date.now() - startedAt,
  };
}

/**
 * 重建并导入演练库。
 *
 * @param {string} file dump 文件
 * @param {object} options 选项
 * @returns {{ms: number}} 结果
 */
function restore(file, options) {
  const startedAt = Date.now();
  const target = assertTargetName(options.target);
  const drop = runClient([`--user=${adminUser()}`, "--batch"], {
    input: `DROP DATABASE IF EXISTS \`${target}\`; CREATE DATABASE \`${target}\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;`,
    options,
    admin: true,
  });
  if (drop.status !== 0) {
    throw new Error(`重建演练库失败: ${(drop.stderr || "").trim()}`);
  }
  // dump 不带 --databases，因此没有 CREATE DATABASE/USE；导入时显式指定演练库，
  // 并逐行过滤防御 `USE`/`CREATE DATABASE` 意外出现，避免把数据写回源库。
  const dumpSql = readFileSync(file, "utf8")
    .split("\n")
    .filter((line) => !/^USE `/.test(line) && !/^CREATE DATABASE /.test(line))
    .join("\n");
  const importResult = runClient(
    [
      `--user=${adminUser()}`,
      "--default-character-set=utf8mb4",
      `--database=${target}`,
    ],
    { input: dumpSql, options, admin: true },
  );
  if (importResult.status !== 0) {
    throw new Error(`导入演练库失败: ${(importResult.stderr || "").trim()}`);
  }
  return { ms: Date.now() - startedAt };
}

/**
 * 演练库可解密校验：委托 rewrap-credentials 的只读模式。
 *
 * @param {object} options 选项
 * @returns {{status: string, detail: object|null, reason?: string}} 结果
 */
function verifyCredentials(options) {
  if (
    !process.env.RICCON_MASTER_KEY ||
    !process.env.RICCON_CREDENTIAL_FINGERPRINT_PEPPER
  ) {
    return {
      status: "skipped",
      detail: null,
      reason: "缺少 RICCON_MASTER_KEY / pepper",
    };
  }
  const result = spawnSync(
    "node",
    [
      "scripts/ops/rewrap-credentials.mjs",
      "--verify-only",
      `--via=${options.via}`,
      `--database=${options.target}`,
    ],
    { encoding: "utf8", maxBuffer: 32 * 1024 * 1024 },
  );
  let detail = null;
  try {
    detail = JSON.parse(result.stdout);
  } catch {
    detail = null;
  }
  if (result.status !== 0) {
    return {
      status: "failed",
      detail,
      reason: (result.stderr || result.stdout || "").trim().slice(0, 500),
    };
  }
  return { status: "passed", detail };
}

/**
 * J1 冒烟：登录 + 创建会话 + 提交 1 个 run（API 必须指向演练库）。
 *
 * @param {string} apiBase API 基地址
 * @returns {Promise<object>} 结果
 */
async function j1Smoke(apiBase) {
  const tenant = process.env.RICCON_DRILL_MEMBER_TENANT;
  const username = process.env.RICCON_DRILL_MEMBER_USERNAME;
  const password = process.env.RICCON_DRILL_MEMBER_PASSWORD;
  if (!tenant || !username || !password) {
    return {
      status: "skipped",
      reason: "缺少 RICCON_DRILL_MEMBER_TENANT/USERNAME/PASSWORD",
    };
  }
  const client = new ApiClient(apiBase);
  await client.login(tenant, username, password);
  const workspaces = await client.get("/api/v1/workspaces?page=1&size=100");
  if (workspaces.status !== 200 || !(workspaces.data.items ?? [])[0]) {
    return {
      status: "failed",
      reason: `工作空间列表不可用: ${workspaces.status}`,
    };
  }
  const conversation = await client.post(
    `/api/v1/workspaces/${workspaces.data.items[0].id}/conversations`,
    { title: "恢复演练 J1 冒烟" },
  );
  if (conversation.status !== 201) {
    return { status: "failed", reason: `创建会话失败: ${conversation.status}` };
  }
  const message = await client.post(
    `/api/v1/conversations/${conversation.data.id}/messages`,
    {
      content: "恢复演练：请返回固定演示内容。",
      clientMessageId: `restore-drill-${Date.now()}`,
    },
    { "idempotency-key": `restore-drill-${Date.now()}` },
  );
  if (message.status !== 200) {
    return {
      status: "failed",
      reason: `提交 run 失败: ${message.status} ${message.text.slice(0, 200)}`,
    };
  }
  return { status: "passed" };
}

/**
 * 删除演练库。
 *
 * @param {object} options 选项
 * @returns {string} 结果
 */
function dropTarget(options) {
  const target = assertTargetName(options.target);
  const result = runClient([`--user=${adminUser()}`, "--batch"], {
    input: `DROP DATABASE IF EXISTS \`${target}\`;`,
    options,
    admin: true,
  });
  if (result.status !== 0) {
    throw new Error(`删除演练库失败: ${(result.stderr || "").trim()}`);
  }
  return "dropped";
}

/**
 * 自检：库名守卫、RTO 判定与报告形状。
 */
function selfTest() {
  let rejected = false;
  try {
    assertTargetName("riccon");
  } catch {
    rejected = true;
  }
  if (!rejected) throw new Error("riccon 必须不能作为演练目标");
  for (const bad of ["riccon_restore_", "Riccon_Restore_X", "other_drill"]) {
    let failed = false;
    try {
      assertTargetName(bad);
    } catch {
      failed = true;
    }
    if (!failed) throw new Error(`非法目标库未被拒绝: ${bad}`);
  }
  assertTargetName("riccon_restore_drill");
  const rto = (hours) => hours <= RTO_LIMIT_HOURS;
  if (!rto(0.5) || rto(4.1)) throw new Error("RTO 判定失败");
  const report = buildReport({
    options: { source: "riccon", target: "riccon_restore_drill" },
    source: {
      tables: 46,
      triggers: 2,
      schemaVersion: 11,
      dataKeys: 1,
      credentials: 2,
    },
    restored: {
      tables: 46,
      triggers: 2,
      schemaVersion: 11,
      dataKeys: 1,
      credentials: 2,
    },
    rtoMs: 120_000,
    credentialCheck: { status: "passed" },
    j1: { status: "passed" },
  });
  if (report.status !== "passed")
    throw new Error(`报告判定失败: ${JSON.stringify(report.failures)}`);
  const broken = buildReport({
    options: { source: "riccon", target: "riccon_restore_drill" },
    source: {
      tables: 46,
      triggers: 2,
      schemaVersion: 11,
      dataKeys: 1,
      credentials: 2,
    },
    restored: {
      tables: 45,
      triggers: 1,
      schemaVersion: 10,
      dataKeys: 0,
      credentials: 0,
    },
    rtoMs: 5 * 3600 * 1000,
    credentialCheck: { status: "failed" },
    j1: { status: "failed" },
  });
  if (broken.status !== "failed" || broken.failures.length < 5) {
    throw new Error("负向报告未全部失败");
  }
  console.log(
    JSON.stringify(
      {
        status: "passed",
        checks: [
          "target-guard",
          "rto-limit",
          "report-positive",
          "report-negative",
        ],
      },
      null,
      2,
    ),
  );
}

/**
 * 组装报告（纯函数）。
 *
 * @param {object} input 输入
 * @returns {object} 报告
 */
function buildReport(input) {
  const failures = [];
  if (!input.restored) {
    return { status: "failed", failures: ["演练库未建立"], ...input };
  }
  if (input.restored.tables !== input.source.tables) {
    failures.push(
      `表数不一致: source=${input.source.tables} restored=${input.restored.tables}`,
    );
  }
  if (input.restored.triggers !== EXPECTED_TRIGGERS) {
    failures.push(
      `审计触发器数量: expected=${EXPECTED_TRIGGERS} actual=${input.restored.triggers}`,
    );
  }
  if (input.restored.schemaVersion !== EXPECTED_SCHEMA_VERSION) {
    failures.push(
      `MAX(version): expected=${EXPECTED_SCHEMA_VERSION} actual=${input.restored.schemaVersion}`,
    );
  }
  if (input.restored.dataKeys < 1) failures.push("tenant_data_key 为空");
  if (input.restored.credentials < 1) failures.push("provider_credential 为空");
  if (input.rtoMs > RTO_LIMIT_HOURS * 3600 * 1000) {
    failures.push(
      `RTO 超过 ${RTO_LIMIT_HOURS}h: ${(input.rtoMs / 3600_000).toFixed(2)}h`,
    );
  }
  const partialReasons = [];
  if (input.credentialCheck.status === "failed") {
    failures.push(
      `凭据可解密判据未通过: ${input.credentialCheck.reason ?? "失败"}`,
    );
  } else if (input.credentialCheck.status === "skipped") {
    partialReasons.push(
      `凭据可解密判据未执行: ${input.credentialCheck.reason}`,
    );
  }
  if (input.j1.status === "failed") {
    failures.push(`J1 冒烟未通过: ${input.j1.reason ?? "失败"}`);
  } else if (input.j1.status === "skipped") {
    partialReasons.push(`J1 冒烟未执行: ${input.j1.reason}`);
  }
  return {
    status:
      failures.length === 0
        ? partialReasons.length === 0
          ? "passed"
          : "partial"
        : "failed",
    partialReasons,
    rto: {
      limitHours: RTO_LIMIT_HOURS,
      actualHours: Number((input.rtoMs / 3600_000).toFixed(4)),
    },
    rpo: {
      declaredHours: RPO_DECLARED_HOURS,
      note: "每 24h 全量备份 + binlog；客户生产可由 SOW 覆盖",
    },
    failures,
    ...input,
  };
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
  assertTargetName(options.target);
  if (options.target === options.source) {
    throw usageError("--target 不能等于 --source");
  }
  if (!/^[A-Za-z0-9_]+$/.test(options.source)) {
    throw usageError(`--source 只允许字母/数字/下划线，实际 ${options.source}`);
  }
  const plan = {
    mode: options.apply ? "apply" : "dry-run",
    source: options.source,
    target: options.target,
    via: options.via,
    apiBase: options.apiBase ?? null,
    keep: options.keep,
    criteria: {
      tables: "与源库一致",
      triggers: EXPECTED_TRIGGERS,
      schemaVersion: EXPECTED_SCHEMA_VERSION,
      credentials: "一行可解密 + fingerprint 匹配",
      j1: options.apiBase ? "必需" : "partial",
      rtoHours: RTO_LIMIT_HOURS,
      rpoHours: RPO_DECLARED_HOURS,
    },
  };
  console.log(JSON.stringify(plan, null, 2));
  if (!options.apply) {
    console.log("[restore-drill] dry-run：未导出/未建库；确认后加 --apply");
    return;
  }
  const sourceStructure = structure(options.source, options, false);
  if (sourceStructure.tables === 0 || sourceStructure.schemaVersion !== EXPECTED_SCHEMA_VERSION) {
    throw new Error(
      `源库 ${options.source} 未初始化：tables=${sourceStructure.tables} MAX(version)=${sourceStructure.schemaVersion}；` +
        "先由 apps/api 跑 Flyway V1–V11 并完成 M04 种子，再执行恢复演练（空库演练不算通过）",
    );
  }
  const dumped = dump(options);
  const restored = restore(dumped.file, options);
  if (!options.keep && !options.dump) {
    rmSync(dumped.file, { force: true });
  }
  const restoredStructure = structure(options.target, options, true);
  const credentialCheck = verifyCredentials(options);
  let j1 = { status: "skipped", reason: "未提供 --api-base" };
  if (options.apiBase) {
    try {
      j1 = await j1Smoke(options.apiBase);
    } catch (error) {
      j1 = { status: "failed", reason: error.message };
    }
  }
  const report = buildReport({
    options: {
      source: options.source,
      target: options.target,
      via: options.via,
    },
    source: sourceStructure,
    restored: restoredStructure,
    dump: { bytes: dumped.bytes, path: options.dump ?? null },
    restoreMs: restored.ms,
    rtoMs: dumped.ms + restored.ms,
    credentialCheck,
    j1,
  });
  report.cleanup = options.keep ? "kept" : dropTarget(options);
  const reportPath =
    options.report ??
    path.join(
      ".riccon",
      "evidence",
      process.env.REAL_EVIDENCE_TASK ?? "TASK-0112",
      "restore-drill.json",
    );
  mkdirSync(path.dirname(reportPath), { recursive: true });
  writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`);
  console.log(JSON.stringify({ ...report, reportPath }, null, 2));
  if (report.status === "failed") {
    process.exitCode = 1;
  } else if (report.status === "partial" && !options.allowPartial) {
    console.error(
      "[restore-drill] 演练不完整（缺 J1 或可解密校验）；加 --allow-partial 记录为 partial",
    );
    process.exitCode = 2;
  }
}

main().catch((error) => {
  console.error(`[restore-drill] ${error.message}`);
  process.exitCode = error.exitCode ?? 1;
});
