import path from 'node:path';

import { expect, test, type Page, type Request, type Response } from '@playwright/test';

import { runRealStackSql } from '../../support/m04-quota-cost';
import {
  M04_FIXTURES,
  createChatAndSend,
  expectNoHorizontalScroll,
  expectNoProviderModelKeyLeak,
  loginRealLocal,
  logout,
  queueFakeModelScenario,
  resetFakeModel,
  setFakeModelScenario,
} from '../../support/m04-routing';
import { readSeed } from './real-session';

/**
 * M04-B11a 真实栈 E2E：主模型失败降级、首字后中断与审计 7 天预设。
 *
 * 数据由 `scripts/real-stack/run.mjs` 启动的真实 API/Worker/Web 与 seed 准备；
 * 假模型通过 `e2e/support/fake-model` 的控制端点注入确定性失败，不使用生产模型。
 * 运行方式（定向）：
 *
 * ```bash
 * REAL_PLAYWRIGHT_GREP='M04-B11a|E2E-M04-0[23]' \
 *   REAL_EVIDENCE_TASK=TASK-0113 node scripts/real-stack/run.mjs --mode=full
 * ```
 */

/** 平台管理员测试口令；由 `scripts/real-stack/run.mjs` 注入，不是生产凭据。 */
const PLATFORM_ADMIN_PASSWORD = 'test-bootstrap-password-12';

/** 真实栈固定报告正文中的首段标记；假模型每次返回同一文本。 */
const REPORT_HEADING = '市场规模与增长';
/** 真实栈固定报告正文中的末段标记。 */
const REPORT_TAIL = '结论与建议';

/** 真实栈假模型控制端点；与 `e2e/support/m04-routing` 的默认值保持一致。 */
const FAKE_MODEL_BASE = process.env.REAL_MODEL_BASE ?? 'http://127.0.0.1:18990';

/** 真实栈两个 ACTIVE 假模型；路由链前两位必须来自该集合才能完成跨组件断言。 */
const ACTIVE_MODEL_KEYS = ['fake-chat-primary', 'fake-chat-secondary'] as const;
/** 可路由的 ACTIVE 假模型 key。 */
type ActiveModelKey = (typeof ACTIVE_MODEL_KEYS)[number];

/** 假模型请求日志条目；只声明断言需要的字段。 */
interface FakeModelRequestLog {
  sequence: number;
  scenario: string;
  path: string;
  model: string | null;
  status: number | null;
  errorCode?: string | null;
}

/**
 * 读取平台管理员账号。
 *
 * @returns 平台管理员用户名
 */
function platformAdminUsername(): string {
  const seed = readSeed();
  return seed.users.platformAdmin;
}

/**
 * 登录平台管理员。
 *
 * @param page Playwright 页面
 */
async function loginPlatformAdminReal(page: Page): Promise<void> {
  await loginRealLocal(page, 'platform', platformAdminUsername(), PLATFORM_ADMIN_PASSWORD);
}

/**
 * 从路由行文本中提取唯一的 ACTIVE 假模型 key。
 *
 * 路由行同时渲染展示名、modelKey、状态与端点标签；只接受真实栈的两个 ACTIVE 假模型，
 * 避免把停用模型或乱码展示名当成断言基准。
 *
 * @param text 行文本
 * @param context 失败信息中的位置说明
 * @returns 命中的 ACTIVE 模型 key
 */
function activeModelKeyIn(text: string, context: string): ActiveModelKey {
  const matched = ACTIVE_MODEL_KEYS.filter((key) => text.includes(key));
  expect(matched, `${context} 必须恰好包含一个 ACTIVE 假模型 key；实际文本：${text}`).toHaveLength(
    1,
  );
  return matched[0];
}

/**
 * 返回假模型 key 对应的 provider 端点路径。
 *
 * seed 中主供应商 baseUrl 为 `<fake>/v1`、备用供应商为 `<fake>/v1-secondary`；
 * 路径来自假模型请求日志，能同时证明“用了哪个模型”和“走了哪个供应商端点”。
 *
 * @param modelKey ACTIVE 假模型 key
 * @returns chat/completions 路径
 */
function providerPathFor(modelKey: ActiveModelKey): string {
  return modelKey === 'fake-chat-secondary'
    ? '/v1-secondary/chat/completions'
    : '/v1/chat/completions';
}

/**
 * 读取假模型请求日志；日志只保留掩码与模型标识，不含 Authorization 明文。
 *
 * @returns 请求时间线
 */
async function readFakeModelRequests(): Promise<FakeModelRequestLog[]> {
  const response = await fetch(`${FAKE_MODEL_BASE}/__log__`);
  expect(response.status, '读取假模型请求日志失败').toBe(200);
  const data = (await response.json()) as { requests: FakeModelRequestLog[] };
  return data.requests;
}

/**
 * 通过只读管理 API 读取当前 CHAT 链的模型 ID 顺序。
 *
 * 只用于确认 UI 保存已持久化到服务端（不是直接调用写接口）；
 * 运行时是否真正使用新链仍以假模型请求日志为准。
 *
 * @param page Playwright 页面（复用平台管理员会话）
 * @returns 有序模型 ID
 */
async function readChatChainModelIds(page: Page): Promise<string[]> {
  const response = await page.request.get('/api/v1/admin/model-routing');
  expect(response.status(), '读取路由链失败').toBe(200);
  const data = (await response.json()) as {
    items: Array<{ purpose: string; entries: Array<{ modelId: string }> }>;
  };
  const chat = data.items.find((item) => item.purpose === 'CHAT');
  const modelIds = chat?.entries.map((entry) => entry.modelId) ?? [];
  expect(modelIds.length, 'CHAT 路由链缺失或为空').toBeGreaterThanOrEqual(3);
  return modelIds;
}

/** 真实栈 J1 fixture；与 J1 旅程使用同一份可解析 DOCX。 */
const MARKET_DOCX = path.resolve(__dirname, '../fixtures/market.docx');

/** 真实引用条目最小字段；与 OpenAPI Citation 对齐，只声明本文件断言的字段。 */
interface ServerCitation {
  ordinal: number;
  sourceType: 'FILE' | 'KNOWLEDGE';
  fileId?: string | null;
  knowledgeDocumentId?: string | null;
  locatorType?: 'PAGE' | 'SHEET' | 'PARAGRAPH' | null;
  paragraphNo?: number | null;
  title: string;
  snippet: string;
}

/** 会话消息最小字段；用于读取服务端落库结果，避免只依赖前端渲染。 */
interface ServerMessage {
  seq: number;
  role: 'USER' | 'ASSISTANT' | 'SYSTEM' | 'TOOL';
  content: string;
  status: 'PENDING' | 'STREAMING' | 'COMPLETED' | 'FAILED' | 'STOPPED';
  errorCode?: string | null;
  citations?: ServerCitation[];
}

/** 发送消息请求体最小字段。 */
interface SendMessageBody {
  content: string;
  clientMessageId: string;
  fileIds?: string[];
}

/**
 * 判断是否为员工发送消息请求。
 *
 * 只匹配契约路径，避免把文件上传、停止生成等 POST 误当成一次新回答。
 *
 * @param request Playwright 请求
 * @returns 是否消息发送请求
 */
function isSendMessageRequest(request: Request): boolean {
  return (
    request.method() === 'POST' &&
    /\/api\/v1\/conversations\/[^/]+\/messages$/.test(new URL(request.url()).pathname)
  );
}

/**
 * 判断是否为员工发送消息响应。
 *
 * @param response Playwright 响应
 * @returns 是否消息发送响应
 */
function isSendMessageResponse(response: Response): boolean {
  return isSendMessageRequest(response.request());
}

/**
 * 新建会话并返回服务端会话 ID。
 *
 * 会话 ID 用于在回答结束后读取服务端消息/引用快照，证明界面内容不是前端拼装。
 *
 * @param page Playwright 页面
 * @returns 会话 ID
 */
async function openNewConversation(page: Page): Promise<string> {
  const created = page.waitForResponse((response) => {
    return (
      response.request().method() === 'POST' &&
      /\/api\/v1\/workspaces\/[^/]+\/conversations$/.test(new URL(response.url()).pathname)
    );
  });
  await page.getByRole('button', { name: '新建会话' }).click();
  const response = await created;
  expect(response.status(), '新建会话必须 201').toBe(201);
  await expect(page.getByText('开始 J1 问答')).toBeVisible();
  const body = (await response.json()) as { id: string };
  return body.id;
}

/**
 * 在当前会话输入并发送问题；不新建会话，保证文件与上下文留在同一会话。
 *
 * @param page Playwright 页面
 * @param question 问题
 */
async function sendInActiveConversation(page: Page, question: string): Promise<void> {
  const composer = page.getByPlaceholder('输入问题，Enter 发送，Shift+Enter 换行');
  await composer.fill(question);
  await composer.press('Enter');
}

/**
 * 上传真实 DOCX 并等待解析成功；返回服务端文件 ID。
 *
 * 解析成功后才发送问题，模拟 R-M04-03 要求的“先上传可解析文件再提问”。
 *
 * @param page Playwright 页面
 * @param workspaceId 工作空间 ID
 * @param conversationId 会话 ID
 * @returns 上传文件 ID
 */
async function uploadMarketDocx(
  page: Page,
  workspaceId: string,
  conversationId: string,
): Promise<string> {
  const chooserPromise = page.waitForEvent('filechooser');
  await page.getByRole('button', { name: /上传文件/ }).click();
  const chooser = await chooserPromise;
  await chooser.setFiles(MARKET_DOCX);
  await expect(
    page.locator('.chat-file-item').first().getByText('解析成功', { exact: true }),
  ).toBeVisible({ timeout: 60_000 });

  const response = await page.request.get(
    `/api/v1/workspaces/${encodeURIComponent(workspaceId)}/files?conversationId=${encodeURIComponent(conversationId)}&page=1&size=100`,
  );
  expect(response.status(), '读取会话文件列表失败').toBe(200);
  const body = (await response.json()) as {
    items: Array<{ id: string; originalName: string; status: string }>;
  };
  const file = body.items.find((item) => item.originalName === 'market.docx');
  expect(file, '会话文件列表必须包含刚上传的 market.docx').toBeTruthy();
  expect(file!.status).toBe('AVAILABLE');
  return file!.id;
}

/**
 * 读取会话详情并按 seq 升序返回消息。
 *
 * @param page Playwright 页面
 * @param conversationId 会话 ID
 * @returns 按 seq 排序的消息
 */
async function readConversationMessages(
  page: Page,
  conversationId: string,
): Promise<ServerMessage[]> {
  const response = await page.request.get(
    `/api/v1/conversations/${encodeURIComponent(conversationId)}`,
  );
  expect(response.status(), '读取会话详情失败').toBe(200);
  const body = (await response.json()) as { messages: ServerMessage[] };
  return [...body.messages].sort((left, right) => left.seq - right.seq);
}

/**
 * 清理平台模型 key 的健康状态。
 *
 * 降级与全链失败用例会按产品语义累计失败并可能进入冷却；R-M04-03 需要从“主 key 可用”
 * 的干净状态开始，并在用例结束后恢复，避免污染同一次真实栈运行中的 J2/M03 阶段。
 * 只作用于一次性测试栈，不改变任何生产行为。
 */
function resetPlatformKeyHealth(): void {
  runRealStackSql(`
UPDATE provider_credential
   SET status = 'ACTIVE', consecutive_failures = 0, cooldown_until = NULL, invalidated_at = NULL
 WHERE tenant_id = 'platform' AND credential_type = 'API_KEY';
`);
}

test.describe('M04-B11a 真实栈降级与审计', () => {
  // 假模型是跨 Playwright 用例的外部进程；每个用例结束恢复 ok，避免污染后续
  // real-stack 阶段（J2 后端闭环）或同文件后续用例。
  test.afterEach(async () => {
    await resetFakeModel();
  });

  test('E2E-M04-02 主模型失败自动降级，员工无感且审计可按 7 天预设查询', async ({ page }) => {
    const seed = readSeed();
    await resetFakeModel();
    // 同 key 重试一次后再换模型的规则：两次 5xx 由主 key 消费，第三次由备用模型成功。
    await queueFakeModelScenario('server_error', 2);

    await loginRealLocal(
      page,
      seed.tenants.alpha,
      seed.users.alphaMember,
      seed.passwords.alphaMember,
      'Alpha 员工',
    );
    await createChatAndSend(page, '请总结 2026 年企业协同办公市场规模');

    // 降级成功：员工拿到完整回答，不出现 provider/model/key 或“已切换”提示。
    await expect(page.getByText(REPORT_HEADING, { exact: false }).first()).toBeVisible({
      timeout: 60_000,
    });
    await expect(page.getByText(REPORT_TAIL, { exact: false }).first()).toBeVisible({
      timeout: 60_000,
    });
    await expect(page.getByText(/已切换|已降级/)).toHaveCount(0);
    await expectNoProviderModelKeyLeak(page);

    // 运行详情按 M02 既有要求保留实际提供首字的模型标识。
    await page.getByRole('button', { name: '运行详情' }).last().click();
    const runDrawer = page.getByRole('dialog').filter({ hasText: '运行详情' });
    await expect(runDrawer.getByText('模型调用')).toBeVisible();
    await expect(runDrawer.getByText('fake-chat-secondary').first()).toBeVisible();
    await page.keyboard.press('Escape');

    // 平台侧审计：换租户管理员账号后按“降级事件（最近 7 天）”预设查询。
    await logout(page);
    await loginRealLocal(
      page,
      seed.tenants.alpha,
      seed.users.alphaAdmin,
      seed.passwords.alphaAdmin,
      'Alpha 管理员',
    );
    await page.goto('/admin/audit');
    await expect(page.getByText('保留策略与只读边界')).toBeVisible();
    await expect(page.getByText(/降级事件预设为最近 7 天/)).toBeVisible();

    await page.getByRole('button', { name: '降级事件（最近 7 天）' }).click();
    await expect(page.getByText('已应用降级事件预设')).toBeVisible();
    await expect(page.getByText(/已应用 降级事件（最近 7 天）/)).toBeVisible();
    await expect(page.getByText('model.failover.triggered').first()).toBeVisible({
      timeout: 15_000,
    });

    // 对象类型可继续筛选为“模型”，并保持命中同一降级事件。
    await page.getByRole('combobox', { name: '对象类型' }).click();
    await page
      .locator('.ant-select-dropdown:visible .ant-select-item-option')
      .filter({ hasText: '模型' })
      .first()
      .click();
    await page.getByRole('button', { name: /查\s*询/ }).click();
    await expect(page.getByText('model.failover.triggered').first()).toBeVisible();

    // 详情只包含模型 ID/原因/尝试号，不含 provider 地址、key 或明文。
    await page
      .getByRole('button', { name: /查看详情：事件 model.failover.triggered/ })
      .first()
      .click();
    const detailDrawer = page.getByRole('dialog').filter({ hasText: '审计事件详情' });
    await expect(detailDrawer.getByText('附加详情')).toBeVisible();
    await expect(detailDrawer.getByText('发生时间')).toBeVisible();
    await expect(detailDrawer.getByText('追踪 ID')).toBeVisible();
    const detailText = await detailDrawer.locator('.audit-details-json').innerText();
    expect(detailText).toContain('"fromModelId"');
    expect(detailText).toContain('"toModelId"');
    expect(detailText).toContain('"reason"');
    expect(detailText).toContain('"attempt"');
    expect(detailText).not.toContain('127.0.0.1');
    expect(detailText).not.toContain('fake-chat-');
    await page.keyboard.press('Escape');
  });

  test('E2E-M04-03 首字后中断保留内容、重试成功且审计记录已输出状态', async ({ page }) => {
    const seed = readSeed();
    await resetFakeModel();
    await setFakeModelScenario('stream_break');

    await loginRealLocal(
      page,
      seed.tenants.alpha,
      seed.users.alphaMember,
      seed.passwords.alphaMember,
      'Alpha 员工',
    );
    await createChatAndSend(page, '请总结 2026 年企业协同办公市场');

    // 首字后断流：已输出内容保留，显示统一中断文案与重试入口，不重放。
    await expect(page.getByText(REPORT_HEADING, { exact: false }).first()).toBeVisible({
      timeout: 60_000,
    });
    await expect(page.getByText('回答已中断，已保留已生成内容，可重试。')).toBeVisible({
      timeout: 60_000,
    });
    await expect(page.getByText('错误码：RICCON-MODEL-502')).toBeVisible();
    const retry = page.getByRole('button', { name: /重\s*试/ });
    await expect(retry).toBeVisible();
    // 只定位助手气泡：用户消息同样使用 .message-text，不能用首个子元素判断回答内容。
    const partialBubble = page
      .locator('.chat-message-list .message-text')
      .filter({ hasText: REPORT_HEADING })
      .first();
    const partial = await partialBubble.innerText();
    expect(partial).toContain('市场规模与增长');
    expect(partial).not.toContain(REPORT_TAIL);

    // 重试后新回答完整，旧内容仍在且没有把新旧回答拼成重复段落。
    await setFakeModelScenario('ok');
    await retry.click();
    await expect(page.getByText(REPORT_TAIL, { exact: false })).toBeVisible({ timeout: 60_000 });
    await expect(
      page.locator('.chat-message-list .message-text').filter({ hasText: REPORT_HEADING }),
    ).toHaveCount(2);
    const fullBubble = page
      .locator('.chat-message-list .message-text')
      .filter({ hasText: REPORT_TAIL })
      .first();
    const secondText = await fullBubble.innerText();
    expect(secondText.match(/市场规模与增长/g) ?? []).toHaveLength(1);
    expect(secondText.match(/结论与建议/g) ?? []).toHaveLength(1);

    // 审计：失败记录包含 emitted=true，证明“首字后失败”语义被保留。
    await logout(page);
    await loginRealLocal(
      page,
      seed.tenants.alpha,
      seed.users.alphaAdmin,
      seed.passwords.alphaAdmin,
      'Alpha 管理员',
    );
    const response = await page.request.get(
      '/api/v1/audit-events?action=model.invocation.failed&page=1&size=50',
    );
    expect(response.status()).toBe(200);
    const pageData = (await response.json()) as {
      items: Array<{ action: string; details?: Record<string, unknown> | null }>;
    };
    expect(
      pageData.items.some(
        (item) =>
          item.action === 'model.invocation.failed' &&
          item.details?.emitted === true &&
          item.details?.errorCode === 'RICCON-MODEL-502',
      ),
      '首字后中断审计必须保留 emitted=true 与稳定错误码',
    ).toBe(true);
  });

  test('M04-B11a 真实栈 key 生命周期与路由审计（AC-07 审计段）', async ({ page }) => {
    await resetFakeModel();
    await loginPlatformAdminReal(page);

    // 路由页：真实 API 保存后审计含 purpose/version/modelIds。
    await page.goto('/admin/model-routing');
    await expect(page.getByText('模型路由', { exact: true }).first()).toBeVisible();
    await expect(page.getByText(M04_FIXTURES.endpoints.demoOnly).first()).toBeVisible();
    const chatCard = page.locator('.model-routing-card').filter({ hasText: '对话链' });
    // 真实栈 seed 的模型展示名当前存在字符集乱码（B8 种子问题，已记录）；按稳定 modelKey 定位，
    // 避免把乱码当成 E2E 断言基准。
    const primaryRow = chatCard
      .locator('.model-routing-item')
      .filter({ hasText: 'fake-chat-primary' });
    await expect(primaryRow.getByText('fake-chat-primary')).toBeVisible();
    await primaryRow.getByRole('button', { name: /^下\s*移/ }).click();
    await chatCard.getByRole('button', { name: '保存对话链' }).click();
    await expect(page.getByText(/已保存对话链（版本 v/, { exact: false })).toBeVisible();

    const routingAudit = await page.request.get(
      '/api/v1/audit-events?action=model.routing.updated&page=1&size=20',
    );
    expect(routingAudit.status()).toBe(200);
    const routingData = (await routingAudit.json()) as {
      total: number;
      items: Array<{ details?: Record<string, unknown> | null }>;
    };
    expect(routingData.total).toBeGreaterThan(0);
    expect(
      routingData.items.some(
        (item) =>
          item.details?.purpose === 'CHAT' &&
          Array.isArray(item.details?.modelIds) &&
          (item.details?.modelIds as unknown[]).length === 3,
      ),
    ).toBe(true);

    // key 页：新增/轮换/停用全部写入审计，响应与审计只保留掩码。
    const label = 'E2E 真实栈 key';
    const firstKey = 'e2e-real-stack-key-0001';
    const rotatedKey = 'e2e-real-stack-key-0002';
    await page.goto('/admin/model-keys');
    await expect(page.getByText('模型 key', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('key 生命周期与在途影响')).toBeVisible();

    await page.getByRole('button', { name: '新增 key' }).click();
    const createDialog = page.getByRole('dialog');
    await createDialog.getByLabel('key 名称').fill(label);
    await createDialog.getByLabel('API key').fill(firstKey);
    await createDialog.getByRole('button', { name: '新增 key' }).click();
    await expect(page.getByText(/已新增 key「E2E 真实栈 key」（\*\*\*\*0001）/)).toBeVisible();

    const createdRow = page
      .locator('.model-key-item')
      .filter({ hasText: label })
      .filter({ hasText: '****0001' });
    await createdRow.getByRole('button', { name: `轮换 key ${label}` }).click();
    const rotateDialog = page.getByRole('dialog');
    await expect(rotateDialog.getByText('轮换影响（保存前请确认）')).toBeVisible();
    await expect(
      rotateDialog.getByText(/进行中的流式请求不会被取消，可能以可重试错误结束，且不会重复输出/),
    ).toBeVisible();
    await rotateDialog.getByLabel('API key').fill(rotatedKey);
    await rotateDialog.getByRole('button', { name: '确认轮换' }).click();
    await expect(page.getByText(/已轮换 key「E2E 真实栈 key」（\*\*\*\*0002）/)).toBeVisible();

    const rotatedRow = page
      .locator('.model-key-item')
      .filter({ hasText: label })
      .filter({ hasText: '****0002' });
    await rotatedRow.getByRole('button', { name: `停用 key ${label}` }).click();
    const disableDialog = page.getByRole('dialog').filter({ hasText: `停用 key「${label}」` });
    await expect(
      disableDialog.getByText('停用后新请求不再使用该 key；在途流式请求继续，不会中断。'),
    ).toBeVisible();
    await disableDialog.getByRole('button', { name: /^停\s*用$/ }).click();
    await expect(page.getByText(/已停用 key「E2E 真实栈 key」/)).toBeVisible();

    const bodyText = await page.locator('body').innerText();
    expect(bodyText).not.toContain(firstKey);
    expect(bodyText).not.toContain(rotatedKey);

    for (const action of [
      'model.credential.created',
      'model.credential.rotated',
      'model.credential.disabled',
    ]) {
      const audit = await page.request.get(`/api/v1/audit-events?action=${action}&page=1&size=50`);
      expect(audit.status(), `${action} 审计查询失败`).toBe(200);
      const responseText = await audit.text();
      expect(responseText).not.toContain(firstKey);
      expect(responseText).not.toContain(rotatedKey);
      const data = JSON.parse(responseText) as {
        total: number;
        items: Array<{ details?: Record<string, unknown> | null }>;
      };
      expect(data.total, `${action} 未写入审计`).toBeGreaterThan(0);
      const masks = data.items
        .map((item) => item.details?.maskedHint)
        .filter((value): value is string => typeof value === 'string');
      expect(masks, `${action} 审计必须包含掩码`).toContain(
        action === 'model.credential.created' ? '****0001' : '****0002',
      );
    }
  });

  test('M04-AC-05 全链失败员工侧稳定 503、错误码与中文文案', async ({ page }) => {
    const seed = readSeed();
    await resetFakeModel();
    await setFakeModelScenario('server_error');
    await loginRealLocal(
      page,
      seed.tenants.alpha,
      seed.users.alphaMember,
      seed.passwords.alphaMember,
      'Alpha 员工',
    );

    // 捕获员工提交消息的 HTTP 响应，直接断言 503 ProblemDetail 与稳定错误码/文案。
    const failedResponsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        /\/api\/v1\/conversations\/[^/]+\/messages$/.test(new URL(response.url()).pathname),
      { timeout: 60_000 },
    );
    await createChatAndSend(page, '请总结 2026 年企业协同办公市场');
    const failedResponse = await failedResponsePromise;
    expect(failedResponse.status()).toBe(503);
    const problem = (await failedResponse.json()) as {
      code?: string;
      detail?: string;
      title?: string;
    };
    expect(problem.code).toBe('RICCON-MODEL-503');
    expect(problem.detail ?? problem.title).toBe('模型服务暂不可用，请稍后重试。');

    await expect(page.getByText('模型服务暂不可用，请稍后重试。')).toBeVisible({
      timeout: 60_000,
    });
    await expect(page.getByText('错误码：RICCON-MODEL-503')).toBeVisible();
    await expect(page.getByRole('button', { name: /重\s*试/ })).toBeVisible();
    await expectNoHorizontalScroll(page);
    await expectNoProviderModelKeyLeak(page);
  });

  test('M04-AC-01 UI 保存对话链后下一次 CHAT 实际使用新链', async ({ page }) => {
    const seed = readSeed();
    await resetFakeModel();
    await loginPlatformAdminReal(page);

    // 1. 管理员通过真实 UI 调整 CHAT 链：把当前链首下移一位（前两位互换）。
    await page.goto('/admin/model-routing');
    await expect(page.getByText('模型路由', { exact: true }).first()).toBeVisible();
    const chatCard = page.locator('.model-routing-card').filter({ hasText: '对话链' });
    const chatRows = chatCard.locator('.model-routing-item');
    await expect(chatRows).toHaveCount(3);

    // 保存前先记录服务端顺序，用于证明保存确实落库而非只改本地草稿。
    const beforeIds = await readChatChainModelIds(page);
    await chatRows
      .nth(0)
      .getByRole('button', { name: /^下\s*移/ })
      .click();
    const promotedKey = activeModelKeyIn(await chatRows.nth(0).innerText(), '下移后的链首');
    await expect(chatRows.nth(0)).toContainText(promotedKey);
    await chatCard.getByRole('button', { name: '保存对话链' }).click();
    await expect(page.getByText(/已保存对话链（版本 v/)).toBeVisible();
    await expect(page.getByText(/下一次模型请求生效/).first()).toBeVisible();

    const afterIds = await readChatChainModelIds(page);
    expect(
      afterIds,
      `保存后 CHAT 链顺序应与 UI 草稿一致（前两位互换）：before=${JSON.stringify(beforeIds)} after=${JSON.stringify(afterIds)}`,
    ).toEqual([beforeIds[1], beforeIds[0], ...beforeIds.slice(2)]);

    // 2. 清空假模型日志：随后只观察“保存之后”的模型请求。
    await resetFakeModel();

    // 3. 同一条用例内，员工通过真实 UI 发起下一次 CHAT（不直接调用路由写接口）。
    await logout(page);
    await loginRealLocal(
      page,
      seed.tenants.alpha,
      seed.users.alphaMember,
      seed.passwords.alphaMember,
      'Alpha 员工',
    );
    await createChatAndSend(page, '请总结 2026 年企业协同办公市场规模');
    await expect(page.getByText(REPORT_TAIL, { exact: false }).first()).toBeVisible({
      timeout: 60_000,
    });
    // “运行详情”只在流结束后出现，代表本次 CHAT 已到终态；等到它再读假模型日志，
    // 避免在最后一个增量之后、finish/usage 之前读到 status=null 的中间态。
    const detailButton = page.getByRole('button', { name: '运行详情' }).last();
    await expect(detailButton).toBeVisible({ timeout: 60_000 });

    // 4. 新链生效的可观测证据：假模型收到的 model 与 provider 端点。
    const requests = await readFakeModelRequests();
    await test.info().attach('fake-model-log.json', {
      contentType: 'application/json',
      body: JSON.stringify(
        { promotedKey, expectedPath: providerPathFor(promotedKey), beforeIds, afterIds, requests },
        null,
        2,
      ),
    });
    expect(
      requests.length,
      `保存路由后的下一次 CHAT 未产生任何模型请求：${JSON.stringify(requests)}`,
    ).toBeGreaterThan(0);
    const expectedPath = providerPathFor(promotedKey);
    for (const request of requests) {
      expect(
        request.model,
        `CHAT 未使用新链首 ${promotedKey}（期望 model=${promotedKey} path=${expectedPath}）：${JSON.stringify(request)}`,
      ).toBe(promotedKey);
      expect(
        request.path,
        `CHAT 未命中 ${promotedKey} 的供应商端点 ${expectedPath}：${JSON.stringify(request)}`,
      ).toBe(expectedPath);
      expect(request.status, `模型请求未成功：${JSON.stringify(request)}`).toBe(200);
    }

    // 5. 运行详情（产品界面）显示实际使用的模型，作为跨组件第二信号。
    await detailButton.click();
    const runDrawer = page.getByRole('dialog').filter({ hasText: '运行详情' });
    await expect(runDrawer.getByText(promotedKey).first()).toBeVisible();
    await page.keyboard.press('Escape');

    // 6. 还原链顺序，避免影响同文件后续用例或其他真实栈阶段。
    await logout(page);
    await loginPlatformAdminReal(page);
    await page.goto('/admin/model-routing');
    const restoreCard = page.locator('.model-routing-card').filter({ hasText: '对话链' });
    const restoreRows = restoreCard.locator('.model-routing-item');
    await expect(restoreRows).toHaveCount(3);
    await expect(restoreRows.nth(0)).toContainText(promotedKey);
    await restoreRows
      .nth(0)
      .getByRole('button', { name: /^下\s*移/ })
      .click();
    await restoreCard.getByRole('button', { name: '保存对话链' }).click();
    await expect(page.getByText(/已保存对话链（版本 v/)).toBeVisible();
    expect(await readChatChainModelIds(page), '用例结束必须把 CHAT 链还原为进入时的顺序').toEqual(
      beforeIds,
    );
  });

  test.describe('R-M04-03 三条定向断言', () => {
    // 用例会主动注入模型失败并可能让 key 进入冷却；结束后清理，避免污染后续真实栈阶段。
    test.afterEach(() => {
      resetPlatformKeyHealth();
    });

    test('R-M04-03 降级 run 携带上传文件仍返回可定位引用', async ({ page }) => {
      const seed = readSeed();
      resetPlatformKeyHealth();
      await resetFakeModel();
      await loginRealLocal(
        page,
        seed.tenants.alpha,
        seed.users.alphaMember,
        seed.passwords.alphaMember,
        'Alpha 员工',
      );

      // 先在同一会话上传并解析文件；引用检索依赖服务端文件快照，不能用知识库命中替代。
      const conversationId = await openNewConversation(page);
      const fileId = await uploadMarketDocx(page, seed.workspaces.alpha.id, conversationId);
      await expect(page.getByText('提问会附带 1 个已解析文件；引用可点击查看位置。')).toBeVisible();

      // 同 key 重试一次后再换模型的规则：两次 5xx 由主 key 消费，第三次由备用模型成功。
      await queueFakeModelScenario('server_error', 2);
      const messageRequestPromise = page.waitForRequest(isSendMessageRequest);
      await sendInActiveConversation(page, '请总结真实栈文件中的市场规模与竞争格局');
      const messageRequest = await messageRequestPromise;
      const messageBody = messageRequest.postDataJSON() as SendMessageBody;
      expect(messageBody.fileIds, '降级 run 必须在同一请求中携带已上传文件').toEqual([fileId]);

      // 降级成功：员工拿到完整回答，不出现 provider/model/key 或“已切换/已降级”提示。
      await expect(page.getByText(REPORT_HEADING, { exact: false }).first()).toBeVisible({
        timeout: 60_000,
      });
      await expect(page.getByText(REPORT_TAIL, { exact: false }).first()).toBeVisible({
        timeout: 60_000,
      });
      await expect(page.getByText(/已切换|已降级/)).toHaveCount(0);
      await expectNoProviderModelKeyLeak(page);

      const detailButton = page.getByRole('button', { name: '运行详情' }).last();
      await expect(detailButton).toBeVisible({ timeout: 60_000 });

      // 服务端快照：引用条数与文件 ID 必须对应本次上传文件，不能只凭前端文本判断。
      const messages = await readConversationMessages(page, conversationId);
      const assistant = messages.find(
        (message) => message.role === 'ASSISTANT' && message.status === 'COMPLETED',
      );
      expect(assistant, '降级 run 必须落库为完成态助手消息').toBeTruthy();
      const citations = assistant!.citations ?? [];
      expect(citations.length, '带文件提问必须产生引用').toBeGreaterThan(0);
      const fileCitations = citations.filter((citation) => citation.sourceType === 'FILE');
      expect(
        fileCitations.length,
        '引用必须包含上传文件来源，而不是只有知识库命中',
      ).toBeGreaterThan(0);
      for (const citation of fileCitations) {
        expect(citation.fileId, '文件引用必须指向本次上传文件').toBe(fileId);
        expect(citation.title).toBe('market.docx');
        expect(citation.locatorType).toBe('PARAGRAPH');
        expect(citation.paragraphNo ?? null, '文件引用必须保留段落定位').not.toBeNull();
        expect(citation.snippet.trim().length, '文件引用必须包含非空片段').toBeGreaterThan(0);
      }

      // 前端引用条数与服务端快照一致，并逐条对应序号、标题与段落定位。
      const citationBlock = page.locator('.citation-block').last();
      await expect(citationBlock.getByText('引用来源')).toBeVisible();
      const citationButtons = citationBlock.locator('.citation-link');
      await expect(citationButtons).toHaveCount(citations.length);
      for (let index = 0; index < citations.length; index += 1) {
        const text = await citationButtons.nth(index).innerText();
        const citation = citations[index]!;
        expect(text).toContain(`[${citation.ordinal}]`);
        expect(text).toContain(citation.title);
        expect(text).toMatch(/第 \d+ 段/);
      }

      // 打开文件引用：来源类型、文件标题、段落定位与片段都必须来自上传文件快照。
      const fileCitationIndex = citations.findIndex((citation) => citation.sourceType === 'FILE');
      await citationButtons.nth(fileCitationIndex).click();
      const citationDrawer = page.getByRole('dialog').filter({ hasText: '引用详情' });
      await expect(citationDrawer.getByText('引用详情')).toBeVisible();
      await expect(citationDrawer.getByRole('heading', { name: 'market.docx' })).toBeVisible();
      await expect(citationDrawer.getByText(/类型：文件；定位：\s*第 \d+ 段/)).toBeVisible();
      const snippet = (await citationDrawer.locator('.citation-snippet').innerText()).trim();
      expect(snippet.length, '文件引用抽屉必须展示非空片段').toBeGreaterThan(0);
      await page.keyboard.press('Escape');
      await expect(citationDrawer).toBeHidden();

      // 运行详情保留实际提供首字的模型标识。
      await detailButton.click();
      const runDrawer = page.getByRole('dialog').filter({ hasText: '运行详情' });
      await expect(runDrawer.getByText('模型调用')).toBeVisible();
      await expect(runDrawer.getByText('fake-chat-secondary').first()).toBeVisible();
      await page.keyboard.press('Escape');

      // 假模型请求日志证明降级确实发生：主模型两次 5xx，备用模型完成本次 run。
      const requests = await readFakeModelRequests();
      const primaryFailures = requests.filter((request) => request.scenario === 'server_error');
      expect(primaryFailures, `主模型应恰好失败两次：${JSON.stringify(requests)}`).toHaveLength(2);
      for (const failure of primaryFailures) {
        expect(failure.model).toBe('fake-chat-primary');
        expect(failure.status).toBe(500);
      }
      const successes = requests.filter(
        (request) => request.scenario === 'ok' && request.status === 200,
      );
      expect(successes, `备用模型应完成一次请求：${JSON.stringify(requests)}`).toHaveLength(1);
      expect(successes[0]!.model).toBe('fake-chat-secondary');

      await test.info().attach('r-m04-03-failover-citations.json', {
        contentType: 'application/json',
        body: JSON.stringify({ conversationId, fileId, messageBody, citations, requests }, null, 2),
      });
    });

    test('R-M04-03 全链失败后保留已上传文件且重试为独立请求', async ({ page }) => {
      const seed = readSeed();
      resetPlatformKeyHealth();
      await resetFakeModel();
      await setFakeModelScenario('server_error');
      await loginRealLocal(
        page,
        seed.tenants.alpha,
        seed.users.alphaMember,
        seed.passwords.alphaMember,
        'Alpha 员工',
      );

      const conversationId = await openNewConversation(page);
      await uploadMarketDocx(page, seed.workspaces.alpha.id, conversationId);
      await expect(page.getByText('提问会附带 1 个已解析文件；引用可点击查看位置。')).toBeVisible();

      // 全链失败：固定 503 与稳定错误码，请求本身必须携带已上传文件。
      const failedRequestPromise = page.waitForRequest(isSendMessageRequest);
      const failedResponsePromise = page.waitForResponse(isSendMessageResponse);
      await sendInActiveConversation(page, '请总结真实栈文件中的市场规模');
      const failedRequest = await failedRequestPromise;
      const failedResponse = await failedResponsePromise;
      expect(failedResponse.status()).toBe(503);
      const problem = (await failedResponse.json()) as {
        code?: string;
        detail?: string;
        title?: string;
      };
      expect(problem.code).toBe('RICCON-MODEL-503');
      expect(problem.detail ?? problem.title).toBe('模型服务暂不可用，请稍后重试。');
      const failedBody = failedRequest.postDataJSON() as SendMessageBody;
      const failedFileIds = failedBody.fileIds ?? [];
      expect(failedFileIds, '失败请求必须携带已上传文件').toHaveLength(1);

      // 失败后文件仍留在文件列表与输入区，重试入口可用。
      await expect(page.getByText('模型服务暂不可用，请稍后重试。')).toBeVisible({
        timeout: 60_000,
      });
      await expect(page.getByText('错误码：RICCON-MODEL-503')).toBeVisible();
      const retry = page.locator('.message-error').getByRole('button', { name: /重\s*试/ });
      await expect(retry).toBeVisible();
      await expect(page.locator('.chat-file-item').filter({ hasText: 'market.docx' })).toHaveCount(
        1,
      );
      await expect(page.getByText('会话文件 1/5')).toBeVisible();
      await expect(page.getByText('提问会附带 1 个已解析文件；引用可点击查看位置。')).toBeVisible();

      // 重试是新的独立消息请求：新 clientMessageId/幂等键，但仍携带同一个文件。
      // 全链失败可能让平台 key 进入退避；先恢复测试栈 key 健康，再验证应用层重试路径。
      await setFakeModelScenario('ok');
      resetPlatformKeyHealth();
      const retryRequestPromise = page.waitForRequest(isSendMessageRequest);
      const retryResponsePromise = page.waitForResponse(isSendMessageResponse);
      await retry.click();
      const retryRequest = await retryRequestPromise;
      const retryResponse = await retryResponsePromise;
      expect([200, 201], '重试请求应成功').toContain(retryResponse.status());
      const retryBody = retryRequest.postDataJSON() as SendMessageBody;
      expect(retryBody.fileIds, '重试请求仍携带已上传文件').toEqual(failedFileIds);
      expect(retryBody.clientMessageId).not.toBe(failedBody.clientMessageId);
      expect(retryRequest.headers()['idempotency-key']).not.toBe(
        failedRequest.headers()['idempotency-key'],
      );

      await expect(page.getByText(REPORT_TAIL, { exact: false }).first()).toBeVisible({
        timeout: 60_000,
      });
      await expect(page.getByRole('button', { name: '运行详情' }).last()).toBeVisible({
        timeout: 60_000,
      });

      // 失败记录仍在、文件仍在；完整正文只出现一次，旧回答没有被拼接或重放。
      await expect(page.getByText('错误码：RICCON-MODEL-503')).toBeVisible();
      await expect(page.locator('.chat-file-item').filter({ hasText: 'market.docx' })).toHaveCount(
        1,
      );
      const messageTexts = await page.locator('.chat-message-list .message-text').allInnerTexts();
      const headingCount = messageTexts.reduce(
        (total, text) => total + (text.match(/市场规模与增长/g)?.length ?? 0),
        0,
      );
      expect(headingCount, `重试后完整正文只应出现一次：${JSON.stringify(messageTexts)}`).toBe(1);

      // 服务端消息快照：失败消息与重试成功消息是两条独立记录，失败内容为空。
      const messages = await readConversationMessages(page, conversationId);
      const assistants = messages.filter((message) => message.role === 'ASSISTANT');
      expect(assistants.map((message) => message.status)).toEqual(['FAILED', 'COMPLETED']);
      expect(assistants[0]!.errorCode).toBe('RICCON-MODEL-503');
      expect(assistants[0]!.content).toBe('');
      expect(assistants[1]!.content).toContain(REPORT_TAIL);
      expect(assistants[1]!.content.match(/市场规模与增长/g) ?? []).toHaveLength(1);
      await expectNoProviderModelKeyLeak(page);
    });
  });
});
