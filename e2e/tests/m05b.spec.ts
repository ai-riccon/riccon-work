import type { Page } from '@playwright/test';
import { expect, test } from './fixtures';
import { expectNoSeriousA11yViolations } from './a11y';
import {
  M05B_COPY,
  M05B_FIXTURES,
  connectorRequest,
  connectorRow,
  expectNoHorizontalScroll,
  loginMember,
  loginPlatformAdmin,
  loginTenantAdmin,
  openConnectors,
} from '../support/m05b-connectors';

/**
 * M05b 连接器 E2E（合同 stub）。
 *
 * 覆盖范围：
 * - E2E-M05-04：配置页创建/编辑/测试/凭据只写掩码、endpointId 文本输入与部署允许列表、
 *   defaultVisibility 显式必填、scheduleEnabled（无 per-connector cron）、403/404；
 * - E2E-M05-05：同步 202/409、失败原因与重试入口、条目五态、删除 KEEP/SOFT_DELETE 与删除传播；
 * - E2E-M05-04b：越权矩阵（员工 403、平台管理员 404、跨租户 404、CSRF 拒绝）；
 * - E2E-M05-04c：列表五态（加载/空/错误/禁用/无权限/404/成功）；
 * - E2E-M05-07e：375px、键盘路径、Modal/Drawer 焦点；
 * - E2E-M05-07f-1..4：连接器页/表单/条目抽屉/删除确认 axe 无 serious/critical；
 * - E2E-M05-04f：连接器来源引用“源内容已更新”的用户可见结果。
 *
 * 真实同步/索引/删除传播的权威语义由后端 `IT-CONN-*`、`SEC-CONN-*` 覆盖；
 * 本文件只验证浏览器可见结果、权限边界与安全展示。
 */

/** 打开页面内的 Modal（按标题过滤），避免与 Drawer 的 dialog 冲突。 */
function modal(page: Page, title: string) {
  return page.getByRole('dialog').filter({ hasText: title });
}

/** 打开条目 Drawer。 */
function itemsDrawer(page: Page) {
  return page.getByRole('dialog').filter({ hasText: '同步条目：' });
}

/**
 * 记录连接器请求 URL 与响应文本；用于断言凭据不出现在 URL/响应/页面。
 *
 * @param page Playwright 页面
 * @returns 收集器
 */
function collectConnectorTraffic(page: Page) {
  const requestUrls: string[] = [];
  const responseTexts: Array<Promise<string>> = [];
  page.on('request', (request) => {
    if (request.url().includes('/api/v1/knowledge/connectors')) {
      requestUrls.push(request.url());
    }
  });
  page.on('response', (response) => {
    if (response.url().includes('/api/v1/knowledge/connectors')) {
      responseTexts.push(response.text().catch(() => ''));
    }
  });
  return { requestUrls, responseTexts };
}

/**
 * 断言当前焦点仍在指定容器内；用于 Modal/Drawer 焦点陷阱。
 *
 * @param page Playwright 页面
 * @param selector 容器选择器
 */
async function expectFocusInside(page: Page, selector: string): Promise<void> {
  await expect
    .poll(
      async () =>
        page.evaluate((sel) => {
          const element = document.activeElement as HTMLElement | null;
          if (element?.closest(sel)) {
            return 'inside';
          }
          return JSON.stringify({
            tag: element?.tagName ?? 'none',
            ariaLabel: element?.getAttribute('aria-label') ?? '',
            text: (element?.textContent ?? '').slice(0, 40),
          });
        }, selector),
      { message: `焦点必须保持在 ${selector} 内` },
    )
    .toBe('inside');
}

/** 断言焦点可见：键盘导航后活动元素匹配 `:focus-visible` 且有可见轮廓。 */
async function expectVisibleFocus(page: Page): Promise<void> {
  const result = await page.evaluate(() => {
    const element = document.activeElement as HTMLElement | null;
    if (!element) {
      return { focusVisible: false, outlined: false, tag: 'none' };
    }
    const style = getComputedStyle(element);
    return {
      focusVisible: element.matches(':focus-visible'),
      outlined:
        style.outlineStyle !== 'none' ||
        style.boxShadow !== 'none' ||
        Number.parseFloat(style.outlineWidth) > 0,
      tag: element.tagName,
    };
  });
  expect(result.focusVisible, `键盘焦点不可见：${JSON.stringify(result)}`).toBe(true);
  expect(result.outlined, `焦点缺少可见轮廓：${JSON.stringify(result)}`).toBe(true);
}

test.describe('M05b 知识库连接器', () => {
  test('E2E-M05-04 创建/编辑/测试/凭据掩码与 endpointId 允许列表', async ({
    page,
    stub,
    consoleGuard,
  }) => {
    const traffic = collectConnectorTraffic(page);
    consoleGuard.allowNetworkError(/\/api\/v1\/knowledge\/connectors$/, 400);
    consoleGuard.allowNetworkError(/\/api\/v1\/knowledge\/connectors$/, 409);
    await stub.seedM05b();
    await loginTenantAdmin(page);

    // 菜单入口只对租户管理员可见。
    await page.goto(M05B_FIXTURES.routes.overview);
    await expect(page.getByRole('link', { name: M05B_COPY.menuLabel, exact: true })).toBeVisible();

    await openConnectors(page);
    await expect(page.getByText('共 4 个连接器')).toBeVisible();

    // 列表：状态、端点、默认可见范围、定时同步、同步结果、失败码与掩码。
    const contractsRow = connectorRow(page, M05B_FIXTURES.connectors.contractsName);
    await expect(contractsRow.getByText('启用', { exact: true })).toBeVisible();
    await expect(contractsRow.getByText('internal-s3')).toBeVisible();
    await expect(contractsRow.getByText('全租户', { exact: true })).toBeVisible();
    await expect(contractsRow.getByText(/下次 2026-11-24 12:00:00 \+08:00/)).toBeVisible();
    await expect(contractsRow.getByText('部分成功', { exact: true })).toBeVisible();
    await expect(contractsRow.getByText(M05B_FIXTURES.connectors.contractsMask)).toBeVisible();
    // 列表不展示 bucket 或对象 key。
    await expect(contractsRow.getByText('sales-archive')).toHaveCount(0);
    await expect(contractsRow.getByText('contracts/2026')).toHaveCount(0);

    // 新建：非允许列表端点 400，不创建任何行，审计无 created。
    const form = modal(page, '新建知识库连接器');
    await page.getByRole('button', { name: /新建连接器/ }).click();
    await expect(form).toBeVisible();
    await expect(form.getByText('创建后先测试再启用')).toBeVisible();
    const endpointInput = form.getByRole('combobox', {
      name: '端点 ID（部署允许列表中的标识，不是 URL）',
    });
    await expect(endpointInput).toBeEditable();
    await expect(form.getByText(/只填写部署允许列表 RICCON_CONNECTOR_ENDPOINTS/)).toBeVisible();
    await expect(form.getByText(/不支持 per-connector cron/)).toBeVisible();
    await expect(form.locator('input[name="cron"], input[placeholder*="cron"]')).toHaveCount(0);

    await form.getByPlaceholder('例如：销售合同归档').fill('季度归档');
    await endpointInput.fill(M05B_FIXTURES.endpoints.notAllowed);
    await form.getByPlaceholder('例如：sales-archive').fill('q-archive');
    await form.getByRole('radio', { name: 'TENANT（本租户全员可见）' }).check();
    await form.getByRole('button', { name: '创建连接器' }).click();
    await expect(form.getByText(M05B_COPY.allowlistError)).toBeVisible();
    const auditAfterRejectedCreate = await stub.audit();
    expect(
      auditAfterRejectedCreate.some(
        (entry) =>
          entry.action === 'knowledge.connector.created' && entry.targetTitle === '季度归档',
      ),
    ).toBe(false);

    // 修正为允许列表端点并写入凭据：响应/页面/审计只出现掩码。
    await endpointInput.fill(M05B_FIXTURES.endpoints.allowed);
    await form.getByPlaceholder('例如：contracts/2026/').fill('q/2026/');
    await form.getByRole('switch', { name: '参与定时同步' }).click();
    await form
      .getByLabel('访问键 ID（accessKeyId）')
      .fill(M05B_FIXTURES.secrets.createdAccessKeyId);
    await form.getByLabel('访问密钥（secretAccessKey）').fill(M05B_FIXTURES.secrets.createdSecret);
    await form.getByRole('button', { name: '创建连接器' }).click();
    await expect(form).toBeHidden();
    await expect(page.getByText(/已创建连接器「季度归档」（默认暂停）/)).toBeVisible();
    await expect(page.getByText(/凭据已加密保存，仅显示掩码 M05B…026/)).toBeVisible();

    const createdRow = connectorRow(page, '季度归档');
    await expect(createdRow.getByText('暂停', { exact: true })).toBeVisible();
    await expect(createdRow.getByText(M05B_FIXTURES.secrets.createdMask)).toBeVisible();
    await expect(page.getByText(M05B_FIXTURES.secrets.createdSecret)).toHaveCount(0);
    await expect(page.getByText(M05B_FIXTURES.secrets.createdAccessKeyId)).toHaveCount(0);

    const auditAfterCreate = await stub.audit();
    expect(
      auditAfterCreate.some(
        (entry) =>
          entry.action === 'knowledge.connector.created' && entry.targetTitle === '季度归档',
      ),
    ).toBe(true);
    expect(
      auditAfterCreate.some((entry) => entry.action === 'knowledge.connector.credential.changed'),
    ).toBe(true);
    expect(JSON.stringify(auditAfterCreate)).not.toContain(M05B_FIXTURES.secrets.createdSecret);
    expect(JSON.stringify(auditAfterCreate)).not.toContain(
      M05B_FIXTURES.secrets.createdAccessKeyId,
    );

    // 连接测试成功。
    await page.getByRole('button', { name: '测试 季度归档' }).click();
    await expect(page.getByText(/连接测试成功/)).toBeVisible();

    // 编辑：只显示掩码，不回显明文；保存不携带 credential；改名生效。
    const editForm = modal(page, '编辑连接器「季度归档」');
    await page.getByRole('button', { name: '编辑 季度归档' }).click();
    await expect(editForm).toBeVisible();
    await expect(editForm.getByText(/当前凭据：M05B…026/)).toBeVisible();
    await expect(editForm.getByLabel('访问键 ID（accessKeyId）')).toHaveValue('');
    await expect(editForm.getByLabel('访问密钥（secretAccessKey）')).toHaveValue('');
    await expect(editForm.getByText(/不支持 per-connector cron/)).toBeVisible();
    await editForm.getByPlaceholder('例如：销售合同归档').fill('季度归档（改名）');
    const patchRequestPromise = page.waitForRequest(
      (request) =>
        request.method() === 'PATCH' &&
        request.url().includes('/api/v1/knowledge/connectors/') &&
        !request.url().endsWith('/credential'),
    );
    await editForm.getByRole('button', { name: '保存修改' }).click();
    const patchRequest = await patchRequestPromise;
    const patchBody = patchRequest.postDataJSON() as Record<string, unknown>;
    expect(patchBody).toMatchObject({ name: '季度归档（改名）' });
    expect(patchBody).not.toHaveProperty('credential');
    await expect(editForm).toBeHidden();
    await expect(page.getByText(/已保存连接器「季度归档（改名）」/)).toBeVisible();
    await expect(connectorRow(page, '季度归档（改名）')).toBeVisible();

    // 轮换凭据：PATCH 后 PUT，响应只返回掩码；页面/响应/URL/审计均无明文。
    const rotateForm = modal(page, '编辑连接器「季度归档（改名）」');
    await page.getByRole('button', { name: '编辑 季度归档（改名）' }).click();
    await expect(rotateForm).toBeVisible();
    await rotateForm
      .getByLabel('访问键 ID（accessKeyId）')
      .fill(M05B_FIXTURES.secrets.rotatedAccessKeyId);
    await rotateForm
      .getByLabel('访问密钥（secretAccessKey）')
      .fill(M05B_FIXTURES.secrets.rotatedSecret);
    const credentialResponsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === 'PUT' &&
        response.url().endsWith('/credential') &&
        response.status() === 200,
    );
    await rotateForm.getByRole('button', { name: '保存修改' }).click();
    const credentialResponse = await credentialResponsePromise;
    const credentialPayload = (await credentialResponse.json()) as Record<string, unknown>;
    expect(JSON.stringify(credentialPayload)).not.toContain(M05B_FIXTURES.secrets.rotatedSecret);
    expect(JSON.stringify(credentialPayload)).not.toContain(
      M05B_FIXTURES.secrets.rotatedAccessKeyId,
    );
    expect(credentialPayload).not.toHaveProperty('accessKeyId');
    expect(credentialPayload).not.toHaveProperty('secretAccessKey');
    expect(credentialPayload.credentialMaskedHint).toBe(M05B_FIXTURES.secrets.rotatedMask);
    await expect(rotateForm).toBeHidden();
    await expect(page.getByText(/轮换凭据（仅显示掩码 ROTA…026）/)).toBeVisible();
    await expect(
      connectorRow(page, '季度归档（改名）').getByText(M05B_FIXTURES.secrets.rotatedMask),
    ).toBeVisible();

    // 重名 409：表单内显示可执行文案。
    const duplicateForm = modal(page, '新建知识库连接器');
    await page.getByRole('button', { name: /新建连接器/ }).click();
    await expect(duplicateForm).toBeVisible();
    await duplicateForm
      .getByPlaceholder('例如：销售合同归档')
      .fill(M05B_FIXTURES.connectors.contractsName);
    await duplicateForm
      .getByRole('combobox', { name: '端点 ID（部署允许列表中的标识，不是 URL）' })
      .fill(M05B_FIXTURES.endpoints.allowed);
    await duplicateForm.getByPlaceholder('例如：sales-archive').fill('duplicate-bucket');
    await duplicateForm.getByRole('radio', { name: 'TENANT（本租户全员可见）' }).check();
    await duplicateForm.getByRole('button', { name: '创建连接器' }).click();
    await expect(duplicateForm.getByText(M05B_COPY.duplicateName)).toBeVisible();
    await duplicateForm.locator('.ant-modal-close').click();
    await expect(duplicateForm).toBeHidden();

    // defaultVisibility 必须显式；DEPT 必须选启用部门。
    const guardForm = modal(page, '新建知识库连接器');
    await page.getByRole('button', { name: /新建连接器/ }).click();
    await expect(guardForm).toBeVisible();
    await guardForm.getByPlaceholder('例如：销售合同归档').fill('缺省可见范围');
    await guardForm
      .getByRole('combobox', { name: '端点 ID（部署允许列表中的标识，不是 URL）' })
      .fill(M05B_FIXTURES.endpoints.allowed);
    await guardForm.getByPlaceholder('例如：sales-archive').fill('guard-bucket');
    await guardForm.getByRole('button', { name: '创建连接器' }).click();
    await expect(guardForm.getByText(M05B_COPY.visibilityRequired)).toBeVisible();
    await guardForm.getByRole('radio', { name: 'DEPT（仅指定部门可见）' }).check();
    await guardForm.getByRole('button', { name: '创建连接器' }).click();
    await expect(guardForm.getByText(M05B_COPY.departmentRequired)).toBeVisible();
    await guardForm.locator('.ant-modal-close').click();
    await expect(guardForm).toBeHidden();

    // 凭据不出现在 URL、任何连接器响应或页面 DOM。
    await Promise.all(traffic.responseTexts);
    for (const url of traffic.requestUrls) {
      expect(url).not.toContain(M05B_FIXTURES.secrets.createdSecret);
      expect(url).not.toContain(M05B_FIXTURES.secrets.rotatedSecret);
      expect(url).not.toContain(M05B_FIXTURES.secrets.createdAccessKeyId);
      expect(url).not.toContain(M05B_FIXTURES.secrets.rotatedAccessKeyId);
    }
    const responses = await Promise.all(traffic.responseTexts);
    for (const text of responses) {
      expect(text).not.toContain(M05B_FIXTURES.secrets.createdSecret);
      expect(text).not.toContain(M05B_FIXTURES.secrets.rotatedSecret);
      expect(text).not.toContain(M05B_FIXTURES.secrets.createdAccessKeyId);
      expect(text).not.toContain(M05B_FIXTURES.secrets.rotatedAccessKeyId);
    }
    const pageContent = await page.content();
    expect(pageContent).not.toContain(M05B_FIXTURES.secrets.createdSecret);
    expect(pageContent).not.toContain(M05B_FIXTURES.secrets.rotatedSecret);
  });

  test('E2E-M05-04b 越权：员工 403、平台 404、跨租户 404 与 CSRF 拒绝', async ({
    page,
    stub,
    consoleGuard,
  }) => {
    await stub.seedM05b();
    await loginTenantAdmin(page);
    await openConnectors(page);

    // 跨租户 connectorId：配置/条目统一 404，不泄露名称。
    const otherItems = await connectorRequest(
      page,
      'GET',
      `/api/v1/knowledge/connectors/${M05B_FIXTURES.connectors.otherTenantId}/items`,
    );
    expect(otherItems.status).toBe(404);
    expect(otherItems.text).not.toContain(M05B_FIXTURES.connectors.otherTenantName);
    const otherPatch = await connectorRequest(
      page,
      'PATCH',
      `/api/v1/knowledge/connectors/${M05B_FIXTURES.connectors.otherTenantId}`,
      { data: { name: '被篡改的名称' } },
    );
    expect(otherPatch.status).toBe(404);
    expect(otherPatch.text).not.toContain(M05B_FIXTURES.connectors.otherTenantName);
    const unknownItems = await connectorRequest(
      page,
      'GET',
      '/api/v1/knowledge/connectors/00000000-0000-4000-8000-000000000000/items',
    );
    expect(unknownItems.status).toBe(404);

    // 缺少 CSRF：写操作 403，且不产生连接器或审计。
    const auditBefore = await stub.audit();
    const csrfMissingCreate = await connectorRequest(page, 'POST', '/api/v1/knowledge/connectors', {
      csrf: false,
      data: {
        name: '无 CSRF 连接器',
        type: 'S3',
        endpointId: M05B_FIXTURES.endpoints.allowed,
        config: { bucket: 'csrf-bucket' },
        defaultVisibility: 'TENANT',
      },
    });
    expect(csrfMissingCreate.status).toBe(403);
    expect(csrfMissingCreate.text).toContain('RICCON-AUTH-403');
    const auditAfter = await stub.audit();
    expect(auditAfter.length).toBe(auditBefore.length);
    const listed = await connectorRequest(
      page,
      'GET',
      '/api/v1/knowledge/connectors?page=1&size=20',
    );
    expect(listed.text).not.toContain('无 CSRF 连接器');

    // 普通员工：无菜单入口；页面 403；直接调用 403 且无数据。
    await page.context().clearCookies();
    await loginMember(page);
    await page.goto(M05B_FIXTURES.routes.overview);
    await expect(page.getByRole('link', { name: M05B_COPY.menuLabel, exact: true })).toHaveCount(0);
    consoleGuard.allowNetworkError(/\/api\/v1\/knowledge\/connectors/, 403);
    await page.goto(M05B_FIXTURES.routes.connectors);
    await expect(page.getByText('没有访问权限', { exact: true })).toBeVisible();
    await expect(page.getByText(M05B_COPY.forbiddenMember)).toBeVisible();
    await expect(page.getByText(M05B_FIXTURES.connectors.contractsName)).toHaveCount(0);
    const memberList = await connectorRequest(page, 'GET', '/api/v1/knowledge/connectors');
    expect(memberList.status).toBe(403);
    expect(memberList.text).not.toContain(M05B_FIXTURES.connectors.contractsName);
    const memberItems = await connectorRequest(
      page,
      'GET',
      `/api/v1/knowledge/connectors/${M05B_FIXTURES.connectors.contractsId}/items`,
    );
    expect(memberItems.status).toBe(403);
    expect(memberItems.text).not.toContain('q3-report');
    const memberSync = await connectorRequest(
      page,
      'POST',
      `/api/v1/knowledge/connectors/${M05B_FIXTURES.connectors.contractsId}/sync`,
      { data: {} },
    );
    expect(memberSync.status).toBe(403);
    const memberCreate = await connectorRequest(page, 'POST', '/api/v1/knowledge/connectors', {
      data: {
        name: '员工越权创建',
        type: 'S3',
        endpointId: M05B_FIXTURES.endpoints.allowed,
        config: { bucket: 'member-bucket' },
        defaultVisibility: 'TENANT',
      },
    });
    expect(memberCreate.status).toBe(403);

    // 平台管理员：无菜单入口；页面与接口统一 404，不泄露租户连接器。
    await page.context().clearCookies();
    await loginPlatformAdmin(page);
    await page.goto(M05B_FIXTURES.routes.overview);
    await expect(page.getByRole('link', { name: M05B_COPY.menuLabel, exact: true })).toHaveCount(0);
    consoleGuard.allowNetworkError(/\/api\/v1\/knowledge\/connectors/, 404);
    await page.goto(M05B_FIXTURES.routes.connectors);
    await expect(page.getByText('连接器不存在', { exact: true })).toBeVisible();
    await expect(page.getByText(M05B_COPY.notFoundPlatform)).toBeVisible();
    await expect(page.getByText(M05B_FIXTURES.connectors.contractsName)).toHaveCount(0);
    const platformList = await connectorRequest(page, 'GET', '/api/v1/knowledge/connectors');
    expect(platformList.status).toBe(404);
    expect(platformList.text).not.toContain(M05B_FIXTURES.connectors.contractsName);
    const platformItems = await connectorRequest(
      page,
      'GET',
      `/api/v1/knowledge/connectors/${M05B_FIXTURES.connectors.contractsId}/items`,
    );
    expect(platformItems.status).toBe(404);
    expect(platformItems.text).not.toContain('q3-report');
    const platformSync = await connectorRequest(
      page,
      'POST',
      `/api/v1/knowledge/connectors/${M05B_FIXTURES.connectors.contractsId}/sync`,
      { data: {} },
    );
    expect(platformSync.status).toBe(404);
  });

  test('E2E-M05-05 同步 202/409、失败原因与条目五态', async ({ page, stub, consoleGuard }) => {
    consoleGuard.allowNetworkError(/\/api\/v1\/knowledge\/connectors\/[^/]+\/sync$/, 409);
    await stub.seedM05b();
    await loginTenantAdmin(page);
    await openConnectors(page);

    // 失败连接器：最近失败码、结束时间与上次成功时间可见；可重试。
    const backupRow = connectorRow(page, M05B_FIXTURES.connectors.backupName);
    await expect(backupRow.getByText('同步失败', { exact: true })).toBeVisible();
    await expect(backupRow.getByText(/源端认证失败/)).toBeVisible();
    await expect(backupRow.getByText('KB_CONNECTOR_AUTH')).toBeVisible();
    await expect(backupRow.getByText('2026-11-22 09:04:12 +08:00')).toBeVisible();

    const backupDrawer = itemsDrawer(page);
    await page.getByRole('button', { name: `条目 ${M05B_FIXTURES.connectors.backupName}` }).click();
    await expect(backupDrawer).toBeVisible();
    await expect(backupDrawer.getByText('开始时间')).toBeVisible();
    await expect(backupDrawer.getByText('2026-11-23 18:25:00 +08:00')).toBeVisible();
    await expect(backupDrawer.getByText('结束时间')).toBeVisible();
    // 失败也必须有结束时间。
    await expect(backupDrawer.getByText('2026-11-23 18:30:00 +08:00')).toBeVisible();
    await expect(backupDrawer.getByText(M05B_COPY.itemsEmpty)).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(backupDrawer).toBeHidden();

    // 连接测试失败：AUTH 分类与中文原因。
    await stub.setScenario('m05b-test-fail-auth');
    await page.getByRole('button', { name: `测试 ${M05B_FIXTURES.connectors.backupName}` }).click();
    await expect(page.locator('.ant-alert-error')).toContainText(
      '连接测试失败（凭据被拒绝 · AUTH）',
    );
    await expect(page.locator('.ant-alert-error')).toContainText('源端拒绝了凭据');
    await stub.setScenario('none');

    // 未写入凭据：CONFIG 分类失败，并给出先保存凭据的下一步。
    await page
      .getByRole('button', { name: `测试 ${M05B_FIXTURES.connectors.financeName}` })
      .click();
    await expect(page.locator('.ant-alert-error')).toContainText(
      '连接测试失败（配置不可用 · CONFIG）',
    );
    await expect(page.locator('.ant-alert-error')).toContainText('尚未写入凭据');

    // 手动同步 202：标记同步中并禁用重复触发；并发直接调用 409。
    const syncedRow = connectorRow(page, M05B_FIXTURES.connectors.backupName);
    await page.getByRole('button', { name: `同步 ${M05B_FIXTURES.connectors.backupName}` }).click();
    await expect(page.getByText(/已受理连接器「年度备份（连接失败）」的同步（202/)).toBeVisible();
    const syncingButton = page.getByRole('button', {
      name: `同步中 ${M05B_FIXTURES.connectors.backupName}`,
    });
    await expect(syncingButton).toBeDisabled();
    await expect(syncedRow.getByText('同步中', { exact: true })).toBeVisible();
    const concurrentSync = await connectorRequest(
      page,
      'POST',
      `/api/v1/knowledge/connectors/${M05B_FIXTURES.connectors.backupId}/sync`,
      { data: {} },
    );
    expect(concurrentSync.status).toBe(409);
    expect(concurrentSync.text).toContain('already pending or running');

    // 409 场景：UI 显示可执行中文文案。
    await stub.setScenario('m05b-sync-409');
    await page
      .getByRole('button', { name: `同步 ${M05B_FIXTURES.connectors.financeName}` })
      .click();
    await expect(page.locator('.ant-alert-error')).toContainText(M05B_COPY.syncConflict);
    await stub.setScenario('none');

    // 条目五态、失败原因、计数与对象末段；不含 bucket/完整 key/预签名 URL。
    const contractsDrawer = itemsDrawer(page);
    await page
      .getByRole('button', { name: `条目 ${M05B_FIXTURES.connectors.contractsName}` })
      .click();
    await expect(contractsDrawer).toBeVisible();
    await expect(contractsDrawer.getByText('部分成功', { exact: true })).toBeVisible();
    await expect(contractsDrawer.getByText('list 完整')).toBeVisible();
    await expect(contractsDrawer.getByText('2026-11-23 09:00:00 +08:00').first()).toBeVisible();
    await expect(contractsDrawer.getByText('2026-11-23 09:04:12 +08:00').first()).toBeVisible();
    await expect(contractsDrawer.getByText('2026-11-22 09:04:12 +08:00').first()).toBeVisible();
    await expect(
      contractsDrawer.getByText('扫描 6；新增 2；更新 1；跳过 1；失败 1；删除 1'),
    ).toBeVisible();
    for (const label of ['待同步', '已同步', '已跳过', '失败', '已删除']) {
      await expect(contractsDrawer.getByText(label, { exact: true }).first()).toBeVisible();
    }
    await expect(contractsDrawer.getByText(/文件类型不支持/)).toBeVisible();
    await expect(contractsDrawer.getByText('KB_CONNECTOR_TYPE_UNSUPPORTED')).toBeVisible();
    await expect(contractsDrawer.getByText(/对象处理失败/)).toBeVisible();
    await expect(contractsDrawer.getByText('KB_CONNECTOR_OBJECT_FAILED')).toBeVisible();
    await expect(contractsDrawer.getByText('2026/q3-report.pdf')).toBeVisible();
    await expect(contractsDrawer.getByText('2026/broken.pdf')).toBeVisible();
    await expect(contractsDrawer.getByText('contracts/2026')).toHaveCount(0);
    await expect(contractsDrawer.getByText('sales-archive')).toHaveCount(0);
    await expect(contractsDrawer.getByText(/X-Amz|X-AMZ/)).toHaveCount(0);
    await expect(contractsDrawer.getByText(/\/objects\//)).toHaveCount(0);

    // 状态筛选回到第一页并按 FAILED 请求。
    const filteredRequest = page.waitForRequest(
      (request) =>
        request.method() === 'GET' &&
        request
          .url()
          .includes(`/api/v1/knowledge/connectors/${M05B_FIXTURES.connectors.contractsId}/items`) &&
        request.url().includes('status=FAILED'),
    );
    await contractsDrawer.getByRole('combobox', { name: '同步条目状态筛选' }).click();
    await page
      .locator('.ant-select-dropdown:visible .ant-select-item-option')
      .filter({ hasText: '失败' })
      .click();
    await filteredRequest;
    await expect(contractsDrawer.getByRole('row').filter({ hasText: 'broken.pdf' })).toBeVisible();
    await expect(contractsDrawer.getByText('2026/q3-report.pdf')).toHaveCount(0);

    // 条目错误态与重试：先关闭当前抽屉，再打开另一个连接器的条目。
    // 焦点在筛选下拉时 Escape 只会关闭下拉，因此显式使用抽屉关闭按钮。
    await contractsDrawer.getByRole('button', { name: '关闭' }).click();
    await expect(contractsDrawer).toBeHidden();
    consoleGuard.allowNetworkError(/\/api\/v1\/knowledge\/connectors\/[^/]+\/items/, 500);
    await stub.setScenario('m05b-items-500');
    await page
      .getByRole('button', { name: `条目 ${M05B_FIXTURES.connectors.financeName}` })
      .click();
    await expect(contractsDrawer).toBeVisible();
    await expect(contractsDrawer.getByText('同步条目加载失败')).toBeVisible();
    await expect(contractsDrawer.getByRole('button', { name: /重\s*试/ })).toBeVisible();
    await expect(contractsDrawer.getByText(/java\.|Exception|at com\./)).toHaveCount(0);
    await stub.setScenario('none');
    await contractsDrawer.getByRole('button', { name: /重\s*试/ }).click();
    await expect(contractsDrawer.getByText(M05B_COPY.itemsEmpty)).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(contractsDrawer).toBeHidden();
  });

  test('E2E-M05-05b 删除 KEEP/SOFT_DELETE 与知识库删除传播', async ({ page, stub }) => {
    await stub.seedM05b();
    await loginTenantAdmin(page);

    // 删除前：连接器导入文档在知识库列表可见（默认 TENANT）。
    await page.goto('/chat');
    await page.getByTestId('knowledge-entry').click();
    const knowledgeDrawer = page.getByTestId('knowledge-drawer');
    await expect(knowledgeDrawer).toBeVisible();
    await expect(
      knowledgeDrawer.getByRole('row').filter({ hasText: 'q3-report.pdf' }),
    ).toBeVisible();
    await expect(
      knowledgeDrawer.getByRole('row').filter({ hasText: 'q4-plan.docx' }),
    ).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(knowledgeDrawer).toBeHidden();

    await openConnectors(page);

    // 删除财务连接器：默认 KEEP，保留已导入文档。
    const financeDialog = modal(page, `删除连接器「${M05B_FIXTURES.connectors.financeName}」`);
    await page
      .getByRole('button', { name: `删除 ${M05B_FIXTURES.connectors.financeName}` })
      .click();
    await expect(financeDialog).toBeVisible();
    await expect(financeDialog.getByRole('radio', { name: /KEEP：保留已导入文档/ })).toBeChecked();
    const keepRequestPromise = page.waitForRequest(
      (request) =>
        request.method() === 'DELETE' &&
        request
          .url()
          .includes(`/api/v1/knowledge/connectors/${M05B_FIXTURES.connectors.financeId}`),
    );
    await financeDialog.getByRole('button', { name: '确认删除' }).click();
    const keepRequest = await keepRequestPromise;
    expect(keepRequest.url()).toContain('documents=KEEP');
    await expect(page.locator('.ant-alert-success')).toContainText('保留已导入文档');
    await expect(connectorRow(page, M05B_FIXTURES.connectors.financeName)).toHaveCount(0);

    // 删除合同连接器：SOFT_DELETE 有二次警示，导入文档在知识库列表消失并写 CONNECTOR 审计。
    const contractsDialog = modal(page, `删除连接器「${M05B_FIXTURES.connectors.contractsName}」`);
    await page
      .getByRole('button', { name: `删除 ${M05B_FIXTURES.connectors.contractsName}` })
      .click();
    await expect(contractsDialog).toBeVisible();
    await contractsDialog.getByRole('radio', { name: /SOFT_DELETE：一并软删除/ }).check();
    await expect(contractsDialog.getByText('将一并删除文档')).toBeVisible();
    const softDeleteRequestPromise = page.waitForRequest(
      (request) =>
        request.method() === 'DELETE' &&
        request
          .url()
          .includes(`/api/v1/knowledge/connectors/${M05B_FIXTURES.connectors.contractsId}`),
    );
    await contractsDialog.getByRole('button', { name: '确认删除' }).click();
    const softDeleteRequest = await softDeleteRequestPromise;
    expect(softDeleteRequest.url()).toContain('documents=SOFT_DELETE');
    await expect(page.locator('.ant-alert-success')).toContainText('一并软删除已导入文档');
    await expect(connectorRow(page, M05B_FIXTURES.connectors.contractsName)).toHaveCount(0);
    await expect(connectorRow(page, M05B_FIXTURES.connectors.policyName)).toBeVisible();

    const audit = await stub.audit();
    const connectorDeletes = audit.filter(
      (entry) => entry.action === 'knowledge.connector.deleted',
    ) as Array<{
      details?: { documents?: string } | null;
    }>;
    expect(connectorDeletes.some((entry) => entry.details?.documents === 'KEEP')).toBe(true);
    expect(connectorDeletes.some((entry) => entry.details?.documents === 'SOFT_DELETE')).toBe(true);
    expect(
      audit.some(
        (entry) =>
          entry.action === 'knowledge.document.deleted' &&
          entry.reason === 'CONNECTOR' &&
          entry.targetId === 'd6d6d6d6-d6d6-46d6-86d6-000000000001',
      ),
    ).toBe(true);

    // 删除传播后：知识库列表不再显示被软删除的连接器文档。
    await page.goto('/chat');
    await page.getByTestId('knowledge-entry').click();
    const afterDrawer = page.getByTestId('knowledge-drawer');
    await expect(afterDrawer).toBeVisible();
    await expect(afterDrawer.getByRole('row').filter({ hasText: 'q3-report.pdf' })).toHaveCount(0);
    await expect(afterDrawer.getByRole('row').filter({ hasText: 'q4-plan.docx' })).toHaveCount(0);
  });

  test('E2E-M05-04c 列表五态：加载/空/错误/禁用/无权限/404/成功', async ({
    page,
    stub,
    consoleGuard,
  }) => {
    await stub.seedM05b();
    await loginTenantAdmin(page);

    // 加载态：延迟列表响应，展示 aria-busy 骨架。
    await page.route(/\/api\/v1\/knowledge\/connectors\?/, async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 1500));
      await route.continue().catch(() => undefined);
    });
    await page.goto(M05B_FIXTURES.routes.connectors);
    await expect(page.getByLabel('正在加载连接器列表')).toBeVisible();
    await expect(connectorRow(page, M05B_FIXTURES.connectors.contractsName)).toBeVisible({
      timeout: 15_000,
    });
    await page.unroute(/\/api\/v1\/knowledge\/connectors\?/);

    // 空态：引导新建第一个连接器。
    await stub.seedM05b({ empty: true });
    await page.goto(M05B_FIXTURES.routes.connectors);
    await expect(page.getByText(M05B_COPY.empty)).toBeVisible();
    await expect(page.getByRole('button', { name: '新建第一个连接器' })).toBeVisible();

    // 错误 500：中文错误与重试入口，重试后恢复。
    await stub.seedM05b();
    await stub.setScenario('m05b-list-500');
    consoleGuard.allowNetworkError(/\/api\/v1\/knowledge\/connectors\?/, 500);
    await page.goto(M05B_FIXTURES.routes.connectors);
    await expect(page.getByText('连接器列表加载失败')).toBeVisible();
    await expect(page.getByText(/java\.|Exception|at com\./)).toHaveCount(0);
    await stub.setScenario('none');
    await page.getByRole('button', { name: /重\s*试/ }).click();
    await expect(connectorRow(page, M05B_FIXTURES.connectors.contractsName)).toBeVisible();

    // 禁用（连接器关闭）：503 可读原因与下一步。
    await stub.setScenario('m05b-list-503');
    consoleGuard.allowNetworkError(/\/api\/v1\/knowledge\/connectors\?/, 503);
    await page.goto(M05B_FIXTURES.routes.connectors);
    await expect(page.getByText(M05B_COPY.disabled)).toBeVisible();
    await expect(page.getByText(/RICCON_CONNECTOR_ENABLED/)).toBeVisible();

    // 无权限（同租户非管理员被服务端拒绝）。
    await stub.setScenario('m05b-list-403');
    consoleGuard.allowNetworkError(/\/api\/v1\/knowledge\/connectors\?/, 403);
    await page.goto(M05B_FIXTURES.routes.connectors);
    await expect(page.getByText('没有访问权限', { exact: true })).toBeVisible();
    await expect(page.getByText(/RICCON-AUTH-403/)).toBeVisible();

    // 404：不泄露连接器存在性，并提供重试。
    await stub.setScenario('m05b-list-404');
    consoleGuard.allowNetworkError(/\/api\/v1\/knowledge\/connectors\?/, 404);
    await page.goto(M05B_FIXTURES.routes.connectors);
    await expect(page.getByText('连接器不存在', { exact: true })).toBeVisible();
    await expect(page.getByText(/RICCON-TENANT-404/)).toBeVisible();
    await expect(page.getByText(M05B_FIXTURES.connectors.contractsName)).toHaveCount(0);

    // 恢复成功态。
    await stub.setScenario('none');
    await page.getByRole('button', { name: /重\s*试/ }).click();
    await expect(connectorRow(page, M05B_FIXTURES.connectors.contractsName)).toBeVisible();
  });

  test('E2E-M05-07e 375px、键盘路径与 Modal/Drawer 焦点', async ({ page, stub }) => {
    await stub.seedM05b();
    await page.setViewportSize({ width: 375, height: 812 });
    await loginTenantAdmin(page);
    await openConnectors(page);
    await expectNoHorizontalScroll(page);

    // 键盘打开新建弹窗，焦点进入 Modal 且 Tab 被焦点陷阱约束。
    const createButton = page.getByRole('button', { name: /新建连接器/ });
    await createButton.focus();
    await page.keyboard.press('Enter');
    const form = modal(page, '新建知识库连接器');
    await expect(form).toBeVisible();
    await expectFocusInside(page, '.ant-modal');
    for (let index = 0; index < 12; index += 1) {
      await page.keyboard.press('Tab');
      await expectFocusInside(page, '.ant-modal');
    }
    await expectVisibleFocus(page);
    await expectNoHorizontalScroll(page);
    await page.keyboard.press('Escape');
    await expect(form).toBeHidden();
    await expect(createButton).toBeFocused();

    // 键盘触发连接测试。
    const testButton = page.getByRole('button', {
      name: `测试 ${M05B_FIXTURES.connectors.contractsName}`,
    });
    await testButton.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByText(/连接测试成功/)).toBeVisible();

    // 键盘打开编辑并保存（打开连接器 → 保存）。
    const editButton = page.getByRole('button', {
      name: `编辑 ${M05B_FIXTURES.connectors.contractsName}`,
    });
    await editButton.focus();
    await page.keyboard.press('Enter');
    const editForm = modal(page, `编辑连接器「${M05B_FIXTURES.connectors.contractsName}」`);
    await expect(editForm).toBeVisible();
    await expectFocusInside(page, '.ant-modal');
    // 键盘 Tab 后活动元素必须有可见焦点；再聚焦保存并回车完成键盘保存。
    await page.keyboard.press('Tab');
    await expectVisibleFocus(page);
    const saveButton = editForm.getByRole('button', { name: '保存修改' });
    await saveButton.focus();
    await page.keyboard.press('Enter');
    await expect(editForm).toBeHidden();
    await expect(page.getByText(/已保存连接器「销售合同归档」/)).toBeVisible();

    // 键盘打开条目 Drawer；焦点在 Drawer 内，Escape 关闭并回到触发按钮。
    const itemsButton = page.getByRole('button', {
      name: `条目 ${M05B_FIXTURES.connectors.contractsName}`,
    });
    await itemsButton.focus();
    await page.keyboard.press('Enter');
    const drawer = itemsDrawer(page);
    await expect(drawer).toBeVisible();
    await expectFocusInside(page, '.ant-drawer');
    // antd 6 的 Drawer 没有焦点陷阱，关闭后也不会把焦点还给触发按钮；AC-19 只要求
    // 键盘可完成路径与焦点可见。这里断言打开后焦点进入 Drawer、抽屉内控件可 Tab 到达、
    // Escape 可关闭；焦点逃逸与不归还触发按钮作为 P2 残余在任务回执记录，不在测试中
    // 伪装为已通过。
    await page.keyboard.press('Tab');
    await expectFocusInside(page, '.ant-drawer');
    await page.keyboard.press('Shift+Tab');
    await expectFocusInside(page, '.ant-drawer');
    await expectNoHorizontalScroll(page);
    await page.keyboard.press('Escape');
    await expect(drawer).toBeHidden();
  });

  test('E2E-M05-07f-1 连接器列表 axe 无 serious/critical @a11y', async ({ page, stub }) => {
    await stub.seedM05b();
    await page.setViewportSize({ width: 375, height: 812 });
    await loginTenantAdmin(page);
    await openConnectors(page);
    await connectorRow(page, M05B_FIXTURES.connectors.contractsName).waitFor();
    await page.mouse.move(0, 0);
    await expectNoSeriousA11yViolations(page);
  });

  test('E2E-M05-07f-2 新建连接器表单 axe 无 serious/critical @a11y', async ({ page, stub }) => {
    await stub.seedM05b();
    await page.setViewportSize({ width: 375, height: 812 });
    await loginTenantAdmin(page);
    await openConnectors(page);
    await page.getByRole('button', { name: /新建连接器/ }).click();
    await expect(modal(page, '新建知识库连接器')).toBeVisible();
    await page.mouse.move(0, 0);
    await expectNoSeriousA11yViolations(page);
  });

  test('E2E-M05-07f-3 条目抽屉 axe 无 serious/critical @a11y', async ({ page, stub }) => {
    await stub.seedM05b();
    await page.setViewportSize({ width: 375, height: 812 });
    await loginTenantAdmin(page);
    await openConnectors(page);
    // 375px 下表格 fixed-left/right 列重叠，行按钮可能被名称列遮挡；用键盘打开抽屉。
    const itemsButton = page.getByRole('button', {
      name: `条目 ${M05B_FIXTURES.connectors.contractsName}`,
    });
    await itemsButton.focus();
    await page.keyboard.press('Enter');
    await expect(itemsDrawer(page)).toBeVisible();
    await expect(itemsDrawer(page).getByText('2026/q3-report.pdf')).toBeVisible();
    await page.mouse.move(0, 0);
    await expectNoSeriousA11yViolations(page);
  });

  test('E2E-M05-07f-4 删除确认弹窗 axe 无 serious/critical @a11y', async ({ page, stub }) => {
    await stub.seedM05b();
    await page.setViewportSize({ width: 375, height: 812 });
    await loginTenantAdmin(page);
    await openConnectors(page);
    // 375px 下用键盘打开删除确认，避免 fixed 列遮挡点击。
    const deleteButton = page.getByRole('button', {
      name: `删除 ${M05B_FIXTURES.connectors.financeName}`,
    });
    await deleteButton.focus();
    await page.keyboard.press('Enter');
    await expect(
      modal(page, `删除连接器「${M05B_FIXTURES.connectors.financeName}」`),
    ).toBeVisible();
    await page.mouse.move(0, 0);
    await expectNoSeriousA11yViolations(page);
  });

  test('E2E-M05-04f 连接器来源引用显示“源内容已更新”', async ({ page, stub }) => {
    await stub.seedM05b();
    await loginMember(page);
    await page.getByRole('button', { name: '新建会话' }).click();
    await expect(page.getByText('开始 J1 问答')).toBeVisible();
    await stub.setScenario('m05-citation-connector-updated');
    const composer = page.getByPlaceholder('输入问题，Enter 发送，Shift+Enter 换行');
    await composer.fill('请汇总第三季度销售情况');
    await composer.press('Enter');

    const citation = page.getByRole('button', { name: /q3-report\.pdf/ });
    await expect(citation).toBeVisible({ timeout: 15_000 });
    await citation.click();
    const drawer = page.getByRole('dialog').filter({ hasText: '引用详情' });
    await expect(drawer.getByTestId('citation-updated')).toContainText(M05B_COPY.updatedNotice);
    await expect(drawer.getByTestId('citation-title')).toContainText('q3-report.pdf');
    await expect(drawer.getByTestId('citation-snippet')).toContainText(
      '华东区第三季度销售额 128 万元',
    );
    await expect(drawer.getByText('第 2 段', { exact: true })).toBeVisible();
  });
});
