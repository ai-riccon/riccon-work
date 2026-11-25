import { describe, expect, it } from 'vitest';
import type { WorkflowGraph } from '@/pages/workflow/list/workflow-api';
import {
  CANVAS_MIN_HEIGHT,
  CANVAS_MIN_WIDTH,
  CANVAS_NODE_HEIGHT,
  CANVAS_NODE_WIDTH,
  CANVAS_PADDING,
  computeCanvasLayout,
} from './layout';

/**
 * 画布布局纯函数测试。
 *
 * 重点覆盖坐标归一化、无坐标回退与连线端点；这些值直接决定画布是否出现
 * 负坐标、NaN 或与节点错位的箭头，jsdom 无法替代几何断言。
 */

/** 构造最小图。 */
function graphOf(nodes: WorkflowGraph['nodes'], edges: WorkflowGraph['edges'] = []): WorkflowGraph {
  return { schemaVersion: 1, nodes, edges };
}

describe('画布布局', () => {
  it('空图返回最小尺寸且没有节点/连线', () => {
    const layout = computeCanvasLayout(graphOf([]));
    expect(layout.width).toBe(CANVAS_MIN_WIDTH);
    expect(layout.height).toBe(CANVAS_MIN_HEIGHT);
    expect(layout.nodes).toEqual([]);
    expect(layout.edges).toEqual([]);
  });

  it('负坐标被平移到内边距内，且 viewBox 尺寸覆盖所有节点', () => {
    const layout = computeCanvasLayout(
      graphOf([
        {
          id: 'a',
          type: 'START',
          name: '开始',
          config: {},
          position: { x: -40, y: -10 },
        },
        {
          id: 'b',
          type: 'END',
          name: '结束',
          config: {},
          position: { x: 300, y: 160 },
        },
      ]),
    );
    expect(layout.nodes[0]).toMatchObject({ x: CANVAS_PADDING, y: CANVAS_PADDING });
    expect(layout.nodes[1].x).toBe(300 - -40 + CANVAS_PADDING);
    expect(layout.width).toBeGreaterThanOrEqual(CANVAS_MIN_WIDTH);
    expect(layout.height).toBeGreaterThanOrEqual(CANVAS_MIN_HEIGHT);
    for (const node of layout.nodes) {
      expect(Number.isFinite(node.x)).toBe(true);
      expect(Number.isFinite(node.y)).toBe(true);
    }
  });

  it('缺失或 NaN 坐标回退到网格，不产生 NaN', () => {
    const layout = computeCanvasLayout(
      graphOf([
        { id: 'a', type: 'START', name: '开始', config: {} },
        {
          id: 'b',
          type: 'RETRIEVAL',
          name: '检索',
          config: {},
          position: { x: Number.NaN, y: Number.POSITIVE_INFINITY },
        },
        {
          id: 'c',
          type: 'END',
          name: '结束',
          config: {},
          position: null,
        },
      ]),
    );
    const positions = layout.nodes.map((node) => `${node.x},${node.y}`);
    expect(new Set(positions).size).toBe(3);
    for (const node of layout.nodes) {
      expect(Number.isFinite(node.x)).toBe(true);
      expect(Number.isFinite(node.y)).toBe(true);
    }
  });

  it('连线端点取源节点右中与目标节点左中，并保留条件分支', () => {
    const layout = computeCanvasLayout(
      graphOf(
        [
          {
            id: 'start',
            type: 'START',
            name: '开始',
            config: {},
            position: { x: 0, y: 0 },
          },
          {
            id: 'end',
            type: 'END',
            name: '结束',
            config: {},
            position: { x: 300, y: 100 },
          },
        ],
        [{ id: 'e1', source: 'start', target: 'end', branch: 'TRUE' }],
      ),
    );
    const source = layout.nodes[0];
    const target = layout.nodes[1];
    expect(layout.edges[0]).toMatchObject({
      x1: source.x + CANVAS_NODE_WIDTH,
      y1: source.y + CANVAS_NODE_HEIGHT / 2,
      x2: target.x,
      y2: target.y + CANVAS_NODE_HEIGHT / 2,
      branch: 'TRUE',
    });
    expect(layout.edges[0].labelX).toBe((source.x + CANVAS_NODE_WIDTH + target.x) / 2);
  });

  it('悬空边与自连边不绘制，但仍由校验面板负责报告', () => {
    const layout = computeCanvasLayout(
      graphOf(
        [
          { id: 'a', type: 'START', name: '开始', config: {}, position: { x: 0, y: 0 } },
          { id: 'b', type: 'END', name: '结束', config: {}, position: { x: 200, y: 0 } },
        ],
        [
          { id: 'ok', source: 'a', target: 'b' },
          { id: 'missing', source: 'a', target: 'ghost' },
          { id: 'self', source: 'a', target: 'a' },
        ],
      ),
    );
    expect(layout.edges.map((edge) => edge.id)).toEqual(['ok']);
  });
});
