#!/usr/bin/env node
// M04-V2 备份恢复演练补齐：在 `riccon_restore_drill`（由 restore-drill --keep 导入的快照）上启动
// API/Worker/假模型，执行 J1 冒烟，并读取历史 CHAT run 的运行详情（AC-18 历史可读、OPS-M04-01）。
//
// 前置：`scripts/ops/restore-drill.mjs --apply --keep --allow-partial` 已通过结构/可解密判据。
// 主密钥与 pepper 只从文件读取，不打印。退出码非 0 表示演练失败。

import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdirSync, openSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import process from "node:process";

import { ApiClient, waitFor } from "../../../scripts/real-stack/lib/http.mjs";

const repoRoot = process.cwd();
const runDir = path.join(repoRoot, ".riccon/evidence/TASK-0116/restore-j1");
mkdirSync(runDir, { recursive: true });

/** 读取主密钥与 pepper。 */
function readSecret(name) {
  const file = process.env[name];
  if (!file) {
    throw new Error(`${name} is required (path to a secret file)`);
  }
  return readFileSync(file, "utf8").trim();
}

const masterKey = readSecret("M04_MASTER_KEY_FILE");
const pepper = readSecret("M04_PEPPER_FILE");
const children = [];

/** 启动子进程并写日志。 */
function start(name, command, args, env, logFile) {
  const fd = openSync(logFile, "a");
  const child = spawn(command, args, {
    cwd: repoRoot,
    env: { ...process.env, ...env },
    stdio: ["ignore", fd, fd],
  });
  children.push({ name, child });
  return child;
}

/** 轮询健康检查。 */
async function waitHealth(url, timeoutMs = 150_000) {
  await waitFor(
    async () => {
      try {
        const response = await fetch(url);
        return response.ok ? response : null;
      } catch {
        return null;
      }
    },
    timeoutMs,
    500,
  );
}

/** 在演练库上执行只读 SQL（用于查找历史 run）。 */
function restoreSql(query) {
  const result = spawn(
    "docker",
    [
      "compose",
      "-f",
      "deploy/compose/docker-compose.test.yml",
      "exec",
      "-T",
      "-e",
      "MYSQL_PWD=riccon_test_only",
      "mysql",
      "mysql",
      "--user=riccon",
      "--database=riccon_restore_drill",
      "-N",
      "--batch",
      "-e",
      query,
    ],
    { cwd: repoRoot, stdio: ["ignore", "pipe", "pipe"] },
  );
  let out = "";
  result.stdout.on("data", (chunk) => {
    out += chunk;
  });
  return new Promise((resolve) => {
    result.on("close", (code) => resolve({ code, out: out.trim() }));
  });
}

/** 十六进制转 UUID 字符串。 */
function hexToUuid(hex) {
  const value = hex.toLowerCase();
  return `${value.slice(0, 8)}-${value.slice(8, 12)}-${value.slice(12, 16)}-${value.slice(16, 20)}-${value.slice(20)}`;
}

const endpoints = [
  {
    code: "fake-primary",
    baseUrl: "http://127.0.0.1:18990/v1",
    environmentLabel: "DEMO_ONLY",
  },
  {
    code: "fake-secondary",
    baseUrl: "http://127.0.0.1:18990/v1-secondary",
    environmentLabel: "DEMO_ONLY",
  },
];
const endpointHash = createHash("sha256")
  .update(
    JSON.stringify(
      endpoints.map(({ code, baseUrl, environmentLabel }) => ({
        code,
        baseUrl,
        environmentLabel,
      })),
    ),
  )
  .digest("hex");

const shared = {
  RICCON_MASTER_KEY: masterKey,
  RICCON_MASTER_KEY_ID: "mk-1",
  RICCON_CREDENTIAL_FINGERPRINT_PEPPER: pepper,
  RICCON_MODEL_DEPLOYMENT_MODE: "DEMO",
  RICCON_MODEL_ENDPOINTS: JSON.stringify(endpoints),
  RICCON_MODEL_ENDPOINT_SET_HASH: endpointHash,
  RICCON_MODEL_ALLOW_HTTP: "true",
  RICCON_BILLING_CURRENCY: "CNY",
  RICCON_QUOTA_DEFAULT_TENANT_DAILY: "10000",
  RICCON_QUOTA_DEFAULT_TENANT_MONTHLY: "200000",
  RICCON_QUOTA_DEFAULT_USER_DAILY: "1000",
  RICCON_QUOTA_DEFAULT_USER_MONTHLY: "20000",
  RICCON_CHAT_MAX_STREAMS: "50",
  RICCON_DB_POOL_SIZE: "10",
  RICCON_FAKE_MODEL: "1",
  RICCON_SERVICE_TOKEN_SECRET: `restore-j1-${Date.now()}-${Math.random().toString(16).slice(2)}`,
  RICCON_DB_URL:
    "jdbc:mysql://127.0.0.1:13306/riccon_restore_drill?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8",
};

const report = { generatedAt: new Date().toISOString() };
try {
  start(
    "fake-model",
    "node",
    [
      "scripts/real-stack/fake-model.mjs",
      "--scenario",
      "ok",
      "--chunk-count",
      "18",
    ],
    {},
    path.join(runDir, "fake-model.log"),
  );
  start(
    "api",
    "java",
    ["-jar", "apps/api/target/riccon-api-0.1.0-SNAPSHOT-exec.jar"],
    {
      ...shared,
      RICCON_BOOTSTRAP_ADMIN_USERNAME: "platform-admin",
      RICCON_BOOTSTRAP_ADMIN_PASSWORD: "test-bootstrap-password-12",
      RICCON_API_PORT: "18089",
      RICCON_WORKER_BASE_URL: "http://127.0.0.1:18083",
      RICCON_PUBLIC_BASE_URL: "http://127.0.0.1:8092",
    },
    path.join(runDir, "api.log"),
  );
  start(
    "worker",
    "java",
    ["-jar", "apps/worker/target/riccon-worker-0.1.0-SNAPSHOT-exec.jar"],
    {
      ...shared,
      RICCON_S3_BUCKET: "riccon-test",
      RICCON_MCP_ALLOW_STDIO: "true",
      RICCON_MCP_ALLOWED_SERVERS: "riccon-builtin",
      RICCON_MCP_ALLOW_HTTP: "true",
      RICCON_WORKER_PORT: "18083",
    },
    path.join(runDir, "worker.log"),
  );
  await waitHealth("http://127.0.0.1:18089/actuator/health");
  await waitHealth("http://127.0.0.1:18083/actuator/health");
  await waitHealth("http://127.0.0.1:18990/__health__", 30_000);

  const seed = JSON.parse(
    readFileSync(
      path.join(repoRoot, ".riccon/evidence/TASK-0116/real-stack-seed.json"),
      "utf8",
    ),
  );
  const member = new ApiClient("http://127.0.0.1:18089");
  await member.login(
    seed.tenants.alpha,
    "alpha-member",
    "alpha-member-password-12",
  );
  const workspaces = await member.get("/api/v1/workspaces?page=1&size=100");
  if (workspaces.status !== 200 || !(workspaces.data.items ?? [])[0]) {
    throw new Error(`restored workspace missing: ${workspaces.status}`);
  }
  const conversation = await member.post(
    `/api/v1/workspaces/${workspaces.data.items[0].id}/conversations`,
    { title: "恢复演练 J1 冒烟（V2）" },
  );
  if (conversation.status !== 201) {
    throw new Error(`restore J1 conversation failed: ${conversation.status}`);
  }
  const idempotency = `restore-j1-${Date.now()}`;
  const message = await member.post(
    `/api/v1/conversations/${conversation.data.id}/messages`,
    { content: "恢复演练：请返回固定演示内容。", clientMessageId: idempotency },
    { "Idempotency-Key": idempotency },
  );
  if (![200, 201].includes(message.status)) {
    throw new Error(
      `restore J1 message failed: ${message.status} ${message.text.slice(0, 300)}`,
    );
  }
  const runMatch = /"runId":"([0-9a-f-]{36})"/.exec(message.text);
  if (!runMatch) {
    throw new Error("restore J1 stream did not expose runId");
  }
  const detail = await waitFor(
    async () => {
      const response = await member.get(`/api/v1/runs/${runMatch[1]}`);
      if (response.status !== 200) {
        return null;
      }
      return (response.data?.modelInvocations ?? []).some(
        (invocation) =>
          invocation.purpose === "CHAT" && invocation.status === "SUCCEEDED",
      )
        ? response
        : null;
    },
    90_000,
    500,
  );
  report.j1 = {
    status: "passed",
    runId: runMatch[1],
    invocationCount: detail.data.modelInvocations.length,
  };

  // 历史调用可在运行详情读取：从恢复库取一个真实历史 run，再通过 API 读取。
  const history = await restoreSql(
    "SELECT HEX(id) FROM agent_run WHERE tenant_id='tenant-alpha' AND kind='CHAT' AND status='SUCCEEDED' ORDER BY created_at ASC LIMIT 1",
  );
  if (history.code !== 0 || !history.out) {
    throw new Error("restored database has no historical CHAT run");
  }
  const historyRun = await member.get(`/api/v1/runs/${hexToUuid(history.out)}`);
  if (historyRun.status !== 200) {
    throw new Error(`historical run detail not readable: ${historyRun.status}`);
  }
  const historyInvocations = historyRun.data?.modelInvocations ?? [];
  if (!historyInvocations.some((invocation) => invocation.modelKey)) {
    throw new Error("historical run detail must expose model identifier");
  }
  report.history = {
    status: "passed",
    runId: hexToUuid(history.out),
    invocationCount: historyInvocations.length,
  };
  report.status = "passed";
  console.log("[restore-j1] passed");
  console.log(JSON.stringify(report, null, 2));
} catch (error) {
  report.status = "failed";
  report.error = String(error?.stack ?? error);
  console.error(`[restore-j1] failed: ${error?.message ?? error}`);
  process.exitCode = 1;
} finally {
  writeFileSync(
    path.join(runDir, "restore-j1.json"),
    JSON.stringify(report, null, 2),
  );
  for (const { child } of children) {
    if (child && !child.killed) {
      child.kill("SIGTERM");
    }
  }
  await new Promise((resolve) => setTimeout(resolve, 2000));
  for (const { child } of children) {
    if (child && !child.killed) {
      child.kill("SIGKILL");
    }
  }
}
