/**
 * 前端运行时配置。
 *
 * 构建时由 `apps/web/config/config.ts` 的 `define` 注入；默认同源部署（空字符串），
 * 不在源码中硬编码任何后端域名，也不读取 Cookie 或密钥。
 */

/** API 基础地址；空字符串表示与 Web 同源，由反向代理转发到 apps/api。 */
export const API_BASE_URL: string =
  typeof process !== 'undefined' ? (process.env.RICCON_API_BASE_URL ?? '') : '';

/**
 * 是否启用本地演示适配层。
 *
 * 只有显式设置 `RICCON_API_MOCK=1` 才启用；生产构建未设置时固定为 false，
 * 因此真实部署不会走到 mock 成功路径。E2E 与本地演示使用该开关。
 */
export const IS_MOCK_API: boolean =
  typeof process !== 'undefined' && process.env.RICCON_API_MOCK === '1';

/**
 * 本地演示账号；只在 `RICCON_API_MOCK=1` 时展示给使用者。
 * 不是生产凭据，生产构建不会启用 mock 适配层。
 */
export const MOCK_CREDENTIALS = {
  tenantCode: 'acme',
  username: 'demo',
  password: 'demo1234',
} as const;
