import type { ArtifactCitation, Citation } from '@/services/api/types';
import type { CitationView } from './types';

/** 低置信阈值；与 ADR-0023 §4.3 一致。 */
export const CITATION_LOW_CONFIDENCE = 0.6;

/**
 * 把 J1/J2 的引用结构归一为引用详情视图。
 *
 * 两个契约的字段集合不同（J2 带有 `mcpInvocationId`），但详情展示只需要
 * 公共定位字段；统一视图让引用抽屉可以被聊天与报告复用。
 *
 * @param citation J1 或 J2 引用
 * @returns 统一引用视图
 */
export function toCitationView(citation: Citation | ArtifactCitation): CitationView {
  return {
    ordinal: citation.ordinal,
    sourceType: citation.sourceType,
    title: citation.title,
    snippet: citation.snippet,
    fileId: citation.fileId ?? null,
    knowledgeDocumentId: citation.knowledgeDocumentId ?? null,
    mcpInvocationId: 'mcpInvocationId' in citation ? (citation.mcpInvocationId ?? null) : null,
    locatorType: citation.locatorType ?? null,
    pageNo: citation.pageNo ?? null,
    sheetName: citation.sheetName ?? null,
    cellRange: citation.cellRange ?? null,
    paragraphNo: citation.paragraphNo ?? null,
    bbox: citation.bbox ?? null,
    ocrConfidence: citation.ocrConfidence ?? null,
    sourceStatus: citation.sourceStatus ?? 'AVAILABLE',
  };
}

/**
 * 判断引用内容是否必须遮蔽。
 *
 * `NO_ACCESS`/`DELETED` 时服务端返回空标题、空片段与空定位；前端再次按状态
 * 强制遮蔽，避免旧缓存或异常响应泄露标题、片段或定位。
 *
 * @param citation 引用视图
 * @returns 是否遮蔽
 */
export function isCitationMasked(citation: Pick<CitationView, 'sourceStatus'>): boolean {
  return citation.sourceStatus === 'NO_ACCESS' || citation.sourceStatus === 'DELETED';
}

/**
 * 返回引用可用性中文标签。
 *
 * @param citation 引用视图
 * @returns 标签；`AVAILABLE` 返回“可访问”
 */
export function citationAvailabilityLabel(citation: CitationView): string {
  switch (citation.sourceStatus) {
    case 'NO_ACCESS':
      return '该引用不可访问';
    case 'DELETED':
      return '源文档已删除';
    case 'UPDATED':
      return '源内容已更新';
    case 'AVAILABLE':
    default:
      return '可访问';
  }
}

/**
 * 返回引用可用性说明（含下一步）。
 *
 * @param citation 引用视图
 * @returns 中文说明
 */
export function citationAvailabilityDescription(citation: CitationView): string {
  switch (citation.sourceStatus) {
    case 'NO_ACCESS':
      return '该知识库文档的访问范围已变更，无法查看标题、片段或定位。请联系文档创建人或租户管理员。';
    case 'DELETED':
      return '源文档已删除或已到期，无法查看标题、片段或定位。如需保留内容，请联系租户管理员。';
    case 'UPDATED':
      return '引用保留生成时的快照；源内容已更新，定位指向当前版本。';
    case 'AVAILABLE':
    default:
      return '引用可正常访问。';
  }
}

/**
 * 生成引用定位文案。
 *
 * Excel 使用 `工作表!区域` 形式（例如 `销售数据!B2:D10`）；整表片段只显示
 * 工作表名，不伪造单元格。遮蔽状态返回“定位不可见”。
 *
 * @param citation 引用视图
 * @returns 定位文案
 */
export function citationLocatorText(
  citation: Pick<
    CitationView,
    'sourceStatus' | 'locatorType' | 'sheetName' | 'cellRange' | 'pageNo' | 'paragraphNo'
  >,
): string {
  if (isCitationMasked(citation)) {
    return '定位不可见';
  }
  switch (citation.locatorType) {
    case 'SHEET': {
      if (!citation.sheetName) {
        return '工作表未标注';
      }
      return citation.cellRange
        ? `工作表 ${citation.sheetName}!${citation.cellRange}`
        : `工作表 ${citation.sheetName}`;
    }
    case 'PAGE':
      return citation.pageNo ? `第 ${citation.pageNo} 页` : '页码未标注';
    case 'PARAGRAPH':
      return citation.paragraphNo ? `第 ${citation.paragraphNo} 段` : '段落未标注';
    default:
      return '位置未标注';
  }
}

/**
 * 生成 Excel 定位区域文案。
 *
 * @param citation 引用视图
 * @returns 例如 `销售数据!B2:D10`；非 Excel 或遮蔽时为 null
 */
export function citationRegionText(citation: CitationView): string | null {
  if (isCitationMasked(citation) || citation.locatorType !== 'SHEET' || !citation.sheetName) {
    return null;
  }
  return citation.cellRange ? `${citation.sheetName}!${citation.cellRange}` : citation.sheetName;
}

/**
 * 生成页内区域坐标文案。
 *
 * @param citation 引用视图
 * @returns 例如 `x=120, y=340, 宽=480, 高=200（像素）`；无 bbox 时为 null
 */
export function citationBBoxText(citation: CitationView): string | null {
  if (isCitationMasked(citation) || !citation.bbox) {
    return null;
  }
  const { x, y, w, h } = citation.bbox;
  return `x=${x}, y=${y}, 宽=${w}, 高=${h}（像素）`;
}

/**
 * 判断引用是否为低置信 OCR 结果。
 *
 * @param citation 引用视图
 * @returns 置信度低于阈值时为 true
 */
export function citationIsLowConfidence(citation: CitationView): boolean {
  return (
    !isCitationMasked(citation) &&
    citation.ocrConfidence !== null &&
    citation.ocrConfidence !== undefined &&
    citation.ocrConfidence < CITATION_LOW_CONFIDENCE
  );
}

/**
 * 生成 OCR 置信度文案。
 *
 * @param citation 引用视图
 * @returns 例如 `识别置信度 52%`；无置信度时为 null
 */
export function citationConfidenceText(citation: CitationView): string | null {
  if (
    isCitationMasked(citation) ||
    citation.ocrConfidence === null ||
    citation.ocrConfidence === undefined
  ) {
    return null;
  }
  return `识别置信度 ${Math.round(citation.ocrConfidence * 100)}%`;
}

/**
 * 生成引用列表按钮文案。
 *
 * 遮蔽状态不展示原标题，只用序号与“引用不可访问/源文档已删除”；
 * 这是 AC-07 的可见性要求，不是视觉省略。
 *
 * @param citation 引用视图
 * @returns 列表按钮文案
 */
export function citationListLabel(citation: CitationView): string {
  if (isCitationMasked(citation)) {
    return `[${citation.ordinal}] ${citationAvailabilityLabel(citation)}`;
  }
  return `[${citation.ordinal}] ${citation.title}`;
}

/**
 * 返回引用来源中文名。
 *
 * @param citation 引用视图
 * @returns `文件`、`知识库` 或 `工具`
 */
export function citationSourceText(citation: CitationView): string {
  switch (citation.sourceType) {
    case 'FILE':
      return '文件';
    case 'MCP':
      return '工具';
    case 'KNOWLEDGE':
    default:
      return '知识库';
  }
}
