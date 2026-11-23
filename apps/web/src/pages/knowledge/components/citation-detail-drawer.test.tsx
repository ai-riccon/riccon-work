// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/services/api/errors';
import type { Citation } from '@/services/api/types';
import type { KnowledgeApi } from '@/services/knowledge';
import { toCitationView } from '@/services/knowledge';
import { withProviders } from '@/test/render';
import { CitationDetailDrawer } from './citation-detail-drawer';

/** 构造 Excel 引用。 */
function makeExcelCitation(overrides: Partial<Citation> = {}): Citation {
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

/** 构造知识库下载替身。 */
function makeKnowledgeApi(
  download: KnowledgeApi['downloadDocument'] = async () => ({
    blob: new Blob(['hello']),
    filename: '销售数据.xlsx',
  }),
): Pick<KnowledgeApi, 'downloadDocument'> {
  return { downloadDocument: vi.fn(download) };
}

describe('引用详情抽屉', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('Excel 引用显示工作表!区域、命中文本块高亮与下载入口', async () => {
    const knowledgeApi = makeKnowledgeApi();
    render(
      withProviders(
        <CitationDetailDrawer
          citation={toCitationView(makeExcelCitation())}
          knowledgeApi={knowledgeApi}
          onClose={vi.fn()}
        />,
      ),
    );

    const drawer = await screen.findByRole('dialog');
    expect(within(drawer).getByText('引用详情')).toBeVisible();
    expect(within(drawer).getByTestId('citation-title')).toHaveTextContent('销售数据.xlsx');
    expect(within(drawer).getByTestId('citation-region')).toHaveTextContent('销售数据!B2:D10');
    const table = within(drawer).getByTestId('citation-locator');
    expect(within(table).getByText('销售数据')).toBeVisible();
    expect(within(table).getByText('B2:D10')).toBeVisible();
    expect(within(drawer).getByTestId('citation-snippet').querySelector('mark')).toHaveTextContent(
      '华东区第三季度销售额同比增长 18%。',
    );
    expect(within(drawer).getByTestId('citation-status')).toHaveTextContent('可访问');
    expect(within(drawer).getByRole('button', { name: /下载源文件/ })).toBeVisible();
  });

  it('图片/扫描件引用显示页码、bbox 与低置信提示', async () => {
    const citation: Citation = {
      ordinal: 2,
      sourceType: 'KNOWLEDGE',
      knowledgeDocumentId: 'doc-2',
      locatorType: 'PAGE',
      pageNo: 3,
      bbox: { x: 120, y: 340, w: 480, h: 200 },
      ocrConfidence: 0.52,
      title: '扫描件.pdf',
      snippet: '识别出的文本块',
      sourceStatus: 'AVAILABLE',
    };
    render(
      withProviders(<CitationDetailDrawer citation={toCitationView(citation)} onClose={vi.fn()} />),
    );

    expect(await screen.findByText('第 3 页')).toBeVisible();
    expect(screen.getByTestId('citation-bbox')).toHaveTextContent(
      'x=120, y=340, 宽=480, 高=200（像素）',
    );
    expect(screen.getByTestId('citation-confidence')).toHaveTextContent('识别置信度 52%');
    expect(screen.getByTestId('citation-low-confidence')).toHaveTextContent('置信度低');
    expect(screen.getByTestId('citation-snippet').querySelector('mark')).toHaveTextContent(
      '识别出的文本块',
    );
  });

  it('NO_ACCESS 遮蔽标题、片段、定位与下载，不泄露任何内容', async () => {
    render(
      withProviders(
        <CitationDetailDrawer
          citation={toCitationView(
            makeExcelCitation({
              sourceStatus: 'NO_ACCESS',
              title: '不该显示',
              snippet: '不该显示',
            }),
          )}
          onClose={vi.fn()}
        />,
      ),
    );

    expect(await screen.findByTestId('citation-masked')).toHaveTextContent('该引用不可访问');
    expect(screen.queryByTestId('citation-title')).not.toBeInTheDocument();
    expect(screen.queryByTestId('citation-snippet')).not.toBeInTheDocument();
    expect(screen.queryByTestId('citation-locator')).not.toBeInTheDocument();
    expect(screen.queryByText('不该显示')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /下载源文件/ })).not.toBeInTheDocument();
    expect(screen.getByTestId('citation-status')).toHaveTextContent('该引用不可访问');
  });

  it('DELETED 遮蔽内容并显示源文档已删除', async () => {
    render(
      withProviders(
        <CitationDetailDrawer
          citation={toCitationView(makeExcelCitation({ sourceStatus: 'DELETED' }))}
          onClose={vi.fn()}
        />,
      ),
    );

    expect(await screen.findByTestId('citation-masked')).toHaveTextContent('源文档已删除');
    expect(screen.queryByTestId('citation-title')).not.toBeInTheDocument();
  });

  it('UPDATED 保留快照并提示源内容已更新', async () => {
    render(
      withProviders(
        <CitationDetailDrawer
          citation={toCitationView(makeExcelCitation({ sourceStatus: 'UPDATED' }))}
          onClose={vi.fn()}
        />,
      ),
    );

    expect(await screen.findByTestId('citation-updated')).toHaveTextContent('源内容已更新');
    expect(screen.getByTestId('citation-title')).toHaveTextContent('销售数据.xlsx');
    expect(screen.getByTestId('citation-snippet')).toBeVisible();
  });

  it('无知识库文档 ID 时显示“该文档没有可下载的源文件”，不发起请求', async () => {
    const knowledgeApi = makeKnowledgeApi();
    render(
      withProviders(
        <CitationDetailDrawer
          citation={toCitationView(makeExcelCitation({ knowledgeDocumentId: null }))}
          knowledgeApi={knowledgeApi}
          onClose={vi.fn()}
        />,
      ),
    );

    expect(await screen.findByTestId('citation-no-source')).toHaveTextContent(
      '该文档没有可下载的源文件',
    );
    expect(screen.queryByRole('button', { name: /下载源文件/ })).not.toBeInTheDocument();
    expect(knowledgeApi.downloadDocument).not.toHaveBeenCalled();
  });

  it('下载通过同源代理触发，RICCON-KB-404 显示中文原因', async () => {
    const knowledgeApi = makeKnowledgeApi(async () => {
      throw new ApiError('no source', { status: 404, code: 'RICCON-KB-404' });
    });
    render(
      withProviders(
        <CitationDetailDrawer
          citation={toCitationView(makeExcelCitation())}
          knowledgeApi={knowledgeApi}
          onClose={vi.fn()}
        />,
      ),
    );

    fireEvent.click(await screen.findByRole('button', { name: /下载源文件/ }));

    expect(await screen.findByTestId('citation-download-error')).toHaveTextContent(
      '该文档没有可下载的源文件。',
    );
    expect(knowledgeApi.downloadDocument).toHaveBeenCalledWith('doc-1');
  });

  it('下载成功后创建临时下载链接并释放对象 URL', async () => {
    const createObjectURL = vi.fn(() => 'blob:riccon');
    const revokeObjectURL = vi.fn();
    Object.assign(URL, { createObjectURL, revokeObjectURL });
    const click = vi
      .spyOn(HTMLAnchorElement.prototype, 'click')
      .mockImplementation(() => undefined);
    render(
      withProviders(
        <CitationDetailDrawer
          citation={toCitationView(makeExcelCitation())}
          knowledgeApi={makeKnowledgeApi()}
          onClose={vi.fn()}
        />,
      ),
    );

    fireEvent.click(await screen.findByRole('button', { name: /下载源文件/ }));

    await waitFor(() => {
      expect(click).toHaveBeenCalledTimes(1);
    });
    expect(createObjectURL).toHaveBeenCalledTimes(1);
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:riccon');
  });

  it('FILE 引用可打开原文件并显示原文件错误', async () => {
    const onOpenFile = vi.fn();
    render(
      withProviders(
        <CitationDetailDrawer
          citation={toCitationView({
            ordinal: 3,
            sourceType: 'FILE',
            fileId: 'file-1',
            locatorType: 'PAGE',
            pageNo: 1,
            title: '报告.pdf',
            snippet: '片段',
          })}
          fileError="源文件不可访问"
          onClose={vi.fn()}
          onOpenFile={onOpenFile}
        />,
      ),
    );

    fireEvent.click(await screen.findByRole('button', { name: /打开原文件/ }));
    expect(onOpenFile).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('citation-file-error')).toHaveTextContent('源文件不可访问');
  });
});
