// @vitest-environment jsdom
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { Citation } from '@/services/api/types';
import { withProviders } from '@/test/render';
import { SourceList } from './source-list';

/** 构造 Excel 知识库来源。 */
function makeSource(overrides: Partial<Citation> = {}): Citation {
  return {
    ordinal: 1,
    sourceType: 'KNOWLEDGE',
    knowledgeDocumentId: 'doc-1',
    locatorType: 'SHEET',
    sheetName: '销售数据',
    cellRange: 'B2:D10',
    title: '知识库：销售数据',
    snippet: '华东区第三季度销售额同比增长 18%。',
    sourceStatus: 'AVAILABLE',
    ...overrides,
  };
}

describe('J2 检索来源列表', () => {
  it('空命中给出下一步', () => {
    render(withProviders(<SourceList sources={[]} />));
    expect(screen.getByText('没有检索命中')).toBeVisible();
  });

  it('点击来源打开引用详情，显示工作表区域与命中片段', async () => {
    render(withProviders(<SourceList sources={[makeSource()]} />));

    fireEvent.click(screen.getByTestId('research-source-1'));

    expect(await screen.findByText('工作表 销售数据!B2:D10')).toBeVisible();
    expect(screen.getByTestId('citation-snippet')).toHaveTextContent(
      '华东区第三季度销售额同比增长 18%。',
    );
  });

  it('NO_ACCESS 来源不展示标题、片段与定位', async () => {
    render(
      withProviders(
        <SourceList
          sources={[
            makeSource({
              sourceStatus: 'NO_ACCESS',
              title: '',
              snippet: '',
              locatorType: undefined,
              sheetName: null,
              cellRange: null,
            }),
          ]}
        />,
      ),
    );

    expect(screen.getByText('该引用不可访问')).toBeVisible();
    expect(screen.queryByText('华东区第三季度销售额同比增长 18%。')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('research-source-1'));
    expect(await screen.findByTestId('citation-masked')).toBeVisible();
    expect(screen.queryByTestId('citation-title')).not.toBeInTheDocument();
  });
});
