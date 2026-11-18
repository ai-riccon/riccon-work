import {
  test as base,
  expect,
  type APIRequestContext,
  type ConsoleMessage,
} from '@playwright/test';

/** 独立 stub API 地址；Playwright 配置用同一端口启动 `e2e/stub/server.mjs`。 */
export const STUB_URL = `http://127.0.0.1:${Number(process.env.E2E_STUB_PORT ?? 8124)}`;

/** stub 可模拟的错误场景；只影响错误路径用例，不改变正常业务数据。 */
export type StubScenario =
  | 'none'
  | 'research-list-403'
  | 'mcp-tools-403'
  | 'approval-409'
  | 'citation-source-404';

/** stub 控制面；控制端点不属于产品 OpenAPI，仅 E2E 使用。 */
export interface StubControl {
  /** 清空业务数据、会话与活动 SSE 运行。 */
  reset: () => Promise<void>;
  /** 设置错误场景；`none` 恢复默认行为。 */
  setScenario: (name: StubScenario) => Promise<void>;
  /** 让所有服务端会话失效，用于验证 401 → 会话过期页面。 */
  revokeSessions: () => Promise<void>;
  /** 播种 M03 部门/空间演示数据；只影响当前用例。 */
  seedM03: () => Promise<void>;
}

/**
 * 构造 stub 控制客户端。
 *
 * 每个用例开始自动 reset，保证任务、会话与消息不跨用例泄漏；
 * 用例内部再按需切换错误场景。
 *
 * @param request Playwright API 请求上下文
 * @returns 控制方法
 */
function createStubControl(request: APIRequestContext): StubControl {
  return {
    reset: async () => {
      const response = await request.post(`${STUB_URL}/__stub__/reset`);
      expect(response.ok(), 'stub reset 失败').toBe(true);
    },
    setScenario: async (name) => {
      const response = await request.post(`${STUB_URL}/__stub__/scenario`, { data: { name } });
      expect(response.ok(), 'stub 场景切换失败').toBe(true);
    },
    revokeSessions: async () => {
      const response = await request.post(`${STUB_URL}/__stub__/revoke-sessions`);
      expect(response.ok(), 'stub 会话失效失败').toBe(true);
    },
    seedM03: async () => {
      const response = await request.post(`${STUB_URL}/__stub__/m03-seed`);
      expect(response.ok(), 'stub M03 数据播种失败').toBe(true);
    },
  };
}

/**
 * 框架告警文本白名单。
 *
 * 当前为空：/chat、/research、/admin、/login 页面在修复 antd 6 Alert 属性与 Umi MFSU
 * 互操作问题后不应出现任何框架 console.error。若未来必须放行，必须在此写明来源、版本、
 * 原因与移除条件，禁止在用例里临时屏蔽。网络错误不走这里，由下方的 URL+状态码匹配处理。
 */
const CONSOLE_ERROR_ALLOWLIST: RegExp[] = [];

/**
 * 已知可接受的网络错误白名单。
 *
 * 只放行“契约规定的探测型 401”：未登录访问 `/auth/session` 返回 401 是正常的匿名态，
 * 浏览器仍会把它记为 console.error。匹配同时要求请求 URL 与状态码，避免把其他 4xx/5xx 吞掉。
 *
 * 负路径用例（错误凭据、会话失效、403、409）必须在用例内调用
 * `consoleGuard.allowNetworkError(url, status)` 显式登记预期错误，让守卫保持可审计。
 */
const DEFAULT_EXPECTED_NETWORK_ERRORS: ExpectedNetworkError[] = [
  { url: /\/api\/v1\/auth\/session$/, status: 401 },
];

/** 预期网络错误：请求 URL 与 HTTP 状态码必须同时匹配。 */
interface ExpectedNetworkError {
  url: RegExp;
  status: number;
}

/** console 守卫控制器；用于负路径用例显式登记预期错误。 */
export interface ConsoleGuard {
  /** 登记一次预期网络错误；仅影响当前用例。 */
  allowNetworkError: (url: RegExp, status: number) => void;
}

/**
 * 判断 console.error 是否为已登记的预期网络错误。
 *
 * @param message Playwright console 消息
 * @param expected 预期错误列表
 * @returns 命中返回 true
 */
function isExpectedNetworkError(
  message: ConsoleMessage,
  expected: ExpectedNetworkError[],
): boolean {
  const text = message.text();
  if (!text.startsWith('Failed to load resource:')) {
    return false;
  }
  const statusMatch = /status of (\d+)/.exec(text);
  if (!statusMatch) {
    return false;
  }
  const status = Number(statusMatch[1]);
  const url = message.location().url;
  return expected.some((entry) => entry.status === status && entry.url.test(url));
}

/**
 * Playwright 测试基类：为每个用例重置 stub，并自动挂上浏览器 console 守卫。
 *
 * 守卫在页面导航前注册，收集 `pageerror` 与 `console.error`；用例结束时统一断言为空，
 * 让“页面能渲染但控制台报错”的回归直接失败，而不是靠人工看日志。
 */
export const test = base.extend<{ consoleGuard: ConsoleGuard; stub: StubControl }>({
  stub: [
    async ({ request }, use) => {
      const control = createStubControl(request);
      await control.reset();
      await use(control);
    },
    { auto: true },
  ],
  consoleGuard: [
    async ({ page }, use) => {
      const errors: string[] = [];
      const expected = [...DEFAULT_EXPECTED_NETWORK_ERRORS];

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
        if (isExpectedNetworkError(message, expected)) {
          return;
        }
        errors.push(`console.error: ${text}`);
      });

      await use({
        allowNetworkError: (url, status) => {
          expected.push({ url, status });
        },
      });

      expect(errors, `页面出现未处理的运行时错误：\n${errors.join('\n')}`).toEqual([]);
    },
    { auto: true },
  ],
});

export { expect };
