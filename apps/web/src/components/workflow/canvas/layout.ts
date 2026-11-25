import type {
  WorkflowEdgeBranch,
  WorkflowGraph,
  WorkflowNodeType,
} from '@/pages/workflow/list/workflow-api';

/**
 * 画布几何布局（纯函数，不依赖 React 与 DOM）。
 *
 * 画布是 ADR-0024 §11 的“增强视图”：只读取与表单相同的 `WorkflowGraph`，不写草稿。
 * 这里把服务端坐标（节点左上角 x/y）换算成统一的正坐标系，并生成连线端点，
 * 让 SVG `viewBox` 可以整体缩放以适配容器宽度，避免 375px/窄栏出现横向滚动。
 *
 * 类型复用说明：`workflow-api.ts` 的契约类型目前按 T10 写入范围放在 list 页面内，
 * T11 只读复用；后续迁移到 `src/services/workflow/**` 时本文件只改 import。
 */

/** 画布节点宽度（用户单位）。 */
export const CANVAS_NODE_WIDTH = 176;

/** 画布节点高度（用户单位）。 */
export const CANVAS_NODE_HEIGHT = 72;

/** 画布内边距（用户单位）。 */
export const CANVAS_PADDING = 32;

/** 画布最小宽度；节点很少时保持可读比例。 */
export const CANVAS_MIN_WIDTH = 640;

/** 画布最小高度；空图或单节点时保持稳定高度。 */
export const CANVAS_MIN_HEIGHT = 280;

/** 无有效坐标时的回退网格列数。 */
const FALLBACK_COLUMNS = 3;

/** 无有效坐标时的回退水平间距。 */
const FALLBACK_GAP_X = 64;

/** 无有效坐标时的回退垂直间距。 */
const FALLBACK_GAP_Y = 56;

/** 画布节点盒模型；坐标为 x/y 左上角。 */
export interface CanvasNodeBox {
  id: string;
  name: string;
  type: WorkflowNodeType;
  x: number;
  y: number;
  width: number;
  height: number;
  centerX: number;
  centerY: number;
}

/** 画布连线；端点取源节点右中与目标节点左中。 */
export interface CanvasEdgeLine {
  id: string;
  source: string;
  target: string;
  branch: WorkflowEdgeBranch | null;
  x1: number;
  y1: number;
  x2: number;
  y2: number;
  labelX: number;
  labelY: number;
}

/** 画布布局结果。 */
export interface CanvasLayout {
  width: number;
  height: number;
  nodes: CanvasNodeBox[];
  edges: CanvasEdgeLine[];
}

/**
 * 判断坐标是否为有限数字。
 *
 * @param value 任意值
 * @returns 是否为可渲染的有限数字
 */
function finite(value: number | null | undefined): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

/**
 * 为缺少有效坐标的节点生成回退位置。
 *
 * 旧草稿或手工构造的图可能没有 position；这里按创建顺序排成网格，
 * 保证画布仍可显示，而不是把节点全部叠在 (0,0)。
 *
 * @param index 节点在 `graph.nodes` 中的顺序
 * @returns 回退坐标
 */
function fallbackPosition(index: number): { x: number; y: number } {
  return {
    x: (index % FALLBACK_COLUMNS) * (CANVAS_NODE_WIDTH + FALLBACK_GAP_X),
    y: Math.floor(index / FALLBACK_COLUMNS) * (CANVAS_NODE_HEIGHT + FALLBACK_GAP_Y),
  };
}

/**
 * 计算画布布局。
 *
 * 步骤：
 * 1. 逐节点解析坐标（无效时回退网格），得到原始盒模型；
 * 2. 以最小 x/y 平移，使所有节点落在 `CANVAS_PADDING` 以内；
 * 3. 计算 viewBox 尺寸（不小于最小值）；
 * 4. 为每条源/目标都存在的边生成端点与分支标签位置。
 *
 * @param graph 当前草稿图
 * @returns 可直接渲染的布局
 */
export function computeCanvasLayout(graph: WorkflowGraph): CanvasLayout {
  if (graph.nodes.length === 0) {
    // 空图不进入边界计算：否则占位最大宽高会被再加一次内边距，返回非最小尺寸。
    return { width: CANVAS_MIN_WIDTH, height: CANVAS_MIN_HEIGHT, nodes: [], edges: [] };
  }

  const raw = graph.nodes.map((node, index) => {
    const fallback = fallbackPosition(index);
    const x = finite(node.position?.x) ? node.position.x : fallback.x;
    const y = finite(node.position?.y) ? node.position.y : fallback.y;
    return { node, x, y };
  });

  const minX = Math.min(...raw.map((item) => item.x));
  const minY = Math.min(...raw.map((item) => item.y));
  const maxX = Math.max(...raw.map((item) => item.x + CANVAS_NODE_WIDTH));
  const maxY = Math.max(...raw.map((item) => item.y + CANVAS_NODE_HEIGHT));

  const width = Math.max(CANVAS_MIN_WIDTH, maxX - minX + CANVAS_PADDING * 2);
  const height = Math.max(CANVAS_MIN_HEIGHT, maxY - minY + CANVAS_PADDING * 2);

  // 平移后所有坐标 >= CANVAS_PADDING；viewBox 从 0 开始，SVG 才能按容器宽度等比缩放。
  const nodes: CanvasNodeBox[] = raw.map(({ node, x, y }) => {
    const left = x - minX + CANVAS_PADDING;
    const top = y - minY + CANVAS_PADDING;
    return {
      id: node.id,
      name: node.name,
      type: node.type,
      x: left,
      y: top,
      width: CANVAS_NODE_WIDTH,
      height: CANVAS_NODE_HEIGHT,
      centerX: left + CANVAS_NODE_WIDTH / 2,
      centerY: top + CANVAS_NODE_HEIGHT / 2,
    };
  });

  const byId = new Map(nodes.map((node) => [node.id, node]));
  const edges: CanvasEdgeLine[] = [];
  for (const edge of graph.edges) {
    const source = byId.get(edge.source);
    const target = byId.get(edge.target);
    // 悬空边与自连边由校验面板报告，画布不猜测含义，直接跳过。
    if (!source || !target || source.id === target.id) {
      continue;
    }
    const x1 = source.x + source.width;
    const y1 = source.centerY;
    const x2 = target.x;
    const y2 = target.centerY;
    edges.push({
      id: edge.id,
      source: edge.source,
      target: edge.target,
      branch: edge.branch ?? null,
      x1,
      y1,
      x2,
      y2,
      labelX: (x1 + x2) / 2,
      labelY: (y1 + y2) / 2 - 10,
    });
  }

  return { width, height, nodes, edges };
}
