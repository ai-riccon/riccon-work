import { DownloadOutlined, ReloadOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Empty, Skeleton, Space, Typography } from 'antd';
import { useState } from 'react';
import { citationListLabel, isCitationMasked, toCitationView } from '@/services/knowledge';
import type { CitationView } from '@/services/knowledge';
import { CitationDetailDrawer } from '@/pages/knowledge/components/citation-detail-drawer';
import type { ArtifactSourceType, ResearchTaskDetail } from '@/services/api/types';
import { formatBytes } from '@/services/files/file-validation';
import {
  artifactCitationTarget,
  artifactSourceLabel,
  citationLocatorLabel,
  formatDateTime,
  formatDuration,
  isMarkdownContent,
  parseArtifactMarkdown,
  reportDownloadFilename,
  sourceLocatorLabel,
} from '@/services/research/format';
import { StatusTag } from './status-tag';

/** 产物面板属性。 */
export interface ArtifactPanelProps {
  detail: ResearchTaskDetail;
  body: string | null;
  bodyLoading: boolean;
  bodyError: string | null;
  /** 产物正文的同源下载地址；为空表示当前没有可下载版本。 */
  artifactContentUrl: string | null;
  highlightedAnchor: string | null;
  onReloadBody: () => void;
  onLocate: (anchorId: string) => void;
}

/**
 * 展示报告产物：标题、摘要、分节正文、引用清单与数据来源。
 *
 * 正文通过 API 同源内容端点按版本读取；引用清单中的每条记录都可定位到任务时间线，
 * 让审批、工具调用与报告结论能人工抽查对应关系。
 *
 * @param props 任务详情、正文与回调
 * @returns 产物面板
 */
export function ArtifactPanel({
  detail,
  body,
  bodyLoading,
  bodyError,
  artifactContentUrl,
  highlightedAnchor,
  onReloadBody,
  onLocate,
}: ArtifactPanelProps) {
  const artifact = detail.artifact;
  const version = artifact?.currentVersionDetail ?? null;
  const [downloadError, setDownloadError] = useState<string | null>(null);

  /**
   * 通过同源内容端点下载报告。
   *
   * 下载地址与 API 同源并携带会话 Cookie，不依赖对象存储 CORS；
   * 不把正文读入内存，因此正文超过在线预览上限时仍可下载。
   */
  const handleDownload = (): void => {
    if (!artifact || !version || !artifactContentUrl) {
      setDownloadError('暂未找到可下载的产物版本，请稍后重试。');
      return;
    }
    setDownloadError(null);
    const anchor = document.createElement('a');
    anchor.href = artifactContentUrl;
    anchor.download = reportDownloadFilename(artifact.title ?? '市场调研报告');
    anchor.rel = 'noopener';
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
  };

  if (!artifact || !version) {
    return (
      <section aria-labelledby="research-artifact-title" className="research-artifact">
        <Typography.Title id="research-artifact-title" level={2}>
          报告产物
        </Typography.Title>
        <Empty
          description={emptyArtifactHint(detail.status)}
          image={Empty.PRESENTED_IMAGE_SIMPLE}
        />
      </section>
    );
  }

  const reportDocument = body ? parseArtifactMarkdown(body) : null;
  const previewable = isMarkdownContent(version.contentType);

  return (
    <section aria-labelledby="research-artifact-title" className="research-artifact">
      <Typography.Title id="research-artifact-title" level={2}>
        报告产物
      </Typography.Title>
      <Card
        extra={
          <Space wrap>
            <Button icon={<DownloadOutlined />} onClick={handleDownload}>
              {previewable ? '下载 Markdown' : '下载产物'}
            </Button>
            <Button
              icon={<ReloadOutlined />}
              loading={bodyLoading}
              onClick={onReloadBody}
              size="small"
            >
              重新加载正文
            </Button>
          </Space>
        }
        title={
          <div className="research-artifact-heading">
            <Typography.Title level={3} style={{ margin: 0 }}>
              {artifact.title}
            </Typography.Title>
            <Space size={[8, 4]} wrap>
              <StatusTag tone="success">已保存</StatusTag>
              <StatusTag tone="default">v{version.version}</StatusTag>
              <Typography.Text type="secondary">
                {formatBytes(version.sizeBytes)} · 生成于 {formatDateTime(version.createdAt)}
              </Typography.Text>
            </Space>
          </div>
        }
      >
        {version.summary && (
          <Typography.Paragraph className="research-artifact-summary">
            {version.summary}
          </Typography.Paragraph>
        )}

        {downloadError && (
          <Alert
            showIcon
            description={downloadError}
            style={{ marginBottom: 12 }}
            title="下载失败"
            type="error"
          />
        )}

        {!previewable ? (
          <Alert
            showIcon
            description={`该报告正文格式为 ${version.contentType}，暂不支持在线预览；请点击右上角“下载产物”获取。`}
            title="暂不支持在线预览"
            type="info"
          />
        ) : bodyLoading ? (
          <div aria-busy="true" aria-label="正在加载报告正文">
            <Skeleton active paragraph={{ rows: 6 }} />
          </div>
        ) : bodyError ? (
          <Alert
            showIcon
            action={
              <Button onClick={onReloadBody} size="small">
                重试
              </Button>
            }
            description={bodyError}
            title="报告正文加载失败"
            type="error"
          />
        ) : reportDocument ? (
          <div className="research-artifact-body">
            {reportDocument.sections.length === 0 ? (
              <Empty description="正文为空" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            ) : (
              reportDocument.sections.map((section, index) => (
                <section
                  aria-label={section.heading ?? `正文第 ${index + 1} 节`}
                  className="research-artifact-section"
                  key={`${section.heading ?? 'section'}-${index}`}
                >
                  {section.heading && (
                    <Typography.Title level={4}>{section.heading}</Typography.Title>
                  )}
                  {section.blocks.map((block, blockIndex) =>
                    block.type === 'paragraph' ? (
                      <Typography.Paragraph key={`paragraph-${blockIndex}`}>
                        {block.text}
                      </Typography.Paragraph>
                    ) : (
                      <ul key={`bullets-${blockIndex}`}>
                        {block.items.map((item) => (
                          <li key={item}>{item}</li>
                        ))}
                      </ul>
                    ),
                  )}
                </section>
              ))
            )}
          </div>
        ) : null}

        <CitationList detail={detail} highlightedAnchor={highlightedAnchor} onLocate={onLocate} />

        <section aria-labelledby="artifact-sources-title" className="artifact-sources">
          <Typography.Title id="artifact-sources-title" level={3}>
            数据来源
          </Typography.Title>
          <ul className="artifact-source-list">
            {detail.sources.map((source) => (
              <li key={`source-${source.ordinal}`}>
                <Typography.Text>
                  {source.title}
                  <Typography.Text type="secondary">
                    （{sourceLocatorLabel(source)}）
                  </Typography.Text>
                </Typography.Text>
                <Button onClick={() => onLocate('timeline-RETRIEVAL')} size="small" type="link">
                  定位检索阶段
                  <span className="research-sr-only">：{source.title}</span>
                </Button>
              </li>
            ))}
            {detail.mcpInvocations.map((invocation) => (
              <li key={`mcp-${invocation.id}`}>
                <Typography.Text>
                  MCP 工具 {invocation.toolName}
                  <Typography.Text type="secondary">
                    （耗时 {formatDuration(invocation.durationMs)}）
                  </Typography.Text>
                </Typography.Text>
                <Button
                  onClick={() => onLocate(`timeline-invocation-${invocation.id}`)}
                  size="small"
                  type="link"
                >
                  定位工具调用
                  <span className="research-sr-only">：{invocation.toolName}</span>
                </Button>
              </li>
            ))}
          </ul>
          {detail.sources.length === 0 && detail.mcpInvocations.length === 0 && (
            <Typography.Text type="secondary">本次报告没有外部数据来源。</Typography.Text>
          )}
        </section>
      </Card>
    </section>
  );
}

/**
 * 产物引用来源对应的标签色调。
 *
 * @param sourceType 引用来源
 * @returns 标签色调
 */
function artifactSourceTone(sourceType: ArtifactSourceType): 'file' | 'knowledge' | 'mcp' {
  switch (sourceType) {
    case 'FILE':
      return 'file';
    case 'MCP':
      return 'mcp';
    case 'KNOWLEDGE':
    default:
      return 'knowledge';
  }
}

/**
 * 产物为空时的状态说明。
 *
 * @param status 任务状态
 * @returns 中文说明
 */
function emptyArtifactHint(status: ResearchTaskDetail['status']): string {
  switch (status) {
    case 'FAILED':
      return '任务失败，未生成报告；已完成阶段记录仍保留在时间线中。';
    case 'CANCELLED':
      return '任务已终止，未生成报告。';
    case 'WAITING_APPROVAL':
      return '审批通过并完成生成后，报告会显示在这里。';
    case 'SUCCEEDED':
      return '任务已完成，但没有可展示的产物版本。';
    case 'PENDING':
    case 'RUNNING':
    default:
      return '报告生成中，完成后会显示在这里。';
  }
}

/** 引用清单属性。 */
interface CitationListProps {
  detail: ResearchTaskDetail;
  highlightedAnchor: string | null;
  onLocate: (anchorId: string) => void;
}

/**
 * 产物引用清单；每条都能定位到时间线。
 *
 * @param props 详情与定位回调
 * @returns 引用清单
 */
function CitationList({ detail, highlightedAnchor, onLocate }: CitationListProps) {
  const citations = detail.artifact?.currentVersionDetail?.citations ?? [];
  const [activeCitation, setActiveCitation] = useState<CitationView | null>(null);
  if (citations.length === 0) {
    return (
      <section aria-labelledby="artifact-citations-title" className="artifact-citations">
        <Typography.Title id="artifact-citations-title" level={3}>
          引用清单
        </Typography.Title>
        <Typography.Text type="secondary">该版本没有引用条目。</Typography.Text>
      </section>
    );
  }

  return (
    <section aria-labelledby="artifact-citations-title" className="artifact-citations">
      <Typography.Title id="artifact-citations-title" level={3}>
        引用清单
      </Typography.Title>
      <Typography.Paragraph type="secondary">
        每条引用对应任务时间线中的检索命中或 MCP 调用，可点击定位后人工抽查。
      </Typography.Paragraph>
      <ol className="artifact-citation-list">
        {citations.map((citation) => {
          const target = artifactCitationTarget(citation, detail);
          const view = toCitationView(citation);
          const masked = isCitationMasked(view);
          return (
            <li key={`citation-${citation.ordinal}`}>
              <div className="artifact-citation-header">
                <Typography.Text strong>
                  {masked ? citationListLabel(view) : `[${citation.ordinal}] ${citation.title}`}
                </Typography.Text>
                <StatusTag tone={artifactSourceTone(citation.sourceType)}>
                  {artifactSourceLabel(citation.sourceType)}
                </StatusTag>
                <Typography.Text type="secondary">{citationLocatorLabel(citation)}</Typography.Text>
                {!target.exact && <StatusTag tone="warning">未精确匹配</StatusTag>}
              </div>
              {!masked && (
                <Typography.Paragraph className="artifact-citation-snippet">
                  {citation.snippet}
                </Typography.Paragraph>
              )}
              <Button
                aria-label={`查看引用详情：${citationListLabel(view)}`}
                data-testid={`artifact-citation-${citation.ordinal}`}
                onClick={() => setActiveCitation(view)}
                size="small"
                type="link"
              >
                查看引用详情
              </Button>
              <Button onClick={() => onLocate(target.anchorId)} size="small" type="link">
                在时间线中查看引用 {citation.ordinal} · {target.label}
              </Button>
            </li>
          );
        })}
      </ol>
      {highlightedAnchor === null && (
        <Typography.Text type="secondary">
          提示：点击“在时间线中查看”可高亮对应阶段。
        </Typography.Text>
      )}
      <CitationDetailDrawer citation={activeCitation} onClose={() => setActiveCitation(null)} />
    </section>
  );
}
