import { Button } from 'antd';
import { useEffect, useId, useMemo, useState } from 'react';
import { NODE_TYPE_LABELS } from '@/pages/workflow/list/format';
import type { WorkflowGraph } from '@/pages/workflow/list/workflow-api';
import { computeCanvasLayout } from './layout';
import './canvas.css';

/**
 * 流程画布（最小增强视图）。
 *
 * 设计口径（ADR-0024 §11、M06-AC-19/20）：
 * - 列表/表单是主路径与等价路径；画布只读展示节点与连线，选中节点后同步右侧节点配置表单；
 * - 节点是原生 HTML 按钮（渲染在 SVG `foreignObject` 内），Tab 顺序按节点创建顺序，
 *   Enter/空格选中，`aria-pressed` 与 `aria-label` 同时表达名称、类型与状态；
 * - 连线在 SVG 中有 `<title>`，另有视觉隐藏的有序列表，供屏幕阅读器读出源→目标与条件分支；
 * - 视口小于 768px 时不渲染画布，只提示使用列表/表单，页面因此不会出现横向滚动；
 * - 不引入 React Flow 等依赖，几何计算集中在 `layout.ts` 纯函数中。
 */

/** 画布宽屏断点；与 ADR-0024 §11“<768px 默认表单视图”一致。 */
export const CANVAS_WIDE_QUERY = '(min-width: 768px)';

/** 小屏提示文案；窄屏时替代画布渲染。 */
export const CANVAS_COMPACT_HINT = '小屏请使用列表/表单编辑；画布在 768px 以上显示。';

/** 画布组件属性。 */
export interface WorkflowCanvasProps {
  /** 当前草稿图；与表单读写同一状态。 */
  graph: WorkflowGraph;
  /** 当前选中的节点 ID；用于同步节点配置表单。 */
  selectedNodeId: string | null;
  /** 有待修正校验问题的节点 ID；用于画布状态标记与 aria 文案。 */
  invalidNodeIds?: readonly string[];
  /** 选中节点；只同步表单，不修改图。 */
  onSelectNode: (nodeId: string) => void;
  /** 键盘/点击跳到节点配置表单；未选中节点时按钮禁用。 */
  onRequestNodeForm?: () => void;
  /** 键盘/点击跳到连线表单。 */
  onRequestEdgeForm?: () => void;
}

/**
 * 读取当前视口是否达到画布断点。
 *
 * 无法访问 `matchMedia`（例如极端测试环境）时按宽屏处理：
 * 画布仍只是增强视图，表单始终可用，不会因探测失败而隐藏主路径。
 *
 * @returns 是否渲染画布
 */
function readWideViewport(): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    return true;
  }
  return window.matchMedia(CANVAS_WIDE_QUERY).matches;
}

/**
 * 订阅画布断点变化。
 *
 * @returns 当前是否为宽屏
 */
function useWideViewport(): boolean {
  const [wide, setWide] = useState(readWideViewport);

  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
      return;
    }
    const media = window.matchMedia(CANVAS_WIDE_QUERY);
    setWide(media.matches);
    const handleChange = (event: MediaQueryListEvent): void => {
      setWide(event.matches);
    };
    media.addEventListener('change', handleChange);
    return () => {
      media.removeEventListener('change', handleChange);
    };
  }, []);

  return wide;
}

/**
 * 流程画布组件。
 *
 * @param props 图、选中节点与表单入口回调
 * @returns 画布增强视图或小屏提示
 */
export function WorkflowCanvas({
  graph,
  selectedNodeId,
  invalidNodeIds,
  onSelectNode,
  onRequestNodeForm,
  onRequestEdgeForm,
}: WorkflowCanvasProps) {
  const wide = useWideViewport();
  const layout = useMemo(() => computeCanvasLayout(graph), [graph]);
  // 同一页面可能存在多个实例；marker id 必须唯一，避免箭头指向错误画布。
  const markerId = `workflow-canvas-arrow-${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`;

  if (!wide) {
    return (
      <div className="workflow-canvas" data-testid="workflow-canvas">
        <p className="workflow-canvas-fallback">{CANVAS_COMPACT_HINT}</p>
      </div>
    );
  }

  if (graph.nodes.length === 0) {
    return (
      <div className="workflow-canvas" data-testid="workflow-canvas">
        <p className="workflow-canvas-hint">
          还没有节点。请在上方节点列表添加节点，画布会自动生成预览；画布不是唯一入口。
        </p>
      </div>
    );
  }

  const invalid = new Set(invalidNodeIds ?? []);
  const nodeById = new Map(graph.nodes.map((node) => [node.id, node]));
  const sourceName = (id: string): string => nodeById.get(id)?.name ?? id;
  const edgeLabel = (edge: WorkflowGraph['edges'][number]): string =>
    `${sourceName(edge.source)} → ${sourceName(edge.target)}${
      edge.branch ? `（条件 ${edge.branch}）` : ''
    }`;

  return (
    <div className="workflow-canvas" data-testid="workflow-canvas">
      <p className="workflow-canvas-hint">
        画布用于总览与选择节点；节点参数、连线与校验请在下方列表/表单中完成，两者读写同一草稿。
      </p>
      <div className="workflow-canvas-visual" data-testid="workflow-canvas-visual">
        <svg
          className="workflow-canvas-svg"
          viewBox={`0 0 ${layout.width} ${layout.height}`}
          style={{ aspectRatio: `${layout.width} / ${layout.height}` }}
          focusable="false"
        >
          <defs>
            <marker
              id={markerId}
              viewBox="0 0 10 10"
              refX="9"
              refY="5"
              markerWidth="6"
              markerHeight="6"
              orient="auto-start-reverse"
            >
              <path d="M 0 0 L 10 5 L 0 10 z" fill="#8c8c8c" />
            </marker>
          </defs>
          {layout.edges.map((edge) => (
            <g className="workflow-canvas-edge" key={edge.id}>
              <title>{edgeLabel(edge)}</title>
              <line
                className="workflow-canvas-edge-line"
                x1={edge.x1}
                y1={edge.y1}
                x2={edge.x2}
                y2={edge.y2}
                markerEnd={`url(#${markerId})`}
              />
              {edge.branch ? (
                <text className="workflow-canvas-edge-label" x={edge.labelX} y={edge.labelY}>
                  {edge.branch}
                </text>
              ) : null}
            </g>
          ))}
          {layout.nodes.map((box) => {
            const selected = box.id === selectedNodeId;
            const broken = invalid.has(box.id);
            const status = broken ? '待修正' : '已配置';
            const className = [
              'workflow-canvas-node',
              selected ? 'workflow-canvas-node-selected' : '',
              broken ? 'workflow-canvas-node-invalid' : '',
            ]
              .filter(Boolean)
              .join(' ');
            return (
              <foreignObject
                className="workflow-canvas-node-foreign"
                key={box.id}
                x={box.x}
                y={box.y}
                width={box.width}
                height={box.height}
              >
                <button
                  type="button"
                  className={className}
                  aria-pressed={selected}
                  aria-label={`${box.name}，${NODE_TYPE_LABELS[box.type]}，${status}`}
                  onClick={() => onSelectNode(box.id)}
                  // 显式处理 Enter/空格：preventDefault 阻止 Enter 的默认点击与空格滚动，
                  // 让真实浏览器和 jsdom 共用同一条键盘路径，选中同一节点是幂等更新。
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' || event.key === ' ') {
                      event.preventDefault();
                      onSelectNode(box.id);
                    }
                  }}
                >
                  <span className="workflow-canvas-node-name">{box.name}</span>
                  <span className="workflow-canvas-node-meta">
                    {NODE_TYPE_LABELS[box.type]} · {status}
                  </span>
                </button>
              </foreignObject>
            );
          })}
        </svg>
        {/* 视觉隐藏的有序列表：屏幕阅读器可读出完整的源→目标与条件分支。 */}
        <ol className="workflow-canvas-edge-list" aria-label="画布连线列表">
          {graph.edges.map((edge) => (
            <li key={edge.id}>
              {sourceName(edge.source)} → {sourceName(edge.target)}
              {edge.branch ? `（条件 ${edge.branch}）` : ''}
            </li>
          ))}
        </ol>
      </div>
      {onRequestNodeForm || onRequestEdgeForm ? (
        <div className="workflow-canvas-actions">
          {onRequestNodeForm ? (
            <Button size="small" disabled={!selectedNodeId} onClick={onRequestNodeForm}>
              在表单中编辑选中节点
            </Button>
          ) : null}
          {onRequestEdgeForm ? (
            <Button size="small" onClick={onRequestEdgeForm}>
              在表单中编辑连线
            </Button>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
