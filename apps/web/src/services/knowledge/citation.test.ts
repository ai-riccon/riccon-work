import { describe, expect, it } from 'vitest';
import type { ArtifactCitation, Citation } from '@/services/api/types';
import {
  citationAvailabilityLabel,
  citationBBoxText,
  citationConfidenceText,
  citationIsLowConfidence,
  citationListLabel,
  citationLocatorText,
  citationRegionText,
  citationSourceText,
  isCitationMasked,
  toCitationView,
} from './citation';
import type { CitationView } from './types';

/** 构造 Excel 引用。 */
function makeCitation(overrides: Partial<Citation> = {}): Citation {
  return {
    ordinal: 1,
    sourceType: 'KNOWLEDGE',
    knowledgeDocumentId: 'doc-1',
    locatorType: 'SHEET',
    sheetName: '销售数据',
    cellRange: 'B2:D10',
    title: '销售数据.xlsx',
    snippet: '华东区第三季度销售额同比增长 18%。',
    sourceStatus: 'AVAILABLE',
    ...overrides,
  };
}

/** 构造引用视图。 */
function makeView(overrides: Partial<CitationView> = {}): CitationView {
  return {
    ...toCitationView(makeCitation()),
    ...overrides,
  };
}

describe('引用统一视图', () => {
  it('J1 Citation 与 J2 ArtifactCitation 映射到同一视图', () => {
    const j1 = toCitationView(makeCitation());
    const j2: ArtifactCitation = {
      ordinal: 2,
      sourceType: 'MCP',
      mcpInvocationId: 'mcp-1',
      title: '市场规模',
      snippet: '320 亿元',
    };
    const view = toCitationView(j2);

    expect(j1.cellRange).toBe('B2:D10');
    expect(j1.sourceStatus).toBe('AVAILABLE');
    expect(view.mcpInvocationId).toBe('mcp-1');
    expect(view.sourceStatus).toBe('AVAILABLE');
    expect(view.locatorType).toBeNull();
  });
});

describe('引用定位', () => {
  it('Excel 显示工作表!区域，整表不伪造单元格', () => {
    expect(citationLocatorText(makeView())).toBe('工作表 销售数据!B2:D10');
    expect(citationRegionText(makeView())).toBe('销售数据!B2:D10');
    expect(
      citationLocatorText(makeView({ locatorType: 'SHEET', sheetName: '汇总', cellRange: null })),
    ).toBe('工作表 汇总');
    expect(citationRegionText(makeView({ sheetName: '汇总', cellRange: null }))).toBe('汇总');
  });

  it('PDF/图片显示页码与 bbox 坐标，缺失时给出稳定占位', () => {
    const view = makeView({
      locatorType: 'PAGE',
      pageNo: 3,
      sheetName: null,
      cellRange: null,
      bbox: { x: 120, y: 340, w: 480, h: 200 },
      ocrConfidence: 0.82,
    });
    expect(citationLocatorText(view)).toBe('第 3 页');
    expect(citationBBoxText(view)).toBe('x=120, y=340, 宽=480, 高=200（像素）');
    expect(citationConfidenceText(view)).toBe('识别置信度 82%');
    expect(citationIsLowConfidence(view)).toBe(false);

    expect(citationLocatorText(makeView({ locatorType: 'PAGE', pageNo: null }))).toBe('页码未标注');
    expect(citationLocatorText(makeView({ locatorType: 'PARAGRAPH', paragraphNo: 5 }))).toBe(
      '第 5 段',
    );
    expect(citationLocatorText(makeView({ locatorType: null }))).toBe('位置未标注');
    expect(citationBBoxText(makeView({ bbox: null }))).toBeNull();
  });

  it('低置信阈值 0.6：低于阈值提示，等于阈值不提示', () => {
    expect(citationIsLowConfidence(makeView({ ocrConfidence: 0.599 }))).toBe(true);
    expect(citationIsLowConfidence(makeView({ ocrConfidence: 0.6 }))).toBe(false);
    expect(citationConfidenceText(makeView({ ocrConfidence: 0.52 }))).toBe('识别置信度 52%');
    expect(citationConfidenceText(makeView({ ocrConfidence: null }))).toBeNull();
  });

  it('来源类型中文名', () => {
    expect(citationSourceText(makeView())).toBe('知识库');
    expect(citationSourceText(makeView({ sourceType: 'FILE' }))).toBe('文件');
    expect(citationSourceText(makeView({ sourceType: 'MCP' }))).toBe('工具');
  });
});

describe('引用可用性与遮蔽', () => {
  it('NO_ACCESS 遮蔽标题、片段与定位', () => {
    const view = makeView({
      sourceStatus: 'NO_ACCESS',
      title: '',
      snippet: '',
      locatorType: null,
      sheetName: null,
      cellRange: null,
      bbox: null,
    });
    expect(isCitationMasked(view)).toBe(true);
    expect(citationAvailabilityLabel(view)).toBe('该引用不可访问');
    expect(citationLocatorText(view)).toBe('定位不可见');
    expect(citationRegionText(view)).toBeNull();
    expect(citationBBoxText(view)).toBeNull();
    expect(citationListLabel(view)).toBe('[1] 该引用不可访问');
  });

  it('DELETED 遮蔽内容，列表文案不泄露标题', () => {
    const view = makeView({ sourceStatus: 'DELETED', title: '不该显示', snippet: '不该显示' });
    expect(citationAvailabilityLabel(view)).toBe('源文档已删除');
    expect(citationListLabel(view)).toBe('[1] 源文档已删除');
    expect(citationLocatorText(view)).toBe('定位不可见');
  });

  it('UPDATED 保留快照并标注源内容已更新', () => {
    const view = makeView({ sourceStatus: 'UPDATED' });
    expect(isCitationMasked(view)).toBe(false);
    expect(citationAvailabilityLabel(view)).toBe('源内容已更新');
    expect(citationListLabel(view)).toBe('[1] 销售数据.xlsx');
  });

  it('未知状态按 AVAILABLE 处理', () => {
    const view = makeView({ sourceStatus: undefined });
    expect(isCitationMasked(view)).toBe(false);
    expect(citationAvailabilityLabel(view)).toBe('可访问');
  });
});
