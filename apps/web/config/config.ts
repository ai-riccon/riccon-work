import { defineConfig } from '@umijs/max';
import { APP_TITLE, routes } from './routes';

/**
 * Umi Max 构建配置。
 *
 * 设计取舍：
 * 1. 显式声明 routes，而不是依赖约定式路由，保证员工端/管理端边界可读、可测试；
 * 2. 不开启 Umi 的 antd 插件，避免其内置 antd 依赖与项目锁定的 antd 6 产生两份运行时；
 * 3. history 使用 browser 模式，生产由反向代理回退到 index.html（M09 在 Helm 中配置）。
 */
export default defineConfig({
  routes,
  title: APP_TITLE,
  history: { type: 'browser' },
  npmClient: 'pnpm',
  // 分包产物共用 esbuild 助手时会因 IIFE 包裹方式冲突导致构建失败；开启后按 Umi 官方提示生成独立 IIFE。
  esbuildMinifyIIFE: true,
  // Umi 4.7.34 的 MFSU 会把 CJS 包 @umijs/utils/compiled/strip-ansi 预打包成双层 default，
  // 导致 dev 环境的 HMR client 在格式化 webpack warning 时抛 TypeError；显式排除该包，
  // 让 webpack 直接按 __esModule 互操作解析。待 Umi 修复该缺陷或升级到修复版本后移除。
  mfsu: { exclude: [/@umijs\/utils\/compiled\/strip-ansi/] },
});
