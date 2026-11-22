#!/usr/bin/env node
// 真实栈字符集自检：证明 MySQL 客户端显式 utf8mb4 后，中文写入、回读与 API 读模型一致。
//
// 背景（TASK-0125）：容器内 mysql 8.4 客户端 `--default-character-set=auto` 会按 locale 回退
// latin1；种子中的中文以 UTF-8 字节被当作 latin1 写入，落库即双写（`å‡æ¨¡åž‹`），
// API 用 JDBC utf8mb4 读出为乱码。行数校验发现不了，必须逐字段比对。
//
// 检查项：
// 1) 静态：共享客户端参数包含 `--default-character-set=utf8mb4`；
// 2) 往返：单会话 TEMPORARY TABLE 写入/读取中文，连接字符集与落库字节都必须正确；
// 3) 种子：连续两次 `seed.mjs --mode=catalog`（临时密钥只经 env 传递，不落日志），
//    要求两次 textIntegrity=passed、行数一致，且 DB 展示名与预期中文逐字一致。
//
// 依赖 compose 测试栈的 `mysql` 服务已就绪；不可用时失败，不静默跳过。
// 注意：第 3 步会以本次临时主密钥重写平台 DEK 与凭据；之后应重新执行 full seed 或
// `run.mjs --mode=full`（会重建数据库），否则 API 无法解密旧凭据。
//
// 用法：node scripts/real-stack/charset-check.mjs

import { spawnSync } from "node:child_process";
import { randomBytes, randomUUID } from "node:crypto";
import process from "node:process";

import { mysqlClientArgs, runSql } from "./lib/mysql.mjs";

/** 仓库根目录。 */
const repoRoot = process.cwd();

/** 预期中文样例：与 seed.mjs 目录常量独立书写，避免用同一来源自证。 */
const EXPECTED_MODELS = {
  "0192f0a0-0000-7000-8000-000000000111": "假模型-主（真实栈）",
  "0192f0a0-0000-7000-8000-000000000112": "假模型-备用（真实栈）",
  "0192f0a0-0000-7000-8000-000000000113": "假模型-停用示例（真实栈）",
};

/** 逐项检查结果。 */
const checks = [];
let failed = false;

/**
 * 记录一步检查。
 *
 * @param {string} name 名称
 * @param {boolean} ok 是否通过
 * @param {string} [detail] 细节
 */
function record(name, ok, detail = "") {
  checks.push({ name, status: ok ? "passed" : "failed", detail });
  if (!ok) {
    failed = true;
  }
}

/**
 * 解析 `--batch --raw` 结果集中的单值：取表头后一行。
 *
 * @param {string} stdout 原始输出
 * @param {string} column 列名（表头）
 * @returns {string|null} 值
 */
function valueOf(stdout, column) {
  const lines = stdout.split("\n");
  const headerIndex = lines.indexOf(column);
  return headerIndex >= 0 && headerIndex + 1 < lines.length
    ? lines[headerIndex + 1]
    : null;
}

/**
 * 第一步：静态断言共享客户端参数。
 */
function checkClientArgs() {
  const args = mysqlClientArgs();
  record(
    "client args include --default-character-set=utf8mb4",
    args.includes("--default-character-set=utf8mb4"),
    args.join(" "),
  );
}

/**
 * 第二步：单会话中文往返（TEMPORARY TABLE 只在同一连接内可见）。
 */
function checkRoundTrip() {
  const sample = "假模型-主（真实栈）";
  const expectedHex = Buffer.from(sample, "utf8").toString("hex").toUpperCase();
  const stdout = runSql(`
CREATE TEMPORARY TABLE charset_probe (id INT PRIMARY KEY, label VARCHAR(64) NOT NULL);
INSERT INTO charset_probe (id, label) VALUES (1, '${sample}');
SELECT LOWER(HEX(label)) AS stored_hex FROM charset_probe WHERE id = 1;
SELECT @@character_set_client AS client_charset, @@character_set_connection AS connection_charset;
DROP TEMPORARY TABLE charset_probe;
`).stdout;
  const storedHex = valueOf(stdout, "stored_hex");
  record(
    "round-trip stores exact UTF-8 bytes",
    storedHex === expectedHex.toLowerCase(),
    `expected=${expectedHex} actual=${storedHex}`,
  );
  const charsetLine = valueOf(stdout, "client_charset\tconnection_charset");
  record(
    "session charset is utf8mb4",
    charsetLine === "utf8mb4\tutf8mb4",
    charsetLine ?? "missing",
  );
}

/**
 * 生成 catalog seed 的临时环境；密钥只存在于子进程 env，不写日志或输出。
 *
 * @returns {Record<string, string>} 子进程环境
 */
function buildSeedEnv() {
  return {
    ...process.env,
    RICCON_MASTER_KEY: randomBytes(32).toString("base64"),
    RICCON_CREDENTIAL_FINGERPRINT_PEPPER: randomBytes(32).toString("base64"),
    RICCON_MASTER_KEY_ID: "mk-1",
    REAL_REPO_ROOT: repoRoot,
    REAL_MODEL_KEYS: JSON.stringify({
      "fake-primary": [
        {
          id: "0192f0a0-0000-7000-8000-000000000121",
          label: "主 key（真实栈）",
          key: randomUUID(),
          priority: 10,
        },
      ],
      "fake-secondary": [
        {
          id: "0192f0a0-0000-7000-8000-000000000122",
          label: "备用 key（真实栈）",
          key: randomUUID(),
          priority: 10,
        },
      ],
    }),
  };
}

/**
 * 以给定环境执行一次 catalog seed，返回解析后的摘要。
 *
 * @param {Record<string, string>} env 子进程环境
 * @returns {object} seed 摘要
 */
function runCatalogSeed(env) {
  const result = spawnSync(
    "node",
    ["scripts/real-stack/seed.mjs", "--mode=catalog"],
    {
      cwd: repoRoot,
      env,
      encoding: "utf8",
      maxBuffer: 16 * 1024 * 1024,
    },
  );
  if (result.status !== 0) {
    throw new Error(
      `catalog seed failed (exit ${result.status}): ${result.stderr || result.stdout}`,
    );
  }
  return JSON.parse(result.stdout);
}

/**
 * 第三步：两次 catalog seed 幂等 + 中文展示名逐字回读。
 */
function checkCatalogSeed() {
  const env = buildSeedEnv();
  const first = runCatalogSeed(env);
  const second = runCatalogSeed(env);
  record(
    "seed catalog textIntegrity passed twice",
    first.catalog?.textIntegrity === "passed" &&
      second.catalog?.textIntegrity === "passed",
    `first=${first.catalog?.textIntegrity} second=${second.catalog?.textIntegrity}`,
  );
  record(
    "seed catalog is idempotent",
    first.catalog?.counts === second.catalog?.counts,
    `first=${JSON.stringify(first.catalog?.counts)} second=${JSON.stringify(second.catalog?.counts)}`,
  );

  const ids = Object.keys(EXPECTED_MODELS);
  const rows = runSql(`
SELECT LOWER(HEX(id)) AS id_hex, display_name FROM model
 WHERE id IN (${ids.map((id) => `UNHEX(REPLACE('${id}', '-', ''))`).join(", ")});
`).stdout;
  const actual = new Map();
  for (const line of rows.split("\n")) {
    if (line === "" || line === "id_hex\tdisplay_name") {
      continue;
    }
    const [idHex, ...text] = line.split("\t");
    actual.set(idHex, text.join("\t"));
  }
  const mismatches = ids
    .map((id) => ({ id, expected: EXPECTED_MODELS[id] }))
    .filter(
      ({ id, expected }) =>
        actual.get(id.replaceAll("-", "").toLowerCase()) !== expected,
    );
  record(
    "stored display names match expected Chinese",
    mismatches.length === 0,
    mismatches.length === 0
      ? `${ids.length} models`
      : JSON.stringify(
          mismatches.map(({ id, expected }) => ({
            id,
            expected,
            actual: actual.get(id.replaceAll("-", "").toLowerCase()) ?? null,
          })),
        ),
  );
}

try {
  checkClientArgs();
  checkRoundTrip();
  checkCatalogSeed();
} catch (error) {
  record("charset check executed", false, String(error?.message ?? error));
}

console.log(
  JSON.stringify(
    { status: failed ? "failed" : "passed", checks, sample: EXPECTED_MODELS },
    null,
    2,
  ),
);
if (failed) {
  process.exitCode = 1;
}
