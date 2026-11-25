// @vitest-environment jsdom
import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import type { SessionInfo } from '@/services/api/types';
import { authStore } from '@/services/auth/auth-store';
import { DEMO_SESSION, pageOf } from '@/test/fake-api';
import { withProviders } from '@/test/render';
import type { WorkflowTemplate, WorkflowTemplateApi } from './template-api';
import WorkflowTemplatesPage from './index';

/**
 * 模板库页面测试。
 *
 * 覆盖加载/空/错误/无权限态、内置标识与更新时间、复制成功跳转与失败提示、名称/场景过滤与重置、
 * 375px 卡片结构；真实路由与跨页面跳转由 T16a 的 E2E-M06-07 覆盖。
 */

/** 模板 ID，与 V15 种子一致。 */
const TEMPLATE_ID = '01990000-0000-7000-8000-000000000601';

/** 流程编辑者会话。 */
const EDITOR_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  user: { ...DEMO_SESSION.user, id: 'editor-1' },
  roles: ['WORKFLOW_EDITOR'],
  permissions: ['workflow.read', 'workflow.edit', 'workflow.publish', 'workflow.run'],
};

/** 只有读取权限的会话（普通员工）；能看模板库但不能复制。 */
const READER_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  roles: ['MEMBER'],
  permissions: ['workflow.read', 'workflow.run', 'workflow.approve'],
};

/** 无编排权限的会话。 */
const NO_PERMISSION_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  roles: ['MEMBER'],
  permissions: ['workspace.read'],
};

/** 内置模板夹具。 */
function makeTemplate(overrides: Partial<WorkflowTemplate> = {}): WorkflowTemplate {
  return {
    id: TEMPLATE_ID,
    name: '市场调研报告',
    scenario: '市场调研',
    description: '检索知识库并调用 1 个 MCP 数据工具，经人工审批后生成带引用的报告',
    source: 'BUILT_IN',
    visibility: 'BUILT_IN',
    builtInVersion: 1,
    upgradeAvailable: false,
    updatedAt: '2026-11-25T08:00:00+08:00',
    ...overrides,
  };
}

/** 构造模板 API 替身。 */
function createFakeApi(overrides: Partial<WorkflowTemplateApi> = {}): WorkflowTemplateApi {
  return {
    listTemplates: vi.fn(async () => pageOf<WorkflowTemplate>([makeTemplate()])),
    copyTemplate: vi.fn(async () => ({ id: 'draft-1', name: '市场调研报告' })),
    ...overrides,
  };
}

/**
 * 渲染模板库页面。
 *
 * @param api API 替身
 * @param navigate 导航替身
 * @param session 会话
 * @returns 导航替身与渲染结果
 */
function renderPage(
  api: WorkflowTemplateApi,
  navigate = vi.fn(),
  session: SessionInfo = EDITOR_SESSION,
) {
  authStore.setAuthenticated(session);
  return {
    navigate,
    ...render(withProviders(<WorkflowTemplatesPage api={api} navigate={navigate} />)),
  };
}

/**
 * 解析 rgb()/hex 颜色为 WCAG 相对亮度。
 *
 * @param color 内联样式颜色
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
 * 断言 Tag 前景/背景对比度达到 WCAG 2.1 AA 4.5:1。
 *
 * @param tag 状态 Tag
 */
function expectAATagContrast(tag: HTMLElement): void {
  expect(tag.style.color).not.toBe('');
  expect(tag.style.backgroundColor).not.toBe('');
  const [lighter, darker] = [
    relativeLuminance(tag.style.color),
    relativeLuminance(tag.style.backgroundColor),
  ].sort((left, right) => right - left);
  expect((lighter + 0.05) / (darker + 0.05)).toBeGreaterThanOrEqual(4.5);
}

describe('模板库页面', () => {
  beforeEach(() => {
    authStore.reset();
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1024 });
  });

  it('加载后展示名称、场景、内置标识与最近更新时间', async () => {
    const api = createFakeApi();
    renderPage(api);

    expect(await screen.findByRole('heading', { name: '市场调研报告', level: 2 })).toBeVisible();
    expect(screen.getByText('内置模板')).toBeVisible();
    expect(screen.getByText('市场调研')).toBeVisible();
    expect(screen.getByText(/内置版本 v1 · 更新于 2026-11-25 08:00:00 \+08:00/)).toBeVisible();
    expect(screen.getByText(/检索知识库并调用 1 个 MCP 数据工具/)).toBeVisible();
    expect(screen.getByRole('button', { name: /复制模板：市场调研报告/ })).toBeEnabled();
    expect(api.listTemplates).toHaveBeenCalledWith({ page: 1, size: 20 });
  });

  it('空态显示说明与刷新入口', async () => {
    const api = createFakeApi({ listTemplates: vi.fn(async () => pageOf<WorkflowTemplate>([])) });
    renderPage(api);

    expect(
      await screen.findByText('暂无可用模板。内置模板由平台统一维护，请联系租户管理员。'),
    ).toBeVisible();
    expect(screen.getByRole('button', { name: '清空筛选' })).toBeVisible();
    expect(screen.getAllByRole('button', { name: /刷\s*新/ })).toHaveLength(2);
  });

  it('错误态展示中文说明并可重试', async () => {
    const listTemplates = vi
      .fn<WorkflowTemplateApi['listTemplates']>()
      .mockRejectedValueOnce(
        new ApiError('服务暂不可用', { status: 500, code: 'RICCON-INTERNAL-500' }),
      )
      .mockResolvedValueOnce(pageOf<WorkflowTemplate>([makeTemplate()]));
    const api = createFakeApi({ listTemplates });
    renderPage(api);

    expect(await screen.findByText('模板库加载失败')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '重试' }));

    expect(await screen.findByRole('heading', { name: '市场调研报告', level: 2 })).toBeVisible();
    expect(listTemplates).toHaveBeenCalledTimes(2);
  });

  it('无读取权限时不请求接口并显示 403 说明', async () => {
    const api = createFakeApi();
    renderPage(api, vi.fn(), NO_PERMISSION_SESSION);

    expect(await screen.findByText('没有访问权限')).toBeVisible();
    expect(api.listTemplates).not.toHaveBeenCalled();
  });

  it('只有读取权限时复制按钮禁用并提示申请权限', async () => {
    const api = createFakeApi();
    renderPage(api, vi.fn(), READER_SESSION);

    expect(
      await screen.findByText('你没有编排权限，无法复制模板；请联系租户管理员申请。'),
    ).toBeVisible();
    expect(screen.getByRole('button', { name: /复制模板：市场调研报告/ })).toBeDisabled();
  });

  it('复制成功提示不改源模板并跳转编辑器', async () => {
    const api = createFakeApi();
    const { navigate } = renderPage(api);

    fireEvent.click(await screen.findByRole('button', { name: /复制模板：市场调研报告/ }));

    expect(api.copyTemplate).toHaveBeenCalledWith(TEMPLATE_ID);
    // antd message 在 jsdom 中以动画容器渲染，可见性由浏览器动画决定；这里断言提示文本已进入 DOM。
    expect(
      await screen.findByText('已复制“市场调研报告”，模板本身不会被修改。'),
    ).toBeInTheDocument();
    expect(navigate).toHaveBeenCalledWith('/workflows/draft-1/edit');
  });

  it('复制 403 时显示申请权限提示且不跳转', async () => {
    const api = createFakeApi({
      copyTemplate: vi.fn(async () => {
        throw new ApiError('无权限', { status: 403, code: 'RICCON-AUTH-403' });
      }),
    });
    const { navigate } = renderPage(api);

    fireEvent.click(await screen.findByRole('button', { name: /复制模板：市场调研报告/ }));

    expect(
      await screen.findByText('你没有编排权限，无法复制模板，请联系租户管理员申请。'),
    ).toBeVisible();
    expect(navigate).not.toHaveBeenCalled();
  });

  it('按名称与场景查询并支持重置', async () => {
    const api = createFakeApi();
    renderPage(api);

    fireEvent.change(await screen.findByLabelText('模板名称'), { target: { value: ' 调研 ' } });
    fireEvent.change(screen.getByLabelText('适用场景'), { target: { value: '市场调研' } });
    fireEvent.click(screen.getByRole('button', { name: /查\s*询/ }));

    expect(api.listTemplates).toHaveBeenLastCalledWith({
      name: '调研',
      scenario: '市场调研',
      page: 1,
      size: 20,
    });

    fireEvent.click(screen.getByRole('button', { name: /重\s*置/ }));
    expect(api.listTemplates).toHaveBeenLastCalledWith({ page: 1, size: 20 });
    expect((screen.getByLabelText('模板名称') as HTMLInputElement).value).toBe('');
    expect((screen.getByLabelText('适用场景') as HTMLInputElement).value).toBe('');
  });

  it('375px 使用卡片布局且复制按钮可达', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 375 });
    const api = createFakeApi();
    renderPage(api);

    expect(await screen.findByTestId('workflow-template-list')).toBeVisible();
    expect(screen.getByTestId('workflow-templates-page')).toBeVisible();
    expect(screen.getByRole('button', { name: /复制模板：市场调研报告/ })).toBeVisible();
  });

  it('内置模板 Tag 达到 AA 对比度，复制按钮名称包含可见文本', async () => {
    const api = createFakeApi();
    renderPage(api);

    const tag = await screen.findByText('内置模板');
    expect(tag).toHaveClass('ant-tag-blue');
    expectAATagContrast(tag);

    const copy = screen.getByRole('button', { name: /复制模板：市场调研报告/ });
    const visibleText = (copy.textContent ?? '').trim();
    expect(visibleText).toBe('复制为草稿');
    expect(copy.getAttribute('aria-label')).toContain(visibleText);
  });
});
