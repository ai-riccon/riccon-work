// 真实栈验证用 MySQL 访问：通过 compose 测试栈的 mysql 客户端执行 SQL。
//
// 不引入额外数据库驱动，避免验证脚本依赖生产 Maven 依赖；所有语句只作用于测试栈。

import { spawnSync } from "node:child_process";

const COMPOSE_FILE = "deploy/compose/docker-compose.test.yml";

/**
 * 执行一段 SQL 并返回原始 stdout；stderr 非空且退出码非零时抛错。
 *
 * @param {string} sql SQL 文本
 * @param {{ allowFailure?: boolean, repoRoot?: string }} [options]
 */
export function runSql(sql, options = {}) {
  const result = spawnSync(
    "docker",
    [
      "compose",
      "-f",
      COMPOSE_FILE,
      "exec",
      "-T",
      "-e",
      "MYSQL_PWD=riccon_test_only",
      "mysql",
      "mysql",
      "--user=riccon",
      "--database=riccon",
      "--batch",
      "--raw",
    ],
    {
      cwd: options.repoRoot ?? process.cwd(),
      input: sql,
      encoding: "utf8",
      maxBuffer: 32 * 1024 * 1024,
    },
  );
  if (result.status !== 0 && !options.allowFailure) {
    throw new Error(
      `mysql failed (exit ${result.status}): ${result.stderr || result.stdout}`,
    );
  }
  return {
    status: result.status,
    stdout: result.stdout ?? "",
    stderr: result.stderr ?? "",
  };
}

/**
 * 执行单值查询并返回字符串。
 *
 * @param {string} sql 查询
 */
export function queryValue(sql) {
  const { stdout } = runSql(sql);
  const lines = stdout.trim().split("\n");
  return lines[lines.length - 1];
}

/**
 * 把 UUID 字符串转成 MySQL BINARY(16) 字面量。
 *
 * @param {string} value UUID
 */
export function binaryUuid(value) {
  return `UNHEX(REPLACE('${value}', '-', ''))`;
}

/**
 * SQL 字符串字面量转义；只用于验证脚本内的固定文本。
 *
 * @param {string} value 原始值
 */
export function sqlString(value) {
  return `'${String(value).replaceAll("\\", "\\\\").replaceAll("'", "''")}'`;
}
