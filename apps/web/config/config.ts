import { defineConfig } from '@umijs/max';
import { APP_TITLE, routes } from './routes';

/**
 * E2E 专用开发代理目标。
 *
 * Playwright 会同时启动独立 stub API（`e2e/stub/server.mjs`）和 Web dev server，
 * 并把该变量传给 Web 进程。只有设置时才会生成代理：生产构建与非 E2E 开发
 * 都保持同源直连，不引入额外转发或演示分支。
 */
const e2eApiTarget = process.env.RICCON_E2E_API_TARGET;

/**
 * Umi Max 构建配置。
 *
 * 设计取舍：
 * 1. 显式声明 routes，而不是依赖约定式路由，保证员工端/管理端边界可读、可测试；
 * 2. 不开启 Umi 的 antd 插件，避免其内置 antd 依赖与项目锁定的 antd 6 产生两份运行时；
 * 3. history 使用 browser 模式，生产由反向代理回退到 index.html（M09 在 Helm 中配置）；
 * 4. 应用内不含 mock 适配层；E2E 只通过开发代理指向仓库 `e2e/stub/` 的独立 stub。
 */
export default defineConfig({
  routes,
  title: APP_TITLE,
  history: { type: 'browser' },
  npmClient: 'pnpm',
  // 运行时配置通过构建时 define 注入：默认同源 API，不在源码中硬编码后端域名。
  // Umi 会对 define 的值做 JSON.stringify，因此这里直接传字符串。
  define: {
    'process.env.RICCON_API_BASE_URL': process.env.RICCON_API_BASE_URL ?? '',
  },
  // E2E stub 使用同源 Cookie 语义，因此通过 dev server 代理 `/api` 到 stub，
  // 而不是把 API 基地址改成跨域地址。未设置目标时不生成代理。
  ...(e2eApiTarget
    ? {
        proxy: {
          '/api': {
            target: e2eApiTarget,
            changeOrigin: true,
          },
        },
      }
    : {}),
  // 分包产物共用 esbuild 助手时会因 IIFE 包裹方式冲突导致构建失败；开启后按 Umi 官方提示生成独立 IIFE。
  esbuildMinifyIIFE: true,
  // Umi 4.7.34 的 MFSU 会把 CJS 包 @umijs/utils/compiled/strip-ansi 预打包成双层 default，
  // 导致 dev 环境的 HMR client 在格式化 webpack warning 时抛 TypeError；显式排除该包，
  // 让 webpack 直接按 __esModule 互操作解析。待 Umi 修复该缺陷或升级到修复版本后移除。
  // E2E 关闭 MFSU：冷启动预打包会长时间停留在 Bundling 页面，且首次 /admin 重定向不稳定；
  // 正常开发仍保留 MFSU 以维持 HMR 速度。
  mfsu:
    process.env.RICCON_E2E === '1' ? false : { exclude: [/@umijs\/utils\/compiled\/strip-ansi/] },
});
