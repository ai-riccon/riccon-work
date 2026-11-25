// @vitest-environment jsdom
import { fireEvent, render, screen, within } from '@testing-library/react';
import type { ComponentProps } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { WorkflowGraph } from '@/pages/workflow/list/workflow-api';
import { CANVAS_COMPACT_HINT, WorkflowCanvas } from './index';

/**
 * 画布组件测试。
 *
 * 覆盖节点/连线渲染、选中与键盘操作、aria 状态、等价表单入口与 375px 降级；
 * 真实浏览器键盘全流程、axe 与无横向滚动由 T16a 的 E2E-M06-09/@a11y 负责。
 */

/** 标准三节点图；坐标为服务端草稿格式。 */
const GRAPH: WorkflowGraph = {
  schemaVersion: 1,
  nodes: [
    { id: 'start', type: 'START', name: '开始', config: {}, position: { x: 0, y: 0 } },
    {
      id: 'retrieval-1',
      type: 'RETRIEVAL',
      name: '检索知识库',
      config: { queryTemplate: '{{input.topic}}', topK: 5 },
      position: { x: 240, y: 0 },
    },
    {
      id: 'end',
      type: 'END',
      name: '结束',
      config: { resultFrom: 'retrieval-1' },
      position: { x: 480, y: 0 },
    },
  ],
  edges: [
    { id: 'e1', source: 'start', target: 'retrieval-1' },
    { id: 'e2', source: 'retrieval-1', target: 'end' },
  ],
};

/**
 * 设置视口宽度并返回对应的 matchMedia 替身。
 *
 * 画布只查询 `(min-width: 768px)`；其余查询保持 false，避免影响 antd 默认行为。
 *
 * @param width 视口宽度（px）
 */
function setViewport(width: number): void {
  Object.defineProperty(window, 'innerWidth', { configurable: true, value: width });
  window.matchMedia = ((query: string) => ({
    matches: query === '(min-width: 768px)' ? width >= 768 : false,
    media: query,
    onchange: null,
    addListener: () => undefined,
    removeListener: () => undefined,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia;
}

/** 渲染画布。 */
function renderCanvas(overrides: Partial<ComponentProps<typeof WorkflowCanvas>> = {}) {
  return render(
    <WorkflowCanvas
      graph={GRAPH}
      selectedNodeId={null}
      onSelectNode={vi.fn()}
      onRequestNodeForm={vi.fn()}
      onRequestEdgeForm={vi.fn()}
      {...overrides}
    />,
  );
}

describe('流程画布', () => {
  beforeEach(() => {
    setViewport(1024);
  });

  it('渲染节点、连线与等价表单入口', () => {
    renderCanvas();
    const canvas = screen.getByTestId('workflow-canvas');
    expect(screen.getByTestId('workflow-canvas-visual')).toBeInTheDocument();

    expect(within(canvas).getByRole('button', { name: '开始，开始，已配置' })).toBeInTheDocument();
    expect(
      within(canvas).getByRole('button', { name: '检索知识库，知识库检索，已配置' }),
    ).toBeInTheDocument();
    expect(within(canvas).getByRole('button', { name: '结束，结束，已配置' })).toBeInTheDocument();

    const edgeList = within(canvas).getByRole('list', { name: '画布连线列表' });
    expect(
      within(edgeList)
        .getAllByRole('listitem')
        .map((item) => item.textContent),
    ).toEqual(['开始 → 检索知识库', '检索知识库 → 结束']);
    expect(canvas.querySelectorAll('g.workflow-canvas-edge title')).toHaveLength(2);
    expect(
      within(canvas).getByRole('button', { name: '在表单中编辑选中节点' }),
    ).toBeInTheDocument();
    expect(within(canvas).getByRole('button', { name: '在表单中编辑连线' })).toBeInTheDocument();
  });

  it('点击、Enter 与空格都选中节点，并可从画布回到表单', () => {
    const onSelectNode = vi.fn();
    const onRequestNodeForm = vi.fn();
    const onRequestEdgeForm = vi.fn();
    renderCanvas({
      selectedNodeId: 'retrieval-1',
      onSelectNode,
      onRequestNodeForm,
      onRequestEdgeForm,
    });

    const node = screen.getByRole('button', { name: '检索知识库，知识库检索，已配置' });
    expect(node).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(node);
    expect(onSelectNode).toHaveBeenLastCalledWith('retrieval-1');

    fireEvent.keyDown(node, { key: 'Enter' });
    expect(onSelectNode).toHaveBeenLastCalledWith('retrieval-1');

    fireEvent.keyDown(node, { key: ' ' });
    expect(onSelectNode).toHaveBeenLastCalledWith('retrieval-1');
    expect(onSelectNode).toHaveBeenCalledTimes(3);

    fireEvent.click(screen.getByRole('button', { name: '在表单中编辑选中节点' }));
    expect(onRequestNodeForm).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: '在表单中编辑连线' }));
    expect(onRequestEdgeForm).toHaveBeenCalledTimes(1);
  });

  it('未选中节点时禁用“在表单中编辑选中节点”', () => {
    renderCanvas({ selectedNodeId: null });
    expect(screen.getByRole('button', { name: '在表单中编辑选中节点' })).toBeDisabled();
  });

  it('待修正节点在 aria 标签和样式中同时可见', () => {
    renderCanvas({ invalidNodeIds: ['retrieval-1'] });
    const node = screen.getByRole('button', { name: '检索知识库，知识库检索，待修正' });
    expect(node).toHaveClass('workflow-canvas-node-invalid');
  });

  it('375px 不渲染画布，提示改用列表/表单', () => {
    setViewport(375);
    renderCanvas();
    expect(screen.getByTestId('workflow-canvas')).toBeInTheDocument();
    expect(screen.queryByTestId('workflow-canvas-visual')).toBeNull();
    expect(screen.getByText(CANVAS_COMPACT_HINT)).toBeVisible();
    expect(screen.queryByRole('button', { name: '检索知识库，知识库检索，已配置' })).toBeNull();
  });

  it('空图给出添加入口提示，不渲染空白 SVG', () => {
    renderCanvas({ graph: { schemaVersion: 1, nodes: [], edges: [] } });
    expect(screen.getByText(/还没有节点/)).toBeVisible();
    expect(screen.queryByTestId('workflow-canvas-visual')).toBeNull();
  });
});
