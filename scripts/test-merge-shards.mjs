#!/usr/bin/env node
// M19 P2 分片合并校验：读取 CI 下载的 4 个分片 surefire 报告，校验它们合起来恰好是
// scripts/test-shards-baseline.json 登记的全量集合，且每个用例都真实执行、没有失败/跳过。
//
// 校验规则（任一不满足即非 0 退出，并打印具体类与计数）：
// 1. 分片目录数量与编号完整（默认 4 片，编号 0..3）；
// 2. 基线里的每个测试类恰好出现一次；没有基线之外的额外类；
//    重复类通常意味着“未登记的新测试类在所有分片都跑了”，缺类意味着分片排除列表或基线过期；
// 3. 每个类的 tests 不少于基线登记值（删除测试方法必须重建基线并留痕）；
// 4. 用例总数不少于基线 test_count；
// 5. failures / errors / skipped 全为 0。
//
// 用法：
//   node scripts/test-merge-shards.mjs --shards-root .ci/shards
//   node scripts/test-merge-shards.mjs --shards-root "$TMPDIR/shards" --out-dir "$TMPDIR/merged"
//
// 分片目录结构：<shards-root>/<任意带编号的目录>/apps/*/target/surefire-reports/TEST-*.xml
//              以及 .../platform/*/target/surefire-reports/TEST-*.xml；目录名中的第一个数字
//              作为分片号（CI artifact 名为 backend-shard-<N>）。
//
// 退出码：0 全部通过；1 参数错误、报告缺失或校验失败（报告仍会尽力写出便于排查）。

import {
  closeSync,
  existsSync,
  mkdirSync,
  openSync,
  readFileSync,
  readdirSync,
  readSync,
  writeFileSync,
} from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

/** 仓库根目录（脚本位于 scripts/）。 */
const repoRoot = path.resolve(fileURLToPath(new URL("..", import.meta.url)));

/** 默认基线文件。 */
const DEFAULT_BASELINE_PATH = path.join(
  repoRoot,
  "scripts",
  "test-shards-baseline.json",
);

/** 默认分片数。 */
const DEFAULT_SHARD_COUNT = 4;

/** 读取 XML 头部时最多读取的字节数。 */
const XML_PREFIX_BYTES = 64 * 1024;

/** 打印用法。 */
function usage() {
  console.log(`用法: node scripts/test-merge-shards.mjs --shards-root <dir> [options]

选项:
  --shards-root <dir>   分片报告根目录（必填）；每个子目录是一个分片
  --baseline <file>     基线文件（默认 scripts/test-shards-baseline.json）
  --out-dir <dir>       合并报告输出目录（默认 .ci/test-shards-merged）
  --shard-count N       期望分片数（默认 ${DEFAULT_SHARD_COUNT}）
  --help                显示本帮助

校验：分片齐全且编号完整、每个基线类恰好一次、无额外类、每类用例数不减少、
总用例数不少于基线、failures/errors/skips 全为 0。`);
}

/** 解析命令行参数；未知参数直接失败。 */
function parseOptions(args) {
  const options = {
    shardsRoot: null,
    baselinePath: DEFAULT_BASELINE_PATH,
    outDir: path.join(repoRoot, ".ci", "test-shards-merged"),
    shardCount: DEFAULT_SHARD_COUNT,
    help: false,
  };
  for (let index = 0; index < args.length; index += 1) {
    const arg = args[index];
    if (arg === "--shards-root") {
      options.shardsRoot = path.resolve(requireValue(args, ++index, arg));
    } else if (arg === "--baseline") {
      options.baselinePath = path.resolve(requireValue(args, ++index, arg));
    } else if (arg === "--out-dir") {
      options.outDir = path.resolve(requireValue(args, ++index, arg));
    } else if (arg === "--shard-count") {
      const value = Number(requireValue(args, ++index, arg));
      if (!Number.isInteger(value) || value < 1) {
        throw new Error("--shard-count 必须是正整数");
      }
      options.shardCount = value;
    } else if (arg === "--help" || arg === "-h") {
      options.help = true;
    } else {
      throw new Error(`未知参数: ${arg}`);
    }
  }
  if (!options.shardsRoot) {
    throw new Error("必须提供 --shards-root <dir>");
  }
  return options;
}

/** 读取选项值；缺失时失败。 */
function requireValue(args, index, flag) {
  const value = args[index];
  if (!value || value.startsWith("--")) {
    throw new Error(`${flag} 需要一个值`);
  }
  return value;
}

/** 列出目录下的子目录名；目录不存在时返回空数组。 */
function listDirectories(directory) {
  if (!existsSync(directory)) {
    return [];
  }
  return readdirSync(directory, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name)
    .sort();
}

/** 递归列出目录下的所有文件（返回绝对路径）。 */
function walkFiles(directory) {
  const files = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const fullPath = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      files.push(...walkFiles(fullPath));
    } else if (entry.isFile()) {
      files.push(fullPath);
    }
  }
  return files;
}

/** 读取文件头部的字节。 */
function readXmlPrefix(file) {
  const descriptor = openSync(file, "r");
  try {
    const buffer = Buffer.alloc(XML_PREFIX_BYTES);
    const bytesRead = readSync(descriptor, buffer, 0, XML_PREFIX_BYTES, 0);
    return buffer.subarray(0, bytesRead).toString("utf8");
  } finally {
    closeSync(descriptor);
  }
}

/** 从 <testsuite> 起始标签解析必需属性；缺失或非法时抛出。 */
function parseTestSuite(file) {
  const prefix = readXmlPrefix(file);
  const match = /<testsuite\b([^>]*)>/.exec(prefix);
  if (!match) {
    throw new Error("未找到 <testsuite> 起始标签");
  }
  const attributes = match[1];

  /** 读取单个属性值。 */
  const attribute = (name) => {
    const found = new RegExp(`\\b${name}="([^"]*)"`).exec(attributes);
    return found ? found[1] : null;
  };

  /** 读取数值属性。 */
  const number = (name) => {
    const raw = attribute(name);
    if (raw === null) {
      throw new Error(`<testsuite> 缺少属性 ${name}`);
    }
    const value = Number(raw);
    if (!Number.isFinite(value) || value < 0) {
      throw new Error(`<testsuite> 属性 ${name} 非法: ${raw}`);
    }
    return value;
  };

  const name = attribute("name");
  if (!name) {
    throw new Error("<testsuite> 缺少属性 name");
  }
  return {
    name,
    tests: number("tests"),
    failures: number("failures"),
    errors: number("errors"),
    skipped: number("skipped"),
    time: number("time"),
  };
}

/** 保留 3 位小数。 */
function roundSeconds(value) {
  return Math.round(value * 1000) / 1000;
}

/** 生成北京时间（UTC+08:00）时间戳。 */
function beijingTimestamp(date = new Date()) {
  const shifted = new Date(date.getTime() + 8 * 60 * 60 * 1000);
  return shifted.toISOString().replace("Z", "+08:00");
}

/** 报告路径的显示形式。 */
function displayPath(file) {
  const relative = path.relative(repoRoot, file);
  return relative.startsWith("..") ? file : relative;
}

/** 读取并校验基线文件；结构错误直接抛出。 */
function loadBaseline(baselinePath) {
  if (!existsSync(baselinePath)) {
    throw new Error(`基线文件不存在: ${baselinePath}`);
  }
  let baseline;
  try {
    baseline = JSON.parse(readFileSync(baselinePath, "utf8"));
  } catch (error) {
    throw new Error(`基线 JSON 解析失败: ${baselinePath}（${error.message}）`);
  }
  if (
    !baseline ||
    !Array.isArray(baseline.classes) ||
    baseline.classes.length === 0
  ) {
    throw new Error(`基线缺少 classes 数组: ${baselinePath}`);
  }
  if (typeof baseline.test_count !== "number") {
    throw new Error(`基线缺少 test_count: ${baselinePath}`);
  }
  return baseline;
}

/**
 * 发现分片目录并把目录名映射为分片号。
 *
 * @param {string} root 分片根目录
 * @param {number} expected 期望分片数
 * @returns {Array<{id: number, name: string, directory: string}>}
 */
function discoverShards(root, expected) {
  if (!existsSync(root)) {
    throw new Error(`分片根目录不存在: ${root}`);
  }
  const shards = [];
  const problems = [];
  for (const name of listDirectories(root)) {
    const match = /(\d+)/.exec(name);
    if (!match) {
      problems.push(`分片目录名无法解析编号: ${name}`);
      continue;
    }
    shards.push({
      id: Number(match[1]),
      name,
      directory: path.join(root, name),
    });
  }
  if (problems.length > 0) {
    throw new Error(`分片目录非法：\n  - ${problems.join("\n  - ")}`);
  }
  shards.sort((left, right) => left.id - right.id);
  const ids = shards.map((shard) => shard.id);
  const missing = [];
  for (let id = 0; id < expected; id += 1) {
    if (!ids.includes(id)) {
      missing.push(id);
    }
  }
  const duplicated = ids.filter((id, index) => ids.indexOf(id) !== index);
  if (
    missing.length > 0 ||
    duplicated.length > 0 ||
    shards.length !== expected
  ) {
    throw new Error(
      `分片不完整：期望 ${expected} 片（0..${expected - 1}），实际 ${ids.join(", ") || "无"}` +
        (missing.length ? `；缺失 ${missing.join(", ")}` : "") +
        (duplicated.length ? `；重复 ${duplicated.join(", ")}` : ""),
    );
  }
  return shards;
}

/**
 * 从分片目录内的相对路径推导模块标识（apps/api、platform/foo）。
 *
 * @param {string} shardDirectory 分片目录
 * @param {string} file 报告文件
 * @returns {string|null}
 */
function moduleFromPath(shardDirectory, file) {
  const relative = path
    .relative(shardDirectory, file)
    .split(path.sep)
    .join("/");
  const match = /(?:^|\/)(apps|platform)\/([^/]+)\//.exec(relative);
  return match ? `${match[1]}/${match[2]}` : null;
}

/** 在分片目录里查找分片墙钟文件（可选，仅用于报告展示）。 */
function readShardWallSeconds(shard) {
  const expectedName = `shard-${shard.id}-timings.json`;
  const match = walkFiles(shard.directory).find(
    (file) => path.basename(file) === expectedName,
  );
  if (!match) {
    return null;
  }
  try {
    const payload = JSON.parse(readFileSync(match, "utf8"));
    return typeof payload.wall_seconds === "number"
      ? payload.wall_seconds
      : null;
  } catch {
    return null;
  }
}

/**
 * 收集并聚合分片报告。
 *
 * @param {Array<{id: number, name: string, directory: string}>} shards 分片
 * @returns {{entries: Map<string, Array<object>>, problems: string[], shardStats: Array<object>}}
 */
function collectShardReports(shards) {
  const entries = new Map();
  const problems = [];
  const shardStats = [];

  for (const shard of shards) {
    const reports = walkFiles(shard.directory)
      .filter(
        (file) =>
          path.basename(file).startsWith("TEST-") &&
          path.basename(file).endsWith(".xml"),
      )
      .sort();
    if (reports.length === 0) {
      problems.push(
        `分片 ${shard.id}（${shard.name}）没有任何 TEST-*.xml 报告`,
      );
    }
    const stats = {
      shard: shard.id,
      artifact: shard.name,
      suites: 0,
      tests: 0,
      failures: 0,
      errors: 0,
      skipped: 0,
      time: 0,
      wall_seconds: readShardWallSeconds(shard),
    };
    for (const file of reports) {
      const module = moduleFromPath(shard.directory, file);
      if (!module) {
        problems.push(
          `分片 ${shard.id} 的报告路径无法定位模块（既不在 apps/* 也不在 platform/*）: ${displayPath(file)}`,
        );
        continue;
      }
      let suite;
      try {
        suite = parseTestSuite(file);
      } catch (error) {
        problems.push(
          `分片 ${shard.id} 解析 surefire 报告失败: ${displayPath(file)}（${error.message}）`,
        );
        continue;
      }
      if (!entries.has(suite.name)) {
        entries.set(suite.name, []);
      }
      entries.get(suite.name).push({
        shard: shard.id,
        module,
        tests: suite.tests,
        failures: suite.failures,
        errors: suite.errors,
        skipped: suite.skipped,
        time: roundSeconds(suite.time),
        file: displayPath(file),
      });
      stats.suites += 1;
      stats.tests += suite.tests;
      stats.failures += suite.failures;
      stats.errors += suite.errors;
      stats.skipped += suite.skipped;
      stats.time += suite.time;
    }
    stats.time = roundSeconds(stats.time);
    shardStats.push(stats);
  }
  return { entries, problems, shardStats };
}

/**
 * 校验分片合集与基线一致。
 *
 * @param {object} baseline 基线
 * @param {Map<string, Array<object>>} entries 聚合结果
 * @param {Array<object>} shardStats 分片统计
 * @returns {{problems: string[], totals: object}}
 */
function validateMerged(baseline, entries, shardStats) {
  const problems = [];
  const baselineClasses = new Map(
    baseline.classes.map((entry) => [entry.class, entry]),
  );

  for (const [className, runs] of [...entries.entries()].sort()) {
    if (runs.length > 1) {
      problems.push(
        `测试类执行了 ${runs.length} 次（分片 ${runs.map((run) => run.shard).join(", ")}）: ${className}` +
          (baselineClasses.has(className)
            ? ""
            : "；该类不在基线中，说明新增测试未登记且被所有分片执行"),
      );
    }
    if (!baselineClasses.has(className)) {
      problems.push(`分片出现基线之外的测试类: ${className}`);
    }
  }

  for (const [className, entry] of [...baselineClasses.entries()].sort()) {
    const runs = entries.get(className);
    if (!runs) {
      problems.push(`基线测试类没有任何分片执行: ${className}`);
      continue;
    }
    if (runs.length !== 1) {
      continue;
    }
    const [run] = runs;
    if (run.tests < entry.tests) {
      problems.push(
        `用例数减少: ${className} 预期 ≥ ${entry.tests}，实际 ${run.tests}（分片 ${run.shard}）`,
      );
    }
    if (run.failures !== 0 || run.errors !== 0 || run.skipped !== 0) {
      problems.push(
        `失败/错误/跳过非 0: ${className} failures=${run.failures} errors=${run.errors} skipped=${run.skipped}（分片 ${run.shard}）`,
      );
    }
    if (run.module !== entry.module) {
      problems.push(
        `模块不一致: ${className} 基线 ${entry.module}，实际 ${run.module}`,
      );
    }
  }

  const totals = {
    suites: shardStats.reduce((sum, shard) => sum + shard.suites, 0),
    tests: shardStats.reduce((sum, shard) => sum + shard.tests, 0),
    failures: shardStats.reduce((sum, shard) => sum + shard.failures, 0),
    errors: shardStats.reduce((sum, shard) => sum + shard.errors, 0),
    skipped: shardStats.reduce((sum, shard) => sum + shard.skipped, 0),
    time: roundSeconds(shardStats.reduce((sum, shard) => sum + shard.time, 0)),
  };

  if (totals.tests < baseline.test_count) {
    problems.push(`用例总数 ${totals.tests} 少于基线 ${baseline.test_count}`);
  }
  if (totals.failures !== 0 || totals.errors !== 0 || totals.skipped !== 0) {
    problems.push(
      `全量失败/错误/跳过非 0: failures=${totals.failures} errors=${totals.errors} skipped=${totals.skipped}`,
    );
  }
  if (totals.suites !== baseline.suite_count) {
    problems.push(
      `执行类数 ${totals.suites} 与基线 suite_count=${baseline.suite_count} 不一致`,
    );
  }
  return { problems, totals };
}

/**
 * 生成合并报告对象。
 *
 * @param {object} baseline 基线
 * @param {Array<object>} shardStats 分片统计
 * @param {object} totals 总计
 * @param {Map<string, Array<object>>} entries 聚合结果
 * @param {string[]} problems 问题列表
 * @returns {object}
 */
function buildReport(baseline, shardStats, totals, entries, problems) {
  const baselineClasses = new Map(
    baseline.classes.map((entry) => [entry.class, entry]),
  );
  const classes = [...entries.entries()]
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([className, runs]) => {
      const baselineEntry = baselineClasses.get(className);
      return {
        class: className,
        module: runs[0]?.module ?? baselineEntry?.module ?? null,
        shard: runs.length === 1 ? runs[0].shard : null,
        runs: runs.length,
        tests: runs.reduce((sum, run) => sum + run.tests, 0),
        baseline_tests: baselineEntry?.tests ?? null,
        failures: runs.reduce((sum, run) => sum + run.failures, 0),
        errors: runs.reduce((sum, run) => sum + run.errors, 0),
        skipped: runs.reduce((sum, run) => sum + run.skipped, 0),
        time: roundSeconds(runs.reduce((sum, run) => sum + run.time, 0)),
      };
    });
  return {
    generated_at: beijingTimestamp(),
    baseline: {
      generated_from: baseline.generated_from,
      suite_count: baseline.suite_count,
      test_count: baseline.test_count,
      failure_count: baseline.failure_count,
      error_count: baseline.error_count,
      skip_count: baseline.skip_count,
    },
    verdict: problems.length === 0 ? "pass" : "fail",
    problems,
    shards: shardStats,
    totals,
    classes,
  };
}

/** 生成人读 Markdown 报告。 */
function renderMarkdown(report) {
  const lines = [];
  lines.push("# 后端分片合并报告");
  lines.push("");
  lines.push(`- 生成时间：${report.generated_at}`);
  lines.push(`- 结论：**${report.verdict}**`);
  lines.push(
    `- 基线：${report.baseline.suite_count} 类 / ${report.baseline.test_count} 用例（${report.baseline.generated_from}）`,
  );
  lines.push(
    `- 实际：${report.totals.suites} 类 / ${report.totals.tests} 用例 / failures=${report.totals.failures} errors=${report.totals.errors} skipped=${report.totals.skipped}`,
  );
  lines.push("");
  lines.push("## 分片");
  lines.push("");
  lines.push(
    "| 分片 | 类数 | 用例 | failures | errors | skipped | suite 时间 | 墙钟 |",
  );
  lines.push("| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |");
  for (const shard of report.shards) {
    lines.push(
      `| ${shard.shard} | ${shard.suites} | ${shard.tests} | ${shard.failures} | ${shard.errors} | ${shard.skipped} | ${shard.time}s | ${
        shard.wall_seconds === null ? "-" : `${shard.wall_seconds}s`
      } |`,
    );
  }
  lines.push("");
  lines.push("## 校验");
  lines.push("");
  if (report.problems.length === 0) {
    lines.push(
      "全部通过：分片完整，每个基线类恰好执行一次，无额外类，用例数不减少，0 failure/error/skip。",
    );
  } else {
    for (const problem of report.problems) {
      lines.push(`- ✗ ${problem}`);
    }
  }
  lines.push("");
  return lines.join("\n");
}

/** 入口。 */
function main() {
  const options = parseOptions(process.argv.slice(2));
  if (options.help) {
    usage();
    return;
  }

  const baseline = loadBaseline(options.baselinePath);
  const shards = discoverShards(options.shardsRoot, options.shardCount);
  const { entries, problems, shardStats } = collectShardReports(shards);
  const validation = validateMerged(baseline, entries, shardStats);
  const allProblems = [...problems, ...validation.problems];
  const report = buildReport(
    baseline,
    shardStats,
    validation.totals,
    entries,
    allProblems,
  );

  mkdirSync(options.outDir, { recursive: true });
  const jsonPath = path.join(options.outDir, "test-shards-merged.json");
  const markdownPath = path.join(options.outDir, "test-shards-merged.md");
  writeFileSync(jsonPath, `${JSON.stringify(report, null, 2)}\n`, "utf8");
  writeFileSync(markdownPath, renderMarkdown(report), "utf8");

  console.log("后端分片合并校验");
  for (const shard of report.shards) {
    console.log(
      `  分片 ${shard.shard}（${shard.artifact}）: 类 ${shard.suites} / 用例 ${shard.tests} / ` +
        `failures=${shard.failures} errors=${shard.errors} skipped=${shard.skipped} ` +
        `suite=${shard.time}s${shard.wall_seconds === null ? "" : ` wall=${shard.wall_seconds}s`}`,
    );
  }
  console.log(
    `  总计: 类 ${report.totals.suites} / 用例 ${report.totals.tests} / ` +
      `failures=${report.totals.failures} errors=${report.totals.errors} skipped=${report.totals.skipped}`,
  );
  console.log(
    `  基线: 类 ${report.baseline.suite_count} / 用例 ${report.baseline.test_count}`,
  );
  if (allProblems.length > 0) {
    for (const problem of allProblems) {
      console.error(`  ✗ ${problem}`);
    }
    console.error(`结论: fail（${allProblems.length} 项问题）`);
  } else {
    console.log("结论: pass（4 片互斥完整，每个用例恰好执行一次）");
  }
  console.log(`已写入: ${jsonPath}`);
  console.log(`已写入: ${markdownPath}`);

  if (allProblems.length > 0) {
    process.exitCode = 1;
  }
}

try {
  main();
} catch (error) {
  console.error(`[test-merge-shards] ${error.message}`);
  process.exitCode = 1;
}
