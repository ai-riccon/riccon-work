// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import type { Department, SessionInfo } from '@/services/api/types';
import { authStore } from '@/services/auth/auth-store';
import { DEMO_SESSION, pageOf } from '@/test/fake-api';
import { withProviders } from '@/test/render';
import type {
  WorkflowApi,
  WorkflowDefinitionDetail,
  WorkflowGraph,
  WorkflowValidationResult,
  WorkflowVersion,
} from '../list/workflow-api';
import WorkflowEditorPage from './index';

/**
 * 流程编辑器测试。
 *
 * 覆盖表单主路径、保存前规范化（F-003）、服务端校验 panel、409 另存副本、
 * 发布成功/400、只读/无权限/关闭/不存在状态、目录降级与 375px/键盘基线。
 */

/** 流程编辑者会话：可发布“仅自己/本部门”，不具备“本租户”范围。 */
const EDITOR_SESSION: SessionInfo = {
  ...DEMO_SESSION,
  user: { ...DEMO_SESSION.user, id: 'editor-1', displayName: '李强' },
  roles: ['WORKFLOW_EDITOR'],
  permissions: [
    'workflow.read',
    'workflow.edit',
    'workflow.publish',
    'workflow.run',
    'workflow.approve',
    'department.read',
    'model.read',
    'mcp.read',
  ],
};

/** 租户管理员会话：可发布“仅自己/本租户”，不能发布“本部门”（与 WorkflowPublishScopeValidator 同口径）。 */
const TENANT_ADMIN_SESSION: SessionInfo = {
  ...EDITOR_SESSION,
  roles: ['TENANT_ADMIN'],
  permissions: [...EDITOR_SESSION.permissions, 'workflow.manage'],
};

/** 最小有效图。 */
const VALID_GRAPH: WorkflowGraph = {
  schemaVersion: 1,
  nodes: [
    { id: 'start', type: 'START', name: '开始', config: {}, position: { x: 0, y: 0 } },
    {
      id: 'retrieval-1',
      type: 'RETRIEVAL',
      name: '检索知识库',
      config: { queryTemplate: '{{input.topic}}', topK: 5 },
      position: { x: 220, y: 0 },
    },
    {
      id: 'end',
      type: 'END',
      name: '结束',
      config: { resultFrom: 'retrieval-1', resultTitle: '市场调研报告' },
      position: { x: 440, y: 0 },
    },
  ],
  edges: [
    { id: 'e1', source: 'start', target: 'retrieval-1' },
    { id: 'e2', source: 'retrieval-1', target: 'end' },
  ],
};

/**
 * V15 内置 `market-research` 模板的图形态；`graph_json` 中没有任何 position。
 *
 * 用于验证“模板创建 → 编辑器加载 → 画布”主路径不会把节点规范化到 (0,0)，
 * 且保存往返不会凭空写入坐标（REV-TASK-0231-001 F-002）。
 */
const TEMPLATE_GRAPH: WorkflowGraph = {
  schemaVersion: 1,
  nodes: [
    { id: 'start', type: 'START', name: '开始', config: {} },
    {
      id: 'retrieval',
      type: 'RETRIEVAL',
      name: '检索知识库',
      config: { queryTemplate: '{{input.topic}}', topK: 5 },
    },
    {
      id: 'draft',
      type: 'MODEL',
      name: '生成调研草稿',
      config: {
        promptTemplate: '根据检索结果整理市场调研要点：{{input.topic}}',
        outputKind: 'TEXT',
      },
    },
    {
      id: 'approval',
      type: 'APPROVAL',
      name: '审批取数工具',
      config: {
        assigneeType: 'ROLE',
        assigneeRole: 'TENANT_ADMIN',
        actionKind: 'MCP_TOOL',
        targetNodeKey: 'tool',
      },
    },
    {
      id: 'tool',
      type: 'MCP_TOOL',
      name: '获取市场数据',
      config: { toolId: null, argsTemplate: { query: '{{input.topic}}' } },
    },
    {
      id: 'report',
      type: 'MODEL',
      name: '生成调研报告',
      config: {
        promptTemplate: '整合草稿与工具结果，输出带引用的市场调研报告',
        outputKind: 'TEXT',
      },
    },
    {
      id: 'end',
      type: 'END',
      name: '结束',
      config: { resultFrom: 'report', resultTitle: '市场调研报告' },
    },
  ],
  edges: [
    { id: 'e1', source: 'start', target: 'retrieval' },
    { id: 'e2', source: 'retrieval', target: 'draft' },
    { id: 'e3', source: 'draft', target: 'approval' },
    { id: 'e4', source: 'approval', target: 'tool' },
    { id: 'e5', source: 'tool', target: 'report' },
    { id: 'e6', source: 'report', target: 'end' },
  ],
};

/** 流程详情。 */
function makeDetail(overrides: Partial<WorkflowDefinitionDetail> = {}): WorkflowDefinitionDetail {
  return {
    id: 'workflow-1',
    name: '销售部市场调研',
    description: '检索知识库并生成报告',
    ownerUserId: 'editor-1',
    status: 'ACTIVE',
    draftVersion: 1,
    publishedVersionId: null,
    publishedVersionNo: null,
    hasUnpublishedChanges: true,
    createdAt: '2026-11-25T08:00:00+08:00',
    updatedAt: '2026-11-25T08:00:00+08:00',
    draftGraph: VALID_GRAPH,
    latestVersion: null,
    canEdit: true,
    canPublish: true,
    canRun: false,
    ...overrides,
  };
}

/** 发布版本。 */
function makeVersion(overrides: Partial<WorkflowVersion> = {}): WorkflowVersion {
  return {
    id: 'version-1',
    workflowId: 'workflow-1',
    versionNo: 1,
    runScope: 'ONLY_SELF',
    graphSha256: 'a'.repeat(64),
    nodeCount: 3,
    publishedBy: 'editor-1',
    publishedAt: '2026-11-25T09:00:00+08:00',
    ...overrides,
  };
}

/** 部门。 */
const DEPARTMENT: Department = {
  id: 'dept-1',
  name: '销售部',
  status: 'ACTIVE',
  managerUserId: 'editor-1',
  managerDisplayName: '李强',
  memberCount: 8,
  createdAt: '2026-11-25T08:00:00+08:00',
  updatedAt: '2026-11-25T08:00:00+08:00',
};

/** 构造编辑器 API 替身。 */
function createFakeApi(overrides: Partial<WorkflowApi> = {}): WorkflowApi {
  return {
    listWorkflows: vi.fn(async () => pageOf([])),
    createWorkflow: vi.fn(async () => makeDetail()),
    getWorkflow: vi.fn(async () => makeDetail()),
    saveDraft: vi.fn(async (_id, input) => ({
      ...makeDetail(),
      draftVersion: input.version + 1,
      name: input.name ?? '销售部市场调研',
      draftGraph: input.graph,
    })),
    duplicateWorkflow: vi.fn(async () => makeDetail({ id: 'copy-1' })),
    validateWorkflow: vi.fn(async () => ({ valid: true, issues: [] })),
    publishWorkflow: vi.fn(async () => makeVersion()),
    listModels: vi.fn(async () => pageOf([])),
    listMcpTools: vi.fn(async () => pageOf([])),
    listDepartments: vi.fn(async () => pageOf([DEPARTMENT])),
    ...overrides,
  };
}

/** 渲染编辑器。 */
function renderEditor(api: WorkflowApi, navigate = vi.fn(), session = EDITOR_SESSION) {
  authStore.setAuthenticated(session);
  return {
    navigate,
    ...render(
      withProviders(<WorkflowEditorPage api={api} workflowId="workflow-1" navigate={navigate} />),
    ),
  };
}

/**
 * 让画布断点跟随 jsdom 的 innerWidth。
 *
 * 画布只查询 `(min-width: 768px)`；其余查询保持 false，避免与 antd 默认行为互相影响。
 */
function installMatchMedia(): void {
  window.matchMedia = ((query: string) => ({
    matches: query === '(min-width: 768px)' ? window.innerWidth >= 768 : false,
    media: query,
    onchange: null,
    addListener: () => undefined,
    removeListener: () => undefined,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia;
}

/** 等待编辑器主区域出现。 */
async function waitForEditor(): Promise<void> {
  expect(await screen.findByRole('heading', { name: '销售部市场调研' })).toBeVisible();
  expect(screen.getByRole('button', { name: '开始' })).toBeVisible();
  const edgeList = screen.getByRole('list', { name: '流程连线列表' });
  expect(within(edgeList).getByText('开始 → 检索知识库')).toBeVisible();
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

describe('流程编辑器', () => {
  beforeEach(() => {
    authStore.reset();
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1024 });
    installMatchMedia();
  });
  it('表单为主路径，画布作为增强视图展示同一张图', async () => {
    const api = createFakeApi();
    renderEditor(api);
    await waitForEditor();

    expect(screen.getByRole('button', { name: '检索知识库' })).toBeVisible();
    const pageEdges = within(screen.getByRole('list', { name: '流程连线列表' }));
    expect(pageEdges.getByText('检索知识库 → 结束')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '检索知识库' }));
    expect(await screen.findByText('知识库检索 · retrieval-1')).toBeVisible();

    // 画布只读展示同一张图，不是唯一入口；表单列表与连线表仍在页面中。
    const canvas = screen.getByTestId('workflow-canvas');
    expect(
      within(canvas).getByRole('button', { name: '检索知识库，知识库检索，已配置' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('list', { name: '流程节点列表' })).toBeVisible();
    expect(screen.getByRole('list', { name: '流程连线列表' })).toBeVisible();
    expect(api.getWorkflow).toHaveBeenCalledWith('workflow-1');
  });

  it('画布选中节点同步表单，键盘可回到表单继续配置与连线', async () => {
    const api = createFakeApi();
    renderEditor(api);
    await waitForEditor();

    const canvas = screen.getByTestId('workflow-canvas');
    const retrievalNode = within(canvas).getByRole('button', {
      name: '检索知识库，知识库检索，已配置',
    });
    retrievalNode.focus();
    expect(retrievalNode).toHaveFocus();
    fireEvent.keyDown(retrievalNode, { key: 'Enter' });
    expect(await screen.findByText('知识库检索 · retrieval-1')).toBeVisible();

    fireEvent.click(within(canvas).getByRole('button', { name: '在表单中编辑选中节点' }));
    expect(screen.getByLabelText('节点名称')).toHaveFocus();

    fireEvent.click(within(canvas).getByRole('button', { name: '在表单中编辑连线' }));
    expect(screen.getByRole('combobox', { name: '连线源节点' })).toHaveFocus();
  });

  it('修改节点配置后保存草稿：位置 x/y 完整、空字段被省略（F-003）', async () => {
    const saveDraft = vi.fn<WorkflowApi['saveDraft']>(async (_id, input) => ({
      ...makeDetail(),
      draftVersion: input.version + 1,
      name: input.name ?? '销售部市场调研',
      draftGraph: input.graph,
    }));
    const api = createFakeApi({ saveDraft });
    renderEditor(api);
    await waitForEditor();

    fireEvent.change(screen.getByLabelText('流程名称'), { target: { value: '  新流程名  ' } });
    fireEvent.click(screen.getByRole('button', { name: '检索知识库' }));
    fireEvent.change(await screen.findByLabelText('查询模板'), {
      target: { value: '  销售部主题  ' },
    });
    fireEvent.click(screen.getByRole('button', { name: '保存节点配置' }));
    fireEvent.click(screen.getByRole('button', { name: '保存草稿' }));

    await waitFor(() => {
      expect(saveDraft).toHaveBeenCalledTimes(1);
    });
    const [workflowId, payload] = saveDraft.mock.calls[0];
    expect(workflowId).toBe('workflow-1');
    expect(payload.version).toBe(1);
    expect(payload.name).toBe('新流程名');
    const retrieval = payload.graph.nodes.find((node) => node.id === 'retrieval-1');
    expect(retrieval?.config.queryTemplate).toBe('销售部主题');
    expect(retrieval?.config).not.toHaveProperty('promptTemplate');
    expect(retrieval?.position).toEqual({ x: 220, y: 0 });
    expect(typeof retrieval?.position?.x).toBe('number');
    expect(await screen.findByText('草稿已保存（版本 v2）。')).toBeVisible();
  });

  it('无坐标模板经编辑器加载走画布网格回退，保存保留缺失坐标（REV-TASK-0231-001 F-002）', async () => {
    const saveDraft = vi.fn<WorkflowApi['saveDraft']>(async (_id, input) => ({
      ...makeDetail(),
      draftVersion: input.version + 1,
      name: input.name ?? '销售部市场调研',
      draftGraph: input.graph,
    }));
    const api = createFakeApi({
      getWorkflow: vi.fn(async () => makeDetail({ draftGraph: TEMPLATE_GRAPH })),
      saveDraft,
    });
    renderEditor(api);
    await waitForEditor();

    const canvas = screen.getByTestId('workflow-canvas');
    const boxCoordinates = (): string[] =>
      Array.from(canvas.querySelectorAll('foreignObject')).map(
        (box) => `${box.getAttribute('x')},${box.getAttribute('y')}`,
      );
    expect(boxCoordinates()).toHaveLength(TEMPLATE_GRAPH.nodes.length);
    expect(new Set(boxCoordinates()).size).toBe(TEMPLATE_GRAPH.nodes.length);

    // 内置模板故意不预填工具：按真实用户路径补选一个已授权工具后，客户端校验才允许保存。
    const allowedToolId = '01990000-0000-7000-8000-000000000601';
    fireEvent.click(screen.getByRole('button', { name: '获取市场数据' }));
    fireEvent.change(await screen.findByLabelText('工具 ID'), {
      target: { value: allowedToolId },
    });
    fireEvent.click(screen.getByRole('button', { name: '保存节点配置' }));

    fireEvent.change(screen.getByLabelText('流程名称'), { target: { value: '模板副本' } });
    fireEvent.click(screen.getByRole('button', { name: '保存草稿' }));
    await waitFor(() => {
      expect(saveDraft).toHaveBeenCalledTimes(1);
    });
    const payload = saveDraft.mock.calls[0][1];
    expect(payload.graph.nodes.every((node) => !('position' in node))).toBe(true);
    expect(await screen.findByText('草稿已保存（版本 v2）。')).toBeVisible();

    // 保存响应回填后再规范化，缺失坐标仍保持缺失，画布继续使用网格回退而不是叠到 (0,0)。
    expect(boxCoordinates()).toHaveLength(TEMPLATE_GRAPH.nodes.length);
    expect(new Set(boxCoordinates()).size).toBe(TEMPLATE_GRAPH.nodes.length);
  });

  it('校验面板展示服务端问题计数、中文文案并可定位节点', async () => {
    const invalid: WorkflowValidationResult = {
      valid: false,
      issues: [
        {
          code: 'REQUIRED_CONFIG',
          nodeId: 'retrieval-1',
          field: 'config.topK',
          message: 'RETRIEVAL node requires topK',
        },
      ],
    };
    const api = createFakeApi({ validateWorkflow: vi.fn(async () => invalid) });
    renderEditor(api);
    await waitForEditor();

    fireEvent.click(screen.getByRole('button', { name: '校验' }));
    expect(await screen.findByText('流程还有 1 个问题，修正后才能发布。')).toBeVisible();
    expect(screen.getByText('节点缺少必填配置，请补充。（检索命中数）')).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: /定位到/ }));
    expect(await screen.findByText('知识库检索 · retrieval-1')).toBeVisible();
  });

  it('服务端校验通过时显示成功提示', async () => {
    const api = createFakeApi();
    renderEditor(api);
    await waitForEditor();
    fireEvent.click(screen.getByRole('button', { name: '校验' }));
    expect(await screen.findByText('校验通过，可以保存或发布。')).toBeVisible();
  });

  it('409 冲突提供另存副本，副本保存本地图后跳转', async () => {
    const duplicateWorkflow = vi.fn(async () => makeDetail({ id: 'copy-1' }));
    const saveDraft = vi
      .fn<WorkflowApi['saveDraft']>()
      .mockRejectedValueOnce(
        new ApiError('draft version does not match', {
          status: 409,
          code: 'RICCON-WF-409',
        }),
      )
      .mockImplementationOnce(async (_id, input) => ({
        ...makeDetail({ id: 'copy-1' }),
        draftVersion: input.version + 1,
        draftGraph: input.graph,
      }));
    const api = createFakeApi({ duplicateWorkflow, saveDraft });
    const { navigate } = renderEditor(api);
    await waitForEditor();

    fireEvent.change(screen.getByLabelText('流程名称'), { target: { value: '冲突后的流程' } });
    fireEvent.click(screen.getByRole('button', { name: '保存草稿' }));
    expect(await screen.findByText('草稿已被其他会话修改')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '另存副本' }));
    await waitFor(() => {
      expect(navigate).toHaveBeenCalledWith('/workflows/copy-1/edit');
    });
    expect(duplicateWorkflow).toHaveBeenCalledWith('workflow-1', {
      name: '冲突后的流程 副本',
    });
    expect(saveDraft).toHaveBeenLastCalledWith(
      'copy-1',
      expect.objectContaining({ version: 1, name: '冲突后的流程 副本' }),
    );
  });

  it('发布调用 validate 后 publish，并提示版本与范围', async () => {
    const validateWorkflow = vi.fn(async () => ({ valid: true, issues: [] }));
    const publishWorkflow = vi.fn(async () => makeVersion());
    const api = createFakeApi({ validateWorkflow, publishWorkflow });
    renderEditor(api);
    await waitForEditor();

    fireEvent.click(screen.getByRole('button', { name: '发布' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: '发布' }));

    await waitFor(() => {
      expect(publishWorkflow).toHaveBeenCalledWith('workflow-1', {
        runScope: 'ONLY_SELF',
        departmentId: undefined,
      });
    });
    expect(await screen.findByText('发布成功：v1（仅自己）。')).toBeVisible();
  });

  it('发布 400 在弹窗内展示失败原因', async () => {
    const publishWorkflow = vi.fn(async () => {
      throw new ApiError('workflow validation failed with 1 issue(s)', {
        status: 400,
        code: 'RICCON-WF-400',
        retryable: false,
      });
    });
    const api = createFakeApi({ publishWorkflow });
    renderEditor(api);
    await waitForEditor();

    fireEvent.click(screen.getByRole('button', { name: '发布' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: '发布' }));

    expect(
      await screen.findByText('发布失败（400）：workflow validation failed with 1 issue(s)'),
    ).toBeInTheDocument();
  });

  it('目录加载失败时降级为手动填写 UUID', async () => {
    const api = createFakeApi({
      listModels: vi.fn(async () => {
        throw new ApiError('forbidden', { status: 403, code: 'RICCON-AUTH-403' });
      }),
      listMcpTools: vi.fn(async () => {
        throw new ApiError('forbidden', { status: 403, code: 'RICCON-AUTH-403' });
      }),
    });
    renderEditor(api);
    await waitForEditor();
    expect(
      await screen.findByText('模型与工具目录加载失败：可直接填写 UUID，或刷新页面重试。'),
    ).toBeVisible();
  });

  it('添加节点、调整顺序、删除并重新添加连线', async () => {
    const api = createFakeApi();
    renderEditor(api);
    await waitForEditor();

    fireEvent.mouseDown(screen.getByRole('combobox', { name: '节点类型' }));
    fireEvent.click(await screen.findByText('人工审批'));
    fireEvent.click(screen.getByRole('button', { name: '添加节点' }));
    expect(await screen.findByText('人工审批 · approval-1')).toBeVisible();

    fireEvent.click(screen.getByRole('button', { name: '上移节点 检索知识库' }));
    const nodeItems = within(screen.getByRole('list', { name: '流程节点列表' })).getAllByRole(
      'listitem',
    );
    expect(nodeItems[0].textContent).toContain('检索知识库');

    fireEvent.click(screen.getByRole('button', { name: '删除连线 start 到 retrieval-1' }));
    expect(screen.queryByText('开始 → 检索知识库')).toBeNull();

    fireEvent.mouseDown(screen.getByRole('combobox', { name: '连线源节点' }));
    const sourceOptions = await screen.findAllByText('开始（开始）');
    fireEvent.click(sourceOptions[sourceOptions.length - 1]);
    fireEvent.mouseDown(screen.getByRole('combobox', { name: '连线目标节点' }));
    const targetOptions = await screen.findAllByText('检索知识库（知识库检索）');
    fireEvent.click(targetOptions[targetOptions.length - 1]);
    fireEvent.click(screen.getByRole('button', { name: '添加连线' }));
    await waitFor(() => {
      expect(
        within(screen.getByRole('list', { name: '流程连线列表' })).getByText('开始 → 检索知识库'),
      ).toBeVisible();
    });
  });

  it('选择本部门后自动选中负责的部门并按该范围校验（F-005）', async () => {
    const validateWorkflow = vi.fn(async () => ({ valid: true, issues: [] }));
    const api = createFakeApi({ validateWorkflow });
    renderEditor(api);
    await waitForEditor();

    // 非租户管理员只能发布“仅自己/本部门”，本租户范围必须与后端口径一致地禁用。
    expect(screen.getByRole('radio', { name: '本租户' })).toBeDisabled();
    await waitFor(() => {
      expect(screen.getByRole('radio', { name: '本部门' })).toBeEnabled();
    });
    fireEvent.click(screen.getByRole('radio', { name: '本部门' }));
    expect(await screen.findByRole('combobox', { name: '发布部门' })).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '校验' }));
    await waitFor(() => {
      expect(validateWorkflow).toHaveBeenCalledWith('workflow-1', {
        runScope: 'DEPARTMENT',
        departmentId: 'dept-1',
      });
    });
  });

  it('REV F-001：部门目录未加载时保留最近发布的本部门范围并二次确认', async () => {
    const never = new Promise<never>(() => undefined);
    const validateWorkflow = vi.fn(async () => ({ valid: true, issues: [] }));
    const publishWorkflow = vi.fn(async () =>
      makeVersion({ runScope: 'DEPARTMENT', departmentId: 'dept-1' }),
    );
    const api = createFakeApi({
      listDepartments: vi.fn(() => never),
      getWorkflow: vi.fn(async () =>
        makeDetail({
          latestVersion: makeVersion({ runScope: 'DEPARTMENT', departmentId: 'dept-1' }),
        }),
      ),
      validateWorkflow,
      publishWorkflow,
    });
    renderEditor(api);
    await waitForEditor();

    // 目录阻塞时不得把最近发布的本部门范围重置为“仅自己”。
    expect(await screen.findByRole('radio', { name: '本部门' })).toBeChecked();
    expect(screen.getByRole('radio', { name: '仅自己' })).not.toBeChecked();
    expect(
      await screen.findByText(
        '部门目录仍在加载；已保留最近发布版本的本部门范围，发布前会再次确认。',
      ),
    ).toBeVisible();

    fireEvent.click(screen.getByRole('button', { name: '发布' }));
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('本部门（dept-1）');
    expect(dialog).toHaveTextContent('发布后该部门的成员可以运行该版本');
    fireEvent.click(within(dialog).getByRole('button', { name: '发布' }));

    await waitFor(() => {
      expect(publishWorkflow).toHaveBeenCalledWith('workflow-1', {
        runScope: 'DEPARTMENT',
        departmentId: 'dept-1',
      });
    });
  });

  it('REV F-005：租户管理员恢复本部门范围时显式阻断，不静默改成仅自己', async () => {
    const validateWorkflow = vi.fn(async () => ({ valid: true, issues: [] }));
    const api = createFakeApi({
      getWorkflow: vi.fn(async () =>
        makeDetail({
          latestVersion: makeVersion({ runScope: 'DEPARTMENT', departmentId: 'dept-1' }),
        }),
      ),
      validateWorkflow,
    });
    renderEditor(api, vi.fn(), TENANT_ADMIN_SESSION);
    await waitForEditor();

    const departmentRadio = screen.getByRole('radio', { name: '本部门' });
    expect(departmentRadio).toBeChecked();
    expect(departmentRadio).toBeDisabled();
    expect(
      (await screen.findAllByText('租户管理员不能发布“本部门”范围，请改选“仅自己”或“本租户”。'))
        .length,
    ).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: '发布' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '发布版本' })).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: '校验' }));
    await waitFor(() => expect(validateWorkflow).not.toHaveBeenCalled());
  });

  it('REV F-005：部门目录失败显示告警并可重试，不静默忽略', async () => {
    const listDepartments = vi
      .fn<WorkflowApi['listDepartments']>()
      .mockRejectedValueOnce(new ApiError('forbidden', { status: 403, code: 'RICCON-AUTH-403' }))
      .mockResolvedValueOnce(pageOf([DEPARTMENT]));
    const api = createFakeApi({ listDepartments });
    renderEditor(api);
    await waitForEditor();

    expect(await screen.findByText('部门目录加载失败')).toBeVisible();
    expect(screen.getByText(/没有部门读取权限（department\.read）/)).toBeVisible();
    fireEvent.click(screen.getByRole('button', { name: '重试加载' }));
    await waitFor(() => expect(listDepartments).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.queryByText('部门目录加载失败')).toBeNull());
    await waitFor(() => expect(screen.getByRole('radio', { name: '本部门' })).toBeEnabled());
  });

  it('REV F-002：另存副本名称不超过 200 字符', async () => {
    const duplicateWorkflow = vi.fn<WorkflowApi['duplicateWorkflow']>(async () =>
      makeDetail({ id: 'copy-1' }),
    );
    const saveDraft = vi
      .fn<WorkflowApi['saveDraft']>()
      .mockRejectedValueOnce(
        new ApiError('draft version does not match', {
          status: 409,
          code: 'RICCON-WF-409',
        }),
      )
      .mockImplementationOnce(async (_id, input) => ({
        ...makeDetail({ id: 'copy-1' }),
        draftVersion: input.version + 1,
        name: input.name ?? '副本',
        draftGraph: input.graph,
      }));
    const api = createFakeApi({ duplicateWorkflow, saveDraft });
    renderEditor(api);
    await waitForEditor();

    fireEvent.change(screen.getByLabelText('流程名称'), {
      target: { value: 'a'.repeat(200) },
    });
    fireEvent.click(screen.getByRole('button', { name: '保存草稿' }));
    fireEvent.click(await screen.findByRole('button', { name: '另存副本' }));

    await waitFor(() => expect(duplicateWorkflow).toHaveBeenCalledTimes(1));
    const copyName = duplicateWorkflow.mock.calls[0][1]?.name;
    expect(copyName).toHaveLength(200);
    expect(copyName?.endsWith(' 副本')).toBe(true);
    await waitFor(() => expect(saveDraft).toHaveBeenCalledTimes(2));
    expect(saveDraft.mock.calls[1][1].name).toHaveLength(200);
  });

  it('发布前校验失败会阻止 publish 并展示问题计数', async () => {
    const validation: WorkflowValidationResult = {
      valid: false,
      issues: [
        {
          code: 'NO_APPROVER',
          nodeId: 'retrieval-1',
          field: 'config.assigneeRole',
          message: 'approval node requires an approver',
        },
      ],
    };
    const validateWorkflow = vi.fn(async () => validation);
    const publishWorkflow = vi.fn(async () => makeVersion());
    const api = createFakeApi({ validateWorkflow, publishWorkflow });
    renderEditor(api);
    await waitForEditor();

    fireEvent.click(screen.getByRole('button', { name: '发布' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: '发布' }));

    expect(
      (await screen.findAllByText('流程还有 1 个问题，修正后才能发布。')).length,
    ).toBeGreaterThan(0);
    expect(publishWorkflow).not.toHaveBeenCalled();
  });

  it('409 后可放弃本地修改并重新加载服务端草稿', async () => {
    const saveDraft = vi.fn(async () => {
      throw new ApiError('draft version does not match', {
        status: 409,
        code: 'RICCON-WF-409',
      });
    });
    const getWorkflow = vi.fn(async () => makeDetail());
    const api = createFakeApi({ saveDraft, getWorkflow });
    renderEditor(api);
    await waitForEditor();

    fireEvent.change(screen.getByLabelText('流程名称'), { target: { value: '本地修改' } });
    fireEvent.click(screen.getByRole('button', { name: '保存草稿' }));
    expect(await screen.findByText('草稿已被其他会话修改')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '放弃本地修改并加载最新草稿' }));

    await waitFor(() => {
      expect(getWorkflow).toHaveBeenCalledTimes(2);
    });
    expect((screen.getByLabelText('流程名称') as HTMLInputElement).value).toBe('销售部市场调研');
  });

  it('只读流程不渲染编辑入口', async () => {
    const api = createFakeApi({
      getWorkflow: vi.fn(async () =>
        makeDetail({ canEdit: false, canPublish: false, draftGraph: undefined }),
      ),
    });
    renderEditor(api);
    expect(
      await screen.findByText(
        '只有流程所有者与租户管理员可以编辑草稿。你可以查看流程状态，但不能修改节点或参数。',
      ),
    ).toBeVisible();
    expect(screen.queryByRole('button', { name: '保存草稿' })).toBeNull();
  });

  it('403/404/503 分别显示无权限、不可见与编排关闭', async () => {
    const forbidden = createFakeApi({
      getWorkflow: vi.fn(async () => {
        throw new ApiError('forbidden', { status: 403, code: 'RICCON-AUTH-403' });
      }),
    });
    const first = renderEditor(forbidden);
    expect(await screen.findByText('没有访问权限')).toBeVisible();
    first.unmount();

    const missing = createFakeApi({
      getWorkflow: vi.fn(async () => {
        throw new ApiError('missing', { status: 404, code: 'RICCON-TENANT-404' });
      }),
    });
    const second = renderEditor(missing);
    expect(await screen.findByText('流程不存在或不可见')).toBeVisible();
    second.unmount();

    const disabled = createFakeApi({
      getWorkflow: vi.fn(async () => {
        throw new ApiError('disabled', { status: 503, code: 'RICCON-WF-503' });
      }),
    });
    const third = renderEditor(disabled);
    expect(await screen.findByText('编排功能已关闭')).toBeVisible();
    third.unmount();

    const failed = createFakeApi({
      getWorkflow: vi.fn(async () => {
        throw new ApiError('boom', { status: 500, code: 'RICCON-INTERNAL-500' });
      }),
    });
    renderEditor(failed);
    expect(await screen.findByText('流程加载失败')).toBeVisible();
  });

  it('375px 保持单列结构，关键按钮可聚焦', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 375 });
    const api = createFakeApi();
    renderEditor(api);
    await waitForEditor();

    const save = screen.getByRole('button', { name: '保存草稿' });
    const validate = screen.getByRole('button', { name: '校验' });
    const publish = screen.getByRole('button', { name: '发布' });
    for (const button of [save, validate, publish]) {
      expect(button).toBeEnabled();
      expect(button.getAttribute('tabindex')).not.toBe('-1');
    }
    expect(screen.getByRole('list', { name: '流程节点列表' })).toBeVisible();
    expect(screen.getByRole('list', { name: '流程连线列表' })).toBeVisible();
    // 小于 768px 直接进入表单视图：画布不渲染，也没有可横向滚动的 SVG。
    expect(screen.queryByTestId('workflow-canvas-visual')).toBeNull();
    expect(screen.getByText('小屏请使用列表/表单编辑；画布在 768px 以上显示。')).toBeVisible();
  });

  it('状态 Tag 达到 AA 对比度，节点与发布按钮按可见名称可查', async () => {
    renderEditor(createFakeApi());
    await waitForEditor();

    const coloredTags = Array.from(
      document.querySelectorAll<HTMLElement>(
        '.workflow-editor-title .ant-tag, .workflow-node-row .ant-tag',
      ),
    ).filter((tag) => tag.style.color !== '');
    expect(coloredTags.length).toBeGreaterThanOrEqual(3);
    coloredTags.forEach(expectAATagContrast);

    // 两字中文按钮关闭 antd 自动空格；aria-label 以可见文本开头并补充节点上下文。
    for (const name of [
      '校验',
      '发布',
      '上移节点 开始',
      '下移节点 开始',
      '删除节点 开始',
      '删除连线 start 到 retrieval-1',
    ]) {
      expect(screen.getByRole('button', { name })).toBeVisible();
    }
  });

  it('保存 400 时校验面板可达并展示服务端问题（F-004）', async () => {
    const detailMessage =
      'workflow validation failed with 1 issue(s): REQUIRED_CONFIG node=retrieval-1 field=config.topK: topK required';
    const saveDraft = vi.fn(async () => {
      throw new ApiError(detailMessage, {
        status: 400,
        code: 'RICCON-WF-400',
        retryable: false,
      });
    });
    renderEditor(createFakeApi({ saveDraft }));
    await waitForEditor();

    fireEvent.change(screen.getByLabelText('流程名称'), { target: { value: '触发服务端校验' } });
    fireEvent.click(screen.getByRole('button', { name: '保存草稿' }));

    expect(await screen.findByText(`保存失败（400）：${detailMessage}`)).toBeVisible();
    const panel = document.getElementById('workflow-validation-panel');
    expect(panel).not.toBeNull();
    expect(within(panel as HTMLElement).getByText(detailMessage)).toBeVisible();

    fireEvent.click(screen.getByRole('button', { name: '查看校验问题' }));
    expect(panel).toHaveFocus();
  });

  it('保存 429 时展示并发限制、当前值与下一步（AC-17）', async () => {
    const limited = new ApiError(
      'tenant active run limit reached (limit=10, current=12); wait for an active run to finish before retrying',
      { status: 429, code: 'RICCON-WF-429' },
    );
    const saveDraft = vi.fn(async () => {
      throw limited;
    });
    renderEditor(createFakeApi({ saveDraft }));
    await waitForEditor();

    fireEvent.change(screen.getByLabelText('流程名称'), { target: { value: '触发并发限制' } });
    fireEvent.click(screen.getByRole('button', { name: '保存草稿' }));

    const alertText = await screen.findByText(/租户并发运行已达上限/);
    expect(alertText).toHaveTextContent('当前 12 个');
    expect(alertText).toHaveTextContent('上限 10 个');
    expect(alertText).toHaveTextContent('等待已有运行结束后重试');
    expect(alertText).toHaveTextContent('联系租户管理员');
  });
});
