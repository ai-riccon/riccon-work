#!/usr/bin/env node
// M04-V2 独立运行时验证（Verifier 资产，不是生产代码）：
//   1) AC-18：关闭路由（BASELINE）、降级与配额执行后，J1 问答与 J2 报告仍可用，
//      历史模型调用与成本快照可读；随后恢复开关种子。
//   2) OPS-M04-02a：`scripts/ops/metrics-check --base-url <worker>` 断言 Worker 7 个指标。
//   3) OPS-M04-02b：抓取 API `/actuator/prometheus`，断言配额/成本 2 个主指标 + 3 个辅助指标。
//   4) 回滚窗口固定 WARN：以 `RICCON_WORKER_SCHEMA_VALIDATION_ENABLED=false` 启动第二个 Worker，
//      核对 `RICCON-WORKER-002` 逐字出现恰好一次。
//
// 前置：MySQL 8.4.13 测试栈已启动；`real-stack-seed.json` 与构建产物来自 TASK-0116 的 EV；
// 主密钥与 pepper 只从 `M04_MASTER_KEY_FILE` / `M04_PEPPER_FILE` 读取，脚本不打印它们的值。
// 退出码：任一断言失败非 0；成功写 `runtime-verify.json`。

import { spawn } from "node:child_process";
import { createHash, randomBytes } from "node:crypto";
import {
  copyFileSync,
  mkdirSync,
  openSync,
  readFileSync,
  writeFileSync,
} from "node:fs";
import path from "node:path";
import process from "node:process";

import { ApiClient, waitFor } from "../../../scripts/real-stack/lib/http.mjs";

const repoRoot = process.cwd();
const evidenceDir = path.join(repoRoot, ".riccon/evidence/TASK-0116");
const runDir = path.join(evidenceDir, "ac18");
mkdirSync(runDir, { recursive: true });

/** 读取主密钥与 pepper；缺失即失败，绝不回显。 */
function readSecret(envName) {
  const file = process.env[envName];
  if (!file) {
    throw new Error(`${envName} is required (path to a secret file)`);
  }
  return readFileSync(file, "utf8").trim();
}

const masterKey = readSecret("M04_MASTER_KEY_FILE");
const pepper = readSecret("M04_PEPPER_FILE");
const masterKeyId = process.env.M04_MASTER_KEY_ID ?? "mk-1";

const apiBase = "http://127.0.0.1:18080";
const workerBase = "http://127.0.0.1:18081";
const modelBase = "http://127.0.0.1:18990";
const seed = JSON.parse(
  readFileSync(path.join(evidenceDir, "real-stack-seed.json"), "utf8"),
);
const tenant = seed.tenants.alpha;

const endpoints = [
  {
    code: "fake-primary",
    baseUrl: `${modelBase}/v1`,
    environmentLabel: "DEMO_ONLY",
  },
  {
    code: "fake-secondary",
    baseUrl: `${modelBase}/v1-secondary`,
    environmentLabel: "DEMO_ONLY",
  },
];
const endpointHash = createHash("sha256")
  .update(
    JSON.stringify(
      endpoints.map((endpoint) => ({
        code: endpoint.code,
        baseUrl: endpoint.baseUrl,
        environmentLabel: endpoint.environmentLabel,
      })),
    ),
  )
  .digest("hex");

const serviceSecret = `m04-v2-verifier-${randomBytes(24).toString("hex")}`;
const sharedEnv = {
  RICCON_MASTER_KEY: masterKey,
  RICCON_MASTER_KEY_ID: masterKeyId,
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
  RICCON_DB_POOL_SIZE: "25",
  RICCON_FAKE_MODEL: "1",
  SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE: "25",
  RICCON_SERVICE_TOKEN_SECRET: serviceSecret,
};

/** 运行中的子进程；finally 统一回收。 */
const children = [];

/**
 * 启动子进程并写日志。
 */
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

/** 轮询 HTTP 健康检查。 */
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

/** 断言帮助函数。 */
function requireOk(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

/** 读取 seed 的开关版本并整组保存。 */
async function putSwitches(
  client,
  routingMode,
  failoverEnabled,
  quotaEnforcementEnabled,
) {
  const current = await client.get("/api/v1/admin/model-gateway/switches");
  requireOk(
    current.status === 200,
    `switch GET failed: ${current.status} ${current.text}`,
  );
  const saved = await client.request(
    "PUT",
    "/api/v1/admin/model-gateway/switches",
    {
      body: {
        version: current.data.version,
        routingMode,
        failoverEnabled,
        quotaEnforcementEnabled,
      },
    },
  );
  requireOk(
    saved.status === 200,
    `switch PUT failed: ${saved.status} ${saved.text}`,
  );
  return saved.data;
}

/** 提交 CHAT 消息并等待该 run 的 CHAT 调用成功。 */
async function submitChatAndWait(member, conversationId, content) {
  const idempotencyKey = `m04-v2-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  const message = await member.post(
    `/api/v1/conversations/${conversationId}/messages`,
    { content, clientMessageId: idempotencyKey },
    { "Idempotency-Key": idempotencyKey },
  );
  requireOk(
    [200, 201].includes(message.status),
    `chat message rejected: ${message.status} ${message.text.slice(0, 300)}`,
  );
  const match = /"runId":"([0-9a-f-]{36})"/.exec(message.text);
  requireOk(match, "chat stream must expose runId");
  const runId = match[1];
  const detail = await waitFor(
    async () => {
      const response = await member.get(`/api/v1/runs/${runId}`);
      if (response.status !== 200) {
        return null;
      }
      const succeeded = (response.data?.modelInvocations ?? []).some(
        (invocation) =>
          invocation.purpose === "CHAT" && invocation.status === "SUCCEEDED",
      );
      return succeeded ? response : null;
    },
    90_000,
    500,
  );
  return { runId, body: message.text, detail: detail.data };
}

/** 通过 docker compose 执行只读/夹具 SQL。 */
function dockerMysql(sql, extraArgs = []) {
  const result = spawn("docker", [
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
    "--database=riccon",
    "-N",
    "--batch",
    ...extraArgs,
    "-e",
    sql,
  ]);
  let output = "";
  result.stdout.on("data", (chunk) => {
    output += chunk;
  });
  result.stderr.on("data", () => {});
  return new Promise((resolve) => {
    result.on("close", (code) => resolve({ code, output: output.trim() }));
  });
}

const report = {
  generatedAt: new Date().toISOString(),
  checks: {},
  observations: {},
};
let apiClient;
let workerClient;
let holder;
try {
  // ---------- 启动真实进程 ----------
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
      ...sharedEnv,
      RICCON_BOOTSTRAP_ADMIN_USERNAME: "platform-admin",
      RICCON_BOOTSTRAP_ADMIN_PASSWORD: "test-bootstrap-password-12",
      RICCON_API_PORT: "18080",
      RICCON_WORKER_BASE_URL: workerBase,
      RICCON_PUBLIC_BASE_URL: "http://127.0.0.1:8092",
    },
    path.join(runDir, "api.log"),
  );
  start(
    "worker",
    "java",
    ["-jar", "apps/worker/target/riccon-worker-0.1.0-SNAPSHOT-exec.jar"],
    {
      ...sharedEnv,
      RICCON_S3_BUCKET: "riccon-test",
      RICCON_MCP_ALLOW_STDIO: "true",
      RICCON_MCP_ALLOWED_SERVERS: "riccon-builtin",
      RICCON_MCP_ALLOW_HTTP: "true",
      RICCON_WORKER_PORT: "18081",
    },
    path.join(runDir, "worker.log"),
  );
  await waitHealth(`${apiBase}/actuator/health`);
  await waitHealth(`${workerBase}/actuator/health`);
  await waitHealth(`${modelBase}/__health__`, 30_000);
  report.checks.startup = "passed";

  // ---------- AC-18：关闭开关后 J1/J2 仍可用 ----------
  const platform = new ApiClient(apiBase);
  await platform.login(
    "platform",
    "platform-admin",
    "test-bootstrap-password-12",
  );
  const member = new ApiClient(apiBase);
  await member.login(tenant, "alpha-member", "alpha-member-password-12");
  const tenantAdmin = new ApiClient(apiBase);
  await tenantAdmin.login(tenant, "alpha-admin", "alpha-admin-password-12");

  const before = await platform.get("/api/v1/admin/model-gateway/switches");
  requireOk(before.status === 200, `switch GET failed: ${before.status}`);
  report.observations.switchesBefore = {
    routingMode: before.data.routingMode,
    failoverEnabled: before.data.failoverEnabled,
    quotaEnforcementEnabled: before.data.quotaEnforcementEnabled,
    version: before.data.version,
  };
  const closed = await putSwitches(platform, "BASELINE", false, false);
  requireOk(
    closed.routingMode === "BASELINE",
    "routingMode must close to BASELINE",
  );
  requireOk(closed.failoverEnabled === false, "failover must close");
  requireOk(
    closed.quotaEnforcementEnabled === false,
    "quota enforcement must close",
  );
  const afterClose = await platform.get("/api/v1/admin/model-gateway/switches");
  requireOk(
    afterClose.data.version === closed.version,
    "next switch read must not be cached",
  );
  const closeAudits = await dockerMysql(
    `SELECT COUNT(*) FROM audit_event WHERE action = 'model.gateway.switch.changed' AND details LIKE '%DATABASE%'`,
  );
  report.checks.ac18SwitchCloseAudit =
    closeAudits.code === 0 && Number(closeAudits.output) >= 2
      ? "passed"
      : "failed";
  requireOk(
    report.checks.ac18SwitchCloseAudit === "passed",
    `switch audit count: ${closeAudits.output}`,
  );

  const workspaces = await member.get("/api/v1/workspaces?page=1&size=100");
  requireOk(
    workspaces.status === 200 && workspaces.data.items.length > 0,
    "J1 workspace missing",
  );
  const conversation = await member.post(
    `/api/v1/workspaces/${workspaces.data.items[0].id}/conversations`,
    { title: "AC-18 关闭开关后 J1" },
  );
  requireOk(
    conversation.status === 201,
    `J1 conversation failed: ${conversation.status}`,
  );
  const j1 = await submitChatAndWait(
    member,
    conversation.data.id,
    "AC-18：关闭开关后仍应正常回答。",
  );
  const chatInvocation = j1.detail.modelInvocations.find(
    (invocation) => invocation.purpose === "CHAT",
  );
  requireOk(
    chatInvocation?.modelKey,
    "J1 run detail must expose model identifier",
  );
  report.observations.j1 = {
    runId: j1.runId,
    status: j1.detail.status,
    modelKey: chatInvocation.modelKey,
    firstTokenMs: chatInvocation.firstTokenMs,
  };
  requireOk(
    !j1.body.includes("fake-primary") && !j1.body.includes("fake-secondary"),
    "J1 stream must not expose provider names",
  );
  report.checks.ac18J1 = "passed";

  // J2：复用真实栈 seed 的 MCP 目录，独立目录避免覆盖 EV-0646 的产物。
  const j2Seed = path.join(runDir, "real-stack-seed.json");
  copyFileSync(path.join(evidenceDir, "real-stack-seed.json"), j2Seed);
  const j2 = spawn("node", ["scripts/real-stack/j2-journey.mjs"], {
    cwd: repoRoot,
    env: { ...process.env, REAL_SEED_OUT: j2Seed },
    stdio: ["ignore", "pipe", "pipe"],
  });
  let j2Out = "";
  let j2Err = "";
  j2.stdout.on("data", (chunk) => {
    j2Out += chunk;
  });
  j2.stderr.on("data", (chunk) => {
    j2Err += chunk;
  });
  const j2Code = await new Promise((resolve) => j2.on("close", resolve));
  writeFileSync(path.join(runDir, "j2.log"), `${j2Out}\n${j2Err}`);
  requireOk(
    j2Code === 0,
    `AC-18 J2 journey failed (exit ${j2Code}); see ac18/j2.log`,
  );
  report.checks.ac18J2 = "passed";

  // 恢复默认策略（ROUTED/true/false），供后续指标与 perf 使用。
  const restored = await putSwitches(platform, "ROUTED", true, false);
  requireOk(restored.routingMode === "ROUTED", "switch restore failed");

  // ---------- 指标前置流量 ----------
  const quota = await tenantAdmin.get("/api/v1/quotas");
  requireOk(quota.status === 200, `tenant quota read failed: ${quota.status}`);
  const daily = quota.data.windows.find((window) => window.window === "DAILY");
  const used = daily.used;
  await putSwitches(platform, "ROUTED", true, true);
  const limited = await platform.request(
    "PUT",
    `/api/v1/admin/tenants/${tenant}/quotas`,
    {
      body: { limits: { daily: used + 1, monthly: null } },
    },
  );
  requireOk(
    limited.status === 200,
    `quota limit update failed: ${limited.status} ${limited.text}`,
  );
  const accepted = await submitChatAndWait(
    member,
    conversation.data.id,
    "配额拒绝指标前置：第 1 次。",
  );
  const rejected = await member.post(
    `/api/v1/conversations/${conversation.data.id}/messages`,
    {
      content: "配额拒绝指标前置：第 2 次。",
      clientMessageId: `m04-v2-quota-${Date.now()}`,
    },
    { "Idempotency-Key": `m04-v2-quota-${Date.now()}` },
  );
  requireOk(
    rejected.status === 429,
    `second run must be 429, got ${rejected.status}: ${rejected.text.slice(0, 200)}`,
  );
  requireOk(
    rejected.data?.code === "RICCON-QUOTA-429",
    "quota rejection code mismatch",
  );
  report.observations.quotaRejection = {
    acceptedRun: accepted.runId,
    rejectedStatus: rejected.status,
  };

  // 关闭执行但已超限 → over_limit_observed 指标。
  await putSwitches(platform, "ROUTED", true, false);
  const usedAfter = await tenantAdmin.get("/api/v1/quotas");
  const usedNow = usedAfter.data.windows.find(
    (window) => window.window === "DAILY",
  ).used;
  await platform.request("PUT", `/api/v1/admin/tenants/${tenant}/quotas`, {
    body: { limits: { daily: usedNow, monthly: null } },
  });
  await submitChatAndWait(
    member,
    conversation.data.id,
    "仅计量超限前置：关闭执行时仍可运行。",
  );
  await putSwitches(platform, "ROUTED", true, true);
  await platform.request("PUT", `/api/v1/admin/tenants/${tenant}/quotas`, {
    body: { limits: { daily: null, monthly: null } },
  });

  // 成本查询指标：平台 + 租户各一次。
  const costTenant = await tenantAdmin.get(
    "/api/v1/costs?from=2026-11-01&to=2026-11-30&groupBy=DAY",
  );
  requireOk(
    costTenant.status === 200,
    `tenant cost report failed: ${costTenant.status}`,
  );
  const costPlatform = await platform.get(
    "/api/v1/admin/costs?from=2026-11-01&to=2026-11-30&groupBy=DAY",
  );
  requireOk(
    costPlatform.status === 200,
    `platform cost report failed: ${costPlatform.status}`,
  );

  // 配额存储故障指标：持有 tenant 行锁，等待 API 触发 lock_wait。
  holder = spawn(
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
      "--database=riccon",
      "-e",
      `START TRANSACTION; SELECT id FROM tenant WHERE id = '${tenant}' FOR UPDATE; SELECT SLEEP(70); COMMIT;`,
    ],
    { cwd: repoRoot, stdio: "ignore" },
  );
  children.push({ name: "row-lock-holder", child: holder });
  await new Promise((resolve) => setTimeout(resolve, 2500));
  const storeFailure = await member.post(
    `/api/v1/conversations/${conversation.data.id}/messages`,
    {
      content: "配额存储故障前置。",
      clientMessageId: `m04-v2-store-${Date.now()}`,
    },
    { "Idempotency-Key": `m04-v2-store-${Date.now()}` },
  );
  requireOk(
    storeFailure.status === 503 &&
      storeFailure.data?.code === "RICCON-QUOTA-503",
    `quota store failure must be 503 RICCON-QUOTA-503, got ${storeFailure.status}: ${storeFailure.text.slice(0, 200)}`,
  );
  holder.kill("SIGTERM");
  report.checks.quotaStoreFailure = "passed";

  // ---------- API 指标：2 主 + 3 辅助 ----------
  const apiMetrics = await (
    await fetch(`${apiBase}/actuator/prometheus`)
  ).text();
  writeFileSync(path.join(runDir, "api-prometheus.txt"), apiMetrics);
  const metricLines = (name) =>
    apiMetrics
      .split("\n")
      .filter((line) => line.startsWith(name) && !line.startsWith("#"));
  const mainRejections = metricLines("riccon_quota_rejections_total");
  const mainRatio = metricLines("riccon_quota_usage_ratio");
  const auxQueries = metricLines("riccon_cost_report_queries_total");
  const auxStoreFailure = metricLines("riccon_quota_store_failure_total");
  const auxOverLimit = metricLines("riccon_quota_over_limit_observed_total");
  requireOk(
    mainRejections.some(
      (line) =>
        line.includes('scope="TENANT"') && line.includes('window="DAILY"'),
    ),
    "API metric riccon_quota_rejections_total missing TENANT/DAILY",
  );
  requireOk(
    mainRatio.length >= 4,
    "API metric riccon_quota_usage_ratio series missing",
  );
  requireOk(
    auxQueries.some((line) => line.includes('group_by="DAY"')),
    "API metric riccon_cost_report_queries_total missing",
  );
  requireOk(
    auxStoreFailure.length >= 1,
    "API metric riccon_quota_store_failure_total missing",
  );
  requireOk(
    auxOverLimit.length >= 1,
    "API metric riccon_quota_over_limit_observed_total missing",
  );
  const forbiddenLabels = [...mainRejections, ...mainRatio].filter((line) =>
    /tenant|userId|runId/.test(line),
  );
  requireOk(
    forbiddenLabels.length === 0,
    `high-cardinality labels found: ${forbiddenLabels[0]}`,
  );
  report.observations.apiMetrics = {
    rejections: mainRejections.length,
    usageRatio: mainRatio.length,
    costQueries: auxQueries.length,
    storeFailure: auxStoreFailure.length,
    overLimitObserved: auxOverLimit.length,
  };
  report.checks.opsM0402b = "passed";

  // ---------- Worker 7 指标 ----------
  const metricsCheck = spawn(
    "scripts/ops/metrics-check",
    ["--base-url", workerBase],
    {
      cwd: repoRoot,
      env: process.env,
    },
  );
  let metricsOut = "";
  metricsCheck.stdout.on("data", (chunk) => {
    metricsOut += chunk;
  });
  metricsCheck.stderr.on("data", (chunk) => {
    metricsOut += chunk;
  });
  const metricsCode = await new Promise((resolve) =>
    metricsCheck.on("close", resolve),
  );
  writeFileSync(path.join(runDir, "metrics-check.log"), metricsOut);
  requireOk(
    metricsCode === 0,
    `metrics-check failed (exit ${metricsCode}); see ac18/metrics-check.log`,
  );
  report.checks.opsM0402a = "passed";

  // ---------- 回滚窗口固定 WARN ----------
  const warnLog = path.join(runDir, "worker-rollback.log");
  const warnEnv = {
    ...sharedEnv,
    RICCON_S3_BUCKET: "riccon-test",
    RICCON_MCP_ALLOW_STDIO: "true",
    RICCON_MCP_ALLOWED_SERVERS: "riccon-builtin",
    RICCON_MCP_ALLOW_HTTP: "true",
    RICCON_WORKER_PORT: "18082",
    RICCON_DB_POOL_SIZE: "2",
    RICCON_WORKER_SCHEMA_VALIDATION_ENABLED: "false",
  };
  writeFileSync(warnLog, "");
  start(
    "worker-rollback",
    "java",
    ["-jar", "apps/worker/target/riccon-worker-0.1.0-SNAPSHOT-exec.jar"],
    warnEnv,
    warnLog,
  );
  const expectedWarn =
    "RICCON-WORKER-002 schema validation disabled; worker is running in rollback window, expected schema version=11";
  await waitFor(
    async () => readFileSync(warnLog, "utf8").includes("RICCON-WORKER-002"),
    120_000,
    500,
  );
  // 多等 5 秒，确认没有第二条（“恰好一次”）。
  await new Promise((resolve) => setTimeout(resolve, 5000));
  const warnContent = readFileSync(warnLog, "utf8");
  const warnCount = warnContent.split(expectedWarn).length - 1;
  writeFileSync(path.join(runDir, "worker-rollback-warn.txt"), warnContent);
  requireOk(
    warnCount === 1,
    `RICCON-WORKER-002 must appear exactly once, got ${warnCount}`,
  );
  requireOk(
    !warnContent.includes("RICCON-WORKER-001"),
    "rollback window must not emit RICCON-WORKER-001",
  );
  report.checks.workerRollbackWarn = "passed";
  report.observations.workerWarn = expectedWarn;

  report.status = "passed";
  console.log("[m04-v2] runtime verification passed");
  console.log(JSON.stringify(report, null, 2));
} catch (error) {
  report.status = "failed";
  report.error = String(error?.stack ?? error);
  console.error(
    `[m04-v2] runtime verification failed: ${error?.message ?? error}`,
  );
  process.exitCode = 1;
} finally {
  writeFileSync(
    path.join(runDir, "runtime-verify.json"),
    JSON.stringify(report, null, 2),
  );
  for (const { child } of children) {
    if (child && !child.killed) {
      child.kill("SIGTERM");
    }
  }
  // 给子进程 2 秒优雅退出，避免端口残留影响下一次运行。
  await new Promise((resolve) => setTimeout(resolve, 2000));
  for (const { child } of children) {
    if (child && !child.killed) {
      child.kill("SIGKILL");
    }
  }
}
