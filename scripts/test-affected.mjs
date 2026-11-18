#!/usr/bin/env node
// M12-B 受影响测试映射：根据 git 变更给出 L1 内层循环的定向测试命令与理由。
//
// 只输出建议，不执行命令，也不写入任何状态。它不是测试结果，不能作为任务完成或模块
// gate 证据；任务收尾与 gate 仍按 docs/CODING-STANDARDS.md §10.1 使用合同全量命令。
//
// 用法：
//   node scripts/test-affected.mjs                       # 工作树相对 HEAD 的变更 + 未跟踪文件
//   node scripts/test-affected.mjs --staged              # 暂存区变更 + 未跟踪文件
//   node scripts/test-affected.mjs --base origin/main    # 相对指定 revision 的变更 + 未跟踪文件
//   node scripts/test-affected.mjs --dry-run             # 显式 dry-run（脚本本身从不执行命令）
//
// 退出码：0 表示完成映射（可能包含“回退模块全量”或“手册检查”）；1 表示参数错误、
// git 失败或存在无法识别的路径，避免静默返回空建议。

import { spawnSync } from "node:child_process";
import { existsSync, readdirSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

/** 仓库根目录（脚本位于 scripts/）。 */
const repoRoot = path.resolve(fileURLToPath(new URL("..", import.meta.url)));

/** 需要递归扫描测试时跳过的目录。 */
const SKIP_DIRECTORIES = new Set([
  ".git",
  ".codegraph",
  ".riccon",
  "node_modules",
  "target",
  "dist",
  "coverage",
  "playwright-report",
  "test-results",
  ".umi",
  ".umi-production",
]);

/** 视为共享/横切的 API 生产包：任一变化都可能影响全部集成测试。 */
const SHARED_API_FEATURES = new Set([
  "admin",
  "audit",
  "common",
  "config",
  "error",
  "persistence",
  "web",
]);

/** 视为共享/横切的 Worker 生产包：任一变化都可能影响全部集成测试。 */
const SHARED_WORKER_FEATURES = new Set(["common", "config", "persistence"]);

/**
 * API feature → 额外测试名关键词。
 *
 * 默认规则已经会命中“同包测试 + 类名包含 feature”的测试；这里补充跨包但语义相关的
 * 集成测试，避免只改 service 时漏掉 API 旅程测试。新增测试类时同步维护此表。
 */
const API_FEATURE_ALIASES = {
  research: ["Research", "Artifact", "Approval", "RunDetail"],
  file: ["File"],
  knowledge: ["Knowledge", "ContentScope"],
  workspace: ["Workspace", "ChatScope"],
  department: ["Department", "WorkspaceScope"],
  organization: ["Department", "WorkspaceScope"],
  chat: ["Chat", "Conversation", "RunDetail"],
  auth: ["Auth", "TenantIsolation", "LoginRateLimiter"],
  identity: ["Auth", "TenantIsolation", "RoleDefinitions", "LoginRateLimiter"],
  run: ["RunDetail", "Research", "Artifact"],
  mcp: ["Mcp"],
  outbox: ["Research", "RunDetail"],
};

/** Worker feature → 额外测试名关键词；维护方式同 API_FEATURE_ALIASES。 */
const WORKER_FEATURE_ALIASES = {
  research: [
    "Research",
    "Hitl",
    "ReportArtifact",
    "Approval",
    "RunStageDetails",
  ],
  agent: ["KnowledgeRetriever", "UntrustedData"],
  index: ["KnowledgeIndex", "OpenSearch", "Hashing", "Embedding"],
  parse: [
    "FileParse",
    "S3FileContentReader",
    "DocumentParser",
    "TextChunker",
    "Parse",
  ],
  chat: ["Chat", "RunCancellation", "ServiceToken"],
  mcp: ["Mcp"],
  model: ["Model", "OpenAi"],
  run: ["Outbox", "EventPayload", "RunCancellation"],
  crypto: ["Envelope", "MasterKey"],
  audit: ["AuditDetails"],
};

/** 根目录下已知的构建/文档文件；其余根路径视为未知输入。 */
const ROOT_BACKEND_FILES = new Set(["pom.xml", "mvnw", "mvnw.cmd"]);
const ROOT_FRONTEND_FILES = new Set([
  "package.json",
  "pnpm-lock.yaml",
  "pnpm-workspace.yaml",
]);
const ROOT_MANUAL_FILES = new Set([
  "AGENTS.md",
  "README.md",
  "LICENSE",
  ".gitignore",
  ".editorconfig",
  ".npmrc",
  ".nvmrc",
]);

/**
 * 解析命令行参数。
 *
 * @param {string[]} args 原始参数
 * @returns {{base: string|null, staged: boolean, dryRun: boolean}}
 */
function parseOptions(args) {
  const options = { base: null, staged: false, dryRun: false };
  for (let index = 0; index < args.length; index += 1) {
    const arg = args[index];
    if (arg === "--") {
      // pnpm 转发参数时会保留分隔符；忽略它，保证 `pnpm test:affected -- --dry-run` 可用。
      continue;
    }
    if (arg === "--base") {
      if (options.staged) {
        throw new Error("--base 与 --staged 不能同时使用");
      }
      const value = args[index + 1];
      if (!value || value.startsWith("--")) {
        throw new Error("--base 需要一个 revision");
      }
      options.base = value;
      index += 1;
    } else if (arg === "--staged") {
      if (options.base) {
        throw new Error("--staged 与 --base 不能同时使用");
      }
      options.staged = true;
    } else if (arg === "--dry-run") {
      options.dryRun = true;
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
  console.log(`用法: node scripts/test-affected.mjs [--base <rev> | --staged] [--dry-run]

只根据 git 变更输出 L1 内层循环的测试建议；不执行命令，不能替代模块 gate。

选项:
  --base <rev>   比较工作树与指定 revision（例如 HEAD~1、origin/main）
  --staged       只看暂存区变更（仍包含未跟踪文件）
  --dry-run      显式标记 dry-run（脚本从不执行命令）
  --help         显示本帮助

退出码:
  0  映射完成（可能包含模块全量回退或手册检查）
  1  参数/git 错误，或存在无法识别的路径`);
}

/**
 * 执行 git 并返回 stdout；失败时抛出带 stderr 的错误。
 *
 * @param {string[]} args git 参数
 * @returns {string}
 */
function git(args) {
  const result = spawnSync("git", args, { cwd: repoRoot, encoding: "utf8" });
  if (result.error) {
    throw new Error(`git ${args.join(" ")} 启动失败: ${result.error.message}`);
  }
  if (result.status !== 0) {
    throw new Error(
      `git ${args.join(" ")} 失败: ${(result.stderr || result.stdout || "").trim()}`,
    );
  }
  return result.stdout || "";
}

/**
 * 校验并返回变更文件列表。
 *
 * @param {{base: string|null, staged: boolean}} options 变更来源
 * @returns {{source: string, files: string[]}}
 */
function collectChangedFiles(options) {
  let tracked = "";
  let source;
  if (options.staged) {
    tracked = git(["diff", "--cached", "--name-only"]);
    source = "暂存区（git diff --cached）";
  } else if (options.base) {
    const verify = spawnSync(
      "git",
      ["rev-parse", "--verify", `${options.base}^{commit}`],
      {
        cwd: repoRoot,
        encoding: "utf8",
      },
    );
    if (verify.status !== 0) {
      throw new Error(
        `无法解析 --base ${options.base}: ${(verify.stderr || "").trim()}`,
      );
    }
    tracked = git(["diff", "--name-only", options.base]);
    source = `工作树相对 ${options.base}（git diff --name-only）`;
  } else {
    tracked = git(["diff", "--name-only", "HEAD"]);
    source = "工作树相对 HEAD（git diff --name-only HEAD）";
  }
  const untracked = git(["ls-files", "--others", "--exclude-standard"]);
  const files = unique([
    ...splitLines(tracked),
    ...splitLines(untracked),
  ]).sort();
  return { source: `${source} + 未跟踪文件`, files };
}

/**
 * 按行拆分 git 输出并去空。
 *
 * @param {string} value git stdout
 * @returns {string[]}
 */
function splitLines(value) {
  return String(value)
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);
}

/**
 * 去重并保持顺序。
 *
 * @param {string[]} values 原始值
 * @returns {string[]}
 */
function unique(values) {
  return [...new Set(values)];
}

/**
 * 递归列出文件；跳过构建产物与依赖目录。
 *
 * @param {string} absoluteDir 绝对目录
 * @returns {string[]} 绝对文件路径
 */
function walkFiles(absoluteDir) {
  if (!existsSync(absoluteDir)) {
    return [];
  }
  const result = [];
  for (const entry of readdirSync(absoluteDir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (!SKIP_DIRECTORIES.has(entry.name)) {
        result.push(...walkFiles(path.join(absoluteDir, entry.name)));
      }
    } else if (entry.isFile()) {
      result.push(path.join(absoluteDir, entry.name));
    }
  }
  return result;
}

/**
 * 收集 Java 测试类：包路径、简单类名和绝对路径。
 *
 * @param {"api"|"worker"} module 模块名
 * @returns {Array<{simpleName: string, packagePath: string, absolute: string, relative: string}>}
 */
function collectJavaTests(module) {
  const testRoot = path.join(
    repoRoot,
    `apps/${module}/src/test/java/com/riccon/${module}`,
  );
  return walkFiles(testRoot)
    .filter((file) => /Tests?\.java$/.test(path.basename(file)))
    .map((absolute) => {
      const relativeToRoot = path
        .relative(testRoot, absolute)
        .split(path.sep)
        .join("/");
      const directory = path.posix.dirname(relativeToRoot);
      return {
        simpleName: path.basename(absolute, ".java"),
        packagePath: directory === "." ? "" : directory,
        absolute,
        relative: path.relative(repoRoot, absolute).split(path.sep).join("/"),
      };
    });
}

/** API 与 worker 的测试类清单在启动时固定，避免逐文件重复扫描。 */
const JAVA_TESTS = {
  api: collectJavaTests("api"),
  worker: collectJavaTests("worker"),
};

/**
 * 把短横线/下划线分隔的名称转为 PascalCase，用于匹配测试类名。
 *
 * @param {string} value 原始名称
 * @returns {string}
 */
function pascalCase(value) {
  return value
    .split(/[-_]/)
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join("");
}

/**
 * 根据 feature 选择 Java 测试类。
 *
 * @param {"api"|"worker"} module 模块
 * @param {string} feature 生产包名
 * @returns {Array<{simpleName: string, packagePath: string, absolute: string, relative: string}>}
 */
function matchJavaTests(module, feature) {
  const tests = JAVA_TESTS[module];
  const aliases = (
    module === "api" ? API_FEATURE_ALIASES : WORKER_FEATURE_ALIASES
  )[feature];
  const tokens = aliases && aliases.length ? aliases : [pascalCase(feature)];
  return tests.filter(
    (test) =>
      test.packagePath === feature ||
      test.packagePath.startsWith(`${feature}/`) ||
      tokens.some((token) =>
        test.simpleName.toLowerCase().includes(token.toLowerCase()),
      ),
  );
}

/** 收集 Web 测试文件（相对 apps/web）。 */
const WEB_TESTS = walkFiles(path.join(repoRoot, "apps/web"))
  .filter((absolute) => /\.test\.(ts|tsx)$/.test(path.basename(absolute)))
  .map((absolute) =>
    path
      .relative(path.join(repoRoot, "apps/web"), absolute)
      .split(path.sep)
      .join("/"),
  )
  .sort();

/** 收集 E2E spec 文件（相对仓库根）。 */
const E2E_SPECS = walkFiles(path.join(repoRoot, "e2e"))
  .filter((absolute) => /\.spec\.ts$/.test(path.basename(absolute)))
  .map((absolute) =>
    path.relative(repoRoot, absolute).split(path.sep).join("/"),
  )
  .sort();

/**
 * 生成可复制的命令；只在必要时加单引号。
 *
 * @param {string} value 命令片段
 * @returns {string}
 */
function shellQuote(value) {
  return /^[A-Za-z0-9_./:=,@+-]+$/.test(value)
    ? value
    : `'${value.replaceAll("'", "'\\''")}'`;
}

/**
 * 判断 Java 文件是否为测试类（而非测试支撑）。
 *
 * @param {string} value 文件名
 * @returns {boolean}
 */
function isJavaTestClass(value) {
  return /Tests?\.java$/.test(value);
}

/**
 * 汇总一次分析的输出。
 */
class SuggestionSet {
  constructor() {
    /** @type {Map<string, {title: string, command: string, reason: string, files: Set<string>}>} */
    this.groups = new Map();
    /** @type {Array<{file: string, note: string}>} */
    this.manual = [];
    /** @type {Array<{file: string, note: string}>} */
    this.ignored = [];
    /** @type {Array<{file: string, note: string}>} */
    this.unknown = [];
    /** @type {Map<string, {command: string, files: string[]}>} */
    this.nodeChecks = new Map();
  }

  /**
   * 添加命令组；相同 key 合并文件和理由。
   *
   * @param {string} key 组 key
   * @param {string} title 标题
   * @param {string} command 命令
   * @param {string} reason 理由
   * @param {string} file 触发文件
   */
  addCommand(key, title, command, reason, file) {
    const existing = this.groups.get(key);
    if (existing) {
      existing.files.add(file);
      if (!existing.reason.includes(reason)) {
        existing.reason = `${existing.reason}；${reason}`;
      }
      return;
    }
    this.groups.set(key, { title, command, reason, files: new Set([file]) });
  }

  /**
   * 记录手动检查项。
   *
   * @param {string} file 文件
   * @param {string} note 说明
   */
  addManual(file, note) {
    this.manual.push({ file, note });
  }

  /**
   * 记录忽略项。
   *
   * @param {string} file 文件
   * @param {string} note 说明
   */
  addIgnored(file, note) {
    this.ignored.push({ file, note });
  }

  /**
   * 记录未知输入；最终以非零退出。
   *
   * @param {string} file 文件
   * @param {string} note 原因
   */
  addUnknown(file, note) {
    this.unknown.push({ file, note });
  }

  /**
   * 记录 node --check 项。
   *
   * @param {string} file 文件
   * @param {string} reason 理由
   */
  addNodeCheck(file, reason) {
    const existing = this.nodeChecks.get(file);
    if (existing) {
      existing.reason = `${existing.reason}；${reason}`;
    } else {
      this.nodeChecks.set(file, {
        command: `node --check ${shellQuote(file)}`,
        reason,
      });
    }
  }

  /**
   * 渲染为文本。
   *
   * @param {string} source 变更来源
   * @param {{dryRun: boolean}} options 选项
   * @returns {string}
   */
  render(source, options) {
    const lines = [];
    lines.push("# 受影响测试建议（L1 内层循环）");
    lines.push("");
    lines.push(
      "> 不能作为任务完成或模块 gate 证据；L2/L3 仍按 docs/CODING-STANDARDS.md §10.1",
    );
    lines.push("> 执行合同 test/coverage/E2E，并由 verifier 独立取证。");
    if (options.dryRun) {
      lines.push("> 模式：--dry-run（脚本只输出命令，不执行）。");
    } else {
      lines.push("> 模式：仅输出建议；本脚本从不自动执行命令。");
    }
    lines.push("");
    lines.push(`变更来源：${source}`);
    lines.push("");

    const groups = [...this.groups.values()];
    if (groups.length) {
      lines.push("## 建议命令");
      lines.push("");
      groups.forEach((group, index) => {
        lines.push(`### [${index + 1}] ${group.title}`);
        lines.push("");
        lines.push("```bash");
        lines.push(group.command);
        lines.push("```");
        lines.push("");
        lines.push("触发文件：");
        for (const file of [...group.files].sort()) {
          lines.push(`- ${file}`);
        }
        lines.push("");
        lines.push(`理由：${group.reason}`);
        lines.push("");
      });
    } else {
      lines.push("## 建议命令");
      lines.push("");
      lines.push("无需运行自动化测试。");
      lines.push("");
    }

    if (this.nodeChecks.size) {
      lines.push("## 脚本语法检查");
      lines.push("");
      for (const [file, check] of [...this.nodeChecks.entries()].sort()) {
        lines.push(`- ${file}：\`${check.command}\`（${check.reason}）`);
      }
      lines.push("");
    }

    if (this.manual.length) {
      lines.push("## 手册检查（无自动化测试映射）");
      lines.push("");
      for (const item of this.manual) {
        lines.push(`- ${item.file}：${item.note}`);
      }
      lines.push("");
    }

    if (this.ignored.length) {
      lines.push("## 已忽略（工具状态）");
      lines.push("");
      for (const item of this.ignored) {
        lines.push(`- ${item.file}：${item.note}`);
      }
      lines.push("");
    }

    if (this.unknown.length) {
      lines.push("## 无法识别的路径");
      lines.push("");
      for (const item of this.unknown) {
        lines.push(`- ${item.file}：${item.note}`);
      }
      lines.push("");
      lines.push(
        "请补充映射规则或手动说明；脚本以非零退出，避免静默返回空建议。",
      );
      lines.push("");
    }

    if (groups.length || this.nodeChecks.size) {
      lines.push("## 汇总（可复制）");
      lines.push("");
      lines.push("```bash");
      for (const group of groups) {
        lines.push(group.command);
      }
      for (const check of [...this.nodeChecks.values()]) {
        lines.push(check.command);
      }
      lines.push("```");
      lines.push("");
    }

    lines.push("提醒：");
    lines.push(
      "- 迁移/回滚与 schema 版本测试必须使用一次性容器，不得连接 external 热栈。",
    );
    lines.push(
      "- external 热栈仅用于单 agent L1；默认/CI/gate 仍使用 Testcontainers。",
    );
    lines.push("- 运行前先确认工作树与提交范围；本脚本不替代 §10.1 的 L2/L3。");
    return lines.join("\n");
  }
}

/**
 * 分析一个变更文件并写入建议集合。
 *
 * @param {string} file 相对仓库根的文件路径
 * @param {SuggestionSet} suggestions 输出集合
 */
function analyzeFile(file, suggestions) {
  if (file.startsWith(".riccon/")) {
    suggestions.addIgnored(file, "Riccon 状态/证据文件，不参与测试映射。");
    return;
  }
  if (file.startsWith("docs/")) {
    suggestions.addManual(
      file,
      "文档变更：无自动化测试；核对文档中的命令与 scripts/README 是否一致，必要时手动执行示例命令。",
    );
    return;
  }
  if (file.startsWith("scripts/")) {
    if (file.endsWith(".mjs")) {
      suggestions.addNodeCheck(file, "Node 脚本语法检查");
      if (file === "scripts/test-affected.mjs") {
        suggestions.addManual(
          file,
          "修改映射脚本后运行 `node scripts/test-affected.mjs --dry-run --base HEAD~1` 验证输出。",
        );
      } else if (file === "scripts/test-services.mjs") {
        suggestions.addManual(
          file,
          "热栈脚本：在 compose 已启动时运行 `node scripts/test-services.mjs status` 做冒烟。",
        );
      } else if (file.startsWith("scripts/real-stack/")) {
        suggestions.addManual(
          file,
          "真实栈脚本：按 docs/RUNBOOK.md 的对应编排手动运行，不进入模块 gate。",
        );
      } else {
        suggestions.addManual(file, "脚本变更：确认调用方与文档命令一致。");
      }
    } else {
      suggestions.addManual(file, "非 Node 脚本变更：按调用方说明手动验证。");
    }
    return;
  }
  if (file.startsWith("e2e/")) {
    analyzeE2eFile(file, suggestions);
    return;
  }
  if (file.startsWith("apps/api/")) {
    analyzeApiFile(file, suggestions);
    return;
  }
  if (file.startsWith("apps/worker/")) {
    analyzeWorkerFile(file, suggestions);
    return;
  }
  if (file.startsWith("apps/web/")) {
    analyzeWebFile(file, suggestions);
    return;
  }
  if (file.startsWith("platform/")) {
    suggestions.addCommand(
      "backend-full",
      "后端全量（platform 共享层）",
      "./mvnw -q -T1C verify",
      "platform 模块被 api/worker 共享，变更可能跨模块影响，回退后端全量。",
      file,
    );
    return;
  }
  if (
    file.startsWith(".github/") ||
    file.startsWith("deploy/") ||
    file.startsWith("contracts/")
  ) {
    suggestions.addManual(
      file,
      "CI/部署/契约配置变更：无单元测试映射；按 RUNBOOK 与 CI 对应 job 检查，必要时运行合同命令。",
    );
    return;
  }
  if (file.startsWith(".mvn/") || ROOT_BACKEND_FILES.has(file)) {
    suggestions.addCommand(
      "backend-full",
      "后端全量（构建入口）",
      "./mvnw -q -T1C verify",
      "Maven wrapper/根构建变更影响全部后端模块，回退后端全量。",
      file,
    );
    return;
  }
  if (ROOT_FRONTEND_FILES.has(file)) {
    suggestions.addCommand(
      "web-full",
      "前端全量（根 package/lock）",
      "pnpm -w test",
      "根 workspace/依赖变更影响全部前端测试；如改动依赖先执行 pnpm install --frozen-lockfile。",
      file,
    );
    return;
  }
  if (ROOT_MANUAL_FILES.has(file)) {
    suggestions.addManual(
      file,
      "仓库级文档/合同文件：无自动化测试，按文档评审流程检查。",
    );
    return;
  }
  suggestions.addUnknown(file, "未匹配任何已知模块或映射规则。");
}

/**
 * API 变更映射。
 *
 * @param {string} file 文件
 * @param {SuggestionSet} suggestions 输出
 */
function analyzeApiFile(file, suggestions) {
  const mainPrefix = "apps/api/src/main/java/com/riccon/api/";
  const testPrefix = "apps/api/src/test/java/com/riccon/api/";
  if (file.startsWith(mainPrefix)) {
    const relative = file.slice(mainPrefix.length);
    const segments = relative.split("/");
    if (segments.length === 1) {
      addModuleFull(
        "api",
        file,
        "ApiApplication 等模块入口变更，回退 API 模块全量。",
        suggestions,
      );
      return;
    }
    const feature = segments[0];
    if (SHARED_API_FEATURES.has(feature)) {
      addModuleFull(
        "api",
        file,
        `${feature} 是共享/横切包，变更可能影响全部 API 集成测试。`,
        suggestions,
      );
      return;
    }
    const tests = matchJavaTests("api", feature);
    if (!tests.length) {
      addModuleFull(
        "api",
        file,
        `无法从 feature=${feature} 唯一确定测试类，回退 API 模块全量。`,
        suggestions,
      );
      return;
    }
    addJavaTests(
      "api",
      file,
      tests,
      `feature=${feature} 命中 API 测试类。`,
      suggestions,
    );
    return;
  }
  if (file.startsWith(testPrefix)) {
    analyzeJavaTestFile(file, "api", suggestions);
    return;
  }
  if (file.startsWith("apps/api/src/main/resources/db/migration/")) {
    suggestions.addCommand(
      "api-migration",
      "API 迁移测试（一次性容器）",
      "./mvnw -q -pl apps/api -am test -Dtest=V9MigrationTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false",
      "迁移文件变更：先跑迁移语义测试；若新增/修改迁移，再按模块全量验证。",
      file,
    );
    addModuleFull(
      "api",
      file,
      "迁移属于高风险变更，需要 API 模块全量回归。",
      suggestions,
    );
    return;
  }
  if (file.startsWith("apps/api/src/main/resources/")) {
    addModuleFull(
      "api",
      file,
      "API 运行配置/资源变更可能影响全部集成测试。",
      suggestions,
    );
    return;
  }
  if (file.startsWith("apps/api/src/test/resources/")) {
    addModuleFull(
      "api",
      file,
      "测试资源配置变更可能影响全部 API 集成测试。",
      suggestions,
    );
    return;
  }
  if (file === "apps/api/pom.xml") {
    addModuleFull(
      "api",
      file,
      "API 构建依赖变更，回退 API 模块全量。",
      suggestions,
    );
    return;
  }
  if (file.startsWith("apps/api/target/")) {
    suggestions.addIgnored(file, "构建产物，不应提交。");
    return;
  }
  suggestions.addManual(
    file,
    "API 模块其他文件：无自动化测试映射，按变更内容手动评估。",
  );
}

/**
 * Worker 变更映射。
 *
 * @param {string} file 文件
 * @param {SuggestionSet} suggestions 输出
 */
function analyzeWorkerFile(file, suggestions) {
  const mainPrefix = "apps/worker/src/main/java/com/riccon/worker/";
  const testPrefix = "apps/worker/src/test/java/com/riccon/worker/";
  if (file.startsWith(mainPrefix)) {
    const relative = file.slice(mainPrefix.length);
    const segments = relative.split("/");
    if (segments.length === 1) {
      addModuleFull(
        "worker",
        file,
        "WorkerApplication 等模块入口变更，回退 Worker 模块全量。",
        suggestions,
      );
      return;
    }
    const feature = segments[0];
    if (SHARED_WORKER_FEATURES.has(feature)) {
      addModuleFull(
        "worker",
        file,
        `${feature} 是共享/横切包，变更可能影响全部 Worker 集成测试。`,
        suggestions,
      );
      return;
    }
    const tests = matchJavaTests("worker", feature);
    if (!tests.length) {
      addModuleFull(
        "worker",
        file,
        `无法从 feature=${feature} 唯一确定测试类，回退 Worker 模块全量。`,
        suggestions,
      );
      return;
    }
    addJavaTests(
      "worker",
      file,
      tests,
      `feature=${feature} 命中 Worker 测试类。`,
      suggestions,
    );
    return;
  }
  if (file.startsWith(testPrefix)) {
    analyzeJavaTestFile(file, "worker", suggestions);
    return;
  }
  if (file.startsWith("apps/worker/src/main/resources/")) {
    addModuleFull(
      "worker",
      file,
      "Worker 运行配置变更可能影响全部集成测试。",
      suggestions,
    );
    return;
  }
  if (file.startsWith("apps/worker/src/test/resources/")) {
    addModuleFull(
      "worker",
      file,
      "测试资源配置变更可能影响全部 Worker 集成测试。",
      suggestions,
    );
    return;
  }
  if (file === "apps/worker/pom.xml") {
    addModuleFull(
      "worker",
      file,
      "Worker 构建依赖变更，回退 Worker 模块全量。",
      suggestions,
    );
    return;
  }
  if (file.startsWith("apps/worker/target/")) {
    suggestions.addIgnored(file, "构建产物，不应提交。");
    return;
  }
  suggestions.addManual(
    file,
    "Worker 模块其他文件：无自动化测试映射，按变更内容手动评估。",
  );
}

/**
 * Java 测试文件变更：测试类本身变更直接跑该类；测试支撑变更回退模块全量。
 *
 * @param {string} file 文件
 * @param {"api"|"worker"} module 模块
 * @param {SuggestionSet} suggestions 输出
 */
function analyzeJavaTestFile(file, module, suggestions) {
  const name = path.basename(file);
  if (!isJavaTestClass(name)) {
    addModuleFull(
      module,
      file,
      "测试支撑/基类变更会影响多个测试类，回退模块全量。",
      suggestions,
    );
    return;
  }
  if (!existsSync(path.join(repoRoot, file))) {
    addModuleFull(
      module,
      file,
      "测试文件已删除/重命名；回退模块全量，确认没有遗留引用。",
      suggestions,
    );
    return;
  }
  const simpleName = name.replace(/\.java$/, "");
  addJavaTests(
    module,
    file,
    [{ simpleName, packagePath: "", absolute: "", relative: file }],
    "直接变更的测试类。",
    suggestions,
  );
}

/**
 * 添加 Java 定向测试命令（按模块合并）。
 *
 * @param {"api"|"worker"} module 模块
 * @param {string} file 触发文件
 * @param {Array<{simpleName: string}>} tests 测试类
 * @param {string} reason 理由
 * @param {SuggestionSet} suggestions 输出
 */
function addJavaTests(module, file, tests, reason, suggestions) {
  const names = unique(tests.map((test) => test.simpleName)).sort();
  const command =
    `./mvnw -q -pl apps/${module} -am test -Dtest=${names.join(",")} ` +
    "-DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false";
  suggestions.addCommand(
    `${module}-tests`,
    `${module.toUpperCase()} 定向测试`,
    command,
    reason,
    file,
  );
}

/**
 * 添加模块全量回退命令。
 *
 * @param {"api"|"worker"} module 模块
 * @param {string} file 触发文件
 * @param {string} reason 理由
 * @param {SuggestionSet} suggestions 输出
 */
function addModuleFull(module, file, reason, suggestions) {
  suggestions.addCommand(
    `${module}-full`,
    `${module.toUpperCase()} 模块全量（回退）`,
    `./mvnw -q -T1C -pl apps/${module} -am verify`,
    reason,
    file,
  );
}

/**
 * Web 变更映射。
 *
 * @param {string} file 文件
 * @param {SuggestionSet} suggestions 输出
 */
function analyzeWebFile(file, suggestions) {
  const webRoot = "apps/web/";
  const relative = file.slice(webRoot.length);
  if (
    file.startsWith("apps/web/node_modules/") ||
    file.startsWith("apps/web/coverage/") ||
    file.startsWith("apps/web/dist/") ||
    file.startsWith("apps/web/.umi/") ||
    file.startsWith("apps/web/src/.umi/")
  ) {
    suggestions.addIgnored(file, "前端构建产物/依赖目录，不应提交。");
    return;
  }
  if (file.endsWith(".test.ts") || file.endsWith(".test.tsx")) {
    if (!existsSync(path.join(repoRoot, file))) {
      suggestions.addCommand(
        "web-full",
        "前端全量（测试删除/重命名）",
        "pnpm -w test",
        "测试文件已删除/重命名，回退前端全量。",
        file,
      );
      return;
    }
    addWebTests([relative], file, "直接变更的 vitest 文件。", suggestions);
    return;
  }
  if (
    relative === "package.json" ||
    relative === "vitest.config.ts" ||
    relative === "tsconfig.json" ||
    relative === ".umirc.ts" ||
    relative.startsWith("config/") ||
    relative.startsWith("src/test/") ||
    relative.startsWith("types/")
  ) {
    suggestions.addCommand(
      "web-full",
      "前端全量（配置/测试支撑）",
      "pnpm -w test",
      "前端配置、路由或测试支撑变更可能影响所有 vitest，回退前端全量。",
      file,
    );
    return;
  }
  if (relative.startsWith("scripts/")) {
    if (relative.endsWith(".mjs")) {
      suggestions.addNodeCheck(file, "前端 Node 脚本语法检查");
    } else {
      suggestions.addManual(file, "前端脚本变更：按调用方说明手动验证。");
    }
    return;
  }
  if (!relative.startsWith("src/")) {
    suggestions.addManual(
      file,
      "Web 构建/说明文件：无自动化测试映射，按构建或文档流程检查。",
    );
    return;
  }

  const exact = [
    `${relative.replace(/\.(ts|tsx)$/, "")}.test.ts`,
    `${relative.replace(/\.(ts|tsx)$/, "")}.test.tsx`,
  ].filter((candidate) => WEB_TESTS.includes(candidate));
  if (exact.length) {
    addWebTests(exact, file, "同目录同名 vitest 文件。", suggestions);
    return;
  }

  // 逐级向上找最近的、包含测试的目录；命中后运行该目录下全部测试，覆盖页面内共享组件。
  let directory = path.posix.dirname(relative);
  while (directory && directory !== "." && directory !== "") {
    const candidates = WEB_TESTS.filter((test) =>
      test.startsWith(`${directory}/`),
    );
    if (candidates.length) {
      addWebTests(
        candidates,
        file,
        `变更位于 ${directory}/ 页面/服务子树，运行该子树相关 vitest。`,
        suggestions,
      );
      return;
    }
    const parent = path.posix.dirname(directory);
    if (parent === directory) {
      break;
    }
    directory = parent;
  }
  suggestions.addCommand(
    "web-full",
    "前端全量（无法唯一定位）",
    "pnpm -w test",
    "无法从变更文件唯一定位 vitest 文件，回退前端全量。",
    file,
  );
}

/**
 * 添加 Web 定向测试命令。
 *
 * @param {string[]} testFiles 相对 apps/web 的测试路径
 * @param {string} file 触发文件
 * @param {string} reason 理由
 * @param {SuggestionSet} suggestions 输出
 */
function addWebTests(testFiles, file, reason, suggestions) {
  const command = `pnpm --filter @riccon/web exec vitest run ${testFiles.map(shellQuote).join(" ")}`;
  suggestions.addCommand(
    "web-tests",
    "前端定向测试（vitest）",
    command,
    reason,
    file,
  );
}

/**
 * E2E 变更映射。
 *
 * @param {string} file 文件
 * @param {SuggestionSet} suggestions 输出
 */
function analyzeE2eFile(file, suggestions) {
  if (file.endsWith(".spec.ts")) {
    if (!existsSync(path.join(repoRoot, file))) {
      suggestions.addCommand(
        "e2e-full",
        "E2E 全量（spec 删除/重命名）",
        "pnpm -w e2e",
        "spec 已删除/重命名，回退 E2E 全量。",
        file,
      );
      return;
    }
    suggestions.addCommand(
      "e2e-tests",
      "E2E 定向测试",
      `pnpm -w e2e -- ${shellQuote(file)}`,
      "直接变更的 Playwright spec。",
      file,
    );
    return;
  }
  if (file.startsWith("e2e/real/")) {
    suggestions.addManual(
      file,
      "真实栈 E2E 编排：不进入模块 gate；按 docs/RUNBOOK.md 与 scripts/real-stack 手动运行。",
    );
    return;
  }
  suggestions.addCommand(
    "e2e-full",
    "E2E 全量（共享配置/stub/fixture）",
    "pnpm -w e2e",
    "E2E 配置、stub 或共享 fixture 变更可能影响全部 spec，回退 E2E 全量。",
    file,
  );
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

  const { source, files } = collectChangedFiles(options);
  const suggestions = new SuggestionSet();
  for (const file of files) {
    analyzeFile(file, suggestions);
  }

  console.log(`变更文件（${files.length}）：`);
  if (files.length === 0) {
    console.log("  （无）");
  } else {
    for (const file of files) {
      console.log(`  - ${file}`);
    }
  }
  console.log("");
  console.log(suggestions.render(source, options));

  if (suggestions.unknown.length) {
    process.exitCode = 1;
  }
}

try {
  main();
} catch (error) {
  console.error(`[test-affected] ${error.message}`);
  process.exit(1);
}
