import { Alert, Button, Typography } from 'antd';
import { useState } from 'react';
import type { Citation } from '@/services/api/types';
import { citationListLabel, isCitationMasked, toCitationView } from '@/services/knowledge';
import type { CitationView, KnowledgeApi } from '@/services/knowledge';
import { citationLocatorLabel } from '@/services/research/format';
import { CitationDetailDrawer } from '@/pages/knowledge/components/citation-detail-drawer';
import { StatusTag } from './status-tag';

/** 检索来源列表属性。 */
export interface SourceListProps {
  sources: Citation[];
  /** 知识库下载能力；测试可注入。 */
  knowledgeApi?: Pick<KnowledgeApi, 'downloadDocument'>;
}

/**
 * 检索阶段命中列表。
 *
 * 只展示契约返回的标题、片段与定位；点击条目打开引用详情，可查看
 * 工作表/区域、页码/坐标、命中文本块与 OCR 置信度。`NO_ACCESS`/`DELETED`
 * 引用不展示标题、片段或定位。
 *
 * @param props 检索命中
 * @returns 来源列表或空态提示
 */
export function SourceList({ sources, knowledgeApi }: SourceListProps) {
  const [active, setActive] = useState<CitationView | null>(null);

  if (sources.length === 0) {
    return (
      <Alert
        showIcon
        description="未检索到相关资料。请调整调研主题后重新创建任务，任务不会进入生成阶段。"
        title="没有检索命中"
        type="warning"
      />
    );
  }

  return (
    <>
      <ul aria-label="检索命中来源" className="research-source-list">
        {sources.map((source) => {
          const view = toCitationView(source);
          const masked = isCitationMasked(view);
          return (
            <li key={`${source.ordinal}-${source.title || 'masked'}`}>
              <Button
                aria-label={`查看引用详情：${citationListLabel(view)}`}
                className="research-source-button"
                data-testid={`research-source-${source.ordinal}`}
                onClick={() => setActive(view)}
                type="text"
              >
                <span className="research-source-header">
                  <StatusTag tone={source.sourceType === 'FILE' ? 'file' : 'knowledge'}>
                    [{source.ordinal}] {source.sourceType === 'FILE' ? '文件' : '知识库'}
                  </StatusTag>
                  <Typography.Text strong>
                    {masked ? citationListLabel(view).replace(/^\[\d+\]\s*/, '') : source.title}
                  </Typography.Text>
                  <Typography.Text type="secondary">{citationLocatorLabel(source)}</Typography.Text>
                </span>
              </Button>
              {!masked && (
                <Typography.Paragraph className="research-source-snippet">
                  {source.snippet}
                </Typography.Paragraph>
              )}
            </li>
          );
        })}
      </ul>
      <CitationDetailDrawer
        citation={active}
        knowledgeApi={knowledgeApi}
        onClose={() => setActive(null)}
      />
    </>
  );
}
