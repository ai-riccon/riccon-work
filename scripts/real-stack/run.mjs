#!/usr/bin/env node
// M02/M03/M10/M04 真实栈编排：compose + API + Worker + Web + 假模型 + 本地 MCP server。
//
// 该脚本是验证者资产，不 import 生产应用内的 mock；它启动真实进程、在 MySQL 中 seed 目录与
// 加密凭据，然后运行 Playwright 浏览器旅程、J2 HTTP 闭环、安全审计与 M04 目录/配额检查。
//
// 两种模式：
// - full（默认）：完整 compose（MySQL/OpenSearch/RustFS）+ Web，用于 M02/M03/M10/M04/M05 回归；
// - perf：只启动 MySQL、假模型、API、Worker（不启 Web），按降级矩阵运行 k6。
//   设置 PERF_M05_SCENARIO=knowledge 时额外启动 OpenSearch/RustFS，按 M05 知识库场景
//   造 5000 文档/10 万 chunk、上传积压并量测首字/API/ACL/检索/索引延迟。
//   `PERF-M04` 主机规格与 OpenSearch 开关记录在 `real-stack-env.json` / `perf-m04-matrix.json`。
//
// 关键约束（ADR-0021 §2.1/§10.2）：
// - API 与 Worker 注入同一份 `RICCON_MODEL_DEPLOYMENT_MODE=DEMO`、`RICCON_MODEL_ENDPOINTS`、
//   `RICCON_MODEL_ALLOW_HTTP=true`、主密钥、pepper、币种与默认配额 env；
// - 端点 JSON 的 code/baseUrl 必须与 `seed.mjs` 写入 DB 的目录一致，否则 `/actuator/info`
//   的 endpointSetHash 与 allowed 状态会漂移；
// - 脚本输出、日志与证据不包含主密钥、pepper 或假 API key。
//
// 退出码：任何准备、seed、检查、浏览器或 perf 阶段失败即非零；不把“未执行”当成通过。
//
// 用法：
//   node scripts/real-stack/run.mjs --help
//   node scripts/real-stack/run.mjs --self-test
//   node scripts/real-stack/run.mjs --mode=full
//   node scripts/real-stack/run.mjs --mode=perf --perf-modes=server_error,rate_limited,unauthorized,timeout

import { spawn, spawnSync } from "node:child_process";
import { createHash, createHmac, randomBytes } from "node:crypto";
import {
  closeSync,
  existsSync,
  mkdirSync,
  openSync,
  readFileSync,
  writeFileSync,
} from "node:fs";
import os from "node:os";
import path from "node:path";
import process from "node:process";

import { mysqlClientArgs, runSql, sqlString } from "./lib/mysql.mjs";

/** 仓库根目录。 */
const repoRoot = process.cwd();

/** 证据任务目录；复验时用 REAL_EVIDENCE_TASK 隔离新旧日志。 */
const evidenceTask = process.env.REAL_EVIDENCE_TASK ?? "TASK-0024";
/**
 * 证据目录。默认写入 `.riccon/evidence/<task>`；性能复验可用 `REAL_EVIDENCE_DIR`
 * 指向工作区外目录，避免把大规模 fixtures 日志/报告混入状态仓库（如 M05b-B11b 的 perf-b11b）。
 */
const evidenceDir =
  process.env.REAL_EVIDENCE_DIR ??
  path.join(repoRoot, ".riccon/evidence", evidenceTask);
mkdirSync(evidenceDir, { recursive: true });

/** 端口；与 compose/前端默认端口区分。 */
const API_PORT = Number(process.env.REAL_API_PORT ?? 18080);
const WORKER_PORT = Number(process.env.REAL_WORKER_PORT ?? 18081);
const WEB_PORT = Number(process.env.REAL_WEB_PORT ?? 8092);
const MODEL_PORT = Number(process.env.REAL_MODEL_PORT ?? 18990);
const OIDC_PORT = Number(process.env.REAL_OIDC_PORT ?? 18995);
const OIDC_TENANT = "tenant-alpha";
const OIDC_REALM = "alpha";
const OIDC_CLIENT_ID = "riccon-alpha";
const OIDC_CLIENT_SECRET = `oidc-secret-${randomBytes(18).toString("hex")}`;
const OIDC_SUBJECT = "subject-alpha";
const OIDC_ISSUER = `http://127.0.0.1:${OIDC_PORT}/realms/${OIDC_REALM}`;
/** 租户配置的 IdP 展示名；浏览器断言与 API 配置共用同一值。 */
const OIDC_PROVIDER_NAME = "Alpha 测试企业 IdP";

/** Worker 启动时校验的 Flyway 期望版本；API 必须先迁移到这里再起 Worker。 */
const FLYWAY_EXPECTED_VERSION = 15;

/**
 * M06 编排运行限制默认值。
 *
 * <p>取值与 `apps/api/src/main/resources/application.yml` 的默认一致；显式注入是为了让 API 与 Worker
 * 收到同一份限制，并把实际值写进 `real-stack-env.json`，避免“报告只写默认值、运行时却用了别的值”。
 * 环境变量可覆盖，覆盖同样记录在快照中。
 */
const WORKFLOW_ENV = {
  RICCON_WORKFLOW_ENABLED: process.env.RICCON_WORKFLOW_ENABLED ?? "true",
  RICCON_WORKFLOW_MAX_NODES: process.env.RICCON_WORKFLOW_MAX_NODES ?? "50",
  RICCON_WORKFLOW_MAX_STEPS: process.env.RICCON_WORKFLOW_MAX_STEPS ?? "200",
  RICCON_WORKFLOW_MODEL_TIMEOUT_SECONDS:
    process.env.RICCON_WORKFLOW_MODEL_TIMEOUT_SECONDS ?? "300",
  RICCON_WORKFLOW_RETRIEVAL_TIMEOUT_SECONDS:
    process.env.RICCON_WORKFLOW_RETRIEVAL_TIMEOUT_SECONDS ?? "120",
  RICCON_WORKFLOW_MCP_TIMEOUT_SECONDS:
    process.env.RICCON_WORKFLOW_MCP_TIMEOUT_SECONDS ?? "60",
  RICCON_WORKFLOW_RUN_TIMEOUT_MINUTES:
    process.env.RICCON_WORKFLOW_RUN_TIMEOUT_MINUTES ?? "30",
  RICCON_WORKFLOW_TENANT_CONCURRENCY:
    process.env.RICCON_WORKFLOW_TENANT_CONCURRENCY ?? "10",
  RICCON_WORKFLOW_FLOW_CONCURRENCY:
    process.env.RICCON_WORKFLOW_FLOW_CONCURRENCY ?? "5",
  RICCON_WORKFLOW_RESULT_MAX_BYTES:
    process.env.RICCON_WORKFLOW_RESULT_MAX_BYTES ?? "5242880",
  RICCON_WORKFLOW_WORKER_CONCURRENCY:
    process.env.RICCON_WORKFLOW_WORKER_CONCURRENCY ?? "10",
  RICCON_WORKFLOW_CLAIM_BATCH: process.env.RICCON_WORKFLOW_CLAIM_BATCH ?? "1",
  RICCON_WORKFLOW_CLAIM_POLL_INTERVAL_MS:
    process.env.RICCON_WORKFLOW_CLAIM_POLL_INTERVAL_MS ?? "1000",
  RICCON_WORKFLOW_RETENTION_DAYS:
    process.env.RICCON_WORKFLOW_RETENTION_DAYS ?? "180",
};

/**
 * 同步等待；仅用于 CLI 编排轮询，不用于浏览器或请求路径。
 *
 * @param {number} ms 毫秒
 */
function sleepSync(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

/**
 * 把 WORKFLOW_ENV 折叠成报告友好的结构；同时保留原始 env 键值供复现。
 *
 * @returns {object} 快照节点
 */
function workflowEnvSnapshot() {
  const numeric = (key) => Number(WORKFLOW_ENV[key]);
  return {
    enabled: WORKFLOW_ENV.RICCON_WORKFLOW_ENABLED === "true",
    source:
      "scripts/real-stack/run.mjs WORKFLOW_ENV（默认值与 application.yml 一致；环境变量可覆盖）",
    env: { ...WORKFLOW_ENV },
    maxNodes: numeric("RICCON_WORKFLOW_MAX_NODES"),
    maxSteps: numeric("RICCON_WORKFLOW_MAX_STEPS"),
    modelTimeoutSeconds: numeric("RICCON_WORKFLOW_MODEL_TIMEOUT_SECONDS"),
    retrievalTimeoutSeconds: numeric(
      "RICCON_WORKFLOW_RETRIEVAL_TIMEOUT_SECONDS",
    ),
    mcpTimeoutSeconds: numeric("RICCON_WORKFLOW_MCP_TIMEOUT_SECONDS"),
    runTimeoutMinutes: numeric("RICCON_WORKFLOW_RUN_TIMEOUT_MINUTES"),
    tenantConcurrency: numeric("RICCON_WORKFLOW_TENANT_CONCURRENCY"),
    flowConcurrency: numeric("RICCON_WORKFLOW_FLOW_CONCURRENCY"),
    resultMaxBytes: numeric("RICCON_WORKFLOW_RESULT_MAX_BYTES"),
    workerConcurrency: numeric("RICCON_WORKFLOW_WORKER_CONCURRENCY"),
    retentionDays: numeric("RICCON_WORKFLOW_RETENTION_DAYS"),
  };
}

/** M05 knowledge 场景默认规模；环境变量可覆盖，报告只记录实际写入值。 */
const KNOWLEDGE_DEFAULTS = {
  documents: 5000,
  chunksPerDocument: 20,
  knowledgeVus: 50,
  duration: "60s",
  thinkTimeSeconds: 0.8,
  backlogUploads: 6,
};

/** M05 语料中的稳定检索词；V14 ngram 需要二元组足够独特的词，避免常见 bigram 命中全表。 */
const KNOWLEDGE_QUERY = process.env.PERF_M05_QUERY ?? "QXZJKW";

/** 含检索词的文档间隔；默认每 100 篇命中 1%（1000 chunk），代表选择性关键词查询。 */
const KNOWLEDGE_MATCH_EVERY = Number(process.env.PERF_M05_MATCH_EVERY ?? 100);

/**
 * M05b 连接器同步 perf 夹具默认值。
 *
 * <p>1000 个对象是 ADR-0023 §9 的 `PERF-M05-003` 口径；脚本允许减小规模做冒烟，
 * 但报告会记录实际规模，不把缩小规模冒充 1000 对象通过。
 */
const CONNECTOR_DEFAULTS = {
  objects: 1000,
  bucket: "riccon-perf-b11b-source",
  prefix: "perf-b11b/",
  endpointId: "rustfs-perf-b11b",
  connectorName: "PERF-M05b 连接器同步",
  host: "127.0.0.1:19000",
  region: "us-east-1",
  accessKey: "riccon_test_only",
  secretKey: "riccon_test_only_secret",
  uploadConcurrency: 8,
  token: "QCONNB11B",
  syncBudgetMs: 15 * 60_000,
  queueWaitBudgetMs: 5 * 60_000,
};

/** 证据文件。 */
const SEED_FILE = path.join(evidenceDir, "real-stack-seed.json");
const ENV_SNAPSHOT_FILE = path.join(evidenceDir, "real-stack-env.json");

/** 主密钥、pepper 与服务令牌只在本地测试栈使用，不写入输出。 */
const masterKey =
  process.env.RICCON_MASTER_KEY ?? randomBytes(32).toString("base64");
const pepper =
  process.env.RICCON_CREDENTIAL_FINGERPRINT_PEPPER ??
  randomBytes(32).toString("base64");
const masterKeyId = process.env.RICCON_MASTER_KEY_ID ?? "mk-1";
const serviceSecret =
  process.env.RICCON_SERVICE_TOKEN_SECRET ??
  `real-stack-service-token-${randomBytes(24).toString("hex")}`;

/** 运行中的子进程；finally 中统一回收。 */
const children = [];

/** 命令行参数；parseArgs 后填充。 */
const options = parseArgs(process.argv.slice(2));

/**
 * 打印用法。
 */
function usage() {
  console.log(`用法: node scripts/real-stack/run.mjs [options]

选项:
  --mode full|perf               full=完整真实栈回归（默认）；perf=只启 MySQL+假模型+API+Worker 跑 k6
  --perf-modes <list>            perf 降级矩阵，逗号分隔：unauthorized,rate_limited,server_error,unavailable,timeout,redirect
  --self-test                    校验脚本静态前置（假模型/种子/k6 脚本语法与可用工具），不启动服务
  --help                         打印本帮助

环境变量:
  REAL_EVIDENCE_TASK             证据目录任务号（默认 TASK-0024）
  REAL_EVIDENCE_DIR              证据目录绝对/相对路径；设置后覆盖 REAL_EVIDENCE_TASK 目录
  REAL_API_PORT/REAL_WORKER_PORT/REAL_WEB_PORT/REAL_MODEL_PORT
  REAL_PLAYWRIGHT_GREP           只过滤浏览器用例；全量 gate 不设置
  REAL_PERF_DURATION             perf 首字/降级场景时长（默认 45s）
  REAL_PERF_SKIP_M04=1           跳过 M04 首字/降级/配额/成本矩阵（M05 定向复验用）
  PERF_M05_SCENARIO=knowledge    启用 M05 知识库性能场景（OpenSearch+RustFS+10 万 chunk）
  PERF_M05_DURATION              knowledge 主负载时长（默认 60s）
  PERF_M05_DOCS/PERF_M05_CHUNKS_PER_DOC  语料规模（默认 5000/20）
  PERF_M05_BACKLOG               knowledge 运行期间并发上传的索引积压文档数（默认 6）
  PERF_M05_SEARCH_ENFORCE=1      检索 p95 >500ms 时硬失败（默认只标记 decisionRequired）
  PERF_M05_CONNECTOR=1           在 knowledge 场景前增加连接器同步分支（PERF-M05-003/004）
  PERF_M05_CONNECTOR_OBJECTS     源桶对象数（默认 1000；冒烟可减小，报告记录实际值）
  PERF_M05_CONNECTOR_BUCKET/PREFIX/ENDPOINT_ID  源桶、前缀与允许列表端点 ID
  PERF_M05_CONNECTOR_SYNC_TIMEOUT_MS  等待同步终态上限（默认 20min）
  PERF_M05_CONNECTOR_INDEX_TIMEOUT_MS 等待连接器文档索引完成上限（默认 20min）
  PERF_M05_CONNECTOR_CLEANUP=0   保留源桶对象（默认 1，同步结束后清理，下一次 down -v 也会清理）
  RICCON_OCR_LANG                宿主 OCR 探测语言（默认 chi_sim+eng）

示例:
  node scripts/real-stack/run.mjs --mode=full
  node scripts/real-stack/run.mjs --mode=perf --perf-modes=server_error,rate_limited,unauthorized,timeout
  PERF_M05_SCENARIO=knowledge REAL_PERF_SKIP_M04=1 node scripts/real-stack/run.mjs --mode=perf`);
}

/**
 * 解析命令行参数。
 *
 * @param {string[]} argv 参数
 * @returns {{mode: string, perfModes: string[], selfTest: boolean, help: boolean}} 参数
 */
function parseArgs(argv) {
  const result = { mode: "full", perfModes: [], selfTest: false, help: false };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === "--mode") {
      index += 1;
      result.mode = argv[index];
    } else if (arg.startsWith("--mode=")) {
      result.mode = arg.slice("--mode=".length);
    } else if (arg === "--perf-modes") {
      index += 1;
      result.perfModes = String(argv[index] ?? "")
        .split(",")
        .map((value) => value.trim())
        .filter(Boolean);
    } else if (arg.startsWith("--perf-modes=")) {
      result.perfModes = arg
        .slice("--perf-modes=".length)
        .split(",")
        .map((value) => value.trim())
        .filter(Boolean);
    } else if (arg === "--self-test") {
      result.selfTest = true;
    } else if (arg === "--help" || arg === "-h") {
      result.help = true;
    } else {
      throw new Error(`未知参数: ${arg}`);
    }
  }
  if (!["full", "perf"].includes(result.mode)) {
    throw new Error(`未知模式: ${result.mode}`);
  }
  if (result.perfModes.length === 0) {
    result.perfModes = [
      "server_error",
      "rate_limited",
      "unauthorized",
      "timeout",
    ];
  }
  const allowedModes = new Set([
    "unauthorized",
    "rate_limited",
    "server_error",
    "unavailable",
    "timeout",
    "redirect",
  ]);
  for (const mode of result.perfModes) {
    if (!allowedModes.has(mode)) {
      throw new Error(`未知降级模式: ${mode}`);
    }
  }
  return result;
}

/**
 * 启动子进程并把 stdout/stderr 写入证据日志。
 *
 * @param {string} name 名称
 * @param {string} command 命令
 * @param {string[]} args 参数
 * @param {object} [options] env/expectedExit
 * @returns {import('node:child_process').ChildProcess} 子进程
 */
function start(name, command, args, options = {}) {
  const logPath = path.join(evidenceDir, `real-stack-${name}.log`);
  const fd = openSync(logPath, "a");
  const child = spawn(command, args, {
    cwd: repoRoot,
    env: { ...process.env, ...(options.env ?? {}) },
    stdio: ["ignore", fd, fd],
  });
  child.on("exit", (code, signal) => {
    if (!options.expectedExit) {
      console.log(`[real-stack] ${name} exited code=${code} signal=${signal}`);
    }
  });
  children.push({ name, child });
  console.log(
    `[real-stack] started ${name} pid=${child.pid} log=${path.relative(repoRoot, logPath)}`,
  );
  return child;
}

/**
 * 同步执行并记录日志；非零退出即抛错。
 *
 * @param {string} name 名称
 * @param {string} command 命令
 * @param {string[]} args 参数
 * @param {object} [options] env/allowFailure
 * @returns {import('node:child_process').SpawnSyncReturns<string>} 结果
 */
function run(name, command, args, options = {}) {
  const logPath = path.join(evidenceDir, `real-stack-${name}.log`);
  const fd = openSync(logPath, "a");
  const result = spawnSync(command, args, {
    cwd: repoRoot,
    env: { ...process.env, ...(options.env ?? {}) },
    stdio: ["ignore", fd, fd],
  });
  if (result.status !== 0 && !options.allowFailure) {
    throw new Error(
      `${name} failed with exit ${result.status}; see ${path.relative(repoRoot, logPath)}`,
    );
  }
  return result;
}

/**
 * 同步执行并捕获 stdout；用于需要解析 JSON 的脚本。
 *
 * @param {string} name 名称
 * @param {string} command 命令
 * @param {string[]} args 参数
 * @param {object} [options] env
 * @returns {{status: number, stdout: string, stderr: string}} 结果
 */
function capture(name, command, args, options = {}) {
  const logPath = path.join(evidenceDir, `real-stack-${name}.log`);
  const result = spawnSync(command, args, {
    cwd: repoRoot,
    env: { ...process.env, ...(options.env ?? {}) },
    encoding: "utf8",
    maxBuffer: 64 * 1024 * 1024,
  });
  writeFileSync(logPath, `${result.stdout ?? ""}\n${result.stderr ?? ""}`);
  return {
    status: result.status,
    stdout: result.stdout ?? "",
    stderr: result.stderr ?? "",
  };
}

/**
 * 轮询 HTTP 健康检查。
 *
 * @param {string} url 地址
 * @param {number} [timeoutMs] 超时
 * @returns {Promise<void>}
 */
async function waitForHttp(url, timeoutMs = 120_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url);
      if (response.status >= 200 && response.status < 300) {
        return;
      }
    } catch {
      // 进程仍在启动。
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(`timeout waiting for ${url}`);
}

/** 停止全部子进程。 */
async function stopChildren() {
  for (const { name, child } of children.reverse()) {
    if (child.exitCode !== null || child.signalCode !== null) {
      continue;
    }
    console.log(`[real-stack] stopping ${name} pid=${child.pid}`);
    child.kill("SIGTERM");
  }
  await new Promise((resolve) => setTimeout(resolve, 3000));
  for (const { name, child } of children) {
    if (child.exitCode === null && child.signalCode === null) {
      console.log(`[real-stack] killing ${name} pid=${child.pid}`);
      child.kill("SIGKILL");
    }
  }
}

/**
 * 生成 S3 SigV4 请求头；只用于本地测试栈（RustFS）与 perf 夹具。
 *
 * @param {object} input 签名输入
 * @param {string} input.method HTTP 方法
 * @param {string} input.host 主机（含端口）
 * @param {string} input.canonicalPath 已编码的 canonical URI
 * @param {string} input.payloadHash 请求体 SHA-256
 * @param {string} input.accessKey 访问键
 * @param {string} input.secretKey 访问密钥
 * @param {string} input.region 区域
 * @returns {Record<string,string>} 请求头
 */
function s3AuthHeaders(input) {
  const amzDate = new Date()
    .toISOString()
    .replace(/[-:]/g, "")
    .replace(/\.\d{3}/, "");
  const date = amzDate.slice(0, 8);
  const canonicalHeaders = `host:${input.host}\nx-amz-content-sha256:${input.payloadHash}\nx-amz-date:${amzDate}\n`;
  const signedHeaders = "host;x-amz-content-sha256;x-amz-date";
  const canonicalRequest = `${input.method}\n${input.canonicalPath}\n\n${canonicalHeaders}\n${signedHeaders}\n${input.payloadHash}`;
  const scope = `${date}/${input.region}/s3/aws4_request`;
  const stringToSign = `AWS4-HMAC-SHA256\n${amzDate}\n${scope}\n${createHash(
    "sha256",
  )
    .update(canonicalRequest)
    .digest("hex")}`;
  const hmac = (key, value) => createHmac("sha256", key).update(value).digest();
  const signingKey = hmac(
    hmac(hmac(hmac(`AWS4${input.secretKey}`, date), input.region), "s3"),
    "aws4_request",
  );
  const signature = createHmac("sha256", signingKey)
    .update(stringToSign)
    .digest("hex");
  return {
    Host: input.host,
    "x-amz-content-sha256": input.payloadHash,
    "x-amz-date": amzDate,
    Authorization: `AWS4-HMAC-SHA256 Credential=${input.accessKey}/${scope}, SignedHeaders=${signedHeaders}, Signature=${signature}`,
  };
}

/**
 * 调用测试栈 S3 兼容接口；只支持 perf 夹具需要的 PUT/DELETE。
 *
 * @param {object} input 请求参数
 * @param {'PUT'|'DELETE'} input.method 方法
 * @param {string} input.bucket 桶
 * @param {string} [input.key] 对象 key；空表示桶操作
 * @param {string} [input.body] 请求体
 * @param {string} [input.host] 主机（含端口）
 * @param {string} [input.region] 区域
 * @param {string} [input.accessKey] 访问键
 * @param {string} [input.secretKey] 访问密钥
 * @returns {Promise<Response>} 响应
 */
async function s3Request(input) {
  const host = input.host ?? CONNECTOR_DEFAULTS.host;
  const region = input.region ?? CONNECTOR_DEFAULTS.region;
  const payload = input.body ?? "";
  const payloadHash = createHash("sha256").update(payload).digest("hex");
  const canonicalPath = input.key
    ? `/${input.bucket}/${input.key
        .split("/")
        .map((segment) => encodeURIComponent(segment))
        .join("/")}`
    : `/${input.bucket}`;
  const headers = s3AuthHeaders({
    method: input.method,
    host,
    canonicalPath,
    payloadHash,
    accessKey: input.accessKey ?? CONNECTOR_DEFAULTS.accessKey,
    secretKey: input.secretKey ?? CONNECTOR_DEFAULTS.secretKey,
    region,
  });
  return fetch(`http://${host}${canonicalPath}`, {
    method: input.method,
    headers,
    body: input.method === "PUT" ? payload : undefined,
  });
}

/**
 * 带有限重试的 S3 调用；只重试网络异常、429 与 5xx，4xx 立即返回由调用方记录。
 *
 * <p>1000 对象上传会建立大量短连接；测试栈在高负载或 RustFS 短暂抖动时可能出现
 * `fetch failed`。夹具层重试可避免把一次瞬时网络错误误报成连接器功能失败。
 *
 * @param {object} input s3Request 参数
 * @param {number} [attempts] 最大尝试次数
 * @returns {Promise<Response>} 响应
 */
async function s3RequestWithRetry(input, attempts = 3) {
  let lastError = null;
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      const response = await s3Request(input);
      if (response.status === 429 || response.status >= 500) {
        // 消费失败响应体，避免 undici 连接被未读流占用。
        await response.text().catch(() => "");
        lastError = new Error(`S3 ${response.status}`);
        if (attempt < attempts) {
          await sleep(100 * attempt);
          continue;
        }
      }
      // 成功/幂等冲突响应也必须消费 body：1000 个短 PUT 若留下未读流，会耗尽
      // undici 连接池并在后续请求抛出 fetch failed。4xx 交给调用方读取错误详情。
      if (response.ok || [404, 409].includes(response.status)) {
        await response.arrayBuffer().catch(() => undefined);
      }
      return response;
    } catch (error) {
      lastError = error;
      if (attempt < attempts) {
        await sleep(100 * attempt);
        continue;
      }
    }
  }
  throw lastError ?? new Error("S3 request failed");
}

/**
 * 创建桶；已存在（409）视为成功，便于重复运行。
 *
 * @param {string} bucket 桶名
 * @param {object} [options] 主机/区域/凭据覆盖
 * @returns {Promise<void>}
 */
async function createS3Bucket(bucket, options = {}) {
  const response = await s3RequestWithRetry({
    method: "PUT",
    bucket,
    ...options,
  });
  if (![200, 409].includes(response.status)) {
    throw new Error(
      `create bucket failed: ${response.status} ${await response.text()}`,
    );
  }
}

/**
 * 使用 SigV4 创建测试桶；RustFS 不提供免认证建桶。
 *
 * @returns {Promise<void>}
 */
async function createBucket() {
  await createS3Bucket("riccon-test");
}

/**
 * 计算假模型端点列表与 canonical hash；API/Worker 与 seed 共用同一结果。
 *
 * @returns {{endpoints: Array<{code: string, baseUrl: string, environmentLabel: string}>, hash: string}} 端点
 */
function buildModelEndpoints() {
  const endpoints = [
    {
      code: "fake-primary",
      baseUrl: `http://127.0.0.1:${MODEL_PORT}/v1`,
      environmentLabel: "DEMO_ONLY",
    },
    {
      code: "fake-secondary",
      baseUrl: `http://127.0.0.1:${MODEL_PORT}/v1-secondary`,
      environmentLabel: "DEMO_ONLY",
    },
  ];
  const canonical = JSON.stringify(
    endpoints.map((endpoint) => ({
      code: endpoint.code,
      baseUrl: endpoint.baseUrl,
      environmentLabel: endpoint.environmentLabel,
    })),
  );
  return {
    endpoints,
    hash: createHash("sha256").update(canonical).digest("hex"),
  };
}

/**
 * 生成假模型 key 表；key 只存在于进程 env 与 seed SQL，不写入日志/证据。
 *
 * @param {string} badScenario fake-primary key 的故障场景（full 模式为 ok）
 * @returns {{json: string, badKey: string, goodKey: string}} key 表
 */
function buildModelKeys(badScenario) {
  const primaryKey = `fake-primary-key-${randomBytes(12).toString("hex")}`;
  const secondaryKey = `fake-secondary-key-${randomBytes(12).toString("hex")}`;
  return {
    json: JSON.stringify({
      "fake-primary": [
        {
          id: "0192f0a0-0000-7000-8000-000000000121",
          label: "主 key（真实栈）",
          key: primaryKey,
          priority: 10,
        },
      ],
      "fake-secondary": [
        {
          id: "0192f0a0-0000-7000-8000-000000000122",
          label: "备用 key（真实栈）",
          key: secondaryKey,
          priority: 10,
        },
      ],
    }),
    badKey: primaryKey,
    goodKey: secondaryKey,
    primaryScenario: badScenario,
  };
}

/**
 * 读取脚本 sha256。
 *
 * @param {string} relativePath 相对路径
 * @returns {string|null} sha256
 */
function fileSha256(relativePath) {
  const file = path.join(repoRoot, relativePath);
  return existsSync(file)
    ? createHash("sha256").update(readFileSync(file)).digest("hex")
    : null;
}

/**
 * 读取 compose 镜像 tag。
 *
 * @returns {string[]} 镜像列表
 */
function composeImages() {
  const result = spawnSync(
    "docker",
    [
      "compose",
      "-f",
      "deploy/compose/docker-compose.test.yml",
      "config",
      "--images",
    ],
    {
      cwd: repoRoot,
      encoding: "utf8",
    },
  );
  if (result.status !== 0) {
    return [];
  }
  return result.stdout
    .trim()
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);
}

/**
 * 读取 k6 版本；缺失时返回 null。
 *
 * @returns {string|null} 版本
 */
function k6Version() {
  const result = spawnSync("k6", ["version"], { encoding: "utf8" });
  return result.status === 0 ? result.stdout.trim() : null;
}

/**
 * 生成带 +08:00 偏移的 ISO 时间；项目默认 Asia/Shanghai。
 *
 * @param {Date} [date] 时间
 * @returns {string} ISO 8601 带偏移
 */
function shanghaiIso(date = new Date()) {
  const offsetMinutes = 8 * 60;
  const shifted = new Date(date.getTime() + offsetMinutes * 60_000);
  return `${shifted.toISOString().slice(0, 19)}+08:00`;
}

/**
 * 截断长文本，避免把 native/JDBC 输出写进报告。
 *
 * @param {string} value 原文
 * @param {number} [max] 最大长度
 * @returns {string} 截断结果
 */
function truncateText(value, max = 400) {
  const text = String(value ?? "").trim();
  return text.length > max ? `${text.slice(0, max)}…` : text;
}

/**
 * 异步等待；只用于夹具上传重试与终态轮询。
 *
 * @param {number} ms 毫秒
 * @returns {Promise<void>}
 */
function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * 计算 nearest-rank 分位数；values 会被复制排序，不修改入参。
 *
 * @param {number[]} values 样本
 * @param {number} percentile 0–100
 * @returns {number|null} 分位数
 */
function percentile(values, percentile) {
  if (!Array.isArray(values) || values.length === 0) {
    return null;
  }
  const sorted = [...values].sort((left, right) => left - right);
  const rank = Math.ceil((percentile / 100) * sorted.length);
  return sorted[Math.min(sorted.length, Math.max(1, rank)) - 1];
}

/**
 * 以 root 执行 SQL；slow_log 的全局开关与 mysql.slow_log 读取需要 SYSTEM_VARIABLES_ADMIN。
 *
 * @param {string} sql SQL 文本
 * @param {{ allowFailure?: boolean }} [options] 选项
 * @returns {{status: number, stdout: string, stderr: string}} 结果
 */
function runRootSql(sql, options = {}) {
  const result = spawnSync(
    "docker",
    [
      "compose",
      "-f",
      "deploy/compose/docker-compose.test.yml",
      "exec",
      "-T",
      "-e",
      `MYSQL_PWD=${process.env.RICCON_DB_ROOT_PASSWORD ?? "riccon_root_test_only"}`,
      "mysql",
      "mysql",
      "--user=root",
      "--database=riccon",
      "--batch",
      "--raw",
      "--default-character-set=utf8mb4",
    ],
    {
      cwd: repoRoot,
      input: sql,
      encoding: "utf8",
      maxBuffer: 32 * 1024 * 1024,
    },
  );
  if (result.status !== 0 && !options.allowFailure) {
    throw new Error(
      `root mysql failed (exit ${result.status}): ${result.stderr || result.stdout}`,
    );
  }
  return {
    status: result.status,
    stdout: result.stdout ?? "",
    stderr: result.stderr ?? "",
  };
}

/**
 * 探测宿主是否可加载 libtesseract 并完成一次 OCR。
 *
 * Worker 在 `riccon.worker.ocr.enabled=true` 时构造期加载原生库，宿主缺库会让 Worker
 * 启动失败。先跑 OcrCli 再决定 OCR 开关，避免把“宿主缺 OCR”误报成性能失败；
 * 探测结果写入报告，未量测的 OCR 指标不会被当成通过。
 *
 * @returns {{available: boolean, exitCode: number|null, engine: string, language: string, sample: string, output: string, probedAt: string}} 探测结果
 */
function probeOcr() {
  const jar = "apps/worker/target/riccon-worker-0.1.0-SNAPSHOT-exec.jar";
  const language = process.env.RICCON_OCR_LANG ?? "chi_sim+eng";
  const result = {
    available: false,
    exitCode: null,
    engine: "tesseract",
    language,
    sample: "deploy/ocr/sample.png",
    output: "",
    probedAt: shanghaiIso(),
  };
  if (!existsSync(path.join(repoRoot, jar))) {
    result.output = `missing ${jar}`;
    return result;
  }
  const args = [
    "-Dloader.main=com.riccon.worker.ocr.service.OcrCli",
    "-cp",
    jar,
    "org.springframework.boot.loader.launch.PropertiesLauncher",
    "deploy/ocr/sample.png",
    "--lang",
    language,
  ];
  if (process.env.RICCON_OCR_TESSDATA_PATH) {
    args.push("--tessdata", process.env.RICCON_OCR_TESSDATA_PATH);
  }
  const probe = spawnSync("java", args, {
    cwd: repoRoot,
    encoding: "utf8",
    timeout: 120_000,
  });
  result.exitCode = probe.status;
  result.available = probe.status === 0;
  result.output = truncateText(`${probe.stdout ?? ""}\n${probe.stderr ?? ""}`);
  return result;
}

/**
 * 构造环境快照：host CPU/内存、镜像 tag、k6 版本、脚本 sha、启动命令与 perf profile。
 *
 * @param {object} context 运行上下文
 * @returns {object} 快照
 */
function buildEnvSnapshot(context) {
  return {
    generatedAt: new Date().toISOString(),
    mode: context.mode,
    host: {
      platform: `${os.platform()} ${os.release()}`,
      arch: os.arch(),
      cpus: os.cpus().length,
      cpuModel: os.cpus()[0]?.model ?? "unknown",
      memoryBytes: os.totalmem(),
      memoryGiB: Number((os.totalmem() / 1024 ** 3).toFixed(2)),
      loadAverage: os.loadavg().map((value) => Number(value.toFixed(2))),
      node: process.version,
    },
    images: {
      compose: composeImages(),
      started:
        context.mode === "perf"
          ? (context.startedImages ?? ["mysql:8.4.13"])
          : composeImages(),
    },
    k6Version: k6Version(),
    scriptSha256: fileSha256("e2e/perf/mvc-smoke.js"),
    // k6 前置校验字段；必须与注入 API/Worker 的 perf profile 一致。
    // perf profile 使用 50；full 模式保持 25；ADR 的准入下限是 ≥25。
    chatMaxStreams: 50,
    dbPoolSize: context.mode === "perf" ? 50 : 25,
    fakeModel: 1,
    deploymentMode: "DEMO",
    // M06 运行限制实际注入值与来源；T16a 要求快照登记默认值与来源，避免报告与运行漂移。
    workflow: workflowEnvSnapshot(),
    ocr: context.ocrProbe ?? null,
    startCommand:
      `node scripts/real-stack/run.mjs ${process.argv.slice(2).join(" ")}`.trim(),
    startupCommands: [
      `java -jar apps/api/target/riccon-api-0.1.0-SNAPSHOT-exec.jar (env keys: RICCON_MODEL_DEPLOYMENT_MODE/ENDPOINTS/ALLOW_HTTP/MASTER_KEY/MASTER_KEY_ID/FINGERPRINT_PEPPER/BILLING_CURRENCY/QUOTA_DEFAULT_*/DB_POOL)`,
      `java -jar apps/worker/target/riccon-worker-0.1.0-SNAPSHOT-exec.jar (env keys: RICCON_MODEL_DEPLOYMENT_MODE/ENDPOINTS/ALLOW_HTTP/MASTER_KEY/CHAT_MAX_STREAMS/DB_POOL_SIZE/MODEL_FIRST_TOKEN_TIMEOUT/PRE_FIRST_TOKEN_BUDGET)`,
      `node scripts/real-stack/fake-model.mjs --scenario ok --chunk-count ${context.mode === "perf" ? 6 : 18}`,
    ],
    perfProfile:
      context.mode === "perf"
        ? {
            chatMaxStreams: 50,
            dbPoolSize: 50,
            dbPoolFloor: 25,
            fakeModel: 1,
            connectTimeout: "500ms",
            firstTokenTimeout: "1000ms",
            preFirstTokenBudget: "2500ms",
            maxAttempts: 3,
            retryDelays: "50ms,100ms",
            duration: process.env.REAL_PERF_DURATION ?? "45s",
          }
        : null,
    openSearch:
      context.mode === "perf"
        ? context.knowledgePerf
          ? `started (perf knowledge; riccon-kb index-version ${context.openSearchIndexVersion ?? "v1"}, 512m heap)`
          : "excluded (perf mode; 2C4Gi sandbox 不足，主机规格见 host)"
        : "started (compose full)",
    perfM05:
      context.mode === "perf" && context.knowledgePerf
        ? {
            scenario: "knowledge",
            dataset: {
              documents: context.knowledgeDataset?.documents ?? null,
              chunks: context.knowledgeDataset?.chunks ?? null,
              chunksPerDocument:
                context.knowledgeDataset?.chunksPerDocument ?? null,
              query: KNOWLEDGE_QUERY,
              matchRatio: `每 ${KNOWLEDGE_MATCH_EVERY} 篇文档命中（默认 1% chunk）`,
            },
            concurrency: {
              knowledgeVus: KNOWLEDGE_DEFAULTS.knowledgeVus,
              aclWriteVus: 1,
            },
            duration:
              process.env.PERF_M05_DURATION ?? KNOWLEDGE_DEFAULTS.duration,
            dbPoolSize: 50,
            chatMaxStreams: 50,
            fakeModel: 1,
            aclEnforcement: true,
            // 复现命令只列非密钥参数；主密钥/pepper/服务令牌由脚本每次随机生成，
            // 报告不保存，验证者复验时也不需要复用同一份。
            reproduceCommand: [
              "PERF_M05_SCENARIO=knowledge",
              context.connectorPerf ? "PERF_M05_CONNECTOR=1" : null,
              context.connectorPerf
                ? `PERF_M05_CONNECTOR_OBJECTS=${context.connectorConfig.objects}`
                : null,
              context.connectorPerf
                ? `PERF_M05_CONNECTOR_BUCKET=${context.connectorConfig.bucket}`
                : null,
              `PERF_M05_DOCS=${context.knowledgeDataset?.documents ?? ""}`,
              `PERF_M05_CHUNKS_PER_DOC=${context.knowledgeDataset?.chunksPerDocument ?? ""}`,
              `PERF_M05_DURATION=${process.env.PERF_M05_DURATION ?? KNOWLEDGE_DEFAULTS.duration}`,
              `PERF_M05_BACKLOG=${process.env.PERF_M05_BACKLOG ?? KNOWLEDGE_DEFAULTS.backlogUploads}`,
              "REAL_PERF_SKIP_M04=1",
              "node scripts/real-stack/run.mjs --mode=perf",
            ]
              .filter(Boolean)
              .join(" "),
            connector: context.connectorPerf
              ? {
                  enabled: true,
                  ...publicConnectorConfig(context.connectorConfig),
                }
              : { enabled: false },
          }
        : null,
  };
}

/**
 * 生成共享 M04 env；API 与 Worker 必须收到同一份。
 *
 * @param {object} context 上下文
 * @returns {Record<string,string>} 环境变量
 */
function sharedModelEnv(context) {
  // perf profile 使用 50 连接承接 50 并发 SSE + 50 个 run-detail GET；full 模式保持 25。
  // 这是性能验证 profile，不是生产默认承诺（ADR 只要求 ≥25）。
  const dbPool = context.mode === "perf" ? "50" : "25";
  return {
    RICCON_MASTER_KEY: masterKey,
    RICCON_MASTER_KEY_ID: masterKeyId,
    RICCON_CREDENTIAL_FINGERPRINT_PEPPER: pepper,
    RICCON_MODEL_DEPLOYMENT_MODE: "DEMO",
    RICCON_MODEL_ENDPOINTS: JSON.stringify(context.endpoints),
    RICCON_MODEL_ENDPOINT_SET_HASH: context.endpointHash,
    RICCON_MODEL_ALLOW_HTTP: "true",
    RICCON_BILLING_CURRENCY: "CNY",
    RICCON_QUOTA_DEFAULT_TENANT_DAILY: "10000",
    RICCON_QUOTA_DEFAULT_TENANT_MONTHLY: "200000",
    RICCON_QUOTA_DEFAULT_USER_DAILY: "1000",
    RICCON_QUOTA_DEFAULT_USER_MONTHLY: "20000",
    RICCON_CHAT_MAX_STREAMS: "50",
    RICCON_DB_POOL_SIZE: dbPool,
    RICCON_FAKE_MODEL: "1",
    SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE: dbPool,
    // 知识库场景必须开启文档级 ACL，否则列表/检索/下载量测的是无 ACL 的旧路径。
    // v2 索引是 B7 的默认验收索引：Worker 写入 riccon-kb-v2-{tenant}，别名在全新测试栈指向 v2。
    ...(context.knowledgePerf
      ? {
          RICCON_KB_ACL_ENFORCEMENT_ENABLED: "true",
          RICCON_OPENSEARCH_INDEX_VERSION:
            context.openSearchIndexVersion ?? "v2",
          RICCON_OPENSEARCH_INDEX_PREFIX:
            context.openSearchIndexPrefix ?? "riccon-kb",
        }
      : {}),
    // M05b 连接器同步分支：API 与 Worker 共用允许列表与开关；端点只来自部署 env，
    // 连接器只保存 endpointId。关闭定时调度避免 perf 运行期间出现额外同步。
    ...(context.connectorPerf
      ? {
          RICCON_CONNECTOR_ENABLED: "true",
          RICCON_CONNECTOR_ENDPOINTS: JSON.stringify(
            context.connectorEndpoints,
          ),
          RICCON_CONNECTOR_SYNC_INTERVAL_MINUTES: "60",
          RICCON_CONNECTOR_SCHEDULER_ENABLED: "false",
          RICCON_CONNECTOR_PATH_STYLE: "true",
          RICCON_CONNECTOR_DEFAULT_REGION: context.connectorConfig.region,
        }
      : {}),
  };
}

/**
 * 读取 Flyway 最大成功版本；表尚未创建或查询失败时返回 0。
 *
 * @returns {number} 最大成功版本
 */
function currentFlywayVersion() {
  const result = runSql(
    `SELECT COALESCE(MAX(CAST(version AS UNSIGNED)), 0) AS version
       FROM flyway_schema_history WHERE success = 1;`,
    { allowFailure: true, repoRoot },
  );
  if (result.status !== 0) {
    return 0;
  }
  const lines = String(result.stdout ?? "")
    .trim()
    .split("\n")
    .filter(Boolean);
  const value = Number(lines[lines.length - 1]);
  return Number.isFinite(value) ? value : 0;
}

/**
 * 等待 API 完成 Flyway 迁移到期望版本。
 *
 * @param {number} expected 期望版本
 * @param {number} [timeoutMs] 超时
 * @returns {Promise<number>} 实际达到的版本
 */
async function waitForFlywayVersion(expected, timeoutMs = 120_000) {
  const deadline = Date.now() + timeoutMs;
  let last = 0;
  while (Date.now() < deadline) {
    last = currentFlywayVersion();
    if (last >= expected) {
      return last;
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(
    `timeout waiting for flyway version ${expected}; last=${last}`,
  );
}

/**
 * 启动 API 与 Worker。
 *
 * 顺序约束：`down -v` 后必须先由 API（唯一迁移者）完成 Flyway 到 V11，再启动 Worker；
 * Worker 启动时会校验 `flyway_schema_history` 最大成功版本，早启动会以 RICCON-WORKER-001 退出。
 *
 * @param {object} context 上下文
 * @param {object} [extra] 额外 env（perf 超时 profile）
 * @returns {Promise<{flywayVersion: number}>} 启动结果
 */
async function startApplications(context, extra = {}) {
  const shared = { ...sharedModelEnv(context), ...extra };
  start(
    "api",
    "java",
    ["-jar", "apps/api/target/riccon-api-0.1.0-SNAPSHOT-exec.jar"],
    {
      env: {
        ...shared,
        ...WORKFLOW_ENV,
        RICCON_BOOTSTRAP_ADMIN_USERNAME: "platform-admin",
        RICCON_BOOTSTRAP_ADMIN_PASSWORD: "test-bootstrap-password-12",
        RICCON_SERVICE_TOKEN_SECRET: serviceSecret,
        RICCON_API_PORT: String(API_PORT),
        RICCON_WORKER_BASE_URL: `http://127.0.0.1:${WORKER_PORT}`,
        RICCON_PUBLIC_BASE_URL: `http://127.0.0.1:${WEB_PORT}`,
        RICCON_OIDC_ALLOW_HTTP: "true",
        // 真实栈故障注入需要每次授权都重新发现，避免 10 分钟缓存掩盖 IdP 不可达。
        RICCON_OIDC_DISCOVERY_CACHE_TTL: "1ms",
        RICCON_OIDC_IDPS: JSON.stringify([
          {
            tenantCode: OIDC_TENANT,
            issuer: OIDC_ISSUER,
            clientId: OIDC_CLIENT_ID,
            clientSecret: OIDC_CLIENT_SECRET,
            providerName: OIDC_PROVIDER_NAME,
            scopes: ["openid", "profile", "email"],
            endSessionEndpoint: `${OIDC_ISSUER}/end-session`,
            enabled: true,
          },
        ]),
      },
    },
  );
  await waitForHttp(`http://127.0.0.1:${API_PORT}/actuator/health`, 120_000);
  const flywayVersion = await waitForFlywayVersion(
    FLYWAY_EXPECTED_VERSION,
    120_000,
  );
  console.log(
    `[real-stack] api ready (flyway=${flywayVersion}); starting worker`,
  );

  start(
    "worker",
    "java",
    ["-jar", "apps/worker/target/riccon-worker-0.1.0-SNAPSHOT-exec.jar"],
    {
      env: {
        ...shared,
        ...WORKFLOW_ENV,
        RICCON_SERVICE_TOKEN_SECRET: serviceSecret,
        RICCON_S3_BUCKET: "riccon-test",
        RICCON_MCP_ALLOW_STDIO: "true",
        RICCON_MCP_ALLOWED_SERVERS: "riccon-builtin",
        RICCON_MCP_ALLOW_HTTP: "true",
        RICCON_WORKER_PORT: String(WORKER_PORT),
        // 宿主缺 libtesseract 时关闭 OCR，避免 Worker 启动失败；探测结果写入 env.ocr。
        ...(context.ocrEnv ?? {}),
        ...extra,
      },
    },
  );
  await waitForHttp(`http://127.0.0.1:${WORKER_PORT}/actuator/health`, 120_000);
  return { flywayVersion };
}

/**
 * 等待 API/Worker 健康并比对 `/actuator/info` 指纹（ADR-0021 §2.1/§10.2）。
 *
 * @returns {Promise<object>} 两侧 info
 */
async function verifyActuatorFingerprint() {
  await waitForHttp(`http://127.0.0.1:${API_PORT}/actuator/health`, 120_000);
  await waitForHttp(`http://127.0.0.1:${WORKER_PORT}/actuator/health`, 120_000);
  const apiResponse = await fetch(`http://127.0.0.1:${API_PORT}/actuator/info`);
  const workerResponse = await fetch(
    `http://127.0.0.1:${WORKER_PORT}/actuator/info`,
  );
  if (apiResponse.status !== 200 || workerResponse.status !== 200) {
    // M04 默认拒绝未放行 /actuator/info；这里记录可见性，不做跨端点相等的假绿。
    // 同一份 env 由 startApplications 的 sharedModelEnv 注入两侧，运行期模型调用可进一步验证。
    return {
      matched: false,
      visibility: "default-deny",
      apiStatus: apiResponse.status,
      workerStatus: workerResponse.status,
      note: "actuator/info is not permitted by default-deny security chain; shared env injection is recorded in real-stack-env.json",
    };
  }
  const apiInfo = await apiResponse.json();
  const workerInfo = await workerResponse.json();
  const select = (info) => ({
    deploymentMode: info?.riccon?.model?.deploymentMode,
    endpointSetHash: info?.riccon?.model?.endpointSetHash,
    routingMode: info?.riccon?.model?.routingMode,
    failoverEnabled: info?.riccon?.model?.failoverEnabled,
    quotaEnforcementEnabled: info?.riccon?.model?.quotaEnforcementEnabled,
    masterKeyId: info?.riccon?.masterKeyId,
    currency: info?.riccon?.billing?.currency,
    tenantDaily: info?.riccon?.quota?.tenantDaily,
    tenantMonthly: info?.riccon?.quota?.tenantMonthly,
    userDaily: info?.riccon?.quota?.userDaily,
    userMonthly: info?.riccon?.quota?.userMonthly,
  });
  const api = select(apiInfo);
  const worker = select(workerInfo);
  const mismatched = Object.keys(api).filter(
    (key) => String(api[key]) !== String(worker[key]),
  );
  if (mismatched.length > 0) {
    throw new Error(
      `API/Worker /actuator/info mismatch: ${mismatched.join(", ")} api=${JSON.stringify(api)} worker=${JSON.stringify(worker)}`,
    );
  }
  return { api, worker, matched: true };
}

/**
 * 确认构建产物存在。
 */
function requireArtifacts() {
  for (const artifact of [
    "apps/api/target/riccon-api-0.1.0-SNAPSHOT-exec.jar",
    "apps/worker/target/riccon-worker-0.1.0-SNAPSHOT-exec.jar",
  ]) {
    if (!existsSync(path.join(repoRoot, artifact))) {
      throw new Error(
        `missing build artifact ${artifact}; run contract build first`,
      );
    }
  }
}

/**
 * 在 fake provider 上设置 key 维度场景。
 *
 * @param {string} scenario 场景名
 * @param {string} badKey 主 key
 * @param {string} goodKey 备用 key
 * @returns {Promise<void>}
 */
async function setFakeKeyScenarios(scenario, badKey, goodKey) {
  const response = await fetch(`http://127.0.0.1:${MODEL_PORT}/__keys__`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ keys: { [badKey]: scenario, [goodKey]: "ok" } }),
  });
  if (response.status !== 200) {
    throw new Error(
      `set fake key scenarios failed: ${response.status} ${await response.text()}`,
    );
  }
}

/**
 * 清理平台 key 健康状态；让每个降级模式从同一初始状态开始。
 *
 * @param {string} mode 场景
 */
function resetKeyHealth(mode) {
  const sql = `
UPDATE provider_credential
   SET status = 'ACTIVE', consecutive_failures = 0, cooldown_until = NULL, invalidated_at = NULL
 WHERE tenant_id = 'platform' AND credential_type = 'API_KEY';
`;
  // 复用共享客户端：字符集与 seed/checks 保持一致，避免连接参数漂移。
  const result = runSql(sql, { repoRoot, allowFailure: true });
  if (result.status !== 0) {
    throw new Error(`reset key health failed (${mode}): ${result.stderr}`);
  }
}

/**
 * 运行一次 k6 场景并写报告。
 *
 * @param {object} context 上下文
 * @param {string} scenario k6 场景
 * @param {object} [extra] 额外 env
 * @returns {{scenario: string, failureMode: string|null, status: number, report: string, log: string}} 结果
 */
function runK6(context, scenario, extra = {}) {
  const suffix = extra.PERF_M04_FAILURE_MODE
    ? `-${extra.PERF_M04_FAILURE_MODE}`
    : "";
  const reportFile = path.join(
    evidenceDir,
    `perf-m04-${scenario}${suffix}.json`,
  );
  const logFile = path.join(evidenceDir, `perf-m04-${scenario}${suffix}.log`);
  const envList = {
    PERF_M04_ENV_JSON: JSON.stringify(context.envSnapshot),
    PERF_M04_BASE_URL: `http://127.0.0.1:${API_PORT}`,
    PERF_M04_WORKSPACE_ID: context.workspaceId,
    PERF_M04_SCENARIO: scenario,
    PERF_M04_REPORT_FILE: reportFile,
    PERF_M04_DURATION: process.env.REAL_PERF_DURATION ?? "45s",
    ...extra,
  };
  const args = ["run"];
  for (const [key, value] of Object.entries(envList)) {
    args.push("-e", `${key}=${value}`);
  }
  args.push("e2e/perf/mvc-smoke.js");
  const result = spawnSync("k6", args, {
    cwd: repoRoot,
    env: { ...process.env, ...context.modelKeysEnv, ...envList },
    encoding: "utf8",
    maxBuffer: 64 * 1024 * 1024,
  });
  writeFileSync(logFile, `${result.stdout ?? ""}\n${result.stderr ?? ""}`);
  console.log(
    `[real-stack] k6 ${scenario}${suffix} exit=${result.status} report=${path.relative(repoRoot, reportFile)}`,
  );
  return {
    scenario,
    failureMode: extra.PERF_M04_FAILURE_MODE ?? null,
    status: result.status,
    report: path.relative(repoRoot, reportFile),
    log: path.relative(repoRoot, logFile),
  };
}

/**
 * 创建 PERF-M06-001 使用的真实流程：检索 → 模型 → 结束，仅自己能运行。
 *
 * <p>走公开 API 创建/保存/发布，与用户操作同链路；脚本不直接写 workflow 表，避免夹具绕过校验。</p>
 *
 * @param {object} seedState real-stack seed 结果
 * @returns {Promise<{workflowId: string, versionId: string, name: string}>} 夹具
 */
async function createWorkflowPerfFixture(seedState) {
  const client = new KnowledgePerfClient(`http://127.0.0.1:${API_PORT}`);
  const csrf = await client.request("GET", "/api/v1/auth/csrf");
  if (csrf.status !== 200) {
    throw new Error(`M06 perf csrf failed: ${csrf.status} ${csrf.text}`);
  }
  client.csrf = csrf.data?.token ?? "";
  const login = await client.request("POST", "/api/v1/auth/login", {
    json: {
      tenantCode: "tenant-alpha",
      username: "alpha-editor",
      password: "alpha-editor-password-12",
    },
  });
  if (login.status !== 200) {
    throw new Error(`M06 perf login failed: ${login.status} ${login.text}`);
  }
  client.csrf = login.data?.csrfToken ?? client.csrf;
  const name = `PERF-M06 流程 ${Date.now()}`;
  const created = await client.request("POST", "/api/v1/workflows", {
    json: { name },
    headers: { "Idempotency-Key": `perf-m06-create-${Date.now()}` },
  });
  if (created.status !== 201) {
    throw new Error(`M06 perf create failed: ${created.status} ${created.text}`);
  }
  const workflowId = created.data.id;
  const graph = {
    schemaVersion: 1,
    nodes: [
      { id: "start", type: "START", name: "开始", config: {} },
      {
        id: "retrieval",
        type: "RETRIEVAL",
        name: "检索知识库",
        config: { queryTemplate: "{{input.topic}}", topK: 5 },
      },
      {
        id: "model",
        type: "MODEL",
        name: "生成结果",
        config: { promptTemplate: "整理主题：{{input.topic}}", outputKind: "TEXT" },
      },
      { id: "end", type: "END", name: "结束", config: { resultFrom: "model", resultTitle: "性能结果" } },
    ],
    edges: [
      { id: "e1", source: "start", target: "retrieval" },
      { id: "e2", source: "retrieval", target: "model" },
      { id: "e3", source: "model", target: "end" },
    ],
  };
  const saved = await client.request("PUT", `/api/v1/workflows/${workflowId}/draft`, {
    json: { version: 1, graph },
  });
  if (saved.status !== 200) {
    throw new Error(`M06 perf save failed: ${saved.status} ${saved.text}`);
  }
  const published = await client.request("POST", `/api/v1/workflows/${workflowId}/publish`, {
    json: { runScope: "ONLY_SELF" },
    headers: { "Idempotency-Key": `perf-m06-publish-${Date.now()}` },
  });
  if (published.status !== 201) {
    throw new Error(`M06 perf publish failed: ${published.status} ${published.text}`);
  }

  // 写路径专用流程：只被 save/validate/publish 使用，避免与运行准入夹具互相污染版本。
  const adminName = `${name} 写路径`;
  const adminCreated = await client.request("POST", "/api/v1/workflows", {
    json: { name: adminName },
    headers: { "Idempotency-Key": `perf-m06-admin-create-${Date.now()}` },
  });
  if (adminCreated.status !== 201) {
    throw new Error(`M06 perf admin create failed: ${adminCreated.status} ${adminCreated.text}`);
  }
  const adminWorkflowId = adminCreated.data.id;
  const adminSaved = await client.request("PUT", `/api/v1/workflows/${adminWorkflowId}/draft`, {
    json: { version: 1, graph },
  });
  if (adminSaved.status !== 200) {
    throw new Error(`M06 perf admin save failed: ${adminSaved.status} ${adminSaved.text}`);
  }
  return { workflowId, versionId: published.data.id, name, adminWorkflowId };
}

/**
 * 并发上限探针：同时提交 tenantConcurrency+2 个运行，断言至少一个稳定 429 提示。
 *
 * @param {object} seedState seed 结果
 * @param {object} fixture 流程夹具
 * @returns {Promise<object>} 探针结果
 */
async function probeWorkflowConcurrencyLimit(seedState, fixture) {
  const client = new KnowledgePerfClient(`http://127.0.0.1:${API_PORT}`);
  const csrf = await client.request("GET", "/api/v1/auth/csrf");
  client.csrf = csrf.data?.token ?? "";
  const login = await client.request("POST", "/api/v1/auth/login", {
    json: {
      tenantCode: "tenant-alpha",
      username: "alpha-editor",
      password: "alpha-editor-password-12",
    },
  });
  if (login.status !== 200) {
    throw new Error(`M06 limit probe login failed: ${login.status} ${login.text}`);
  }
  client.csrf = login.data?.csrfToken ?? client.csrf;
  const limit = Number(WORKFLOW_ENV.RICCON_WORKFLOW_TENANT_CONCURRENCY);
  const attempts = limit + 2;
  const startedAt = Date.now();
  const responses = await Promise.all(
    Array.from({ length: attempts }, (_, index) =>
      client.request("POST", `/api/v1/workflows/${fixture.workflowId}/runs`, {
        json: { inputs: { topic: `并发上限探针 ${index}` } },
        headers: { "Idempotency-Key": `perf-m06-limit-${index}-${Date.now()}` },
      }),
    ),
  );
  const accepted = responses.filter((response) => response.status === 201).length;
  const rejected = responses
    .filter((response) => response.status === 429)
    .map((response) => response.data?.code ?? "missing-code");
  const result = {
    configuredTenantConcurrency: limit,
    attempts,
    accepted,
    rejectedCode: rejected,
    durationMs: Date.now() - startedAt,
  };
  console.log(`[real-stack] M06 concurrency probe ${JSON.stringify(result)}`);
  // 等待探针运行结束，避免占用后续 k6 的并发槽位。
  const deadline = Date.now() + 120_000;
  while (Date.now() < deadline) {
    const active = runSql(
      `SELECT COUNT(*) AS active FROM workflow_run WHERE tenant_id='tenant-alpha' AND status IN ('PENDING','RUNNING');`,
      { repoRoot },
    ).stdout.trim().split("\n").pop();
    if (Number(active) === 0) {
      break;
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  if (rejected.length === 0) {
    throw new Error(
      `M06 concurrency limit probe did not observe RICCON-WF-429 at limit=${limit}: ${JSON.stringify(result)}`,
    );
  }
  return result;
}

/**
 * 读取 k6 JSON 报告中的关键指标（count/p95/rate）。
 *
 * @param {string} reportFile 报告路径
 * @returns {object} 指标摘要
 */
function summarizeWorkflowK6Report(reportFile) {
  if (!existsSync(reportFile)) {
    return { error: "missing report" };
  }
  const report = JSON.parse(readFileSync(reportFile, "utf8"));
  const metric = (name) => {
    const value = report.metrics?.[name]?.values;
    if (!value) {
      return null;
    }
    return {
      count: value.count ?? null,
      p95: value["p(95)"] ?? null,
      rate: value.rate ?? null,
      value: value.value ?? null,
    };
  };
  return {
    m06_j1_first_token_ms: metric("m06_j1_first_token_ms"),
    m06_api_ms: metric("m06_api_ms"),
    m06_run_admission_ms: metric("m06_run_admission_ms"),
    m06_run_ok: metric("m06_run_ok"),
    m06_run_rejected: metric("m06_run_rejected"),
    m06_run_completed_ok: metric("m06_run_completed_ok"),
    m06_j2_ok: metric("m06_j2_ok"),
    m06_j2_duration_ms: metric("m06_j2_duration_ms"),
  };
}

/**
 * 运行 PERF-M06-001 k6 场景并核对数据库不变量。
 *
 * @param {object} context 上下文
 * @param {object} fixture 流程夹具
 * @returns {{run: object, invariants: object}} 结果
 */
function runWorkflowPerfK6(context, fixture) {
  const since = new Date().toISOString().slice(0, 19).replace("T", " ");
  const reportFile = path.join(evidenceDir, "perf-m06-workflow.json");
  const logFile = path.join(evidenceDir, "perf-m06-workflow.log");
  const duration = process.env.REAL_PERF_DURATION ?? "45s";
  const envList = {
    PERF_M04_ENV_JSON: JSON.stringify(context.envSnapshot),
    PERF_M04_BASE_URL: `http://127.0.0.1:${API_PORT}`,
    PERF_M04_WORKSPACE_ID: context.workspaceId,
    PERF_M04_SCENARIO: "workflow",
    PERF_M04_DURATION: duration,
    PERF_M06_WORKFLOW_ID: fixture.workflowId,
    PERF_M06_ADMIN_WORKFLOW_ID: fixture.adminWorkflowId,
    PERF_M06_WORKFLOW_NAME: fixture.name,
    PERF_M06_MCP_TOOL_ID: context.workflowMcpToolId ?? "",
    PERF_M06_REPORT_FILE: reportFile,
    PERF_M06_J1_VUS: process.env.PERF_M06_J1_VUS ?? "25",
    PERF_M06_J2_VUS: process.env.PERF_M06_J2_VUS ?? "3",
    PERF_M06_STUDIO_VUS: process.env.PERF_M06_STUDIO_VUS ?? "22",
  };
  const args = ["run"];
  for (const [key, value] of Object.entries(envList)) {
    args.push("-e", `${key}=${value}`);
  }
  args.push("e2e/perf/mvc-smoke.js");
  const result = spawnSync("k6", args, {
    cwd: repoRoot,
    env: { ...process.env, ...context.modelKeysEnv, ...envList },
    encoding: "utf8",
    maxBuffer: 64 * 1024 * 1024,
  });
  writeFileSync(logFile, `${result.stdout ?? ""}\n${result.stderr ?? ""}`);
  console.log(
    `[real-stack] k6 workflow exit=${result.status} report=${path.relative(repoRoot, reportFile)}`,
  );

  // k6 结束后等待队列清空，再核对无状态丢失、无重复成功节点、无自批。
  const deadline = Date.now() + 240_000;
  let active = -1;
  while (Date.now() < deadline) {
    const value = runSql(
      `SELECT COUNT(*) FROM workflow_run WHERE tenant_id='tenant-alpha' AND created_at >= ${sqlString(since)} AND status IN ('PENDING','RUNNING');`,
      { repoRoot },
    ).stdout.trim().split("\n").pop();
    active = Number(value);
    if (active === 0) {
      break;
    }
    sleepSync(500);
  }
  const queryCount = (sql) => {
    const output = runSql(sql, { repoRoot }).stdout.trim().split("\n").pop();
    return Number(output);
  };
  const created = queryCount(
    `SELECT COUNT(*) FROM workflow_run WHERE tenant_id='tenant-alpha' AND created_at >= ${sqlString(since)};`,
  );
  const succeeded = queryCount(
    `SELECT COUNT(*) FROM workflow_run WHERE tenant_id='tenant-alpha' AND created_at >= ${sqlString(since)} AND status='SUCCEEDED';`,
  );
  const failedCount = queryCount(
    `SELECT COUNT(*) FROM workflow_run WHERE tenant_id='tenant-alpha' AND created_at >= ${sqlString(since)} AND status='FAILED';`,
  );
  const duplicateSuccessNodes = queryCount(`
    SELECT COUNT(*) FROM (
      SELECT nr.workflow_run_id, nr.node_key
        FROM workflow_node_run nr
        JOIN workflow_run r ON r.tenant_id=nr.tenant_id AND r.id=nr.workflow_run_id
       WHERE r.tenant_id='tenant-alpha' AND r.created_at >= ${sqlString(since)}
         AND nr.status='SUCCEEDED'
       GROUP BY nr.workflow_run_id, nr.node_key
      HAVING COUNT(*) > 1
    ) duplicates;`);
  const selfApprovals = queryCount(`
    SELECT COUNT(*) FROM workflow_approval
     WHERE tenant_id='tenant-alpha' AND decided_by IS NOT NULL AND decided_by = requested_by;`);
  const approvalRunMismatch = queryCount(`
    SELECT COUNT(*) FROM workflow_approval a
      JOIN workflow_run r ON r.tenant_id=a.tenant_id AND r.id=a.workflow_run_id
     WHERE a.tenant_id='tenant-alpha' AND a.workflow_id <> r.workflow_id;`);
  const resultOk = created > 0 && succeeded > 0;
  const invariants = {
    ok:
      result.status === 0 &&
      active === 0 &&
      created > 0 &&
      succeeded > 0 &&
      failedCount === 0 &&
      duplicateSuccessNodes === 0 &&
      selfApprovals === 0 &&
      approvalRunMismatch === 0,
    k6ExitCode: result.status,
    duration,
    createdRuns: created,
    succeededRuns: succeeded,
    failedRuns: failedCount,
    nonTerminalRuns: active,
    duplicateSuccessfulNodes: duplicateSuccessNodes,
    selfApprovals,
    approvalRunMismatch,
    metrics: summarizeWorkflowK6Report(reportFile),
    fakeModel: 1,
    limits: workflowEnvSnapshot(),
    report: path.relative(repoRoot, reportFile),
    log: path.relative(repoRoot, logFile),
  };
  console.log(`[real-stack] PERF-M06 invariants ${JSON.stringify(invariants)}`);
  return {
    run: {
      scenario: "workflow",
      failureMode: null,
      status: result.status,
      report: path.relative(repoRoot, reportFile),
      log: path.relative(repoRoot, logFile),
    },
    invariants,
  };
}

/**
 * 轻量 HTTP 客户端：只在知识库准备阶段使用 fetch；不引入额外依赖。
 *
 * 与 k6 一样显式维护 Cookie/CSRF，避免 Node fetch 的 cookie 行为差异。
 */
class KnowledgePerfClient {
  /**
   * @param {string} baseUrl API 根地址
   */
  constructor(baseUrl) {
    this.baseUrl = baseUrl;
    this.cookie = "";
    this.csrf = "";
  }

  /**
   * 发送请求并捕获 Set-Cookie。
   *
   * @param {string} method HTTP 方法
   * @param {string} path 路径
   * @param {{json?: object, form?: FormData, headers?: Record<string,string>}} [options] 选项
   * @returns {Promise<{status:number, data:any, text:string}>} 响应
   */
  async request(method, path, options = {}) {
    const headers = { ...(options.headers ?? {}) };
    if (this.cookie) {
      headers.cookie = this.cookie;
    }
    if (this.csrf && !["GET", "HEAD"].includes(method)) {
      headers["x-csrf-token"] = this.csrf;
    }
    let body;
    if (options.form) {
      body = options.form;
    } else if (options.json !== undefined) {
      headers["content-type"] = "application/json";
      body = JSON.stringify(options.json);
    }
    const response = await fetch(`${this.baseUrl}${path}`, {
      method,
      headers,
      body,
    });
    const setCookies = response.headers.getSetCookie?.() ?? [];
    if (setCookies.length > 0) {
      this.cookie = setCookies.map((value) => value.split(";")[0]).join("; ");
    }
    const text = await response.text();
    let data = null;
    try {
      data = text ? JSON.parse(text) : null;
    } catch {
      data = null;
    }
    return { status: response.status, data, text };
  }

  /**
   * 登录并保存会话与 CSRF。
   *
   * @param {string} tenantCode 租户代码
   * @param {string} username 用户名
   * @param {string} password 密码
   * @returns {Promise<void>}
   */
  async login(tenantCode, username, password) {
    const csrfResponse = await this.request("GET", "/api/v1/auth/csrf");
    if (csrfResponse.status !== 200) {
      throw new Error(
        `csrf failed: ${csrfResponse.status} ${csrfResponse.text}`,
      );
    }
    const anonymousToken = csrfResponse.data?.token;
    const loginResponse = await this.request("POST", "/api/v1/auth/login", {
      json: { tenantCode, username, password },
      headers: { "x-csrf-token": anonymousToken },
    });
    if (loginResponse.status !== 200) {
      throw new Error(
        `login failed for ${tenantCode}/${username}: ${loginResponse.status} ${loginResponse.text}`,
      );
    }
    this.csrf = loginResponse.data?.csrfToken ?? anonymousToken;
  }

  /**
   * GET JSON。
   *
   * @param {string} path 路径
   * @returns {Promise<{status:number, data:any, text:string}>} 响应
   */
  get(path) {
    return this.request("GET", path);
  }

  /**
   * POST JSON。
   *
   * @param {string} path 路径
   * @param {object} json JSON 体
   * @returns {Promise<{status:number, data:any, text:string}>} 响应
   */
  post(path, json) {
    return this.request("POST", path, { json });
  }

  /**
   * 解析当前账号的可用工作空间。
   *
   * @returns {Promise<string>} 工作空间 ID
   */
  async workspaceId() {
    const response = await this.get("/api/v1/workspaces?page=1&size=100");
    if (response.status !== 200) {
      throw new Error(`list workspaces failed: ${response.status}`);
    }
    const workspace = (response.data?.items ?? []).find(
      (item) => item.status === "ACTIVE",
    );
    if (!workspace) {
      throw new Error("no active workspace for knowledge perf account");
    }
    return workspace.id;
  }

  /**
   * 上传文件到工作空间。
   *
   * @param {string} workspace 工作空间 ID
   * @param {string} relativePath 仓库内文件路径
   * @param {string} filename 上传文件名
   * @param {string} mimeType MIME
   * @returns {Promise<string>} 文件 ID
   */
  async upload(workspace, relativePath, filename, mimeType) {
    const content = readFileSync(path.join(repoRoot, relativePath));
    const form = new FormData();
    form.append("file", new Blob([content], { type: mimeType }), filename);
    const response = await this.request(
      "POST",
      `/api/v1/workspaces/${workspace}/files`,
      { form },
    );
    if (response.status !== 201) {
      throw new Error(
        `upload ${filename} failed: ${response.status} ${response.text}`,
      );
    }
    return response.data.id;
  }

  /**
   * 轮询文件解析状态。
   *
   * @param {string} fileId 文件 ID
   * @param {number} [timeoutMs] 超时
   * @returns {Promise<number>} 从开始轮询到 SUCCEEDED 的毫秒数
   */
  async waitFileParsed(fileId, timeoutMs = 120_000) {
    const startedAt = Date.now();
    while (Date.now() - startedAt < timeoutMs) {
      const response = await this.get(`/api/v1/files/${fileId}`);
      if (response.status !== 200) {
        throw new Error(`read file ${fileId} failed: ${response.status}`);
      }
      const status = response.data?.parse?.status;
      if (status === "SUCCEEDED") {
        return Date.now() - startedAt;
      }
      if (status === "FAILED") {
        throw new Error(
          `file parse failed: ${fileId} ${JSON.stringify(response.data?.parse)}`,
        );
      }
      await new Promise((resolve) => setTimeout(resolve, 400));
    }
    throw new Error(`file parse timeout: ${fileId}`);
  }

  /**
   * 登记知识库文档并等待 INDEXED。
   *
   * @param {string} title 标题
   * @param {string} fileId 来源文件 ID
   * @param {{visibility?: string, userIds?: string[], departmentId?: string}} [acl] 可见范围
   * @param {number} [timeoutMs] 超时
   * @returns {Promise<{documentId: string, indexedMs: number, document: object}>} 结果
   */
  async waitIndexed(title, fileId, acl = {}, timeoutMs = 180_000) {
    const startedAt = Date.now();
    const created = await this.post("/api/v1/knowledge/documents", {
      title,
      fileId,
      visibility: acl.visibility ?? "TENANT",
      departmentId: acl.departmentId ?? null,
      userIds: acl.userIds ?? null,
    });
    if (created.status !== 202 && created.status !== 201) {
      throw new Error(
        `create knowledge doc failed: ${created.status} ${created.text}`,
      );
    }
    const documentId = created.data.id;
    while (Date.now() - startedAt < timeoutMs) {
      const response = await this.get(
        `/api/v1/knowledge/documents/${documentId}`,
      );
      if (response.status !== 200) {
        throw new Error(`read knowledge doc failed: ${response.status}`);
      }
      const status = response.data?.status;
      if (status === "INDEXED") {
        return {
          documentId,
          indexedMs: Date.now() - startedAt,
          document: response.data,
        };
      }
      if (status === "FAILED") {
        throw new Error(
          `knowledge index failed: ${documentId} ${response.data?.errorCode} ${response.data?.errorMessage}`,
        );
      }
      await new Promise((resolve) => setTimeout(resolve, 400));
    }
    throw new Error(`knowledge index timeout: ${documentId}`);
  }
}

/**
 * 读取 M05b 连接器 perf 配置；未显式开启时返回 `{enabled:false}`，不影响既有 knowledge 场景。
 *
 * @returns {object} 配置
 */
function connectorPerfConfig() {
  if (process.env.PERF_M05_CONNECTOR !== "1") {
    return { enabled: false };
  }
  const objects = Number(
    process.env.PERF_M05_CONNECTOR_OBJECTS ?? CONNECTOR_DEFAULTS.objects,
  );
  if (!Number.isInteger(objects) || objects < 1 || objects > 5_000) {
    throw new Error(
      `PERF_M05_CONNECTOR_OBJECTS invalid: ${process.env.PERF_M05_CONNECTOR_OBJECTS} (1-5000)`,
    );
  }
  const prefixRaw =
    process.env.PERF_M05_CONNECTOR_PREFIX ?? CONNECTOR_DEFAULTS.prefix;
  const prefix =
    prefixRaw === "" || prefixRaw.endsWith("/") ? prefixRaw : `${prefixRaw}/`;
  const endpointId =
    process.env.PERF_M05_CONNECTOR_ENDPOINT_ID ?? CONNECTOR_DEFAULTS.endpointId;
  if (!/^[a-z0-9][a-z0-9._-]{0,63}$/.test(endpointId)) {
    throw new Error(`PERF_M05_CONNECTOR_ENDPOINT_ID invalid: ${endpointId}`);
  }
  return {
    enabled: true,
    objects,
    bucket: process.env.PERF_M05_CONNECTOR_BUCKET ?? CONNECTOR_DEFAULTS.bucket,
    prefix,
    endpointId,
    endpointUrl: `http://${process.env.PERF_M05_CONNECTOR_S3_HOST ?? CONNECTOR_DEFAULTS.host}`,
    host: process.env.PERF_M05_CONNECTOR_S3_HOST ?? CONNECTOR_DEFAULTS.host,
    region:
      process.env.PERF_M05_CONNECTOR_S3_REGION ?? CONNECTOR_DEFAULTS.region,
    accessKey:
      process.env.PERF_M05_CONNECTOR_S3_ACCESS_KEY ??
      CONNECTOR_DEFAULTS.accessKey,
    secretKey:
      process.env.PERF_M05_CONNECTOR_S3_SECRET_KEY ??
      CONNECTOR_DEFAULTS.secretKey,
    connectorName:
      process.env.PERF_M05_CONNECTOR_NAME ?? CONNECTOR_DEFAULTS.connectorName,
    uploadConcurrency: Number(
      process.env.PERF_M05_CONNECTOR_UPLOAD_CONCURRENCY ??
        CONNECTOR_DEFAULTS.uploadConcurrency,
    ),
    token: process.env.PERF_M05_CONNECTOR_TOKEN ?? CONNECTOR_DEFAULTS.token,
    syncBudgetMs: Number(
      process.env.PERF_M05_CONNECTOR_SYNC_BUDGET_MS ??
        CONNECTOR_DEFAULTS.syncBudgetMs,
    ),
    queueWaitBudgetMs: Number(
      process.env.PERF_M05_CONNECTOR_QUEUE_BUDGET_MS ??
        CONNECTOR_DEFAULTS.queueWaitBudgetMs,
    ),
    syncTimeoutMs: Number(
      process.env.PERF_M05_CONNECTOR_SYNC_TIMEOUT_MS ?? 20 * 60_000,
    ),
    indexTimeoutMs: Number(
      process.env.PERF_M05_CONNECTOR_INDEX_TIMEOUT_MS ?? 20 * 60_000,
    ),
    cleanup: process.env.PERF_M05_CONNECTOR_CLEANUP !== "0",
  };
}

/**
 * 返回可写入报告的连接器配置（不含凭据）。
 *
 * @param {object} config 连接器配置
 * @returns {object} 脱敏配置
 */
function publicConnectorConfig(config) {
  return {
    objects: config.objects,
    bucket: config.bucket,
    prefix: config.prefix,
    endpointId: config.endpointId,
    connectorName: config.connectorName,
    syncBudgetMs: config.syncBudgetMs,
    queueWaitBudgetMs: config.queueWaitBudgetMs,
    cleanup: config.cleanup,
  };
}

/**
 * 构造源桶对象 key 列表。
 *
 * @param {object} config 连接器配置
 * @returns {string[]} key 列表
 */
function connectorObjectKeys(config) {
  return Array.from(
    { length: config.objects },
    (_, index) =>
      `${config.prefix}doc-${String(index + 1).padStart(4, "0")}.txt`,
  );
}

/**
 * 生成连接器源对象内容；每个对象唯一，避免把内容去重误当同步失败。
 *
 * @param {number} index 序号（从 1 开始）
 * @param {object} config 连接器配置
 * @returns {string} 文本内容
 */
function connectorObjectBody(index, config) {
  return [
    `PERF-M05b connector sync object ${index}`,
    `${config.token} 协同办公 市场调研 数据`,
    `fixture=${config.bucket}/${config.prefix}`,
    "",
  ].join("\n");
}

/**
 * 以有限并发执行异步任务；顺序与输入一致。
 *
 * @param {Array} items 输入
 * @param {number} limit 并发上限
 * @param {function(any, number): Promise<any>} worker 任务
 * @returns {Promise<Array>} 结果
 */
async function mapWithConcurrency(items, limit, worker) {
  const results = new Array(items.length);
  let cursor = 0;
  const runners = Array.from(
    { length: Math.max(1, Math.min(limit, items.length)) },
    async () => {
      while (true) {
        const index = cursor;
        cursor += 1;
        if (index >= items.length) {
          return;
        }
        results[index] = await worker(items[index], index);
      }
    },
  );
  await Promise.all(runners);
  return results;
}

/**
 * 构造源桶 1000 个小对象；失败立即抛出，避免同步阶段拿到残缺数据集。
 *
 * @param {object} config 连接器配置
 * @returns {Promise<object>} 上传摘要
 */
async function seedConnectorSourceBucket(config) {
  const startedAt = Date.now();
  await createS3Bucket(config.bucket, {
    host: config.host,
    region: config.region,
    accessKey: config.accessKey,
    secretKey: config.secretKey,
  });
  const keys = connectorObjectKeys(config);
  const failures = [];
  let uploaded = 0;
  let bytes = 0;
  await mapWithConcurrency(
    keys,
    config.uploadConcurrency,
    async (key, index) => {
      const body = connectorObjectBody(index + 1, config);
      const response = await s3RequestWithRetry({
        method: "PUT",
        bucket: config.bucket,
        key,
        body,
        host: config.host,
        region: config.region,
        accessKey: config.accessKey,
        secretKey: config.secretKey,
      });
      if (![200, 201].includes(response.status)) {
        const detail = truncateText(await response.text().catch(() => ""), 200);
        failures.push(`${key}:${response.status}:${detail}`);
        return;
      }
      uploaded += 1;
      bytes += Buffer.byteLength(body);
    },
  );
  if (failures.length > 0) {
    throw new Error(
      `connector source object upload failed (${failures.length}/${keys.length}): ${failures.slice(0, 5).join(",")}`,
    );
  }
  return {
    bucket: config.bucket,
    prefix: config.prefix,
    objects: keys.length,
    uploaded,
    bytes,
    elapsedMs: Date.now() - startedAt,
    token: config.token,
  };
}

/**
 * 清理源桶对象与桶；只在上传成功且同步已结束时调用。
 *
 * @param {object} config 连接器配置
 * @returns {Promise<object>} 清理结果
 */
async function cleanupConnectorSourceBucket(config) {
  if (!config.cleanup) {
    return { skipped: true, reason: "PERF_M05_CONNECTOR_CLEANUP=0" };
  }
  const startedAt = Date.now();
  const keys = connectorObjectKeys(config);
  const statuses = await mapWithConcurrency(
    keys,
    config.uploadConcurrency,
    async (key) => {
      const response = await s3RequestWithRetry({
        method: "DELETE",
        bucket: config.bucket,
        key,
        host: config.host,
        region: config.region,
        accessKey: config.accessKey,
        secretKey: config.secretKey,
      });
      return response.status;
    },
  );
  const failed = statuses.filter((status) => ![200, 204, 404].includes(status));
  const bucketResponse = await s3RequestWithRetry({
    method: "DELETE",
    bucket: config.bucket,
    host: config.host,
    region: config.region,
    accessKey: config.accessKey,
    secretKey: config.secretKey,
  });
  return {
    skipped: false,
    attempted: keys.length,
    failed: failed.length,
    bucketStatus: bucketResponse.status,
    elapsedMs: Date.now() - startedAt,
  };
}

/**
 * 通过 API 创建连接器并启用；创建请求显式带 `defaultVisibility` 与凭据。
 *
 * @param {KnowledgePerfClient} admin 管理员会话
 * @param {object} config 连接器配置
 * @returns {Promise<object>} 连接器与测试结果
 */
async function createConnectorFixture(admin, config) {
  const created = await admin.post("/api/v1/knowledge/connectors", {
    name: config.connectorName,
    type: "S3",
    endpointId: config.endpointId,
    config: {
      bucket: config.bucket,
      prefix: config.prefix,
      region: config.region,
      includeExtensions: ["txt"],
      excludeGlobs: [],
    },
    defaultVisibility: "TENANT",
    scheduleEnabled: false,
    credential: {
      accessKeyId: config.accessKey,
      secretAccessKey: config.secretKey,
    },
  });
  if (created.status !== 201) {
    throw new Error(
      `create connector failed: ${created.status} ${truncateText(created.text)}`,
    );
  }
  const connectorId = created.data?.id;
  if (!connectorId) {
    throw new Error("create connector response missing id");
  }
  const activated = await admin.request(
    "PATCH",
    `/api/v1/knowledge/connectors/${connectorId}`,
    { json: { status: "ACTIVE" } },
  );
  if (activated.status !== 200) {
    throw new Error(
      `activate connector failed: ${activated.status} ${truncateText(activated.text)}`,
    );
  }
  const tested = await admin.post(
    `/api/v1/knowledge/connectors/${connectorId}/test`,
    {},
  );
  if (tested.status !== 200) {
    throw new Error(
      `connector test failed: ${tested.status} ${truncateText(tested.text)}`,
    );
  }
  if (tested.data?.success !== true) {
    throw new Error(
      `connector test unsuccessful: category=${tested.data?.category} message=${truncateText(tested.data?.message)}`,
    );
  }
  return {
    id: connectorId,
    created: created.data,
    test: tested.data,
  };
}

/**
 * 触发手动同步；返回 202 的 syncId 与受理时间。
 *
 * @param {KnowledgePerfClient} admin 管理员会话
 * @param {string} connectorId 连接器 ID
 * @returns {Promise<{syncId: string, acceptedAt: string, triggeredAtMs: number}>} 受理结果
 */
async function triggerConnectorSync(admin, connectorId) {
  const triggeredAtMs = Date.now();
  const response = await admin.post(
    `/api/v1/knowledge/connectors/${connectorId}/sync`,
    {},
  );
  if (response.status !== 202) {
    throw new Error(
      `trigger connector sync failed: ${response.status} ${truncateText(response.text)}`,
    );
  }
  if (!response.data?.syncId) {
    throw new Error("connector sync response missing syncId");
  }
  return {
    syncId: response.data.syncId,
    acceptedAt: response.data.acceptedAt,
    triggeredAtMs,
  };
}

/**
 * 从连接器列表读取指定连接器；列表响应包含同步状态/计数，避免额外详情端点。
 *
 * @param {KnowledgePerfClient} admin 管理员会话
 * @param {string} connectorId 连接器 ID
 * @returns {Promise<object|null>} 连接器
 */
async function findConnectorById(admin, connectorId) {
  const response = await admin.get(
    "/api/v1/knowledge/connectors?page=1&size=100",
  );
  if (response.status !== 200) {
    throw new Error(`list connectors failed: ${response.status}`);
  }
  return (response.data?.items ?? []).find((item) => item.id === connectorId);
}

/**
 * 轮询同步终态；完成、失败或超时都返回最后一次快照，由调用方判定。
 *
 * @param {KnowledgePerfClient} admin 管理员会话
 * @param {string} connectorId 连接器 ID
 * @param {object} config 连接器配置
 * @returns {Promise<{connector: object|null, observedAt: string, timedOut: boolean}>} 终态
 */
async function pollConnectorSyncFinished(admin, connectorId, config) {
  const deadline = Date.now() + config.syncTimeoutMs;
  let connector = null;
  while (Date.now() < deadline) {
    connector = await findConnectorById(admin, connectorId);
    if (connector?.lastSyncFinishedAt) {
      return {
        connector,
        observedAt: new Date().toISOString(),
        timedOut: false,
      };
    }
    await new Promise((resolve) => setTimeout(resolve, 1_000));
  }
  return {
    connector,
    observedAt: new Date().toISOString(),
    timedOut: true,
  };
}

/**
 * 解析 MySQL batch 输出为对象数组。
 *
 * @param {string} stdout 输出
 * @returns {Array<Record<string,string>>} 行
 */
function readTabularRows(stdout) {
  const lines = String(stdout ?? "")
    .trim()
    .split("\n")
    .filter(Boolean);
  if (lines.length === 0) {
    return [];
  }
  const headers = lines[0].split("\t");
  return lines.slice(1).map((line) => {
    const cells = line.split("\t");
    const row = {};
    headers.forEach((header, index) => {
      row[header] = cells[index] ?? "";
    });
    return row;
  });
}

/**
 * 读取连接器条目状态计数。
 *
 * @param {string} connectorId 连接器 ID
 * @returns {object} 计数
 */
function readConnectorItemCounts(connectorId) {
  const rows = readTabularRows(
    runSql(
      `
SELECT status, COUNT(*) AS count
  FROM kb_connector_item
 WHERE tenant_id = 'tenant-alpha'
   AND connector_id = ${binaryUuidLiteral(connectorId)}
 GROUP BY status;`,
      { repoRoot },
    ).stdout,
  );
  const counts = {};
  for (const row of rows) {
    counts[row.status] = Number(row.count) || 0;
  }
  return counts;
}

/**
 * 读取连接器文档状态计数；轮询期间只查这一条，避免每秒扫描 1000 行索引耗时。
 *
 * @param {string} connectorId 连接器 ID
 * @returns {object} 计数
 */
function readConnectorDocumentCounts(connectorId) {
  const countsRow =
    readTabularRows(
      runSql(
        `
SELECT COUNT(*) AS total,
       COALESCE(SUM(status = 'INDEXED'), 0) AS indexed,
       COALESCE(SUM(status = 'FAILED'), 0) AS failed,
       COALESCE(SUM(status IN ('PENDING', 'INDEXING')), 0) AS pending
  FROM knowledge_document
 WHERE tenant_id = 'tenant-alpha'
   AND connector_id = ${binaryUuidLiteral(connectorId)}
   AND deleted_at IS NULL;`,
        { repoRoot },
      ).stdout,
    )[0] ?? {};
  return {
    total: Number(countsRow.total) || 0,
    indexed: Number(countsRow.indexed) || 0,
    failed: Number(countsRow.failed) || 0,
    pending: Number(countsRow.pending) || 0,
  };
}

/**
 * 读取连接器文档状态计数、索引耗时与命中夹具 token 的 chunk 数。
 *
 * @param {string} connectorId 连接器 ID
 * @param {string} token 夹具检索词
 * @returns {{counts: object, latency: object, chunksWithToken: number}} 索引结果
 */
function readConnectorDocumentResults(connectorId, token) {
  const counts = readConnectorDocumentCounts(connectorId);
  const latencyRows = readTabularRows(
    runSql(
      `
SELECT ROUND(TIMESTAMPDIFF(MICROSECOND, created_at, indexed_at) / 1000, 3) AS latency_ms
  FROM knowledge_document
 WHERE tenant_id = 'tenant-alpha'
   AND connector_id = ${binaryUuidLiteral(connectorId)}
   AND status = 'INDEXED'
   AND indexed_at IS NOT NULL;`,
      { repoRoot },
    ).stdout,
  );
  const latencySamples = latencyRows
    .map((row) => Number(row.latency_ms))
    .filter((value) => Number.isFinite(value));
  const chunkRow =
    readTabularRows(
      runSql(
        `
SELECT COUNT(*) AS chunks
  FROM knowledge_chunk c
  JOIN knowledge_document d
    ON d.tenant_id = c.tenant_id AND d.id = c.document_id
 WHERE c.tenant_id = 'tenant-alpha'
   AND d.connector_id = ${binaryUuidLiteral(connectorId)}
   AND c.content LIKE ${sqlString(`%${token}%`)};`,
        { repoRoot },
      ).stdout,
    )[0] ?? {};
  return {
    counts,
    latency: {
      samples: latencySamples.length,
      p50Ms: percentile(latencySamples, 50),
      p95Ms: percentile(latencySamples, 95),
      maxMs: latencySamples.length > 0 ? Math.max(...latencySamples) : null,
    },
    chunksWithToken: Number(chunkRow.chunks) || 0,
  };
}

/**
 * 轮询索引终态；按文档状态收敛，不把“同步完成”误当“索引完成”。
 *
 * @param {string} connectorId 连接器 ID
 * @param {object} config 连接器配置
 * @returns {Promise<object>} 索引结果
 */
async function waitConnectorDocumentsIndexed(connectorId, config) {
  const startedAt = Date.now();
  const deadline = startedAt + config.indexTimeoutMs;
  let counts = readConnectorDocumentCounts(connectorId);
  while (Date.now() < deadline && counts.pending > 0) {
    await new Promise((resolve) => setTimeout(resolve, 1_000));
    counts = readConnectorDocumentCounts(connectorId);
  }
  const result = readConnectorDocumentResults(connectorId, config.token);
  return {
    ...result,
    counts,
    elapsedMs: Date.now() - startedAt,
    timedOut: counts.pending > 0,
  };
}

/**
 * 汇总毫秒样本。
 *
 * @param {number[]} samples 样本
 * @returns {object} 汇总
 */
function summarizeMsSamples(samples) {
  return {
    samples: samples.length,
    p50Ms: percentile(samples, 50),
    p95Ms: percentile(samples, 95),
    maxMs: samples.length > 0 ? Math.max(...samples) : null,
    avgMs:
      samples.length > 0
        ? Number(
            (
              samples.reduce((sum, value) => sum + value, 0) / samples.length
            ).toFixed(3),
          )
        : null,
  };
}

/**
 * 从 agent_run 的 created_at→started_at 计算 heavy 通道队列等待。
 *
 * <p>事件入队时写 `agent_run.created_at`，领取后写 `started_at`；两者差值就是排队时间。
 * 索引事件通过 outbox payload 的 documentId 关联到连接器文档，避免把同时段上传积压混入。
 *
 * @param {string} connectorId 连接器 ID
 * @param {object} config 连接器配置
 * @returns {object} 队列等待
 */
function readConnectorQueueWait(connectorId, config) {
  const syncRows = readTabularRows(
    runSql(
      `
SELECT ROUND(TIMESTAMPDIFF(MICROSECOND, r.created_at, r.started_at) / 1000, 3) AS wait_ms
  FROM agent_run r
  JOIN agent_run_outbox o
    ON o.tenant_id = r.tenant_id AND o.run_id = r.id
 WHERE r.tenant_id = 'tenant-alpha'
   AND r.started_at IS NOT NULL
   AND o.event_type = 'riccon.knowledge.connector.sync.requested.v1'
   AND o.idempotency_key LIKE ${sqlString(`connector:${connectorId}:sync:%`)};`,
      { repoRoot },
    ).stdout,
  );
  const indexRows = readTabularRows(
    runSql(
      `
SELECT ROUND(TIMESTAMPDIFF(MICROSECOND, r.created_at, r.started_at) / 1000, 3) AS wait_ms
  FROM agent_run r
  JOIN agent_run_outbox o
    ON o.tenant_id = r.tenant_id AND o.run_id = r.id
  JOIN knowledge_document d
    ON d.tenant_id = r.tenant_id
   AND d.id = UNHEX(REPLACE(JSON_UNQUOTE(JSON_EXTRACT(o.payload, '$.documentId')), '-', ''))
 WHERE r.tenant_id = 'tenant-alpha'
   AND r.started_at IS NOT NULL
   AND o.event_type = 'riccon.knowledge.index.requested.v1'
   AND d.connector_id = ${binaryUuidLiteral(connectorId)};`,
      { repoRoot },
    ).stdout,
  );
  const syncSamples = syncRows
    .map((row) => Number(row.wait_ms))
    .filter((value) => Number.isFinite(value));
  const indexSamples = indexRows
    .map((row) => Number(row.wait_ms))
    .filter((value) => Number.isFinite(value));
  const combined = [...syncSamples, ...indexSamples];
  const summary = summarizeMsSamples(combined);
  return {
    source:
      "agent_run.created_at（事件入队）→ started_at（heavy 通道领取）；sync 事件 + 连接器文档索引事件",
    sync: summarizeMsSamples(syncSamples),
    index: summarizeMsSamples(indexSamples),
    combined: summary,
    budgetMs: config.queueWaitBudgetMs,
    withinBudget:
      summary.p95Ms !== null && summary.p95Ms <= config.queueWaitBudgetMs,
  };
}

/**
 * 执行 M05b 连接器同步分支：源桶种子 → 连接器/凭据 → 手动同步 → 索引收敛 → 清理。
 *
 * @param {KnowledgePerfClient} admin 管理员会话
 * @param {object} config 连接器配置
 * @returns {Promise<object>} 分支结果
 */
async function runConnectorPerfBranch(admin, config) {
  const result = {
    enabled: true,
    status: "failed",
    config: publicConnectorConfig(config),
    source: null,
    connector: null,
    trigger: null,
    sync: null,
    items: null,
    indexing: null,
    queueWait: null,
    cleanup: null,
    error: null,
    stage: null,
  };
  let syncTerminal = false;
  try {
    result.stage = "source";
    result.source = await seedConnectorSourceBucket(config);
    result.stage = "createConnector";
    const fixture = await createConnectorFixture(admin, config);
    result.connector = fixture;
    result.stage = "triggerSync";
    const trigger = await triggerConnectorSync(admin, fixture.id);
    result.trigger = trigger;

    result.stage = "waitSync";
    const polled = await pollConnectorSyncFinished(admin, fixture.id, config);
    syncTerminal = !polled.timedOut;
    if (polled.timedOut) {
      throw new Error(
        `connector sync timeout after ${config.syncTimeoutMs}ms; lastStatus=${polled.connector?.lastSyncStatus ?? "UNKNOWN"}`,
      );
    }
    const connector = polled.connector;
    const syncStartedMs = Date.parse(connector.lastSyncStartedAt ?? "");
    const syncFinishedMs = Date.parse(connector.lastSyncFinishedAt ?? "");
    const acceptedMs = Date.parse(trigger.acceptedAt ?? "");
    const processingMs =
      Number.isFinite(syncStartedMs) && Number.isFinite(syncFinishedMs)
        ? syncFinishedMs - syncStartedMs
        : null;
    const endToEndMs = Number.isFinite(syncFinishedMs)
      ? syncFinishedMs - trigger.triggeredAtMs
      : null;
    const acceptedToFinishedMs =
      Number.isFinite(acceptedMs) && Number.isFinite(syncFinishedMs)
        ? syncFinishedMs - acceptedMs
        : null;
    result.sync = {
      syncId: trigger.syncId,
      status: connector.lastSyncStatus,
      complete: connector.lastSyncComplete,
      summary: connector.lastSyncSummary ?? null,
      lastSyncStartedAt: connector.lastSyncStartedAt,
      lastSyncFinishedAt: connector.lastSyncFinishedAt,
      acceptedToFinishedMs,
      processingMs,
      endToEndMs,
      budgetMs: config.syncBudgetMs,
      withinBudget:
        processingMs !== null && processingMs <= config.syncBudgetMs,
      endToEndWithinBudget:
        endToEndMs !== null && endToEndMs <= config.syncBudgetMs,
    };
    result.items = readConnectorItemCounts(fixture.id);
    result.stage = "indexing";
    const indexed = await waitConnectorDocumentsIndexed(fixture.id, config);
    result.indexing = {
      counts: indexed.counts,
      latency: indexed.latency,
      chunksWithToken: indexed.chunksWithToken,
      elapsedMs: indexed.elapsedMs,
      timedOut: indexed.timedOut,
    };
    result.stage = "queueWait";
    result.queueWait = readConnectorQueueWait(fixture.id, config);
    result.stage = "done";
  } catch (error) {
    result.error = `${result.stage ?? "unknown"}: ${truncateText(error, 900)}`;
  } finally {
    // 未触发同步（夹具/连接器创建失败）时源桶可安全清理；已触发但未到终态时保留，
    // 避免 worker 仍在读取时删除源对象。
    if (!result.trigger || syncTerminal) {
      try {
        result.cleanup = await cleanupConnectorSourceBucket(config);
      } catch (error) {
        result.cleanup = { skipped: false, error: truncateText(error, 300) };
      }
    } else {
      result.cleanup = {
        skipped: true,
        reason:
          "connector sync did not reach terminal state; source bucket kept for diagnosis",
      };
    }
  }

  const summary = result.sync?.summary;
  const countsOk =
    summary !== null &&
    summary !== undefined &&
    summary.scanned === config.objects &&
    summary.created === config.objects &&
    summary.updated === 0 &&
    summary.skipped === 0 &&
    summary.failed === 0 &&
    summary.deleted === 0 &&
    summary.complete === true;
  const syncOk =
    result.sync !== null &&
    result.sync.status !== "ERROR" &&
    result.sync.withinBudget === true &&
    countsOk;
  const indexingOk =
    result.indexing !== null &&
    result.indexing.counts.total === config.objects &&
    result.indexing.counts.indexed === config.objects &&
    result.indexing.counts.failed === 0 &&
    result.indexing.counts.pending === 0 &&
    result.indexing.timedOut === false &&
    result.indexing.chunksWithToken > 0;
  const queueOk = result.queueWait?.withinBudget === true;
  result.checks = { countsOk, syncOk, indexingOk, queueOk };
  result.status =
    result.error === null && syncOk && indexingOk && queueOk
      ? "passed"
      : "failed";
  // 计数/预算不达标但流程未抛异常时，也给出可读的失败原因，避免 failure 文件只有 checks。
  if (result.status !== "passed" && result.error === null) {
    result.error = `checks failed: ${JSON.stringify(result.checks)}`;
  }
  return result;
}

/**
 * 把 UUID 字符串转为 BINARY(16) 字面量。
 *
 * @param {string} value UUID
 * @returns {string} SQL 表达式
 */
function binaryUuidLiteral(value) {
  return `UNHEX('${value.replaceAll("-", "")}')`;
}

/**
 * 由整数生成稳定的 32 位十六进制 ID；避免随机 UUID 影响重跑对比。
 *
 * @param {number} value 整数
 * @returns {string} 32 位十六进制
 */
function deterministicHex(value) {
  return value.toString(16).padStart(32, "0");
}

/**
 * 把 32 位十六进制格式化为 UUID 字符串。
 *
 * @param {string} hex 32 位十六进制
 * @returns {string} UUID
 */
function uuidFromHex(hex) {
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/**
 * 构造知识库语料 SQL：部门、5000 文档、10 万 chunk 与 USERS ACL。
 *
 * 文档可见性分布：4400 TENANT + 500 DEPT + 50 USERS（成员可见）+ 50 USERS（仅管理员）。
 * 每 `matchEvery` 篇文档的全部 chunk 含检索词；默认 1%（1000 chunk）。V14 FULLTEXT
 * 使用 ngram 索引，与 ACL EXISTS 一起构成检索 p95 的测量口径。
 *
 * @param {object} input 参数
 * @param {string} input.adminUserId 管理员账号 ID
 * @param {string} input.memberUserId 成员账号 ID
 * @param {string} input.admin2UserId 第二管理员账号 ID
 * @param {string} input.departmentId 部门 ID
 * @param {number} input.documents 文档数
 * @param {number} input.chunksPerDocument 每文档 chunk 数
 * @param {string} input.queryToken 检索词
 * @param {number} input.matchEvery 含检索词的文档间隔
 * @returns {string} SQL
 */
function buildKnowledgeSeedSql(input) {
  const tenant = sqlString("tenant-alpha");
  const adminId = binaryUuidLiteral(input.adminUserId);
  const memberId = binaryUuidLiteral(input.memberUserId);
  const admin2Id = binaryUuidLiteral(input.admin2UserId);
  const departmentId = binaryUuidLiteral(input.departmentId);
  const statements = [];

  // 部门与成员归属：让 DEPT 分支真实生效；成员在部门内可见 DEPT 文档。
  statements.push(`
INSERT IGNORE INTO department (tenant_id, id, name, manager_user_id, status, created_by, created_at, updated_at)
VALUES (${tenant}, ${departmentId}, ${sqlString("perf-knowledge-dept")}, ${adminId}, 'ACTIVE', ${adminId}, NOW(6), NOW(6));`);
  statements.push(`
UPDATE app_user SET department_id = ${departmentId}
 WHERE tenant_id = ${tenant} AND username = ${sqlString("alpha-member")};`);

  const docIdHex = (index) => deterministicHex(2_000_000 + index);
  const chunkIdHex = (index) => deterministicHex(3_000_000 + index);
  const docId = (index) => `UNHEX('${docIdHex(index)}')`;

  /** 生成文档行；字段顺序与 INSERT 列一致。 */
  const documentRow = (index) => {
    let visibility = "TENANT";
    let department = "NULL";
    if (index > 4400 && index <= 4900) {
      visibility = "DEPT";
      department = departmentId;
    } else if (index > 4900) {
      visibility = "USERS";
    }
    const title = `知识库性能语料 文档 ${index}${index % input.matchEvery === 0 ? ` ${input.queryToken}` : ""}`;
    return `(${tenant}, ${docId(index)}, ${sqlString(title)}, 'SEED', NULL, SHA2(CONCAT('perf-knowledge-', ${index}), 256), 'text/plain', 1024, 'INDEXED', NOW(6), ${adminId}, NOW(6), NOW(6), '${visibility}', ${department}, NULL, 1, DATE_ADD(NOW(6), INTERVAL 180 DAY))`;
  };

  // 文档按 500 行一批，避免单条 SQL 过大。
  for (let start = 1; start <= input.documents; start += 500) {
    const end = Math.min(input.documents, start + 499);
    const rows = [];
    for (let index = start; index <= end; index += 1) {
      rows.push(documentRow(index));
    }
    statements.push(`
INSERT IGNORE INTO knowledge_document (
  tenant_id, id, title, source_type, file_id, checksum, mime_type, size_bytes, status,
  indexed_at, created_by, created_at, updated_at, visibility, department_id, parse_engine,
  acl_version, retention_expires_at
) VALUES
${rows.join(",\n")};`);
  }

  // 分块按 2000 行一批；chunk ID 用确定性值，便于重跑与核查。
  let chunkSequence = 0;
  for (let start = 1; start <= input.documents; start += 2000) {
    const end = Math.min(input.documents, start + 1999);
    const rows = [];
    for (let index = start; index <= end; index += 1) {
      const withTerm = index % input.matchEvery === 0;
      for (let ordinal = 1; ordinal <= input.chunksPerDocument; ordinal += 1) {
        chunkSequence += 1;
        const content = `PERF-M05 语料 chunk ${index}-${ordinal} ${withTerm ? `${input.queryToken} ` : ""}协同办公 市场调研 数据`;
        rows.push(
          `(${tenant}, UNHEX('${chunkIdHex(chunkSequence)}'), ${docId(index)}, ${ordinal}, 'PARAGRAPH', NULL, NULL, NULL, ${ordinal}, ${sqlString(content)}, 16, NOW(6))`,
        );
      }
    }
    statements.push(`
INSERT IGNORE INTO knowledge_chunk (
  tenant_id, id, document_id, ordinal, locator_type, page_no, sheet_name, cell_range,
  paragraph_no, content, token_count, created_at
) VALUES
${rows.join(",\n")};`);
  }

  // USERS ACL：4901–4950 授权成员与第二管理员；4951–5000 只授权管理员。
  const aclRows = [];
  for (let index = 4901; index <= 4950; index += 1) {
    aclRows.push(
      `(${tenant}, ${docId(index)}, ${memberId}, ${adminId}, NOW(6))`,
    );
    aclRows.push(
      `(${tenant}, ${docId(index)}, ${admin2Id}, ${adminId}, NOW(6))`,
    );
  }
  for (let index = 4951; index <= 5000; index += 1) {
    aclRows.push(
      `(${tenant}, ${docId(index)}, ${adminId}, ${adminId}, NOW(6))`,
    );
  }
  statements.push(`
INSERT IGNORE INTO knowledge_document_acl (tenant_id, document_id, user_id, granted_by, created_at)
VALUES
${aclRows.join(",\n")};`);

  return statements.join("\n");
}

/**
 * 造 M05 语料并返回 k6 需要的文档 ID。
 *
 * @param {object} seedState real-stack-seed.json
 * @returns {{dataset: object, ids: object}} 语料信息与 ID
 */
function seedKnowledgeDataset(seedState) {
  const documents = Number(
    process.env.PERF_M05_DOCS ?? KNOWLEDGE_DEFAULTS.documents,
  );
  const chunksPerDocument = Number(
    process.env.PERF_M05_CHUNKS_PER_DOC ?? KNOWLEDGE_DEFAULTS.chunksPerDocument,
  );
  if (!(documents >= 1000) || !(chunksPerDocument >= 1)) {
    throw new Error(
      `PERF_M05_DOCS/PERF_M05_CHUNKS_PER_DOC invalid: ${documents}/${chunksPerDocument}`,
    );
  }
  if (!(KNOWLEDGE_MATCH_EVERY >= 1)) {
    throw new Error(`PERF_M05_MATCH_EVERY invalid: ${KNOWLEDGE_MATCH_EVERY}`);
  }
  const departmentId = uuidFromHex(deterministicHex(1_900_000));
  const sql = buildKnowledgeSeedSql({
    adminUserId: seedState.userIds.alphaAdmin,
    memberUserId: seedState.userIds.alphaMember,
    admin2UserId: seedState.userIds.alphaAdmin2,
    departmentId,
    documents,
    chunksPerDocument,
    queryToken: KNOWLEDGE_QUERY,
    matchEvery: KNOWLEDGE_MATCH_EVERY,
  });
  const startedAt = Date.now();
  runSql(sql, { repoRoot });
  const seedMs = Date.now() - startedAt;
  const counts = runSql(
    `SELECT
       (SELECT COUNT(*) FROM knowledge_document WHERE tenant_id='tenant-alpha' AND title LIKE '知识库性能语料%') AS documents,
       (SELECT COUNT(*) FROM knowledge_chunk c JOIN knowledge_document d
          ON d.tenant_id=c.tenant_id AND d.id=c.document_id
         WHERE c.tenant_id='tenant-alpha' AND d.title LIKE '知识库性能语料%') AS chunks,
       (SELECT COUNT(*) FROM knowledge_chunk c JOIN knowledge_document d
          ON d.tenant_id=c.tenant_id AND d.id=c.document_id
         WHERE c.tenant_id='tenant-alpha' AND c.content LIKE ${sqlString(`%${KNOWLEDGE_QUERY}%`)}) AS matching_chunks;`,
    { repoRoot },
  ).stdout.trim();
  const matchDocuments = Math.ceil(documents / KNOWLEDGE_MATCH_EVERY);
  return {
    dataset: {
      documents,
      chunks: documents * chunksPerDocument,
      chunksPerDocument,
      matchEvery: KNOWLEDGE_MATCH_EVERY,
      matchDocuments,
      matchingChunks: matchDocuments * chunksPerDocument,
      departmentId,
      seedMs,
      countsRaw: counts,
    },
    ids: {
      visibleDocumentId: uuidFromHex(deterministicHex(2_000_001)),
      aclDocumentId: uuidFromHex(deterministicHex(2_004_901)),
      hiddenDocumentId: uuidFromHex(deterministicHex(2_004_951)),
    },
  };
}

/**
 * 启动一次 k6（异步），用于在运行期间并发提交索引积压。
 *
 * @param {object} context 上下文
 * @param {string} scenario k6 场景
 * @param {object} [extra] 额外 env
 * @returns {{done: Promise<object>, reportFile: string, logFile: string}} 句柄
 */
function launchK6(context, scenario, extra = {}) {
  const reportFile = path.join(evidenceDir, `perf-m05-${scenario}.json`);
  const logFile = path.join(evidenceDir, `perf-m05-${scenario}.log`);
  const envList = {
    PERF_M04_ENV_JSON: JSON.stringify(context.envSnapshot),
    PERF_M04_BASE_URL: `http://127.0.0.1:${API_PORT}`,
    PERF_M04_WORKSPACE_ID: context.workspaceId,
    PERF_M05_SCENARIO: scenario,
    PERF_M05_DURATION:
      process.env.PERF_M05_DURATION ?? KNOWLEDGE_DEFAULTS.duration,
    PERF_M05_THINK_TIME:
      process.env.PERF_M05_THINK_TIME ??
      String(KNOWLEDGE_DEFAULTS.thinkTimeSeconds),
    PERF_M05_REPORT_FILE: reportFile,
    // 父进程的 PERF_M05_CONNECTOR 只控制编排；k6 只有在 run.mjs 注入完整连接器元数据
    // （connectorId/syncId/对象数）时才允许启用分支。默认显式置 0，避免分支失败后
    // k6 继承父 env 继续要求缺失的 ID，把真实错误覆盖成“缺少 PERF_M05_CONNECTOR_ID”。
    PERF_M05_CONNECTOR: "0",
    PERF_M05_ACL_WRITES: process.env.PERF_M05_ACL_WRITES ?? "10",
    ...extra,
  };
  const args = ["run"];
  for (const [key, value] of Object.entries(envList)) {
    args.push("-e", `${key}=${value}`);
  }
  args.push("e2e/perf/mvc-smoke.js");
  const fd = openSync(logFile, "w");
  const child = spawn("k6", args, {
    cwd: repoRoot,
    env: { ...process.env, ...context.modelKeysEnv, ...envList },
    stdio: ["ignore", fd, fd],
  });
  const done = new Promise((resolve) => {
    child.on("close", (code) => {
      closeSync(fd);
      console.log(
        `[real-stack] k6 ${scenario} exit=${code} report=${path.relative(repoRoot, reportFile)}`,
      );
      resolve({ scenario, status: code, report: reportFile, log: logFile });
    });
  });
  return { done, reportFile, logFile };
}

/**
 * 计算 MySQL slow_log 中检索语句的分位数。
 *
 * @param {Array<{ms:number, rowsExamined:number}>} samples 样本
 * @returns {object} 汇总
 */
function summarizeRetrieval(samples) {
  const values = samples.map((sample) => sample.ms);
  return {
    source: "mysql.slow_log (long_query_time=0.001s, log_output=TABLE)",
    statement:
      "AgentContextJdbc.search: FULLTEXT MATCH(knowledge_chunk.content / knowledge_document.title) AGAINST + ACL 谓词（兼容旧 LIKE 过滤）",
    samples: samples.length,
    p50Ms: percentile(values, 50),
    p95Ms: percentile(values, 95),
    maxMs: values.length > 0 ? Math.max(...values) : null,
    avgRowsExamined:
      samples.length > 0
        ? Number(
            (
              samples.reduce((sum, sample) => sum + sample.rowsExamined, 0) /
              samples.length
            ).toFixed(1),
          )
        : null,
  };
}

/**
 * 开启 slow_log 并清空历史；只对测试栈生效。
 *
 * 必须在 API/Worker 启动前调用：MySQL 的 long_query_time 是会话变量，连接建立后
 * 再改 GLOBAL 不会影响已存在的连接池，会漏掉 Worker 检索语句。
 *
 * @returns {void}
 */
function startRetrievalCapture() {
  runRootSql(`
SET GLOBAL slow_query_log = OFF;
TRUNCATE TABLE mysql.slow_log;
SET GLOBAL log_output = 'TABLE';
SET GLOBAL long_query_time = 0.001;
SET GLOBAL slow_query_log = ON;`);
}

/**
 * 清空已捕获语句；保留 GLOBAL 设置，供正式 k6 负载阶段重新采样。
 *
 * @returns {void}
 */
function clearRetrievalSamples() {
  runRootSql("TRUNCATE TABLE mysql.slow_log;");
}

/**
 * 关闭 slow_log 并恢复默认阈值。
 *
 * @returns {void}
 */
function stopRetrievalCapture() {
  runRootSql(
    `
SET GLOBAL slow_query_log = OFF;
SET GLOBAL long_query_time = 10;`,
    { allowFailure: true },
  );
}

/**
 * 读取并解析 slow_log 中的检索样本。
 *
 * @returns {object} 检索汇总
 */
function readRetrievalSamples() {
  const output = runRootSql(`
SELECT query_time, rows_examined
  FROM mysql.slow_log
 WHERE sql_text LIKE '%knowledge_chunk%'
   AND (sql_text LIKE '%AGAINST%' OR sql_text LIKE '%LIKE%')
 ORDER BY start_time;`).stdout;
  const lines = output.trim().split("\n").filter(Boolean);
  if (lines.length <= 1) {
    return summarizeRetrieval([]);
  }
  const samples = lines.slice(1).map((line) => {
    const [rawTime, rowsExamined] = line.split("\t");
    // TIME_TO_SEC 会把 TIME(6) 截断成整秒（1.992s → 1），必须按 HH:MM:SS.ffffff 自行解析。
    const [hours, minutes, seconds] = String(rawTime).split(":");
    const ms =
      (Number(hours) * 3600 + Number(minutes) * 60 + Number(seconds)) * 1000;
    return { ms, rowsExamined: Number(rowsExamined) || 0 };
  });
  return summarizeRetrieval(samples);
}

/**
 * 并发上传索引积压：文档/图片解析与索引走 heavy 通道，聊天走 default 通道。
 *
 * @param {KnowledgePerfClient} admin 管理员会话
 * @param {string} workspace 工作空间
 * @param {object} options 选项
 * @returns {Promise<Array<object>>} 每个文件的耗时
 */
async function uploadBacklog(admin, workspace, options) {
  const uploads = [];
  for (let index = 1; index <= options.documents; index += 1) {
    uploads.push(
      (async () => {
        const startedAt = Date.now();
        const fileId = await admin.upload(
          workspace,
          "e2e/real/fixtures/market.docx",
          `perf-backlog-${index}.docx`,
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        );
        const uploadDoneAt = Date.now();
        const parseMs = await admin.waitFileParsed(fileId);
        const indexed = await admin.waitIndexed(
          `PERF-M05 索引积压 ${index}`,
          fileId,
        );
        const indexedAt = Date.now();
        return {
          kind: "docx",
          index,
          status: "succeeded",
          fileId,
          documentId: indexed.documentId,
          uploadMs: uploadDoneAt - startedAt,
          parseMs,
          createToIndexedMs: indexed.indexedMs,
          totalMs: indexedAt - startedAt,
          chunkCount: null,
          parseEngine: indexed.document?.parseEngine ?? null,
        };
      })().catch((error) => ({
        kind: "docx",
        index,
        status: "failed",
        error: truncateText(error, 300),
      })),
    );
  }
  if (options.ocr) {
    uploads.push(
      (async () => {
        const startedAt = Date.now();
        const fileId = await admin.upload(
          workspace,
          "deploy/ocr/sample.png",
          "perf-ocr-sample.png",
          "image/png",
        );
        const uploadDoneAt = Date.now();
        const parseMs = await admin.waitFileParsed(fileId);
        const indexed = await admin.waitIndexed("PERF-M05 OCR 单页", fileId);
        const indexedAt = Date.now();
        return {
          kind: "ocr-image",
          index: 0,
          status: "succeeded",
          fileId,
          documentId: indexed.documentId,
          uploadMs: uploadDoneAt - startedAt,
          parseMs,
          createToIndexedMs: indexed.indexedMs,
          totalMs: indexedAt - startedAt,
          chunkCount: null,
          parseEngine: indexed.document?.parseEngine ?? null,
          ocr: indexed.document?.parseSummary?.ocr ?? null,
        };
      })().catch((error) => ({
        kind: "ocr-image",
        index: 0,
        status: "failed",
        error: truncateText(error, 300),
      })),
    );
  }
  return Promise.all(uploads);
}

/**
 * 读取 OpenSearch 别名目标与 v2 mapping 关键字段，作为“同一 v2 索引”证据。
 *
 * @param {object} context 上下文
 * @param {string} tenant 租户代码
 * @returns {Promise<object>} 检查结果
 */
async function inspectOpenSearch(context, tenant) {
  const url = `http://127.0.0.1:${19200}`;
  const prefix = context.openSearchIndexPrefix ?? "riccon-kb";
  const alias = `${prefix}-${tenant}`;
  const result = {
    url,
    indexVersion: context.openSearchIndexVersion ?? "v1",
    prefix,
    alias,
    aliasTarget: null,
    mappingFields: [],
    bboxFields: false,
    aclFields: false,
    checkedAt: shanghaiIso(),
    error: null,
  };
  try {
    const aliasResponse = await fetch(`${url}/_alias/${alias}`);
    if (aliasResponse.status !== 200) {
      throw new Error(`alias ${alias} status ${aliasResponse.status}`);
    }
    const aliasJson = await aliasResponse.json();
    result.aliasTarget = Object.keys(aliasJson)[0] ?? null;
    if (!result.aliasTarget) {
      throw new Error(`alias ${alias} has no index target`);
    }
    const mappingResponse = await fetch(
      `${url}/${result.aliasTarget}/_mapping`,
    );
    if (mappingResponse.status !== 200) {
      throw new Error(
        `mapping ${result.aliasTarget} status ${mappingResponse.status}`,
      );
    }
    const mappingJson = await mappingResponse.json();
    const properties =
      Object.values(mappingJson)[0]?.mappings?.properties ?? {};
    result.mappingFields = Object.keys(properties).sort();
    result.bboxFields = ["bbox_x", "bbox_y", "bbox_w", "bbox_h"].every(
      (field) => field in properties,
    );
    result.aclFields = ["visibility", "department_id", "acl_version"].every(
      (field) => field in properties,
    );
  } catch (error) {
    result.error = truncateText(error, 400);
  }
  return result;
}

/**
 * 采样 1 分钟 load；用于报告运行期间的峰值，避免只看首尾掩盖外部争抢。
 *
 * @param {number} [intervalMs] 采样间隔
 * @returns {{stop: function(): {peak:number, samples:number}}} 监控句柄
 */
function startLoadMonitor(intervalMs = 500) {
  let peak = os.loadavg()[0];
  let samples = 0;
  const timer = setInterval(() => {
    const value = os.loadavg()[0];
    peak = Math.max(peak, value);
    samples += 1;
  }, intervalMs);
  timer.unref?.();
  return {
    stop() {
      clearInterval(timer);
      return { peak: Number(peak.toFixed(2)), samples };
    },
  };
}

/**
 * 执行 M05 knowledge 场景：造语料、并发上传积压、跑 k6、汇总 slow_log 并写增强报告。
 *
 * @param {object} context 运行上下文
 * @returns {Promise<object>} 场景结果
 */
async function runKnowledgePerf(context) {
  const phases = {};
  const seedState = JSON.parse(readFileSync(SEED_FILE, "utf8"));
  const seeded = seedKnowledgeDataset(seedState);
  phases.dataset = "passed";

  const admin = new KnowledgePerfClient(`http://127.0.0.1:${API_PORT}`);
  await admin.login("tenant-alpha", "alpha-admin", "alpha-admin-password-12");
  const workspace = await admin.workspaceId();

  // 基线文档：真实上传→解析→索引→可下载；也是下载场景的源文件。
  const baselineStartedAt = Date.now();
  const baselineFileId = await admin.upload(
    workspace,
    "e2e/real/fixtures/market.docx",
    "perf-knowledge-baseline.docx",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  );
  const baselineParseMs = await admin.waitFileParsed(baselineFileId);
  const baselineIndexed = await admin.waitIndexed(
    "PERF-M05 基线知识库文档",
    baselineFileId,
  );
  const baseline = {
    kind: "docx",
    fileId: baselineFileId,
    documentId: baselineIndexed.documentId,
    parseMs: baselineParseMs,
    createToIndexedMs: baselineIndexed.indexedMs,
    totalMs: Date.now() - baselineStartedAt,
  };
  phases.baselineIndex = "passed";

  const aclUserIds = [
    seedState.userIds.alphaMember,
    seedState.userIds.alphaAdmin2,
  ];
  const reportFile = path.join(evidenceDir, "perf-m05-knowledge.json");

  // M05b 连接器同步分支在 50 并发 knowledge 负载之前串行执行：
  // - 同步/索引墙钟不受 k6 并发干扰；
  // - 连接器文档入库后再跑同一套 k6，检索/首字口径覆盖“连接器文档已在库”的整条 AC-20；
  // - 既有上传积压仍在 k6 期间执行，继续验证 heavy 通道不阻塞 chat。
  // slow_log 从 API/Worker 启动前一直开启：MySQL 的 long_query_time 是会话变量，中途关闭再开启
  // 会让连接池中新建连接停留在默认 10s，导致 k6 检索样本漏采；连接器阶段的日志开销写入报告备注。
  let connector = null;
  if (context.connectorPerf) {
    connector = await runConnectorPerfBranch(admin, context.connectorConfig);
    phases.connectorSync = connector.status === "passed" ? "passed" : "failed";
    if (connector.status !== "passed") {
      // 连接器分支失败必须在这里显式落盘并打到 stdout；否则 k6 的“缺少 ID”错误
      // 会覆盖真实根因（例如 RustFS 建桶/PUT 失败或连接器 API 4xx）。
      const connectorFailureFile = path.join(
        evidenceDir,
        "perf-m05-connector-failure.json",
      );
      writeFileSync(
        connectorFailureFile,
        `${JSON.stringify(connector, null, 2)}\n`,
      );
      console.error(
        `[real-stack] connector branch failed: status=${connector.status} stage=${connector.stage ?? "unknown"} error=${connector.error ?? "unknown"} failure=${path.relative(repoRoot, connectorFailureFile)}`,
      );
    }
  }

  // OCR 只在本宿主原生库可用时测量；不可用写入报告，不伪造单页/索引耗时。
  const ocrAvailable = Boolean(context.ocrProbe?.available);
  const backlogDocuments = Number(
    process.env.PERF_M05_BACKLOG ?? KNOWLEDGE_DEFAULTS.backlogUploads,
  );

  clearRetrievalSamples();
  let k6Result;
  let backlog = [];
  const loadMonitor = startLoadMonitor();
  try {
    k6Result = launchK6(context, "knowledge", {
      PERF_M05_DOC_ID: seeded.ids.visibleDocumentId,
      PERF_M05_ACL_DOC_ID: seeded.ids.aclDocumentId,
      PERF_M05_HIDDEN_DOC_ID: seeded.ids.hiddenDocumentId,
      PERF_M05_DOWNLOAD_DOC_ID: baseline.documentId,
      PERF_M05_ACL_USER_IDS: JSON.stringify(aclUserIds),
      PERF_M05_QUERY: KNOWLEDGE_QUERY,
      // 只有连接器夹具与同步 id 都存在时才启用 k6 侧标记；分支失败时仍跑 M05 基线，
      // 但不会把缺失的连接器元数据当成通过。
      ...(connector?.connector?.id && connector?.trigger?.syncId
        ? {
            PERF_M05_CONNECTOR: "1",
            PERF_M05_CONNECTOR_OBJECTS: String(context.connectorConfig.objects),
            PERF_M05_CONNECTOR_ID: connector.connector.id,
            PERF_M05_CONNECTOR_SYNC_ID: connector.trigger.syncId,
            PERF_M05_CONNECTOR_BUCKET: context.connectorConfig.bucket,
            PERF_M05_CONNECTOR_PREFIX: context.connectorConfig.prefix,
          }
        : {}),
    });
    // 等 k6 进入负载后再提交积压；默认 8s 避开 50 VU 登录/首轮检索的峰值。
    await new Promise((resolve) =>
      setTimeout(
        resolve,
        Number(process.env.PERF_M05_BACKLOG_DELAY_MS ?? "8000"),
      ),
    );
    const backlogPromise = uploadBacklog(admin, workspace, {
      documents: backlogDocuments,
      ocr: ocrAvailable,
    });
    const result = await k6Result.done;
    backlog = await backlogPromise;
    k6Result = result;
  } finally {
    stopRetrievalCapture();
  }
  const loadSamples = loadMonitor.stop();
  const retrieval = readRetrievalSamples();
  phases.retrievalCapture = retrieval.samples > 0 ? "passed" : "failed";
  const openSearchDetail = await inspectOpenSearch(context, "tenant-alpha");
  const openSearchMismatch =
    openSearchDetail.error !== null ||
    !String(openSearchDetail.aliasTarget ?? "").includes(
      `-${context.openSearchIndexVersion ?? "v1"}-`,
    ) ||
    !openSearchDetail.bboxFields ||
    !openSearchDetail.aclFields;
  phases.openSearch =
    openSearchDetail.error === null && !openSearchMismatch
      ? "passed"
      : "failed";

  if (!existsSync(reportFile)) {
    const connectorDetail =
      connector && connector.status !== "passed"
        ? `; connector status=${connector.status} stage=${connector.stage ?? "unknown"} error=${connector.error ?? "unknown"}`
        : "";
    throw new Error(
      `k6 knowledge report missing (exit ${k6Result.status})${connectorDetail}; see ${path.relative(repoRoot, k6Result.log)}`,
    );
  }
  const report = JSON.parse(readFileSync(reportFile, "utf8"));
  const backlogSucceeded = backlog.filter(
    (item) => item.status === "succeeded",
  );
  const backlogFailed = backlog.filter((item) => item.status === "failed");
  const docxSamples = [
    baseline,
    ...backlogSucceeded.filter((item) => item.kind === "docx"),
  ].map((item) => item.createToIndexedMs);
  const ocrSamples = backlogSucceeded
    .filter((item) => item.kind === "ocr-image")
    .map((item) => item.createToIndexedMs);
  const metrics = report.metrics ?? {};
  const firstTokenP95 = metrics.m05_first_token_ms?.["p(95)"] ?? null;
  const userFirstTokenP95 = metrics.m05_chat_submit_ttfb_ms?.["p(95)"] ?? null;
  const apiP95 = metrics.m05_api_ms?.["p(95)"] ?? null;
  const aclWriteP95 = metrics.m05_acl_write_ms?.["p(95)"] ?? null;
  const aclGetP95 = metrics.m05_acl_get_ms?.["p(95)"] ?? null;
  const indexP95 = percentile(docxSamples, 95);
  const ocrIndexP95 = percentile(ocrSamples, 95);
  const searchBudgetMs = Number(
    process.env.PERF_M05_SEARCH_P95_BUDGET_MS ?? "500",
  );
  const decisionRequired =
    retrieval.p95Ms === null || retrieval.p95Ms > searchBudgetMs;
  const indexBudgetExceeded =
    docxSamples.length >= 5 && indexP95 !== null && indexP95 > 60_000;
  const enforceSearch =
    process.env.PERF_M05_SEARCH_ENFORCE === "1" || context.connectorPerf;
  const connectorFailed =
    context.connectorPerf && connector?.status !== "passed";

  report.m05 = {
    ...(report.m05 ?? {}),
    generatedAt: shanghaiIso(),
    dataset: {
      documents: seeded.dataset.documents,
      chunks: seeded.dataset.chunks,
      chunksPerDocument: seeded.dataset.chunksPerDocument,
      visibility: {
        tenant: 4400,
        department: 500,
        usersVisible: 50,
        usersHidden: 50,
      },
      departmentId: seeded.dataset.departmentId,
      query: KNOWLEDGE_QUERY,
      matchEvery: seeded.dataset.matchEvery,
      matchDocuments: seeded.dataset.matchDocuments,
      matchingChunks: seeded.dataset.matchingChunks,
      matchRatio: `${((seeded.dataset.matchDocuments / seeded.dataset.documents) * 100).toFixed(1)}% 文档`,
      seedMs: seeded.dataset.seedMs,
      countsRaw: seeded.dataset.countsRaw,
      note: "直插 SQL 只用于列表/检索/ACL 数据规模；索引与下载仍走真实上传→解析→索引→对象存储路径",
    },
    host: context.envSnapshot.host,
    hostEnd: {
      loadAverage: os.loadavg().map((value) => Number(value.toFixed(2))),
      loadPeak: loadSamples.peak,
      loadSamples: loadSamples.samples,
      measuredAt: shanghaiIso(),
    },
    openSearch: context.envSnapshot.openSearch,
    openSearchDetail,
    openSearchMismatch,
    ocr: {
      probed: context.ocrProbe ?? null,
      measured: ocrAvailable && ocrSamples.length > 0,
      samples: backlog.filter((item) => item.kind === "ocr-image"),
      note: ocrAvailable
        ? "单页 PNG 端到端 create→INDEXED；未构造 20 页扫描件"
        : "宿主 libtesseract 不可用，OCR 未量测；报告不把未执行当通过，需在含 Tesseract 的 worker 镜像/CI 复验",
    },
    indexing: {
      noOcr: {
        samples: docxSamples,
        p95Ms: indexP95,
        budgetMs: 60_000,
        withinBudget: indexP95 !== null && indexP95 <= 60_000,
      },
      ocr: {
        samples: ocrSamples,
        p95Ms: ocrIndexP95,
        budgetMs: 120_000,
        withinBudget: ocrIndexP95 !== null && ocrIndexP95 <= 120_000,
        note: "单页样本，非 ADR 的 20 页口径",
      },
      baseline,
      backlog,
      backlogFailures: backlogFailed,
      backlogDuringLoad: true,
    },
    // M05b 连接器同步分支：同步/索引/队列等待；未启用时为 null，不伪造“已执行”。
    connectorSync: connector,
    retrieval: {
      ...retrieval,
      budgetMs: searchBudgetMs,
      decisionRequired,
      decision: decisionRequired
        ? "检索 p95 未证明 ≤500ms：需在 M05 gate 前决定 (a) MySQL FULLTEXT/覆盖索引，(b) OpenSearch 两阶段 ACL 复核，(c) 用户批准单独检索预算（ADR-0023 §9）"
        : "检索 p95 在 500ms 内，无需额外决策",
    },
    budgets: {
      firstTokenServerP95Ms: firstTokenP95,
      firstTokenServerBudgetMs: 3000,
      firstTokenServerWithinBudget:
        firstTokenP95 !== null && firstTokenP95 <= 3000,
      // 检索在 Worker 模型调用前同步执行；用 retrieval p95 + 服务端首字 p95 估算用户可感知
      // 首字上界。k6 对 SSE 整段缓冲，m05_chat_submit_ttfb_ms 接近完整流，不能当首字。
      userFirstTokenEstimatedP95Ms:
        retrieval.p95Ms !== null && firstTokenP95 !== null
          ? retrieval.p95Ms + firstTokenP95
          : null,
      userFirstTokenBudgetMs: 3000,
      userFirstTokenWithinBudget:
        retrieval.p95Ms !== null &&
        firstTokenP95 !== null &&
        retrieval.p95Ms + firstTokenP95 <= 3000,
      apiP95Ms: apiP95,
      apiBudgetMs: 500,
      apiWithinBudget: apiP95 !== null && apiP95 <= 500,
      aclWriteP95Ms: aclWriteP95,
      aclGetP95Ms: aclGetP95,
      aclBudgetMs: 500,
      // `PERF_M05_ACL_WRITES=0` 时 ACL 读写场景不启动；不能用 null/false 冒充通过或失败。
      aclWithinBudget:
        Number(process.env.PERF_M05_ACL_WRITES ?? "10") > 0
          ? aclWriteP95 !== null &&
            aclWriteP95 <= 500 &&
            aclGetP95 !== null &&
            aclGetP95 <= 500
          : null,
      downloadP95Ms: metrics.m05_download_ms?.["p(95)"] ?? null,
      indexNoOcrP95Ms: indexP95,
      indexBudgetMs: 60_000,
      sseBufferedRequestP95Ms: userFirstTokenP95,
      // M05b：同步总时长与 heavy 通道队列等待是独立预算；索引延迟只记录（连接器
      // 文档可能排在同步事件之后，不能用上传的 60s create→INDEXED 预算替代）。
      connectorSyncProcessingMs: connector?.sync?.processingMs ?? null,
      connectorSyncBudgetMs: connector?.sync?.budgetMs ?? null,
      connectorSyncWithinBudget: connector?.sync?.withinBudget ?? null,
      connectorQueueWaitP95Ms: connector?.queueWait?.combined?.p95Ms ?? null,
      connectorQueueWaitBudgetMs: connector?.queueWait?.budgetMs ?? null,
      connectorQueueWaitWithinBudget:
        connector?.queueWait?.withinBudget ?? null,
      connectorIndexP95Ms: connector?.indexing?.latency?.p95Ms ?? null,
      note: "首字用 retrieval p95 + 服务端模型首字 p95 估算；k6 对 SSE 整段缓冲，m05_chat_submit_ttfb_ms 不是首字口径",
    },
    thresholds: report.thresholds,
    notes: [
      "首字 p95 ≤3s、稳态 JSON API（列表/详情/ACL 读/ACL 写）p95 ≤500ms、50 并发。",
      "首字用 retrieval p95 + 服务端模型首字 p95 估算；m05_chat_submit_ttfb_ms 因 k6 整段缓冲只作参考，不作为首字阈值。",
      "下载是流式代理，单独记录 m05_download_ms，不并入 JSON API p95。",
      "检索 p95 来自 MySQL slow_log 中 AgentContextJdbc FULLTEXT MATCH + ACL 谓词的实际执行时间；不含网络与结果映射。",
      "slow_log 在 API/Worker 建连前开启（long_query_time=0.001s）；它会给测试栈带来额外写开销，结果偏保守。",
      ...(Number(process.env.PERF_M05_ACL_WRITES ?? "10") > 0
        ? []
        : [
            "PERF_M05_ACL_WRITES=0：ACL 写 residual 按既有问题单列，本报告未执行 ACL 读写场景，aclWithinBudget=null 表示未量测，不是通过。",
          ]),
      context.connectorPerf
        ? `M05b 连接器同步分支：${context.connectorConfig.objects} 对象同步与索引在 k6 前串行完成，随后用同一套 50 并发 knowledge 负载复验整条 AC-20；slow_log 全程开启，连接器阶段结果偏保守。`
        : "M05 不启用连接器分支。",
    ],
  };
  if (os.loadavg()[0] > os.cpus().length) {
    report.m05.notes.push(
      `宿主 1 分钟负载 ${os.loadavg()[0].toFixed(1)} 高于 CPU 数 ${os.cpus().length}；结果偏保守，复验应在空闲宿主进行`,
    );
  }
  writeFileSync(reportFile, `${JSON.stringify(report, null, 2)}\n`);

  const matrix = {
    status:
      k6Result.status === 0 &&
      !indexBudgetExceeded &&
      !openSearchMismatch &&
      !connectorFailed &&
      (!decisionRequired || !enforceSearch)
        ? decisionRequired
          ? "passed-with-decision"
          : "passed"
        : "failed",
    generatedAt: shanghaiIso(),
    phase: phases,
    k6: {
      status: k6Result.status,
      report: path.relative(repoRoot, reportFile),
      log: path.relative(repoRoot, k6Result.log),
    },
    retrieval,
    budgets: report.m05.budgets,
    indexing: report.m05.indexing,
    connector: connector,
    openSearch: report.m05.openSearchDetail,
    openSearchMismatch,
    load: {
      start: report.m05.host.loadAverage,
      peak: report.m05.hostEnd.loadPeak,
      end: report.m05.hostEnd.loadAverage,
      samples: report.m05.hostEnd.loadSamples,
    },
    ocr: report.m05.ocr,
    decisionRequired,
    decision: report.m05.retrieval.decision,
    notes: [
      "PERF-M05-001：首字/API/ACL 写/检索量测；PERF-M05-002：索引延迟（无 OCR 与 OCR 单页）。",
      context.connectorPerf
        ? `PERF-M05-003：连接器同步 ${context.connectorConfig.objects} 对象 ≤15min；PERF-M05-004：heavy 通道队列等待 p95 ≤5min。`
        : "PERF-M05-003/004：连接器同步分支未启用；本报告不声称已量测连接器同步或队列等待。",
      ...(Number(process.env.PERF_M05_ACL_WRITES ?? "10") > 0
        ? []
        : [
            "PERF_M05_ACL_WRITES=0：ACL 写 residual 未在本任务判据内执行，aclWithinBudget=null 表示未量测。",
          ]),
      "OpenSearch 使用 v2 索引并核对别名目标与 bbox/acl mapping 字段；检索仍以 MySQL FULLTEXT + ACL 为准。",
      context.connectorPerf
        ? "连接器分支启用时检索 p95 >500ms 直接失败（M05b 整条复验判据），不降级为 passed-with-decision。"
        : "检索 >500ms 时 status 为 passed-with-decision（ADR-0023 §9 未预设通过阈值）；设置 PERF_M05_SEARCH_ENFORCE=1 可按 500ms 硬失败。",
      "OCR 不可用时 ocr.measured=false，不把未执行当通过。",
    ],
  };
  writeFileSync(
    path.join(evidenceDir, "perf-m05-matrix.json"),
    `${JSON.stringify(matrix, null, 2)}\n`,
  );
  console.log(JSON.stringify(matrix, null, 2));

  const failed =
    k6Result.status !== 0 ||
    indexBudgetExceeded ||
    openSearchMismatch ||
    connectorFailed ||
    (enforceSearch && decisionRequired);
  return {
    status: failed ? "failed" : matrix.status,
    matrix,
    reportFile,
  };
}

/**
 * 自检：静态校验脚本语法、假模型自检与 k6 可用性；不启动服务。
 */
function selfTest() {
  const checks = [];
  /**
   * 记录一步。
   *
   * @param {string} name 名称
   * @param {boolean} ok 是否通过
   * @param {string} [detail] 细节
   */
  const record = (name, ok, detail = "") =>
    checks.push({ name, status: ok ? "passed" : "failed", detail });
  for (const file of [
    "scripts/real-stack/fake-model.mjs",
    "scripts/real-stack/seed.mjs",
    "scripts/real-stack/checks.mjs",
    "scripts/real-stack/charset-check.mjs",
    "scripts/real-stack/run.mjs",
    "e2e/perf/mvc-smoke.js",
    "e2e/support/fake-model/scenarios.mjs",
    "e2e/support/fake-model/server.mjs",
    "e2e/support/fake-model/index.mjs",
    "e2e/stub/server.mjs",
  ]) {
    const result = spawnSync("node", ["--check", file], { encoding: "utf8" });
    record(
      `node --check ${file}`,
      result.status === 0,
      result.stderr?.trim() ?? "",
    );
  }
  const fakeSelfTest = spawnSync(
    "node",
    ["scripts/real-stack/fake-model.mjs", "--self-test"],
    {
      encoding: "utf8",
    },
  );
  record(
    "fake-model --self-test",
    fakeSelfTest.status === 0,
    fakeSelfTest.stderr?.trim() ?? "",
  );
  const seedSelfTest = spawnSync(
    "node",
    ["scripts/real-stack/seed.mjs", "--self-test"],
    {
      encoding: "utf8",
    },
  );
  record(
    "seed --self-test",
    seedSelfTest.status === 0,
    seedSelfTest.stderr?.trim() ?? "",
  );
  // 中文展示名落库必须显式 utf8mb4；容器内客户端 auto 会回退 latin1 造成双写乱码。
  const mysqlArgs = mysqlClientArgs();
  record(
    "mysql client charset utf8mb4",
    mysqlArgs.includes("--default-character-set=utf8mb4"),
    mysqlArgs.join(" "),
  );
  record("k6 available", Boolean(k6Version()), k6Version() ?? "k6 not found");
  const failed = checks.filter((entry) => entry.status === "failed");
  console.log(
    JSON.stringify(
      { status: failed.length === 0 ? "passed" : "failed", checks },
      null,
      2,
    ),
  );
  if (failed.length > 0) {
    process.exitCode = 1;
  }
}

/**
 * full 模式：完整真实栈回归。
 *
 * @returns {Promise<object>} 阶段摘要
 */
async function runFull() {
  const phases = {};
  const endpoints = buildModelEndpoints();
  const modelKeys = buildModelKeys("ok");
  const context = {
    mode: "full",
    endpoints: endpoints.endpoints,
    endpointHash: endpoints.hash,
    modelKeysEnv: { REAL_MODEL_KEYS: modelKeys.json },
  };
  context.envSnapshot = buildEnvSnapshot(context);
  writeFileSync(
    ENV_SNAPSHOT_FILE,
    `${JSON.stringify(context.envSnapshot, null, 2)}\n`,
  );

  // 1. 干净测试栈
  console.log("[real-stack] reset compose test stack");
  spawnSync(
    "docker",
    ["compose", "-f", "deploy/compose/docker-compose.test.yml", "down", "-v"],
    {
      cwd: repoRoot,
      stdio: "ignore",
    },
  );
  run("compose-up", "docker", [
    "compose",
    "-f",
    "deploy/compose/docker-compose.test.yml",
    "up",
    "-d",
    "--wait",
  ]);
  phases.compose = "passed";
  await createBucket();
  requireArtifacts();
  for (const artifact of ["apps/web/dist/index.html"]) {
    if (!existsSync(path.join(repoRoot, artifact))) {
      throw new Error(
        `missing build artifact ${artifact}; run contract build first`,
      );
    }
  }

  // 2. 假模型、测试 IdP 与真实应用进程
  const fakeEnv = {
    FAKE_MODEL_PORT: String(MODEL_PORT),
    FAKE_MODEL_KEY_SCENARIOS: JSON.stringify({
      [modelKeys.badKey]: "ok",
      [modelKeys.goodKey]: "ok",
    }),
  };
  start("model", "node", ["scripts/real-stack/fake-model.mjs"], {
    env: fakeEnv,
  });
  await waitForHttp(`http://127.0.0.1:${MODEL_PORT}/__health__`, 30_000);

  const oidcRealms = {
    [OIDC_REALM]: {
      clientId: OIDC_CLIENT_ID,
      clientSecret: OIDC_CLIENT_SECRET,
      subject: OIDC_SUBJECT,
      email: "oidc-alpha@example.test",
    },
  };
  start("oidc", "node", ["e2e/oidp/server.mjs"], {
    env: {
      OIDP_PORT: String(OIDC_PORT),
      OIDP_REALMS: JSON.stringify(oidcRealms),
    },
  });
  await waitForHttp(`http://127.0.0.1:${OIDC_PORT}/__oidc__/health`, 30_000);

  const startup = await startApplications(context);
  phases.migration =
    startup.flywayVersion >= FLYWAY_EXPECTED_VERSION ? "passed" : "failed";
  const fingerprint = await verifyActuatorFingerprint();
  context.fingerprint = fingerprint;
  phases.fingerprint = fingerprint.matched ? "passed" : "default-deny-recorded";

  // 3. Web 生产构建 + 同源代理
  start("web", "node", ["scripts/real-stack/web-server.mjs"], {
    env: {
      REAL_WEB_PORT: String(WEB_PORT),
      REAL_API_PORT: String(API_PORT),
      REAL_WEB_DIST: "apps/web/dist",
    },
  });
  await waitForHttp(`http://127.0.0.1:${WEB_PORT}/login`, 30_000);

  // 4. seed：租户/账号、知识库、MCP、M04 目录（首次）
  const seedEnv = {
    ...context.modelKeysEnv,
    REAL_API_BASE: `http://127.0.0.1:${API_PORT}`,
    REAL_REPO_ROOT: repoRoot,
    REAL_SEED_OUT: SEED_FILE,
    REAL_DOCX_PATH: path.join(repoRoot, "e2e/real/fixtures/market.docx"),
    REAL_MODEL_ENDPOINT_BASE: `http://127.0.0.1:${MODEL_PORT}`,
    RICCON_MASTER_KEY: masterKey,
    RICCON_CREDENTIAL_FINGERPRINT_PEPPER: pepper,
    RICCON_MASTER_KEY_ID: masterKeyId,
  };
  run("seed", "node", ["scripts/real-stack/seed.mjs", "--mode=full"], {
    env: seedEnv,
  });
  phases.seed = "passed";

  // 5. 目录幂等：同一模式再跑一次 catalog，行数必须与首次一致。
  const firstCatalog = capture(
    "seed-catalog-1",
    "node",
    ["scripts/real-stack/seed.mjs", "--mode=catalog"],
    {
      env: seedEnv,
    },
  );
  if (firstCatalog.status !== 0) {
    throw new Error(
      `catalog seed failed; see ${path.join(evidenceDir, "real-stack-seed-catalog-1.log")}`,
    );
  }
  const secondCatalog = capture(
    "seed-catalog-2",
    "node",
    ["scripts/real-stack/seed.mjs", "--mode=catalog"],
    {
      env: seedEnv,
    },
  );
  if (secondCatalog.status !== 0) {
    throw new Error(
      `catalog seed rerun failed; see ${path.join(evidenceDir, "real-stack-seed-catalog-2.log")}`,
    );
  }
  const counts1 = JSON.parse(firstCatalog.stdout).catalog?.counts;
  const counts2 = JSON.parse(secondCatalog.stdout).catalog?.counts;
  if (!counts1 || counts1 !== counts2) {
    throw new Error(
      `catalog seed is not idempotent: ${JSON.stringify({ counts1, counts2 })}`,
    );
  }
  phases.seedIdempotent = "passed";

  // 6. M10 真实栈安全/功能复核
  const m10 = spawnSync("node", ["scripts/real-stack/m10-checks.mjs"], {
    cwd: repoRoot,
    env: {
      ...process.env,
      ...context.modelKeysEnv,
      REAL_SEED_OUT: SEED_FILE,
      REAL_API_LOG: path.join(evidenceDir, "real-stack-api.log"),
      REAL_WEB_BASE: `http://127.0.0.1:${WEB_PORT}`,
      REAL_OIDC_BASE: `http://127.0.0.1:${OIDC_PORT}`,
      REAL_OIDC_REALM: OIDC_REALM,
      REAL_OIDC_TENANT: OIDC_TENANT,
      REAL_OIDC_CLIENT_ID: OIDC_CLIENT_ID,
      REAL_OIDC_CLIENT_SECRET: OIDC_CLIENT_SECRET,
      REAL_OIDC_SUBJECT: OIDC_SUBJECT,
      REAL_OIDC_PROVIDER_NAME: OIDC_PROVIDER_NAME,
    },
    encoding: "utf8",
    maxBuffer: 32 * 1024 * 1024,
  });
  writeFileSync(
    path.join(evidenceDir, "real-stack-m10-checks.log"),
    `${m10.stdout ?? ""}\n${m10.stderr ?? ""}`,
  );
  phases.m10Checks = m10.status === 0 ? "passed" : "failed";

  // 7. 浏览器真实栈 E2E（失败是产品验收信号，不在这里抛出）
  const playwrightEnv = {
    REAL_WEB_BASE: `http://127.0.0.1:${WEB_PORT}`,
    REAL_SEED_OUT: SEED_FILE,
    REAL_EVIDENCE_DIR: evidenceDir,
    REAL_OIDC_BASE: `http://127.0.0.1:${OIDC_PORT}`,
    REAL_OIDC_REALM: OIDC_REALM,
    REAL_OIDC_TENANT: OIDC_TENANT,
    REAL_OIDC_SUBJECT: OIDC_SUBJECT,
    REAL_OIDC_CLIENT_ID: OIDC_CLIENT_ID,
    REAL_OIDC_PROVIDER_NAME: OIDC_PROVIDER_NAME,
  };
  const playwrightGrep = process.env.REAL_PLAYWRIGHT_GREP;
  const playwrightArgs = [
    "exec",
    "playwright",
    "test",
    "--config",
    "e2e/real/playwright.real.config.ts",
    "--reporter=line",
  ];
  if (playwrightGrep) {
    playwrightArgs.push("--grep", playwrightGrep);
  }
  const playwright = spawnSync("pnpm", playwrightArgs, {
    cwd: repoRoot,
    env: { ...process.env, ...playwrightEnv },
    encoding: "utf8",
    maxBuffer: 64 * 1024 * 1024,
  });
  writeFileSync(
    path.join(evidenceDir, "real-stack-playwright.log"),
    `${playwright.stdout ?? ""}\n${playwright.stderr ?? ""}`,
  );
  phases.browserE2e = playwright.status === 0 ? "passed" : "failed";

  // 8. J2 后端闭环与安全审计
  const j2 = spawnSync("node", ["scripts/real-stack/j2-journey.mjs"], {
    cwd: repoRoot,
    env: { ...process.env, ...context.modelKeysEnv, REAL_SEED_OUT: SEED_FILE },
    encoding: "utf8",
    maxBuffer: 16 * 1024 * 1024,
  });
  writeFileSync(
    path.join(evidenceDir, "real-stack-j2.log"),
    `${j2.stdout ?? ""}\n${j2.stderr ?? ""}`,
  );
  phases.j2Journey = j2.status === 0 ? "passed" : "failed";

  if (playwrightGrep) {
    // 定向浏览器复验不会执行 J1/J2 全旅程，checks.mjs 的 message.sent/J2 审计断言必然缺事件；
    // 该阶段在定向模式跳过，不能把“未覆盖”当成通过。全量 gate 不设置 grep，照常执行。
    phases.securityChecks = "skipped-targeted";
    phases.m04Checks = "skipped-targeted";
  } else {
    const checks = spawnSync("node", ["scripts/real-stack/checks.mjs"], {
      cwd: repoRoot,
      env: {
        ...process.env,
        ...context.modelKeysEnv,
        REAL_SEED_OUT: SEED_FILE,
        RICCON_MASTER_KEY: masterKey,
        REAL_API_LOG: path.join(evidenceDir, "real-stack-api.log"),
        REAL_WORKER_LOG: path.join(evidenceDir, "real-stack-worker.log"),
        REAL_MODEL_LOG: path.join(evidenceDir, "real-stack-model.log"),
        REAL_MODEL_CONTROL: `http://127.0.0.1:${MODEL_PORT}`,
        REAL_WORKER_BASE: `http://127.0.0.1:${WORKER_PORT}`,
      },
      encoding: "utf8",
      maxBuffer: 32 * 1024 * 1024,
    });
    writeFileSync(
      path.join(evidenceDir, "real-stack-checks.log"),
      `${checks.stdout ?? ""}\n${checks.stderr ?? ""}`,
    );
    phases.securityChecks = checks.status === 0 ? "passed" : "failed";
    phases.m04Checks = phases.securityChecks;
  }

  const m03 = spawnSync("node", ["scripts/real-stack/m03-checks.mjs"], {
    cwd: repoRoot,
    env: { ...process.env, REAL_SEED_OUT: SEED_FILE },
    encoding: "utf8",
    maxBuffer: 16 * 1024 * 1024,
  });
  writeFileSync(
    path.join(evidenceDir, "real-stack-m03-checks.log"),
    `${m03.stdout ?? ""}\n${m03.stderr ?? ""}`,
  );
  phases.m03Checks = m03.status === 0 ? "passed" : "failed";

  const failedPhases = Object.entries(phases).filter(
    ([, status]) => status === "failed",
  );
  const summary = {
    status: failedPhases.length === 0 ? "passed" : "failed",
    generatedAt: new Date().toISOString(),
    mode: "full",
    phases,
    env: context.envSnapshot,
    fingerprint: context.fingerprint,
    firstTokenBudget: "3000ms (M02-AC-03 / PERF-M04-001)",
    notes: [
      "浏览器 E2E 覆盖登录、J1 上传/解析/引用/重开/首字与逐段增长、J1 运行详情、filesRef 快速发送、J2 创建与审批/产物、M10 OIDC/审计/导出、可访问性。",
      "J2 后端闭环与安全检查另行通过 HTTP/数据库验证；M10 安全复核另跑测试 IdP 与审计/导出断言；M04 目录/配额/成本检查在 checks.mjs。",
      "任何阶段失败都会让本脚本以非零退出。",
    ],
  };
  writeFileSync(
    path.join(evidenceDir, "real-stack-summary.json"),
    `${JSON.stringify(summary, null, 2)}\n`,
  );
  console.log(JSON.stringify(summary, null, 2));
  if (failedPhases.length > 0) {
    process.exitCode = 1;
  }
  return summary;
}

/**
 * perf 模式：只启动 MySQL + 假模型 + API + Worker，按矩阵运行 k6。
 *
 * @returns {Promise<object>} 矩阵摘要
 */
async function runPerf() {
  const phases = {};
  const endpoints = buildModelEndpoints();
  const initialKeys = buildModelKeys(options.perfModes[0]);
  const knowledgePerf = process.env.PERF_M05_SCENARIO === "knowledge";
  // PERF-M06-001：50 并发混合 J1/J2/M06。该场景需要知识库/MCP/S3（J2 报告依赖），
  // 因此启动 MySQL + OpenSearch + RustFS 并按 full 模式播种。
  const workflowPerf = process.env.PERF_M06_SCENARIO === "workflow";
  const connectorConfig = connectorPerfConfig();
  if (connectorConfig.enabled && !knowledgePerf) {
    throw new Error(
      "PERF_M05_CONNECTOR=1 requires PERF_M05_SCENARIO=knowledge",
    );
  }
  const connectorPerf = connectorConfig.enabled;
  const connectorEndpoints = connectorPerf
    ? [
        {
          id: connectorConfig.endpointId,
          url: connectorConfig.endpointUrl,
          label: "RustFS perf B11b",
          allowHttp: true,
          production: false,
        },
      ]
    : [];
  // 宿主没有 libtesseract 时 Worker 会在 OCR 引擎构造期失败；先探测再决定开关。
  const ocrProbe = probeOcr();
  const context = {
    mode: "perf",
    knowledgePerf,
    connectorPerf,
    connectorConfig,
    connectorEndpoints,
    endpoints: endpoints.endpoints,
    endpointHash: endpoints.hash,
    modelKeysEnv: { REAL_MODEL_KEYS: initialKeys.json },
    openSearchIndexVersion:
      process.env.RICCON_OPENSEARCH_INDEX_VERSION ??
      (knowledgePerf ? "v2" : "v1"),
    openSearchIndexPrefix:
      process.env.RICCON_OPENSEARCH_INDEX_PREFIX ?? "riccon-kb",
    ocrProbe,
    ocrEnv: ocrProbe.available
      ? {
          RICCON_OCR_ENABLED: "true",
          RICCON_OCR_LANG: ocrProbe.language,
          RICCON_OCR_TESSDATA_PATH: process.env.RICCON_OCR_TESSDATA_PATH ?? "",
          RICCON_OCR_MAX_CONCURRENCY:
            process.env.RICCON_OCR_MAX_CONCURRENCY ?? "2",
          OMP_THREAD_LIMIT: "1",
        }
      : { RICCON_OCR_ENABLED: "0" },
    startedImages: knowledgePerf
      ? [
          "mysql:8.4.13",
          "opensearchproject/opensearch:3.9.0",
          "rustfs/rustfs:1.0.6",
        ]
      : ["mysql:8.4.13"],
    knowledgeDataset: {
      documents: Number(
        process.env.PERF_M05_DOCS ?? KNOWLEDGE_DEFAULTS.documents,
      ),
      chunks:
        Number(process.env.PERF_M05_DOCS ?? KNOWLEDGE_DEFAULTS.documents) *
        Number(
          process.env.PERF_M05_CHUNKS_PER_DOC ??
            KNOWLEDGE_DEFAULTS.chunksPerDocument,
        ),
      chunksPerDocument: Number(
        process.env.PERF_M05_CHUNKS_PER_DOC ??
          KNOWLEDGE_DEFAULTS.chunksPerDocument,
      ),
    },
  };
  context.envSnapshot = buildEnvSnapshot(context);
  writeFileSync(
    ENV_SNAPSHOT_FILE,
    `${JSON.stringify(context.envSnapshot, null, 2)}\n`,
  );

  console.log(
    knowledgePerf
      ? "[real-stack] perf mode: mysql + opensearch + object-storage (M05 knowledge)"
      : "[real-stack] perf mode: mysql only (no OpenSearch/Web)",
  );
  spawnSync(
    "docker",
    ["compose", "-f", "deploy/compose/docker-compose.test.yml", "down", "-v"],
    {
      cwd: repoRoot,
      stdio: "ignore",
    },
  );
  const services =
    knowledgePerf || workflowPerf
      ? ["mysql", "opensearch", "object-storage"]
      : ["mysql"];
  run("compose-up-perf", "docker", [
    "compose",
    "-f",
    "deploy/compose/docker-compose.test.yml",
    "up",
    "-d",
    "--wait",
    ...services,
  ]);
  phases.compose = "passed";
  if (knowledgePerf || workflowPerf) {
    // 下载/知识库场景需要真实对象；bucket 创建与 full 模式共用同一 SigV4 实现。
    await createBucket();
    phases.objectStorage = "passed";
  }
  requireArtifacts();

  start("model", "node", ["scripts/real-stack/fake-model.mjs"], {
    env: {
      FAKE_MODEL_PORT: String(MODEL_PORT),
      FAKE_MODEL_FIRST_DELAY_MS: "10",
      FAKE_MODEL_CHUNK_INTERVAL_MS: "10",
      FAKE_MODEL_CHUNK_COUNT: "6",
      FAKE_MODEL_TIMEOUT_HOLD_MS: "5000",
    },
  });
  await waitForHttp(`http://127.0.0.1:${MODEL_PORT}/__health__`, 30_000);

  if (knowledgePerf) {
    // 必须在 API/Worker 建连前设置，否则连接池会话保留旧的 long_query_time，
    // 会漏掉 Worker 的检索语句。
    startRetrievalCapture();
    phases.retrievalCapture = "enabled";
  }

  const startup = await startApplications(context, {
    // AC-17 假模型 perf profile（ADR-0021 §5.2）；生产默认不承诺 3s 失败注入。
    RICCON_MODEL_FIRST_TOKEN_TIMEOUT: "1000ms",
    RICCON_MODEL_PRE_FIRST_TOKEN_BUDGET: "2500ms",
    RICCON_MODEL_ROUTING_MAX_ATTEMPTS: "3",
    RICCON_MODEL_ROUTING_RETRY_DELAYS: "50ms,100ms",
    RICCON_MODEL_CONNECT_TIMEOUT: "500ms",
    RICCON_WORKER_MODEL_CONNECT_TIMEOUT: "500ms",
  });
  phases.migration =
    startup.flywayVersion >= FLYWAY_EXPECTED_VERSION ? "passed" : "failed";
  const fingerprint = await verifyActuatorFingerprint();
  context.fingerprint = fingerprint;
  phases.fingerprint = fingerprint.matched ? "passed" : "default-deny-recorded";

  const seedEnv = {
    ...context.modelKeysEnv,
    REAL_API_BASE: `http://127.0.0.1:${API_PORT}`,
    REAL_REPO_ROOT: repoRoot,
    REAL_SEED_OUT: SEED_FILE,
    REAL_MODEL_ENDPOINT_BASE: `http://127.0.0.1:${MODEL_PORT}`,
    RICCON_MASTER_KEY: masterKey,
    RICCON_CREDENTIAL_FINGERPRINT_PEPPER: pepper,
    RICCON_MASTER_KEY_ID: masterKeyId,
  };
  const seedMode = workflowPerf ? "full" : "perf";
  run("seed-perf", "node", ["scripts/real-stack/seed.mjs", `--mode=${seedMode}`], {
    env: seedEnv,
  });
  phases.seed = "passed";
  const seedState = JSON.parse(readFileSync(SEED_FILE, "utf8"));
  context.workspaceId = seedState.workspaces.alpha.id;
  context.workflowMcpToolId = seedState.catalog?.mcpToolId ?? "";

  let workflowFixture = null;
  let limitProbe = null;
  if (workflowPerf) {
    // M06 夹具：真实接口创建/发布一个无审批简单流程；并发探针验证上限提示。
    workflowFixture = await createWorkflowPerfFixture(seedState);
    limitProbe = await probeWorkflowConcurrencyLimit(seedState, workflowFixture);
    context.envSnapshot.perfM06 = {
      scenario: "workflow",
      workflowId: workflowFixture.workflowId,
      adminWorkflowId: workflowFixture.adminWorkflowId,
      workflowName: workflowFixture.name,
      publishedVersionId: workflowFixture.versionId,
      concurrency: {
        j1Vus: 25,
        j2Vus: 3,
        studioVus: 22,
        total: 50,
      },
      workflowLimits: workflowEnvSnapshot(),
      fakeModel: 1,
      limitProbe,
      reproduceCommand: [
        "PERF_M06_SCENARIO=workflow",
        `REAL_PERF_DURATION=${process.env.REAL_PERF_DURATION ?? "45s"}`,
        "REAL_PERF_SKIP_M04=1",
        "node scripts/real-stack/run.mjs --mode=perf",
      ].join(" "),
    };
    writeFileSync(
      ENV_SNAPSHOT_FILE,
      `${JSON.stringify(context.envSnapshot, null, 2)}\n`,
    );
  }

  const runs = [];
  const skipM04 = process.env.REAL_PERF_SKIP_M04 === "1";
  if (!skipM04) {
    // 首字基准不注入失败。
    await setFakeKeyScenarios("ok", initialKeys.badKey, initialKeys.goodKey);
    resetKeyHealth("baseline");
    runs.push(runK6(context, "first_token"));

    // 降级矩阵：每个模式独立一次 k6 运行，前置重置 key 场景与健康状态。
    for (const mode of options.perfModes) {
      await setFakeKeyScenarios(mode, initialKeys.badKey, initialKeys.goodKey);
      resetKeyHealth(mode);
      runs.push(runK6(context, "failover", { PERF_M04_FAILURE_MODE: mode }));
    }

    // 配额争用：fake provider 正常返回，仅验证配额准入。
    await setFakeKeyScenarios("ok", initialKeys.badKey, initialKeys.goodKey);
    resetKeyHealth("quota");
    runs.push(runK6(context, "quota"));

    // 成本查询：B7 端点缺失时这里会非零，不能静默跳过。
    runs.push(runK6(context, "cost"));
  } else {
    phases.m04 = "skipped-by-REAL_PERF_SKIP_M04";
  }

  let knowledgeResult = null;
  if (knowledgePerf) {
    // 语料必须放在 M04 场景之后造，避免把 10 万 chunk 混入 M04 首字基线。
    try {
      knowledgeResult = await runKnowledgePerf(context);
    } finally {
      // runKnowledgePerf 正常路径会关闭；这里兜住异常路径，避免 slow_log 一直开启。
      stopRetrievalCapture();
    }
    phases.knowledge =
      knowledgeResult.status === "failed" ? "failed" : "passed";
    phases.ocr = context.ocrProbe?.available
      ? "measured"
      : "unavailable-on-host";
  }

  let workflowResult = null;
  if (workflowPerf) {
    workflowResult = runWorkflowPerfK6(context, workflowFixture);
    runs.push(workflowResult.run);
    phases.workflow = workflowResult.invariants.ok ? "passed" : "failed";
  }

  const failed = runs.filter((entry) => entry.status !== 0);
  const knowledgeFailed = knowledgeResult?.status === "failed";
  const workflowFailed = workflowResult != null && !workflowResult.invariants.ok;
  const knowledgeDecision = knowledgeResult?.status === "passed-with-decision";
  const matrix = {
    status:
      failed.length === 0 && !knowledgeFailed && !workflowFailed
        ? knowledgeDecision
          ? "passed-with-decision"
          : "passed"
        : "failed",
    generatedAt: new Date().toISOString(),
    mode: "perf",
    env: context.envSnapshot,
    fingerprint: context.fingerprint,
    phases,
    runs,
    knowledge: knowledgeResult?.matrix ?? null,
    workflow: workflowResult
      ? {
          fixture: workflowFixture,
          limitProbe,
          invariants: workflowResult.invariants,
        }
      : null,
    notes: [
      knowledgePerf
        ? "M05 knowledge：MySQL + OpenSearch + RustFS 启动；OpenSearch 只做可重建索引，检索仍以 MySQL FULLTEXT + ACL 为准。"
        : "perf 模式只启动 MySQL 与假模型/API/Worker；OpenSearch 与 Web 未启动，主机规格见 env.host。",
      "每个降级模式独立一次 k6 运行并在前后重置 fake provider key 场景与 key 健康，避免 401 失效/冷却掩盖降级。",
      "配额场景会通过开关 GET/PUT 临时打开 quotaEnforcementEnabled，并把租户/用户日限额设为已用+1；结束后恢复开关与两级限额原值。",
      "OCR 引擎由宿主 libtesseract 决定；不可用时 Worker 以 RICCON_OCR_ENABLED=0 启动并在报告中标记未量测。",
    ],
  };
  writeFileSync(
    path.join(evidenceDir, "perf-m04-matrix.json"),
    `${JSON.stringify(matrix, null, 2)}\n`,
  );
  console.log(JSON.stringify(matrix, null, 2));
  if (failed.length > 0 || knowledgeFailed || workflowFailed) {
    process.exitCode = 1;
  }
  return matrix;
}

/** 主流程。 */
async function main() {
  if (options.help) {
    usage();
    return;
  }
  if (options.selfTest) {
    selfTest();
    return;
  }
  try {
    if (options.mode === "perf") {
      await runPerf();
    } else {
      await runFull();
    }
  } catch (error) {
    console.error("[real-stack] orchestration failed:", error);
    writeFileSync(
      path.join(evidenceDir, "real-stack-summary.json"),
      `${JSON.stringify({ status: "failed", error: String(error), mode: options.mode, generatedAt: new Date().toISOString() }, null, 2)}\n`,
    );
    process.exitCode = 1;
  } finally {
    await stopChildren();
  }
}

await main();
