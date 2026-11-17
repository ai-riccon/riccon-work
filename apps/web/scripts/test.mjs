import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

/**
 * 前端测试入口包装。
 *
 * 背景：合同 commands.coverage 以 `pnpm -w test -- --coverage` 调用。
 * pnpm 转发参数时会保留分隔符 `--`，而 vitest 把 `--` 之后的 `--coverage` 当作文件名过滤器，
 * 结果是测试通过但不产出覆盖率报告。这里统一剥离 `--` 后再转发，保证两种调用行为一致：
 * - `pnpm -w test`                 -> vitest run
 * - `pnpm -w test -- --coverage`   -> vitest run --coverage
 */
const forwardedArgs = process.argv.slice(2).filter((arg) => arg !== '--');
const webRoot = fileURLToPath(new URL('..', import.meta.url));

const result = spawnSync('pnpm', ['exec', 'vitest', 'run', ...forwardedArgs], {
  cwd: webRoot,
  stdio: 'inherit',
});

if (result.error) {
  console.error(`[web-test] 启动 vitest 失败: ${result.error.message}`);
  process.exit(1);
}

process.exit(result.status ?? 1);
