import js from '@eslint/js';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';
import tseslint from 'typescript-eslint';

/**
 * 前端 ESLint 扁平配置。
 *
 * 规则取舍：
 * 1. 只启用 typescript-eslint recommended 与 React Hooks 两条硬规则，避免风格规则与 Prettier 冲突；
 * 2. 生成目录（.umi/dist/coverage）不参与检查；
 * 3. 配置目录与源码都允许 Node 与浏览器全局，避免为 vitest 配置单独维护第三份配置。
 */
export default tseslint.config(
  {
    ignores: [
      'dist/**',
      '.umi/**',
      '.umi-production/**',
      'src/.umi/**',
      'src/.umi-production/**',
      'coverage/**',
      'node_modules/**',
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['**/*.{js,mjs,cjs}'],
    languageOptions: {
      globals: globals.node,
    },
  },
  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      globals: { ...globals.browser, ...globals.node },
    },
    plugins: { 'react-hooks': reactHooks },
    rules: {
      'react-hooks/rules-of-hooks': 'error',
      'react-hooks/exhaustive-deps': 'error',
    },
  },
);
