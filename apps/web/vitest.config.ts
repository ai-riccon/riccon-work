import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';

/**
 * Vitest 配置。
 *
 * 说明：M01 只覆盖路由表等纯逻辑；组件渲染测试在 M02 引入 jsdom 环境后按页面补充。
 * 覆盖率报告输出到 apps/web/coverage，供合同 commands.coverage 的 `pnpm -w test -- --coverage` 复现。
 */
export default defineConfig({
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'node',
    include: ['config/**/*.test.ts', 'src/**/*.test.{ts,tsx}'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'lcov'],
      reportsDirectory: 'coverage',
      include: ['config/routes.ts', 'src/**/*.{ts,tsx}'],
      exclude: ['src/**/*.test.{ts,tsx}', 'src/.umi/**'],
    },
  },
});
