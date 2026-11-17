import { describe, expect, it } from 'vitest';
import type {
  ArtifactCitation,
  Citation,
  ResearchTaskDetail,
  RunStageRecord,
} from '@/services/api/types';
import {
  artifactCitationTarget,
  artifactSourceLabel,
  approvalStatusColor,
  approvalStatusLabel,
  buildStageTimeline,
  citationLocatorLabel,
  describeResearchFailure,
  formatDateTime,
  formatDuration,
  formatParamsPreview,
  formatShortId,
  isMarkdownContent,
  mcpInvocationStatusColor,
  mcpInvocationStatusLabel,
  parseArtifactMarkdown,
  reportDownloadFilename,
  researchStatusColor,
  researchStatusLabel,
  sourceLocatorLabel,
  stageStatusLabel,
  stageThoughtChainStatus,
} from './format';
import {
  makeApproval,
  makeArtifact,
  makeInvocation,
  makeResearchDetail,
  RESEARCH_STAGES,
  RESEARCH_SOURCE,
} from '@/test/fake-api';

/**
 * J2 展示规则单测。
 *
 * 这些函数决定界面文案与阶段状态，覆盖全部枚举与边界，
 * 页面测试只需要验证组合与交互，不必重复穷举状态。
 */
describe('J2 展示规则', () => {
  it('报告任务状态与颜色一一对应', () => {
    expect(researchStatusLabel('PENDING')).toBe('排队中');
    expect(researchStatusLabel('RUNNING')).toBe('运行中');
    expect(researchStatusLabel('WAITING_APPROVAL')).toBe('等待审批');
    expect(researchStatusLabel('SUCCEEDED')).toBe('已完成');
    expect(researchStatusLabel('FAILED')).toBe('失败');
    expect(researchStatusLabel('CANCELLED')).toBe('已终止');

    expect(researchStatusColor('PENDING')).toBe('default');
    expect(researchStatusColor('RUNNING')).toBe('processing');
    expect(researchStatusColor('WAITING_APPROVAL')).toBe('warning');
    expect(researchStatusColor('SUCCEEDED')).toBe('success');
    expect(researchStatusColor('CANCELLED')).toBe('default');
    expect(researchStatusColor('FAILED')).toBe('error');
  });

  it('阶段状态文案与 ThoughtChain 状态映射完整', () => {
    expect(stageStatusLabel('RUNNING')).toBe('进行中');
    expect(stageStatusLabel('SUCCEEDED')).toBe('已完成');
    expect(stageStatusLabel('FAILED')).toBe('失败');
    expect(stageStatusLabel('SKIPPED')).toBe('已跳过');
    expect(stageStatusLabel('PENDING')).toBe('等待中');

    expect(stageThoughtChainStatus('RUNNING')).toBe('loading');
    expect(stageThoughtChainStatus('SUCCEEDED')).toBe('success');
    expect(stageThoughtChainStatus('FAILED')).toBe('error');
    expect(stageThoughtChainStatus('SKIPPED')).toBe('abort');
    expect(stageThoughtChainStatus('PENDING')).toBeUndefined();
  });

  it('审批与 MCP 调用状态文案完整', () => {
    expect(approvalStatusLabel('PENDING')).toBe('待审批');
    expect(approvalStatusLabel('APPROVED')).toBe('已批准');
    expect(approvalStatusLabel('REJECTED')).toBe('已拒绝');
    expect(approvalStatusLabel('EXPIRED')).toBe('已过期');
    expect(approvalStatusLabel('CANCELLED')).toBe('已取消');
    expect(approvalStatusColor('PENDING')).toBe('warning');
    expect(approvalStatusColor('APPROVED')).toBe('success');
    expect(approvalStatusColor('REJECTED')).toBe('error');
    expect(approvalStatusColor('EXPIRED')).toBe('error');
    expect(approvalStatusColor('CANCELLED')).toBe('default');

    expect(mcpInvocationStatusLabel('STARTED')).toBe('调用中');
    expect(mcpInvocationStatusLabel('SUCCEEDED')).toBe('成功');
    expect(mcpInvocationStatusLabel('FAILED')).toBe('失败');
    expect(mcpInvocationStatusLabel('DENIED')).toBe('已拒绝');
    expect(mcpInvocationStatusColor('STARTED')).toBe('processing');
    expect(mcpInvocationStatusColor('SUCCEEDED')).toBe('success');
    expect(mcpInvocationStatusColor('DENIED')).toBe('warning');
    expect(mcpInvocationStatusColor('FAILED')).toBe('error');
  });

  it('引用来源文案覆盖文件、知识库与 MCP', () => {
    expect(artifactSourceLabel('FILE')).toBe('文件');
    expect(artifactSourceLabel('KNOWLEDGE')).toBe('知识库');
    expect(artifactSourceLabel('MCP')).toBe('MCP 工具');
  });

  it('时间统一按北京时间格式化，非法输入显示占位', () => {
    expect(formatDateTime('2026-11-17T08:00:00.000Z')).toBe('2026-11-17 16:00:00');
    expect(formatDateTime(null)).toBe('—');
    expect(formatDateTime(undefined)).toBe('—');
    expect(formatDateTime('not-a-date')).toBe('—');
  });

  it('耗时格式化覆盖毫秒、秒与分钟边界', () => {
    expect(formatDuration(null)).toBe('—');
    expect(formatDuration(undefined)).toBe('—');
    expect(formatDuration(Number.NaN)).toBe('—');
    expect(formatDuration(-1)).toBe('—');
    expect(formatDuration(0)).toBe('0 ms');
    expect(formatDuration(999)).toBe('999 ms');
    expect(formatDuration(1000)).toBe('1.0 s');
    expect(formatDuration(59_900)).toBe('59.9 s');
    expect(formatDuration(60_000)).toBe('1 分 0 秒');
    expect(formatDuration(90_500)).toBe('1 分 31 秒');
  });

  it('短 ID 保留完整短值并截断长值', () => {
    expect(formatShortId(null)).toBe('—');
    expect(formatShortId('abc')).toBe('abc');
    expect(formatShortId('1234567890')).toBe('12345678…');
    expect(formatShortId('1234567890', 4)).toBe('1234…');
  });

  it('入参摘要格式化为键值行并截断长值', () => {
    expect(formatParamsPreview(null)).toBe('—');
    expect(formatParamsPreview({})).toBe('（无参数）');
    expect(
      formatParamsPreview({
        market: '企业协同办公',
        limit: 10,
        readOnly: true,
        empty: null,
        filters: { region: '华东' },
      }),
    ).toBe(
      [
        'market: 企业协同办公',
        'limit: 10',
        'readOnly: true',
        'empty: —',
        'filters: {"region":"华东"}',
      ].join('\n'),
    );
    expect(formatParamsPreview({ query: 'x'.repeat(200) })).toContain('…');

    const circular: Record<string, unknown> = {};
    circular.self = circular;
    expect(formatParamsPreview(circular)).toBe('self: （无法展示）');
  });

  it('失败描述区分可重试与必须新建任务', () => {
    const base: Pick<ResearchTaskDetail, 'status' | 'failureCode' | 'failureMessage' | 'stages'> = {
      status: 'SUCCEEDED',
      failureCode: null,
      failureMessage: null,
      stages: [],
    };
    expect(describeResearchFailure(base)).toEqual({ stage: null, message: '', retryable: false });

    const failedTool: RunStageRecord = {
      stage: 'TOOL',
      attempt: 1,
      status: 'FAILED',
      errorCode: 'RICCON-MCP-504',
    };
    expect(
      describeResearchFailure({
        ...base,
        status: 'FAILED',
        stages: [failedTool],
      }),
    ).toEqual({
      stage: 'TOOL',
      message: '外部工具调用失败，请重试工具阶段。',
      retryable: true,
    });

    expect(
      describeResearchFailure({
        ...base,
        status: 'FAILED',
        failureCode: 'RICCON-RESEARCH-404',
        stages: [{ stage: 'RETRIEVAL', attempt: 1, status: 'FAILED' }],
      }),
    ).toEqual({
      stage: 'RETRIEVAL',
      message: '未检索到相关资料，请调整主题后重新创建任务。',
      retryable: false,
    });

    expect(
      describeResearchFailure({
        ...base,
        status: 'FAILED',
        stages: [{ stage: 'GENERATION', attempt: 1, status: 'FAILED' }],
      }).retryable,
    ).toBe(true);

    expect(
      describeResearchFailure({
        ...base,
        status: 'FAILED',
        stages: [{ stage: 'APPROVAL', attempt: 1, status: 'FAILED' }],
      }).message,
    ).toBe('审批被拒绝，任务已终止。');

    expect(
      describeResearchFailure({
        ...base,
        status: 'FAILED',
        stages: [],
      }),
    ).toEqual({ stage: null, message: '任务执行失败，请重试。', retryable: false });
  });

  it('等待审批时产出四阶段时间线，审批节点为进行中', () => {
    const detail = makeResearchDetail();
    const timeline = buildStageTimeline(detail);

    expect(timeline.map((item) => item.stage)).toEqual([
      'RETRIEVAL',
      'TOOL',
      'APPROVAL',
      'GENERATION',
    ]);
    expect(timeline.map((item) => item.status)).toEqual([
      'SUCCEEDED',
      'PENDING',
      'RUNNING',
      'PENDING',
    ]);
    expect(timeline[1].approval?.id).toBe('approval-1');
    expect(timeline[2].approval?.id).toBe('approval-1');
    expect(timeline[2].startedAt).toBe('2026-11-17T08:00:02.000Z');
    expect(timeline[2].finishedAt).toBeNull();
  });

  it('审批拒绝后工具阶段跳过、审批阶段失败', () => {
    const rejected = makeApproval({
      status: 'REJECTED',
      decidedBy: 'user-2',
      decidedAt: '2026-11-17T08:04:00.000Z',
      decisionComment: '数据范围过大',
    });
    const detail = makeResearchDetail({
      status: 'CANCELLED',
      currentStage: null,
      failureCode: 'RICCON-APPROVAL-REJECTED',
      failureMessage: '审批被拒绝，任务已终止。',
      approvals: [rejected],
      stages: [
        ...RESEARCH_STAGES,
        { stage: 'APPROVAL', attempt: 1, status: 'FAILED', errorCode: 'RICCON-APPROVAL-REJECTED' },
        { stage: 'GENERATION', attempt: 1, status: 'SKIPPED' },
      ],
    });
    const timeline = buildStageTimeline(detail);

    expect(timeline[1].status).toBe('SKIPPED');
    expect(timeline[2].status).toBe('FAILED');
    expect(timeline[2].finishedAt).toBe('2026-11-17T08:04:00.000Z');
    // CANCELLED 不属于失败任务，失败描述不启用重试。
    expect(describeResearchFailure(detail).retryable).toBe(false);
  });

  it('完成的详情所有阶段成功并挂载最后一次工具调用', () => {
    const invocation = makeInvocation();
    const succeeded = makeResearchDetail({
      status: 'SUCCEEDED',
      currentStage: null,
      approvals: [
        makeApproval({
          status: 'APPROVED',
          decidedAt: '2026-11-17T08:04:00.000Z',
        }),
      ],
      mcpInvocations: [invocation],
      stages: [
        { stage: 'RETRIEVAL', attempt: 1, status: 'SUCCEEDED' },
        { stage: 'TOOL', attempt: 1, status: 'SUCCEEDED' },
        { stage: 'APPROVAL', attempt: 1, status: 'SUCCEEDED' },
        { stage: 'GENERATION', attempt: 1, status: 'SUCCEEDED' },
      ],
      artifact: makeArtifact(),
    });
    const timeline = buildStageTimeline(succeeded);

    expect(timeline.every((item) => item.status === 'SUCCEEDED')).toBe(true);
    expect(timeline[1].invocation?.id).toBe('invocation-1');
    expect(timeline[3].invocation).toBeNull();
  });

  it('排队中的任务不把当前阶段误标为进行中', () => {
    const detail = makeResearchDetail({
      status: 'PENDING',
      currentStage: 'RETRIEVAL',
      approvals: [],
      stages: [],
      mcpInvocations: [],
    });
    expect(buildStageTimeline(detail).map((item) => item.status)).toEqual([
      'PENDING',
      'PENDING',
      'PENDING',
      'PENDING',
    ]);
  });

  it('没有阶段记录的成功任务回退为全部成功', () => {
    const detail = makeResearchDetail({
      status: 'SUCCEEDED',
      currentStage: null,
      approvals: [],
      stages: [],
      mcpInvocations: [],
    });
    expect(buildStageTimeline(detail).map((item) => item.status)).toEqual([
      'SUCCEEDED',
      'SUCCEEDED',
      'SUCCEEDED',
      'SUCCEEDED',
    ]);
  });

  it('失败阶段没有记录时按 failureCode 标记', () => {
    const detail = makeResearchDetail({
      status: 'FAILED',
      currentStage: null,
      failureCode: 'RICCON-MODEL-500',
      failureMessage: '报告生成失败。',
      approvals: [makeApproval({ status: 'APPROVED' })],
      stages: [{ stage: 'RETRIEVAL', attempt: 1, status: 'SUCCEEDED' }],
      mcpInvocations: [],
    });
    const timeline = buildStageTimeline(detail);

    expect(timeline[1].status).toBe('PENDING');
    expect(timeline[2].status).toBe('SUCCEEDED');
    // 失败描述按“最后失败记录”判断阶段；没有失败记录时 stage 为空，界面只能展示任务级原因。
    expect(describeResearchFailure(detail)).toEqual({
      stage: null,
      message: '报告生成失败。',
      retryable: false,
    });
  });

  it('解析报告正文的标题、分节、段落与列表', () => {
    const document = parseArtifactMarkdown(
      [
        '# 报告标题',
        '',
        '## 摘要',
        '第一段。',
        '第二行。',
        '',
        '- 要点一',
        '- 要点二',
        '',
        '## 竞争格局',
        '### 头部厂商',
        '份额集中。',
        '',
        '结尾段落。',
      ].join('\r\n'),
    );

    expect(document.title).toBe('报告标题');
    expect(document.sections).toHaveLength(2);
    expect(document.sections[0]).toEqual({
      heading: '摘要',
      blocks: [
        { type: 'paragraph', text: '第一段。 第二行。' },
        { type: 'bullets', items: ['要点一', '要点二'] },
      ],
    });
    expect(document.sections[1].heading).toBe('竞争格局');
    expect(document.sections[1].blocks).toEqual([
      { type: 'paragraph', text: '头部厂商' },
      { type: 'paragraph', text: '份额集中。' },
      { type: 'paragraph', text: '结尾段落。' },
    ]);
  });

  it('无标题正文合并为单节，空正文返回空分节', () => {
    expect(parseArtifactMarkdown('只有一段正文。')).toEqual({
      title: null,
      sections: [{ heading: null, blocks: [{ type: 'paragraph', text: '只有一段正文。' }] }],
    });
    expect(parseArtifactMarkdown('   \n\n  ')).toEqual({ title: null, sections: [] });
    expect(parseArtifactMarkdown('## 只有标题')).toEqual({
      title: null,
      sections: [{ heading: '只有标题', blocks: [] }],
    });
  });

  it('引用定位：知识库指向检索，MCP 指向具体调用', () => {
    const detail = { mcpInvocations: [makeInvocation()] };
    const knowledge: ArtifactCitation = {
      ordinal: 1,
      sourceType: 'KNOWLEDGE',
      knowledgeDocumentId: 'doc-1',
      title: '知识库资料',
      snippet: '片段',
    };
    expect(artifactCitationTarget(knowledge, detail)).toEqual({
      anchorId: 'timeline-RETRIEVAL',
      label: '检索阶段',
      exact: true,
    });

    const file: ArtifactCitation = {
      ordinal: 2,
      sourceType: 'FILE',
      fileId: 'file-1',
      title: '文件',
      snippet: '片段',
    };
    expect(artifactCitationTarget(file, detail).anchorId).toBe('timeline-RETRIEVAL');

    const mcp: ArtifactCitation = {
      ordinal: 3,
      sourceType: 'MCP',
      mcpInvocationId: 'invocation-1',
      title: 'MCP',
      snippet: '片段',
    };
    expect(artifactCitationTarget(mcp, detail)).toEqual({
      anchorId: 'timeline-invocation-invocation-1',
      label: '工具调用：market_data_lookup',
      exact: true,
    });

    const missing: ArtifactCitation = {
      ordinal: 4,
      sourceType: 'MCP',
      mcpInvocationId: 'missing',
      title: 'MCP',
      snippet: '片段',
    };
    expect(artifactCitationTarget(missing, detail)).toEqual({
      anchorId: 'timeline-TOOL',
      label: '工具阶段（未找到具体调用）',
      exact: false,
    });
  });

  it('引用定位文案覆盖页码、工作表、段落与未知', () => {
    expect(citationLocatorLabel({ locatorType: 'PAGE', pageNo: 3 })).toBe('第 3 页');
    expect(citationLocatorLabel({ locatorType: 'PAGE' })).toBe('页码未知');
    expect(citationLocatorLabel({ locatorType: 'SHEET', sheetName: '销售数据' })).toBe(
      '工作表 销售数据',
    );
    expect(citationLocatorLabel({ locatorType: 'SHEET' })).toBe('工作表未知');
    expect(citationLocatorLabel({ locatorType: 'PARAGRAPH', paragraphNo: 4 })).toBe('第 4 段');
    expect(citationLocatorLabel({ locatorType: 'PARAGRAPH' })).toBe('段落未知');
    expect(citationLocatorLabel({})).toBe('定位未知');
  });

  it('正文类型与下载文件名符合预览与导出约束', () => {
    expect(isMarkdownContent('text/markdown; charset=utf-8')).toBe(true);
    expect(isMarkdownContent('text/plain')).toBe(true);
    expect(isMarkdownContent('application/json')).toBe(false);

    expect(reportDownloadFilename('2026 市场报告')).toBe('2026 市场报告.md');
    expect(reportDownloadFilename('a/b:c*?"<>|')).toBe('a-b-c------.md');
    expect(reportDownloadFilename('   ')).toBe('市场调研报告.md');
    expect(reportDownloadFilename('x'.repeat(120))).toHaveLength(83);
  });

  it('检索来源定位区分文件与知识库', () => {
    const knowledge: Citation = RESEARCH_SOURCE;
    const file: Citation = {
      ...RESEARCH_SOURCE,
      sourceType: 'FILE',
      locatorType: 'PAGE',
      pageNo: 2,
    };
    expect(sourceLocatorLabel(knowledge)).toBe('知识库 · 第 4 段');
    expect(sourceLocatorLabel(file)).toBe('文件 · 第 2 页');
  });

  it('MCP 调用与阶段记录一一对应时保留全部尝试', () => {
    const invocation = makeInvocation({ id: 'invocation-2', status: 'FAILED' });
    const stages: RunStageRecord[] = [
      { stage: 'TOOL', attempt: 1, status: 'FAILED', errorCode: 'RICCON-MCP-504' },
      { stage: 'TOOL', attempt: 2, status: 'FAILED', errorCode: 'RICCON-MCP-504' },
    ];
    const detail = makeResearchDetail({
      status: 'FAILED',
      currentStage: null,
      stages,
      mcpInvocations: [invocation],
      approvals: [],
    });
    const timeline = buildStageTimeline(detail);
    expect(timeline[1].attempts).toHaveLength(2);
    expect(timeline[1].status).toBe('FAILED');
    expect(timeline[1].errorCode).toBe('RICCON-MCP-504');
    expect(timeline[1].invocation?.id).toBe('invocation-2');
  });
});
