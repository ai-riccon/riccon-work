#!/usr/bin/env node
// M02 真实栈 E2E 编排：compose + API + Worker + Web 生产构建 + 假模型 + 本地 MCP server。
//
// 该脚本是验证者资产，不 import 生产应用内的 mock；它启动真实进程、在 MySQL 中 seed 目录与
// 加密凭据，然后运行 Playwright 浏览器旅程与 J2 HTTP 闭环/安全审计。
//
// 退出码：只要浏览器验收（首字预算、J2 创建入口）失败即非零；J2 后端闭环与安全检查失败同样非零。
// 所有服务的日志写入 `.riccon/evidence/<REAL_EVIDENCE_TASK>/`（默认 TASK-0024），便于评审复核。
// 复验时设置 REAL_EVIDENCE_TASK=TASK-00xx，把新证据与旧证据隔离，避免覆盖历史日志。

import { spawn, spawnSync } from "node:child_process";
import { createHash, createHmac, randomBytes } from "node:crypto";
import {
  existsSync,
  mkdirSync,
  openSync,
  readFileSync,
  writeFileSync,
} from "node:fs";
import path from "node:path";

const repoRoot = process.cwd();
const evidenceTask = process.env.REAL_EVIDENCE_TASK ?? "TASK-0024";
const evidenceDir = path.join(repoRoot, ".riccon/evidence", evidenceTask);
mkdirSync(evidenceDir, { recursive: true });

const API_PORT = Number(process.env.REAL_API_PORT ?? 18080);
const WORKER_PORT = Number(process.env.REAL_WORKER_PORT ?? 18081);
const WEB_PORT = Number(process.env.REAL_WEB_PORT ?? 8092);
const MODEL_PORT = Number(process.env.REAL_MODEL_PORT ?? 18990);
const SEED_FILE = path.join(evidenceDir, "real-stack-seed.json");
const masterKey =
  process.env.RICCON_MASTER_KEY ?? randomBytes(32).toString("base64");
const serviceSecret =
  process.env.RICCON_SERVICE_TOKEN_SECRET ??
  `real-stack-service-token-${randomBytes(24).toString("hex")}`;

/** 运行中的子进程；finally 中统一回收。 */
const children = [];

/** 启动子进程并把 stdout/stderr 写入证据日志。 */
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

/** 同步执行并继承输出；用于一次性命令。 */
function run(name, command, args, options = {}) {
  const logPath = path.join(evidenceDir, `real-stack-${name}.log`);
  const fd = openSync(logPath, "a");
  const result = spawnSync(command, args, {
    cwd: repoRoot,
    env: { ...process.env, ...(options.env ?? {}) },
    stdio: ["ignore", fd, fd],
  });
  if (result.status !== 0) {
    throw new Error(
      `${name} failed with exit ${result.status}; see ${path.relative(repoRoot, logPath)}`,
    );
  }
  return result;
}

/** 轮询 HTTP 健康检查。 */
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

const phases = {};

try {
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

  // 2. 产物检查（构建由合同 commands.build 负责；这里只消费已提交的构建结果）
  for (const artifact of [
    "apps/api/target/riccon-api-0.1.0-SNAPSHOT-exec.jar",
    "apps/worker/target/riccon-worker-0.1.0-SNAPSHOT-exec.jar",
    "apps/web/dist/index.html",
  ]) {
    if (!existsSync(path.join(repoRoot, artifact))) {
      throw new Error(
        `missing build artifact ${artifact}; run contract build first`,
      );
    }
  }

  // 3. 假模型与真实应用进程
  start("model", "node", ["scripts/real-stack/fake-model.mjs"], {
    env: { FAKE_MODEL_PORT: String(MODEL_PORT) },
  });
  await waitForHttp(`http://127.0.0.1:${MODEL_PORT}/__health__`, 30_000);

  start(
    "api",
    "java",
    ["-jar", "apps/api/target/riccon-api-0.1.0-SNAPSHOT-exec.jar"],
    {
      env: {
        RICCON_BOOTSTRAP_ADMIN_USERNAME: "platform-admin",
        RICCON_BOOTSTRAP_ADMIN_PASSWORD: "test-bootstrap-password-12",
        RICCON_SERVICE_TOKEN_SECRET: serviceSecret,
        RICCON_API_PORT: String(API_PORT),
        RICCON_WORKER_BASE_URL: `http://127.0.0.1:${WORKER_PORT}`,
      },
    },
  );
  await waitForHttp(`http://127.0.0.1:${API_PORT}/actuator/health`, 120_000);

  start(
    "worker",
    "java",
    ["-jar", "apps/worker/target/riccon-worker-0.1.0-SNAPSHOT-exec.jar"],
    {
      env: {
        RICCON_MASTER_KEY: masterKey,
        RICCON_SERVICE_TOKEN_SECRET: serviceSecret,
        RICCON_S3_BUCKET: "riccon-test",
        RICCON_MCP_ALLOW_STDIO: "true",
        RICCON_MCP_ALLOWED_SERVERS: "riccon-builtin",
        RICCON_MCP_ALLOW_HTTP: "true",
        RICCON_MODEL_ALLOW_HTTP: "true",
        RICCON_WORKER_PORT: String(WORKER_PORT),
      },
    },
  );
  await waitForHttp(`http://127.0.0.1:${WORKER_PORT}/actuator/health`, 120_000);

  // 4. Web 生产构建 + 同源代理
  start("web", "node", ["scripts/real-stack/web-server.mjs"], {
    env: {
      REAL_WEB_PORT: String(WEB_PORT),
      REAL_API_PORT: String(API_PORT),
      REAL_WEB_DIST: "apps/web/dist",
    },
  });
  await waitForHttp(`http://127.0.0.1:${WEB_PORT}/login`, 30_000);

  // 5. seed：租户/账号、模型/MCP 目录、加密凭据、知识库文档
  run("seed", "node", ["scripts/real-stack/seed.mjs"], {
    env: {
      RICCON_MASTER_KEY: masterKey,
      REAL_API_BASE: `http://127.0.0.1:${API_PORT}`,
      REAL_REPO_ROOT: repoRoot,
      REAL_SEED_OUT: SEED_FILE,
      REAL_DOCX_PATH: path.join(repoRoot, "e2e/real/fixtures/market.docx"),
    },
  });
  phases.seed = "passed";

  // 6. 浏览器真实栈 E2E（失败是产品验收信号，不在这里抛出）
  const playwrightEnv = {
    REAL_WEB_BASE: `http://127.0.0.1:${WEB_PORT}`,
    REAL_SEED_OUT: SEED_FILE,
    REAL_EVIDENCE_DIR: evidenceDir,
  };
  const playwright = spawnSync(
    "pnpm",
    [
      "exec",
      "playwright",
      "test",
      "--config",
      "e2e/real/playwright.real.config.ts",
      "--reporter=line",
    ],
    {
      cwd: repoRoot,
      env: { ...process.env, ...playwrightEnv },
      encoding: "utf8",
      maxBuffer: 64 * 1024 * 1024,
    },
  );
  writeFileSync(
    path.join(evidenceDir, "real-stack-playwright.log"),
    `${playwright.stdout ?? ""}\n${playwright.stderr ?? ""}`,
  );
  phases.browserE2e = playwright.status === 0 ? "passed" : "failed";

  // 7. J2 后端闭环与安全审计（独立于浏览器缺口，验证真实链路是否可用）
  const j2 = spawnSync("node", ["scripts/real-stack/j2-journey.mjs"], {
    cwd: repoRoot,
    env: { ...process.env, REAL_SEED_OUT: SEED_FILE },
    encoding: "utf8",
    maxBuffer: 16 * 1024 * 1024,
  });
  writeFileSync(
    path.join(evidenceDir, "real-stack-j2.log"),
    `${j2.stdout ?? ""}\n${j2.stderr ?? ""}`,
  );
  phases.j2Journey = j2.status === 0 ? "passed" : "failed";

  const checks = spawnSync("node", ["scripts/real-stack/checks.mjs"], {
    cwd: repoRoot,
    env: {
      ...process.env,
      REAL_SEED_OUT: SEED_FILE,
      RICCON_MASTER_KEY: masterKey,
      REAL_API_LOG: path.join(evidenceDir, "real-stack-api.log"),
      REAL_WORKER_LOG: path.join(evidenceDir, "real-stack-worker.log"),
      REAL_MODEL_LOG: path.join(evidenceDir, "real-stack-model.log"),
    },
    encoding: "utf8",
    maxBuffer: 16 * 1024 * 1024,
  });
  writeFileSync(
    path.join(evidenceDir, "real-stack-checks.log"),
    `${checks.stdout ?? ""}\n${checks.stderr ?? ""}`,
  );
  phases.securityChecks = checks.status === 0 ? "passed" : "failed";

  const summary = {
    status:
      phases.browserE2e === "passed" &&
      phases.j2Journey === "passed" &&
      phases.securityChecks === "passed"
        ? "passed"
        : "failed",
    generatedAt: new Date().toISOString(),
    phases,
    firstTokenBudget: "3000ms (M02-AC-03)",
    notes: [
      "浏览器 E2E 覆盖登录、J1 上传/解析/引用/重开/首字与逐段增长、J1 运行详情、filesRef 快速发送、J2 创建与审批/产物、可访问性。",
      "J2 后端闭环与安全检查另行通过 HTTP/数据库验证；任何阶段失败都会让本脚本以非零退出。",
    ],
  };
  writeFileSync(
    path.join(evidenceDir, "real-stack-summary.json"),
    `${JSON.stringify(summary, null, 2)}\n`,
  );
  console.log(JSON.stringify(summary, null, 2));

  const failed = Object.entries(phases).filter(
    ([, status]) => status === "failed",
  );
  if (failed.length > 0) {
    process.exitCode = 1;
  }
} catch (error) {
  console.error("[real-stack] orchestration failed:", error);
  writeFileSync(
    path.join(evidenceDir, "real-stack-summary.json"),
    `${JSON.stringify({ status: "failed", error: String(error), phases, generatedAt: new Date().toISOString() }, null, 2)}\n`,
  );
  process.exitCode = 1;
} finally {
  await stopChildren();
}
