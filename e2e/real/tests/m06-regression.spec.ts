import { expect, test } from '@playwright/test';
import {
  api,
  createWorkflow,
  expectOk,
  loginApi,
  loginAs,
  M06_ROUTES,
  publishWorkflow,
  saveDraft,
  seed,
  simpleGraph,
} from './m06-support';
import { waitForJ1Answer } from './real-session';

/**
 * M06 既有功能回归与端点真实性（E2E-M06-10、M06-AC-22）。
 *
 * J1/J2 页面与真实链路不回归；M06 新增端点返回真实数据而非 404/deferred；
 * 发布动作可在 M10 审计查询中按对象与时间范围查到，且不含提示词/凭据。
 */

test.describe('M06 回归与审计', () => {
  test('E2E-M06-10 J1/J2 无回归、M06 端点真实实现、审计可查', async ({ page, browser }) => {
    const data = seed();

    // J1：真实工作空间问答仍可用。
    await loginAs(page, 'alpha-member', data.passwords.alphaMember);
    await page.getByRole('button', { name: '新建会话' }).click();
    await expect(page.getByText('开始 J1 问答')).toBeVisible();
    const composer = page.getByPlaceholder('输入问题，Enter 发送，Shift+Enter 换行');
    await composer.fill('市场规模与竞争格局');
    await composer.press('Enter');
    await waitForJ1Answer(page);

    // J2：既有入口与任务列表不变（完整 J2 闭环由 j2.spec.ts 在同一真实栈运行中覆盖）。
    await page.goto('/research/tasks');
    await expect(page.getByRole('heading', { name: '市场调研报告' })).toBeVisible();
    await expect(page.getByText(/报告任务|新建任务|暂无/).first()).toBeVisible();

    // M06 端点真实实现：列表/模板/限制均返回 200 与符合契约的字段。
    await loginAs(page, 'alpha-editor', data.passwords.alphaEditor);
    const endpoints = [
      ['GET', '/api/v1/workflows?view=MINE&page=1&size=20'],
      ['GET', '/api/v1/workflow-templates?page=1&size=20'],
      ['GET', '/api/v1/workflow-runs?page=1&size=20'],
      ['GET', '/api/v1/workflow-approvals?page=1&size=20'],
      ['GET', '/api/v1/workflows/runtime-limits'],
    ] as const;
    for (const [method, path] of endpoints) {
      const response = await api<Record<string, unknown>>(page, method, path);
      expect(response.status, `${path}: ${response.text}`).toBe(200);
      expect(response.text).not.toContain('deferred');
      expect(response.text).not.toContain('not-implemented');
    }
    const limits = expectOk(
      await api<{ maxNodes: number; maxSteps: number; resultMaxBytes: number }>(
        page,
        'GET',
        '/api/v1/workflows/runtime-limits',
      ),
      '运行限制',
    );
    expect(limits.maxNodes).toBeGreaterThan(0);
    expect(limits.maxSteps).toBeGreaterThan(0);
    expect(limits.resultMaxBytes).toBeGreaterThan(0);

    // 发布一次真实流程，供审计按对象与时间查询。
    const name = `E2E-M06-10 审计流程 ${Date.now()}`;
    const workflow = await createWorkflow(page, name);
    await saveDraft(page, workflow.id, workflow.draftVersion, simpleGraph());
    const before = new Date(Date.now() - 10 * 60_000).toISOString();
    await publishWorkflow(page, workflow.id, 'ONLY_SELF');
    const after = new Date().toISOString();

    // 租户管理员通过 M10 审计查询按 action + targetId + 时间范围查到发布事件。
    const adminContext = await browser.newContext({
      baseURL: process.env.REAL_WEB_BASE ?? 'http://127.0.0.1:8092',
    });
    const adminPage = await adminContext.newPage();
    try {
      await loginApi(adminPage, data.tenants.alpha, 'alpha-admin', data.passwords.alphaAdmin);
      const query = new URLSearchParams({
        action: 'workflow.published',
        targetId: workflow.id,
        occurredFrom: before,
        occurredTo: after,
        page: '1',
        size: '20',
      });
      const audit = expectOk(
        await api<{
          items: Array<{
            action: string;
            targetId: string;
            details: Record<string, unknown>;
          }>;
          total: number;
        }>(adminPage, 'GET', `/api/v1/audit-events?${query.toString()}`),
        '审计查询',
      );
      expect(audit.total).toBeGreaterThanOrEqual(1);
      const event = audit.items.find((item) => item.targetId === workflow.id);
      expect(event).toBeTruthy();
      expect(event!.action).toBe('workflow.published');
      expect(JSON.stringify(event!.details)).not.toContain('{{input.topic}}');
      expect(JSON.stringify(event!.details)).not.toContain('promptTemplate');
      expect(JSON.stringify(event!.details)).not.toContain('authorization');

      // M06 运行历史与审计查询入口均在真实栈可用。
      await adminPage.goto('/admin/audit');
      await expect(adminPage.getByText('保留策略与只读边界')).toBeVisible();
    } finally {
      await adminContext.close();
    }

    // M06 页面入口在真实导航中可见。
    await page.goto(M06_ROUTES.list);
    await expect(page.getByRole('heading', { name: '编排 Studio' })).toBeVisible();
    await expect(page.getByRole('link', { name: '模板库' })).toBeVisible();
  });
});
