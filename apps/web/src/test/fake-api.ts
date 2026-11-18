import { vi } from 'vitest';
import type { RicconApi } from '@/services/api/client';
import { ApiError } from '@/services/api/errors';
import type {
  Approval,
  ArtifactDetail,
  Citation,
  ChatEvent,
  Conversation,
  ConversationDetail,
  McpInvocation,
  McpTool,
  Message,
  ModelInvocation,
  Page,
  ResearchTask,
  ResearchTaskDetail,
  RunDetail,
  RunStageRecord,
  SessionInfo,
  Workspace,
  WorkspaceFile,
} from '@/services/api/types';

/** 测试用会话；字段与 OpenAPI `SessionInfo` 对齐。 */
export const DEMO_SESSION: SessionInfo = {
  authenticated: true,
  user: {
    id: 'user-1',
    username: 'demo',
    displayName: '演示员工',
    email: null,
    status: 'ACTIVE',
    roles: ['MEMBER'],
    lastLoginAt: '2026-11-17T08:00:00.000Z',
    createdAt: '2026-11-17T08:00:00.000Z',
  },
  tenant: {
    id: 'tenant-a',
    name: '示例企业',
    status: 'ACTIVE',
    retentionDays: 180,
    createdAt: '2026-11-17T08:00:00.000Z',
  },
  roles: ['MEMBER'],
  permissions: ['workspace.read', 'conversation.read', 'conversation.write'],
  csrfToken: 'csrf-token-for-tests',
  expiresAt: '2026-11-17T09:00:00.000Z',
};

/** 测试用工作空间。 */
export const DEMO_WORKSPACE: Workspace = {
  id: 'workspace-1',
  kind: 'PERSONAL',
  name: '我的工作空间',
  description: null,
  ownerUserId: 'user-1',
  status: 'ACTIVE',
  createdAt: '2026-11-17T08:00:00.000Z',
  updatedAt: '2026-11-17T08:00:00.000Z',
};

/**
 * 构造分页响应。
 *
 * @param items 列表项
 * @returns Page
 */
export function pageOf<T>(items: T[]): Page<T> {
  return {
    page: 1,
    size: 100,
    total: items.length,
    totalPages: items.length > 0 ? 1 : 0,
    items,
  };
}

/**
 * 构造会话。
 *
 * @param overrides 局部覆盖
 * @returns Conversation
 */
export function makeConversation(overrides: Partial<Conversation> = {}): Conversation {
  return {
    id: 'conversation-1',
    workspaceId: DEMO_WORKSPACE.id,
    title: '示例会话',
    status: 'ACTIVE',
    lastMessageAt: '2026-11-17T08:10:00.000Z',
    createdAt: '2026-11-17T08:00:00.000Z',
    updatedAt: '2026-11-17T08:10:00.000Z',
    ...overrides,
  };
}

/**
 * 构造服务端消息。
 *
 * @param overrides 局部覆盖
 * @returns Message
 */
export function makeMessage(overrides: Partial<Message> = {}): Message {
  return {
    id: 'message-1',
    conversationId: 'conversation-1',
    seq: 1,
    role: 'USER',
    content: '请总结文件',
    status: 'COMPLETED',
    citations: [],
    createdAt: '2026-11-17T08:10:00.000Z',
    ...overrides,
  };
}

/**
 * 构造文件。
 *
 * @param overrides 局部覆盖
 * @returns WorkspaceFile
 */
export function makeFile(overrides: Partial<WorkspaceFile> = {}): WorkspaceFile {
  return {
    id: 'file-1',
    workspaceId: DEMO_WORKSPACE.id,
    conversationId: 'conversation-1',
    originalName: '季度报告.pdf',
    format: 'PDF',
    mimeType: 'application/pdf',
    sizeBytes: 2048,
    sha256: 'a'.repeat(64),
    status: 'AVAILABLE',
    parse: {
      id: 'parse-1',
      attempt: 1,
      status: 'SUCCEEDED',
      pageCount: 3,
      chunkCount: 4,
      errorCode: null,
      errorMessage: null,
      startedAt: '2026-11-17T08:00:00.000Z',
      finishedAt: '2026-11-17T08:00:01.000Z',
    },
    createdAt: '2026-11-17T08:00:00.000Z',
    deletedAt: null,
    ...overrides,
  };
}

/**
 * 构造会话详情。
 *
 * @param messages 消息列表
 * @param overrides 会话覆盖
 * @returns ConversationDetail
 */
export function makeDetail(
  messages: Message[] = [],
  overrides: Partial<Conversation> = {},
): ConversationDetail {
  const conversation = makeConversation(overrides);
  return { ...conversation, messages };
}

/** 构造完成的 SSE 事件。 */
export function completedEvent(overrides: Partial<ChatEvent> = {}): ChatEvent {
  return {
    eventId: 'event-completed',
    eventType: 'riccon.chat.completed.v1',
    version: 1,
    occurredAt: '2026-11-17T08:10:00.000Z',
    tenantId: 'tenant-a',
    runId: 'run-1',
    messageId: 'assistant-1',
    traceId: 'trace-1',
    data: { finishReason: 'stop', firstTokenMs: 120 },
    ...overrides,
  } as ChatEvent;
}

/**
 * 构造报告任务摘要。
 *
 * @param overrides 局部覆盖
 * @returns ResearchTask
 */
export function makeResearchTask(overrides: Partial<ResearchTask> = {}): ResearchTask {
  return {
    id: 'research-task-1',
    workspaceId: DEMO_WORKSPACE.id,
    topic: '2026 年企业协同办公市场规模与竞争格局',
    status: 'RUNNING',
    currentStage: 'RETRIEVAL',
    mcpToolId: 'mcp-tool-1',
    currentRunId: 'run-research-1',
    failureCode: null,
    failureMessage: null,
    createdAt: '2026-11-17T08:00:00.000Z',
    startedAt: '2026-11-17T08:00:01.000Z',
    finishedAt: null,
    ...overrides,
  };
}

/** 一条知识库检索命中。 */
export const RESEARCH_SOURCE: Citation = {
  ordinal: 1,
  sourceType: 'KNOWLEDGE',
  knowledgeDocumentId: 'doc-1',
  locatorType: 'PARAGRAPH',
  paragraphNo: 4,
  title: '知识库：2025 协同办公市场研究',
  snippet: '2025 年国内协同办公市场规模约为 320 亿元，同比增长 18%。',
  score: 0.91,
};

/** 阶段时间线：检索成功、等待工具审批。 */
export const RESEARCH_STAGES: RunStageRecord[] = [
  {
    stage: 'RETRIEVAL',
    attempt: 1,
    status: 'SUCCEEDED',
    startedAt: '2026-11-17T08:00:01.000Z',
    finishedAt: '2026-11-17T08:00:02.000Z',
    details: { hitCount: 1 },
  },
];

/** 待审批记录。 */
export function makeApproval(overrides: Partial<Approval> = {}): Approval {
  return {
    id: 'approval-1',
    researchTaskId: 'research-task-1',
    runId: 'run-research-1',
    stage: 'TOOL',
    attempt: 1,
    status: 'PENDING',
    actionType: 'MCP_TOOL_CALL',
    toolName: 'market_data_lookup',
    paramsPreview: { market: '企业协同办公', region: '华东', period: '2025' },
    paramsSha256: 'b'.repeat(64),
    requestedBy: DEMO_SESSION.user.id,
    decidedBy: null,
    decidedAt: null,
    decisionComment: null,
    expiresAt: '2026-11-17T09:00:00.000Z',
    createdAt: '2026-11-17T08:00:02.000Z',
    ...overrides,
  };
}

/** 成功的 MCP 调用记录。 */
export function makeInvocation(overrides: Partial<McpInvocation> = {}): McpInvocation {
  return {
    id: 'invocation-1',
    toolName: 'market_data_lookup',
    status: 'SUCCEEDED',
    paramsSha256: 'b'.repeat(64),
    resultSha256: 'c'.repeat(64),
    durationMs: 680,
    errorCode: null,
    startedAt: '2026-11-17T08:03:00.000Z',
    finishedAt: '2026-11-17T08:03:00.680Z',
    ...overrides,
  };
}

/** 产物版本详情。 */
export function makeArtifact(overrides: Partial<ArtifactDetail> = {}): ArtifactDetail {
  const version = {
    id: 'artifact-version-1',
    version: 1,
    contentType: 'text/markdown',
    sizeBytes: 2048,
    sha256: 'd'.repeat(64),
    summary: '报告基于知识库检索与市场数据工具生成，包含市场规模、竞争格局与建议。',
    createdAt: '2026-11-17T08:05:00.000Z',
    citations: [
      {
        ordinal: 1,
        sourceType: 'KNOWLEDGE' as const,
        knowledgeDocumentId: 'doc-1',
        title: '知识库：2025 协同办公市场研究',
        snippet: '2025 年国内协同办公市场规模约为 320 亿元。',
        locatorType: 'PARAGRAPH' as const,
        paragraphNo: 4,
      },
      {
        ordinal: 2,
        sourceType: 'MCP' as const,
        mcpInvocationId: 'invocation-1',
        title: 'MCP：market_data_lookup',
        snippet: '返回 3 条市场规模与增速记录（演示数据）。',
        locatorType: null,
      },
    ],
  };
  return {
    id: 'artifact-1',
    workspaceId: DEMO_WORKSPACE.id,
    researchTaskId: 'research-task-1',
    kind: 'REPORT',
    title: '2026 年企业协同办公市场调研报告',
    status: 'SAVED',
    currentVersion: 1,
    currentVersionDetail: version,
    createdAt: '2026-11-17T08:05:00.000Z',
    updatedAt: '2026-11-17T08:05:00.000Z',
    ...overrides,
  };
}

/** 报告正文 Markdown。 */
export const ARTIFACT_MARKDOWN = [
  '# 2026 年企业协同办公市场调研报告',
  '',
  '## 摘要',
  '市场保持两位数增长，知识库与工具取数是报告的主要数据来源。',
  '',
  '## 市场规模与增长',
  '2025 年国内协同办公市场规模约为 320 亿元[1]。',
  '- 2024 年同比增长 18%',
  '- 预计 2026 年增速维持在 15% 左右',
  '',
  '## 竞争格局',
  '头部厂商份额集中，中腰部厂商通过行业方案差异化竞争[2]。',
].join('\n');

/**
 * 构造报告任务详情。
 *
 * @param overrides 局部覆盖
 * @returns ResearchTaskDetail
 */
export function makeResearchDetail(
  overrides: Partial<ResearchTaskDetail> = {},
): ResearchTaskDetail {
  return {
    ...makeResearchTask({ status: 'WAITING_APPROVAL', currentStage: 'APPROVAL' }),
    stages: RESEARCH_STAGES,
    approvals: [makeApproval()],
    mcpInvocations: [],
    sources: [RESEARCH_SOURCE],
    artifact: null,
    ...overrides,
  };
}

/**
 * 构造模型调用记录。
 *
 * @param overrides 局部覆盖
 * @returns ModelInvocation
 */
export function makeModelInvocation(overrides: Partial<ModelInvocation> = {}): ModelInvocation {
  return {
    id: 'model-invocation-1',
    modelId: 'model-1',
    modelKey: 'demo-chat-model',
    purpose: 'CHAT',
    status: 'SUCCEEDED',
    firstTokenMs: 320,
    durationMs: 1850,
    promptTokens: 128,
    completionTokens: 96,
    errorCode: null,
    startedAt: '2026-11-17T08:10:00.000Z',
    finishedAt: '2026-11-17T08:10:02.000Z',
    ...overrides,
  };
}

/**
 * 构造运行详情。
 *
 * @param overrides 局部覆盖
 * @returns RunDetail
 */
export function makeRunDetail(overrides: Partial<RunDetail> = {}): RunDetail {
  return {
    id: 'run-1',
    kind: 'CHAT',
    status: 'SUCCEEDED',
    currentStage: null,
    attempt: 1,
    errorCode: null,
    errorMessage: null,
    startedAt: '2026-11-17T08:10:00.000Z',
    finishedAt: '2026-11-17T08:10:02.000Z',
    modelInvocations: [makeModelInvocation()],
    stages: [],
    ...overrides,
  };
}

/** 已授权只读 MCP 工具。 */
export const GRANTED_MCP_TOOL: McpTool = {
  id: 'mcp-tool-1',
  serverId: null,
  name: 'market_data_lookup',
  description: '只读市场数据查询（演示）',
  riskLevel: 'READ',
  requiresApproval: true,
  status: 'ACTIVE',
};

/**
 * 创建可注入的 fake API。
 *
 * 默认行为是“已登录、一个工作空间、空会话列表”，测试按需覆盖单个方法；
 * 所有方法都是 vi.fn，便于断言调用次数与参数。
 *
 * @param overrides 局部覆盖
 * @returns RicconApi
 */
export function createFakeApi(overrides: Partial<RicconApi> = {}): RicconApi {
  let conversationSequence = 0;
  const base: RicconApi = {
    getSession: vi.fn(async () => DEMO_SESSION),
    login: vi.fn(async () => DEMO_SESSION),
    logout: vi.fn(async () => undefined),
    listWorkspaces: vi.fn(async () => pageOf([DEMO_WORKSPACE])),
    listConversations: vi.fn(async () => pageOf([])),
    createConversation: vi.fn(async (workspaceId: string) => {
      conversationSequence += 1;
      return makeConversation({
        id: `conversation-new-${conversationSequence}`,
        workspaceId,
        title: null,
      });
    }),
    getConversation: vi.fn(async () => {
      throw new ApiError('会话不存在', {
        status: 404,
        code: 'RICCON-TENANT-404',
      });
    }),
    listMessages: vi.fn(async () => pageOf<Message>([])),
    listFiles: vi.fn(async () => pageOf<WorkspaceFile>([])),
    getFile: vi.fn(async (fileId: string) => makeFile({ id: fileId })),
    uploadFile: vi.fn(async (_workspaceId: string, conversationId: string, file: File) =>
      makeFile({ id: `file-${file.name}`, conversationId, originalName: file.name }),
    ),
    retryFileParse: vi.fn(async (fileId: string) =>
      makeFile({
        id: fileId,
        parse: {
          id: 'parse-retry',
          attempt: 2,
          status: 'PENDING',
          errorCode: null,
          errorMessage: null,
          startedAt: '2026-11-17T08:00:02.000Z',
          finishedAt: null,
        },
      }),
    ),
    getFileDownloadUrl: vi.fn(async () => ({
      url: 'https://files.example.test/report.pdf',
      expiresAt: '2026-11-17T09:00:00.000Z',
    })),
    sendMessage: vi.fn(async function* sendMessageGenerator() {
      yield completedEvent();
    }),
    stopGeneration: vi.fn(async () => undefined),
    getRun: vi.fn(async (runId: string) => makeRunDetail({ id: runId })),
    listResearchTasks: vi.fn(async () => pageOf<ResearchTask>([])),
    createResearchTask: vi.fn(async (workspaceId: string, input) =>
      makeResearchTask({
        id: 'research-task-new',
        workspaceId,
        topic: input.topic,
        mcpToolId: input.mcpToolId,
        status: 'PENDING',
        currentStage: null,
      }),
    ),
    getResearchTask: vi.fn(async (researchTaskId: string) =>
      makeResearchDetail({ id: researchTaskId }),
    ),
    retryResearchTask: vi.fn(async (researchTaskId: string) =>
      makeResearchTask({
        id: researchTaskId,
        status: 'RUNNING',
        failureCode: null,
        failureMessage: null,
      }),
    ),
    listGrantedMcpTools: vi.fn(async () => pageOf<McpTool>([GRANTED_MCP_TOOL])),
    decideApproval: vi.fn(async (approvalId: string) =>
      makeApproval({
        id: approvalId,
        status: 'APPROVED',
        decidedBy: DEMO_SESSION.user.id,
        decidedAt: '2026-11-17T08:04:00.000Z',
      }),
    ),
    getArtifact: vi.fn(async (artifactId: string) => ({
      ...makeArtifact(),
      id: artifactId,
    })),
    listArtifactVersions: vi.fn(async () => pageOf([makeArtifact().currentVersionDetail!])),
    listArtifacts: vi.fn(async () => pageOf([makeArtifact()])),
    getArtifactVersion: vi.fn(async (_artifactId: string, version: number) => {
      const detail = makeArtifact();
      return {
        ...detail.currentVersionDetail!,
        version,
      };
    }),
    getArtifactDownloadUrl: vi.fn(async (artifactId: string, version: number) => ({
      url: `https://files.example.test/artifacts/${artifactId}/versions/${version}`,
      expiresAt: '2026-11-17T09:00:00.000Z',
    })),
    getArtifactContentUrl: vi.fn(
      (artifactId: string, version: number) =>
        `/api/v1/artifacts/${artifactId}/versions/${version}/content`,
    ),
    fetchArtifactContent: vi.fn(async () => ARTIFACT_MARKDOWN),
  };

  return { ...base, ...overrides };
}
