import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';

/**
 * Vitest 配置。
 *
 * 覆盖率口径（对应合同 constraints.coverage：line 80 / branch 70）：
 * - 只统计手写源码：`config/routes.ts` 与 `src/**` 下的 TS/TSX；
 * - 排除 Umi 生成目录 `src/.umi/**`、`src/.umi-production/**`、根目录 `.umi/**`、
 *   `.umi-production/**`（其中包含生成的 exports/testBrowser 等文件）；
 * - 排除依赖与产物 `node_modules/**`、`dist/**`、`coverage/**`；
 * - 排除测试文件 `**\/*.test.{ts,tsx}` 与测试支撑 `src/test/**`；
 * - 排除 `config/config.ts`：Umi 构建配置是声明式对象，没有运行时分支，由 typecheck/build 验证，
 *   计入覆盖率只会虚增分母；该排除与 `src/test/**` 均在 TASK-0009 回执中逐条列明。
 * 门槛在下方 thresholds 显式声明，不高于合同值；报告输出到 apps/web/coverage，
 * 由合同 commands.coverage 的 `pnpm -w test -- --coverage` 复现。
 */
export default defineConfig({
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
      // 页面只依赖 Link/Outlet/useLocation 三个 Umi 运行时能力；测试中替换为轻量替身，
      // 避免在 jsdom 中启动完整 Umi 运行时，同时保留页面对路由 API 的真实调用形态。
      '@umijs/max': fileURLToPath(new URL('./src/test/umijs-max.tsx', import.meta.url)),
    },
  },
  test: {
    environment: 'node',
    setupFiles: ['./src/test/setup.ts'],
    include: ['config/**/*.test.ts', 'src/**/*.test.{ts,tsx}'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'lcov'],
      reportsDirectory: 'coverage',
      include: ['config/routes.ts', 'src/**/*.{ts,tsx}'],
      exclude: [
        '**/*.test.{ts,tsx}',
        'src/test/**',
        'src/.umi/**',
        'src/.umi-production/**',
        '.umi/**',
        '.umi-production/**',
        'node_modules/**',
        'dist/**',
        'coverage/**',
        'config/config.ts',
        // 演示适配层只在 RICCON_API_MOCK=1 时进入运行时，由 E2E 覆盖；它不是生产代码路径，
        // 且包含大量刻意构造的失败响应，计入单元覆盖率会挤占真实业务代码的门槛。
        'src/mocks/**',
      ],
      thresholds: {
        lines: 80,
        branches: 70,
      },
    },
  },
});
