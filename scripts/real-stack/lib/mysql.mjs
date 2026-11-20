// 真实栈验证用 MySQL 访问：通过 compose 测试栈的 mysql 客户端执行 SQL。
//
// 不引入额外数据库驱动，避免验证脚本依赖生产 Maven 依赖；所有语句只作用于测试栈。
//
// 字符集约束：容器内 mysql 8.4 客户端的 `--default-character-set=auto` 按 locale 解析，
// 无 locale 时回退 latin1。此时中文以 UTF-8 字节被当作 latin1 写入（utf8mb4→双写），
// API 用 JDBC utf8mb4 读出即为 `å‡æ¨¡åž‹` 一类乱码。因此客户端必须显式 utf8mb4，
// 且 seed/checks/run 共用本文件的参数构造，避免各处漂移。

import { spawnSync } from "node:child_process";

const COMPOSE_FILE = "deploy/compose/docker-compose.test.yml";

/** 测试栈连接标识；与 compose 中 mysql 服务一致。 */
const MYSQL_USER = "riccon";
const MYSQL_DATABASE = "riccon";

/** 客户端连接字符集；见文件头注释，禁止依赖服务端/客户端的 auto 推断。 */
export const MYSQL_CLIENT_CHARSET = "utf8mb4";

/**
 * 构造 `mysql` 客户端参数（不含 docker compose 前缀），供所有真实栈脚本共用。
 *
 * @param {{ user?: string, database?: string }} [options] 连接覆盖
 * @returns {string[]} 参数列表
 */
export function mysqlClientArgs(options = {}) {
  return [
    `--user=${options.user ?? MYSQL_USER}`,
    `--database=${options.database ?? MYSQL_DATABASE}`,
    "--batch",
    "--raw",
    `--default-character-set=${MYSQL_CLIENT_CHARSET}`,
  ];
}

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
      ...mysqlClientArgs(),
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
 * 把 UUID 字符串转成小写十六进制，用于把查询结果回配到预期值。
 *
 * @param {string} value UUID
 * @returns {string} 32 位小写十六进制
 */
export function uuidHex(value) {
  return String(value).replaceAll("-", "").toLowerCase();
}

/**
 * SQL 字符串字面量转义；只用于验证脚本内的固定文本。
 *
 * @param {string} value 原始值
 */
export function sqlString(value) {
  return `'${String(value).replaceAll("\\", "\\\\").replaceAll("'", "''")}'`;
}
