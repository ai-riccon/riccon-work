/**
 * 前端运行时配置。
 *
 * 构建时由 `apps/web/config/config.ts` 的 `define` 注入；默认同源部署（空字符串），
 * 不在源码中硬编码任何后端域名，也不读取 Cookie 或密钥。
 *
 * 生产构建与开发环境都走真实 API：应用内不包含任何演示数据或 mock 成功路径。
 * 端到端测试使用 `e2e/stub/` 下的独立 Node stub API，通过开发代理或
 * `RICCON_API_BASE_URL` 指向该 stub，因此无需在浏览器包内保留演示适配层。
 */

/** API 基础地址；空字符串表示与 Web 同源，由反向代理转发到 apps/api。 */
export const API_BASE_URL: string =
  typeof process !== 'undefined' ? (process.env.RICCON_API_BASE_URL ?? '') : '';
