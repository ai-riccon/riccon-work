#!/usr/bin/env node
// M04 运维脚本：主密钥轮换的 DEK rewrap 与凭据可解密校验（ADR-0006、ADR-0021 §6.5/§10.2）。
//
// 语义：
// - 信封是“主密钥包裹 DEK，DEK 加密凭据”（`tenant_data_key` + `provider_credential.ciphertext`）。
//   轮换主密钥只需要用旧主密钥解包 DEK、再用新主密钥重新包裹，**不重写凭据密文、不改 pepper**；
// - 默认 dry-run：只读校验（每个 provider 抽一行凭据解密并重算 fingerprint），不写库；
// - `--apply`：对 `master_key_id` 不等于目标 ID 的 `tenant_data_key` 行做 rewrap，逐行校验回读；
// - `--verify-only`：不写库，供 restore-drill 复用为“恢复库一行 credential 可解密”的判据。
//
// 安全：
// - 主密钥/pepper 只来自环境变量；明文 key 只在进程内存中短暂存在，绝不打印、不写文件；
//   任何输出只含 tenant/key_id/provider 代码与布尔结果；
// - 双密钥窗口：`RICCON_MASTER_KEY`（新）+ `RICCON_MASTER_KEY_PREVIOUS`（旧）；两者都不进日志；
// - 退出码：0 成功/通过；1 校验或 SQL 失败；2 用法或环境错误。失败一律非 0，不静默跳过。
//
// 用法：
//   node scripts/ops/rewrap-credentials.mjs --verify-only [--via compose|client] [--database riccon]
//   node scripts/ops/rewrap-credentials.mjs --apply [--limit 50]
//   node scripts/ops/rewrap-credentials.mjs --self-test

import {
  createCipheriv,
  createDecipheriv,
  createHmac,
  randomBytes,
} from "node:crypto";
import process from "node:process";
import { spawnSync } from "node:child_process";

const COMPOSE_FILE = "deploy/compose/docker-compose.test.yml";

/** 信封 schema 版本；AAD 以 tenant|purpose|keyId|version 绑定（与 Worker EnvelopeDecryptor 一致）。 */
const ENVELOPE_VERSION = 1;

/** 用法。 */
function usage() {
  console.log(`用法：
  node scripts/ops/rewrap-credentials.mjs --verify-only [--database <db>]
  node scripts/ops/rewrap-credentials.mjs --apply [--limit <行数>]
  node scripts/ops/rewrap-credentials.mjs --self-test
  node scripts/ops/rewrap-credentials.mjs --help

环境变量（缺任一必需项即退出 2）：
  RICCON_MASTER_KEY                 新主密钥（Base64 32 字节）
  RICCON_MASTER_KEY_ID              新主密钥标识（默认 mk-1）
  RICCON_MASTER_KEY_PREVIOUS        旧主密钥（仅 --apply 需要）
  RICCON_CREDENTIAL_FINGERPRINT_PEPPER  pepper（校验 fingerprint，不随主密钥轮换）

选项：
  --apply       执行 rewrap；缺省 dry-run（只读校验）
  --verify-only 只校验可解密与 fingerprint，不写库
  --limit <n>   最多处理 n 行 tenant_data_key（默认全部）
  --database <db>  覆盖数据库名（restore-drill 用 riccon_restore_*）
  --via compose|client   compose=测试栈容器；client=RICCON_DB_* + PATH 上的 mysql
  --self-test   纯静态自检（随机密钥往返，不连库）`);
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
    verifyOnly: false,
    limit: null,
    database: null,
    via: "compose",
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
    else if (arg === "--verify-only") options.verifyOnly = true;
    else if (arg === "--limit") options.limit = Number(next());
    else if (arg.startsWith("--limit="))
      options.limit = Number(arg.split("=")[1]);
    else if (arg === "--database") options.database = next();
    else if (arg.startsWith("--database="))
      options.database = arg.split("=")[1];
    else if (arg === "--via") options.via = next();
    else if (arg.startsWith("--via=")) options.via = arg.split("=")[1];
    else throw usageError(`未知参数: ${arg}`);
  }
  if (!["compose", "client"].includes(options.via)) {
    throw usageError(`--via 只能是 compose 或 client，实际 ${options.via}`);
  }
  if (options.apply && options.verifyOnly) {
    throw usageError("--apply 与 --verify-only 不能同时使用");
  }
  if (
    options.limit !== null &&
    (!Number.isInteger(options.limit) || options.limit <= 0)
  ) {
    throw usageError("--limit 必须是正整数");
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
 * 读取必需的 Base64 密钥。
 *
 * @param {string} name 变量名
 * @returns {Buffer} 32 字节密钥
 */
function readMasterKey(name) {
  const raw = process.env[name];
  if (!raw) {
    throw envError(`缺少 ${name}（Base64 32 字节）`);
  }
  const key = Buffer.from(raw, "base64");
  if (key.length !== 32) {
    throw envError(
      `${name} 必须是 Base64 编码的 32 字节密钥，实际 ${key.length} 字节`,
    );
  }
  return key;
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
      database: options.database ?? "riccon",
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
    database: options.database ?? process.env.RICCON_DB_DATABASE ?? "riccon",
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
    maxBuffer: 32 * 1024 * 1024,
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
 * 解析 batch 输出。
 *
 * @param {string} stdout 输出
 * @returns {Array<Record<string,string>>} 行
 */
function parseRows(stdout) {
  const lines = stdout.trim().split("\n").filter(Boolean);
  if (lines.length === 0) return [];
  const header = lines[0].split("\t");
  return lines.slice(1).map((line) => {
    const cells = line.split("\t");
    return Object.fromEntries(
      header.map((name, index) => [name, cells[index] ?? ""]),
    );
  });
}

/**
 * AES-256-GCM 封装。
 *
 * @param {Buffer} key 32 字节密钥
 * @param {Buffer} nonce 12 字节
 * @param {Buffer} plaintext 明文
 * @param {string} aad 附加认证数据
 * @returns {Buffer} 密文 + tag
 */
function gcmSeal(key, nonce, plaintext, aad) {
  const cipher = createCipheriv("aes-256-gcm", key, nonce);
  cipher.setAAD(Buffer.from(aad, "utf8"));
  return Buffer.concat([
    cipher.update(plaintext),
    cipher.final(),
    cipher.getAuthTag(),
  ]);
}

/**
 * AES-256-GCM 解封。
 *
 * @param {Buffer} key 32 字节密钥
 * @param {Buffer} nonce 12 字节
 * @param {Buffer} payload 密文 + tag
 * @param {string} aad 附加认证数据
 * @returns {Buffer} 明文
 */
function gcmOpen(key, nonce, payload, aad) {
  const tag = payload.subarray(payload.length - 16);
  const body = payload.subarray(0, payload.length - 16);
  const decipher = createDecipheriv("aes-256-gcm", key, nonce);
  decipher.setAAD(Buffer.from(aad, "utf8"));
  decipher.setAuthTag(tag);
  return Buffer.concat([decipher.update(body), decipher.final()]);
}

/**
 * AAD；与 API/Worker 逐字一致。
 *
 * @param {string} tenantId 租户
 * @param {string} purpose 用途
 * @param {string} keyId DEK 标识
 * @returns {string} AAD
 */
function envelopeAad(tenantId, purpose, keyId) {
  return `${tenantId}|${purpose}|${keyId}|${ENVELOPE_VERSION}`;
}

/**
 * fingerprint：HMAC-SHA-256(pepper, providerId || 0x00 || 明文 key)。
 *
 * @param {Buffer} pepper pepper
 * @param {string} providerId provider UUID 字符串
 * @param {string} apiKey 明文 key
 * @returns {string} 十六进制
 */
function fingerprint(pepper, providerId, apiKey) {
  return createHmac("sha256", pepper)
    .update(providerId, "utf8")
    .update(Buffer.from([0]))
    .update(apiKey, "utf8")
    .digest("hex");
}

/**
 * 解析 ciphertext 信封。
 *
 * @param {string} ciphertext `enc:v1:<keyId>:<base64>`
 * @returns {{keyId: string, nonce: Buffer, payload: Buffer}} 解析结果
 */
function parseCiphertext(ciphertext) {
  const match = /^enc:v1:([^:]+):(.+)$/.exec(ciphertext);
  if (!match) {
    throw new Error("ciphertext 不是 enc:v1 信封");
  }
  const raw = Buffer.from(match[2], "base64");
  return {
    keyId: match[1],
    nonce: raw.subarray(0, 12),
    payload: raw.subarray(12),
  };
}

/**
 * HEX 转 UUID 字符串。
 *
 * @param {string} hex 32 位十六进制
 * @returns {string} UUID
 */
function hexToUuid(hex) {
  const text = hex.toLowerCase();
  return `${text.slice(0, 8)}-${text.slice(8, 12)}-${text.slice(12, 16)}-${text.slice(16, 20)}-${text.slice(20)}`;
}

/**
 * 读取 tenant_data_key 行。
 *
 * @param {object} options 选项
 * @returns {Array<object>} 行
 */
function loadDekRows(options) {
  const limit = options.limit ? ` LIMIT ${options.limit}` : "";
  const rows = parseRows(
    runSql(
      `SELECT tenant_id, key_id, purpose, HEX(wrapped_dek) AS wrapped_dek_hex,
              HEX(wrap_nonce) AS wrap_nonce_hex, master_key_id
         FROM tenant_data_key
        WHERE retired_at IS NULL
        ORDER BY tenant_id, key_id${limit};`,
      options,
    ),
  );
  return rows.map((row) => ({
    tenantId: row.tenant_id,
    keyId: row.key_id,
    purpose: row.purpose,
    wrappedDek: Buffer.from(row.wrapped_dek_hex, "hex"),
    wrapNonce: Buffer.from(row.wrap_nonce_hex, "hex"),
    masterKeyId: row.master_key_id,
  }));
}

/**
 * 读一行凭据用于可解密校验；只返回密文与标识。
 *
 * @param {object} options 选项
 * @returns {Array<object>} 每 provider 一行
 */
function loadCredentialSamples(options) {
  const rows = parseRows(
    runSql(
      `SELECT pc.tenant_id, HEX(pc.provider_id) AS provider_id_hex, pc.credential_type,
              pc.key_id, pc.ciphertext, pc.fingerprint
         FROM provider_credential pc
         JOIN (SELECT tenant_id, provider_id, credential_type, MIN(created_at) AS first_created
                 FROM provider_credential
                WHERE status IN ('ACTIVE', 'DISABLED')
                GROUP BY tenant_id, provider_id, credential_type) pick
           ON pick.tenant_id = pc.tenant_id AND pick.provider_id = pc.provider_id
          AND pick.credential_type = pc.credential_type AND pick.first_created = pc.created_at
        ORDER BY pc.tenant_id, pc.provider_id;`,
      options,
    ),
  );
  const seen = new Set();
  return rows.filter((row) => {
    const key = `${row.tenant_id}/${row.provider_id_hex}/${row.credential_type}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

/**
 * 校验一行凭据可解密且 fingerprint 匹配。
 *
 * @param {object} sample 凭据行
 * @param {Map<string, Buffer>} deks keyId -> DEK
 * @param {Buffer} pepper pepper
 * @returns {{ok: boolean, reason?: string}} 结果
 */
function verifyCredential(sample, deks, pepper) {
  const parsed = parseCiphertext(sample.ciphertext);
  const dek = deks.get(parsed.keyId);
  if (!dek) {
    return { ok: false, reason: `缺少 DEK: ${parsed.keyId}` };
  }
  let plaintext;
  try {
    plaintext = gcmOpen(
      dek,
      parsed.nonce,
      parsed.payload,
      envelopeAad(sample.tenant_id, "model-credential", parsed.keyId),
    ).toString("utf8");
  } catch {
    return { ok: false, reason: "DEK 解密凭据失败（AAD/密钥不匹配）" };
  }
  const expected = fingerprint(
    pepper,
    hexToUuid(sample.provider_id_hex),
    plaintext,
  );
  if (sample.fingerprint && expected !== sample.fingerprint) {
    return {
      ok: false,
      reason: "fingerprint 与明文不匹配（pepper 或 provider 不一致）",
    };
  }
  return { ok: true };
}

/**
 * 纯逻辑自检：信封往返、错误 AAD 拒绝、fingerprint 一致。
 */
function selfTest() {
  const masterA = randomBytes(32);
  const masterB = randomBytes(32);
  const dek = randomBytes(32);
  const pepper = randomBytes(32);
  const tenant = "platform";
  const keyId = "mk-dek-1";
  const aad = envelopeAad(tenant, "model-credential", keyId);
  const apiKey = "self-test-key-0001";
  const providerId = "0192f0a0-0000-7000-8000-000000000101";

  const wrapNonce = randomBytes(12);
  const wrappedA = gcmSeal(masterA, wrapNonce, dek, aad);
  const unwrapped = gcmOpen(masterA, wrapNonce, wrappedA, aad);
  if (!unwrapped.equals(dek)) throw new Error("DEK 往返失败");

  const rewrappedNonce = randomBytes(12);
  const wrappedB = gcmSeal(masterB, rewrappedNonce, unwrapped, aad);
  if (!gcmOpen(masterB, rewrappedNonce, wrappedB, aad).equals(dek)) {
    throw new Error("rewrap 后 DEK 不一致");
  }

  const credNonce = randomBytes(12);
  const payload = gcmSeal(dek, credNonce, Buffer.from(apiKey, "utf8"), aad);
  const ciphertext = `enc:v1:${keyId}:${Buffer.concat([credNonce, payload]).toString("base64")}`;
  const parsed = parseCiphertext(ciphertext);
  const decrypted = gcmOpen(dek, parsed.nonce, parsed.payload, aad).toString(
    "utf8",
  );
  if (decrypted !== apiKey) throw new Error("凭据往返失败");

  const wrongAad = envelopeAad("tenant-other", "model-credential", keyId);
  let rejected = false;
  try {
    gcmOpen(dek, parsed.nonce, parsed.payload, wrongAad);
  } catch {
    rejected = true;
  }
  if (!rejected) throw new Error("错误 AAD 未被拒绝");

  const expected = fingerprint(pepper, providerId, apiKey);
  const sample = {
    tenant_id: tenant,
    provider_id_hex: providerId.replaceAll("-", ""),
    credential_type: "API_KEY",
    key_id: keyId,
    ciphertext,
    fingerprint: expected,
  };
  const ok = verifyCredential(sample, new Map([[keyId, dek]]), pepper);
  if (!ok.ok) throw new Error(`fingerprint 校验失败: ${ok.reason}`);

  console.log(
    JSON.stringify(
      {
        status: "passed",
        checks: [
          "dek-roundtrip",
          "rewrap-roundtrip",
          "credential-roundtrip",
          "aad-reject",
          "fingerprint-match",
        ],
      },
      null,
      2,
    ),
  );
}

/**
 * 取 DEK 映射（用当前主密钥解包所有行）。
 *
 * @param {Array<object>} rows tenant_data_key 行
 * @param {Buffer} masterKey 主密钥
 * @returns {Map<string, Buffer>} DEK 映射
 */
function unwrapDeks(rows, masterKey) {
  const deks = new Map();
  for (const row of rows) {
    deks.set(
      row.keyId,
      gcmOpen(
        masterKey,
        row.wrapNonce,
        row.wrappedDek,
        envelopeAad(row.tenantId, row.purpose, row.keyId),
      ),
    );
  }
  return deks;
}

/** 主流程。 */
function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) {
    usage();
    return;
  }
  if (options.selfTest) {
    selfTest();
    return;
  }
  const newKey = readMasterKey("RICCON_MASTER_KEY");
  const newKeyId = process.env.RICCON_MASTER_KEY_ID ?? "mk-1";
  const pepper = readMasterKey("RICCON_CREDENTIAL_FINGERPRINT_PEPPER");
  const rows = loadDekRows(options);
  if (rows.length === 0) {
    throw new Error("tenant_data_key 没有可用行；先确认迁移与部署种子");
  }
  const samples = loadCredentialSamples(options);
  const report = {
    mode: options.apply ? "apply" : "read-only",
    targetKeyId: newKeyId,
    dekRows: rows.length,
    credentialsChecked: samples.length,
    rewrapped: [],
    verified: [],
    failures: [],
  };
  if (options.apply) {
    const oldKey = readMasterKey("RICCON_MASTER_KEY_PREVIOUS");
    const oldKeyId = process.env.RICCON_MASTER_KEY_PREVIOUS_ID ?? null;
    for (const row of rows) {
      if (row.masterKeyId === newKeyId) {
        continue;
      }
      let dek;
      try {
        dek = gcmOpen(
          oldKey,
          row.wrapNonce,
          row.wrappedDek,
          envelopeAad(row.tenantId, row.purpose, row.keyId),
        );
      } catch {
        report.failures.push({
          tenant: row.tenantId,
          keyId: row.keyId,
          reason: "旧主密钥无法解包 DEK",
        });
        continue;
      }
      const nonce = randomBytes(12);
      const wrapped = gcmSeal(
        newKey,
        nonce,
        dek,
        envelopeAad(row.tenantId, row.purpose, row.keyId),
      );
      runSql(
        `UPDATE tenant_data_key
            SET wrapped_dek = UNHEX('${wrapped.toString("hex")}'),
                wrap_nonce = UNHEX('${nonce.toString("hex")}'),
                master_key_id = ${sqlString(newKeyId)}
          WHERE tenant_id = ${sqlString(row.tenantId)} AND key_id = ${sqlString(row.keyId)};`,
        options,
      );
      const [readBack] = parseRows(
        runSql(
          `SELECT master_key_id, HEX(wrapped_dek) AS wrapped_dek_hex FROM tenant_data_key
            WHERE tenant_id = ${sqlString(row.tenantId)} AND key_id = ${sqlString(row.keyId)};`,
          options,
        ),
      );
      if (!readBack || readBack.master_key_id !== newKeyId) {
        report.failures.push({
          tenant: row.tenantId,
          keyId: row.keyId,
          reason: "rewrap 回读失败",
        });
        continue;
      }
      report.rewrapped.push({
        tenant: row.tenantId,
        keyId: row.keyId,
        from: row.masterKeyId,
        to: newKeyId,
      });
      if (oldKeyId && row.masterKeyId !== oldKeyId) {
        report.failures.push({
          tenant: row.tenantId,
          keyId: row.keyId,
          reason: `旧 master_key_id=${row.masterKeyId} 与期望 ${oldKeyId} 不一致`,
        });
      }
    }
  }
  // 用当前（新）主密钥重新验证：所有 DEK 可解包，且每 provider 抽一行凭据可解密、fingerprint 匹配。
  const refreshed = loadDekRows(options);
  let deks;
  try {
    deks = unwrapDeks(refreshed, newKey);
  } catch {
    report.failures.push({ reason: "新主密钥无法解包全部 DEK" });
    deks = new Map();
  }
  for (const sample of samples) {
    const result = verifyCredential(sample, deks, pepper);
    if (result.ok) {
      report.verified.push({
        tenant: sample.tenant_id,
        provider: hexToUuid(sample.provider_id_hex),
        credentialType: sample.credential_type,
      });
    } else {
      report.failures.push({
        tenant: sample.tenant_id,
        provider: hexToUuid(sample.provider_id_hex),
        credentialType: sample.credential_type,
        reason: result.reason,
      });
    }
  }
  report.status = report.failures.length === 0 ? "passed" : "failed";
  console.log(JSON.stringify(report, null, 2));
  if (report.status !== "passed") {
    process.exitCode = 1;
  }
}

try {
  main();
} catch (error) {
  console.error(`[rewrap-credentials] ${error.message}`);
  process.exitCode = error.exitCode ?? 1;
}
