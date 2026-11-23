import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from './fixtures';
import { expectNoSeriousA11yViolations } from './a11y';
import { loginAs, M03_CREDENTIALS } from './session';

/** M05 合成文档 ID；与 `e2e/stub/server.mjs` 的 M05 seed 对齐。 */
const DOC = {
  excelMulti: 'b5b5b5b5-b5b5-45b5-85b5-000000000001',
  excelTruncated: 'b5b5b5b5-b5b5-45b5-85b5-000000000002',
  excelCorrupt: 'b5b5b5b5-b5b5-45b5-85b5-000000000003',
  ocrOk: 'b5b5b5b5-b5b5-45b5-85b5-000000000004',
  ocrLow: 'b5b5b5b5-b5b5-45b5-85b5-000000000005',
  ocrPartial: 'b5b5b5b5-b5b5-45b5-85b5-000000000006',
  ocrFailed: 'b5b5b5b5-b5b5-45b5-85b5-000000000007',
  ocrDisabled: 'b5b5b5b5-b5b5-45b5-85b5-000000000008',
  aclUsers: 'b5b5b5b5-b5b5-45b5-85b5-000000000009',
  aclDept: 'b5b5b5b5-b5b5-45b5-85b5-00000000000a',
  aclCreator: 'b5b5b5b5-b5b5-45b5-85b5-00000000000b',
  tenant: 'b5b5b5b5-b5b5-45b5-85b5-00000000000c',
  expired: 'b5b5b5b5-b5b5-45b5-85b5-00000000000d',
  noSource: 'b5b5b5b5-b5b5-45b5-85b5-00000000000e',
} as const;

/** 断言当前页面无横向滚动。 */
async function expectNoHorizontalScroll(page: Page): Promise<void> {
  const metrics = await page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }));
  expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.clientWidth);
}

/** 读取 CSRF 令牌；与浏览器共享 Cookie 会话。 */
async function csrfToken(page: Page): Promise<string> {
  const response = await page.request.get('/api/v1/auth/csrf');
  expect(response.ok(), '读取 CSRF 失败').toBe(true);
  return ((await response.json()) as { token: string }).token;
}

/** 通过浏览器同源请求调用知识库 API；用于验证直接调用时的 403/404 与幂等。 */
async function knowledgeRequest(
  page: Page,
  method: 'GET' | 'POST' | 'PUT' | 'DELETE',
  path: string,
  data?: unknown,
): Promise<{ status: number; text: string }> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (method !== 'GET') {
    headers['X-CSRF-TOKEN'] = await csrfToken(page);
    headers['Content-Type'] = 'application/json';
  }
  const response = await page.request.fetch(path, { method, headers, data });
  return { status: response.status(), text: await response.text() };
}

/** 打开知识库抽屉并等待列表加载。 */
async function openKnowledge(page: Page): Promise<ReturnType<Page['getByTestId']>> {
  await page.getByTestId('knowledge-entry').click();
  const drawer = page.getByTestId('knowledge-drawer');
  await expect(drawer).toBeVisible();
  await expect(drawer.getByText('按文档可见范围过滤')).toBeVisible();
  return drawer;
}

/** 在知识库列表中打开指定文档详情。 */
async function openDocument(drawer: ReturnType<Page['getByTestId']>, title: string): Promise<void> {
  await drawer.getByRole('button', { name: `查看文档：${title}` }).click();
  await expect(drawer.getByTestId('knowledge-document-title')).toHaveText(title);
}

/** 断言知识库列表包含/不包含指定标题。 */
async function expectDocumentRow(
  drawer: ReturnType<Page['getByTestId']>,
  title: string,
  visible = true,
): Promise<void> {
  const row = drawer.getByRole('row').filter({ hasText: title });
  if (visible) {
    await expect(row).toBeVisible();
  } else {
    await expect(row).toHaveCount(0);
  }
}

/** 在聊天中新建会话并发送问题。 */
async function sendQuestion(page: Page, question: string): Promise<void> {
  await page.getByRole('button', { name: '新建会话' }).click();
  await expect(page.getByText('开始 J1 问答')).toBeVisible();
  const composer = page.getByPlaceholder('输入问题，Enter 发送，Shift+Enter 换行');
  await composer.fill(question);
  await composer.press('Enter');
}

/** 等待 M05 引用按钮出现并打开引用详情。 */
async function openCitation(page: Page, name: RegExp): Promise<ReturnType<Page['getByRole']>> {
  const citation = page.getByRole('button', { name });
  await expect(citation).toBeVisible({ timeout: 15_000 });
  await citation.click();
  const drawer = page.getByRole('dialog').filter({ hasText: '引用详情' });
  await expect(drawer.getByText('引用详情')).toBeVisible();
  return drawer;
}

/** 切换到知识库列表下一页；用于分页后仍能验证 ACL 可见性。 */
async function goToNextPage(drawer: ReturnType<Page['getByTestId']>): Promise<void> {
  await drawer.locator('.ant-pagination-next button').click();
  await expect(drawer.locator('.ant-pagination-item-active')).toContainText('2');
}

/**
 * M05 知识库纵深 E2E（M05-AC-01..09、15/16/21 M05 段、17/18/19）。
 *
 * 覆盖 Excel 结构、OCR 四态、文档级 ACL 四处一致、下载代理、删除/重索引、429 文案、
 * 保留策略与 375px/键盘/axe。后端 ACL/检索/审计的真实语义由 M05 集成测试覆盖；
 * 本文件验证用户可见结果与浏览器路径。
 */
test.describe('M05 知识库纵深', () => {
  test('E2E-M05-01 Excel 多表/空表/隐藏表/截断/失败与格式拒绝', async ({ page, stub }) => {
    await stub.seedM05();
    await loginAs(page, M03_CREDENTIALS.tenantAdmin, '王芳');
    const drawer = await openKnowledge(page);

    // 多工作表、空表与隐藏表：全部可见工作表名、范围、空表标记与隐藏提示。
    await openDocument(drawer, '销售数据.xlsx');
    const detail = drawer.getByTestId('knowledge-detail');
    await expect(detail.getByText('销售数据', { exact: true })).toBeVisible();
    await expect(detail.getByText('范围：A1:F20')).toBeVisible();
    await expect(detail.getByText('汇总', { exact: true })).toBeVisible();
    await expect(detail.getByText('范围：A1:D8')).toBeVisible();
    await expect(detail.getByText('空表（空工作表）')).toBeVisible();
    await expect(detail.getByText('空工作表', { exact: true })).toBeVisible();
    await expect(detail.getByText('隐藏工作表：已跳过 1 个，不索引其内容。')).toBeVisible();
    await expect(detail.getByText('已跳过 1 个隐藏工作表。')).toBeVisible();
    await expect(detail.getByText('识别成功', { exact: true }).first()).toBeVisible();

    // 超过单表索引上限：显示已索引与未索引范围。
    await openDocument(drawer, '明细大表.xlsx');
    await expect(detail.getByText('范围：A1:Z200000')).toBeVisible();
    await expect(detail.getByText('已索引：A1:Z50000')).toBeVisible();
    await expect(detail.getByText('附件', { exact: true })).toBeVisible();
    await expect(detail.getByText('未索引', { exact: true })).toBeVisible();
    await expect(detail.getByText('工作表“明细”行数超过上限，已截断。')).toBeVisible();

    // 损坏 XLSX：稳定中文原因与重试入口，不出现堆栈。
    await openDocument(drawer, '损坏数据.xlsx');
    await expect(detail.getByText('解析失败', { exact: true }).first()).toBeVisible();
    await expect(detail.getByText(/文件损坏或格式不合法/)).toBeVisible();
    await expect(detail.getByTestId('knowledge-retry')).toBeVisible();
    await expect(detail.getByText(/java\.util\.zip/)).toHaveCount(0);

    // 旧版 .xls 在上传入口被拒绝，显示中文原因且不创建文件行。
    await page.keyboard.press('Escape');
    await expect(drawer).toBeHidden();
    await page.getByRole('button', { name: '新建会话' }).click();
    await expect(page.getByText('开始 J1 问答')).toBeVisible();
    const chooserPromise = page.waitForEvent('filechooser');
    await page.getByRole('button', { name: /上传文件/ }).click();
    const chooser = await chooserPromise;
    await chooser.setFiles({
      name: '旧版数据.xls',
      mimeType: 'application/vnd.ms-excel',
      buffer: Buffer.from('riccon stub legacy xls'),
    });
    await expect(page.getByText(/“旧版数据\.xls”格式不受支持/)).toBeVisible();
    await expect(page.getByText(/仅支持 PDF、DOCX、XLSX、PNG、JPEG/)).toBeVisible();
    await expect(page.locator('.chat-file-item').filter({ hasText: '旧版数据.xls' })).toHaveCount(
      0,
    );

    // Excel 引用详情：工作表 + 区域 + 命中文本块；不要求打开完整表格编辑器。
    await stub.setScenario('m05-citation-excel');
    const composer = page.getByPlaceholder('输入问题，Enter 发送，Shift+Enter 换行');
    await composer.fill('请汇总销售数据里的华东区业绩');
    await composer.press('Enter');
    const excelCitation = await openCitation(page, /销售数据\.xlsx/);
    await expect(excelCitation.getByTestId('citation-locator')).toContainText('销售数据');
    await expect(excelCitation.getByTestId('citation-locator')).toContainText('B2:D10');
    await expect(excelCitation.getByTestId('citation-region')).toHaveText('销售数据!B2:D10');
    await expect(excelCitation.getByTestId('citation-snippet')).toContainText(
      '华东区季度销售额 128 万元',
    );
  });

  test('E2E-M05-02 OCR 四态、低置信、OCR 关闭与重试', async ({ page, stub }) => {
    await stub.seedM05();
    await loginAs(page, M03_CREDENTIALS.tenantAdmin, '王芳');
    const drawer = await openKnowledge(page);

    // 列表状态：成功、低置信、部分成功；失败文档显示“解析失败”。
    const successRow = drawer.getByRole('row').filter({ hasText: '扫描合同.png' });
    await expect(successRow.getByText('识别成功', { exact: true })).toBeVisible();
    const lowRow = drawer.getByRole('row').filter({ hasText: '模糊票据.jpg' });
    await expect(lowRow.getByText('识别成功（置信度低，请核对原文）')).toBeVisible();
    const partialRow = drawer.getByRole('row').filter({ hasText: '多页扫描.pdf' });
    await expect(partialRow.getByText('部分成功', { exact: true })).toBeVisible();
    const failedRow = drawer.getByRole('row').filter({ hasText: '全失败扫描.pdf' });
    await expect(failedRow.getByText('解析失败', { exact: true })).toBeVisible();

    // 低置信详情：保留可检索提示与平均置信度，不误显示为失败。
    await openDocument(drawer, '模糊票据.jpg');
    const detail = drawer.getByTestId('knowledge-detail');
    await expect(
      detail.getByText('识别成功（置信度低，请核对原文）', { exact: true }).first(),
    ).toBeVisible();
    await expect(detail.getByText(/平均置信度 52%/).first()).toBeVisible();
    await expect(detail.getByText(/引用会携带同样的低置信提示/).first()).toBeVisible();
    await expect(detail.getByTestId('knowledge-retry')).toHaveCount(0);

    // 部分失败：列出失败页并按缺失页 reindex(force=false) 重试。
    await openDocument(drawer, '多页扫描.pdf');
    await expect(detail.getByText('部分成功', { exact: true }).first()).toBeVisible();
    await expect(detail.getByTestId('knowledge-parse-warning')).toContainText('失败页：2');
    const reindexRequest = page.waitForRequest(
      (request) =>
        request.method() === 'POST' &&
        request.url().includes(`/documents/${DOC.ocrPartial}/reindex`),
    );
    await detail.getByTestId('knowledge-retry').click();
    const partialRequest = await reindexRequest;
    expect(partialRequest.url()).not.toContain('force=true');
    // 重索引受理后详情切换为“解析中”，证明状态更新且不需要等待整页刷新。
    await expect(detail.getByText('解析中', { exact: true }).first()).toBeVisible();

    // 全部失败：稳定原因与重试入口；重试走 force=true。
    await openDocument(drawer, '全失败扫描.pdf');
    await expect(detail.getByText('识别失败', { exact: true }).first()).toBeVisible();
    await expect(detail.getByText(/OCR 识别失败/).first()).toBeVisible();
    const forceRequest = page.waitForRequest(
      (request) =>
        request.method() === 'POST' &&
        request.url().includes(`/documents/${DOC.ocrFailed}/reindex`) &&
        request.url().includes('force=true'),
    );
    await detail.getByTestId('knowledge-retry').click();
    await forceRequest;
    await expect(detail.getByText('解析中', { exact: true }).first()).toBeVisible();

    // OCR 关闭：识别未启用、原图与文件名保留提示，不产生伪引用错误。
    await openDocument(drawer, '图片-OCR关闭.png');
    await expect(detail.getByText('识别未启用', { exact: true }).first()).toBeVisible();
    await expect(detail.getByText(/OCR 未启用，图片仅保留原图与文件名/)).toBeVisible();
    await expect(detail.getByText(/请联系管理员启用后重试/)).toBeVisible();

    // OCR 引用定位：页码、bbox 与文本块可见；低置信引用带同样提示。
    await stub.setScenario('m05-citation-ocr');
    await page.keyboard.press('Escape');
    await expect(drawer).toBeHidden();
    await sendQuestion(page, '请提取合同验收条款');
    const citationDrawer = await openCitation(page, /扫描合同\.png/);
    await expect(citationDrawer.getByText('第 2 页', { exact: true })).toBeVisible();
    await expect(citationDrawer.getByTestId('citation-bbox')).toHaveText(
      'x=120, y=340, 宽=480, 高=200（像素）',
    );
    await expect(citationDrawer.getByTestId('citation-snippet')).toContainText(
      '甲方应在 2026 年 12 月 31 日前完成验收。',
    );
    await page.keyboard.press('Escape');

    await stub.setScenario('m05-citation-ocr-low');
    await sendQuestion(page, '这张票据金额是多少');
    const lowCitation = await openCitation(page, /模糊票据\.jpg/);
    await expect(lowCitation.getByText('置信度低', { exact: true }).first()).toBeVisible();
    await expect(lowCitation.getByTestId('citation-confidence')).toHaveText('识别置信度 52%');
    await expect(lowCitation.getByTestId('citation-low-confidence')).toContainText('请核对原文');
  });

  test('E2E-M05-03 ACL 四处一致、创建人例外、403/404 与旧引用遮蔽', async ({ page, stub }) => {
    await stub.seedM05();
    await loginAs(page, M03_CREDENTIALS.tenantAdmin, '王芳');
    const drawer = await openKnowledge(page);

    // 管理员列表可见全量；打开 USERS 文档并看到名单摘要。
    await expectDocumentRow(drawer, '销售制度.docx');
    await openDocument(drawer, '销售制度.docx');
    const detail = drawer.getByTestId('knowledge-detail');
    const aclSection = detail.getByTestId('knowledge-acl-section');
    await expect(aclSection.getByText('指定用户', { exact: true }).first()).toBeVisible();
    await expect(aclSection.getByText('王敏')).toBeVisible();
    await expect(detail.getByTestId('knowledge-acl-edit')).toBeVisible();

    // 修改为“指定部门：销售部”，保存后下一次请求生效。
    await detail.getByTestId('knowledge-acl-edit').click();
    await detail.getByRole('radio', { name: '指定部门' }).check();
    await detail.getByRole('combobox', { name: '可见部门' }).click();
    await page
      .locator('.ant-select-dropdown:visible .ant-select-item-option')
      .filter({ hasText: '销售部' })
      .click();
    const aclRequest = page.waitForRequest(
      (request) =>
        request.method() === 'PUT' && request.url().includes(`/documents/${DOC.aclUsers}/acl`),
    );
    await detail.getByTestId('knowledge-acl-save').click();
    const savedRequest = await aclRequest;
    expect(savedRequest.postDataJSON()).toEqual({
      visibility: 'DEPT',
      departmentId: 'dept-sales',
    });
    // 保存后详情与列表都按新范围刷新；成功提示可能被列表刷新立即清空，故断言持久结果。
    await expect(detail.getByText('指定部门：销售部').first()).toBeVisible();
    await expect(
      drawer.getByRole('row').filter({ hasText: '销售制度.docx' }).getByText('指定部门：销售部'),
    ).toBeVisible();

    // 管理性读取（U8）写审计：管理员列表与详情对非自然可见文档产生 MANAGEMENT_READ。
    const audit = await stub.audit();
    expect(
      audit.some(
        (entry) =>
          entry.action === 'knowledge.document.admin_read' && entry.reason === 'MANAGEMENT_READ',
      ),
    ).toBe(true);
    expect(
      audit.some((entry) => entry.action === 'knowledge.acl.changed' && entry.reason === 'ACL'),
    ).toBe(true);

    // 成员王敏（销售部）：列表、详情、下载均成功；直接 PUT ACL 返回 403。
    await page.context().clearCookies();
    await loginAs(page, M03_CREDENTIALS.member, '王敏');
    const memberDrawer = await openKnowledge(page);
    await expectDocumentRow(memberDrawer, '销售制度.docx');
    await openDocument(memberDrawer, '销售制度.docx');
    const downloadRequest = page.waitForRequest(
      (request) =>
        request.method() === 'GET' &&
        request.url().includes(`/api/v1/knowledge/documents/${DOC.aclUsers}/download`),
    );
    const downloadPromise = page.waitForEvent('download');
    await memberDrawer.getByTestId('knowledge-download').click();
    const download = await downloadPromise;
    const sourceRequest = await downloadRequest;
    expect(download.suggestedFilename()).toBe('销售制度.docx');
    expect(sourceRequest.url()).not.toContain('/objects/');
    expect(sourceRequest.url()).not.toContain('X-Amz');
    expect(sourceRequest.headers()['authorization']).toBeUndefined();

    const memberPut = await knowledgeRequest(
      page,
      'PUT',
      `/api/v1/knowledge/documents/${DOC.aclUsers}/acl`,
      { visibility: 'TENANT' },
    );
    expect(memberPut.status).toBe(403);
    expect(memberPut.text).not.toContain('销售制度');

    // 市场部成员赵敏：列表不含该文档；直接访问详情/下载统一 404 且不泄露标题。
    await page.context().clearCookies();
    await loginAs(page, M03_CREDENTIALS.outsider, '赵敏');
    const outsiderDrawer = await openKnowledge(page);
    await expectDocumentRow(outsiderDrawer, '销售制度.docx', false);
    await expectDocumentRow(outsiderDrawer, '部门手册.pdf', false);
    const outsiderDetail = await knowledgeRequest(
      page,
      'GET',
      `/api/v1/knowledge/documents/${DOC.aclUsers}`,
    );
    expect(outsiderDetail.status).toBe(404);
    expect(outsiderDetail.text).not.toContain('销售制度');
    const outsiderDownload = await knowledgeRequest(
      page,
      'GET',
      `/api/v1/knowledge/documents/${DOC.aclUsers}/download`,
    );
    expect(outsiderDownload.status).toBe(404);
    expect(outsiderDownload.text).not.toContain('销售制度');

    // 平台管理员：无知识库入口；直接访问租户文档详情/ACL 统一 404，不泄露标题。
    await page.context().clearCookies();
    await loginAs(page, M03_CREDENTIALS.platformAdmin, '赵蕾');
    await expect(page.getByTestId('knowledge-entry')).toHaveCount(0);
    const platformDetail = await knowledgeRequest(
      page,
      'GET',
      `/api/v1/knowledge/documents/${DOC.aclUsers}`,
    );
    expect(platformDetail.status).toBe(404);
    expect(platformDetail.text).not.toContain('销售制度');
    const platformAcl = await knowledgeRequest(
      page,
      'GET',
      `/api/v1/knowledge/documents/${DOC.aclUsers}/acl`,
    );
    expect(platformAcl.status).toBe(404);
    expect(platformAcl.text).not.toContain('销售制度');

    // 创建人李强不在 USERS 名单：仍可见并可下载。
    await page.context().clearCookies();
    await loginAs(page, M03_CREDENTIALS.manager, '李强');
    const creatorDrawer = await openKnowledge(page);
    await goToNextPage(creatorDrawer);
    await expectDocumentRow(creatorDrawer, '李强的方案.docx');
    await openDocument(creatorDrawer, '李强的方案.docx');
    const creatorDownloadPromise = page.waitForEvent('download');
    await creatorDrawer.getByTestId('knowledge-download').click();
    const creatorDownload = await creatorDownloadPromise;
    expect(creatorDownload.suggestedFilename()).toBe('李强的方案.docx');

    await page.keyboard.press('Escape');
    await expect(creatorDrawer).toBeHidden();

    // ACL 收紧后的旧引用：只显示“该引用不可访问”，不展示标题、片段或定位。
    await stub.setScenario('m05-citation-no-access');
    await sendQuestion(page, '销售制度里如何申请采购');
    const masked = await openCitation(page, /该引用不可访问/);
    await expect(masked.getByTestId('citation-masked')).toBeVisible();
    await expect(masked.getByTestId('citation-title')).toHaveCount(0);
    await expect(masked.getByTestId('citation-snippet')).toHaveCount(0);
    await expect(masked.getByTestId('citation-locator')).toHaveCount(0);
    await expect(masked.getByText('销售制度.docx')).toHaveCount(0);
    await expect(masked.getByText(/访问范围已变更/)).toBeVisible();
  });

  test('E2E-M05-05 删除/重索引幂等、审计动作与 409/503', async ({ page, stub, consoleGuard }) => {
    await stub.seedM05();
    await loginAs(page, M03_CREDENTIALS.tenantAdmin, '王芳');
    const drawer = await openKnowledge(page);

    // 删除：软删除成功后列表不再显示；重复 DELETE 幂等 204；审计记录 reason=USER。
    await goToNextPage(drawer);
    await openDocument(drawer, '全员公告.pdf');
    const detail = drawer.getByTestId('knowledge-detail');
    await detail.getByTestId('knowledge-delete').click();
    await page.getByRole('button', { name: '确认删除' }).click();
    await expect(detail).toHaveCount(0);
    await expectDocumentRow(drawer, '全员公告.pdf', false);
    const repeatDelete = await knowledgeRequest(
      page,
      'DELETE',
      `/api/v1/knowledge/documents/${DOC.tenant}`,
    );
    expect(repeatDelete.status).toBe(204);
    const auditAfterDelete = await stub.audit();
    expect(
      auditAfterDelete.some(
        (entry) =>
          entry.action === 'knowledge.document.deleted' &&
          entry.reason === 'USER' &&
          entry.targetId === DOC.tenant,
      ),
    ).toBe(true);

    // 普通成员对可见文档删除/重索引 403；不可见文档统一 404。
    await page.context().clearCookies();
    await loginAs(page, M03_CREDENTIALS.member, '王敏');
    const memberDelete = await knowledgeRequest(
      page,
      'DELETE',
      `/api/v1/knowledge/documents/${DOC.excelMulti}`,
    );
    expect(memberDelete.status).toBe(403);
    const memberReindex = await knowledgeRequest(
      page,
      'POST',
      `/api/v1/knowledge/documents/${DOC.excelMulti}/reindex`,
    );
    expect(memberReindex.status).toBe(403);
    const invisibleDelete = await knowledgeRequest(
      page,
      'DELETE',
      `/api/v1/knowledge/documents/${DOC.expired}`,
    );
    expect(invisibleDelete.status).toBe(404);

    // 409/503：可读错误，不出现堆栈或英文内部错误。
    await page.context().clearCookies();
    await loginAs(page, M03_CREDENTIALS.tenantAdmin, '王芳');
    consoleGuard.allowNetworkError(/\/api\/v1\/knowledge\/documents\/[^/]+\/reindex(\?.*)?$/, 409);
    consoleGuard.allowNetworkError(/\/api\/v1\/knowledge\/documents\/[^/]+\/reindex(\?.*)?$/, 503);
    const errorDrawer = await openKnowledge(page);
    await openDocument(errorDrawer, '损坏数据.xlsx');
    const errorDetail = errorDrawer.getByTestId('knowledge-detail');
    await stub.setScenario('m05-reindex-409');
    await errorDetail.getByTestId('knowledge-retry').click();
    await expect(errorDetail.getByTestId('knowledge-action-error')).toContainText(
      '文档状态已变化，请刷新后重试。',
    );
    await stub.setScenario('m05-reindex-503');
    await errorDetail.getByTestId('knowledge-retry').click();
    await expect(errorDetail.getByTestId('knowledge-action-error')).toContainText(
      '索引服务暂不可用，请稍后重试。',
    );
    await expect(errorDetail.getByText(/java\.|Exception|at com\./)).toHaveCount(0);
  });

  test('E2E-M05-06 429 聊天/报告入口显示限额类型、绝对与相对重置时间', async ({
    page,
    stub,
    consoleGuard,
  }) => {
    await loginAs(page, M03_CREDENTIALS.member, '王敏');
    await stub.setScenario('m05-quota-429');
    consoleGuard.allowNetworkError(/\/api\/v1\/conversations\/[^/]+\/messages$/, 429);
    consoleGuard.allowNetworkError(/\/api\/v1\/workspaces\/[^/]+\/research-tasks$/, 429);

    // 聊天入口：透传日限额、+08:00 绝对时间与“还有 X 小时”，不再显示通用文案。
    await sendQuestion(page, '请总结今天的销售进展');
    const quotaAlert = page.getByTestId('chat-quota-429');
    await expect(quotaAlert).toContainText('已达日限额');
    await expect(quotaAlert).toContainText(/还有 [23] 小时/);
    await expect(quotaAlert).toContainText(/重置时间 .*\+08:00/);
    await expect(quotaAlert).toContainText('请等待配额重置，或联系管理员调整限额');
    await expect(quotaAlert).not.toContainText('请求过于频繁，请稍后重试。');
    await expect(quotaAlert).not.toContainText(/tenantId|used|limit/);

    // 报告入口：同一错误文案一致。
    await page.getByRole('button', { name: '市场调研报告' }).click();
    await expect(page).toHaveURL(/\/research\/tasks$/);
    await page
      .getByPlaceholder('例如：2026 年中国企业协同办公市场规模与竞争格局')
      .fill('2026 年协同办公市场配额验证');
    await page.getByRole('combobox', { name: '数据工具' }).click();
    await page.getByText('market_data_lookup · 只读 · 需审批').click();
    await page.getByRole('button', { name: /创建\s*报告任务/ }).click();
    const researchError = page.getByTestId('research-action-error');
    await expect(researchError).toContainText('已达日限额');
    await expect(researchError).toContainText(/还有 [23] 小时/);
    await expect(researchError).toContainText(/\+08:00/);
    await expect(researchError).not.toContainText('请求过于频繁，请稍后重试。');
  });

  test('E2E-M05-07 375px 与键盘路径（打开文档 → 修改可见范围 → 保存；打开引用 → 查看文本块/坐标）', async ({
    page,
    stub,
  }) => {
    await stub.seedM05();
    await page.setViewportSize({ width: 375, height: 812 });
    await loginAs(page, M03_CREDENTIALS.tenantAdmin, '王芳');
    await expectNoHorizontalScroll(page);

    // 键盘打开知识库抽屉。
    const entry = page.getByTestId('knowledge-entry');
    await entry.focus();
    await page.keyboard.press('Enter');
    const drawer = page.getByTestId('knowledge-drawer');
    await expect(drawer).toBeVisible();
    await expectNoHorizontalScroll(page);

    // 键盘打开文档并进入 ACL 编辑。
    const openButton = drawer.getByRole('button', { name: '查看文档：销售制度.docx' });
    await openButton.focus();
    await page.keyboard.press('Enter');
    const detail = drawer.getByTestId('knowledge-detail');
    await expect(detail.getByTestId('knowledge-document-title')).toHaveText('销售制度.docx');

    const edit = detail.getByTestId('knowledge-acl-edit');
    await edit.focus();
    await page.keyboard.press('Enter');
    const deptRadio = detail.getByRole('radio', { name: '指定部门' });
    await deptRadio.focus();
    await page.keyboard.press('Space');
    await expect(deptRadio).toBeChecked();
    const department = detail.getByRole('combobox', { name: '可见部门' });
    await department.focus();
    await page.keyboard.press('Enter');
    await page.keyboard.type('销售部');
    await page.keyboard.press('Enter');
    const save = detail.getByTestId('knowledge-acl-save');
    await save.focus();
    await page.keyboard.press('Enter');
    // 成功提示会被列表刷新清空；断言持久结果：详情与列表都显示“指定部门：销售部”。
    await expect(detail.getByText('指定部门：销售部').first()).toBeVisible();
    await expectNoHorizontalScroll(page);

    // 键盘打开引用并查看文本块/坐标。
    await page.keyboard.press('Escape');
    await expect(drawer).toBeHidden();
    await stub.setScenario('m05-citation-ocr');
    await sendQuestion(page, '请提取合同验收条款');
    const citation = page.getByRole('button', { name: /扫描合同\.png/ });
    await expect(citation).toBeVisible({ timeout: 15_000 });
    await citation.focus();
    await page.keyboard.press('Enter');
    const citationDrawer = page.getByRole('dialog').filter({ hasText: '引用详情' });
    await expect(citationDrawer.getByTestId('citation-snippet')).toBeVisible();
    await expect(citationDrawer.getByTestId('citation-bbox')).toBeVisible();
    await expectNoHorizontalScroll(page);
  });

  test('E2E-M05-07b 知识库列表 axe 无 serious/critical @a11y', async ({ page, stub }) => {
    await stub.seedM05();
    await page.setViewportSize({ width: 375, height: 812 });
    await loginAs(page, M03_CREDENTIALS.tenantAdmin, '王芳');
    const drawer = await openKnowledge(page);
    await expectDocumentRow(drawer, '销售数据.xlsx');
    await page.mouse.move(0, 0);
    await expectNoSeriousA11yViolations(page);
  });

  test('E2E-M05-07c 文档详情/ACL 设置 axe 无 serious/critical @a11y', async ({ page, stub }) => {
    await stub.seedM05();
    await page.setViewportSize({ width: 375, height: 812 });
    await loginAs(page, M03_CREDENTIALS.tenantAdmin, '王芳');
    const drawer = await openKnowledge(page);
    await openDocument(drawer, '销售制度.docx');
    await page.mouse.move(0, 0);
    await expectNoSeriousA11yViolations(page);
  });

  test('E2E-M05-07d 引用详情 axe 无 serious/critical @a11y', async ({ page, stub }) => {
    await stub.seedM05();
    await loginAs(page, M03_CREDENTIALS.member, '王敏');
    await stub.setScenario('m05-citation-ocr');
    await sendQuestion(page, '请提取合同验收条款');
    const citationDrawer = await openCitation(page, /扫描合同\.png/);
    await expect(citationDrawer.getByTestId('citation-bbox')).toBeVisible();
    await page.mouse.move(0, 0);
    await expectNoSeriousA11yViolations(page);
  });

  test('E2E-M05-08 保留策略、已到期不可见与已删除引用', async ({ page, stub }) => {
    await stub.seedM05();
    await loginAs(page, M03_CREDENTIALS.member, '王敏');
    const drawer = await openKnowledge(page);

    // 保留策略：默认 180 天、到期时间与策略来源可见。
    await openDocument(drawer, '扫描合同.png');
    const detail = drawer.getByTestId('knowledge-detail');
    await expect(detail.getByText(/保留 180 天/)).toBeVisible();
    await expect(detail.getByText(/到期 2027-05-22/)).toBeVisible();
    await expect(detail.getByText(/部署默认/)).toBeVisible();

    // 已到期文档不在列表。
    await expectDocumentRow(drawer, '过期合同.pdf', false);

    // 打开已删除/已到期文档的旧引用：显示“源文档已删除”，不展示正文、片段或定位。
    await stub.setScenario('m05-citation-deleted');
    await page.keyboard.press('Escape');
    await expect(drawer).toBeHidden();
    await sendQuestion(page, '过期合同里的违约责任是什么');
    const deleted = await openCitation(page, /源文档已删除/);
    await expect(deleted.getByTestId('citation-masked')).toBeVisible();
    await expect(deleted.getByTestId('citation-title')).toHaveCount(0);
    await expect(deleted.getByTestId('citation-snippet')).toHaveCount(0);
    await expect(deleted.getByText(/源文档已删除或已到期/)).toBeVisible();
    await page.keyboard.press('Escape');

    // 文档无源文件：引用片段与定位仍可见，并明确提示没有可下载源文件。
    await stub.setScenario('m05-citation-no-source');
    await sendQuestion(page, '行业协同办公渗透率如何');
    const noSource = await openCitation(page, /种子语料/);
    await expect(noSource.getByTestId('citation-no-source')).toContainText(
      '该文档没有可下载的源文件',
    );
    await expect(noSource.getByTestId('citation-snippet')).toContainText(
      '企业协同办公渗透率持续提升',
    );
    await expect(noSource.getByText('第 3 段', { exact: true })).toBeVisible();
    await expect(noSource.getByTestId('citation-download')).toHaveCount(0);
  });

  test('E2E-M05-09 失败时间与保留策略不追溯明示', async ({ page, stub }) => {
    await stub.seedM05();
    await loginAs(page, M03_CREDENTIALS.tenantAdmin, '王芳');
    const drawer = await openKnowledge(page);

    // FAILED 文档显示固定失败时间（+08:00）与重试入口；不出现堆栈。
    await openDocument(drawer, '损坏数据.xlsx');
    const failedDetail = drawer.getByTestId('knowledge-detail');
    await expect(failedDetail.getByTestId('knowledge-failed-at')).toContainText(
      '失败时间：2026-11-23 18:30:00 +08:00',
    );
    await expect(failedDetail.getByTestId('knowledge-retry')).toBeVisible();
    await expect(failedDetail.getByText(/java\.|Exception|at com\./)).toHaveCount(0);

    // 详情始终明示保留策略不追溯；非 FAILED 文档不显示失败时间。
    await expect(failedDetail.getByTestId('knowledge-retention-note')).toContainText(
      '保留策略变更仅影响新文档和新的到期计算，不追溯历史文档',
    );
    await openDocument(drawer, '销售数据.xlsx');
    await expect(
      drawer.getByTestId('knowledge-detail').getByTestId('knowledge-failed-at'),
    ).toHaveCount(0);
    await expect(drawer.getByTestId('knowledge-retention-note')).toContainText(
      '默认 180 天，租户策略可覆盖',
    );
  });

  test('E2E-M05-03b 新文档登记、空/错误态与 ACL 表单影响提示', async ({
    page,
    stub,
    consoleGuard,
  }) => {
    await stub.seedM05();
    await loginAs(page, M03_CREDENTIALS.tenantAdmin, '王芳');
    consoleGuard.allowNetworkError(/\/api\/v1\/knowledge\/documents$/, 403);
    consoleGuard.allowNetworkError(/\/api\/v1\/knowledge\/documents$/, 404);
    consoleGuard.allowNetworkError(/\/api\/v1\/knowledge\/documents$/, 409);
    consoleGuard.allowNetworkError(/\/api\/v1\/knowledge\/documents\?/, 500);

    // 先上传一个工作空间文件，作为登记来源。
    await page.getByRole('button', { name: '新建会话' }).click();
    await expect(page.getByText('开始 J1 问答')).toBeVisible();
    const uploadResponsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        /\/api\/v1\/workspaces\/[^/]+\/files$/.test(response.url()),
    );
    const chooserPromise = page.waitForEvent('filechooser');
    await page.getByRole('button', { name: /上传文件/ }).click();
    const chooser = await chooserPromise;
    await chooser.setFiles({
      name: '登记测试文件.pdf',
      mimeType: 'application/pdf',
      buffer: Buffer.from('%PDF-1.4\n% M05 create stub file\n'),
    });
    const uploadedFile = (await (await uploadResponsePromise).json()) as { id: string };
    const fileRow = page.locator('.chat-file-item').filter({ hasText: '登记测试文件.pdf' });
    await expect(fileRow.getByText('解析成功', { exact: true })).toBeVisible({
      timeout: 15_000,
    });

    // 空态与历史文档提示：筛选无结果时给出清空筛选的下一步。
    const drawer = await openKnowledge(page);
    await expect(drawer.getByTestId('knowledge-legacy-notice')).toContainText(
      '历史文档默认全租户可见，可收紧',
    );
    await expect(drawer.getByTestId('knowledge-create-open')).toBeVisible();
    await drawer.getByRole('combobox', { name: '筛选文档状态' }).click();
    await page
      .locator('.ant-select-dropdown:visible .ant-select-item-option')
      .filter({ hasText: '排队中' })
      .click();
    await expect(drawer.getByTestId('knowledge-empty')).toContainText('当前筛选条件下没有文档');
    await drawer.getByRole('button', { name: '清空筛选' }).click();
    await expectDocumentRow(drawer, '销售数据.xlsx');

    // ACL 表单影响提示与创建人不可移除说明。
    await openDocument(drawer, '销售制度.docx');
    const detail = drawer.getByTestId('knowledge-detail');
    await detail.getByTestId('knowledge-acl-edit').click();
    await expect(
      detail.getByText('保存后，下一次列表、检索、引用与知识库下载请求立即按新范围判定'),
    ).toBeVisible();
    await expect(
      detail.getByText('创建人始终可见，无需列入；未选择用户时仅创建人可见。'),
    ).toBeVisible();

    // 新文档登记：默认 TENANT、未确认不能保存、保存后列表出现。
    const modal = page.locator('.ant-modal').filter({ hasText: '登记知识库文档' });
    await drawer.getByTestId('knowledge-create-open').click();
    await expect(modal).toBeVisible();
    await expect(modal.getByRole('radio', { name: '租户全员' })).toBeChecked();
    await modal.getByTestId('knowledge-create-submit').click();
    await expect(modal.getByText('请选择工作空间中的文件。')).toBeVisible();
    await modal.getByRole('combobox', { name: '源文件' }).click();
    await page
      .locator('.ant-select-dropdown:visible .ant-select-item-option')
      .filter({ hasText: '登记测试文件.pdf' })
      .click();
    await expect(modal.getByRole('textbox', { name: '文档标题' })).toHaveValue('登记测试文件');
    await modal.getByTestId('knowledge-create-submit').click();
    await expect(modal.getByText('请先确认可见范围影响。')).toBeVisible();

    await modal.getByTestId('knowledge-create-confirm').check();
    const createRequestPromise = page.waitForRequest(
      (request) =>
        request.method() === 'POST' && request.url().endsWith('/api/v1/knowledge/documents'),
    );
    await modal.getByTestId('knowledge-create-submit').click();
    const createRequest = await createRequestPromise;
    expect(createRequest.postDataJSON()).toEqual({
      title: '登记测试文件',
      fileId: uploadedFile.id,
      visibility: 'TENANT',
    });
    expect(createRequest.headers()['idempotency-key']).toMatch(/[0-9a-f-]{36}/);
    await expect(modal).toBeHidden();
    await expect(drawer.getByTestId('knowledge-notice')).toContainText('文档已登记并进入解析队列');
    await expectDocumentRow(drawer, '登记测试文件');

    // 登记错误分支：403/404/409 均显示稳定中文原因，不出现堆栈。
    for (const [scenario, message] of [
      ['m05-create-403', '没有登记知识库文档的权限'],
      ['m05-create-404', '源文件不存在或已被删除'],
      ['m05-create-409', '该文件已在知识库中'],
    ] as const) {
      await drawer.getByTestId('knowledge-create-open').click();
      await expect(modal).toBeVisible();
      await modal.getByRole('combobox', { name: '源文件' }).click();
      await page
        .locator('.ant-select-dropdown:visible .ant-select-item-option')
        .filter({ hasText: '登记测试文件.pdf' })
        .click();
      await modal.getByTestId('knowledge-create-confirm').check();
      await stub.setScenario(scenario);
      await modal.getByTestId('knowledge-create-submit').click();
      await expect(modal.getByTestId('knowledge-create-error')).toContainText(message);
      await expect(modal.getByText(/java\.|Exception|at com\./)).toHaveCount(0);
      await modal.locator('.ant-modal-close').click();
      await expect(modal).toBeHidden();
    }
    await stub.setScenario('none');

    // 错误态：列表 500 显示可重试错误，不出现堆栈。
    await stub.setScenario('m05-list-500');
    await page.keyboard.press('Escape');
    await expect(drawer).toBeHidden();
    const reopened = await openKnowledge(page);
    await expect(reopened.getByTestId('knowledge-error')).toContainText('知识库加载失败');
    await expect(reopened.getByText(/java\.|Exception|at com\./)).toHaveCount(0);
    await stub.setScenario('none');
    await reopened.getByRole('button', { name: /重\s*试/ }).click();
    await expectDocumentRow(reopened, '销售数据.xlsx');

    // 无权限态：列表 403 显示固定中文说明，不渲染任何文档。
    await stub.setScenario('m05-list-403');
    consoleGuard.allowNetworkError(/\/api\/v1\/knowledge\/documents\?/, 403);
    await reopened.getByTestId('knowledge-refresh').click();
    await expect(reopened.getByTestId('knowledge-forbidden')).toContainText('没有访问权限');
    await expect(reopened.getByText(/请联系租户管理员开通 knowledge\.read/)).toBeVisible();
    await expectDocumentRow(reopened, '销售数据.xlsx', false);
  });
});
