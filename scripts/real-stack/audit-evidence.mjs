#!/usr/bin/env node
// M02 活动证据审计：TASK-0008..TASK-0031 的每个活动 EV 必须存在、哈希匹配、dirty=false，
// 且 commit 是 HEAD 祖先；同时检查重复 ID 和 M02 范围内未被任务引用的孤立 EV。
//
// 允许的“预期失败”证据：
// - TASK-0011 EV-0129/0130：证明 audit_event UPDATE/DELETE 被触发器拒绝；
// - TASK-0011 EV-0133/0134：证明未设置 log_bin_trust_function_creators 时触发器创建失败；
// - TASK-0024 EV-0232：REV-M02-001 的真实栈失败证据，保留用于追溯，已被 TASK-0029 复验替代；
// - TASK-0029 EV-0266/0267：真实栈用例定位器缺陷导致的失败尝试，已被 EV-0268 替代；
// - TASK-0029 EV-0268：REV-M02-002 记录的 F-012 产品失败证据，保留用于追溯；
// - TASK-0031 EV-0277：stub E2E 文案定位缺陷导致的失败尝试，已被 EV-0281 替代。
//
// 允许的未引用证据：
// - TASK-0030 EV-0278/0279/0280：在脏工作区录制，未列入任务 evidence；
//   正式证据为 EV-0285/0286/0287（dirty=false）。脏证据不得被任务引用。
//
// 除上述显式列表外，任何非零退出或未引用证据都视为审计失败。

import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import path from "node:path";

const repoRoot = process.cwd();
const taskIds = [];
for (let index = 8; index <= 31; index += 1) {
  taskIds.push(`TASK-${String(index).padStart(4, "0")}`);
}

const expectedFailures = new Set([
  "EV-0129",
  "EV-0130",
  "EV-0133",
  "EV-0134",
  "EV-0232",
  "EV-0266",
  "EV-0267",
  "EV-0268",
  "EV-0277",
]);

const allowedUnreferenced = new Set(["EV-0278", "EV-0279", "EV-0280"]);

const problems = [];
const records = [];
const seen = new Map();

/** 读取 JSON 文件；失败时记录问题并返回 null。 */
function readJson(file) {
  try {
    return JSON.parse(readFileSync(file, "utf8"));
  } catch (error) {
    problems.push(`${path.relative(repoRoot, file)}: ${error.message}`);
    return null;
  }
}

for (const taskId of taskIds) {
  const taskPath = path.join(repoRoot, ".riccon/tasks", `${taskId}.json`);
  if (!existsSync(taskPath)) {
    problems.push(`${taskId}: task file missing`);
    continue;
  }
  const task = readJson(taskPath);
  if (!task) continue;
  for (const evidenceId of task.evidence ?? []) {
    if (seen.has(evidenceId)) {
      problems.push(
        `${evidenceId}: duplicate across ${seen.get(evidenceId)} and ${taskId}`,
      );
      continue;
    }
    seen.set(evidenceId, taskId);
    const metaPath = path.join(
      repoRoot,
      ".riccon/evidence",
      taskId,
      `${evidenceId}.json`,
    );
    if (!existsSync(metaPath)) {
      problems.push(`${taskId}/${evidenceId}: metadata missing`);
      continue;
    }
    const record = readJson(metaPath);
    if (!record) continue;
    const outputPath = path.resolve(repoRoot, record.output_path);
    if (!existsSync(outputPath)) {
      problems.push(`${evidenceId}: output missing ${record.output_path}`);
      continue;
    }
    const buffer = readFileSync(outputPath);
    const sha = createHash("sha256").update(buffer).digest("hex");
    if (sha !== record.output_sha256) {
      problems.push(`${evidenceId}: sha256 mismatch`);
    }
    if (buffer.length !== record.output_bytes) {
      problems.push(`${evidenceId}: size mismatch`);
    }
    if (record.manual === true) {
      problems.push(`${evidenceId}: manual evidence is not acceptable`);
    }
    if (record.dirty !== false) {
      problems.push(`${evidenceId}: dirty=${record.dirty}`);
    }
    const ancestor =
      spawnSync(
        "git",
        ["merge-base", "--is-ancestor", String(record.commit), "HEAD"],
        { cwd: repoRoot },
      ).status === 0;
    if (!ancestor) {
      problems.push(
        `${evidenceId}: commit ${String(record.commit).slice(0, 7)} is not an ancestor of HEAD`,
      );
    }
    if (record.exit_code !== 0 && !expectedFailures.has(evidenceId)) {
      problems.push(`${evidenceId}: unexpected exit ${record.exit_code}`);
    }
    records.push(record);
  }
}

// M02 范围内的 EV 元数据文件必须都能被某个任务引用，避免“录了但没归档”的孤立证据；
// allowedUnreferenced 中的脏证据是显式例外，必须保持 dirty=true 且不在任务 evidence 中。
const referenced = new Set(records.map((record) => record.id));
for (const taskId of taskIds) {
  const dir = path.join(repoRoot, ".riccon/evidence", taskId);
  if (!existsSync(dir) || !statSync(dir).isDirectory()) continue;
  for (const file of readdirSync(dir)) {
    if (!/^EV-\d+\.json$/.test(file)) continue;
    const id = file.replace(".json", "");
    if (referenced.has(id)) continue;
    if (allowedUnreferenced.has(id)) {
      const record = readJson(path.join(dir, file));
      if (!record || record.dirty !== true) {
        problems.push(
          `${id}: allowed unreferenced evidence must be dirty=true`,
        );
      }
      continue;
    }
    problems.push(
      `${taskId}/${id}: evidence file is not referenced by the task`,
    );
  }
}

const nonzero = records.filter((record) => record.exit_code !== 0);
const summary = {
  status: problems.length === 0 ? "passed" : "failed",
  generatedAt: new Date().toISOString(),
  auditedTasks: taskIds.length,
  activeEvidence: records.length,
  expectedFailures: nonzero.map((record) => ({
    id: record.id,
    task: record.task_id,
    exitCode: record.exit_code,
  })),
  allowedUnreferenced: [...allowedUnreferenced],
  allDirtyFalse: records.every((record) => record.dirty === false),
  allCommitsAncestors: !problems.some((problem) =>
    problem.includes("not an ancestor"),
  ),
  problems,
};
console.log(JSON.stringify(summary, null, 2));
if (problems.length > 0) {
  process.exitCode = 1;
}
