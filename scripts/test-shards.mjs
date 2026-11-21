#!/usr/bin/env node
// M19 P2 后端测试分片：把一次全量 surefire 报告登记成“必须各跑一次”的基线，再按类名
// 确定性地切成 4 片，供 CI 矩阵生成 Maven 命令。
//
// 权威集合是 scripts/test-shards-baseline.json；测试集合（新增/删除/重命名测试类）变化时
// 必须先用一次全量运行重建基线并随代码提交：
//
//   RICCON_TEST_SERVICES=external RICCON_TEST_SERVICES_NAMESPACE=<ns> ./mvnw -q -T1C verify
//   node scripts/test-shards.mjs --update-baseline
//
// 分片算法（确定性，不依赖随机数与机器状态）：
// - 先按模块分组（apps/*、platform/*）；同一模块内按基线时间降序、类名升序排序；
// - 每个类分配给“本模块累计时间最小”的分片，同负载时优先类数少的分片，再优先分片号小；
// - 这样每个分片的 API 子集与 worker 子集都被独立摊平：Maven `-T1C` 并行构建 api/worker 时，
//   分片墙钟约等于 max(api 子集, worker 子集) + 固定开销，而不是两者之和。
//
// 防漏跑：每个分片用 Surefire 3.5.6 的排除语法运行“除其他 3 片之外的所有类”
// （-Dtest='!com.x.A,!com.y.B,...'）。未登记到基线的新测试类不会被任何排除列表命中，
// 因而会在 4 个分片里全部执行，合并脚本按集合校验时以“重复类”失败，不会静默漏跑。
//
// 用法：
//   node scripts/test-shards.mjs                     # 打印分片摘要
//   node scripts/test-shards.mjs --check             # 校验基线完整、4 片互斥、源码测试类已登记
//   node scripts/test-shards.mjs --shard 0 --print-test-filter
//   node scripts/test-shards.mjs --shard 0 --emit --out-dir .ci/test-shards
//   node scripts/test-shards.mjs --out-dir .ci/test-shards
//   node scripts/test-shards.mjs --update-baseline --reports-root <dir>
//
// 退出码：0 通过；1 参数错误、基线非法、分片不一致或源码扫描发现未登记测试类。

import {
  closeSync,
  copyFileSync,
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

/** 分片数；CI 矩阵与合并脚本共享同一常量。 */
const SHARD_COUNT = 4;

/** 默认基线文件路径。 */
const DEFAULT_BASELINE_PATH = path.join(
  repoRoot,
  "scripts",
  "test-shards-baseline.json",
);

/** 需要扫描的子项目目录（相对 reports-root 或仓库根）。 */
const SCANNED_ROOTS = ["apps", "platform"];

/** surefire 报告子目录名。 */
const SUREFIRE_DIRECTORY = path.join("target", "surefire-reports");

/** 源码目录相对模块根的路径。 */
const TEST_SOURCE_DIRECTORY = path.join("src", "test", "java");

/** 读取 XML 头部时最多读取的字节数；<testsuite> 起始标签远小于该值。 */
const XML_PREFIX_BYTES = 64 * 1024;

/**
 * JUnit 5 测试方法注解；用于源码扫描时区分“测试类”与命名为 Test* 的辅助类
 * （例如 apps/api 的 TestFiles、apps/worker 的 TestSchema 没有测试方法）。
 */
const TEST_ANNOTATION =
  /@(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b/;

/** 打印用法。 */
function usage() {
  console.log(`用法: node scripts/test-shards.mjs [options]

默认行为：打印 4 个分片的类数、用例数与基线时间。

选项:
  --check                        校验基线聚合值、4 片互斥完整、源码测试类均已登记
  --shard N                      指定分片（0..${SHARD_COUNT - 1}）
  --print-test-filter            打印该分片的 Surefire -Dtest 排除过滤器（单行）
  --emit                         写出该分片的分片清单 JSON（需配合 --shard 与 --out-dir）
  --out-dir <dir>                输出目录；不带 --shard 时写出全部 4 片的 shards.json
  --simulate <dir>               用 --reports-root 的现有报告模拟 4 片目录（本地演练合并校验）
  --update-baseline              从一次全量 surefire 报告重建基线
  --reports-root <dir>           重建基线时的报告根目录（默认仓库根）
  --generated-from <text>        重建基线时写入来源说明（默认自动生成）
  --baseline <file>              读取其他基线文件（默认 scripts/test-shards-baseline.json）
  --no-scan-sources              跳过 --check 的源码扫描
  --help                         显示本帮助

基线来源：一次全量、0 failure/error/skip 的 surefire 运行（CI 为 module gate 的
./mvnw -q -T1C verify jacoco:report）。测试集合变更必须重建基线并随代码提交。`);
}

/**
 * 解析命令行参数；未知参数直接失败，避免静默使用默认值。
 *
 * @param {string[]} args 参数列表
 * @returns {object}
 */
function parseOptions(args) {
  const options = {
    check: false,
    updateBaseline: false,
    reportsRoot: repoRoot,
    outDir: null,
    shard: null,
    printTestFilter: false,
    emit: false,
    simulateDir: null,
    baselinePath: DEFAULT_BASELINE_PATH,
    scanSources: true,
    generatedFrom: null,
    help: false,
  };
  for (let index = 0; index < args.length; index += 1) {
    const arg = args[index];
    if (arg === "--check") {
      options.check = true;
    } else if (arg === "--update-baseline") {
      options.updateBaseline = true;
    } else if (arg === "--print-test-filter") {
      options.printTestFilter = true;
    } else if (arg === "--emit") {
      options.emit = true;
    } else if (arg === "--no-scan-sources") {
      options.scanSources = false;
    } else if (arg === "--shard") {
      options.shard = parseShard(requireValue(args, ++index, arg));
    } else if (arg === "--out-dir") {
      options.outDir = path.resolve(requireValue(args, ++index, arg));
    } else if (arg === "--simulate") {
      options.simulateDir = path.resolve(requireValue(args, ++index, arg));
    } else if (arg === "--reports-root") {
      options.reportsRoot = path.resolve(requireValue(args, ++index, arg));
    } else if (arg === "--generated-from") {
      options.generatedFrom = requireValue(args, ++index, arg);
    } else if (arg === "--baseline") {
      options.baselinePath = path.resolve(requireValue(args, ++index, arg));
    } else if (arg === "--help" || arg === "-h") {
      options.help = true;
    } else {
      throw new Error(`未知参数: ${arg}`);
    }
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

/** 解析分片号并校验范围。 */
function parseShard(raw) {
  const shard = Number(raw);
  if (!Number.isInteger(shard) || shard < 0 || shard >= SHARD_COUNT) {
    throw new Error(
      `--shard 必须是 0..${SHARD_COUNT - 1} 的整数，收到: ${raw}`,
    );
  }
  return shard;
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

/** 列出存在 src/test/java 的模块标识（apps/api、platform/foo）。 */
function listTestModules() {
  const modules = [];
  for (const group of SCANNED_ROOTS) {
    for (const project of listDirectories(path.join(repoRoot, group))) {
      const module = `${group}/${project}`;
      if (existsSync(path.join(repoRoot, module, TEST_SOURCE_DIRECTORY))) {
        modules.push(module);
      }
    }
  }
  return modules;
}

/**
 * 收集 surefire 报告文件。
 *
 * @param {string} reportsRoot 报告根目录
 * @returns {Array<{module: string, file: string}>}
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

/** 读取文件头部的字节，避免把带 system-out 的大报告整个读入内存。 */
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
 * 从 <testsuite> 起始标签解析必需属性；缺失或非法时抛出，绝不静默跳过。
 *
 * @param {string} file 报告文件
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

/** 保留 3 位小数，避免浮点尾数污染基线。 */
function roundSeconds(value) {
  return Math.round(value * 1000) / 1000;
}

/** 生成北京时间（UTC+08:00）时间戳。 */
function beijingTimestamp(date = new Date()) {
  const shifted = new Date(date.getTime() + 8 * 60 * 60 * 1000);
  return shifted.toISOString().replace("Z", "+08:00");
}

/** 生成报告路径的显示形式：仓库内用相对路径，仓库外用绝对路径。 */
function displayPath(file) {
  const relative = path.relative(repoRoot, file);
  return relative.startsWith("..") ? file : relative;
}

/**
 * 从一次全量 surefire 报告重建基线。
 *
 * 失败/错误/跳过必须为 0：用一次红色或含跳过用例的运行做基线，会把失败和漏跑洗成“基线”，
 * 因此这里直接拒绝，而不是记录到基线里。
 *
 * @param {string} reportsRoot 报告根目录
 * @param {string|null} generatedFrom 来源说明
 * @returns {object} 基线对象
 */
function buildBaseline(reportsRoot, generatedFrom) {
  if (!existsSync(reportsRoot)) {
    throw new Error(`报告根目录不存在: ${reportsRoot}`);
  }
  const reports = collectReports(reportsRoot);
  if (reports.length === 0) {
    throw new Error(
      `未找到 surefire 报告：检查 ${SCANNED_ROOTS.join("/*、")}/*/${SUREFIRE_DIRECTORY}/TEST-*.xml（报告根目录 ${reportsRoot}）`,
    );
  }

  const classes = [];
  const sourceClasses = new Set(
    scanSourceTestClasses().map((source) => source.class),
  );
  const unknownReports = [];
  let failures = 0;
  let errors = 0;
  let skipped = 0;
  let time = 0;
  for (const report of reports) {
    let suite;
    try {
      suite = parseTestSuite(report.file);
    } catch (error) {
      throw new Error(
        `解析 surefire 报告失败: ${displayPath(report.file)}（${error.message}）`,
      );
    }
    if (!sourceClasses.has(suite.name)) {
      // Surefire 不会清理 target/surefire-reports：测试类重命名/换模块后，旧 XML 会一直残留，
      // 把“幽灵类”混进基线，导致分片合并在真实运行里永远缺类。这里直接失败。
      unknownReports.push(`${suite.name}（${displayPath(report.file)}）`);
    }
    failures += suite.failures;
    errors += suite.errors;
    skipped += suite.skipped;
    time += suite.time;
    classes.push({
      class: suite.name,
      module: report.module,
      tests: suite.tests,
      time: roundSeconds(suite.time),
    });
  }
  if (unknownReports.length > 0) {
    throw new Error(
      `报告中的测试类找不到带测试注解的源码（多为重命名后残留的旧 surefire 报告）：\n  - ${unknownReports.join(
        "\n  - ",
      )}\n先删除相应 target/surefire-reports 再跑一次全量，然后重建基线`,
    );
  }
  const reportedClasses = new Set(classes.map((entry) => entry.class));
  const missingReports = [...sourceClasses]
    .filter((className) => !reportedClasses.has(className))
    .sort();
  if (missingReports.length > 0) {
    throw new Error(
      `源码测试类没有出现在全量报告里（未执行或被 surefire 排除，不能建成基线）：\n  - ${missingReports.join(
        "\n  - ",
      )}`,
    );
  }

  classes.sort(
    (left, right) =>
      left.class.localeCompare(right.class) ||
      left.module.localeCompare(right.module),
  );
  const seen = new Set();
  for (const entry of classes) {
    if (seen.has(entry.class)) {
      throw new Error(
        `全量报告里出现重复测试类: ${entry.class}；请先修正重复报告再重建基线`,
      );
    }
    seen.add(entry.class);
  }

  const baseline = {
    generated_from:
      generatedFrom ??
      `node scripts/test-shards.mjs --update-baseline --reports-root ${displayPath(reportsRoot)}`,
    generated_at: beijingTimestamp(),
    shards: SHARD_COUNT,
    suite_count: classes.length,
    test_count: classes.reduce((sum, entry) => sum + entry.tests, 0),
    failure_count: failures,
    error_count: errors,
    skip_count: skipped,
    time_total_seconds: roundSeconds(time),
    classes,
  };

  if (failures !== 0 || errors !== 0 || skipped !== 0) {
    throw new Error(
      `拒绝用非绿色运行重建基线：failures=${failures} errors=${errors} skipped=${skipped}；` +
        "先修到 0 failure/error/skip，再重建基线",
    );
  }
  return baseline;
}

/**
 * 读取并校验基线文件。
 *
 * @param {string} baselinePath 基线路径
 * @returns {object} 基线
 */
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
  const problems = validateBaseline(baseline);
  if (problems.length > 0) {
    throw new Error(
      `基线非法（${baselinePath}）：\n  - ${problems.join("\n  - ")}`,
    );
  }
  return baseline;
}

/**
 * 校验基线自身的一致性：结构、唯一性、聚合值与排序。
 *
 * @param {object} baseline 基线对象
 * @returns {string[]} 问题列表；为空表示通过
 */
function validateBaseline(baseline) {
  const problems = [];
  if (!baseline || typeof baseline !== "object") {
    return ["基线不是 JSON 对象"];
  }
  if (baseline.shards !== SHARD_COUNT) {
    problems.push(`shards 必须为 ${SHARD_COUNT}，实际 ${baseline.shards}`);
  }
  if (typeof baseline.generated_from !== "string" || !baseline.generated_from) {
    problems.push("缺少 generated_from");
  }
  const classes = baseline.classes;
  if (!Array.isArray(classes) || classes.length === 0) {
    return [...problems, "classes 必须是非空数组"];
  }
  const seen = new Set();
  let testTotal = 0;
  let timeTotal = 0;
  let previous = null;
  for (const [index, entry] of classes.entries()) {
    const label = `classes[${index}]`;
    if (!entry || typeof entry !== "object") {
      problems.push(`${label} 不是对象`);
      continue;
    }
    if (typeof entry.class !== "string" || !entry.class) {
      problems.push(`${label}.class 缺失`);
      continue;
    }
    if (!/^(apps|platform)\/[^/]+$/.test(entry.module ?? "")) {
      problems.push(`${label}.module 非法: ${entry.module}`);
    }
    if (!Number.isInteger(entry.tests) || entry.tests < 0) {
      problems.push(`${label}.tests 非法: ${entry.tests}`);
    }
    if (typeof entry.time !== "number" || entry.time < 0) {
      problems.push(`${label}.time 非法: ${entry.time}`);
    }
    if (seen.has(entry.class)) {
      problems.push(`重复测试类: ${entry.class}`);
    }
    seen.add(entry.class);
    if (previous !== null && previous.localeCompare(entry.class) > 0) {
      problems.push(`classes 未按类名升序: ${previous} 在 ${entry.class} 之前`);
    }
    previous = entry.class;
    testTotal += Number.isInteger(entry.tests) ? entry.tests : 0;
    timeTotal += typeof entry.time === "number" ? entry.time : 0;
  }
  if (baseline.suite_count !== classes.length) {
    problems.push(
      `suite_count=${baseline.suite_count} 与 classes.length=${classes.length} 不一致`,
    );
  }
  if (baseline.test_count !== testTotal) {
    problems.push(
      `test_count=${baseline.test_count} 与 classes 汇总=${testTotal} 不一致`,
    );
  }
  const roundedTime = Math.round(timeTotal * 1000) / 1000;
  if (Math.abs((baseline.time_total_seconds ?? -1) - roundedTime) > 0.001) {
    problems.push(
      `time_total_seconds=${baseline.time_total_seconds} 与 classes 汇总=${roundedTime} 不一致`,
    );
  }
  for (const key of ["failure_count", "error_count", "skip_count"]) {
    if (baseline[key] !== 0) {
      problems.push(`${key} 必须为 0，实际 ${baseline[key]}`);
    }
  }
  return problems;
}

/**
 * 把基线切成 4 个分片（确定性：同一基线永远得到同一结果）。
 *
 * @param {Array<{class: string, module: string, tests: number, time: number}>} classes 基线类
 * @returns {Array<object>} 分片数组，按分片号升序
 */
function partitionClasses(classes) {
  const byModule = new Map();
  for (const entry of classes) {
    if (!byModule.has(entry.module)) {
      byModule.set(entry.module, []);
    }
    byModule.get(entry.module).push(entry);
  }

  const shards = Array.from({ length: SHARD_COUNT }, (_, shard) => ({
    shard,
    classes: [],
    modules: new Map(),
  }));

  for (const module of [...byModule.keys()].sort()) {
    const moduleClasses = byModule
      .get(module)
      .slice()
      .sort(
        (left, right) =>
          right.time - left.time || left.class.localeCompare(right.class),
      );
    const loads = new Array(SHARD_COUNT).fill(0);
    for (const entry of moduleClasses) {
      let target = 0;
      for (let shard = 1; shard < SHARD_COUNT; shard += 1) {
        const betterLoad = loads[shard] < loads[target] - 1e-9;
        const sameLoad = Math.abs(loads[shard] - loads[target]) <= 1e-9;
        const fewerClasses =
          shards[shard].modules.get(module)?.classes.length ?? 0;
        const targetClasses =
          shards[target].modules.get(module)?.classes.length ?? 0;
        if (betterLoad || (sameLoad && fewerClasses < targetClasses)) {
          target = shard;
        }
      }
      loads[target] += entry.time;
      shards[target].classes.push(entry);
      if (!shards[target].modules.has(module)) {
        shards[target].modules.set(module, {
          module,
          classes: [],
          tests: 0,
          time: 0,
        });
      }
      const summary = shards[target].modules.get(module);
      summary.classes.push(entry.class);
      summary.tests += entry.tests;
      summary.time += entry.time;
    }
  }

  return shards.map((shard) => {
    const modules = [...shard.modules.values()]
      .map((summary) => ({
        module: summary.module,
        class_count: summary.classes.length,
        tests: summary.tests,
        time: roundSeconds(summary.time),
        classes: summary.classes.slice().sort(),
      }))
      .sort((left, right) => left.module.localeCompare(right.module));
    return {
      shard: shard.shard,
      class_count: shard.classes.length,
      tests: shard.classes.reduce((sum, entry) => sum + entry.tests, 0),
      baseline_time_seconds: roundSeconds(
        shard.classes.reduce((sum, entry) => sum + entry.time, 0),
      ),
      classes: shard.classes.map((entry) => entry.class).sort(),
      modules,
    };
  });
}

/**
 * 生成分片的 Surefire 排除过滤器：`!其他三片的每一个类`。
 *
 * 未登记的新测试类不会被排除，因此会在所有分片执行，合并时以重复类失败；
 * 这是“防漏跑”的核心，不依赖源码命名扫描。
 *
 * @param {Array<object>} shards 全部分片
 * @param {number} shard 目标分片
 * @returns {string} -Dtest 的值
 */
function buildTestFilter(shards, shard) {
  const excluded = shards
    .filter((candidate) => candidate.shard !== shard)
    .flatMap((candidate) => candidate.classes)
    .sort();
  if (excluded.length === 0) {
    throw new Error("排除列表为空；基线不可能只有 1 个分片的类");
  }
  return excluded.map((name) => `!${name}`).join(",");
}

/**
 * 扫描源码中的测试类，返回“命中 Surefire 默认命名且包含 JUnit 5 注解”的 FQCN。
 *
 * 只用于 --check 的静态防漏跑提示：命名为 Test* 但没有测试方法的辅助类
 * （apps/api 的 TestFiles、apps/worker 的 TestSchema 等）不会被误报。
 *
 * @returns {Array<{class: string, module: string, file: string}>}
 */
function scanSourceTestClasses() {
  const found = [];
  for (const module of listTestModules()) {
    const sourceRoot = path.join(repoRoot, module, TEST_SOURCE_DIRECTORY);
    for (const file of walkJavaFiles(sourceRoot)) {
      const baseName = path.basename(file).replace(/\.java$/, "");
      if (!matchesSurefireDefault(baseName)) {
        continue;
      }
      const content = readFileSync(file, "utf8");
      if (!TEST_ANNOTATION.test(content)) {
        continue;
      }
      const className = path
        .relative(sourceRoot, file)
        .replace(/\.java$/, "")
        .split(path.sep)
        .join(".");
      found.push({ class: className, module, file });
    }
  }
  return found;
}

/** 递归列出目录下的 .java 文件。 */
function walkJavaFiles(directory) {
  const files = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const fullPath = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      files.push(...walkJavaFiles(fullPath));
    } else if (entry.name.endsWith(".java")) {
      files.push(fullPath);
    }
  }
  return files;
}

/** Surefire 默认 includes：Test*.java、*Test.java、*Tests.java、*TestCase.java。 */
function matchesSurefireDefault(baseName) {
  return (
    baseName.startsWith("Test") ||
    baseName.endsWith("Test") ||
    baseName.endsWith("Tests") ||
    baseName.endsWith("TestCase")
  );
}

/**
 * 分片自检：基线聚合值、4 片互斥完整、源码测试类均已登记。
 *
 * @param {object} baseline 基线
 * @param {Array<object>} shards 分片
 * @param {boolean} scanSources 是否扫描源码
 * @returns {string[]} 问题列表
 */
function checkPartition(baseline, shards, scanSources) {
  const problems = [];
  const baselineClasses = new Map(
    baseline.classes.map((entry) => [entry.class, entry]),
  );
  const assigned = new Map();
  for (const shard of shards) {
    for (const className of shard.classes) {
      if (assigned.has(className)) {
        problems.push(
          `${className} 同时出现在分片 ${assigned.get(className)} 与 ${shard.shard}`,
        );
      }
      assigned.set(className, shard.shard);
    }
  }
  for (const className of baselineClasses.keys()) {
    if (!assigned.has(className)) {
      problems.push(`基线类未被任何分片覆盖: ${className}`);
    }
  }
  for (const className of assigned.keys()) {
    if (!baselineClasses.has(className)) {
      problems.push(`分片出现基线之外的类: ${className}`);
    }
  }
  if (assigned.size !== baselineClasses.size) {
    problems.push(
      `分片并集 ${assigned.size} 与基线 ${baselineClasses.size} 不一致`,
    );
  }
  const shardTests = shards.reduce((sum, shard) => sum + shard.tests, 0);
  if (shardTests !== baseline.test_count) {
    problems.push(
      `4 片用例汇总 ${shardTests} 与基线 test_count=${baseline.test_count} 不一致`,
    );
  }

  if (scanSources) {
    const registered = new Set(baselineClasses.keys());
    const sourceClasses = new Set();
    for (const source of scanSourceTestClasses()) {
      sourceClasses.add(source.class);
      if (!registered.has(source.class)) {
        problems.push(
          `源码测试类未登记到基线: ${source.class}（${displayPath(source.file)}）；` +
            "跑一次全量后执行 --update-baseline",
        );
      }
    }
    for (const className of registered) {
      if (!sourceClasses.has(className)) {
        problems.push(
          `基线类找不到带测试注解的源码: ${className}；删除测试后必须重建基线`,
        );
      }
    }
  }
  return problems;
}

/**
 * 用现有全量 surefire 报告模拟 4 个分片目录：按分片清单把每个类的报告复制到
 * `<out-dir>/shard-<N>/<模块>/target/surefire-reports/`，供本地演练合并校验（不参与 CI）。
 *
 * @param {Array<object>} shards 分片
 * @param {string} reportsRoot 全量报告根目录
 * @param {string} outDir 模拟输出目录
 */
function simulateShards(shards, reportsRoot, outDir) {
  const index = new Map();
  for (const report of collectReports(reportsRoot)) {
    const suite = parseTestSuite(report.file);
    index.set(suite.name, {
      file: report.file,
      relative: path.relative(reportsRoot, report.file),
    });
  }
  let copied = 0;
  for (const shard of shards) {
    for (const className of shard.classes) {
      const report = index.get(className);
      if (!report) {
        throw new Error(
          `分片 ${shard.shard} 的类找不到 surefire 报告: ${className}（先跑一次全量）`,
        );
      }
      const target = path.join(outDir, `shard-${shard.shard}`, report.relative);
      mkdirSync(path.dirname(target), { recursive: true });
      copyFileSync(report.file, target);
      copied += 1;
    }
  }
  console.log(
    `已模拟 ${shards.length} 片 / ${copied} 份报告: ${outDir}（仅本地演练，不参与 CI）`,
  );
}

/**
 * 输出目录内写入 JSON；自动创建父目录。
 *
 * @param {string} directory 目录
 * @param {string} fileName 文件名
 * @param {object} payload 内容
 * @returns {string} 写入路径
 */
function writeJson(directory, fileName, payload) {
  mkdirSync(directory, { recursive: true });
  const filePath = path.join(directory, fileName);
  writeFileSync(filePath, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
  return filePath;
}

/** 打印分片摘要表。 */
function renderSummary(baseline, shards) {
  console.log(
    `基线: ${baseline.suite_count} 个测试类 / ${baseline.test_count} 个用例 / 累计 ${baseline.time_total_seconds}s（${baseline.generated_from}）`,
  );
  console.log("");
  console.log("| 分片 | 类数 | 用例 | 基线时间 (s) | 模块 |");
  console.log("| ---: | ---: | ---: | ---: | --- |");
  for (const shard of shards) {
    const modules = shard.modules
      .map((module) => `${module.module}=${module.tests}`)
      .join(", ");
    console.log(
      `| ${shard.shard} | ${shard.class_count} | ${shard.tests} | ${shard.baseline_time_seconds} | ${modules} |`,
    );
  }
  console.log("");
  console.log(
    `合计: ${shards.reduce((sum, shard) => sum + shard.class_count, 0)} 类 / ${shards.reduce((sum, shard) => sum + shard.tests, 0)} 用例`,
  );
}

/** 入口：解析参数并执行。 */
function main() {
  const options = parseOptions(process.argv.slice(2));
  if (options.help) {
    usage();
    return;
  }

  if (options.updateBaseline) {
    const baseline = buildBaseline(options.reportsRoot, options.generatedFrom);
    const problems = validateBaseline(baseline);
    if (problems.length > 0) {
      throw new Error(
        `重建后的基线未通过自检：\n  - ${problems.join("\n  - ")}`,
      );
    }
    let targetPath = options.baselinePath;
    if (options.outDir) {
      targetPath = writeJson(
        options.outDir,
        "test-shards-baseline.json",
        baseline,
      );
    } else {
      mkdirSync(path.dirname(options.baselinePath), { recursive: true });
      writeFileSync(
        options.baselinePath,
        `${JSON.stringify(baseline, null, 2)}\n`,
        "utf8",
      );
    }
    console.log(
      `已重建基线: ${targetPath}（${baseline.suite_count} 类 / ${baseline.test_count} 用例 / ${baseline.time_total_seconds}s）`,
    );
    return;
  }

  const baseline = loadBaseline(options.baselinePath);
  const shards = partitionClasses(baseline.classes);

  if (options.simulateDir) {
    simulateShards(shards, options.reportsRoot, options.simulateDir);
    return;
  }

  if (options.printTestFilter) {
    if (options.shard === null) {
      throw new Error("--print-test-filter 需要 --shard N");
    }
    console.log(buildTestFilter(shards, options.shard));
    return;
  }

  if (options.check) {
    const problems = checkPartition(
      baseline,
      shards,
      options.scanSources !== false,
    );
    if (problems.length > 0) {
      throw new Error(`分片自检失败：\n  - ${problems.join("\n  - ")}`);
    }
    console.log(
      `分片自检通过：${baseline.suite_count} 类 / ${baseline.test_count} 用例，4 片互斥完整` +
        (options.scanSources ? "，源码测试类均已登记" : "（跳过源码扫描）"),
    );
    return;
  }

  if (options.emit) {
    if (options.shard === null || !options.outDir) {
      throw new Error("--emit 需要 --shard N 与 --out-dir <dir>");
    }
    const shard = shards[options.shard];
    const targetPath = writeJson(
      options.outDir,
      `shard-${options.shard}.json`,
      {
        shard: shard.shard,
        total_shards: SHARD_COUNT,
        generated_at: beijingTimestamp(),
        baseline: {
          generated_from: baseline.generated_from,
          suite_count: baseline.suite_count,
          test_count: baseline.test_count,
        },
        class_count: shard.class_count,
        tests: shard.tests,
        baseline_time_seconds: shard.baseline_time_seconds,
        test_filter: buildTestFilter(shards, options.shard),
        classes: shard.classes,
        modules: shard.modules,
      },
    );
    console.log(
      `已写出分片清单: ${targetPath}（${shard.class_count} 类 / ${shard.tests} 用例）`,
    );
    return;
  }

  if (options.outDir) {
    const targetPath = writeJson(options.outDir, "shards.json", {
      generated_at: beijingTimestamp(),
      baseline: {
        generated_from: baseline.generated_from,
        suite_count: baseline.suite_count,
        test_count: baseline.test_count,
        time_total_seconds: baseline.time_total_seconds,
      },
      total_shards: SHARD_COUNT,
      shards,
    });
    console.log(`已写出全部分片清单: ${targetPath}`);
    renderSummary(baseline, shards);
    return;
  }

  renderSummary(baseline, shards);
}

try {
  main();
} catch (error) {
  console.error(`[test-shards] ${error.message}`);
  process.exitCode = 1;
}
