#!/usr/bin/env node
// M19 P0 测试计时报告：汇总 surefire 报告，输出模块/总计与最慢测试类，供 CI artifact 与预算
// 测量使用。只做测量，不改变测试集合、不跳过、不放宽断言；不写入仓库状态。
//
// 扫描范围：<reports-root>/apps/*/target/surefire-reports 与
//           <reports-root>/platform/*/target/surefire-reports 下的 TEST-*.xml。
//           platform/* 与 apps/* 同等对待，避免只统计业务应用而漏掉共享层测试。
//
// 用法：
//   node scripts/test-timings.mjs
//   node scripts/test-timings.mjs --out-dir .ci/test-timings
//   node scripts/test-timings.mjs --reports-root /tmp/fake-root   # 负向/自测用
//
// 解析失败处理：任一份 TEST-*.xml 无法解析（缺 <testsuite>、缺必需属性、数值非法）时
// 直接失败并以非 0 退出，不跳过、不静默吞掉。理由是计时报告要作为预算依据，静默跳过会
// 让“全量套件”看起来更小更快，形成假绿；失败信息明确指出文件路径与原因。
//
// 退出码：0 表示至少解析出一份报告；1 表示参数错误、报告目录缺失、报告为空或解析失败。

import {
  closeSync,
  existsSync,
  mkdirSync,
  openSync,
  readdirSync,
  readSync,
  writeFileSync,
} from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

/** 仓库根目录（脚本位于 scripts/）。 */
const repoRoot = path.resolve(fileURLToPath(new URL("..", import.meta.url)));

/** 需要扫描的子项目目录（相对 reports-root）。 */
const SCANNED_ROOTS = ["apps", "platform"];

/** surefire 报告子目录名。 */
const SUREFIRE_DIRECTORY = path.join("target", "surefire-reports");

/** top-N 最慢测试类的 N。 */
const TOP_N = 20;

/** 读取 XML 头部时最多读取的字节数；<testsuite> 起始标签远小于该值。 */
const XML_PREFIX_BYTES = 64 * 1024;

/**
 * 解析命令行参数。
 *
 * @param {string[]} args 参数列表
 * @returns {{reportsRoot: string, outDir: string|null, help: boolean}}
 */
function parseOptions(args) {
  const options = { reportsRoot: repoRoot, outDir: null, help: false };
  for (let index = 0; index < args.length; index += 1) {
    const arg = args[index];
    if (arg === "--") {
      // pnpm 转发参数时会保留分隔符；忽略它，保证 `pnpm ... -- --out-dir x` 可用。
      continue;
    }
    if (arg === "--reports-root") {
      const value = args[index + 1];
      if (!value || value.startsWith("--")) {
        throw new Error("--reports-root 需要一个目录");
      }
      options.reportsRoot = path.resolve(value);
      index += 1;
    } else if (arg === "--out-dir") {
      const value = args[index + 1];
      if (!value || value.startsWith("--")) {
        throw new Error("--out-dir 需要一个目录");
      }
      options.outDir = path.resolve(value);
      index += 1;
    } else if (arg === "--help" || arg === "-h") {
      options.help = true;
    } else {
      throw new Error(`未知参数: ${arg}`);
    }
  }
  return options;
}

/**
 * 打印用法。
 */
function usage() {
  console.log(`用法: node scripts/test-timings.mjs [--reports-root <dir>] [--out-dir <dir>]

汇总 apps/* 与 platform/* 的 surefire 报告，输出用例数、失败/错误/跳过、累计 suite 时间
与最慢 ${TOP_N} 个测试类。报告缺失或为空时非 0 退出。

选项:
  --reports-root <dir>  指定报告根目录（默认仓库根），便于负向测试
  --out-dir <dir>       额外写入 test-timings.md 与 test-timings.json（CI artifact 用）
  --help                显示本帮助

退出码:
  0  至少解析出一份报告
  1  参数错误、报告目录缺失、报告为空或 XML 解析失败`);
}

/**
 * 列出目录下的子目录名；目录不存在时返回空数组。
 *
 * @param {string} directory 目录
 * @returns {string[]}
 */
function listDirectories(directory) {
  if (!existsSync(directory)) {
    return [];
  }
  return readdirSync(directory, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name)
    .sort();
}

/**
 * 收集所有待解析的 surefire 报告文件。
 *
 * @param {string} reportsRoot 报告根目录
 * @returns {Array<{module: string, file: string}>} 模块标识（相对路径）与文件绝对路径
 */
function collectReports(reportsRoot) {
  const reports = [];
  for (const group of SCANNED_ROOTS) {
    for (const project of listDirectories(path.join(reportsRoot, group))) {
      const directory = path.join(
        reportsRoot,
        group,
        project,
        SUREFIRE_DIRECTORY,
      );
      if (!existsSync(directory)) {
        continue;
      }
      const files = readdirSync(directory)
        .filter((name) => name.startsWith("TEST-") && name.endsWith(".xml"))
        .sort();
      for (const name of files) {
        reports.push({
          module: `${group}/${project}`,
          file: path.join(directory, name),
        });
      }
    }
  }
  return reports;
}

/**
 * 读取文件头部的字节，避免把带 system-out 的大报告整个读入内存。
 *
 * @param {string} file 文件路径
 * @returns {string}
 */
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

/**
 * 生成报告路径的显示形式：仓库内用相对路径，仓库外用绝对路径。
 *
 * @param {string} file 文件路径
 * @returns {string}
 */
function displayPath(file) {
  const relative = path.relative(repoRoot, file);
  return relative.startsWith("..") ? file : relative;
}

/**
 * 从 <testsuite> 起始标签解析属性。
 *
 * @param {string} file 文件路径
 * @returns {{name: string, tests: number, failures: number, errors: number, skipped: number, time: number}}
 */
function parseTestSuite(file) {
  const prefix = readXmlPrefix(file);
  const match = /<testsuite\b([^>]*)>/.exec(prefix);
  if (!match) {
    throw new Error("未找到 <testsuite> 起始标签");
  }
  const attributes = match[1];

  /** 读取单个属性值；缺失时返回 null。 */
  const attribute = (name) => {
    const found = new RegExp(`\\b${name}="([^"]*)"`).exec(attributes);
    return found ? found[1] : null;
  };

  /** 读取数值属性；缺失或非法时抛出错误。 */
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

/**
 * 汇总报告。
 *
 * @param {Array<{module: string, file: string}>} reports 报告列表
 * @returns {{modules: Array<object>, suites: Array<object>, totals: object}}
 */
function summarize(reports) {
  const modules = new Map();
  const suites = [];

  for (const report of reports) {
    let suite;
    try {
      suite = parseTestSuite(report.file);
    } catch (error) {
      throw new Error(
        `解析 surefire 报告失败: ${displayPath(report.file)}（${error.message}）`,
      );
    }

    if (!modules.has(report.module)) {
      modules.set(report.module, {
        module: report.module,
        suites: 0,
        tests: 0,
        failures: 0,
        errors: 0,
        skipped: 0,
        time: 0,
      });
    }
    const summary = modules.get(report.module);
    summary.suites += 1;
    summary.tests += suite.tests;
    summary.failures += suite.failures;
    summary.errors += suite.errors;
    summary.skipped += suite.skipped;
    summary.time += suite.time;

    suites.push({
      name: suite.name,
      module: report.module,
      tests: suite.tests,
      failures: suite.failures,
      errors: suite.errors,
      skipped: suite.skipped,
      time: suite.time,
    });
  }

  const totals = {
    suites: 0,
    tests: 0,
    failures: 0,
    errors: 0,
    skipped: 0,
    time: 0,
  };
  for (const summary of modules.values()) {
    totals.suites += summary.suites;
    totals.tests += summary.tests;
    totals.failures += summary.failures;
    totals.errors += summary.errors;
    totals.skipped += summary.skipped;
    totals.time += summary.time;
  }

  for (const summary of modules.values()) {
    summary.time = roundSeconds(summary.time);
  }
  totals.time = roundSeconds(totals.time);

  // 稳定排序：时间降序，时间相同按类名升序，再按模块名升序兜底。
  const topSlowest = suites
    .slice()
    .sort(
      (left, right) =>
        right.time - left.time ||
        left.name.localeCompare(right.name) ||
        left.module.localeCompare(right.module),
    )
    .slice(0, TOP_N)
    .map((suite) => ({ ...suite, time: roundSeconds(suite.time) }));

  return {
    modules: [...modules.values()].sort((left, right) =>
      left.module.localeCompare(right.module),
    ),
    suites: topSlowest,
    totals,
  };
}

/**
 * 保留 3 位小数的秒数，避免浮点尾数污染报告。
 *
 * @param {number} value 秒
 * @returns {number}
 */
function roundSeconds(value) {
  return Math.round(value * 1000) / 1000;
}

/**
 * 格式化秒数。
 *
 * @param {number} value 秒
 * @returns {string}
 */
function formatSeconds(value) {
  return `${value.toFixed(3)}s`;
}

/**
 * 生成北京时间（UTC+08:00）时间戳，符合项目“时间默认 Asia/Shanghai”的约定。
 *
 * @param {Date} [date] 时间
 * @returns {string} 带 +08:00 偏移的 ISO 8601 字符串
 */
function beijingTimestamp(date = new Date()) {
  const shifted = new Date(date.getTime() + 8 * 60 * 60 * 1000);
  return shifted.toISOString().replace("Z", "+08:00");
}

/**
 * 生成人读 Markdown 报告。
 *
 * @param {object} result 汇总结果
 * @param {string} reportsRoot 报告根目录
 * @param {string} generatedAt 生成时间
 * @returns {string}
 */
function renderMarkdown(result, reportsRoot, generatedAt) {
  const lines = [];
  lines.push("# 测试计时报告（surefire）");
  lines.push("");
  lines.push(`- 生成时间：${generatedAt}`);
  lines.push(`- 报告根目录：\`${reportsRoot}\``);
  lines.push(`- 用例总数：**${result.totals.tests}**`);
  lines.push(`- 累计 suite 时间：**${formatSeconds(result.totals.time)}**`);
  lines.push("");
  lines.push("## 按模块汇总");
  lines.push("");
  lines.push("| 模块 | suite | tests | failures | errors | skipped | 时间 |");
  lines.push("| --- | ---: | ---: | ---: | ---: | ---: | ---: |");
  for (const summary of result.modules) {
    lines.push(
      `| ${summary.module} | ${summary.suites} | ${summary.tests} | ${summary.failures} | ${summary.errors} | ${summary.skipped} | ${formatSeconds(summary.time)} |`,
    );
  }
  lines.push(
    `| **总计** | ${result.totals.suites} | ${result.totals.tests} | ${result.totals.failures} | ${result.totals.errors} | ${result.totals.skipped} | ${formatSeconds(result.totals.time)} |`,
  );
  lines.push("");
  lines.push(`## 最慢 ${TOP_N} 个测试类`);
  lines.push("");
  lines.push("| # | 测试类 | 模块 | tests | 时间 |");
  lines.push("| ---: | --- | --- | ---: | ---: |");
  result.suites.forEach((suite, index) => {
    lines.push(
      `| ${index + 1} | \`${suite.name}\` | ${suite.module} | ${suite.tests} | ${formatSeconds(suite.time)} |`,
    );
  });
  lines.push("");
  return lines.join("\n");
}

/**
 * 主流程。
 */
function main() {
  const options = parseOptions(process.argv.slice(2));
  if (options.help) {
    usage();
    return;
  }

  if (!existsSync(options.reportsRoot)) {
    throw new Error(`报告根目录不存在: ${options.reportsRoot}`);
  }
  const reports = collectReports(options.reportsRoot);
  if (reports.length === 0) {
    throw new Error(
      `未找到 surefire 报告：检查 apps/*/target/surefire-reports 与 platform/*/target/surefire-reports 下的 TEST-*.xml（报告根目录 ${options.reportsRoot}）`,
    );
  }

  const result = summarize(reports);

  console.log("测试计时报告（surefire）");
  console.log(`  报告根目录: ${options.reportsRoot}`);
  console.log(`  用例总数: ${result.totals.tests}`);
  console.log(
    `  失败/错误/跳过: ${result.totals.failures}/${result.totals.errors}/${result.totals.skipped}`,
  );
  console.log(`  累计 suite 时间: ${formatSeconds(result.totals.time)}`);
  console.log("");
  console.log("按模块汇总:");
  for (const summary of result.modules) {
    console.log(
      `  ${summary.module}: tests=${summary.tests} failures=${summary.failures} errors=${summary.errors} skipped=${summary.skipped} time=${formatSeconds(summary.time)}`,
    );
  }
  console.log("");
  console.log(`最慢 ${TOP_N} 个测试类:`);
  result.suites.forEach((suite, index) => {
    console.log(
      `  ${String(index + 1).padStart(2, " ")}. ${formatSeconds(suite.time).padStart(9, " ")}  ${suite.name} (${suite.module})`,
    );
  });

  if (options.outDir) {
    mkdirSync(options.outDir, { recursive: true });
    const generatedAt = beijingTimestamp();
    const markdownPath = path.join(options.outDir, "test-timings.md");
    const jsonPath = path.join(options.outDir, "test-timings.json");
    writeFileSync(
      markdownPath,
      renderMarkdown(result, options.reportsRoot, generatedAt),
      "utf8",
    );
    writeFileSync(
      jsonPath,
      `${JSON.stringify(
        {
          generated_at: generatedAt,
          reports_root: options.reportsRoot,
          totals: result.totals,
          modules: result.modules,
          top_slowest: result.suites,
        },
        null,
        2,
      )}\n`,
      "utf8",
    );
    console.log("");
    console.log(`已写入: ${markdownPath}`);
    console.log(`已写入: ${jsonPath}`);
  }
}

try {
  main();
} catch (error) {
  console.error(`[test-timings] ${error.message}`);
  process.exitCode = 1;
}
