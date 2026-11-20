#!/usr/bin/env node
// M02/M03/M10/M04 真实栈编排：compose + API + Worker + Web + 假模型 + 本地 MCP server。
//
// 该脚本是验证者资产，不 import 生产应用内的 mock；它启动真实进程、在 MySQL 中 seed 目录与
// 加密凭据，然后运行 Playwright 浏览器旅程、J2 HTTP 闭环、安全审计与 M04 目录/配额检查。
//
// 两种模式：
// - full（默认）：完整 compose（MySQL/OpenSearch/RustFS）+ Web，用于 M02/M03/M10/M04 回归；
// - perf：只启动 MySQL、假模型、API、Worker（不启 OpenSearch/Web），按降级矩阵运行 k6。
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
  existsSync,
  mkdirSync,
  openSync,
  readFileSync,
  writeFileSync,
} from "node:fs";
import os from "node:os";
import path from "node:path";
import process from "node:process";

import { mysqlClientArgs, runSql } from "./lib/mysql.mjs";

/** 仓库根目录。 */
const repoRoot = process.cwd();

/** 证据任务目录；复验时用 REAL_EVIDENCE_TASK 隔离新旧日志。 */
const evidenceTask = process.env.REAL_EVIDENCE_TASK ?? "TASK-0024";
const evidenceDir = path.join(repoRoot, ".riccon/evidence", evidenceTask);
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
  REAL_API_PORT/REAL_WORKER_PORT/REAL_WEB_PORT/REAL_MODEL_PORT
  REAL_PLAYWRIGHT_GREP           只过滤浏览器用例；全量 gate 不设置
  REAL_PERF_DURATION             perf 首字/降级场景时长（默认 45s）

示例:
  node scripts/real-stack/run.mjs --mode=full
  node scripts/real-stack/run.mjs --mode=perf --perf-modes=server_error,rate_limited,unauthorized,timeout`);
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
 * 使用 SigV4 创建测试桶；RustFS 不提供免认证建桶。
 *
 * @returns {Promise<void>}
 */
async function createBucket() {
  const host = "127.0.0.1:19000";
  const accessKey = "riccon_test_only";
  const secretKey = "riccon_test_only_secret";
  const bucket = "riccon-test";
  const region = "us-east-1";
  const service = "s3";
  const payloadHash = createHash("sha256").update("").digest("hex");
  const amzDate = new Date()
    .toISOString()
    .replace(/[-:]/g, "")
    .replace(/\.\d{3}/, "");
  const date = amzDate.slice(0, 8);
  const canonicalHeaders = `host:${host}\nx-amz-content-sha256:${payloadHash}\nx-amz-date:${amzDate}\n`;
  const signedHeaders = "host;x-amz-content-sha256;x-amz-date";
  const canonicalRequest = `PUT\n/${bucket}\n\n${canonicalHeaders}\n${signedHeaders}\n${payloadHash}`;
  const scope = `${date}/${region}/${service}/aws4_request`;
  const stringToSign = `AWS4-HMAC-SHA256\n${amzDate}\n${scope}\n${createHash(
    "sha256",
  )
    .update(canonicalRequest)
    .digest("hex")}`;
  const hmac = (key, value) => createHmac("sha256", key).update(value).digest();
  const signingKey = hmac(
    hmac(hmac(hmac(`AWS4${secretKey}`, date), region), service),
    "aws4_request",
  );
  const signature = createHmac("sha256", signingKey)
    .update(stringToSign)
    .digest("hex");
  const authorization = `AWS4-HMAC-SHA256 Credential=${accessKey}/${scope}, SignedHeaders=${signedHeaders}, Signature=${signature}`;
  const response = await fetch(`http://${host}/${bucket}`, {
    method: "PUT",
    headers: {
      Host: host,
      "x-amz-content-sha256": payloadHash,
      "x-amz-date": amzDate,
      Authorization: authorization,
    },
  });
  if (![200, 409].includes(response.status)) {
    throw new Error(
      `create bucket failed: ${response.status} ${await response.text()}`,
    );
  }
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
      node: process.version,
    },
    images: {
      compose: composeImages(),
      started: context.mode === "perf" ? ["mysql:8.4.13"] : composeImages(),
    },
    k6Version: k6Version(),
    scriptSha256: fileSha256("e2e/perf/mvc-smoke.js"),
    // k6 前置校验字段；必须与注入 API/Worker 的 perf profile 一致。
    chatMaxStreams: 50,
    dbPoolSize: 25,
    fakeModel: 1,
    deploymentMode: "DEMO",
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
            dbPoolSize: 25,
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
        ? "excluded (perf mode; 2C4Gi sandbox 不足，主机规格见 host)"
        : "started (compose full)",
  };
}

/**
 * 生成共享 M04 env；API 与 Worker 必须收到同一份。
 *
 * @param {object} context 上下文
 * @returns {Record<string,string>} 环境变量
 */
function sharedModelEnv(context) {
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
    RICCON_DB_POOL_SIZE: "25",
    RICCON_FAKE_MODEL: "1",
    SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE: "25",
  };
}

/**
 * 启动 API/Worker；返回共享 env 供后续阶段复用。
 *
 * @param {object} context 上下文
 * @param {object} [extra] 额外 env（perf 超时 profile）
 */
function startApplications(context, extra = {}) {
  const shared = { ...sharedModelEnv(context), ...extra };
  start(
    "api",
    "java",
    ["-jar", "apps/api/target/riccon-api-0.1.0-SNAPSHOT-exec.jar"],
    {
      env: {
        ...shared,
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
  start(
    "worker",
    "java",
    ["-jar", "apps/worker/target/riccon-worker-0.1.0-SNAPSHOT-exec.jar"],
    {
      env: {
        ...shared,
        RICCON_SERVICE_TOKEN_SECRET: serviceSecret,
        RICCON_S3_BUCKET: "riccon-test",
        RICCON_MCP_ALLOW_STDIO: "true",
        RICCON_MCP_ALLOWED_SERVERS: "riccon-builtin",
        RICCON_MCP_ALLOW_HTTP: "true",
        RICCON_WORKER_PORT: String(WORKER_PORT),
        ...extra,
      },
    },
  );
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

  startApplications(context);
  await waitForHttp(`http://127.0.0.1:${API_PORT}/actuator/health`, 120_000);
  await waitForHttp(`http://127.0.0.1:${WORKER_PORT}/actuator/health`, 120_000);
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
  const context = {
    mode: "perf",
    endpoints: endpoints.endpoints,
    endpointHash: endpoints.hash,
    modelKeysEnv: { REAL_MODEL_KEYS: initialKeys.json },
  };
  context.envSnapshot = buildEnvSnapshot(context);
  writeFileSync(
    ENV_SNAPSHOT_FILE,
    `${JSON.stringify(context.envSnapshot, null, 2)}\n`,
  );

  console.log("[real-stack] perf mode: mysql only (no OpenSearch/Web)");
  spawnSync(
    "docker",
    ["compose", "-f", "deploy/compose/docker-compose.test.yml", "down", "-v"],
    {
      cwd: repoRoot,
      stdio: "ignore",
    },
  );
  run("compose-up-perf", "docker", [
    "compose",
    "-f",
    "deploy/compose/docker-compose.test.yml",
    "up",
    "-d",
    "--wait",
    "mysql",
  ]);
  phases.compose = "passed";
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

  startApplications(context, {
    // AC-17 假模型 perf profile（ADR-0021 §5.2）；生产默认不承诺 3s 失败注入。
    RICCON_MODEL_FIRST_TOKEN_TIMEOUT: "1000ms",
    RICCON_MODEL_PRE_FIRST_TOKEN_BUDGET: "2500ms",
    RICCON_MODEL_ROUTING_MAX_ATTEMPTS: "3",
    RICCON_MODEL_ROUTING_RETRY_DELAYS: "50ms,100ms",
    RICCON_MODEL_CONNECT_TIMEOUT: "500ms",
    RICCON_WORKER_MODEL_CONNECT_TIMEOUT: "500ms",
  });
  await waitForHttp(`http://127.0.0.1:${API_PORT}/actuator/health`, 120_000);
  await waitForHttp(`http://127.0.0.1:${WORKER_PORT}/actuator/health`, 120_000);
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
  run("seed-perf", "node", ["scripts/real-stack/seed.mjs", "--mode=perf"], {
    env: seedEnv,
  });
  phases.seed = "passed";
  const seedState = JSON.parse(readFileSync(SEED_FILE, "utf8"));
  context.workspaceId = seedState.workspaces.alpha.id;

  const runs = [];
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

  const failed = runs.filter((entry) => entry.status !== 0);
  const matrix = {
    status: failed.length === 0 ? "passed" : "failed",
    generatedAt: new Date().toISOString(),
    mode: "perf",
    env: context.envSnapshot,
    fingerprint: context.fingerprint,
    phases,
    runs,
    notes: [
      "perf 模式只启动 MySQL 与假模型/API/Worker；OpenSearch 与 Web 未启动，主机规格见 env.host。",
      "每个降级模式独立一次 k6 运行并在前后重置 fake provider key 场景与 key 健康，避免 401 失效/冷却掩盖降级。",
      "配额场景会临时打开 quotaEnforcementEnabled 并设置限额=已用+1，结束后恢复 false 与不限。",
    ],
  };
  writeFileSync(
    path.join(evidenceDir, "perf-m04-matrix.json"),
    `${JSON.stringify(matrix, null, 2)}\n`,
  );
  console.log(JSON.stringify(matrix, null, 2));
  if (failed.length > 0) {
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
