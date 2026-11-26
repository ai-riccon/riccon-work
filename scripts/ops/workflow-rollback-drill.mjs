#!/usr/bin/env node
// M06 运维脚本：编排 Studio 发布/回滚演练（T16b、OPS-M06-001/021/023/025、AC-23）。
//
// 演练什么（ADR-0024 §10.4/§10.5、RUNBOOK M06-12）：
//   1) 无数据允许：在**未执行过首次 `ENABLED=false` 启动**的独立空栈上跑 AC-23 判定 SQL，
//      8 项计数全部为 0；应急 drop 判定以 0 退出（脚本不另存一份 drop SQL，只演练判定）；
//   2) 有数据拒绝：在独立数据栈/备份夹具上创建至少 1 个流程/运行/审批与 1 条 `workflow.*`
//      审计后，同一判定 SQL 任一 >0；判定门禁以非 0 退出并拒绝全量回滚；
//   3) 首次禁用后“全 0”不可达：真实启动一次 `RICCON_WORKFLOW_ENABLED=false` 的 API，
//      验证 `workflow.feature.changed` 启动审计写入后，即使其余 7 项为 0 判定仍拒绝；
//   4) 无窗口回滚顺序：关 ENABLED → 关 CLAIM/等宽限 → 取消 WAITING_APPROVAL 并断言 0 →
//      清零 PENDING/RUNNING 并断言 0 → 回滚 Worker（记录 tag/digest；可用
//      `--worker-rollback-command` 执行真实切换）→ 保留 API 复断言 0 与历史可读；
//      **无法清零时脚本以非 0 退出并拒绝回滚 Worker**；
//   5) 5 MiB 容量：真实插入接近 5 MiB 的 `workflow_run.result_json` 与接近 256 KiB 的
//      `workflow_node_run.output_json`，校验存储字节/哈希无静默截断；预检服务端
//      `@@max_allowed_packet`、JDBC `RICCON_DB_MAX_ALLOWED_PACKET`、Hikari
//      `RICCON_DB_POOL_SIZE` 与并发公式，并输出容量公式报告。
//
// 边界与安全：
// - 只操作一次性演练库（名字必须以 `riccon_wf_drill_` 开头），不改源库业务数据；
//   演练库默认结束删除（`--keep` 保留），删除前回收库级授权；
// - 两个“无数据判定”使用不同数据库：空栈（首次禁用前）与数据栈/备份夹具；
//   有数据侧不能与首次禁用启动共用同一个库（OPS-M06-025）；
// - Worker 镜像切换只在提供 `--execute-old-worker`（本地旧镜像容器）或
//   `--worker-rollback-command`（部署命令）时执行；默认只记录回滚目标 tag/digest
//   与守卫结果，不假装执行了镜像回滚；
// - 输出/报告不含主密钥、pepper、服务令牌或明文凭据；API 用随机测试密钥启动。
//
// 用法：
//   node scripts/ops/workflow-rollback-drill.mjs --apply
//   node scripts/ops/workflow-rollback-drill.mjs --apply --keep --grace-seconds 2
//   node scripts/ops/workflow-rollback-drill.mjs --decision-only --database riccon
//   node scripts/ops/workflow-rollback-drill.mjs --self-test
//
// 退出码：0 演练通过；1 断言/API 失败；2 用法/环境/证据不完整；3 判定 SQL 非 0（拒绝回滚）。

import { spawn, spawnSync } from "node:child_process";
import { randomBytes, randomUUID, createHash } from "node:crypto";
import {
  closeSync,
  existsSync,
  mkdirSync,
  openSync,
  readFileSync,
  writeFileSync,
} from "node:fs";
import path from "node:path";
import process from "node:process";

import { ApiClient } from "../real-stack/lib/http.mjs";

/** compose 测试栈文件（与合同 commands.sandbox/run 一致）。 */
const COMPOSE_FILE = "deploy/compose/docker-compose.test.yml";

/** 演练库名前缀；只允许在此前缀内 DROP/CREATE。 */
const DRILL_PREFIX = "riccon_wf_drill_";

/** M06 期望的 Flyway schema 版本（V15 编排 Studio）。 */
const EXPECTED_SCHEMA_VERSION = 15;

/** M06 期望的业务/基础设施表数（57 张 + flyway_schema_history）。 */
const EXPECTED_TABLE_COUNT = 58;

/** M06 期望的触发器数（审计 append-only 2 + workflow_version 不可变 2）。 */
const EXPECTED_TRIGGER_COUNT = 4;

/** 单次运行输出 + 结果上限（5 MiB，ADR-0024 §10.1）。 */
const RESULT_MAX_BYTES = 5_242_880;

/** 单节点输出上限（256 KiB，WorkflowEngineLimits.NODE_OUTPUT_MAX_BYTES）。 */
const NODE_OUTPUT_MAX_BYTES = 256 * 1024;

/** 服务端/驱动包大小下限（8 MiB，ADR-0024 §10.1/§10.5）。 */
const MIN_PACKET_BYTES = 8 * 1024 * 1024;

/** 包大小相对结果上限的安全余量（1 MiB，ADR-0024 §10.1）。 */
const PACKET_HEADROOM_BYTES = 1024 * 1024;

/** JDBC maxAllowedPacket 默认值（application.yml；ADR-0024 §10.1）。 */
const DEFAULT_JDBC_MAX_PACKET = 16_777_216;

/** Worker Hikari 池默认值（application.yml；必须 ≥ 并发 + 4）。 */
const DEFAULT_DB_POOL_SIZE = 16;

/** Worker 运行并发默认值（application.yml）。 */
const DEFAULT_WORKER_CONCURRENCY = 10;

/** Hikari 连接池在并发之外的保留连接数（心跳/outbox/健康检查）。 */
const POOL_RESERVED_CONNECTIONS = 4;

/** AC-23 全量 API 回滚/应急 drop 判定 SQL（ADR-0024 §10.4，原样）。 */
const DECISION_SQL = `SELECT
  (SELECT COUNT(*) FROM workflow_definition) AS definitions,
  (SELECT COUNT(*) FROM workflow_version) AS versions,
  (SELECT COUNT(*) FROM workflow_run) AS runs,
  (SELECT COUNT(*) FROM workflow_node_run) AS node_runs,
  (SELECT COUNT(*) FROM workflow_approval) AS approvals,
  (SELECT COUNT(*) FROM workflow_tool_invocation) AS tool_invocations,
  (SELECT COUNT(*) FROM workflow_user_template) AS user_templates,
  (SELECT COUNT(*) FROM audit_event WHERE action LIKE 'workflow.%') AS workflow_audit_events;`;

/** 受支持回滚的活动状态判定（ADR-0024 §10.4/§10.5）。 */
const ACTIVE_STATUS_SQL = `SELECT
  (SELECT COUNT(*) FROM workflow_run WHERE status = 'WAITING_APPROVAL') AS waiting_approval,
  (SELECT COUNT(*) FROM workflow_run WHERE status = 'PENDING') AS pending,
  (SELECT COUNT(*) FROM workflow_run WHERE status = 'RUNNING') AS running;`;

/** 判定 SQL 的列顺序；报告与退出码共用同一顺序。 */
const DECISION_KEYS = [
  "definitions",
  "versions",
  "runs",
  "node_runs",
  "approvals",
  "tool_invocations",
  "user_templates",
  "workflow_audit_events",
];

/** 测试栈默认登录凭据（仅本地演练；可用 RICCON_DRILL_* 覆盖，禁止写真实密钥）。 */
const DRILL_TENANT = process.env.RICCON_DRILL_TENANT ?? "tenant-alpha";
const DRILL_USERNAME = process.env.RICCON_DRILL_USERNAME ?? "alpha-admin";
const DRILL_PASSWORD =
  process.env.RICCON_DRILL_PASSWORD ?? "alpha-admin-password-12";

/** 证据任务目录；复验时用 REAL_EVIDENCE_TASK 隔离旧报告。 */
const evidenceTask = process.env.REAL_EVIDENCE_TASK ?? "TASK-0238";

/** 当前会话里的临时 API 子进程，用于 finally 清理。 */
const startedApis = [];

/** 当前会话里的临时 Worker 探针子进程，用于 finally 清理。 */
const startedWorkers = [];

/** 用法。 */
function usage() {
  console.log(`用法：
  node scripts/ops/workflow-rollback-drill.mjs --apply [--keep] [--grace-seconds 2]
      [--source riccon] [--data-dump <path>] [--report <path>]
      [--old-worker-tag <tag>] [--old-worker-digest <digest>]
      [--worker-rollback-command <shell 命令>]
  node scripts/ops/workflow-rollback-drill.mjs --decision-only [--database <db>]
  node scripts/ops/workflow-rollback-drill.mjs --self-test
  node scripts/ops/workflow-rollback-drill.mjs --help

选项：
  --apply                       执行完整演练（缺省只打印计划，不建库/不启 API）
  --decision-only               只跑 AC-23 判定 SQL：全 0 退出 0，任一 >0 退出 3
  --database <db>               --decision-only 的目标库，默认源库 riccon
  --source <db>                 源库，默认 riccon（必须已完成 V15）
  --data-dump <path>            “有数据拒绝”使用备份夹具 dump 文件（缺省从源库导出）
  --keep                        保留演练库（默认删除）
  --allow-partial               允许旧 Worker provenance 不完整（记录 residual，不冒充通过）
  --grace-seconds <n>           停止领取后的宽限等待秒数，默认取
                                RICCON_WORKFLOW_SHUTDOWN_GRACE_SECONDS（120）
  --runs-per-day <n>            容量报告每日运行数，默认 100（仅容量估算输入）
  --p95-bytes <n>               容量报告结果 p95 字节；缺省取源库实测
  --retention-days <n>          容量报告保留天数，默认 180
  --api-port <n>                演练 API 端口，默认 18082
  --worker-port <n>             停止领取探针 Worker 端口，默认 18084
  --via compose|client          MySQL 访问通道，默认 compose（测试栈）
  --old-worker-tag <tag>        M06 前 Worker 镜像 tag（生产演练必须由部署提供）
  --old-worker-digest <digest>  M06 前 Worker 镜像 digest（生产演练必须由部署提供）
  --execute-old-worker          实际启动旧 Worker 容器验证回滚窗口（需本地镜像）
  --old-worker-port <n>         旧 Worker 容器映射端口，默认 18086
  --old-worker-expected-schema <n> 旧 Worker 期望 schema，默认 14
  --worker-rollback-command <cmd> 守卫通过后执行的真实 Worker 回滚命令（缺省只记录）
  --report <path>               报告路径，默认 .riccon/evidence/<REAL_EVIDENCE_TASK>/
                                workflow-rollback-drill.json
  --self-test                  纯静态自检（不连库/不启进程）

环境变量：
  RICCON_DRILL_TENANT/USERNAME/PASSWORD  演练登录（默认 tenant-alpha/alpha-admin，仅测试栈）
  RICCON_WORKFLOW_SHUTDOWN_GRACE_SECONDS 宽限秒数默认值
  RICCON_WORKFLOW_RESULT_MAX_BYTES       结果上限（默认 5242880）
  RICCON_DB_MAX_ALLOWED_PACKET           JDBC maxAllowedPacket（默认 16777216）
  RICCON_DB_POOL_SIZE                    Worker Hikari 池（默认 16）
  RICCON_WORKFLOW_WORKER_CONCURRENCY     Worker 并发（默认 10）
  RICCON_DB_*（--via=client）            显式数据库连接
  RICCON_DB_ADMIN_USER/PASSWORD          --via=client 建库/授权账号`);
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
    decisionOnly: false,
    database: "riccon",
    source: "riccon",
    dataDump: null,
    keep: false,
    allowPartial: false,
    graceSeconds: Number(
      process.env.RICCON_WORKFLOW_SHUTDOWN_GRACE_SECONDS ?? "120",
    ),
    runsPerDay: 100,
    p95Bytes: null,
    retentionDays: 180,
    apiPort: 18082,
    workerPort: 18084,
    executeOldWorker: false,
    oldWorkerPort: 18086,
    oldWorkerExpectedSchema: 14,
    via: "compose",
    oldWorkerTag:
      process.env.RICCON_OLD_WORKER_TAG ?? process.env.OLD_WORKER_TAG ?? null,
    oldWorkerDigest:
      process.env.RICCON_OLD_WORKER_DIGEST ??
      process.env.OLD_WORKER_DIGEST ??
      null,
    workerRollbackCommand: null,
    report: null,
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
    else if (arg === "--decision-only") options.decisionOnly = true;
    else if (arg === "--keep") options.keep = true;
    else if (arg === "--allow-partial") options.allowPartial = true;
    else if (arg === "--database") options.database = next();
    else if (arg.startsWith("--database="))
      options.database = arg.split("=")[1];
    else if (arg === "--source") options.source = next();
    else if (arg.startsWith("--source=")) options.source = arg.split("=")[1];
    else if (arg === "--data-dump") options.dataDump = next();
    else if (arg.startsWith("--data-dump="))
      options.dataDump = arg.split("=")[1];
    else if (arg === "--grace-seconds") options.graceSeconds = Number(next());
    else if (arg.startsWith("--grace-seconds="))
      options.graceSeconds = Number(arg.split("=")[1]);
    else if (arg === "--runs-per-day") options.runsPerDay = Number(next());
    else if (arg.startsWith("--runs-per-day="))
      options.runsPerDay = Number(arg.split("=")[1]);
    else if (arg === "--p95-bytes") options.p95Bytes = Number(next());
    else if (arg.startsWith("--p95-bytes="))
      options.p95Bytes = Number(arg.split("=")[1]);
    else if (arg === "--retention-days") options.retentionDays = Number(next());
    else if (arg.startsWith("--retention-days="))
      options.retentionDays = Number(arg.split("=")[1]);
    else if (arg === "--api-port") options.apiPort = Number(next());
    else if (arg.startsWith("--api-port="))
      options.apiPort = Number(arg.split("=")[1]);
    else if (arg === "--worker-port") options.workerPort = Number(next());
    else if (arg.startsWith("--worker-port="))
      options.workerPort = Number(arg.split("=")[1]);
    else if (arg === "--execute-old-worker") options.executeOldWorker = true;
    else if (arg === "--old-worker-port")
      options.oldWorkerPort = Number(next());
    else if (arg.startsWith("--old-worker-port="))
      options.oldWorkerPort = Number(arg.split("=")[1]);
    else if (arg === "--old-worker-expected-schema")
      options.oldWorkerExpectedSchema = Number(next());
    else if (arg.startsWith("--old-worker-expected-schema="))
      options.oldWorkerExpectedSchema = Number(arg.split("=")[1]);
    else if (arg === "--via") options.via = next();
    else if (arg.startsWith("--via=")) options.via = arg.split("=")[1];
    else if (arg === "--old-worker-tag") options.oldWorkerTag = next();
    else if (arg.startsWith("--old-worker-tag="))
      options.oldWorkerTag = arg.split("=")[1];
    else if (arg === "--old-worker-digest") options.oldWorkerDigest = next();
    else if (arg.startsWith("--old-worker-digest="))
      options.oldWorkerDigest = arg.split("=")[1];
    else if (arg === "--worker-rollback-command")
      options.workerRollbackCommand = next();
    else if (arg.startsWith("--worker-rollback-command="))
      options.workerRollbackCommand = arg.split("=")[1];
    else if (arg === "--report") options.report = next();
    else if (arg.startsWith("--report=")) options.report = arg.split("=")[1];
    else throw usageError(`未知参数: ${arg}`);
  }
  if (!["compose", "client"].includes(options.via)) {
    throw usageError(`--via 只能是 compose 或 client，实际 ${options.via}`);
  }
  for (const [name, value] of [
    ["--grace-seconds", options.graceSeconds],
    ["--runs-per-day", options.runsPerDay],
    ["--retention-days", options.retentionDays],
    ["--api-port", options.apiPort],
    ["--worker-port", options.workerPort],
    ["--old-worker-port", options.oldWorkerPort],
    ["--old-worker-expected-schema", options.oldWorkerExpectedSchema],
  ]) {
    if (!Number.isFinite(value) || value < 0) {
      throw usageError(`${name} 必须是非负数`);
    }
  }
  if (!Number.isInteger(options.runsPerDay) || options.runsPerDay < 1) {
    throw usageError("--runs-per-day 必须是正整数");
  }
  if (!Number.isInteger(options.retentionDays) || options.retentionDays < 1) {
    throw usageError("--retention-days 必须是正整数");
  }
  if (!Number.isInteger(options.apiPort) || options.apiPort <= 0) {
    throw usageError("--api-port 必须是正整数");
  }
  if (!Number.isInteger(options.workerPort) || options.workerPort <= 0) {
    throw usageError("--worker-port 必须是正整数");
  }
  if (!Number.isInteger(options.oldWorkerPort) || options.oldWorkerPort <= 0) {
    throw usageError("--old-worker-port 必须是正整数");
  }
  if (
    !Number.isInteger(options.oldWorkerExpectedSchema) ||
    options.oldWorkerExpectedSchema < 1
  ) {
    throw usageError("--old-worker-expected-schema 必须是正整数");
  }
  if (options.apiPort === options.workerPort) {
    throw usageError("--api-port 与 --worker-port 不能相同");
  }
  if (options.executeOldWorker && options.workerRollbackCommand) {
    throw usageError(
      "--execute-old-worker 与 --worker-rollback-command 只能二选一",
    );
  }
  if (options.executeOldWorker && !options.oldWorkerTag) {
    throw usageError("--execute-old-worker 需要同时提供 --old-worker-tag");
  }
  if (options.p95Bytes !== null) {
    if (!Number.isInteger(options.p95Bytes) || options.p95Bytes < 1) {
      throw usageError("--p95-bytes 必须是正整数");
    }
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
 * 断言。
 *
 * @param {unknown} condition 条件
 * @param {string} message 说明
 */
function assert(condition, message) {
  if (!condition) {
    throw new Error(`断言失败: ${message}`);
  }
}

/**
 * 连接参数。
 *
 * @param {object} options 选项
 * @param {boolean} [admin] 是否管理账号（建库/授权/导入）
 * @returns {object} 连接
 */
function connection(options, admin = false) {
  if (options.via === "compose") {
    return {
      via: "compose",
      user: admin
        ? (process.env.RICCON_DB_ADMIN_USER ?? "root")
        : (process.env.RICCON_DB_USERNAME ?? "riccon"),
      password: admin
        ? (process.env.RICCON_DB_ADMIN_PASSWORD ??
          process.env.RICCON_DB_ROOT_PASSWORD ??
          "riccon_root_test_only")
        : (process.env.RICCON_DB_PASSWORD ?? "riccon_test_only"),
    };
  }
  const host = process.env.RICCON_DB_HOST;
  const user = admin
    ? (process.env.RICCON_DB_ADMIN_USER ?? process.env.RICCON_DB_USERNAME)
    : process.env.RICCON_DB_USERNAME;
  const password = admin
    ? (process.env.RICCON_DB_ADMIN_PASSWORD ?? process.env.RICCON_DB_PASSWORD)
    : process.env.RICCON_DB_PASSWORD;
  if (!host || !user || !password) {
    throw envError(
      "--via=client 需要 RICCON_DB_HOST/RICCON_DB_USERNAME/RICCON_DB_PASSWORD",
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
 * 执行 mysql/mysqldump 客户端。
 *
 * @param {string[]} clientArgs 客户端参数（不含数据库名）
 * @param {object} input 输入
 * @returns {{status: number, stdout: string, stderr: string}} 结果
 */
function runClient(clientArgs, input) {
  const target = connection(input.options, input.admin === true);
  const tool = input.command ?? "mysql";
  const command = target.via === "compose" ? "docker" : tool;
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
          tool,
          ...clientArgs,
        ]
      : [`--host=${target.host}`, `--port=${target.port}`, ...clientArgs];
  return spawnSync(command, args, {
    cwd: process.cwd(),
    input: input.sql ?? undefined,
    encoding: "utf8",
    maxBuffer: 512 * 1024 * 1024,
    env: { ...process.env, MYSQL_PWD: target.password },
  });
}

/**
 * 执行 SQL；失败抛错。
 *
 * @param {string} database 数据库
 * @param {string} sql SQL 文本
 * @param {object} options 选项
 * @param {boolean} [admin] 是否管理账号
 * @returns {string} stdout
 */
function runSql(database, sql, options, admin = false) {
  const target = connection(options, admin);
  const credential = admin
    ? target.user
    : (process.env.RICCON_DB_USERNAME ?? "riccon");
  const result = runClient(
    [
      `--user=${credential}`,
      "--batch",
      "--raw",
      "--skip-column-names",
      `--default-character-set=utf8mb4`,
      ...(database ? [database] : []),
    ],
    { sql, options, admin },
  );
  if (result.status !== 0) {
    throw new Error(
      `mysql 执行失败 (exit ${result.status}): ${(result.stderr || result.stdout || "").trim()}`,
    );
  }
  return result.stdout ?? "";
}

/**
 * 查询单值。
 *
 * @param {string} database 数据库
 * @param {string} sql SQL
 * @param {object} options 选项
 * @param {boolean} [admin] 管理账号
 * @returns {string} 值
 */
function queryValue(database, sql, options, admin = false) {
  const lines = runSql(database, sql, options, admin)
    .trim()
    .split("\n")
    .filter(Boolean);
  return lines.length === 0 ? "" : lines[lines.length - 1];
}

/**
 * 查询一行多列，返回列值数组。
 *
 * @param {string} database 数据库
 * @param {string} sql SQL
 * @param {object} options 选项
 * @param {boolean} [admin] 管理账号
 * @returns {string[]} 列值
 */
function queryRow(database, sql, options, admin = false) {
  const line = runSql(database, sql, options, admin)
    .trim()
    .split("\n")
    .filter(Boolean)
    .pop();
  return line === undefined ? [] : line.split("\t");
}

/**
 * 查询多行，返回二维数组。
 *
 * @param {string} database 数据库
 * @param {string} sql SQL
 * @param {object} options 选项
 * @param {boolean} [admin] 管理账号
 * @returns {string[][]} 行
 */
function queryRows(database, sql, options, admin = false) {
  return runSql(database, sql, options, admin)
    .trim()
    .split("\n")
    .filter(Boolean)
    .map((line) => line.split("\t"));
}

/**
 * SQL 字符串字面量。
 *
 * @param {string} value 原始值
 * @returns {string} 字面量
 */
function sqlString(value) {
  return `'${String(value).replaceAll("\\", "\\\\").replaceAll("'", "''")}'`;
}

/**
 * UUID 转 MySQL BINARY(16) 表达式。
 *
 * @param {string} uuid UUID
 * @returns {string} SQL 表达式
 */
function sqlUuid(uuid) {
  return `UNHEX(REPLACE(${sqlString(uuid)}, '-', ''))`;
}

/**
 * 32 位十六进制转带连字符 UUID。
 *
 * @param {string} hex 32 位十六进制
 * @returns {string} UUID
 */
function dashedUuid(hex) {
  const value = String(hex).toLowerCase();
  return [
    value.slice(0, 8),
    value.slice(8, 12),
    value.slice(12, 16),
    value.slice(16, 20),
    value.slice(20),
  ].join("-");
}

/**
 * 校验演练库名，防止误删源库。
 *
 * @param {string} name 库名
 * @returns {string} 规范化库名
 */
function assertDrillName(name) {
  if (!new RegExp(`^${DRILL_PREFIX}[a-z0-9_]+$`).test(name ?? "")) {
    throw usageError(
      `演练库名必须以 ${DRILL_PREFIX} 开头且只含小写字母/数字/下划线：${name}`,
    );
  }
  return name;
}

/**
 * 创建演练库并授权应用账号。
 *
 * @param {string} name 库名
 * @param {object} options 选项
 * @returns {object} 库信息
 */
function createDrillDatabase(name, options) {
  assertDrillName(name);
  const admin = connection(options, true);
  const grantUser =
    process.env.RICCON_DRILL_GRANT_USER ??
    process.env.RICCON_DB_USERNAME ??
    "riccon";
  const grantHost = process.env.RICCON_DRILL_GRANT_HOST ?? "%";
  if (!/^[A-Za-z0-9_.-]{1,32}$/.test(grantUser)) {
    throw usageError(`授权账号不合法: ${grantUser}`);
  }
  runSql(
    "",
    `DROP DATABASE IF EXISTS \`${name}\`;
     CREATE DATABASE \`${name}\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
     GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP, REFERENCES
       ON \`${name}\`.* TO ${sqlString(grantUser)}@${sqlString(grantHost)};`,
    options,
    true,
  );
  return { name, grantUser, grantHost, admin: admin.user };
}

/**
 * 删除演练库并回收授权。
 *
 * @param {object} database 库信息
 * @param {object} options 选项
 */
function dropDrillDatabase(database, options) {
  if (!database) return;
  assertDrillName(database.name);
  try {
    runSql(
      "",
      `REVOKE ALL PRIVILEGES, GRANT OPTION ON \`${database.name}\`.*
         FROM ${sqlString(database.grantUser)}@${sqlString(database.grantHost)};`,
      options,
      true,
    );
  } catch {
    // 授权可能已不存在；删除库仍是安全的收尾动作。
  }
  runSql("", `DROP DATABASE IF EXISTS \`${database.name}\`;`, options, true);
}

/**
 * 导出数据库。
 *
 * @param {string} database 库名
 * @param {object} options 选项
 * @param {boolean} [schemaOnly] 只导出结构
 * @returns {string} dump 文本
 */
function dumpDatabase(database, options, schemaOnly = false) {
  const result = runClient(
    [
      `--user=${process.env.RICCON_DB_USERNAME ?? "riccon"}`,
      "--single-transaction",
      "--hex-blob",
      "--routines",
      "--triggers",
      "--no-tablespaces",
      "--default-character-set=utf8mb4",
      ...(schemaOnly ? ["--no-data"] : []),
      database,
    ],
    { options, command: "mysqldump" },
  );
  if (result.status !== 0) {
    throw new Error(
      `mysqldump 失败 (exit ${result.status}): ${(result.stderr || "").trim()}`,
    );
  }
  return result.stdout ?? "";
}

/**
 * 导入 SQL 到数据库。
 *
 * @param {string} database 库名
 * @param {string} sql SQL
 * @param {object} options 选项
 */
function importSql(database, sql, options) {
  const result = runClient(
    [
      `--user=${connection(options, true).user}`,
      "--batch",
      "--raw",
      "--default-character-set=utf8mb4",
      database,
    ],
    { sql, options, admin: true },
  );
  if (result.status !== 0) {
    throw new Error(
      `导入失败 (exit ${result.status}): ${(result.stderr || result.stdout || "").trim()}`,
    );
  }
}

/**
 * 读取数据库结构判据。
 *
 * @param {string} database 库名
 * @param {object} options 选项
 * @param {boolean} [admin] 管理账号
 * @returns {object} 结构
 */
function schemaMetrics(database, options, admin = false) {
  const db = sqlString(database);
  const value = (sql) => Number(queryValue(database, sql, options, admin));
  return {
    tables: value(
      `SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ${db};`,
    ),
    triggers: value(
      `SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = ${db};`,
    ),
    schemaVersion: value(
      `SELECT COALESCE(MAX(CAST(version AS UNSIGNED)), 0)
         FROM \`${database}\`.flyway_schema_history WHERE success = 1;`,
    ),
  };
}

/**
 * 校验源库已达到 M06 V15。
 *
 * @param {string} database 库名
 * @param {object} options 选项
 * @returns {object} 结构
 */
function requireV15(database, options) {
  let metrics;
  try {
    metrics = schemaMetrics(database, options);
  } catch (error) {
    throw envError(
      `无法读取源库 ${database}：${error.message}；请先启动 compose 测试栈并完成 V15`,
    );
  }
  if (metrics.schemaVersion !== EXPECTED_SCHEMA_VERSION) {
    throw envError(
      `源库 ${database} 的 Flyway 版本为 ${metrics.schemaVersion}，M06 演练要求 ${EXPECTED_SCHEMA_VERSION}`,
    );
  }
  assert(
    metrics.tables === EXPECTED_TABLE_COUNT,
    `源库表数 ${metrics.tables} != ${EXPECTED_TABLE_COUNT}（V15）`,
  );
  assert(
    metrics.triggers === EXPECTED_TRIGGER_COUNT,
    `源库触发器数 ${metrics.triggers} != ${EXPECTED_TRIGGER_COUNT}`,
  );
  return metrics;
}

/**
 * 执行 AC-23 判定 SQL。
 *
 * @param {string} database 库名
 * @param {object} options 选项
 * @param {boolean} [admin] 管理账号
 * @returns {object} 计数
 */
function decisionCounts(database, options, admin = false) {
  const row = queryRow(database, DECISION_SQL, options, admin);
  assert(
    row.length === DECISION_KEYS.length,
    `判定 SQL 列数 ${row.length} != ${DECISION_KEYS.length}`,
  );
  const counts = {};
  DECISION_KEYS.forEach((key, index) => {
    counts[key] = Number(row[index]);
    assert(
      Number.isFinite(counts[key]),
      `判定 SQL 列 ${key} 非数值: ${row[index]}`,
    );
  });
  return counts;
}

/**
 * 判定门禁：全 0 允许（0），任一 >0 拒绝（3）。
 *
 * @param {object} counts 计数
 * @returns {{allowed: boolean, exitCode: number}} 判定
 */
function fullRollbackDecision(counts) {
  const nonZero = DECISION_KEYS.filter((key) => counts[key] > 0);
  return {
    allowed: nonZero.length === 0,
    exitCode: nonZero.length === 0 ? 0 : 3,
    nonZero,
  };
}

/**
 * 读取活动运行计数。
 *
 * @param {string} database 库名
 * @param {object} options 选项
 * @returns {object} 计数
 */
function activeCounts(database, options) {
  const row = queryRow(database, ACTIVE_STATUS_SQL, options);
  return {
    waitingApproval: Number(row[0]),
    pending: Number(row[1]),
    running: Number(row[2]),
  };
}

/**
 * 回滚 Worker 守卫：任一活动状态 >0 即拒绝。
 *
 * @param {object} counts 活动计数
 * @returns {{allowed: boolean, exitCode: number, nonZero: string[]}} 守卫
 */
function workerRollbackGuard(counts) {
  const nonZero = [];
  if (counts.waitingApproval > 0) nonZero.push("WAITING_APPROVAL");
  if (counts.pending > 0) nonZero.push("PENDING");
  if (counts.running > 0) nonZero.push("RUNNING");
  return {
    allowed: nonZero.length === 0,
    exitCode: nonZero.length === 0 ? 0 : 3,
    nonZero,
  };
}

/**
 * 活动运行明细（用于拒绝时记录 runId）。
 *
 * @param {string} database 库名
 * @param {object} options 选项
 * @returns {string[][]} id/status/tenant
 */
function activeRuns(database, options) {
  return queryRows(
    database,
    `SELECT LOWER(HEX(id)), status, tenant_id FROM workflow_run
      WHERE status IN ('WAITING_APPROVAL','PENDING','RUNNING')
      ORDER BY created_at, id;`,
    options,
  );
}

/**
 * 读取配置数值（环境变量优先，缺省用 application.yml 默认）。
 *
 * @param {string} name 环境变量
 * @param {number} fallback 默认值
 * @returns {number} 数值
 */
function numberEnv(name, fallback) {
  const raw = process.env[name];
  if (raw === undefined || raw === "") return fallback;
  const value = Number(raw);
  if (!Number.isFinite(value) || value < 0) {
    throw envError(`${name} 不是非负数值: ${raw}`);
  }
  return value;
}

/**
 * 包大小与连接池预检（ADR-0024 §10.1/§10.5、OPS-M06-011/022）。
 *
 * @param {string} database 库名
 * @param {object} options 选项
 * @returns {object} 预检结果
 */
function packetAndPoolPreflight(database, options) {
  const serverPacket = Number(
    queryValue(database, "SELECT @@max_allowed_packet;", options),
  );
  const resultMaxBytes = numberEnv(
    "RICCON_WORKFLOW_RESULT_MAX_BYTES",
    RESULT_MAX_BYTES,
  );
  const jdbcPacket = numberEnv(
    "RICCON_DB_MAX_ALLOWED_PACKET",
    DEFAULT_JDBC_MAX_PACKET,
  );
  const dbPool = numberEnv("RICCON_DB_POOL_SIZE", DEFAULT_DB_POOL_SIZE);
  const concurrency = numberEnv(
    "RICCON_WORKFLOW_WORKER_CONCURRENCY",
    DEFAULT_WORKER_CONCURRENCY,
  );
  const requiredPacket = Math.max(
    MIN_PACKET_BYTES,
    resultMaxBytes + PACKET_HEADROOM_BYTES,
  );
  assert(
    serverPacket >= requiredPacket,
    `服务端 @@max_allowed_packet=${serverPacket} 小于下限 ${requiredPacket}（≥8 MiB 且 ≥结果上限+1 MiB）`,
  );
  assert(
    jdbcPacket >= requiredPacket,
    `JDBC RICCON_DB_MAX_ALLOWED_PACKET=${jdbcPacket} 小于下限 ${requiredPacket}`,
  );
  assert(
    serverPacket >= jdbcPacket,
    `服务端 @@max_allowed_packet=${serverPacket} 小于 JDBC maxAllowedPacket=${jdbcPacket}`,
  );
  assert(
    dbPool >= concurrency + POOL_RESERVED_CONNECTIONS,
    `RICCON_DB_POOL_SIZE=${dbPool} 必须 ≥ WORKER_CONCURRENCY(${concurrency}) + ${POOL_RESERVED_CONNECTIONS}`,
  );
  return {
    resultMaxBytes,
    nodeOutputMaxBytes: NODE_OUTPUT_MAX_BYTES,
    serverMaxAllowedPacket: serverPacket,
    jdbcMaxAllowedPacket: jdbcPacket,
    requiredPacketBytes: requiredPacket,
    workerConcurrency: concurrency,
    dbPoolSize: dbPool,
    poolMinimum: concurrency + POOL_RESERVED_CONNECTIONS,
    checks: {
      serverPacketAtLeast8MiB: serverPacket >= MIN_PACKET_BYTES,
      jdbcPacketAtLeast8MiB: jdbcPacket >= MIN_PACKET_BYTES,
      serverPacketAtLeastResultPlusHeadroom:
        serverPacket >= resultMaxBytes + PACKET_HEADROOM_BYTES,
      jdbcPacketAtLeastResultPlusHeadroom:
        jdbcPacket >= resultMaxBytes + PACKET_HEADROOM_BYTES,
      serverAtLeastJdbc: serverPacket >= jdbcPacket,
      dbPoolAtLeastConcurrencyPlusReserved:
        dbPool >= concurrency + POOL_RESERVED_CONNECTIONS,
    },
  };
}

/**
 * 读取旧/当前 Worker provenance。
 *
 * @param {object} options 选项
 * @returns {object} provenance
 */
function workerProvenance(options) {
  const repoCommit = spawnSync("git", ["rev-parse", "HEAD"], {
    encoding: "utf8",
  }).stdout?.trim();
  const workerJar = "apps/worker/target/riccon-worker-0.1.0-SNAPSHOT-exec.jar";
  let workerJarSha256 = null;
  if (existsSync(workerJar)) {
    workerJarSha256 = createHash("sha256")
      .update(readFileSync(workerJar))
      .digest("hex");
  }
  if (
    options.oldWorkerDigest &&
    !/^sha256:[a-f0-9]{64}$/.test(options.oldWorkerDigest)
  ) {
    throw usageError(
      `--old-worker-digest 必须是 sha256:<64 位十六进制>，实际 ${options.oldWorkerDigest}`,
    );
  }
  let imageDigest = options.oldWorkerDigest;
  let digestSource = options.oldWorkerDigest ? "operator" : null;
  let localImageId = null;
  if (options.oldWorkerTag) {
    const inspect = spawnSync(
      "docker",
      ["image", "inspect", "--format", "{{.Id}}", options.oldWorkerTag],
      { encoding: "utf8" },
    );
    if (inspect.status === 0 && inspect.stdout.trim()) {
      localImageId = inspect.stdout.trim();
      if (!imageDigest) {
        imageDigest = localImageId;
        digestSource = "docker-image-inspect";
      }
    }
  }
  const provenanceComplete = Boolean(options.oldWorkerTag && imageDigest);
  return {
    rollbackTarget: {
      tag: options.oldWorkerTag,
      digest: imageDigest,
      digestSource,
      complete: provenanceComplete,
      localImageId,
      matchesLocalImage: Boolean(imageDigest && localImageId === imageDigest),
    },
    preRollbackWorker: {
      repoCommit,
      jarPath: workerJar,
      jarSha256: workerJarSha256,
      note: "演练开始前本机 Worker 制品指纹；生产演练应同时记录实际镜像 tag/digest",
    },
    note: "回滚目标由部署/操作者提供；脚本校验 digest 形状并尝试匹配本地镜像，但默认不执行镜像切换（旧 Worker 期望 schema 14，需人工核对）",
  };
}

/**
 * 启动演练 API（关闭编排开关），等待健康。
 *
 * @param {string} database 库名
 * @param {object} options 选项
 * @param {object} context 上下文
 * @returns {Promise<object>} API 句柄
 */
async function startDrillApi(database, options, context) {
  const apiJar =
    process.env.RICCON_DRILL_API_JAR ??
    "apps/api/target/riccon-api-0.1.0-SNAPSHOT-exec.jar";
  if (!existsSync(apiJar)) {
    throw envError(`缺少 API 构建产物 ${apiJar}；请先运行合同 build`);
  }
  const port = options.apiPort;
  const logPath = path.join(
    context.evidenceDir,
    `wf-drill-api-${database}.log`,
  );
  const fd = openSync(logPath, "a");
  const env = {
    ...process.env,
    RICCON_DB_URL: `jdbc:mysql://127.0.0.1:13306/${database}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8`,
    RICCON_WORKFLOW_ENABLED: "false",
    RICCON_WORKFLOW_DISABLED_REASON: "drill: workflow rollback window",
    RICCON_WORKFLOW_CLAIM_ENABLED: "false",
    RICCON_API_PORT: String(port),
    RICCON_MASTER_KEY: randomBytes(32).toString("base64"),
    RICCON_CREDENTIAL_FINGERPRINT_PEPPER: randomBytes(32).toString("base64"),
    RICCON_SERVICE_TOKEN_SECRET: randomBytes(24).toString("base64"),
    RICCON_MODEL_DEPLOYMENT_MODE: "DEMO",
    RICCON_DB_MAX_ALLOWED_PACKET: String(
      numberEnv("RICCON_DB_MAX_ALLOWED_PACKET", DEFAULT_JDBC_MAX_PACKET),
    ),
    RICCON_DB_POOL_SIZE: String(
      numberEnv("RICCON_DB_POOL_SIZE", DEFAULT_DB_POOL_SIZE),
    ),
  };
  const child = spawn("java", ["-jar", apiJar], {
    cwd: process.cwd(),
    env,
    stdio: ["ignore", fd, fd],
  });
  const handle = {
    name: `api:${database}`,
    child,
    logPath,
    fd,
    port,
    baseUrl: `http://127.0.0.1:${port}`,
    database,
  };
  startedApis.push(handle);
  await waitForHttp(`${handle.baseUrl}/actuator/health`, 120_000, logPath);
  return handle;
}

/**
 * 停止 API 并回收文件描述符。
 *
 * @param {object} handle API 句柄
 */
async function stopDrillApi(handle) {
  if (!handle || handle.child.exitCode !== null) {
    if (handle?.fd !== undefined) {
      try {
        closeSync(handle.fd);
      } catch {
        // 已关闭。
      }
    }
    return;
  }
  handle.child.kill("SIGTERM");
  const deadline = Date.now() + 20_000;
  while (handle.child.exitCode === null && Date.now() < deadline) {
    await sleep(200);
  }
  if (handle.child.exitCode === null) {
    handle.child.kill("SIGKILL");
    await sleep(500);
  }
  try {
    closeSync(handle.fd);
  } catch {
    // 已关闭。
  }
  const index = startedApis.indexOf(handle);
  if (index >= 0) startedApis.splice(index, 1);
}

/**
 * 启动“停止领取”探针 Worker（CLAIM_ENABLED=false），等待健康。
 *
 * @param {string} database 库名
 * @param {object} options 选项
 * @param {object} context 上下文
 * @returns {Promise<object>} Worker 句柄
 */
async function startDrillWorker(database, options, context) {
  const workerJar =
    process.env.RICCON_DRILL_WORKER_JAR ??
    "apps/worker/target/riccon-worker-0.1.0-SNAPSHOT-exec.jar";
  if (!existsSync(workerJar)) {
    throw envError(`缺少 Worker 构建产物 ${workerJar}；请先运行合同 build`);
  }
  const port = options.workerPort;
  const logPath = path.join(
    context.evidenceDir,
    `wf-drill-worker-${database}.log`,
  );
  const fd = openSync(logPath, "a");
  const env = {
    ...process.env,
    RICCON_DB_URL: `jdbc:mysql://127.0.0.1:13306/${database}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8`,
    RICCON_WORKFLOW_ENABLED: "false",
    RICCON_WORKFLOW_CLAIM_ENABLED: "false",
    RICCON_WORKER_PORT: String(port),
    // 宿主可能缺 libtesseract；关闭 OCR，避免探针 Worker 启动失败。
    RICCON_OCR_ENABLED: "0",
    RICCON_MASTER_KEY: randomBytes(32).toString("base64"),
    RICCON_CREDENTIAL_FINGERPRINT_PEPPER: randomBytes(32).toString("base64"),
    RICCON_SERVICE_TOKEN_SECRET: randomBytes(24).toString("base64"),
    RICCON_MODEL_DEPLOYMENT_MODE: "DEMO",
    RICCON_S3_BUCKET: "riccon-test",
    RICCON_MCP_ALLOW_STDIO: "true",
    RICCON_MCP_ALLOWED_SERVERS: "riccon-builtin",
    RICCON_MCP_ALLOW_HTTP: "true",
    RICCON_DB_MAX_ALLOWED_PACKET: String(
      numberEnv("RICCON_DB_MAX_ALLOWED_PACKET", DEFAULT_JDBC_MAX_PACKET),
    ),
    RICCON_DB_POOL_SIZE: String(
      numberEnv("RICCON_DB_POOL_SIZE", DEFAULT_DB_POOL_SIZE),
    ),
  };
  const child = spawn("java", ["-jar", workerJar], {
    cwd: process.cwd(),
    env,
    stdio: ["ignore", fd, fd],
  });
  const handle = {
    name: `worker:${database}`,
    child,
    logPath,
    fd,
    port,
    baseUrl: `http://127.0.0.1:${port}`,
    database,
    forcedKill: false,
  };
  startedWorkers.push(handle);
  await waitForHttp(`${handle.baseUrl}/actuator/health`, 120_000, logPath);
  return handle;
}

/**
 * 停止探针 Worker 并记录退出方式。
 *
 * @param {object} handle Worker 句柄
 * @returns {Promise<object>} 停止结果
 */
async function stopDrillWorker(handle) {
  if (!handle || handle.child.exitCode !== null) {
    if (handle?.fd !== undefined) {
      try {
        closeSync(handle.fd);
      } catch {
        // 已关闭。
      }
    }
    return {
      exited: true,
      exitCode: handle?.child.exitCode ?? null,
      forcedKill: handle?.forcedKill ?? false,
    };
  }
  handle.child.kill("SIGTERM");
  const deadline = Date.now() + 20_000;
  while (handle.child.exitCode === null && Date.now() < deadline) {
    await sleep(200);
  }
  if (handle.child.exitCode === null) {
    handle.forcedKill = true;
    handle.child.kill("SIGKILL");
    await sleep(500);
  }
  try {
    closeSync(handle.fd);
  } catch {
    // 已关闭。
  }
  const index = startedWorkers.indexOf(handle);
  if (index >= 0) startedWorkers.splice(index, 1);
  return {
    exited: handle.child.exitCode !== null,
    exitCode: handle.child.exitCode,
    forcedKill: handle.forcedKill,
  };
}

/**
 * 读取 Prometheus 指标值（不带标签的标量）。
 *
 * @param {string} baseUrl 基地址
 * @param {string} metric 指标名
 * @returns {Promise<number|null>} 值；不存在时 null
 */
async function scrapeMetricValue(baseUrl, metric) {
  const response = await fetch(`${baseUrl}/actuator/prometheus`);
  assert(
    response.status === 200,
    `/actuator/prometheus 返回 ${response.status}（需在受控网段内）`,
  );
  const text = await response.text();
  let value = null;
  for (const line of text.split("\n")) {
    if (!line || line.startsWith("#")) continue;
    if (line.startsWith(`${metric} `)) {
      value = Number(line.trim().split(/\s+/).pop());
    }
  }
  return Number.isFinite(value) ? value : null;
}

/**
 * 启动 M06 前 Worker 容器，验证回滚窗口与“不领取/不改 workflow 数据”。
 *
 * @param {string} database order 演练库
 * @param {object} options 选项
 * @param {object} before 回滚前活动计数
 * @returns {object} 回滚验证结果
 */
async function executeOldWorkerRollback(database, options, before) {
  assert(
    options.oldWorkerTag,
    "--execute-old-worker 需要 --old-worker-tag（本地 M06 前 Worker 镜像）",
  );
  const inspect = spawnSync(
    "docker",
    ["image", "inspect", "--format", "{{.Id}}", options.oldWorkerTag],
    { encoding: "utf8" },
  );
  assert(
    inspect.status === 0,
    `找不到旧 Worker 镜像 ${options.oldWorkerTag}：${(inspect.stderr || "").trim()}`,
  );
  const name = `wf-drill-old-worker-${Date.now()}`;
  const dbUrl = `jdbc:mysql://host.docker.internal:13306/${database}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8`;
  const env = {
    RICCON_DB_URL: dbUrl,
    RICCON_DB_USERNAME: process.env.RICCON_DB_USERNAME ?? "riccon",
    RICCON_DB_PASSWORD: process.env.RICCON_DB_PASSWORD ?? "riccon_test_only",
    RICCON_WORKER_PORT: "8081",
    RICCON_WORKER_SCHEMA_VALIDATION_ENABLED: "false",
    RICCON_WORKFLOW_ENABLED: "false",
    RICCON_OCR_ENABLED: "0",
    RICCON_MASTER_KEY: randomBytes(32).toString("base64"),
    RICCON_CREDENTIAL_FINGERPRINT_PEPPER: randomBytes(32).toString("base64"),
    RICCON_SERVICE_TOKEN_SECRET: randomBytes(24).toString("base64"),
    RICCON_MODEL_DEPLOYMENT_MODE: "DEMO",
    RICCON_S3_BUCKET: "riccon-test",
    RICCON_MCP_ALLOW_STDIO: "true",
    RICCON_MCP_ALLOWED_SERVERS: "riccon-builtin",
    RICCON_MCP_ALLOW_HTTP: "true",
    RICCON_CONNECTOR_ENABLED: "false",
  };
  const args = [
    "run",
    "-d",
    "--name",
    name,
    "--add-host=host.docker.internal:host-gateway",
    "-p",
    `127.0.0.1:${options.oldWorkerPort}:8081`,
    ...Object.entries(env).flatMap(([key, value]) => ["-e", `${key}=${value}`]),
    options.oldWorkerTag,
  ];
  const started = spawnSync("docker", args, {
    encoding: "utf8",
    maxBuffer: 16 * 1024 * 1024,
  });
  if (started.status !== 0) {
    throw new Error(
      `旧 Worker 容器启动失败 (exit ${started.status}): ${(started.stderr || started.stdout || "").trim()}`,
    );
  }
  const containerId = started.stdout.trim();
  const baseUrl = `http://127.0.0.1:${options.oldWorkerPort}`;
  try {
    // 轮询健康；超时时把容器日志带进错误。
    const deadline = Date.now() + 90_000;
    let healthy = false;
    let lastError = null;
    while (Date.now() < deadline) {
      try {
        const response = await fetch(`${baseUrl}/actuator/health`, {
          signal: AbortSignal.timeout(2000),
        });
        if (response.status === 200) {
          healthy = true;
          break;
        }
        lastError = `status ${response.status}`;
      } catch (error) {
        lastError = error.message;
      }
      await sleep(500);
    }
    const logs = spawnSync("docker", ["logs", name], {
      encoding: "utf8",
      maxBuffer: 64 * 1024 * 1024,
    });
    const logText = `${logs.stdout ?? ""}\n${logs.stderr ?? ""}`;
    if (!healthy) {
      throw new Error(
        `旧 Worker 健康检查超时（${lastError}）；日志尾部：\n${logText.split("\n").slice(-20).join("\n")}`,
      );
    }
    assert(
      logText.includes("RICCON-WORKER-002"),
      "旧 Worker 日志未出现 RICCON-WORKER-002（schema 校验未按回滚窗口关闭）",
    );
    assert(
      logText.includes(
        `expected schema version=${options.oldWorkerExpectedSchema}`,
      ),
      `旧 Worker 期望 schema 不是 ${options.oldWorkerExpectedSchema}（回滚目标不匹配）`,
    );
    const after = activeCounts(database, options);
    assert(
      JSON.stringify(before) === JSON.stringify(after),
      `旧 Worker 启动后活动运行发生变化：before=${JSON.stringify(before)} after=${JSON.stringify(after)}`,
    );
    const schemaLine = logText
      .split("\n")
      .find((line) => line.includes("schema validation disabled"));
    return {
      mode: "container",
      executed: true,
      image: options.oldWorkerTag,
      imageId: inspect.stdout ? inspect.stdout.trim() : null,
      containerId: containerId.slice(0, 12),
      health: 200,
      schemaValidation: "disabled-for-rollback-window",
      expectedSchema: options.oldWorkerExpectedSchema,
      schemaLine: schemaLine ?? null,
      countsBefore: before,
      countsAfter: after,
      noNewClaims: true,
    };
  } finally {
    spawnSync("docker", ["rm", "-f", name], { encoding: "utf8" });
  }
}

/**
 * 读取 Prometheus 指标的最大值（支持标签行）。
 *
 * @param {string} baseUrl 基地址
 * @param {string} metric 指标名
 * @returns {Promise<number|null>} 最大值；不存在时 null
 */
async function scrapeMetricMax(baseUrl, metric) {
  const response = await fetch(`${baseUrl}/actuator/prometheus`);
  assert(
    response.status === 200,
    `/actuator/prometheus 返回 ${response.status}（需在受控网段内）`,
  );
  const text = await response.text();
  let value = null;
  for (const line of text.split("\n")) {
    if (!line || line.startsWith("#")) continue;
    if (!line.startsWith(`${metric} `) && !line.startsWith(`${metric}{`)) {
      continue;
    }
    const parsed = Number(line.trim().split(/\s+/).pop());
    if (!Number.isFinite(parsed)) continue;
    value = value === null ? parsed : Math.max(value, parsed);
  }
  return value;
}

/**
 * 等待 HTTP 健康。
 *
 * @param {string} url URL
 * @param {number} timeoutMs 超时
 * @param {string} logPath 日志路径
 */
async function waitForHttp(url, timeoutMs, logPath) {
  const deadline = Date.now() + timeoutMs;
  let lastError = null;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(2000) });
      if (response.status === 200) return;
      lastError = `status ${response.status}`;
    } catch (error) {
      lastError = error.message;
    }
    await sleep(500);
  }
  const tail = existsSync(logPath)
    ? readFileSync(logPath, "utf8").split("\n").slice(-20).join("\n")
    : "(no log)";
  throw new Error(`等待 ${url} 超时（${lastError}）；日志尾部：\n${tail}`);
}

/** @param {number} ms 毫秒 @returns {Promise<void>} */
function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * 读取可用于演练的流程/版本/用户。
 *
 * @param {string} database 库名
 * @param {object} options 选项
 * @param {string} tenant 租户
 * @returns {object} 引用
 */
function workflowReference(database, options, tenant) {
  const rows = queryRows(
    database,
    `SELECT LOWER(HEX(d.id)), LOWER(HEX(d.published_version_id)), v.version_no,
            LOWER(HEX(d.owner_user_id))
       FROM workflow_definition d
       JOIN workflow_version v
         ON v.tenant_id = d.tenant_id AND v.id = d.published_version_id
      WHERE d.tenant_id = ${sqlString(tenant)} AND d.published_version_id IS NOT NULL
      ORDER BY d.created_at
      LIMIT 1;`,
    options,
  );
  assert(rows.length === 1, `租户 ${tenant} 没有已发布流程，无法演练`);
  return {
    tenant,
    workflowId: dashedUuid(rows[0][0]),
    versionId: dashedUuid(rows[0][1]),
    versionNo: Number(rows[0][2]),
    ownerId: dashedUuid(rows[0][3]),
  };
}

/**
 * 读取租户内指定用户名。
 *
 * @param {string} database 库名
 * @param {object} options 选项
 * @param {string} tenant 租户
 * @param {string} username 用户名
 * @returns {string} 用户 UUID
 */
function userIdByName(database, options, tenant, username) {
  const hex = queryValue(
    database,
    `SELECT LOWER(HEX(id)) FROM app_user
      WHERE tenant_id = ${sqlString(tenant)} AND username = ${sqlString(username)} LIMIT 1;`,
    options,
  );
  assert(hex, `找不到用户 ${tenant}/${username}`);
  return dashedUuid(hex);
}

/**
 * 插入一条运行夹具。
 *
 * @param {string} database 库名
 * @param {object} options 选项
 * @param {object} run 运行
 */
function insertRunFixture(database, options, run) {
  const status = run.status;
  const leaseOwner =
    status === "RUNNING" ? sqlString("wf-drill-old-worker") : "NULL";
  const leaseUntil =
    status === "RUNNING"
      ? "DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 90 SECOND)"
      : "NULL";
  const startedAt = status === "RUNNING" ? "UTC_TIMESTAMP(6)" : "NULL";
  const finishedAt = status === "SUCCEEDED" ? "UTC_TIMESTAMP(6)" : "NULL";
  runSql(
    database,
    `INSERT INTO workflow_run (
       tenant_id, id, workflow_id, workflow_version_id, version_no, requester_user_id,
       status, inputs_json, result_status, result_size_bytes, current_node_key,
       execution_ms, approval_wait_ms, lease_owner, lease_until, claim_attempts,
       idempotency_key, retention_expires_at, created_at, updated_at, started_at, finished_at
     ) VALUES (
       ${sqlString(run.tenant)}, ${sqlUuid(run.id)}, ${sqlUuid(run.workflowId)},
       ${sqlUuid(run.versionId)}, ${run.versionNo}, ${sqlUuid(run.requesterId)},
       ${sqlString(status)}, JSON_OBJECT(), 'NONE', 0, NULL,
       0, 0, ${leaseOwner}, ${leaseUntil}, 0,
       ${sqlString(run.idempotencyKey)},
       DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 180 DAY), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
       ${startedAt}, ${finishedAt}
     );`,
    options,
  );
}

/**
 * 确保 tenant-beta 有可用于跨租户夹具的流程/版本。
 *
 * @param {string} database 库名
 * @param {object} options 选项
 * @param {object} alphaRef alpha 引用
 * @param {string} betaUser beta 用户
 * @returns {object} beta 引用
 */
function ensureBetaReference(database, options, alphaRef, betaUser) {
  const existing = queryRows(
    database,
    `SELECT LOWER(HEX(d.id)), LOWER(HEX(d.published_version_id)), v.version_no
       FROM workflow_definition d
       JOIN workflow_version v
         ON v.tenant_id = d.tenant_id AND v.id = d.published_version_id
      WHERE d.tenant_id = 'tenant-beta' AND d.published_version_id IS NOT NULL
      LIMIT 1;`,
    options,
  );
  if (existing.length === 1) {
    return {
      tenant: "tenant-beta",
      workflowId: dashedUuid(existing[0][0]),
      versionId: dashedUuid(existing[0][1]),
      versionNo: Number(existing[0][2]),
      ownerId: betaUser,
    };
  }
  const definitionId = randomUUID();
  const versionId = randomUUID();
  runSql(
    database,
    `INSERT INTO workflow_definition (
       tenant_id, id, name, description, owner_user_id, draft_version, draft_graph, status,
       published_version_id, published_version_no, source_template_id, source_template_type,
       source_template_version, created_by, updated_by, created_at, updated_at
     )
     SELECT 'tenant-beta', ${sqlUuid(definitionId)}, CONCAT(name, '（回滚演练）'), description,
            ${sqlUuid(betaUser)}, draft_version, draft_graph, 'ACTIVE',
            ${sqlUuid(versionId)}, 1, NULL, NULL, NULL,
            ${sqlUuid(betaUser)}, ${sqlUuid(betaUser)}, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
       FROM workflow_definition
      WHERE tenant_id = 'tenant-alpha' AND id = ${sqlUuid(alphaRef.workflowId)};`,
    options,
  );
  runSql(
    database,
    `INSERT INTO workflow_version (
       tenant_id, id, workflow_id, version_no, graph_json, graph_sha256, node_count,
       run_scope, department_id, published_by, published_at, created_at
     )
     SELECT 'tenant-beta', ${sqlUuid(versionId)}, ${sqlUuid(definitionId)}, 1,
            graph_json, graph_sha256, node_count, 'ONLY_SELF', NULL,
            ${sqlUuid(betaUser)}, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
       FROM workflow_version
      WHERE tenant_id = 'tenant-alpha' AND id = ${sqlUuid(alphaRef.versionId)};`,
    options,
  );
  return {
    tenant: "tenant-beta",
    workflowId: definitionId,
    versionId,
    versionNo: 1,
    ownerId: betaUser,
  };
}

/**
 * 5 MiB/256 KiB 容量插入与校验。
 *
 * @param {string} database 库名
 * @param {object} options 选项
 * @param {object} reference 流程引用
 * @param {object} preflight 包大小预检
 * @returns {object} 容量结果
 */
function capacityDrill(database, options, reference, preflight) {
  const resultRunId = randomUUID();
  const payloadSize = preflight.resultMaxBytes - 4096 - 16;
  const expected = queryRow(
    database,
    `SELECT OCTET_LENGTH(JSON_OBJECT('payload', REPEAT('a', ${payloadSize}))),
            SHA2(JSON_OBJECT('payload', REPEAT('a', ${payloadSize})), 256);`,
    options,
  );
  const expectedBytes = Number(expected[0]);
  const expectedHash = expected[1];
  assert(
    expectedBytes <= preflight.resultMaxBytes &&
      expectedBytes >= preflight.resultMaxBytes - 8192,
    `目标结果大小 ${expectedBytes} 未落在 5 MiB 附近`,
  );
  insertRunFixture(database, options, {
    tenant: reference.tenant,
    id: resultRunId,
    workflowId: reference.workflowId,
    versionId: reference.versionId,
    versionNo: reference.versionNo,
    requesterId: reference.ownerId,
    status: "SUCCEEDED",
    idempotencyKey: `wf-drill-capacity-${Date.now()}`,
  });
  runSql(
    database,
    `UPDATE workflow_run
        SET result_json = JSON_OBJECT('payload', REPEAT('a', ${payloadSize})),
            result_status = 'SAVED'
      WHERE tenant_id = ${sqlString(reference.tenant)} AND id = ${sqlUuid(resultRunId)};
     UPDATE workflow_run
        SET result_size_bytes = OCTET_LENGTH(result_json)
      WHERE tenant_id = ${sqlString(reference.tenant)} AND id = ${sqlUuid(resultRunId)};`,
    options,
  );
  const stored = queryRow(
    database,
    `SELECT OCTET_LENGTH(result_json), SHA2(result_json, 256), result_size_bytes,
            JSON_VALID(result_json)
       FROM workflow_run
      WHERE tenant_id = ${sqlString(reference.tenant)} AND id = ${sqlUuid(resultRunId)};`,
    options,
  );
  assert(
    Number(stored[0]) === expectedBytes,
    `result_json 长度 ${stored[0]} != 期望 ${expectedBytes}（疑似截断）`,
  );
  assert(
    stored[1] === expectedHash,
    "result_json 哈希与期望不一致（疑似静默截断/改写）",
  );
  assert(
    Number(stored[2]) === expectedBytes,
    `result_size_bytes ${stored[2]} != ${expectedBytes}`,
  );
  assert(stored[3] === "1", "result_json 不是合法 JSON");

  const nodePayloadSize = preflight.nodeOutputMaxBytes - 1024 - 16;
  const nodeExpected = queryRow(
    database,
    `SELECT OCTET_LENGTH(JSON_OBJECT('text', REPEAT('b', ${nodePayloadSize}))),
            SHA2(JSON_OBJECT('text', REPEAT('b', ${nodePayloadSize})), 256);`,
    options,
  );
  const nodeExpectedBytes = Number(nodeExpected[0]);
  assert(
    nodeExpectedBytes <= preflight.nodeOutputMaxBytes &&
      nodeExpectedBytes >= preflight.nodeOutputMaxBytes - 2048,
    `节点输出目标大小 ${nodeExpectedBytes} 未落在 256 KiB 附近`,
  );
  runSql(
    database,
    `INSERT INTO workflow_node_run (
       tenant_id, id, workflow_run_id, node_key, node_type, node_name, attempt, status,
       output_json, started_at, finished_at, created_at, updated_at
     ) VALUES (
       ${sqlString(reference.tenant)}, ${sqlUuid(randomUUID())}, ${sqlUuid(resultRunId)},
       'capacity-node', 'MODEL', '容量演练节点', 1, 'SUCCEEDED',
       JSON_OBJECT('text', REPEAT('b', ${nodePayloadSize})), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
       UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
     );`,
    options,
  );
  const nodeStored = queryRow(
    database,
    `SELECT OCTET_LENGTH(output_json), SHA2(output_json, 256)
       FROM workflow_node_run
      WHERE tenant_id = ${sqlString(reference.tenant)} AND workflow_run_id = ${sqlUuid(resultRunId)}
        AND node_key = 'capacity-node' AND attempt = 1;`,
    options,
  );
  assert(
    Number(nodeStored[0]) === nodeExpectedBytes,
    `output_json 长度 ${nodeStored[0]} != 期望 ${nodeExpectedBytes}（疑似截断）`,
  );
  assert(nodeStored[1] === nodeExpected[1], "output_json 哈希与期望不一致");
  return {
    result: {
      runId: resultRunId,
      payloadBytes: payloadSize,
      storedBytes: expectedBytes,
      limitBytes: preflight.resultMaxBytes,
      headroomBytes: preflight.resultMaxBytes - expectedBytes,
      sha256Match: true,
      jsonValid: true,
      resultSizeBytesMatches: true,
      ratioToLimit: Number(
        (expectedBytes / preflight.resultMaxBytes).toFixed(4),
      ),
    },
    nodeOutput: {
      storedBytes: nodeExpectedBytes,
      limitBytes: preflight.nodeOutputMaxBytes,
      headroomBytes: preflight.nodeOutputMaxBytes - nodeExpectedBytes,
      sha256Match: true,
      note: "单节点上限由 WorkflowEngine/WorkflowEngineLimits 在写入前检查；本演练验证存储与传输不截断",
    },
    note: "数据库本身不强制 5 MiB/256 KiB；应用层上限由 WorkflowEngine 与 WF_RESULT_LIMIT 测试覆盖（IT-WF-109/E2E-M06-09）",
  };
}

/**
 * 近邻分位（nearest-rank）。
 *
 * @param {number[]} values 数值
 * @param {number} percentile 分位（0-100）
 * @returns {number} 分位值
 */
function percentile(values, percentileValue) {
  if (values.length === 0) return null;
  const sorted = [...values].sort((left, right) => left - right);
  const rank = Math.ceil((percentileValue / 100) * sorted.length);
  return sorted[Math.min(sorted.length - 1, Math.max(0, rank - 1))];
}

/**
 * 容量报告（RUNBOOK M06-11 公式）。
 *
 * @param {string} database 源库
 * @param {object} options 选项
 * @param {object} preflight 包大小预检
 * @returns {object} 容量
 */
function capacityReport(database, options, preflight) {
  const values = queryRows(
    database,
    `SELECT result_size_bytes FROM workflow_run
      WHERE result_status = 'SAVED' AND result_size_bytes > 0 ORDER BY result_size_bytes;`,
    options,
  ).map((row) => Number(row[0]));
  const observedP95 = percentile(values, 95);
  const p95Bytes = options.p95Bytes ?? observedP95 ?? preflight.resultMaxBytes;
  const overheadFactor = 1.35;
  const dailyBytes = Math.round(options.runsPerDay * p95Bytes * overheadFactor);
  const retainedBytes = dailyBytes * options.retentionDays;
  const withBackupBytes = retainedBytes * 2;
  // 结果接近 5 MiB 时的上界场景：用于磁盘/备份预留，不代表当前 p95。
  const worstDailyBytes = Math.round(
    options.runsPerDay * preflight.resultMaxBytes * overheadFactor,
  );
  const worstWithBackupBytes = worstDailyBytes * options.retentionDays * 2;
  const dbSize = Number(
    queryValue(
      database,
      `SELECT COALESCE(SUM(data_length + index_length), 0)
         FROM information_schema.tables
        WHERE table_schema = ${sqlString(database)}
          AND table_name LIKE 'workflow_%';`,
      options,
    ),
  );
  return {
    formula:
      "日容量 ≈ 每日运行数 × 结果大小 p95 × (1 + 节点输出/审批/索引开销系数)；保留容量 = 日容量 × 保留天数 × (1 + binlog/备份副本倍数)",
    inputs: {
      runsPerDay: options.runsPerDay,
      p95Bytes,
      p95Source:
        options.p95Bytes !== null
          ? "CLI --p95-bytes"
          : observedP95 === null
            ? "fallback resultMaxBytes（无历史结果）"
            : "源库 workflow_run.result_size_bytes nearest-rank",
      observedSampleCount: values.length,
      observedP95,
      overheadFactor,
      retentionDays: options.retentionDays,
      backupMultiplier: 2,
    },
    estimates: {
      dailyBytes,
      retainedBytes,
      withBackupBytes,
      withBackupMiB: Number((withBackupBytes / (1024 * 1024)).toFixed(2)),
    },
    worstCaseAtResultMax: {
      resultMaxBytes: preflight.resultMaxBytes,
      dailyBytes: worstDailyBytes,
      retainedBytes: worstDailyBytes * options.retentionDays,
      withBackupBytes: worstWithBackupBytes,
      withBackupMiB: Number((worstWithBackupBytes / (1024 * 1024)).toFixed(2)),
      note: "每次运行都接近 5 MiB 的上界；用于磁盘/备份预留，不是预期值",
    },
    observedWorkflowTableBytes: dbSize,
    sourceDatabase: database,
    caveat:
      "源库为假模型测试数据时 p95 偏小；生产容量必须用客户真实分位数校准（riccon_workflow_result_size_bytes）",
  };
}

/**
 * 从数据栈/夹具导出并导入。
 *
 * @param {string} target 目标库
 * @param {object} options 选项
 * @param {object} context 上下文
 * @returns {object} 信息
 */
function cloneDataStack(target, options, context) {
  const dump = options.dataDump
    ? readFileSync(path.resolve(options.dataDump), "utf8")
    : dumpDatabase(options.source, options);
  importSql(target, dump, options);
  const metrics = schemaMetrics(target, options);
  assert(
    metrics.schemaVersion === EXPECTED_SCHEMA_VERSION,
    `数据栈/夹具 ${target} 的 schema 版本 ${metrics.schemaVersion} != ${EXPECTED_SCHEMA_VERSION}`,
  );
  const counts = decisionCounts(target, options);
  const nonZero = DECISION_KEYS.filter((key) => counts[key] > 0);
  assert(
    counts.definitions > 0,
    `数据栈 ${target} 没有 workflow_definition；请用 --data-dump 提供含流程/运行/审批的备份夹具`,
  );
  assert(
    counts.runs > 0,
    `数据栈 ${target} 没有 workflow_run；请用 --data-dump 提供含流程/运行/审批的备份夹具`,
  );
  assert(
    counts.workflow_audit_events > 0,
    `数据栈 ${target} 没有 workflow.* 审计；请用 --data-dump 提供备份夹具`,
  );
  assert(
    nonZero.length > 0,
    `数据栈 ${target} 判定 SQL 意外全 0，不能证明“有数据拒绝”`,
  );
  return {
    source: options.dataDump ? path.resolve(options.dataDump) : options.source,
    bytes: Buffer.byteLength(dump, "utf8"),
    counts,
    nonZero,
    metrics,
    context,
  };
}

/**
 * 创建空栈（结构 + Flyway 历史，无业务数据）。
 *
 * @param {string} target 目标库
 * @param {object} options 选项
 * @returns {object} 信息
 */
function cloneEmptyStack(target, options) {
  const schema = dumpDatabase(options.source, options, true);
  importSql(target, schema, options);
  runSql(
    target,
    `INSERT INTO flyway_schema_history
     SELECT * FROM \`${options.source}\`.flyway_schema_history;`,
    options,
  );
  const metrics = schemaMetrics(target, options);
  assert(
    metrics.schemaVersion === EXPECTED_SCHEMA_VERSION,
    `空栈 ${target} 的 schema 版本 ${metrics.schemaVersion} != ${EXPECTED_SCHEMA_VERSION}`,
  );
  const counts = decisionCounts(target, options);
  return { metrics, counts };
}

/**
 * 执行一次带超时的 shell 命令。
 *
 * @param {string} command 命令
 * @param {number} timeoutMs 超时
 * @returns {object} 结果
 */
function runShell(command, timeoutMs = 120_000) {
  const result = spawnSync("sh", ["-c", command], {
    cwd: process.cwd(),
    encoding: "utf8",
    timeout: timeoutMs,
    maxBuffer: 16 * 1024 * 1024,
  });
  return {
    exitCode: result.status,
    signal: result.signal,
    stdout: (result.stdout ?? "").slice(0, 4000),
    stderr: (result.stderr ?? "").slice(0, 4000),
  };
}

/**
 * 记录一条断言。
 *
 * @param {object[]} assertions 收集器
 * @param {string} id ID
 * @param {string} expected 期望
 * @param {unknown} actual 实际
 * @param {string} [note] 说明
 */
function record(assertions, id, expected, actual, note) {
  assertions.push({
    id,
    status: String(expected) === String(actual) ? "pass" : "fail",
    expected: String(expected),
    actual: String(actual),
    ...(note ? { note } : {}),
  });
}

/**
 * 主演练流程。
 *
 * @param {object} options 选项
 * @param {object} context 上下文
 * @returns {Promise<object>} 报告
 */
async function runDrill(options, context) {
  const { evidenceDir } = context;
  const report = {
    drill: "workflow-rollback",
    version: 1,
    task: evidenceTask,
    mode: "apply",
    startedAt: new Date().toISOString(),
    environment: {
      via: options.via,
      sourceDatabase: options.source,
      drillPrefix: DRILL_PREFIX,
      testedAt: new Date().toISOString(),
      timezone: "Asia/Shanghai",
    },
    worker: null,
    config: null,
    phases: [],
    assertions: [],
    residuals: [],
    artifacts: {},
  };
  const databases = [];
  let api = null;

  try {
    // 预检：源库 V15、结构、包大小、Hikari、Worker provenance。
    const sourceMetrics = requireV15(options.source, options);
    report.environment.source = sourceMetrics;
    report.worker = workerProvenance(options);
    report.config = packetAndPoolPreflight(options.source, options);
    report.assertions.push(
      {
        id: "schema.v15",
        status: "pass",
        expected: EXPECTED_SCHEMA_VERSION,
        actual: sourceMetrics.schemaVersion,
      },
      {
        id: "schema.tables",
        status: "pass",
        expected: EXPECTED_TABLE_COUNT,
        actual: sourceMetrics.tables,
      },
      {
        id: "schema.triggers",
        status: "pass",
        expected: EXPECTED_TRIGGER_COUNT,
        actual: sourceMetrics.triggers,
      },
    );
    if (!report.worker.rollbackTarget.complete) {
      const message =
        "旧 Worker tag/digest 未提供：生产演练必须由部署登记回滚目标（--old-worker-tag/--old-worker-digest）";
      if (options.allowPartial) {
        report.residuals.push({
          id: "R-WF-DRILL-01",
          text: message,
          accepted: true,
        });
      } else {
        throw envError(`${message}；如仅本地验证请加 --allow-partial`);
      }
    }

    // 1) 无数据允许：首次禁用启动前的独立空栈。
    const stamp = new Date()
      .toISOString()
      .replace(/[-:TZ.]/g, "")
      .slice(0, 14);
    const emptyDb = createDrillDatabase(
      `${DRILL_PREFIX}empty_${stamp}`,
      options,
    );
    databases.push(emptyDb);
    const empty = cloneEmptyStack(emptyDb.name, options);
    const emptyDecision = fullRollbackDecision(empty.counts);
    assert(
      emptyDecision.allowed && emptyDecision.exitCode === 0,
      `空栈判定未允许：${JSON.stringify(empty.counts)}`,
    );
    report.phases.push({
      id: "empty-allow",
      status: "pass",
      database: emptyDb.name,
      counts: empty.counts,
      decision: emptyDecision,
      emergencyDrop: {
        allowed: true,
        executed: false,
        note: "演练只验证判定；应急 drop SQL 的权威维护点在 V15MigrationTest/RUNBOOK M06-9/ADR-0024 §10.4，脚本不另存一份",
      },
    });
    record(report.assertions, "empty.allZero", true, emptyDecision.allowed);
    record(report.assertions, "empty.gateExit", 0, emptyDecision.exitCode);

    // 2) 有数据拒绝：独立数据栈/备份夹具。
    const dataDb = createDrillDatabase(`${DRILL_PREFIX}data_${stamp}`, options);
    databases.push(dataDb);
    const dataInfo = cloneDataStack(dataDb.name, options, context);
    const dataDecision = fullRollbackDecision(dataInfo.counts);
    assert(
      !dataDecision.allowed && dataDecision.exitCode === 3,
      `有数据栈未拒绝：${JSON.stringify(dataInfo.counts)}`,
    );
    report.phases.push({
      id: "data-reject",
      status: "pass",
      database: dataDb.name,
      source: dataInfo.source,
      dumpBytes: dataInfo.bytes,
      counts: dataInfo.counts,
      nonZero: dataInfo.nonZero,
      decision: dataDecision,
    });
    record(report.assertions, "data.rejected", true, !dataDecision.allowed);
    record(report.assertions, "data.gateExit", 3, dataDecision.exitCode);

    // 3) 5 MiB/256 KiB 容量与包大小。
    // 容量分位数取源库历史数据（在容量插入之前），避免把演练自己插入的 5 MiB 行算进 p95。
    const capacityStats = capacityReport(
      options.source,
      options,
      report.config,
    );
    const reference = workflowReference(dataDb.name, options, "tenant-alpha");
    const capacity = capacityDrill(
      dataDb.name,
      options,
      reference,
      report.config,
    );
    report.phases.push({
      id: "capacity-5mib",
      status: "pass",
      database: dataDb.name,
      ...capacity,
      capacityReport: capacityStats,
    });
    record(
      report.assertions,
      "capacity.resultNear5MiB",
      true,
      capacity.result.storedBytes <= RESULT_MAX_BYTES &&
        capacity.result.storedBytes >= RESULT_MAX_BYTES - 8192,
    );
    record(
      report.assertions,
      "capacity.resultNoTruncation",
      true,
      capacity.result.sha256Match,
    );
    record(
      report.assertions,
      "capacity.nodeNear256KiB",
      true,
      capacity.nodeOutput.storedBytes <= NODE_OUTPUT_MAX_BYTES &&
        capacity.nodeOutput.storedBytes >= NODE_OUTPUT_MAX_BYTES - 2048,
    );

    // 4) 无窗口回滚顺序：独立 order 栈 + 真实 API（ENABLED=false）。
    const orderDb = createDrillDatabase(
      `${DRILL_PREFIX}order_${stamp}`,
      options,
    );
    databases.push(orderDb);
    importSql(orderDb.name, dumpDatabase(options.source, options), options);
    const orderMetrics = schemaMetrics(orderDb.name, options);
    assert(
      orderMetrics.schemaVersion === EXPECTED_SCHEMA_VERSION,
      `order 栈 schema ${orderMetrics.schemaVersion} != ${EXPECTED_SCHEMA_VERSION}`,
    );
    api = await startDrillApi(orderDb.name, options, context);
    report.artifacts.apiLog = api.logPath;
    const client = new ApiClient(api.baseUrl);
    await client.login(DRILL_TENANT, DRILL_USERNAME, DRILL_PASSWORD);

    const limitsResponse = await client.get("/api/v1/workflows/runtime-limits");
    assert(
      limitsResponse.status === 200,
      `runtime-limits 返回 ${limitsResponse.status}`,
    );
    assert(
      limitsResponse.data.featureEnabled === false,
      `runtime-limits featureEnabled=${limitsResponse.data.featureEnabled}，期望 false`,
    );
    assert(
      limitsResponse.data.disabledReason === "drill: workflow rollback window",
      `disabledReason=${limitsResponse.data.disabledReason}`,
    );
    const alphaRef = workflowReference(orderDb.name, options, "tenant-alpha");
    const createResponse = await client.post(
      `/api/v1/workflows/${alphaRef.workflowId}/runs`,
      { inputs: {} },
      { "Idempotency-Key": `wf-drill-disabled-${Date.now()}` },
    );
    assert(
      createResponse.status === 503 &&
        createResponse.data?.code === "RICCON-WF-503",
      `关闭态创建运行应 503 RICCON-WF-503，实际 ${createResponse.status} ${createResponse.text}`,
    );
    record(
      report.assertions,
      "order.featureDisabled",
      false,
      limitsResponse.data.featureEnabled,
    );
    record(report.assertions, "order.createRun503", 503, createResponse.status);

    // 夹具：WAITING/PENDING/RUNNING（alpha）。
    const alphaAdminId = userIdByName(
      orderDb.name,
      options,
      "tenant-alpha",
      "alpha-admin",
    );
    const alphaMemberId = userIdByName(
      orderDb.name,
      options,
      "tenant-alpha",
      "alpha-member",
    );
    const fixtures = {
      waiting: randomUUID(),
      pending: randomUUID(),
      running: randomUUID(),
      betaWaiting: randomUUID(),
    };
    insertRunFixture(orderDb.name, options, {
      tenant: "tenant-alpha",
      id: fixtures.waiting,
      workflowId: alphaRef.workflowId,
      versionId: alphaRef.versionId,
      versionNo: alphaRef.versionNo,
      requesterId: alphaMemberId,
      status: "WAITING_APPROVAL",
      idempotencyKey: `wf-drill-waiting-${Date.now()}`,
    });
    insertRunFixture(orderDb.name, options, {
      tenant: "tenant-alpha",
      id: fixtures.pending,
      workflowId: alphaRef.workflowId,
      versionId: alphaRef.versionId,
      versionNo: alphaRef.versionNo,
      requesterId: alphaMemberId,
      status: "PENDING",
      idempotencyKey: `wf-drill-pending-${Date.now()}`,
    });
    insertRunFixture(orderDb.name, options, {
      tenant: "tenant-alpha",
      id: fixtures.running,
      workflowId: alphaRef.workflowId,
      versionId: alphaRef.versionId,
      versionNo: alphaRef.versionNo,
      requesterId: alphaMemberId,
      status: "RUNNING",
      idempotencyKey: `wf-drill-running-${Date.now()}`,
    });

    // 步骤 2：停止领取并等宽限。启动一个 CLAIM_ENABLED=false 的真实 Worker 探针，
    // 断言 claim gauge=0、调度器因 CLAIM_ENABLED_FALSE 停用，且宽限内 PENDING/RUNNING 快照不变。
    const probeWorker = await startDrillWorker(orderDb.name, options, context);
    report.artifacts.workerLog = probeWorker.logPath;
    const claimMetric = await scrapeMetricValue(
      probeWorker.baseUrl,
      "riccon_workflow_claim_enabled",
    );
    assert(
      claimMetric === 0,
      `Worker riccon_workflow_claim_enabled=${claimMetric}，期望 0`,
    );
    const hikaricpMax = await scrapeMetricMax(
      probeWorker.baseUrl,
      "hikaricp_connections_max",
    );
    assert(
      hikaricpMax !== null && hikaricpMax >= report.config.poolMinimum,
      `Worker Hikari max=${hikaricpMax} 小于 ${report.config.poolMinimum}（并发+4）`,
    );
    const workerLog = readFileSync(probeWorker.logPath, "utf8");
    const schedulerLine = workerLog
      .split("\n")
      .find(
        (line) =>
          line.includes("workflow.queue.scheduler.disabled") ||
          line.includes("CLAIM_ENABLED_FALSE"),
      );
    assert(
      workerLog.includes("CLAIM_ENABLED_FALSE"),
      "Worker 日志未出现 CLAIM_ENABLED_FALSE（停止领取未生效）",
    );
    const beforeGrace = queryRows(
      orderDb.name,
      `SELECT id, status, IFNULL(lease_owner, ''), IFNULL(lease_until, '')
         FROM workflow_run
        WHERE status IN ('WAITING_APPROVAL','PENDING','RUNNING')
        ORDER BY id;`,
      options,
    );
    const observedBefore = activeCounts(orderDb.name, options);
    report.phases.push({
      id: "claim-stop-grace",
      status: "pending",
      claimEnabled: false,
      claimMetric,
      hikaricpConnectionsMax: hikaricpMax,
      schedulerLine: schedulerLine ?? null,
      graceSeconds: options.graceSeconds,
      graceSecondsSource:
        process.env.RICCON_WORKFLOW_SHUTDOWN_GRACE_SECONDS !== undefined
          ? "env RICCON_WORKFLOW_SHUTDOWN_GRACE_SECONDS"
          : options.graceSeconds === 120
            ? "default 120s"
            : "CLI override（演练缩短；生产按默认 120s）",
      observedBefore,
      note: "真实 Worker 以 RICCON_WORKFLOW_CLAIM_ENABLED=false 启动；宽限内不得出现新 claim",
    });
    await sleep(options.graceSeconds * 1000);
    const afterGrace = queryRows(
      orderDb.name,
      `SELECT id, status, IFNULL(lease_owner, ''), IFNULL(lease_until, '')
         FROM workflow_run
        WHERE status IN ('WAITING_APPROVAL','PENDING','RUNNING')
        ORDER BY id;`,
      options,
    );
    assert(
      JSON.stringify(beforeGrace) === JSON.stringify(afterGrace),
      "宽限期间活动运行/租约发生变化，说明仍有领取或状态推进",
    );
    const workerStop = await stopDrillWorker(probeWorker);
    assert(workerStop.exited, "停止领取探针 Worker 未退出");
    report.phases.at(-1).status = "pass";
    report.phases.at(-1).observedAfter = activeCounts(orderDb.name, options);
    report.phases.at(-1).workerStop = workerStop;
    record(report.assertions, "order.workerClaimDisabled", 0, claimMetric);
    record(
      report.assertions,
      "order.hikaricpAtLeastPoolMinimum",
      true,
      hikaricpMax !== null && hikaricpMax >= report.config.poolMinimum,
    );
    record(report.assertions, "order.workerStopped", true, workerStop.exited);
    record(
      report.assertions,
      "order.noClaimDuringGrace",
      true,
      JSON.stringify(beforeGrace) === JSON.stringify(afterGrace),
    );

    // 步骤 3：取消全部 WAITING_APPROVAL 并断言 0。
    const waitingRows = queryRows(
      orderDb.name,
      `SELECT LOWER(HEX(id)), tenant_id FROM workflow_run
        WHERE status = 'WAITING_APPROVAL' ORDER BY created_at, id;`,
      options,
    );
    const cancelledWaiting = [];
    for (const [hex, tenant] of waitingRows) {
      if (tenant !== DRILL_TENANT) {
        continue;
      }
      const runId = dashedUuid(hex);
      const response = await client.post(
        `/api/v1/workflow-runs/${runId}/cancel`,
        undefined,
        { "Idempotency-Key": `wf-drill-cancel-wait-${Date.now()}` },
      );
      assert(
        response.status === 202,
        `取消 WAITING_APPROVAL ${runId} 返回 ${response.status} ${response.text}`,
      );
      cancelledWaiting.push(runId);
    }
    const afterWaiting = activeCounts(orderDb.name, options);
    assert(
      afterWaiting.waitingApproval === 0,
      `取消后 WAITING_APPROVAL=${afterWaiting.waitingApproval}，期望 0`,
    );
    record(
      report.assertions,
      "order.waitingZero",
      0,
      afterWaiting.waitingApproval,
    );

    // 步骤 4：清零 PENDING/RUNNING 并断言三者均 0。
    const pendingRows = queryRows(
      orderDb.name,
      `SELECT LOWER(HEX(id)) FROM workflow_run
        WHERE status IN ('PENDING','RUNNING') ORDER BY created_at, id;`,
      options,
    );
    const cancelledActive = [];
    for (const [hex] of pendingRows) {
      const runId = dashedUuid(hex);
      const response = await client.post(
        `/api/v1/workflow-runs/${runId}/cancel`,
        undefined,
        { "Idempotency-Key": `wf-drill-cancel-active-${Date.now()}` },
      );
      assert(
        response.status === 202,
        `取消 ${runId} 返回 ${response.status} ${response.text}`,
      );
      cancelledActive.push(runId);
    }
    const cleared = activeCounts(orderDb.name, options);
    const clearedGuard = workerRollbackGuard(cleared);
    assert(
      clearedGuard.allowed,
      `清零后守卫仍拒绝：${JSON.stringify(cleared)}`,
    );
    report.phases.push({
      id: "clear-active",
      status: "pass",
      cancelledWaiting,
      cancelledActive,
      counts: cleared,
      guard: clearedGuard,
    });
    record(
      report.assertions,
      "order.allActiveZero",
      true,
      clearedGuard.allowed,
    );

    // 步骤 4b：清零后又出现新行（跨租户 WAITING_APPROVAL）→ 无法清零时必须拒绝回滚 Worker。
    const betaUser = userIdByName(
      orderDb.name,
      options,
      "tenant-beta",
      "beta-member",
    );
    const betaRef = ensureBetaReference(
      orderDb.name,
      options,
      alphaRef,
      betaUser,
    );
    insertRunFixture(orderDb.name, options, {
      tenant: "tenant-beta",
      id: fixtures.betaWaiting,
      workflowId: betaRef.workflowId,
      versionId: betaRef.versionId,
      versionNo: betaRef.versionNo,
      requesterId: betaUser,
      status: "WAITING_APPROVAL",
      idempotencyKey: `wf-drill-beta-waiting-${Date.now()}`,
    });
    const withBeta = activeCounts(orderDb.name, options);
    assert(
      withBeta.waitingApproval === 1,
      `跨租户夹具后 WAITING=${withBeta.waitingApproval}，期望 1`,
    );
    const betaCancel = await client.post(
      `/api/v1/workflow-runs/${fixtures.betaWaiting}/cancel`,
      undefined,
      { "Idempotency-Key": `wf-drill-cancel-beta-${Date.now()}` },
    );
    assert(
      betaCancel.status === 404,
      `alpha 管理员取消 beta 运行应 404，实际 ${betaCancel.status} ${betaCancel.text}`,
    );
    const refused = activeCounts(orderDb.name, options);
    const refusedGuard = workerRollbackGuard(refused);
    assert(
      !refusedGuard.allowed && refusedGuard.exitCode === 3,
      `无法清零时守卫应拒绝工 rollback，实际 ${JSON.stringify(refusedGuard)}`,
    );
    report.phases.push({
      id: "refuse-dirty",
      status: "pass",
      counts: refused,
      activeRuns: activeRuns(orderDb.name, options),
      guard: refusedGuard,
      note: "跨租户 WAITING_APPROVAL 模拟清零后新行；alpha 管理员 404，无法清零 → 拒绝回滚 Worker",
    });
    record(
      report.assertions,
      "order.dirtyRefused",
      true,
      !refusedGuard.allowed,
    );
    record(report.assertions, "order.dirtyExit", 3, refusedGuard.exitCode);

    // 清理跨租户夹具（演练库内 SQL；生产由 beta 租户管理员/API 取消）。
    runSql(
      orderDb.name,
      `UPDATE workflow_run SET status = 'CANCELLED', finished_at = UTC_TIMESTAMP(6),
              updated_at = UTC_TIMESTAMP(6)
        WHERE tenant_id = 'tenant-beta' AND id = ${sqlUuid(fixtures.betaWaiting)}
          AND status = 'WAITING_APPROVAL';`,
      options,
    );
    const afterCleanup = activeCounts(orderDb.name, options);
    const cleanupGuard = workerRollbackGuard(afterCleanup);
    assert(
      cleanupGuard.allowed,
      `清理跨租户夹具后仍有活动运行：${JSON.stringify(afterCleanup)}`,
    );

    // 步骤 5：回滚 Worker。默认只记录目标与守卫；--execute-old-worker 会真实启动
    // M06 前 Worker 容器（schema 校验按回滚窗口关闭），断言它不领取/不改 workflow 数据。
    const rollback = {
      allowed: true,
      executed: false,
      mode: "recorded-only",
      target: report.worker.rollbackTarget,
      command: options.workerRollbackCommand ?? null,
      exitCode: null,
      note: options.workerRollbackCommand
        ? "执行部署提供的 Worker 回滚命令"
        : "以 recorded-only 方式记录回滚目标并断言守卫；可用 --execute-old-worker 或 --worker-rollback-command 执行真实回滚",
    };
    if (options.executeOldWorker) {
      const oldWorker = await executeOldWorkerRollback(
        orderDb.name,
        options,
        afterCleanup,
      );
      rollback.executed = true;
      rollback.mode = "container";
      rollback.oldWorker = oldWorker;
      rollback.exitCode = 0;
      rollback.note =
        "启动 M06 前 Worker 容器，schema 校验关闭，断言其不领取且活动运行保持 0";
    } else if (options.workerRollbackCommand) {
      const result = runShell(options.workerRollbackCommand);
      rollback.executed = true;
      rollback.mode = "command";
      rollback.exitCode = result.exitCode;
      rollback.stdout = result.stdout;
      rollback.stderr = result.stderr;
      assert(
        result.exitCode === 0,
        `Worker 回滚命令退出 ${result.exitCode}: ${result.stderr || result.stdout}`,
      );
    }
    report.phases.push({
      id: "worker-rollback",
      status: "pass",
      ...rollback,
    });
    record(
      report.assertions,
      "order.workerRollbackAllowed",
      true,
      rollback.allowed,
    );
    record(
      report.assertions,
      "order.workerRollbackExecuted",
      Boolean(rollback.executed),
      rollback.executed,
    );

    // 步骤 6：保留 API 复断言 0、历史可读、schema 15。
    const finalActive = activeCounts(orderDb.name, options);
    const finalGuard = workerRollbackGuard(finalActive);
    assert(
      finalGuard.allowed,
      `回滚后复断言仍非 0：${JSON.stringify(finalActive)}`,
    );
    const history = await client.get("/api/v1/workflow-runs?page=1&size=5");
    assert(history.status === 200, `历史读取返回 ${history.status}`);
    const limitsAfter = await client.get("/api/v1/workflows/runtime-limits");
    assert(
      limitsAfter.status === 200,
      `回滚后 runtime-limits 返回 ${limitsAfter.status}`,
    );
    const health = await fetch(`${api.baseUrl}/actuator/health`);
    assert(health.status === 200, `回滚后 API health=${health.status}`);
    const finalMetrics = schemaMetrics(orderDb.name, options);
    assert(
      finalMetrics.schemaVersion === EXPECTED_SCHEMA_VERSION,
      `回滚后 schema ${finalMetrics.schemaVersion} != ${EXPECTED_SCHEMA_VERSION}`,
    );
    report.phases.push({
      id: "post-rollback-assert",
      status: "pass",
      counts: finalActive,
      guard: finalGuard,
      historyStatus: history.status,
      historyTotal: history.data?.total ?? null,
      limitsStatus: limitsAfter.status,
      apiHealth: health.status,
      schema: finalMetrics,
    });
    record(
      report.assertions,
      "order.postRollbackZero",
      true,
      finalGuard.allowed,
    );
    record(report.assertions, "order.historyReadable", 200, history.status);
    record(report.assertions, "order.apiRetained", 200, health.status);

    // 首次禁用启动审计：真实 API 启动已写 workflow.feature.changed；
    // 复制到空栈验证“其余 7 项为 0 仍然拒绝”（全 0 不可达，OPS-M06-025）。
    const featureAudits = queryRows(
      orderDb.name,
      `SELECT LOWER(HEX(event_id)), tenant_id, action,
              JSON_UNQUOTE(JSON_EXTRACT(details, '$.source')),
              JSON_UNQUOTE(JSON_EXTRACT(details, '$.instanceId')),
              JSON_UNQUOTE(JSON_EXTRACT(details, '$.observedAt'))
         FROM audit_event
        WHERE action = 'workflow.feature.changed' ORDER BY occurred_at LIMIT 1;`,
      options,
    );
    assert(
      featureAudits.length >= 1,
      "关闭态 API 启动未写 workflow.feature.changed 审计",
    );
    const [
      auditHex,
      auditTenant,
      auditAction,
      auditSource,
      auditInstanceId,
      auditObservedAt,
    ] = featureAudits[0];
    assert(
      auditSource === "DEPLOYMENT_CONFIG",
      `workflow.feature.changed details.source=${auditSource}，期望 DEPLOYMENT_CONFIG`,
    );
    assert(
      Boolean(auditInstanceId),
      "workflow.feature.changed 缺少 instanceId",
    );
    assert(
      Boolean(auditObservedAt),
      "workflow.feature.changed 缺少 observedAt",
    );
    runSql(
      emptyDb.name,
      `INSERT INTO audit_event
         (event_id, occurred_at, tenant_id, actor_type, actor_id, action, target_type,
          target_id, outcome, trace_id, request_id, source_ip, details, schema_version)
       SELECT ${sqlUuid(dashedUuid(auditHex))}, UTC_TIMESTAMP(6),
              ${sqlString(auditTenant)}, 'SYSTEM', 'system', ${sqlString(auditAction)},
              'WORKFLOW', NULL, 'SUCCESS', 'wf-drill-disabled-startup', NULL, NULL,
              JSON_OBJECT('enabled', FALSE, 'source', 'DEPLOYMENT_CONFIG',
                          'instanceId', 'wf-drill', 'observedAt', UTC_TIMESTAMP(6)),
              1;`,
      options,
    );
    const afterAudit = decisionCounts(emptyDb.name, options);
    const afterAuditDecision = fullRollbackDecision(afterAudit);
    assert(
      !afterAuditDecision.allowed && afterAuditDecision.exitCode === 3,
      `首次禁用启动审计后判定未拒绝：${JSON.stringify(afterAudit)}`,
    );
    const otherSevenZero = DECISION_KEYS.filter(
      (key) => key !== "workflow_audit_events",
    ).every((key) => afterAudit[key] === 0);
    assert(
      otherSevenZero,
      `复制审计后其余 7 项应仍为 0：${JSON.stringify(afterAudit)}`,
    );
    report.phases.push({
      id: "disabled-startup-audit",
      status: "pass",
      database: emptyDb.name,
      counts: afterAudit,
      decision: afterAuditDecision,
      audit: {
        action: auditAction,
        tenant: auditTenant,
        source: auditSource,
        instanceId: auditInstanceId,
        observedAt: auditObservedAt,
        origin: "真实关闭态 API 启动写入（order 栈）后复制到空栈",
      },
      note: "首次 ENABLED=false 启动写入 workflow.feature.changed 后“全 0”不可达；试发布失败只能恢复 V15 前备份",
    });
    record(
      report.assertions,
      "firstDisable.auditRejects",
      true,
      !afterAuditDecision.allowed,
    );
    record(
      report.assertions,
      "firstDisable.auditSource",
      "DEPLOYMENT_CONFIG",
      auditSource,
    );

    // 回滚 API 关闭。
    await stopDrillApi(api);
    api = null;
    report.phases.push({
      id: "cleanup",
      status: "pass",
      keptDatabases: options.keep ? databases.map((item) => item.name) : [],
    });
    if (!options.keep) {
      for (const database of databases.reverse()) {
        dropDrillDatabase(database, options);
      }
      databases.length = 0;
      report.phases.at(-1).status = "pass";
      report.phases.at(-1).droppedDatabases = true;
    }
    report.finishedAt = new Date().toISOString();
    report.durationMs =
      new Date(report.finishedAt).getTime() -
      new Date(report.startedAt).getTime();
    const failed = report.assertions.filter((item) => item.status !== "pass");
    report.result = {
      verdict: failed.length === 0 ? "pass" : "fail",
      assertions: report.assertions.length,
      failed: failed.length,
    };
    return { report, databases, api };
  } catch (error) {
    report.finishedAt = new Date().toISOString();
    report.durationMs =
      new Date(report.finishedAt).getTime() -
      new Date(report.startedAt).getTime();
    report.result = {
      verdict: "fail",
      error: error.message,
      exitCode: error.exitCode ?? 1,
    };
    report.assertions.push({
      id: "drill.error",
      status: "fail",
      expected: "pass",
      actual: error.message,
    });
    return { report, databases, api, error };
  } finally {
    for (const handle of [...startedWorkers]) {
      await stopDrillWorker(handle);
    }
    for (const handle of [...startedApis]) {
      await stopDrillApi(handle);
    }
  }
}

/**
 * 纯静态自检（不连库/不启进程）。
 */
function selfTest() {
  assert(
    DECISION_SQL.includes("workflow_definition"),
    "判定 SQL 缺少 workflow_definition",
  );
  assert(
    DECISION_SQL.includes("workflow_user_template"),
    "判定 SQL 缺少 workflow_user_template",
  );
  assert(
    DECISION_SQL.includes("action LIKE 'workflow.%'"),
    "判定 SQL 缺少 workflow.* 审计",
  );
  assert(
    ACTIVE_STATUS_SQL.includes("WAITING_APPROVAL"),
    "活动状态 SQL 缺少 WAITING_APPROVAL",
  );
  assert(ACTIVE_STATUS_SQL.includes("PENDING"), "活动状态 SQL 缺少 PENDING");
  assert(ACTIVE_STATUS_SQL.includes("RUNNING"), "活动状态 SQL 缺少 RUNNING");
  assert(RESULT_MAX_BYTES === 5 * 1024 * 1024, "5 MiB 常量错误");
  assert(NODE_OUTPUT_MAX_BYTES === 256 * 1024, "256 KiB 常量错误");
  assert(
    !/DROP\s+TABLE\s+`?workflow/i.test(DECISION_SQL),
    "判定 SQL 不得包含 drop 语句",
  );
  assert(
    !/DROP\s+TABLE/i.test(readFileSync(new URL(import.meta.url), "utf8")),
    "脚本不得另存应急 drop SQL",
  );
  const guard = workerRollbackGuard({
    waitingApproval: 1,
    pending: 0,
    running: 0,
  });
  assert(!guard.allowed && guard.exitCode === 3, "守卫未在 WAITING>0 时拒绝");
  const emptyGuard = workerRollbackGuard({
    waitingApproval: 0,
    pending: 0,
    running: 0,
  });
  assert(emptyGuard.allowed && emptyGuard.exitCode === 0, "守卫未在清零时允许");
  const decision = fullRollbackDecision({
    definitions: 0,
    versions: 0,
    runs: 0,
    node_runs: 0,
    approvals: 0,
    tool_invocations: 0,
    user_templates: 0,
    workflow_audit_events: 1,
  });
  assert(!decision.allowed && decision.exitCode === 3, "审计行未触发拒绝");
  console.log(
    "[wf-drill] self-test: PASS（判定 SQL、回滚守卫、5 MiB/256 KiB 常量）",
  );
}

/**
 * 主入口。
 */
async function main() {
  let options;
  try {
    options = parseArgs(process.argv.slice(2));
    if (options.help) {
      usage();
      return;
    }
    if (options.selfTest) {
      selfTest();
      return;
    }

    // 只读判定模式：供运维直接拒绝全量回滚/应急 drop。
    if (options.decisionOnly) {
      const counts = decisionCounts(options.database, options);
      const decision = fullRollbackDecision(counts);
      console.log(
        `[wf-drill] decision database=${options.database} allowed=${decision.allowed} counts=${JSON.stringify(counts)}`,
      );
      for (const key of decision.nonZero) {
        console.log(`[wf-drill]   ${key}=${counts[key]}`);
      }
      process.exitCode = decision.exitCode;
      return;
    }

    if (!options.apply) {
      console.log(`[wf-drill] 计划（未执行；加 --apply 运行）：
  - 源库: ${options.source}（必须 V15）
  - 空栈: 结构克隆 + Flyway 历史，验证“无数据允许”
  - 数据栈: ${options.dataDump ? options.dataDump : `从 ${options.source} 导出`}，验证“有数据拒绝”
  - order 栈: 克隆数据并启动 API（ENABLED=false），演练取消/清零/回滚/复断言
  - 容量: 真实插入接近 5 MiB result_json 与接近 256 KiB output_json
  - Worker 回滚目标: tag=${options.oldWorkerTag ?? "(未提供)"} digest=${options.oldWorkerDigest ?? "(未提供)"}
  - 宽限: ${options.graceSeconds}s；容量输入: runs/day=${options.runsPerDay}, retention=${options.retentionDays}d`);
      return;
    }

    const reportPath =
      options.report ??
      path.join(
        process.cwd(),
        ".riccon/evidence",
        evidenceTask,
        "workflow-rollback-drill.json",
      );
    const evidenceDir = path.dirname(reportPath);
    mkdirp(evidenceDir);
    const context = { evidenceDir, reportPath };
    console.log(
      `[wf-drill] 开始演练：源库=${options.source}，报告=${reportPath}`,
    );
    const { report, databases, error } = await runDrill(options, context);
    writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`, "utf8");
    console.log(
      `[wf-drill] 结果: ${report.result.verdict}（断言 ${report.result.assertions}，失败 ${report.result.failed ?? "n/a"}）`,
    );
    console.log(`[wf-drill] 报告: ${reportPath}`);
    if (report.phases) {
      for (const phase of report.phases) {
        console.log(`[wf-drill]   ${phase.id}: ${phase.status}`);
      }
    }
    if (error) {
      console.error(`[wf-drill] 失败: ${error.message}`);
      process.exitCode = error.exitCode ?? 1;
      return;
    }
    if (!options.keep && databases.length > 0) {
      // runDrill 正常路径已清理；此处只处理异常路径。
      for (const database of databases.reverse()) {
        try {
          dropDrillDatabase(database, options);
        } catch (cleanupError) {
          console.error(
            `[wf-drill] 清理演练库 ${database.name} 失败: ${cleanupError.message}`,
          );
          process.exitCode = 1;
        }
      }
    }
    process.exitCode = report.result.verdict === "pass" ? 0 : 1;
  } catch (error) {
    console.error(`[wf-drill] 失败: ${error.message}`);
    process.exitCode = error.exitCode ?? 1;
  }
}

/**
 * 递归创建目录。
 *
 * @param {string} directory 目录
 */
function mkdirp(directory) {
  mkdirSync(directory, { recursive: true });
}

main();
