#!/usr/bin/env node
// M12 本地热测试栈管理：start/stop/status/prepare/reset/benchmark。
//
// 默认（不设置 RICCON_TEST_SERVICES）时，Maven 集成测试仍使用 Testcontainers 一次性容器，
// CI 与模块 gate 完全不变。external 模式必须显式 opt-in，并且每个 agent 使用独立 namespace：
//
//   pnpm test-services start
//   pnpm test-services prepare --namespace builder-0054
//   RICCON_TEST_SERVICES=external RICCON_TEST_SERVICES_NAMESPACE=builder-0054 ./mvnw -q -T1C verify
//
// 隔离规则（重要）：
// - 一个 namespace 同时只能被一个测试 JVM 使用；测试基类会通过 MySQL GET_LOCK 抢占命名锁，
//   第二个 JVM 会立即失败。禁止多个 agent 并行共用同一 namespace。
// - 每个 namespace 拥有独立数据库（riccon_test_<ns>_api / …_worker）、S3 桶
//   （riccon-test-<ns>-api / …-worker）和 OpenSearch 索引前缀（riccon-kb-<ns>-worker）。
// - 迁移/回滚与 schema 版本测试始终使用一次性容器，不读取 external 热栈。
//
// 凭据与端口来自 deploy/compose/docker-compose.test.yml 及同名环境变量
// （RICCON_DB_PASSWORD、RICCON_DB_ROOT_PASSWORD、RICCON_S3_ACCESS_KEY、RICCON_S3_SECRET_KEY）；
// 脚本不另造服务，只通过 docker compose 操作同一测试栈。

import { spawnSync } from "node:child_process";
import { readFileSync, statSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

/** 仓库根目录（脚本位于 scripts/）。 */
const repoRoot = path.resolve(fileURLToPath(new URL("..", import.meta.url)));

/** 合同 commands.sandbox 使用的 compose 文件。 */
const composeFile = path.join(
  repoRoot,
  "deploy/compose/docker-compose.test.yml",
);

/** 支持的模块；数据库名按模块区分，允许 api/worker 并行运行。 */
const MODULES = ["api", "worker"];

/** 外部栈连接参数；默认值与 compose 测试栈一致。 */
const settings = {
  mysqlHost: env("RICCON_TEST_MYSQL_HOST", "127.0.0.1"),
  mysqlPort: env("RICCON_TEST_MYSQL_PORT", "13306"),
  dbUsername: "riccon",
  dbPassword: env("RICCON_DB_PASSWORD", "riccon_test_only"),
  dbRootPassword: env("RICCON_DB_ROOT_PASSWORD", "riccon_root_test_only"),
  s3Endpoint: env("RICCON_TEST_S3_ENDPOINT", "http://127.0.0.1:19000"),
  s3AccessKey: env("RICCON_S3_ACCESS_KEY", "riccon_test_only"),
  s3SecretKey: env("RICCON_S3_SECRET_KEY", "riccon_test_only_secret"),
  openSearchUrl: env("RICCON_TEST_OPENSEARCH_URL", "http://127.0.0.1:19200"),
};

/** 基准用例：worker HITL 与 API 认证流程。 */
const BENCHMARK_CASES = [
  {
    id: "worker-hitl",
    module: "worker",
    moduleArgs: ["-pl", "apps/worker", "-am"],
    test: "HitlIntegrationTest",
    report:
      "apps/worker/target/surefire-reports/com.riccon.worker.research.HitlIntegrationTest.txt",
    xmlReport:
      "apps/worker/target/surefire-reports/TEST-com.riccon.worker.research.HitlIntegrationTest.xml",
  },
  {
    id: "api-auth",
    module: "api",
    moduleArgs: ["-pl", "apps/api", "-am"],
    test: "AuthFlowTest",
    report: "apps/api/target/surefire-reports/com.riccon.api.AuthFlowTest.txt",
    xmlReport:
      "apps/api/target/surefire-reports/TEST-com.riccon.api.AuthFlowTest.xml",
  },
];

/** 打印用法。 */
function usage() {
  console.log(`用法: node scripts/test-services.mjs <command> [options]

命令:
  start                              启动 compose 测试栈并等待健康
  stop [--volumes]                   停止测试栈；--volumes 同时删除数据卷
  status [--namespace NS]            查看栈状态、namespace 数据库与导出变量
  prepare --namespace NS [--module M] 创建 namespace 数据库并授权给 riccon 用户
  reset --namespace NS [--module M]   删除并重建 namespace 数据库（含 OpenSearch 索引）
  benchmark --namespace NS [--runs N] 默认 vs external：AuthFlowTest/HitlIntegrationTest 各跑 N 次（默认 3）

选项:
  --module api|worker|all            默认 all
  --runs N                           benchmark 次数，默认 3
  --namespace NS                     external 隔离命名空间（也可用 RICCON_TEST_SERVICES_NAMESPACE）

隔离要求:
  每个 agent 使用独立 namespace；同一 namespace 的测试 JVM 由 MySQL GET_LOCK 互斥，
  第二个 JVM 会失败。禁止并行共用同一 namespace。迁移/回滚测试从不使用 external 热栈。`);
}

/** 读取环境变量，未设置或为空时返回默认值。 */
function env(name, fallback) {
  const value = process.env[name];
  return value === undefined || value.trim() === "" ? fallback : value.trim();
}

/** 解析命令行选项；未知选项直接失败，避免静默默认值。 */
function parseOptions(args) {
  const options = {
    modules: MODULES.slice(),
    runs: 3,
    namespace: env("RICCON_TEST_SERVICES_NAMESPACE", ""),
  };
  for (let index = 0; index < args.length; index += 1) {
    const arg = args[index];
    if (arg === "--namespace") {
      options.namespace = requireValue(args, ++index, arg);
    } else if (arg === "--module") {
      const module = requireValue(args, ++index, arg);
      options.modules = module === "all" ? MODULES.slice() : [module];
    } else if (arg === "--runs") {
      options.runs = Number(requireValue(args, ++index, arg));
    } else if (arg === "--volumes") {
      options.volumes = true;
    } else {
      throw new Error(`未知选项: ${arg}`);
    }
  }
  for (const module of options.modules) {
    if (!MODULES.includes(module)) {
      throw new Error(`--module 只接受 api|worker|all，收到: ${module}`);
    }
  }
  if (!Number.isInteger(options.runs) || options.runs < 1) {
    throw new Error("--runs 必须是正整数");
  }
  return options;
}

/** 读取选项值；缺失时失败。 */
function requireValue(args, index, flag) {
  const value = args[index];
  if (!value || value.startsWith("--")) {
    throw new Error(`${flag} 需要一个值`);
  }
  return value;
}

/** 校验 namespace，与测试基类保持同一规则。 */
function validateNamespace(namespace) {
  if (!namespace) {
    throw new Error("必须提供 --namespace 或 RICCON_TEST_SERVICES_NAMESPACE");
  }
  if (!/^[a-z0-9](?:[a-z0-9-]{0,28}[a-z0-9])?$/.test(namespace)) {
    throw new Error(
      `namespace 必须匹配 [a-z0-9](?:[a-z0-9-]{0,28}[a-z0-9])?: ${namespace}`,
    );
  }
  return namespace;
}

/** 派生 namespace 内的数据库名，与测试基类保持一致。 */
function databaseName(namespace, module) {
  return `riccon_test_${namespace.replaceAll("-", "_")}_${module}`;
}

/** 派生 OpenSearch 索引前缀，与 Worker 测试基类保持一致。 */
function searchPrefix(namespace) {
  return `riccon-kb-${namespace}-`;
}

/** compose 环境：显式注入四类凭据，确保 compose 插值与测试读取同源。 */
function composeEnv() {
  return {
    ...process.env,
    RICCON_DB_PASSWORD: settings.dbPassword,
    RICCON_DB_ROOT_PASSWORD: settings.dbRootPassword,
    RICCON_S3_ACCESS_KEY: settings.s3AccessKey,
    RICCON_S3_SECRET_KEY: settings.s3SecretKey,
  };
}

/** 运行 docker compose；失败时抛出并保留子进程输出。 */
function compose(args, { capture = false } = {}) {
  const result = spawnSync("docker", ["compose", "-f", composeFile, ...args], {
    cwd: repoRoot,
    env: composeEnv(),
    encoding: "utf8",
    stdio: capture ? "pipe" : "inherit",
  });
  if (result.error) {
    throw new Error(`docker compose 执行失败: ${result.error.message}`);
  }
  if (result.status !== 0) {
    const detail = capture
      ? `\n${result.stdout || ""}${result.stderr || ""}`
      : "";
    throw new Error(
      `docker compose ${args.join(" ")} 退出码 ${result.status}${detail}`,
    );
  }
  return result.stdout || "";
}

/** 在 MySQL 容器内以 root 执行 SQL；仅用于建库/授权/状态查询。 */
function mysqlExec(sql) {
  const result = spawnSync(
    "docker",
    [
      "compose",
      "-f",
      composeFile,
      "exec",
      "-T",
      "-e",
      `MYSQL_PWD=${settings.dbRootPassword}`,
      "mysql",
      "mysql",
      "--user=root",
      "--batch",
      "--skip-column-names",
      "--default-character-set=utf8mb4",
      "-e",
      sql,
    ],
    { cwd: repoRoot, env: composeEnv(), encoding: "utf8" },
  );
  if (result.error) {
    throw new Error(`MySQL 命令执行失败: ${result.error.message}`);
  }
  if (result.status !== 0) {
    throw new Error(
      `MySQL 命令退出码 ${result.status}: ${result.stderr || result.stdout}`,
    );
  }
  return (result.stdout || "").trim();
}

/** 等待 HTTP 端点返回 200。 */
async function waitHttp(url, label) {
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(5000) });
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
  } catch (error) {
    throw new Error(
      `${label} 不可用（${url}）: ${error.message}; 先运行 pnpm test-services start`,
    );
  }
}

/** 启动测试栈并等待健康。 */
async function commandStart() {
  compose(["up", "-d", "--wait"]);
  await waitHttp(
    `${settings.openSearchUrl.replace(/\/+$/, "")}/_cluster/health`,
    "OpenSearch",
  );
  await waitHttp(`${settings.s3Endpoint.replace(/\/+$/, "")}/health`, "RustFS");
  mysqlExec("SELECT 1");
  console.log(
    "测试栈已就绪。下一步: pnpm test-services prepare --namespace <namespace>",
  );
}

/** 停止测试栈；默认保留数据卷。 */
function commandStop(options) {
  const args = ["down"];
  if (options.volumes) {
    args.push("-v");
  }
  compose(args);
  console.log(
    options.volumes
      ? "测试栈已停止并删除数据卷。"
      : "测试栈已停止（数据卷保留）。",
  );
}

/** 查看栈、namespace 数据库与 OpenSearch 索引。 */
async function commandStatus(options) {
  compose(["ps"]);
  const databases = mysqlExec(
    "SELECT schema_name FROM information_schema.schemata WHERE schema_name LIKE 'riccon\\_test\\_%' ORDER BY schema_name",
  );
  console.log("namespace 数据库:");
  console.log(
    databases
      ? databases
          .split("\n")
          .map((name) => `  ${name}`)
          .join("\n")
      : "  （无）",
  );
  const indices = await listSearchIndices();
  const namespaced = indices.filter((name) => name.startsWith("riccon-kb-"));
  console.log("OpenSearch 索引:");
  console.log(
    namespaced.length
      ? namespaced.map((name) => `  ${name}`).join("\n")
      : "  （无）",
  );
  console.log("\n外部模式环境变量:");
  console.log(`  export RICCON_TEST_SERVICES=external`);
  console.log(
    `  export RICCON_TEST_SERVICES_NAMESPACE=${options.namespace ? validateNamespace(options.namespace) : "<你的 namespace>"}`,
  );
  console.log(
    `  默认端口: MySQL ${settings.mysqlHost}:${settings.mysqlPort} / OpenSearch ${settings.openSearchUrl} / S3 ${settings.s3Endpoint}`,
  );
  console.log(
    "  凭据: RICCON_DB_PASSWORD / RICCON_DB_ROOT_PASSWORD / RICCON_S3_ACCESS_KEY / RICCON_S3_SECRET_KEY（未设置时用 compose 测试占位值）",
  );
  console.log(
    "  同一 namespace 同时只允许一个测试 JVM；并行 agent 必须使用不同 namespace。",
  );
}

/** 创建 namespace 数据库并授权；可重复执行。 */
function commandPrepare(options) {
  const namespace = validateNamespace(options.namespace);
  for (const module of options.modules) {
    const database = databaseName(namespace, module);
    mysqlExec(
      `CREATE DATABASE IF NOT EXISTS \`${database}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; ` +
        `GRANT ALL PRIVILEGES ON \`${database}\`.* TO '${settings.dbUsername}'@'%';`,
    );
    console.log(`已准备数据库 ${database}`);
  }
  console.log(`\n运行 external 测试:`);
  console.log(
    `  export RICCON_TEST_SERVICES=external RICCON_TEST_SERVICES_NAMESPACE=${namespace}`,
  );
  console.log(`  ./mvnw -q -T1C verify`);
}

/** 删除并重建 namespace 数据库；同时清理该 namespace 的 OpenSearch 索引。 */
async function commandReset(options) {
  const namespace = validateNamespace(options.namespace);
  const lockSuffix = namespace.replaceAll("-", "_");
  const inUse = mysqlExec(
    `SELECT IS_USED_LOCK('riccon_test_${lockSuffix}_api'), IS_USED_LOCK('riccon_test_${lockSuffix}_worker');`,
  );
  if (inUse.split("\t").some((value) => value && value !== "NULL")) {
    throw new Error(
      `namespace ${namespace} 仍有测试 JVM 持有数据库锁；先结束测试进程再 reset`,
    );
  }
  for (const module of options.modules) {
    mysqlExec(
      `DROP DATABASE IF EXISTS \`${databaseName(namespace, module)}\`;`,
    );
  }
  await purgeSearchIndices(namespace);
  commandPrepare({ ...options, namespace });
  console.log(
    "namespace 已重置；S3 对象不做删除（对象 key 含 UUID，不会与后续运行冲突；彻底清空用 stop --volumes）。",
  );
}

/** 列出 OpenSearch 索引名。 */
async function listSearchIndices() {
  const url = `${settings.openSearchUrl.replace(/\/+$/, "")}/_cat/indices?format=json&h=index`;
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(5000) });
    if (!response.ok) {
      return [];
    }
    const body = await response.json();
    return Array.isArray(body) ? body.map((item) => item.index) : [];
  } catch {
    return [];
  }
}

/** 删除 namespace 的 OpenSearch 索引；失败时提示但不掩盖其他错误。 */
async function purgeSearchIndices(namespace) {
  const prefix = searchPrefix(namespace);
  const stale = (await listSearchIndices()).filter((name) =>
    name.startsWith(prefix),
  );
  for (const name of stale) {
    const response = await fetch(
      `${settings.openSearchUrl.replace(/\/+$/, "")}/${name}`,
      {
        method: "DELETE",
        signal: AbortSignal.timeout(10000),
      },
    );
    if (!response.ok) {
      throw new Error(
        `删除 OpenSearch 索引 ${name} 失败: HTTP ${response.status}`,
      );
    }
  }
  if (stale.length) {
    console.log(`已删除 ${stale.length} 个 OpenSearch 索引（前缀 ${prefix}）`);
  }
}

/**
 * 运行单个基准用例并解析 surefire 报告。
 *
 * <p>固定启动时间 = surefire 类耗时 - 各 testcase 时间之和；也就是容器启动、Flyway/TestSchema、
 * Spring 上下文与类级 setup 等每次运行都要付出的固定成本。测试方法自身的耗时单列，避免把
 * bcrypt/登录限流等业务测试时间误算成启动时间。
 *
 * @param {object} testCase 用例定义
 * @param {object} extraEnv 额外环境变量（external 模式）
 * @param {string} label 输出标签
 * @returns {{wallMs:number, surefireSeconds:number, testSeconds:number, startupSeconds:number}}
 */
function runBenchmarkOnce(testCase, extraEnv, label) {
  const started = Date.now();
  const result = spawnSync(
    "./mvnw",
    [
      "-q",
      ...testCase.moduleArgs,
      "test",
      `-Dtest=${testCase.test}`,
      "-DfailIfNoTests=false",
      "-Dsurefire.failIfNoSpecifiedTests=false",
    ],
    {
      cwd: repoRoot,
      env: { ...process.env, ...extraEnv },
      encoding: "utf8",
      timeout: 30 * 60 * 1000,
    },
  );
  const wallMs = Date.now() - started;
  if (result.error || result.status !== 0) {
    process.stderr.write(result.stdout || "");
    process.stderr.write(result.stderr || "");
    throw new Error(
      `${testCase.id} ${label} 失败: ${result.error?.message || `退出码 ${result.status}`}`,
    );
  }
  const reportPath = path.join(repoRoot, testCase.report);
  let report;
  try {
    report = readFileSync(reportPath, "utf8");
  } catch {
    throw new Error(
      `${testCase.id} ${label}: 找不到 surefire 报告 ${testCase.report}`,
    );
  }
  if (statSync(reportPath).mtimeMs < started - 1000) {
    throw new Error(`${testCase.id} ${label}: surefire 报告未在本次运行中更新`);
  }
  const match = report.match(/Time elapsed:\s*([0-9.]+)\s*s/);
  if (!match) {
    throw new Error(`${testCase.id} ${label}: 无法解析 surefire 耗时`);
  }
  const surefireSeconds = Number(match[1]);
  const xmlPath = path.join(repoRoot, testCase.xmlReport);
  let xml;
  try {
    xml = readFileSync(xmlPath, "utf8");
  } catch {
    throw new Error(
      `${testCase.id} ${label}: 找不到 surefire XML 报告 ${testCase.xmlReport}`,
    );
  }
  let testSeconds = 0;
  for (const testCaseTime of xml.matchAll(
    /<testcase\b[^>]*\btime="([0-9.]+)"/g,
  )) {
    testSeconds += Number(testCaseTime[1]);
  }
  const startupSeconds = Math.max(0, surefireSeconds - testSeconds);
  return { wallMs, surefireSeconds, testSeconds, startupSeconds };
}

/** 取中位数（已排序数组）。 */
function median(values) {
  const sorted = [...values].sort((left, right) => left - right);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0
    ? (sorted[middle - 1] + sorted[middle]) / 2
    : sorted[middle];
}

/** 固定启动时间基准：默认 vs external 各跑 N 次，报告中位数与降幅。 */
async function commandBenchmark(options) {
  const namespace = validateNamespace(options.namespace);
  compose(["up", "-d", "--wait"]);
  await waitHttp(
    `${settings.openSearchUrl.replace(/\/+$/, "")}/_cluster/health`,
    "OpenSearch",
  );
  await waitHttp(`${settings.s3Endpoint.replace(/\/+$/, "")}/health`, "RustFS");
  commandPrepare({ ...options, namespace });

  const externalEnv = {
    RICCON_TEST_SERVICES: "external",
    RICCON_TEST_SERVICES_NAMESPACE: namespace,
  };
  const results = [];
  for (const testCase of BENCHMARK_CASES) {
    for (const mode of ["default", "external"]) {
      const extraEnv = mode === "external" ? externalEnv : {};
      // 预热一次（不计入中位数）：完成编译，external 首轮还会初始化 schema。
      console.log(`[benchmark] ${testCase.id} ${mode} 预热`);
      runBenchmarkOnce(testCase, extraEnv, "预热");
      const runs = [];
      for (let index = 1; index <= options.runs; index += 1) {
        const result = runBenchmarkOnce(testCase, extraEnv, `第 ${index} 次`);
        runs.push(result);
        console.log(
          `[benchmark] ${testCase.id} ${mode} 第 ${index} 次: startup=${result.startupSeconds.toFixed(
            2,
          )}s test=${result.testSeconds.toFixed(2)}s class=${result.surefireSeconds.toFixed(2)}s wall=${(
            result.wallMs / 1000
          ).toFixed(2)}s`,
        );
      }
      results.push({ testCase: testCase.id, mode, runs });
    }
  }

  console.log("\n## 固定启动时间基准（本机，串行）\n");
  console.log(
    "| 用例 | 模式 | 各次 startup (s) | startup 中位数 (s) | test 中位数 (s) | class 中位数 (s) | wall 中位数 (s) |",
  );
  console.log("| --- | --- | --- | --- | --- | --- | --- |");
  const startups = new Map();
  const classes = new Map();
  for (const result of results) {
    const startup = result.runs.map((run) => run.startupSeconds);
    const test = result.runs.map((run) => run.testSeconds);
    const classTimes = result.runs.map((run) => run.surefireSeconds);
    const wall = result.runs.map((run) => run.wallMs / 1000);
    const startupMedian = median(startup);
    startups.set(`${result.testCase}:${result.mode}`, startupMedian);
    classes.set(`${result.testCase}:${result.mode}`, median(classTimes));
    console.log(
      `| ${result.testCase} | ${result.mode} | ${startup
        .map((value) => value.toFixed(2))
        .join(" / ")} | ${startupMedian.toFixed(2)} | ${median(test).toFixed(
        2,
      )} | ${median(classTimes).toFixed(2)} | ${median(wall).toFixed(2)} |`,
    );
  }
  console.log(
    "\n| 用例 | 默认 startup 中位数 (s) | external startup 中位数 (s) | 降幅 | ≥40% |",
  );
  console.log("| --- | --- | --- | --- | --- |");
  let allMet = true;
  for (const testCase of BENCHMARK_CASES) {
    const baseline = startups.get(`${testCase.id}:default`);
    const external = startups.get(`${testCase.id}:external`);
    const reduction = ((baseline - external) / baseline) * 100;
    const met = reduction >= 40;
    allMet = allMet && met;
    console.log(
      `| ${testCase.id} | ${baseline.toFixed(2)} | ${external.toFixed(
        2,
      )} | ${reduction.toFixed(1)}% | ${met ? "是" : "否"} |`,
    );
  }
  console.log(
    "\n| 用例 | 默认 class 中位数 (s) | external class 中位数 (s) | 总耗时降幅 |",
  );
  console.log("| --- | --- | --- | --- |");
  for (const testCase of BENCHMARK_CASES) {
    const baseline = classes.get(`${testCase.id}:default`);
    const external = classes.get(`${testCase.id}:external`);
    console.log(
      `| ${testCase.id} | ${baseline.toFixed(2)} | ${external.toFixed(
        2,
      )} | ${(((baseline - external) / baseline) * 100).toFixed(1)}% |`,
    );
  }
  console.log(
    `\n目标（固定启动时间降低 ≥40%）: ${allMet ? "达标" : "未达标，需在回执中记录原因"}`,
  );
  console.log(
    "定义: startup = surefire 类耗时 - 各 testcase 时间之和（容器/迁移/上下文/类级 setup）；" +
      "test 为测试方法自身耗时；wall 含 Maven 启动与编译。",
  );
}

/** 入口：解析命令并执行。 */
async function main() {
  const [command, ...rest] = process.argv.slice(2);
  if (!command || command === "help" || command === "--help") {
    usage();
    return;
  }
  const options = parseOptions(rest);
  switch (command) {
    case "start":
      await commandStart();
      break;
    case "stop":
      commandStop(options);
      break;
    case "status":
      await commandStatus(options);
      break;
    case "prepare":
      commandPrepare(options);
      break;
    case "reset":
      await commandReset(options);
      break;
    case "benchmark":
      await commandBenchmark(options);
      break;
    default:
      usage();
      throw new Error(`未知命令: ${command}`);
  }
}

main().catch((error) => {
  console.error(`[test-services] ${error.message}`);
  process.exit(1);
});
