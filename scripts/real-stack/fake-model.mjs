#!/usr/bin/env node
// 真实栈假 OpenAI 兼容模型服务（B8）。
//
// 该脚本是 `e2e/support/fake-model/server.mjs` 的 CLI 包装：场景表、确定性内容与控制端点
// 全部在共享模块中，B11a/B11b 可直接同进程复用，避免两套 mock 漂移。
//
// 用法：
//   node scripts/real-stack/fake-model.mjs [options]
//   node scripts/real-stack/fake-model.mjs --self-test
//
// 选项：
//   --host <host>                 监听地址（默认 127.0.0.1）
//   --port <port>                 端口（默认 18990；0 表示随机）
//   --scenario <name>             全局场景（默认 ok）
//   --timeout-hold-ms <ms>        timeout 场景保持连接的时长（默认 15000）
//   --first-delay-ms <ms>         首个增量前延迟（默认 60）
//   --chunk-interval-ms <ms>      增量间隔（默认 180）
//   --chunk-count <n>             增量分片数（默认 18）
//   --key-scenarios <json>        key → 场景映射，例如 '{"bad-key":"server_error"}'
//   --help                        打印帮助
//
// 环境变量与选项等价：FAKE_MODEL_HOST/PORT/SCENARIO/TIMEOUT_HOLD_MS/FIRST_DELAY_MS/
// CHUNK_INTERVAL_MS/CHUNK_COUNT/KEY_SCENARIOS。
//
// 控制端点（测试专用）：GET /__health__、GET /__log__、POST /__mode__、POST /__scenario__、
// POST /__keys__、POST /__reset__。场景覆盖 302/401/429/5xx/超时/断流/缺 usage/非法 JSON。
// 退出码：启动失败、自检失败或收到 SIGTERM 之外的致命错误时为非零。

import process from "node:process";

import {
  FIXED_USAGE,
  SCENARIO_NAMES,
  normalizeScenario,
  startFakeModelServer,
} from "../../e2e/support/fake-model/index.mjs";

/** 打印用法。 */
function usage() {
  console.log(`用法: node scripts/real-stack/fake-model.mjs [options]

选项:
  --host <host>                 监听地址，默认 127.0.0.1
  --port <port>                 端口，默认 18990；0 表示随机
  --scenario <name>             全局场景，默认 ok
  --timeout-hold-ms <ms>        timeout 场景保持连接时长，默认 15000
  --first-delay-ms <ms>         首个增量延迟，默认 60
  --chunk-interval-ms <ms>      增量间隔，默认 180
  --chunk-count <n>             增量分片数，默认 18
  --key-scenarios <json>        key → 场景映射（JSON 对象）
  --self-test                   在随机端口启动并验证全部场景确定性
  --help                        打印本帮助

场景: ${SCENARIO_NAMES.join(", ")}（兼容旧别名 ok/slow/error）`);
}

/**
 * 解析命令行参数。
 *
 * @param {string[]} argv 参数
 * @returns {object} 解析结果
 */
function parseArgs(argv) {
  const options = {
    host: process.env.FAKE_MODEL_HOST ?? "127.0.0.1",
    port: Number(process.env.FAKE_MODEL_PORT ?? 18990),
    scenario: process.env.FAKE_MODEL_SCENARIO ?? "ok",
    timeoutHoldMs: Number(process.env.FAKE_MODEL_TIMEOUT_HOLD_MS ?? 15_000),
    firstDelayMs: Number(process.env.FAKE_MODEL_FIRST_DELAY_MS ?? 60),
    chunkIntervalMs: Number(process.env.FAKE_MODEL_CHUNK_INTERVAL_MS ?? 180),
    chunkCount: Number(process.env.FAKE_MODEL_CHUNK_COUNT ?? 18),
    keyScenarios: process.env.FAKE_MODEL_KEY_SCENARIOS
      ? JSON.parse(process.env.FAKE_MODEL_KEY_SCENARIOS)
      : {},
    help: false,
    selfTest: false,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    const next = () => {
      index += 1;
      if (index >= argv.length) {
        throw new Error(`${arg} 缺少参数值`);
      }
      return argv[index];
    };
    switch (arg) {
      case "--host":
        options.host = next();
        break;
      case "--port":
        options.port = Number(next());
        break;
      case "--scenario":
        options.scenario = next();
        break;
      case "--timeout-hold-ms":
        options.timeoutHoldMs = Number(next());
        break;
      case "--first-delay-ms":
        options.firstDelayMs = Number(next());
        break;
      case "--chunk-interval-ms":
        options.chunkIntervalMs = Number(next());
        break;
      case "--chunk-count":
        options.chunkCount = Number(next());
        break;
      case "--key-scenarios":
        options.keyScenarios = JSON.parse(next());
        break;
      case "--self-test":
        options.selfTest = true;
        break;
      case "--help":
      case "-h":
        options.help = true;
        break;
      default:
        throw new Error(`未知参数: ${arg}`);
    }
  }
  if (!normalizeScenario(options.scenario)) {
    throw new Error(`未知场景: ${options.scenario}`);
  }
  return options;
}

/**
 * 断言 helper：失败时抛出带场景名的错误，由自检统一收集。
 *
 * @param {unknown} condition 条件
 * @param {string} message 失败信息
 */
function check(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

/**
 * 运行自检：启动随机端口服务，逐场景验证固定状态/错误码/usage/断流/超时/key 维度。
 *
 * @returns {Promise<object>} 自检报告
 */
async function selfTest() {
  const started = await startFakeModelServer({
    host: "127.0.0.1",
    port: 0,
    globalScenario: "ok",
    firstDelayMs: 0,
    chunkIntervalMs: 0,
    chunkCount: 4,
    timeoutHoldMs: 1_000,
    keyScenarios: {
      "fake-key-bad": "server_error",
      "fake-key-ok": "ok",
    },
  });
  const results = [];

  /**
   * 发送一次 chat/completions 请求。
   *
   * @param {object} [options] scenario/header/key/body/signal
   * @returns {Promise<Response>} 响应
   */
  async function call(options = {}) {
    const headers = { "content-type": "application/json" };
    if (options.scenario) {
      headers["x-fake-model-scenario"] = options.scenario;
    }
    if (options.key) {
      headers.authorization = `Bearer ${options.key}`;
    }
    return fetch(`${started.url}/v1/chat/completions`, {
      method: "POST",
      headers,
      body: JSON.stringify(
        options.body ?? { model: "fake-chat", stream: true, messages: [] },
      ),
      redirect: "manual",
      signal: options.signal,
    });
  }

  /**
   * 记录单场景结果。
   *
   * @param {string} scenario 场景
   * @param {() => Promise<void>} action 断言过程
   */
  async function scenarioResult(scenario, action) {
    try {
      await action();
      results.push({ scenario, status: "passed" });
    } catch (error) {
      results.push({ scenario, status: "failed", error: String(error) });
    }
  }

  try {
    await scenarioResult("unauthorized", async () => {
      const response = await call({ scenario: "unauthorized" });
      check(response.status === 401, `expected 401, got ${response.status}`);
      const body = await response.json();
      check(
        body.error.code === "fake_unauthorized",
        "fixed error code missing",
      );
    });
    await scenarioResult("rate_limited", async () => {
      const response = await call({ scenario: "rate_limited" });
      check(response.status === 429, `expected 429, got ${response.status}`);
      check(
        response.headers.get("retry-after") === "1",
        "Retry-After must be 1",
      );
      const body = await response.json();
      check(
        body.error.code === "fake_rate_limited",
        "fixed error code missing",
      );
    });
    await scenarioResult("server_error", async () => {
      const response = await call({ scenario: "server_error" });
      check(response.status === 500, `expected 500, got ${response.status}`);
      const body = await response.json();
      check(
        body.error.code === "fake_server_error",
        "fixed error code missing",
      );
    });
    await scenarioResult("unavailable", async () => {
      const response = await call({ scenario: "unavailable" });
      check(response.status === 503, `expected 503, got ${response.status}`);
    });
    await scenarioResult("redirect", async () => {
      const response = await call({ scenario: "redirect" });
      check(response.status === 302, `expected 302, got ${response.status}`);
      check(
        response.headers.get("location") === "/v1/redirect-target",
        "redirect target must be fixed",
      );
    });
    await scenarioResult("ok", async () => {
      const response = await call({ scenario: "ok" });
      check(response.status === 200, `expected 200, got ${response.status}`);
      const text = await response.text();
      check(text.includes("data: "), "SSE data frames missing");
      check(text.includes("[DONE]"), "DONE marker missing");
      check(
        text.includes('"prompt_tokens":128'),
        "fixed prompt tokens missing",
      );
      check(
        text.includes('"completion_tokens":96'),
        "fixed completion tokens missing",
      );
    });
    await scenarioResult("missing_usage", async () => {
      const response = await call({ scenario: "missing_usage" });
      check(response.status === 200, `expected 200, got ${response.status}`);
      const text = await response.text();
      check(text.includes("[DONE]"), "DONE marker missing");
      check(!text.includes('"usage"'), "usage must be omitted");
    });
    await scenarioResult("stream_break", async () => {
      const response = await call({ scenario: "stream_break" });
      check(response.status === 200, `expected 200, got ${response.status}`);
      let text = "";
      try {
        text = await response.text();
      } catch {
        // 连接被上游断开属于预期；以已收到的字节继续断言。
      }
      check(!text.includes("[DONE]"), "stream_break must not send DONE");
    });
    await scenarioResult("invalid_json", async () => {
      const response = await call({ scenario: "invalid_json" });
      check(response.status === 200, `expected 200, got ${response.status}`);
      const text = await response.text();
      check(text.includes("this-is-not-json"), "invalid JSON frame missing");
    });
    await scenarioResult("timeout", async () => {
      const controller = new AbortController();
      setTimeout(() => controller.abort(), 250);
      let aborted = false;
      try {
        await call({ scenario: "timeout", signal: controller.signal });
      } catch (error) {
        aborted =
          error?.name === "AbortError" || String(error).includes("aborted");
      }
      check(aborted, "timeout must not respond before client timeout");
    });
    await scenarioResult("tool_call", async () => {
      const response = await call({
        body: {
          model: "fake-chat",
          stream: true,
          messages: [{ role: "user", content: "调研主题：测试主题" }],
          tools: [
            {
              type: "function",
              function: {
                name: "market_data",
                description: "test",
                parameters: { type: "object" },
              },
            },
          ],
        },
      });
      const text = await response.text();
      check(text.includes('"tool_calls"'), "tool_calls missing");
      check(
        text.includes('"quote"') === false,
        "tool_call must not include unrelated content",
      );
    });
    await scenarioResult("key-dimension", async () => {
      const bad = await call({ key: "fake-key-bad" });
      check(bad.status === 500, `bad key expected 500, got ${bad.status}`);
      const good = await call({ key: "fake-key-ok" });
      check(good.status === 200, `good key expected 200, got ${good.status}`);
      const overridden = await call({ key: "fake-key-bad", scenario: "ok" });
      check(overridden.status === 200, "header must override key scenario");
    });
    await scenarioResult("determinism", async () => {
      const first = await (await call({ scenario: "ok" })).text();
      const second = await (await call({ scenario: "ok" })).text();
      check(
        first === second,
        "identical requests must produce identical SSE bodies",
      );
    });
    await scenarioResult("fixed-usage", async () => {
      const log = await (await fetch(`${started.url}/__log__`)).json();
      const okEntry = log.requests.find(
        (entry) => entry.scenario === "ok" && entry.usage,
      );
      check(Boolean(okEntry), "ok request log missing");
      check(
        okEntry.usage.prompt_tokens === FIXED_USAGE.prompt_tokens &&
          okEntry.usage.completion_tokens === FIXED_USAGE.completion_tokens,
        "usage must match fixed constants",
      );
      check(
        log.requests.every((entry) => !Object.hasOwn(entry, "authorization")),
        "log must not contain Authorization plaintext",
      );
    });
  } finally {
    await started.close();
  }

  const failed = results.filter((entry) => entry.status === "failed");
  const report = {
    status: failed.length === 0 ? "passed" : "failed",
    scenarios: results,
    usage: { ...FIXED_USAGE },
  };
  console.log(JSON.stringify(report, null, 2));
  if (failed.length > 0) {
    process.exitCode = 1;
  }
}

const options = parseArgs(process.argv.slice(2));
if (options.help) {
  usage();
} else if (options.selfTest) {
  await selfTest();
} else {
  const started = await startFakeModelServer({
    host: options.host,
    port: options.port,
    globalScenario: options.scenario,
    firstDelayMs: options.firstDelayMs,
    chunkIntervalMs: options.chunkIntervalMs,
    chunkCount: options.chunkCount,
    timeoutHoldMs: options.timeoutHoldMs,
    keyScenarios: options.keyScenarios,
  });
  console.log(
    `fake model listening on ${started.url} scenario=${normalizeScenario(options.scenario)}`,
  );
  /**
   * 优雅退出：关闭监听与所有连接，避免脚本残留。
   *
   * @param {string} signal 信号名
   */
  const shutdown = async (signal) => {
    console.log(`fake model shutting down (${signal})`);
    await started.close();
    process.exit(0);
  };
  process.once("SIGTERM", () => void shutdown("SIGTERM"));
  process.once("SIGINT", () => void shutdown("SIGINT"));
}
