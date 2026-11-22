#!/usr/bin/env node
// M04-V2 独立回滚重放执行器：在一次性演练库 riccon_rollback_v2（mysql:8.4.13 快照）上执行
// `m04-v2-rollback-drill.sql`，并断言旧应用 schema 可读、每组 key 收敛为 1 行、成本快照保留。
// 生产不回滚数据库；本脚本只做演练，不接触 `riccon` 源库。

import { spawnSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";
import path from "node:path";
import process from "node:process";

const repoRoot = process.cwd();
const evidenceDir = path.join(repoRoot, ".riccon/evidence/TASK-0116");
const drillDb = "riccon_rollback_v2";
const compose = ["compose", "-f", "deploy/compose/docker-compose.test.yml"];

/** 执行 SQL 文件（stdin）或查询；返回 stdout。 */
function mysql(args, input) {
  const result = spawnSync(
    "docker",
    [
      ...compose,
      "exec",
      "-T",
      "-e",
      "MYSQL_PWD=riccon_test_only",
      "mysql",
      "mysql",
      ...args,
    ],
    { cwd: repoRoot, input, encoding: "utf8" },
  );
  if (result.status !== 0) {
    throw new Error(`mysql failed (${result.status}): ${result.stderr}`);
  }
  return String(result.stdout ?? "").trim();
}

/** 查询单值。 */
function queryOne(sql) {
  return mysql([
    `--user=riccon`,
    `--database=${drillDb}`,
    "-N",
    "--batch",
    "-e",
    sql,
  ]);
}

const checks = [];
/** 断言。 */
function check(name, condition, detail) {
  checks.push({ name, status: condition ? "passed" : "failed", detail });
  if (!condition) {
    throw new Error(`${name}: ${detail}`);
  }
}

try {
  const before = {
    tables: Number(
      queryOne(
        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()",
      ),
    ),
    schemaVersion: Number(
      queryOne(
        "SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success = 1",
      ),
    ),
    credentials: Number(queryOne("SELECT COUNT(*) FROM provider_credential")),
    groups: Number(
      queryOne(
        "SELECT COUNT(*) FROM (SELECT tenant_id, provider_id, credential_type FROM provider_credential GROUP BY tenant_id, provider_id, credential_type) g",
      ),
    ),
  };
  check(
    "beforeSchemaVersion",
    before.schemaVersion === 11,
    `expected 11, got ${before.schemaVersion}`,
  );
  check(
    "beforeMultiKey",
    before.credentials > before.groups,
    `credentials=${before.credentials} groups=${before.groups}`,
  );

  mysql(
    [`--user=riccon`, `--database=${drillDb}`],
    readFileSync(path.join(evidenceDir, "m04-v2-rollback-drill.sql")),
  );

  const schemaVersion = Number(
    queryOne(
      "SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success = 1",
    ),
  );
  check(
    "schemaVersion10",
    schemaVersion === 10,
    `expected 10, got ${schemaVersion}`,
  );

  const badGroups = Number(
    queryOne(
      `SELECT COUNT(*) FROM (
         SELECT tenant_id, provider_id, credential_type
           FROM provider_credential
          GROUP BY tenant_id, provider_id, credential_type
         HAVING COUNT(*) <> 1 OR SUM(status = 'ACTIVE') > 1
       ) bad`,
    ),
  );
  check("groupConvergence", badGroups === 0, `bad groups=${badGroups}`);

  const legacyStatuses = Number(
    queryOne(
      "SELECT COUNT(*) FROM provider_credential WHERE status IN ('DISABLED','INVALID')",
    ),
  );
  check(
    "legacyStatusNormalized",
    legacyStatuses === 0,
    `remaining DISABLED/INVALID=${legacyStatuses}`,
  );

  const activeProviders = Number(
    queryOne(
      "SELECT COUNT(DISTINCT provider_id) FROM provider_credential WHERE status = 'ACTIVE'",
    ),
  );
  check(
    "activeKeyAvailable",
    activeProviders >= 2,
    `providers with ACTIVE keys=${activeProviders}`,
  );

  const oldUnique = queryOne(
    `SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index)
       FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 'provider_credential'
        AND index_name = 'uk_provider_credential'`,
  );
  check(
    "oldUniqueKeyRestored",
    oldUnique === "tenant_id,provider_id,credential_type",
    `uk_provider_credential=${oldUnique}`,
  );

  const newTables = Number(
    queryOne(
      `SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name IN ('model_routing','model_routing_entry','model_gateway_switch',
                             'model_invocation_attempt','quota_limit','quota_usage')`,
    ),
  );
  check(
    "newTablesDropped",
    newTables === 0,
    `remaining M04 tables=${newTables}`,
  );

  const newColumns = Number(
    queryOne(
      `SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'provider_credential'
          AND column_name IN ('label','fingerprint','masked_hint','priority','consecutive_failures',
                              'cooldown_until','disabled_at','disabled_by','invalidated_at','last_used_at')`,
    ),
  );
  check(
    "newColumnsDropped",
    newColumns === 0,
    `remaining M04 columns=${newColumns}`,
  );

  const costColumns = Number(
    queryOne(
      `SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'model_invocation'
          AND column_name IN ('cost_status','cost_amount','cost_day','attempt_count')`,
    ),
  );
  check(
    "costSnapshotRetained",
    costColumns === 4,
    `cost columns=${costColumns}`,
  );

  // 旧应用（M04 前）按列名读取历史调用仍工作，且成本快照不被删除。
  const oldProjection = mysql([
    "--user=riccon",
    `--database=${drillDb}`,
    "-N",
    "--batch",
    "-e",
    `SELECT COUNT(*) FROM (
       SELECT tenant_id, id, model_id, model_key_snapshot, purpose, status, trace_id, started_at
         FROM model_invocation LIMIT 1
     ) old_app`,
  ]);
  check(
    "historyReadableByOldApp",
    Number(oldProjection) === 1,
    `old projection count=${oldProjection}`,
  );

  const archiveCount = Number(
    queryOne("SELECT COUNT(*) FROM provider_credential_rollback_archive"),
  );
  check("multiKeyArchived", archiveCount > 0, `archived rows=${archiveCount}`);

  const afterTables = Number(
    queryOne(
      "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()",
    ),
  );
  const report = {
    generatedAt: new Date().toISOString(),
    drillDb,
    before,
    after: {
      tables: afterTables,
      schemaVersion,
      credentials: Number(queryOne("SELECT COUNT(*) FROM provider_credential")),
    },
    archiveCount,
    checks,
    status: "passed",
  };
  writeFileSync(
    path.join(evidenceDir, "ac18", "rollback-drill.json"),
    JSON.stringify(report, null, 2),
  );
  console.log("[rollback-drill] passed");
  console.log(JSON.stringify(report, null, 2));
} catch (error) {
  const report = {
    generatedAt: new Date().toISOString(),
    drillDb,
    checks,
    status: "failed",
    error: String(error?.stack ?? error),
  };
  writeFileSync(
    path.join(evidenceDir, "ac18", "rollback-drill.json"),
    JSON.stringify(report, null, 2),
  );
  console.error(`[rollback-drill] failed: ${error?.message ?? error}`);
  process.exitCode = 1;
}
