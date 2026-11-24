// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import type { SessionInfo } from '@/services/api/types';
import { authStore } from '@/services/auth/auth-store';
import type { ConnectorApi } from '@/services/connector';
import { DEMO_SESSION } from '@/test/fake-api';
import {
  connectorItemPage,
  connectorPage,
  createFakeConnectorApi,
  createFakeDepartmentDirectory,
  makeConnector,
  makeConnectorItem,
  makeDepartment,
  makeTestResult,
} from '@/test/fake-connectors';
import { withProviders } from '@/test/render';
import ConnectorsPage from './index';

/** 租户管理员会话：连接器可读可写。 */
const TENANT_ADMIN_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  user: { ...DEMO_SESSION.user, id: 'tenant-admin', displayName: '王敏' },
  roles: ['TENANT_ADMIN'],
  permissions: [
    ...DEMO_SESSION.permissions,
    'knowledge.read',
    'knowledge.manage',
    'knowledge.acl.manage',
    'knowledge.connector.read',
    'knowledge.connector.manage',
  ],
};

/** 只读租户管理员：只有 read。 */
const READ_ONLY_SESSION: SessionInfo = {
  ...TENANT_ADMIN_SESSION,
  permissions: ['knowledge.connector.read'],
};

/** 普通员工会话：没有连接器权限。 */
const MEMBER_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  roles: ['MEMBER'],
};

/** 平台管理员会话：没有连接器权限，按后端语义显示 404。 */
const PLATFORM_ADMIN_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  user: { ...DEMO_SESSION.user, id: 'platform-admin', displayName: '赵蕾' },
  tenant: { ...DEMO_SESSION.tenant, id: 'platform', name: '平台' },
  roles: ['PLATFORM_ADMIN'],
  permissions: ['platform.tenant.manage', 'platform.model.read'],
};

/**
 * 渲染连接器页。
 *
 * @param api API 替身
 * @param departments 部门目录替身
 * @returns 渲染结果
 */
function renderPage(
  api: ConnectorApi,
  departments = createFakeDepartmentDirectory([makeDepartment()]),
) {
  return render(withProviders(<ConnectorsPage api={api} departments={departments} />));
}

/**
 * 断言按钮可访问名称以可见文字开头，且 antd 未向两字中文插入空格。
 *
 * @param button 行操作按钮
 */
function expectLabelInName(button: HTMLElement): void {
  const visibleText = (button.textContent ?? '').trim();
  const accessibleName = (button.getAttribute('aria-label') ?? '').trim();
  expect(visibleText.length).toBeGreaterThan(0);
  expect(visibleText).not.toMatch(/\s/);
  expect(accessibleName.startsWith(visibleText)).toBe(true);
}

/**
 * 解析 rgb()/hex 颜色为 WCAG 相对亮度。
 *
 * @param color 浏览器计算后的颜色
 * @returns 相对亮度（0–1）
 */
function relativeLuminance(color: string): number {
  const channels = color.startsWith('rgb')
    ? (color.match(/\d+/g) ?? []).slice(0, 3).map(Number)
    : [1, 3, 5].map((offset) => Number.parseInt(color.slice(offset, offset + 2), 16));
  const [r, g, b] = channels.map((value) => {
    const channel = value / 255;
    return channel <= 0.03928 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/**
 * 计算两个颜色的 WCAG 对比度。
 *
 * @param first 颜色一（hex 或 rgb）
 * @param second 颜色二（hex 或 rgb）
 * @returns 对比度（1–21）
 */
function contrastRatio(first: string, second: string): number {
  const [lighter, darker] = [relativeLuminance(first), relativeLuminance(second)].sort(
    (a, b) => b - a,
  );
  return (lighter + 0.05) / (darker + 0.05);
}

/**
 * 断言 Tag 前景/背景对比度达到 WCAG 2.1 AA（12px 正文 4.5:1）。
 *
 * 对应 axe `color-contrast`：antd 6 filled 绿色/金色 Tag 是该问题的真实来源。
 *
 * @param tag 状态 Tag
 */
function expectAATagContrast(tag: HTMLElement): void {
  const { color, backgroundColor } = tag.style;
  expect(color).not.toBe('');
  expect(backgroundColor).not.toBe('');
  expect(contrastRatio(color, backgroundColor)).toBeGreaterThanOrEqual(4.5);
}

/**
 * 断言 danger 按钮通过 antd 6 CSS 变量覆盖为 AA 对比度。
 *
 * antd 默认 `colorError`（#ff4d4f）对白底只有 3.27:1；页面把基色/悬停/按下改为
 * red-7/8/9。jsdom 不解析 CSS 变量，因此直接读取按钮内联的自定义属性。
 *
 * @param button danger 按钮
 */
function expectAADangerButton(button: HTMLElement): void {
  for (const variable of [
    '--ant-btn-color-base',
    '--ant-btn-color-hover',
    '--ant-btn-color-active',
  ]) {
    const color = button.style.getPropertyValue(variable);
    expect(color, `${variable} 必须有高对比配色`).not.toBe('');
    expect(contrastRatio(color, '#ffffff'), `${variable} 对白底不足 4.5:1`).toBeGreaterThanOrEqual(
      4.5,
    );
  }
  expect(button.style.getPropertyValue('--ant-btn-color-base')).toBe('#cf1322');
}

/**
 * 收集容器内可键盘聚焦的元素；与抽屉实现的 Tab 循环选择器一致。
 *
 * @param container 抽屉面板
 * @returns 可聚焦元素
 */
function focusableElements(container: HTMLElement): HTMLElement[] {
  return Array.from(
    container.querySelectorAll<HTMLElement>(
      [
        'a[href]',
        'button:not([disabled])',
        'input:not([disabled]):not([type="hidden"])',
        'select:not([disabled])',
        'textarea:not([disabled])',
        '[tabindex]:not([tabindex="-1"])',
      ].join(','),
    ),
  ).filter(
    (element) => element.getAttribute('aria-hidden') !== 'true' && !element.hasAttribute('hidden'),
  );
}

describe('知识库连接器页', () => {
  beforeEach(() => {
    authStore.reset();
    authStore.setAuthenticated(TENANT_ADMIN_SESSION);
  });

  it('列表展示状态、端点、可见范围、同步结果、失败码与凭据掩码', async () => {
    const connector = makeConnector({
      status: 'ERROR',
      lastSyncStatus: 'ERROR',
      lastSyncComplete: false,
      lastErrorCode: 'KB_CONNECTOR_AUTH',
      lastSuccessAt: '2026-11-20T08:00:00+08:00',
      lastSyncSummary: {
        scanned: 5,
        created: 0,
        updated: 0,
        skipped: 1,
        failed: 1,
        deleted: 0,
        complete: false,
      },
    });
    renderPage(
      createFakeConnectorApi({ listConnectors: vi.fn(async () => connectorPage([connector])) }),
    );

    expect(await screen.findByText('销售合同归档')).toBeVisible();
    expect(screen.getByText('S3')).toBeVisible();
    expect(screen.getByText('同步失败')).toBeVisible();
    expect(screen.getByText('internal-s3')).toBeVisible();
    expect(screen.getByText('全租户')).toBeVisible();
    expect(screen.getByText(/2026-11-20 08:00:00 \+08:00/)).toBeVisible();
    expect(screen.getByText(/源端认证失败/)).toBeVisible();
    expect(screen.getByText('KB_CONNECTOR_AUTH')).toBeVisible();
    expect(screen.getByText('AKIA…7F2')).toBeVisible();
    expect(screen.getByText('否')).toBeVisible();
    expect(screen.getByText(/共 1 个连接器/)).toBeVisible();
  });

  it('状态 Tag 前景/背景达到 WCAG AA 对比度', async () => {
    renderPage(
      createFakeConnectorApi({
        listConnectors: vi.fn(async () =>
          connectorPage([
            makeConnector({
              status: 'ACTIVE',
              lastSyncStatus: 'PARTIAL',
              lastSyncComplete: false,
            }),
          ]),
        ),
      }),
    );

    expectAATagContrast(await screen.findByText('启用'));
    expectAATagContrast(await screen.findByText('部分成功'));
  });

  it('普通员工没有连接器权限时直接 403 且不请求数据', () => {
    authStore.reset();
    authStore.setAuthenticated(MEMBER_SESSION);
    const api = createFakeConnectorApi();
    renderPage(api);

    expect(screen.getByText('没有访问权限')).toBeVisible();
    expect(screen.getByText(/RICCON-AUTH-403/)).toBeVisible();
    expect(api.listConnectors).not.toHaveBeenCalled();
  });

  it('平台管理员按后端语义显示 404，不显示 403', () => {
    authStore.reset();
    authStore.setAuthenticated(PLATFORM_ADMIN_SESSION);
    const api = createFakeConnectorApi();
    renderPage(api);

    expect(screen.getByText('连接器不存在')).toBeVisible();
    expect(screen.getByText(/RICCON-TENANT-404/)).toBeVisible();
    expect(screen.queryByText('没有访问权限')).not.toBeInTheDocument();
    expect(api.listConnectors).not.toHaveBeenCalled();
  });

  it('列表 API 403/404 显示与错误码一致的页面', async () => {
    const forbidden = createFakeConnectorApi({
      listConnectors: vi.fn(async () => {
        throw new ApiError('forbidden', { status: 403, code: 'RICCON-AUTH-403' });
      }),
    });
    const { unmount } = renderPage(forbidden);
    expect(await screen.findByText('没有访问权限')).toBeVisible();
    expect(screen.getByText(/RICCON-AUTH-403/)).toBeVisible();
    unmount();

    const missing = createFakeConnectorApi({
      listConnectors: vi.fn(async () => {
        throw new ApiError('not found', { status: 404, code: 'RICCON-TENANT-404' });
      }),
    });
    renderPage(missing);
    expect(await screen.findByText('连接器不存在')).toBeVisible();
    expect(screen.getByText(/RICCON-TENANT-404/)).toBeVisible();
  });

  it('列表通用错误显示错误结果并提供重试', async () => {
    const api = createFakeConnectorApi({
      listConnectors: vi.fn(async () => {
        throw new ApiError('server error', { status: 500, code: 'RICCON-INTERNAL-500' });
      }),
    });
    renderPage(api);

    expect(await screen.findByText('连接器列表加载失败')).toBeVisible();
    expect(screen.getByRole('button', { name: /重\s*试/ })).toBeEnabled();
  });

  it('部门目录加载失败时 DEPT 表单提示重试而不阻断 TENANT', async () => {
    const departments = {
      listDepartments: vi.fn(async () => {
        throw new ApiError('server error', { status: 500, code: 'RICCON-INTERNAL-500' });
      }),
    };
    renderPage(createFakeConnectorApi(), departments);
    await screen.findByText('销售合同归档');

    fireEvent.click(screen.getByRole('button', { name: /新建连接器/ }));
    fireEvent.click(screen.getByLabelText('DEPT（仅指定部门可见）'));

    expect(await screen.findByText(/部门目录加载失败：服务暂时不可用/)).toBeInTheDocument();
    expect(departments.listDepartments).toHaveBeenCalledTimes(1);
  });

  it('只读账号保留列表与条目入口，但禁用写操作', async () => {
    authStore.reset();
    authStore.setAuthenticated(READ_ONLY_SESSION);
    renderPage(createFakeConnectorApi());

    expect(await screen.findByText('销售合同归档')).toBeVisible();
    expect(screen.getByText('只读模式')).toBeVisible();
    expect(screen.getByRole('button', { name: /新建连接器/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: '编辑 销售合同归档' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '删除 销售合同归档' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '条目 销售合同归档' })).toBeEnabled();
  });

  it('创建必须显式选择默认可见范围，缺省时前端阻断并给出 400 说明', async () => {
    const api = createFakeConnectorApi();
    renderPage(api);
    await screen.findByText('销售合同归档');

    fireEvent.click(screen.getByRole('button', { name: /新建连接器/ }));
    fireEvent.change(screen.getByLabelText('连接器名称'), { target: { value: '新连接器' } });
    fireEvent.change(screen.getByLabelText('端点 ID（endpointId）'), {
      target: { value: 'internal-s3' },
    });
    fireEvent.change(screen.getByLabelText('Bucket'), { target: { value: 'new-bucket' } });
    fireEvent.click(screen.getByRole('button', { name: '创建连接器' }));

    expect(await screen.findByText(/请显式选择 TENANT 或 DEPT/)).toBeInTheDocument();
    expect(api.createConnector).not.toHaveBeenCalled();
  });

  it('创建 DEPT 连接器时部门必选，成功后默认暂停并提示测试', async () => {
    const api = createFakeConnectorApi();
    renderPage(api);
    await screen.findByText('销售合同归档');

    fireEvent.click(screen.getByRole('button', { name: /新建连接器/ }));
    fireEvent.change(screen.getByLabelText('连接器名称'), { target: { value: '部门连接器' } });
    fireEvent.change(screen.getByLabelText('端点 ID（endpointId）'), {
      target: { value: 'internal-s3' },
    });
    fireEvent.change(screen.getByLabelText('Bucket'), { target: { value: 'dept-bucket' } });
    fireEvent.click(screen.getByLabelText('DEPT（仅指定部门可见）'));
    fireEvent.click(screen.getByRole('button', { name: '创建连接器' }));
    expect(await screen.findByText(/默认可见范围为 DEPT 时必须选择启用部门/)).toBeInTheDocument();
    expect(api.createConnector).not.toHaveBeenCalled();

    fireEvent.mouseDown(screen.getByLabelText('默认部门'));
    fireEvent.click(await screen.findByTitle('销售部（dept-sal…）'));
    fireEvent.click(screen.getByRole('button', { name: '创建连接器' }));

    await waitFor(() => expect(api.createConnector).toHaveBeenCalledTimes(1));
    expect(api.createConnector).toHaveBeenCalledWith(
      expect.objectContaining({
        name: '部门连接器',
        type: 'S3',
        endpointId: 'internal-s3',
        config: { bucket: 'dept-bucket' },
        defaultVisibility: 'DEPT',
        defaultDepartmentId: 'dept-sales',
        scheduleEnabled: false,
      }),
    );
    expect(await screen.findByText(/已创建连接器「部门连接器」（默认暂停）/)).toBeVisible();
  });

  it('重名 409 在表单内显示可执行文案', async () => {
    const api = createFakeConnectorApi({
      createConnector: vi.fn(async () => {
        throw new ApiError('connector name already exists', {
          status: 409,
          code: 'RICCON-KB-409',
        });
      }),
    });
    renderPage(api);
    await screen.findByText('销售合同归档');

    fireEvent.click(screen.getByRole('button', { name: /新建连接器/ }));
    fireEvent.change(screen.getByLabelText('连接器名称'), { target: { value: '销售合同归档' } });
    fireEvent.change(screen.getByLabelText('端点 ID（endpointId）'), {
      target: { value: 'internal-s3' },
    });
    fireEvent.change(screen.getByLabelText('Bucket'), { target: { value: 'new-bucket' } });
    fireEvent.click(screen.getByLabelText('TENANT（本租户全员可见）'));
    fireEvent.click(screen.getByRole('button', { name: '创建连接器' }));

    expect(await screen.findByText(/软删除后同名也不能重建/)).toBeInTheDocument();
    expect(screen.getByText('保存未完成')).toBeInTheDocument();
  });

  it('暂停/启用使用 PATCH 仅提交 status，并保留读取权限', async () => {
    const api = createFakeConnectorApi();
    renderPage(api);
    await screen.findByText('销售合同归档');

    fireEvent.click(screen.getByRole('button', { name: '暂停 销售合同归档' }));
    await waitFor(() =>
      expect(api.updateConnector).toHaveBeenCalledWith('connector-1', { status: 'PAUSED' }),
    );
    expect(await screen.findByText(/已暂停连接器「销售合同归档」/)).toBeVisible();
    expect(screen.getByRole('button', { name: '启用 销售合同归档' })).toBeEnabled();
  });

  it('编辑展示掩码而不回显明文，并只提交表单字段', async () => {
    const api = createFakeConnectorApi();
    renderPage(api);
    await screen.findByText('销售合同归档');

    fireEvent.click(screen.getByRole('button', { name: '编辑 销售合同归档' }));
    expect(await screen.findByText(/当前凭据：AKIA…7F2/)).toBeInTheDocument();
    expect(screen.queryByDisplayValue('TESTKEY-TEST')).not.toBeInTheDocument();
    const secretInput = screen.getByLabelText('访问密钥（secretAccessKey）') as HTMLInputElement;
    expect(secretInput.value).toBe('');

    fireEvent.change(screen.getByLabelText('连接器名称'), { target: { value: '改名后的连接器' } });
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }));

    await waitFor(() => expect(api.updateConnector).toHaveBeenCalledTimes(1));
    expect(api.updateConnector).toHaveBeenCalledWith(
      'connector-1',
      expect.objectContaining({
        name: '改名后的连接器',
        endpointId: 'internal-s3',
        config: expect.objectContaining({ bucket: 'sales-archive' }),
        defaultVisibility: 'TENANT',
        defaultDepartmentId: null,
        scheduleEnabled: true,
      }),
    );
    expect(api.updateConnector).toHaveBeenCalledWith(
      'connector-1',
      expect.not.objectContaining({ credential: expect.anything() }),
    );
  });

  it('轮换凭据时先保存配置再覆盖写入，页面与通知不出现明文', async () => {
    const api = createFakeConnectorApi();
    renderPage(api);
    await screen.findByText('销售合同归档');

    fireEvent.click(screen.getByRole('button', { name: '编辑 销售合同归档' }));
    await screen.findByText(/当前凭据：AKIA…7F2/);
    fireEvent.change(screen.getByLabelText('访问键 ID（accessKeyId）'), {
      target: { value: 'TESTKEY-NEW' },
    });
    fireEvent.change(screen.getByLabelText('访问密钥（secretAccessKey）'), {
      target: { value: 'super-secret-value' },
    });
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }));

    await waitFor(() => expect(api.putConnectorCredential).toHaveBeenCalledTimes(1));
    expect(api.putConnectorCredential).toHaveBeenCalledWith('connector-1', {
      accessKeyId: 'TESTKEY-NEW',
      secretAccessKey: 'super-secret-value',
    });
    expect(await screen.findByText(/已保存连接器「销售合同归档」并轮换凭据/)).toBeVisible();
    expect(screen.queryByText(/super-secret-value/)).not.toBeInTheDocument();
    expect(screen.queryByText(/TESTKEY-NEW/)).not.toBeInTheDocument();
  });

  it('连接测试成功显示成功原因，失败显示分类与中文原因', async () => {
    const successApi = createFakeConnectorApi();
    const { unmount } = renderPage(successApi);
    await screen.findByText('销售合同归档');
    fireEvent.click(screen.getByRole('button', { name: '测试 销售合同归档' }));
    expect(await screen.findByText(/连接测试成功/)).toBeVisible();
    unmount();

    const failureApi = createFakeConnectorApi({
      testConnector: vi.fn(async () =>
        makeTestResult({
          success: false,
          category: 'AUTH',
          message: '源端拒绝了凭据，请检查访问键与密钥是否有效。',
        }),
      ),
    });
    renderPage(failureApi);
    await screen.findByText('销售合同归档');
    fireEvent.click(screen.getByRole('button', { name: '测试 销售合同归档' }));
    expect(await screen.findByText(/连接测试失败（凭据被拒绝 · AUTH）/)).toBeVisible();
    expect(screen.getByText(/源端拒绝了凭据/)).toBeVisible();
  });

  it('手动同步 202 后标记同步中并禁用重复触发', async () => {
    const api = createFakeConnectorApi();
    renderPage(api);
    await screen.findByText('销售合同归档');

    fireEvent.click(screen.getByRole('button', { name: '同步 销售合同归档' }));

    await waitFor(() => expect(api.syncConnector).toHaveBeenCalledWith('connector-1'));
    expect(await screen.findByText(/已受理连接器「销售合同归档」的同步（202/)).toBeVisible();
    const syncingButton = screen.getByRole('button', { name: '同步中 销售合同归档' });
    expect(syncingButton).toBeDisabled();
    expect(syncingButton).toHaveTextContent('同步中');
  });

  it('并发同步 409 显示可执行提示', async () => {
    const api = createFakeConnectorApi({
      syncConnector: vi.fn(async () => {
        throw new ApiError('connector sync is already pending or running', {
          status: 409,
          code: 'RICCON-KB-409',
        });
      }),
    });
    renderPage(api);
    await screen.findByText('销售合同归档');
    fireEvent.click(screen.getByRole('button', { name: '同步 销售合同归档' }));
    expect(await screen.findByText(/已有排队或运行中的同步/)).toBeVisible();
  });

  it('条目抽屉展示五种状态与失败码，只显示对象末段', async () => {
    const items = [
      makeConnectorItem({ id: 'i1', status: 'PENDING', externalId: 'bucket/secret/a.pdf' }),
      makeConnectorItem({ id: 'i2', status: 'SYNCED', externalId: 'bucket/secret/b.pdf' }),
      makeConnectorItem({ id: 'i3', status: 'SKIPPED', externalId: 'bucket/secret/c.zip' }),
      makeConnectorItem({
        id: 'i4',
        status: 'FAILED',
        externalId: 'bucket/secret/d.pdf',
        errorCode: 'KB_CONNECTOR_OBJECT_TOO_LARGE',
      }),
      makeConnectorItem({ id: 'i5', status: 'DELETED', externalId: 'bucket/secret/e.pdf' }),
    ];
    const api = createFakeConnectorApi({
      listConnectorItems: vi.fn(async () => connectorItemPage(items)),
    });
    renderPage(api);
    await screen.findByText('销售合同归档');

    fireEvent.click(screen.getByRole('button', { name: '条目 销售合同归档' }));

    expect(await screen.findByText('同步条目：销售合同归档')).toBeVisible();
    expect(screen.getByText('待同步')).toBeVisible();
    expect(screen.getByText('已同步')).toBeVisible();
    expect(screen.getByText('已跳过')).toBeVisible();
    expect(screen.getByText('失败')).toBeVisible();
    expect(screen.getByText('已删除')).toBeVisible();
    expect(screen.getByText(/单对象超过 100 MB/)).toBeVisible();
    expect(screen.getByText('KB_CONNECTOR_OBJECT_TOO_LARGE')).toBeVisible();
    expect(screen.getByText('secret/b.pdf')).toBeVisible();
    expect(screen.queryByText('bucket/secret/b.pdf')).not.toBeInTheDocument();
    expect(screen.getByText(/扫描 12；新增 3；更新 1；跳过 2；失败 0；删除 0/)).toBeVisible();
  });

  it('条目抽屉按状态筛选并回到第一页请求', async () => {
    const api = createFakeConnectorApi();
    renderPage(api);
    await screen.findByText('销售合同归档');
    fireEvent.click(screen.getByRole('button', { name: '条目 销售合同归档' }));
    await waitFor(() => expect(api.listConnectorItems).toHaveBeenCalledTimes(1));

    fireEvent.mouseDown(screen.getByLabelText('同步条目状态筛选'));
    fireEvent.click(await screen.findByTitle('失败'));

    await waitFor(() =>
      expect(api.listConnectorItems).toHaveBeenLastCalledWith('connector-1', {
        page: 1,
        size: 10,
        status: 'FAILED',
      }),
    );
  });

  it('删除默认 KEEP，选择 SOFT_DELETE 后二次确认并传参', async () => {
    const api = createFakeConnectorApi({
      listConnectors: vi
        .fn()
        .mockResolvedValueOnce(connectorPage([makeConnector()]))
        .mockResolvedValue(connectorPage([])),
    });
    renderPage(api);
    await screen.findByText('销售合同归档');

    fireEvent.click(screen.getByRole('button', { name: '删除 销售合同归档' }));
    expect(await screen.findByText('删除影响')).toBeInTheDocument();
    expect(screen.getByLabelText('KEEP：保留已导入文档（默认）')).toBeChecked();
    fireEvent.click(screen.getByLabelText('SOFT_DELETE：一并软删除已导入文档并清理索引'));
    expect(screen.getByText('将一并删除文档')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '确认删除' }));

    await waitFor(() =>
      expect(api.deleteConnector).toHaveBeenCalledWith('connector-1', 'SOFT_DELETE'),
    );
    expect(await screen.findByText(/已软删除连接器「销售合同归档」/)).toBeVisible();
    expect(screen.queryByText('销售合同归档')).not.toBeInTheDocument();
  });

  it('分页与刷新使用服务端分页参数', async () => {
    const api = createFakeConnectorApi({
      listConnectors: vi.fn(async () =>
        connectorPage([makeConnector()], { page: 1, size: 10, total: 25, totalPages: 3 }),
      ),
    });
    renderPage(api);
    await screen.findByText('销售合同归档');
    expect(api.listConnectors).toHaveBeenCalledWith({ page: 1, size: 10 });

    fireEvent.click(within(screen.getByTitle('2')).getByText('2'));
    await waitFor(() => expect(api.listConnectors).toHaveBeenLastCalledWith({ page: 2, size: 10 }));

    fireEvent.click(screen.getByRole('button', { name: /刷新/ }));
    await waitFor(() => expect(api.listConnectors).toHaveBeenLastCalledWith({ page: 2, size: 10 }));
  });

  it('删除按钮（行内与确认弹窗）使用 WCAG AA 的高对比 danger 配色', async () => {
    renderPage(createFakeConnectorApi());
    await screen.findByText('销售合同归档');

    const rowDelete = screen.getByRole('button', { name: '删除 销售合同归档' });
    expectAADangerButton(rowDelete);

    fireEvent.click(rowDelete);
    // 确认删除使用 antd primary + danger 实心按钮，同样需要 4.5:1。
    const confirmDelete = await screen.findByRole('button', { name: '确认删除' });
    expectAADangerButton(confirmDelete);
  });

  it('条目表格的横向滚动区域可键盘聚焦并带可读名称', async () => {
    const api = createFakeConnectorApi({
      listConnectorItems: vi.fn(async () => connectorItemPage([makeConnectorItem()])),
    });
    renderPage(api);
    await screen.findByText('销售合同归档');
    fireEvent.click(screen.getByRole('button', { name: '条目 销售合同归档' }));

    const region = await screen.findByRole('region', { name: '同步条目表格（可横向滚动）' });
    expect(region).toHaveClass('ant-table-content');
    expect(region).toHaveAttribute('tabindex', '0');
    region.focus();
    expect(region).toHaveFocus();
  });

  it('列表不使用固定列，窄屏下鼠标可直接打开行操作', async () => {
    const { container } = renderPage(createFakeConnectorApi());
    await screen.findByText('销售合同归档');

    const table = container.querySelector('.connectors-table');
    expect(table).not.toBeNull();
    // 375px 下 fixed-left(210) + fixed-right(330) 会互相遮挡并拦截行按钮点击（F-003）。
    expect(table?.querySelector('.ant-table-cell-fix-left')).toBeNull();
    expect(table?.querySelector('.ant-table-cell-fix-right')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '条目 销售合同归档' }));
    expect(await screen.findByText('同步条目：销售合同归档')).toBeVisible();
  });

  it('条目抽屉 Tab 焦点循环，并在 Escape 关闭后归还触发按钮', async () => {
    renderPage(createFakeConnectorApi());
    await screen.findByText('销售合同归档');
    const trigger = screen.getByRole('button', { name: '条目 销售合同归档' });
    fireEvent.click(trigger);

    const title = await screen.findByText('同步条目：销售合同归档');
    const dialog = title.closest('[role="dialog"]');
    expect(dialog).not.toBeNull();
    const panel = dialog as HTMLElement;
    const closeButton = within(panel).getByRole('button', { name: '关闭' });
    // 打开后焦点进入抽屉，键盘用户有明确起点。
    await waitFor(() => expect(closeButton).toHaveFocus());

    const focusable = focusableElements(panel);
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    expect(first).toBe(closeButton);

    // 最后一个元素 Tab 回到第一个；第一个元素 Shift+Tab 回到最后一个。
    last.focus();
    fireEvent.keyDown(last, { key: 'Tab' });
    expect(first).toHaveFocus();
    fireEvent.keyDown(first, { key: 'Tab', shiftKey: true });
    expect(last).toHaveFocus();

    // Escape 关闭后焦点归还“条目”按钮（F-004）。
    fireEvent.keyDown(window, { key: 'Escape' });
    await waitFor(() => expect(trigger).toHaveFocus());
  });

  it('行操作按钮可访问名称与可见文字一致', async () => {
    renderPage(createFakeConnectorApi());
    await screen.findByText('销售合同归档');
    expectLabelInName(screen.getByRole('button', { name: '测试 销售合同归档' }));
    expectLabelInName(screen.getByRole('button', { name: '同步 销售合同归档' }));
    expectLabelInName(screen.getByRole('button', { name: '条目 销售合同归档' }));
    expectLabelInName(screen.getByRole('button', { name: '编辑 销售合同归档' }));
    expectLabelInName(screen.getByRole('button', { name: '暂停 销售合同归档' }));
    expectLabelInName(screen.getByRole('button', { name: '删除 销售合同归档' }));
  });
});
