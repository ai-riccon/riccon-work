import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/**
 * 前端测试入口包装。
 *
 * 背景：合同 commands.coverage 以 `pnpm -w test -- --coverage` 调用。
 * pnpm 转发参数时会保留分隔符 `--`，而 vitest 把 `--` 之后的 `--coverage` 当作文件名过滤器，
 * 结果是测试通过但不产出覆盖率报告。这里统一剥离 `--` 后再转发，保证两种调用行为一致：
 * - `pnpm -w test`                 -> vitest run
 * - `pnpm -w test -- --coverage`   -> vitest run --coverage
 *
 * 干净 checkout 的 `apps/web/tsconfig.json` 继承 Umi 生成的 `src/.umi/tsconfig.json`；
 * 该文件被 gitignore，直接运行合同覆盖率命令会因缺少 extends 目标而失败。
 * 这里只在缺失时补一次 `max setup`，让合同命令不依赖调用者先跑 typecheck；生成物已存在时不增加开销。
 */
const forwardedArgs = process.argv.slice(2).filter((arg) => arg !== '--');
const webRoot = fileURLToPath(new URL('..', import.meta.url));
const umiTsconfig = fileURLToPath(new URL('../src/.umi/tsconfig.json', import.meta.url));

if (!existsSync(umiTsconfig)) {
  const setup = spawnSync('pnpm', ['exec', 'max', 'setup'], {
    cwd: webRoot,
    stdio: 'inherit',
  });
  if (setup.error || setup.status !== 0) {
    console.error('[web-test] 初始化 Umi 生成文件失败，无法运行测试。');
    process.exit(setup.status ?? 1);
  }
}

const result = spawnSync('pnpm', ['exec', 'vitest', 'run', ...forwardedArgs], {
  cwd: webRoot,
  stdio: 'inherit',
});

if (result.error) {
  console.error(`[web-test] 启动 vitest 失败: ${result.error.message}`);
  process.exit(1);
}

process.exit(result.status ?? 1);
