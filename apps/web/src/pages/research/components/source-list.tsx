import { Alert, Typography } from 'antd';
import type { Citation } from '@/services/api/types';
import { citationLocatorLabel } from '@/services/research/format';
import { StatusTag } from './status-tag';

/** 检索来源列表属性。 */
export interface SourceListProps {
  sources: Citation[];
}

/**
 * 检索阶段命中列表。
 *
 * 只展示契约返回的标题、片段与定位；不拼装或补全来源内容，
 * 避免在无命中时出现看似可信的伪造引用。
 *
 * @param props 检索命中
 * @returns 来源列表或空态提示
 */
export function SourceList({ sources }: SourceListProps) {
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
    <ul aria-label="检索命中来源" className="research-source-list">
      {sources.map((source) => (
        <li key={`${source.ordinal}-${source.title}`}>
          <div className="research-source-header">
            <StatusTag tone={source.sourceType === 'FILE' ? 'file' : 'knowledge'}>
              [{source.ordinal}] {source.sourceType === 'FILE' ? '文件' : '知识库'}
            </StatusTag>
            <Typography.Text strong>{source.title}</Typography.Text>
            <Typography.Text type="secondary">{citationLocatorLabel(source)}</Typography.Text>
          </div>
          <Typography.Paragraph className="research-source-snippet">
            {source.snippet}
          </Typography.Paragraph>
        </li>
      ))}
    </ul>
  );
}
