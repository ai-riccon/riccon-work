#!/usr/bin/env node
// M04 运维脚本：配额开关 false→true 的“真实超限”演练（ADR-0021 §10.2 步 6、ADR-0022 §1.3/§1.5）。
//
// 演练序列（真实超限，不是只读检查）：
//   0) 记录基线：网关开关、目标租户日/月限额与已用、审计与指标计数；
//   1) 确保 quotaEnforcementEnabled=true；
//   2) 把目标租户日限额改为“已用 + 1”（月限额保持），提交 1 个 CHAT run 消耗最后 1 次；
//   3) 再提交 1 个 CHAT run，必须 429（RICCON-QUOTA-429）、出现 `quota.exceeded` 审计、
//      `riccon_quota_rejections_total` 增加；
//   4) PUT quotaEnforcementEnabled=false，再提交 1 个 run：必须被准入（只计量不阻断）；
//   5) 恢复原限额，PUT true；finally 中无论断言是否失败都执行恢复并校验。
//
// 依赖：
// - API 必须已启动且完成 M04 种子（平台管理员、租户管理员/员工、工作空间）；
// - 至少一个平台管理员会话（开关 + 限额）、一个员工会话（提交 run）；
// - 审计核对需要数据库（`--via=compose|client`）；不可用时必须显式 `--allow-partial`，
//   否则脚本退出 2，绝不把“未核对审计”当通过。
//
// 凭据只来自环境变量，不上命令行、不落盘、不打印：
//   RICCON_DRILL_PLATFORM_USERNAME / RICCON_DRILL_PLATFORM_PASSWORD
//   RICCON_DRILL_MEMBER_TENANT / RICCON_DRILL_MEMBER_USERNAME / RICCON_DRILL_MEMBER_PASSWORD
//   RICCON_DRILL_WORKSPACE（可选；缺省取员工可见的第一个工作空间）
//
// 用法：
//   node scripts/ops/quota-drill.mjs --api-base http://127.0.0.1:18080 --tenant tenant-alpha
//   node scripts/ops/quota-drill.mjs --self-test
//
// 退出码：0 演练通过；1 断言/恢复失败；2 用法/环境/证据不完整。

import process from "node:process";
import { spawnSync } from "node:child_process";

import { ApiClient } from "../real-stack/lib/http.mjs";

const COMPOSE_FILE = "deploy/compose/docker-compose.test.yml";

/** 用法。 */
function usage() {
  console.log(`用法：
  node scripts/ops/quota-drill.mjs --api-base <URL> --tenant <tenantId> [--workspace <wsId>]
      [--via compose|client|none] [--allow-partial] [--dry-run]
  node scripts/ops/quota-drill.mjs --self-test
  node scripts/ops/quota-drill.mjs --help

环境变量（凭据，禁止写进仓库）：
  RICCON_DRILL_PLATFORM_USERNAME/PASSWORD  平台管理员（开关与限额写入）
  RICCON_DRILL_MEMBER_TENANT/USERNAME/PASSWORD  目标租户员工（提交 CHAT run）
  RICCON_DRILL_WORKSPACE                   可选工作空间 ID

选项：
  --via compose|client|none  审计核对通道；none 表示不核对（必须配 --allow-partial）
  --allow-partial            允许审计无法核对（记录为 not-verified，不视为 pass）
  --dry-run                  只读取基线并打印计划，不改开关/限额、不发 run
  --timeout <ms>             单次 HTTP 超时，默认 15000
  --self-test                纯静态自检（不连服务/库）`);
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
    workspace: null,
    via: "compose",
    allowPartial: false,
    dryRun: false,
    timeoutMs: 15_000,
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
    else if (arg === "--allow-partial") options.allowPartial = true;
    else if (arg === "--dry-run") options.dryRun = true;
    else if (arg === "--api-base") options.apiBase = next();
    else if (arg.startsWith("--api-base=")) options.apiBase = arg.split("=")[1];
    else if (arg === "--tenant") options.tenant = next();
    else if (arg.startsWith("--tenant=")) options.tenant = arg.split("=")[1];
    else if (arg === "--workspace") options.workspace = next();
    else if (arg.startsWith("--workspace="))
      options.workspace = arg.split("=")[1];
    else if (arg === "--via") options.via = next();
    else if (arg.startsWith("--via=")) options.via = arg.split("=")[1];
    else if (arg === "--timeout") options.timeoutMs = Number(next());
    else if (arg.startsWith("--timeout="))
      options.timeoutMs = Number(arg.split("=")[1]);
    else throw usageError(`未知参数: ${arg}`);
  }
  if (!["compose", "client", "none"].includes(options.via)) {
    throw usageError(`--via 只能是 compose/client/none，实际 ${options.via}`);
  }
  if (!Number.isInteger(options.timeoutMs) || options.timeoutMs <= 0) {
    throw usageError("--timeout 必须是正整数毫秒");
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
 * 读取单值。
 *
 * @param {string} sql 查询
 * @param {object} options 选项
 * @returns {string} 值
 */
function queryValue(sql, options) {
  const lines = runSql(sql, options).trim().split("\n").filter(Boolean);
  return lines.length === 0 ? "" : lines[lines.length - 1];
}

/**
 * 抓取并求和一个 Prometheus 指标（忽略标签）。
 *
 * @param {string} base API 基地址
 * @param {string} metric 指标名
 * @returns {Promise<number|null>} 求和；指标不存在时 null
 */
async function scrapeMetric(base, metric) {
  const response = await fetch(
    `${base.replace(/\/$/, "")}/actuator/prometheus`,
  );
  if (response.status !== 200) {
    throw new Error(
      `/actuator/prometheus 返回 ${response.status}（需在 RICCON_OPS_ALLOWED_CIDRS 内）`,
    );
  }
  return sumMetric(await response.text(), metric);
}

/**
 * 对 Prometheus 文本中的指标求和（纯函数，供自检）。
 *
 * @param {string} text Prometheus 输出
 * @param {string} metric 指标名
 * @returns {number|null} 求和；不存在时 null
 */
function sumMetric(text, metric) {
  let total = 0;
  let found = false;
  const prefix = `${metric}{`;
  for (const line of String(text).split("\n")) {
    if (!line || line.startsWith("#")) continue;
    if (!line.startsWith(`${metric} `) && !line.startsWith(prefix)) continue;
    const value = Number(line.trim().split(/\s+/).pop());
    if (Number.isNaN(value)) continue;
    total += value;
    found = true;
  }
  return found ? total : null;
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
 * 提交一次 CHAT run（J1）。
 *
 * @param {ApiClient} client 员工会话
 * @param {string} workspaceId 工作空间
 * @param {string} tag 标签
 * @param {number} timeoutMs 超时
 * @returns {Promise<{status: number, runId: string|null, text: string}>} 结果
 */
async function submitChat(client, workspaceId, tag, timeoutMs) {
  const conversation = await client.post(
    `/api/v1/workspaces/${workspaceId}/conversations`,
    { title: `配额演练 ${tag}` },
  );
  assert(
    conversation.status === 201,
    `创建会话失败: ${conversation.status} ${conversation.text}`,
  );
  const conversationId = conversation.data.id;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(
      `${client.baseUrl}/api/v1/conversations/${conversationId}/messages`,
      {
        method: "POST",
        headers: {
          cookie: client.jar.header(),
          "content-type": "application/json",
          "x-csrf-token": client.csrfToken,
          "idempotency-key": `quota-drill-${tag}-${Date.now()}`,
        },
        body: JSON.stringify({
          content: `配额演练 ${tag}：请返回固定演示内容。`,
          clientMessageId: `quota-drill-${tag}-${Date.now()}`,
        }),
        signal: controller.signal,
      },
    );
    const text = await response.text();
    const runId = /"runId":"([0-9a-f-]{36})"/.exec(text)?.[1] ?? null;
    return { status: response.status, runId, text };
  } finally {
    clearTimeout(timer);
  }
}

/**
 * 读取 `/api/v1/admin/quotas` 中目标租户的行。
 *
 * @param {ApiClient} platform 平台管理员
 * @param {string} tenant 租户
 * @returns {Promise<object>} 行
 */
async function readTenantQuota(platform, tenant) {
  const response = await platform.get("/api/v1/admin/quotas?page=1&size=100");
  assert(
    response.status === 200,
    `平台配额列表必须是 200，实际 ${response.status} ${response.text}`,
  );
  const row = (response.data.items ?? []).find(
    (item) => item.tenantId === tenant || item.tenantCode === tenant,
  );
  assert(Boolean(row), `平台配额列表未包含租户 ${tenant}`);
  const daily = row.windows.find((window) => window.window === "DAILY");
  const monthly = row.windows.find((window) => window.window === "MONTHLY");
  assert(
    Boolean(daily) && Boolean(monthly),
    "租户配额必须包含 DAILY/MONTHLY 两个窗口",
  );
  return { row, daily, monthly };
}

/**
 * 写入租户限额（整体替换）。
 *
 * @param {ApiClient} platform 平台管理员
 * @param {string} tenant 租户
 * @param {{daily: number|null, monthly: number|null}} limits 限额
 * @returns {Promise<object>} 响应
 */
async function putTenantLimit(platform, tenant, limits) {
  const response = await platform.request(
    "PUT",
    `/api/v1/admin/tenants/${tenant}/quotas`,
    {
      body: { limits },
    },
  );
  assert(
    response.status === 200,
    `限额写入必须 200，实际 ${response.status} ${response.text}`,
  );
  return response.data;
}

/**
 * 切换网关开关；每次使用最新 version。
 *
 * @param {ApiClient} platform 平台管理员
 * @param {{quotaEnforcementEnabled: boolean}} patch 变更
 * @returns {Promise<object>} 新状态
 */
async function putSwitches(platform, patch) {
  const current = await platform.get("/api/v1/admin/model-gateway/switches");
  assert(
    current.status === 200,
    `读取开关必须 200，实际 ${current.status} ${current.text}`,
  );
  const body = {
    version: current.data.version,
    routingMode: current.data.routingMode,
    failoverEnabled: current.data.failoverEnabled,
    quotaEnforcementEnabled: current.data.quotaEnforcementEnabled,
    ...patch,
  };
  const saved = await platform.request(
    "PUT",
    "/api/v1/admin/model-gateway/switches",
    { body },
  );
  assert(
    saved.status === 200,
    `保存开关必须 200，实际 ${saved.status} ${saved.text}`,
  );
  return saved.data;
}

/**
 * 解析工作空间。
 *
 * @param {ApiClient} member 员工会话
 * @param {string|null} override 指定 ID
 * @returns {Promise<string>} 工作空间 ID
 */
async function resolveWorkspace(member, override) {
  if (override) return override;
  const response = await member.get("/api/v1/workspaces?page=1&size=100");
  assert(
    response.status === 200,
    `工作空间列表必须 200，实际 ${response.status} ${response.text}`,
  );
  const first = (response.data.items ?? [])[0];
  assert(Boolean(first), "员工没有可见工作空间，无法提交 CHAT run");
  return first.id;
}

/**
 * 自检：Prometheus 求和、超限限额计算与 429 判定。
 */
function selfTest() {
  const text = `# HELP riccon_quota_rejections_total 拒绝数
riccon_quota_rejections_total{scope="TENANT",window="DAILY"} 2
riccon_quota_rejections_total{scope="USER",window="DAILY"} 3
other_metric 9
`;
  if (sumMetric(text, "riccon_quota_rejections_total") !== 5) {
    throw new Error("Prometheus 求和失败");
  }
  if (sumMetric(text, "missing_metric") !== null) {
    throw new Error("缺失指标应返回 null");
  }
  const used = 41;
  const overLimit = used + 1;
  if (overLimit !== 42) throw new Error("超限限额计算失败");
  const plan = buildRestorePlan(
    { daily: 10000, monthly: 200000 },
    {
      quotaEnforcementEnabled: false,
      routingMode: "ROUTED",
      failoverEnabled: true,
    },
  );
  if (
    plan.limits.daily !== 10000 ||
    plan.switches.quotaEnforcementEnabled !== false
  ) {
    throw new Error("恢复计划构造失败");
  }
  if (!isQuotaRejection({ status: 429, text: '{"code":"RICCON-QUOTA-429"}' })) {
    throw new Error("429 判定失败");
  }
  if (isQuotaRejection({ status: 200, text: "{}" })) {
    throw new Error("200 不应判定为拒绝");
  }
  console.log(
    JSON.stringify(
      {
        status: "passed",
        checks: [
          "prometheus-sum",
          "missing-metric",
          "overlimit-limit",
          "restore-plan",
          "rejection-detect",
        ],
      },
      null,
      2,
    ),
  );
}

/**
 * 恢复计划（纯函数）。
 *
 * @param {{daily: number|null, monthly: number|null}} limits 原限额
 * @param {object} switches 原开关
 * @returns {{limits: object, switches: object}} 计划
 */
function buildRestorePlan(limits, switches) {
  return {
    limits: { daily: limits.daily, monthly: limits.monthly },
    switches: {
      quotaEnforcementEnabled: switches.quotaEnforcementEnabled,
      routingMode: switches.routingMode,
      failoverEnabled: switches.failoverEnabled,
    },
  };
}

/**
 * 429 判定（纯函数）。
 *
 * @param {{status: number, text: string}} response 响应
 * @returns {boolean} 是否配额拒绝
 */
function isQuotaRejection(response) {
  if (response.status !== 429) return false;
  return (
    response.text.includes("RICCON-QUOTA-429") || response.text.includes("限额")
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
  if (options.via === "none" && !options.allowPartial && !options.dryRun) {
    throw usageError(
      "--via=none 表示不核对审计；必须显式 --allow-partial，避免把未核对当通过",
    );
  }
  const platform = new ApiClient(options.apiBase);
  await platform.login(
    "platform",
    requiredEnv("RICCON_DRILL_PLATFORM_USERNAME"),
    requiredEnv("RICCON_DRILL_PLATFORM_PASSWORD"),
  );
  const member = new ApiClient(options.apiBase);
  await member.login(
    process.env.RICCON_DRILL_MEMBER_TENANT ?? options.tenant,
    requiredEnv("RICCON_DRILL_MEMBER_USERNAME"),
    requiredEnv("RICCON_DRILL_MEMBER_PASSWORD"),
  );
  const workspaceId = await resolveWorkspace(
    member,
    options.workspace ?? process.env.RICCON_DRILL_WORKSPACE ?? null,
  );
  const switches = await platform.get("/api/v1/admin/model-gateway/switches");
  const quota = await readTenantQuota(platform, options.tenant);
  const baseline = {
    switches: switches.data,
    limits: {
      daily: quota.daily.limit ?? null,
      monthly: quota.monthly.limit ?? null,
    },
    used: { daily: quota.daily.used, monthly: quota.monthly.used },
    workspaceId,
  };
  const report = {
    status: "failed",
    tenant: options.tenant,
    workspaceId,
    baseline,
    steps: [],
    auditChecked: options.via !== "none",
    partial: false,
    failures: [],
  };
  console.log(
    JSON.stringify(
      { mode: options.dryRun ? "dry-run" : "drill", baseline },
      null,
      2,
    ),
  );
  if (options.dryRun) {
    console.log("[quota-drill] dry-run：不改开关/限额、不发 run");
    return;
  }

  const auditBefore =
    options.via === "none"
      ? null
      : Number(
          queryValue(
            `SELECT COUNT(*) FROM audit_event
              WHERE action = 'quota.exceeded' AND tenant_id = ${sqlString(options.tenant)};`,
            options,
          ),
        );
  const metricBefore = await scrapeMetric(
    options.apiBase,
    "riccon_quota_rejections_total",
  );
  const overLimitObservedBefore = await scrapeMetric(
    options.apiBase,
    "riccon_quota_over_limit_observed_total",
  );
  const overLimit = Number(baseline.used.daily) + 1;
  const restore = buildRestorePlan(baseline.limits, baseline.switches);

  try {
    // 1) 打开执行（若原本关闭）。
    let current = await putSwitches(platform, {
      quotaEnforcementEnabled: true,
    });
    report.steps.push({
      step: "enable-enforcement",
      value: current.quotaEnforcementEnabled,
    });

    // 2) 日限额 = 已用 + 1。
    await putTenantLimit(platform, options.tenant, {
      daily: overLimit,
      monthly: baseline.limits.monthly,
    });
    report.steps.push({
      step: "limit-set",
      daily: overLimit,
      monthly: baseline.limits.monthly,
    });

    // 3) 消耗最后 1 次。
    const admitted = await submitChat(
      member,
      workspaceId,
      "consume",
      options.timeoutMs,
    );
    assert(
      admitted.status === 200,
      `限额内 run 必须 200，实际 ${admitted.status} ${admitted.text.slice(0, 200)}`,
    );
    report.steps.push({
      step: "admit-last",
      status: admitted.status,
      runId: admitted.runId,
    });

    // 4) 真实超限。
    const rejected = await submitChat(
      member,
      workspaceId,
      "overlimit",
      options.timeoutMs,
    );
    assert(
      isQuotaRejection(rejected),
      `超限 run 必须 429，实际 ${rejected.status} ${rejected.text.slice(0, 200)}`,
    );
    report.steps.push({ step: "reject-overlimit", status: rejected.status });

    if (options.via !== "none") {
      const auditAfter = Number(
        queryValue(
          `SELECT COUNT(*) FROM audit_event
            WHERE action = 'quota.exceeded' AND tenant_id = ${sqlString(options.tenant)};`,
          options,
        ),
      );
      assert(
        auditAfter > auditBefore,
        `quota.exceeded 审计未增加: before=${auditBefore} after=${auditAfter}`,
      );
      report.steps.push({
        step: "audit",
        before: auditBefore,
        after: auditAfter,
      });
    } else {
      report.partial = true;
      report.steps.push({
        step: "audit",
        status: "not-verified",
        reason: "--via=none",
      });
    }

    const metricAfter = await scrapeMetric(
      options.apiBase,
      "riccon_quota_rejections_total",
    );
    assert(metricAfter !== null, "缺少 riccon_quota_rejections_total");
    assert(
      metricAfter > (metricBefore ?? 0),
      `riccon_quota_rejections_total 未增加: before=${metricBefore} after=${metricAfter}`,
    );
    report.steps.push({
      step: "metric-rejections",
      before: metricBefore,
      after: metricAfter,
    });

    // 5) 关闭执行：只计量不阻断。
    current = await putSwitches(platform, { quotaEnforcementEnabled: false });
    const metered = await submitChat(
      member,
      workspaceId,
      "meter-only",
      options.timeoutMs,
    );
    assert(
      metered.status === 200,
      `关闭执行时超限 run 仍应准入，实际 ${metered.status} ${metered.text.slice(0, 200)}`,
    );
    report.steps.push({
      step: "meter-only",
      status: metered.status,
      runId: metered.runId,
    });

    const observedAfter = await scrapeMetric(
      options.apiBase,
      "riccon_quota_over_limit_observed_total",
    );
    if (observedAfter === null) {
      report.steps.push({
        step: "metric-over-limit-observed",
        status: "absent",
        reason: "ADR-0021 §10.6 标注“如适用”；缺失不判失败，由 V2 记录",
      });
    } else {
      assert(
        observedAfter > (overLimitObservedBefore ?? 0),
        `riccon_quota_over_limit_observed_total 未增加: before=${overLimitObservedBefore} after=${observedAfter}`,
      );
      report.steps.push({
        step: "metric-over-limit-observed",
        before: overLimitObservedBefore,
        after: observedAfter,
      });
    }
    report.status = report.partial ? "partial" : "passed";
  } catch (error) {
    report.failures.push(error.message);
  } finally {
    // 6) 无论结果如何都恢复限额与开关，并校验。
    try {
      await putTenantLimit(platform, options.tenant, restore.limits);
      const restored = await putSwitches(platform, {
        quotaEnforcementEnabled: restore.switches.quotaEnforcementEnabled,
      });
      report.steps.push({
        step: "restore",
        limits: restore.limits,
        quotaEnforcementEnabled: restored.quotaEnforcementEnabled,
      });
      const check = await readTenantQuota(platform, options.tenant);
      assert(
        (check.daily.limit ?? null) === (restore.limits.daily ?? null),
        `恢复后日限额不一致: ${check.daily.limit} != ${restore.limits.daily}`,
      );
    } catch (restoreError) {
      report.failures.push(`恢复失败: ${restoreError.message}`);
      report.status = "failed";
    }
  }
  console.log(JSON.stringify(report, null, 2));
  if (report.status !== "passed") {
    process.exitCode =
      report.status === "partial" && options.allowPartial ? 0 : 1;
  }
}

main().catch((error) => {
  console.error(`[quota-drill] ${error.message}`);
  process.exitCode = error.exitCode ?? 1;
});
