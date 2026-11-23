import { DownloadOutlined, FileSearchOutlined } from '@ant-design/icons';
import { Alert, Button, Descriptions, Drawer, Flex, Space, Tag, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { knowledgeApi as defaultKnowledgeApi } from '@/services/knowledge';
import type { KnowledgeApi } from '@/services/knowledge';
import {
  citationAvailabilityDescription,
  citationStatusTagStyle,
  citationAvailabilityLabel,
  citationBBoxText,
  citationConfidenceText,
  citationIsLowConfidence,
  citationLocatorText,
  citationRegionText,
  citationSourceText,
  isCitationMasked,
} from '@/services/knowledge';
import type { CitationView } from '@/services/knowledge';
import { describeKnowledgeError } from '@/services/knowledge';
import { KNOWLEDGE_SHARING_NOTICE } from '@/services/workspaces/visibility';

/** 引用详情抽屉的可注入依赖。 */
export interface CitationDetailDrawerProps {
  /** 引用视图；null 表示关闭。 */
  citation: CitationView | null;
  /** 关闭回调。 */
  onClose: () => void;
  /** 知识库下载能力；测试可注入。 */
  knowledgeApi?: Pick<KnowledgeApi, 'downloadDocument'>;
  /** 打开原文件（FILE 来源）；未注入时不显示入口。 */
  onOpenFile?: () => void;
  /** 原文件下载错误。 */
  fileError?: string | null;
  /** 原文件是否不可访问。 */
  fileUnavailable?: boolean;
  /** 原文件是否正在打开。 */
  fileDownloading?: boolean;
}

/**
 * 引用详情抽屉。
 *
 * 责任边界：
 * 1. 只展示服务端返回的标题、片段、定位与置信度，不伪造单元格内容或页图；
 * 2. `NO_ACCESS`/`DELETED` 时前端按状态强制遮蔽，即使响应异常带正文也不展示；
 * 3. 知识库来源通过同源服务端代理下载，不接触对象 key 或预签名 URL。
 *
 * @param props 引用、回调与可注入下载能力
 * @returns 引用详情抽屉
 */
export function CitationDetailDrawer({
  citation,
  onClose,
  knowledgeApi = defaultKnowledgeApi,
  onOpenFile,
  fileError,
  fileUnavailable,
  fileDownloading,
}: CitationDetailDrawerProps) {
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState<string | null>(null);

  // 切换引用或关闭时清空上一次下载反馈，避免错误状态串到另一条引用。
  useEffect(() => {
    setDownloadError(null);
    setDownloading(false);
  }, [citation?.ordinal, citation?.knowledgeDocumentId, citation?.sourceStatus]);

  if (!citation) {
    return null;
  }

  const masked = isCitationMasked(citation);
  const availability = citationAvailabilityLabel(citation);
  const locator = citationLocatorText(citation);
  const region = citationRegionText(citation);
  const bbox = citationBBoxText(citation);
  const confidence = citationConfidenceText(citation);
  const lowConfidence = citationIsLowConfidence(citation);
  const hasKnowledgeSource = Boolean(citation.knowledgeDocumentId);

  /**
   * 触发知识库同源下载。
   */
  const handleKnowledgeDownload = async (): Promise<void> => {
    if (!citation.knowledgeDocumentId) {
      return;
    }
    setDownloading(true);
    setDownloadError(null);
    try {
      const download = await knowledgeApi.downloadDocument(citation.knowledgeDocumentId);
      const objectUrl = URL.createObjectURL(download.blob);
      const anchor = document.createElement('a');
      anchor.href = objectUrl;
      anchor.download = download.filename;
      anchor.rel = 'noopener';
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(objectUrl);
    } catch (error) {
      setDownloadError(describeKnowledgeError(error));
    } finally {
      setDownloading(false);
    }
  };

  return (
    <Drawer destroyOnHidden onClose={onClose} open size="min(560px, 96vw)" title="引用详情">
      <Space
        data-testid="citation-detail"
        orientation="vertical"
        size="middle"
        style={{ width: '100%' }}
      >
        <Flex align="center" gap={8} wrap>
          <Tag color="blue">{citationSourceText(citation)}</Tag>
          <Typography.Text type="secondary">引用 [{citation.ordinal}]</Typography.Text>
          <Tag style={citationStatusTagStyle(citation.sourceStatus)} data-testid="citation-status">
            {availability}
          </Tag>
          {lowConfidence && <Tag style={citationStatusTagStyle('UPDATED')}>置信度低</Tag>}
        </Flex>

        {masked ? (
          <Alert
            data-testid="citation-masked"
            description={citationAvailabilityDescription(citation)}
            showIcon
            title={availability}
            type="warning"
          />
        ) : (
          <>
            {citation.sourceStatus === 'UPDATED' && (
              <Alert
                data-testid="citation-updated"
                description={citationAvailabilityDescription(citation)}
                showIcon
                title="源内容已更新"
                type="warning"
              />
            )}
            <div>
              <Typography.Text type="secondary">来源标题</Typography.Text>
              <Typography.Title
                data-testid="citation-title"
                level={4}
                style={{ margin: '4px 0 0' }}
              >
                {citation.title || '未返回标题'}
              </Typography.Title>
            </div>

            {citation.locatorType === 'SHEET' && region && (
              <section aria-labelledby="citation-excel-locator">
                <Typography.Text id="citation-excel-locator" strong>
                  工作表定位
                </Typography.Text>
                <table className="citation-locator-table" data-testid="citation-locator">
                  <thead>
                    <tr>
                      <th scope="col">工作表</th>
                      <th scope="col">单元格/区域</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr className="citation-locator-row">
                      <td>
                        <mark>{citation.sheetName}</mark>
                      </td>
                      <td>
                        <mark>{citation.cellRange ?? '整表'}</mark>
                      </td>
                    </tr>
                  </tbody>
                </table>
                <Typography.Text data-testid="citation-region" type="secondary">
                  {region}
                </Typography.Text>
              </section>
            )}

            {citation.locatorType !== 'SHEET' && (
              <Descriptions
                bordered
                column={1}
                items={[
                  { key: 'locator', label: '定位', children: locator },
                  ...(bbox
                    ? [
                        {
                          key: 'bbox',
                          label: '页内区域',
                          children: <span data-testid="citation-bbox">{bbox}</span>,
                        },
                      ]
                    : []),
                  ...(confidence
                    ? [
                        {
                          key: 'confidence',
                          label: 'OCR 置信度',
                          children: <span data-testid="citation-confidence">{confidence}</span>,
                        },
                      ]
                    : []),
                ]}
                size="small"
              />
            )}

            {citation.locatorType === 'SHEET' && confidence && (
              <Typography.Text data-testid="citation-confidence" type="secondary">
                {confidence}
              </Typography.Text>
            )}

            {lowConfidence && (
              <Alert
                data-testid="citation-low-confidence"
                description="OCR 识别置信度较低，请核对原文后再引用。"
                showIcon
                title="置信度低"
                type="warning"
              />
            )}

            <div>
              <Typography.Text strong>命中文本块</Typography.Text>
              <Typography.Paragraph
                aria-label="命中文本块"
                className="citation-snippet"
                data-testid="citation-snippet"
              >
                {citation.snippet ? <mark>{citation.snippet}</mark> : '未返回片段'}
              </Typography.Paragraph>
            </div>

            {citation.sourceType === 'KNOWLEDGE' && (
              <Alert
                description={KNOWLEDGE_SHARING_NOTICE}
                showIcon
                title="知识库来源"
                type="info"
              />
            )}

            {citation.sourceType === 'KNOWLEDGE' &&
              (hasKnowledgeSource ? (
                <Button
                  data-testid="citation-download"
                  icon={<DownloadOutlined />}
                  loading={downloading}
                  onClick={() => {
                    void handleKnowledgeDownload();
                  }}
                  type="primary"
                >
                  下载源文件
                </Button>
              ) : (
                <Alert
                  data-testid="citation-no-source"
                  description="该引用没有关联可下载的源文件；命中片段与定位仍可见。"
                  showIcon
                  title="该文档没有可下载的源文件"
                  type="info"
                />
              ))}

            {downloadError && (
              <Alert
                data-testid="citation-download-error"
                description={downloadError}
                showIcon
                title="下载失败"
                type="error"
              />
            )}

            {!fileUnavailable && onOpenFile && citation.fileId && (
              <Button
                icon={<FileSearchOutlined />}
                loading={fileDownloading}
                onClick={onOpenFile}
                type="primary"
              >
                打开原文件
              </Button>
            )}

            {fileError && (
              <Alert
                data-testid="citation-file-error"
                description={fileError}
                showIcon
                title="无法打开原文件"
                type="error"
              />
            )}

            {fileUnavailable && <Typography.Text type="secondary">源文件不可访问</Typography.Text>}
          </>
        )}
      </Space>
    </Drawer>
  );
}
