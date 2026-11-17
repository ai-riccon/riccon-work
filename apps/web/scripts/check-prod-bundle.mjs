/**
 * 生产构建产物守卫。
 *
 * 目的：证明应用内 mock 适配层没有进入 `apps/web/dist`。E2E stub 独立存放在
 * `e2e/stub/`，只由 Playwright 启动，不参与 Web 构建；本脚本用字符串特征
 * 检查构建产物，任何命中都会以非零退出码失败。
 *
 * 使用：先 `pnpm -w build`，再 `node apps/web/scripts/check-prod-bundle.mjs`。
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/** 构建产物目录：apps/web/dist。 */
const distDir = fileURLToPath(new URL('../dist', import.meta.url));

/**
 * 禁止出现在生产产物中的特征。
 *
 * 说明：
 * - `RICCON_API_MOCK` / `createMockFetch` / `mocks/mock-api`：已移除的 in-app mock 入口；
 * - `riccon.j1.mock` / `mock-csrf` / `files.example.invalid`：mock 状态与演示对象地址；
 * - `demo1234` / `演示员工` / `示例企业`：stub/演示数据特征；
 * - `market_data_lookup` / 报告正文片段：stub 合成业务数据，生产构建不应硬编码。
 */
const FORBIDDEN = [
  { pattern: /RICCON_API_MOCK/, label: 'in-app mock 开关' },
  { pattern: /createMockFetch/, label: 'in-app mock 工厂' },
  { pattern: /mocks\/mock-api|@\/mocks\/mock-api/, label: 'in-app mock 模块路径' },
  { pattern: /riccon\.j1\.mock/, label: 'mock 存储键' },
  { pattern: /mock-csrf/, label: 'mock CSRF 令牌' },
  { pattern: /files\.example\.invalid/, label: 'mock 对象存储地址' },
  { pattern: /demo1234/, label: '演示凭据' },
  { pattern: /演示员工/, label: 'stub 用户数据' },
  { pattern: /示例企业/, label: 'stub 租户数据' },
  { pattern: /market_data_lookup/, label: 'stub MCP 工具数据' },
  { pattern: /本报告基于租户知识库检索/, label: 'stub 报告正文' },
];

/**
 * 递归列出目录下的普通文件。
 *
 * @param directory 目录
 * @returns 绝对路径列表
 */
function listFiles(directory) {
  const entries = readdirSync(directory, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const full = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      files.push(...listFiles(full));
    } else if (entry.isFile()) {
      files.push(full);
    }
  }
  return files;
}

if (!statSync(distDir, { throwIfNoEntry: false })) {
  console.error(`[check-prod-bundle] 未找到构建产物：${distDir}，请先运行 pnpm -w build。`);
  process.exit(1);
}

const files = listFiles(distDir);
const jsFiles = files.filter((file) => file.endsWith('.js'));
if (jsFiles.length === 0) {
  console.error('[check-prod-bundle] dist 中没有 JavaScript 产物，构建可能失败。');
  process.exit(1);
}
if (!files.some((file) => file.endsWith('index.html'))) {
  console.error('[check-prod-bundle] dist 中没有 index.html，构建可能失败。');
  process.exit(1);
}

const hits = [];
for (const file of files) {
  const text = readFileSync(file, 'utf8');
  for (const rule of FORBIDDEN) {
    if (rule.pattern.test(text)) {
      hits.push(`${path.relative(distDir, file)} -> ${rule.label}`);
    }
  }
}

const scannedBytes = files.reduce((sum, file) => sum + statSync(file).size, 0);
if (hits.length > 0) {
  console.error('[check-prod-bundle] 生产产物命中禁止特征：');
  for (const hit of hits) {
    console.error(`  - ${hit}`);
  }
  process.exit(1);
}

console.log(
  `[check-prod-bundle] 通过：扫描 ${files.length} 个文件（${(scannedBytes / 1024).toFixed(1)} KiB），` +
    '未发现 mock/stub 特征。',
);
