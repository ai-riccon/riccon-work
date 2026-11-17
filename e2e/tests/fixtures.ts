import { test as base, expect } from '@playwright/test';

/**
 * 已知可接受的 console.error 白名单。
 *
 * 当前为空：/chat、/admin、/login 三个页面在修复 antd 6 Alert 属性与 Umi MFSU 互操作问题后
 * 不应再出现任何 console.error 或 pageerror。若未来必须放行框架告警，必须在此写明
 * 来源、版本、原因与移除条件，禁止在用例里临时屏蔽。
 */
const CONSOLE_ERROR_ALLOWLIST: RegExp[] = [];

/**
 * Playwright 测试基类：自动为每个用例挂上浏览器 console 守卫。
 *
 * 守卫在页面导航前注册，收集 `pageerror` 与 `console.error`；用例结束时统一断言为空，
 * 让“页面能渲染但控制台报错”的回归直接失败，而不是靠人工看日志。
 */
export const test = base.extend<{ consoleGuard: void }>({
  consoleGuard: [
    async ({ page }, use) => {
      const errors: string[] = [];

      page.on('pageerror', (error) => {
        errors.push(`pageerror: ${error.message}`);
      });

      page.on('console', (message) => {
        if (message.type() !== 'error') {
          return;
        }
        const text = message.text();
        if (CONSOLE_ERROR_ALLOWLIST.some((pattern) => pattern.test(text))) {
          return;
        }
        errors.push(`console.error: ${text}`);
      });

      await use();

      expect(errors, `页面出现未处理的运行时错误：\n${errors.join('\n')}`).toEqual([]);
    },
    { auto: true },
  ],
});

export { expect };
