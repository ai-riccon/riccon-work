import { expect, test } from './fixtures';
import { createResearchTask, openResearch } from './research';
import { login } from './session';
/**
 * 真实 API 的错误语义 E2E：401、403、409。
 *
 * 这些用例依赖独立 stub API 的控制端点制造服务端状态，但请求仍由真实
 * `ApiClient` 发出，因此覆盖 Cookie/CSRF、ProblemDetail 解析与页面状态映射。
 * 不通过应用内 mock 冒充真实集成。
 */
test.describe('API 错误语义', () => {
  test('未登录访问受保护接口返回 ProblemDetail 401', async ({ page, consoleGuard }) => {
    await page.goto('/login');
    // 用浏览器 fetch 断言 ProblemDetail：与页面请求走同一条同源/代理路径。
    consoleGuard.allowNetworkError(/\/api\/v1\/workspaces$/, 401);
    const result = await page.evaluate(async () => {
      const response = await fetch('/api/v1/workspaces', {
        headers: { Accept: 'application/json' },
      });
      return { status: response.status, body: (await response.json()) as Record<string, unknown> };
    });

    expect(result.status).toBe(401);
    expect(result.body.status).toBe(401);
    expect(result.body.code).toBe('RICCON-AUTH-401');
    expect(result.body.traceId).toBeTruthy();
    expect(result.body.title).toBe('未登录');
  });

  test('服务端会话失效后写请求返回 401，页面回到登录页并提示过期', async ({
    page,
    stub,
    consoleGuard,
  }) => {
    await login(page);

    // 模拟会话在服务端被撤销；浏览器仍持有旧 Cookie。
    await stub.revokeSessions();
    consoleGuard.allowNetworkError(/\/conversations$/, 401);
    await page.getByRole('button', { name: '新建会话' }).click();

    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByText('会话已过期')).toBeVisible();
    await expect(page.getByText(/登录状态已失效，请重新登录/)).toBeVisible();
  });

  test('报告任务列表 403 显示无权限态且可返回工作空间', async ({ page, stub, consoleGuard }) => {
    await login(page);
    await stub.setScenario('research-list-403');
    consoleGuard.allowNetworkError(/\/research-tasks(\?|$)/, 403);
    await page.getByRole('button', { name: '市场调研报告' }).click();
    await expect(page).toHaveURL(/\/research\/tasks$/);

    await expect(page.getByText('没有访问权限', { exact: true })).toBeVisible();
    await expect(page.getByText('没有访问权限，或安全令牌已失效。')).toBeVisible();
    await page.getByRole('button', { name: '返回工作空间' }).click();
    await expect(page).toHaveURL(/\/chat$/);
  });

  test('MCP 工具目录 403 时展示权限态并禁用创建', async ({ page, stub, consoleGuard }) => {
    await login(page);
    await stub.setScenario('mcp-tools-403');
    consoleGuard.allowNetworkError(/\/mcp-tools(\?|$)/, 403);
    await page.getByRole('button', { name: '市场调研报告' }).click();
    await expect(page).toHaveURL(/\/research\/tasks$/);

    await expect(page.getByText('无权使用数据工具', { exact: true })).toBeVisible();
    await expect(
      page.getByText('当前账号没有使用 MCP 数据工具的权限，请联系管理员授权后再创建任务。'),
    ).toBeVisible();
    await expect(page.getByRole('button', { name: /创建报告任务/ })).toBeDisabled();
  });

  test('审批冲突 409 时提示已刷新最新结果，任务保持待审批', async ({
    page,
    stub,
    consoleGuard,
  }) => {
    await login(page);
    await openResearch(page);
    await createResearchTask(page, '审批冲突演示：行业竞争格局');

    await expect(page.getByText('待审批：调用外部工具', { exact: true })).toBeVisible();
    await stub.setScenario('approval-409');
    consoleGuard.allowNetworkError(/\/decision$/, 409);

    const approve = page.getByRole('button', { name: /批\s*准/ });
    await approve.focus();
    await page.keyboard.press('Enter');

    await expect(page.getByText('操作未完成')).toBeVisible();
    await expect(page.getByText('审批状态已变化，已刷新最新结果。')).toBeVisible();
    await expect(page.getByRole('button', { name: /批\s*准/ })).toBeEnabled();
    await expect(page.getByText('待审批：调用外部工具', { exact: true })).toBeVisible();
  });
});
