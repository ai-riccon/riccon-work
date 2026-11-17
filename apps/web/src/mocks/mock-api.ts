import type {
  Approval,
  ArtifactDetail,
  ChatEvent,
  Citation,
  Conversation,
  McpInvocation,
  McpTool,
  Message,
  ResearchTask,
  ResearchTaskDetail,
  RunStageRecord,
  SessionInfo,
  Workspace,
  WorkspaceFile,
} from '@/services/api/types';
import {
  MAX_FILES_PER_CONVERSATION,
  MAX_FILE_BYTES,
  validateFile,
} from '@/services/files/file-validation';
import { MOCK_CREDENTIALS } from '@/services/api/config';

/**
 * J1 本地演示适配层。
 *
 * 用途：
 * 1. `RICCON_API_MOCK=1` 时接管 fetch，让前端在没有后端的环境下演示/跑 E2E；
 * 2. 严格走真实 ApiClient 的 CSRF、ProblemDetail、SSE 与分页路径，不绕过生产代码；
 * 3. 只在显式开关下启用，生产构建默认 false，因此不会伪造成功响应。
 *
 * 数据只包含合成演示内容，保存在 sessionStorage，刷新后可继续；不读取任何真实凭据。
 */

/** sessionStorage 键；版本变化时自动丢弃旧结构。 */
const STORAGE_KEY = 'riccon.j1.mock.v2';

/** 解析状态推进时间：400ms 进入 RUNNING，900ms 进入终态。 */
const PARSE_RUNNING_AFTER_MS = 400;
const PARSE_FINISH_AFTER_MS = 900;

/** SSE 增量间隔；E2E 有足够时间点击停止。 */
const DELTA_INTERVAL_MS = 180;

/** J2 阶段推进时间；与轮询间隔配合，保证 E2E 1 秒内能看到阶段切换。 */
const RESEARCH_RETRIEVAL_MS = 500;
const RESEARCH_TOOL_MS = 600;
const RESEARCH_GENERATION_MS = 800;

/** 演示工作空间与用户。 */
const DEMO_WORKSPACE_ID = '11111111-1111-4111-8111-111111111111';
const DEMO_USER_ID = '22222222-2222-4222-8222-222222222222';
const DEMO_TENANT_ID = 'tenant-acme';

/** 演示 MCP 工具与知识库文档 ID；只用于合成数据，不对应真实对象。 */
const DEMO_MCP_TOOL_ID = '44444444-4444-4444-8444-444444444444';
const DEMO_KNOWLEDGE_ID_ONE = '55555555-5555-4555-8555-555555555555';
const DEMO_KNOWLEDGE_ID_TWO = '66666666-6666-4666-8666-666666666666';

/** 演示文件/消息的创建时间基准，避免每次刷新随机变化。 */
const BASE_TIME = '2026-11-17T08:00:00.000Z';

interface MockParseOutcome {
  /** 终态是否失败；重试后改为成功。 */
  fail: boolean;
}

interface MockState {
  authenticated: boolean;
  csrfToken: string;
  workspace: Workspace;
  conversations: Conversation[];
  /** 会话 ID -> 消息列表。 */
  messages: Record<string, Message[]>;
  files: WorkspaceFile[];
  /** 文件 ID -> 解析终态。 */
  parseOutcomes: Record<string, MockParseOutcome>;
  /** J2 报告任务内部状态；对外只暴露契约字段。 */
  researchTasks: MockResearchTask[];
  /** 本租户已授权的 MCP 工具。 */
  mcpTools: McpTool[];
  /** 创建任务的幂等键 -> 任务 ID。 */
  researchIdempotency: Record<string, string>;
}

/** 演示任务可触发的失败路径；由主题关键词选择，便于 E2E 复现。 */
type MockResearchMode = 'success' | 'tool-failure' | 'generation-failure' | 'no-sources';

/** 演示任务阶段。 */
type MockResearchPhase =
  'retrieval' | 'waiting-approval' | 'tool' | 'generation' | 'done' | 'failed' | 'cancelled';

/** J2 演示任务；内部字段不进入 API 响应。 */
interface MockResearchTask {
  task: ResearchTask;
  mode: MockResearchMode;
  phase: MockResearchPhase;
  /** 当前阶段开始时间（毫秒），用于按真实时间推进。 */
  phaseStartedAtMs: number;
  stages: RunStageRecord[];
  approvals: Approval[];
  invocations: McpInvocation[];
  sources: Citation[];
  artifact: ArtifactDetail | null;
  artifactBody: string | null;
}

interface ActiveRun {
  runId: string;
  conversationId: string;
  assistantId: string;
  messageId: string;
  content: string;
  citations: Citation[];
  queue: ChatEvent[];
  cursor: number;
  timer: number | null;
  closed: boolean;
  cancelled: boolean;
}

/** 进程内活动运行；不持久化，刷新后流自然结束。 */
const activeRuns = new Map<string, ActiveRun>();

/** 简单自增计数器，用于 traceId 与兼容旧环境的 ID。 */
let idCounter = 0;

/**
 * 生成 HTML 安全的唯一 ID。
 *
 * @returns UUID 或带前缀的稳定 ID
 */
function nextId(prefix: string): string {
  if (typeof globalThis.crypto?.randomUUID === 'function') {
    return globalThis.crypto.randomUUID();
  }
  idCounter += 1;
  return `${prefix}-${Date.now()}-${idCounter}`;
}

/**
 * 生成 mock traceId。
 *
 * @returns 短 trace 标识
 */
function nextTraceId(): string {
  idCounter += 1;
  return `mock-trace-${Date.now()}-${idCounter}`;
}

/** 创建初始演示状态。 */
function createInitialState(): MockState {
  return {
    authenticated: false,
    csrfToken: `mock-csrf-${nextId('csrf')}`,
    workspace: {
      id: DEMO_WORKSPACE_ID,
      kind: 'PERSONAL',
      name: '我的工作空间',
      description: '用于 J1 演示的个人工作空间',
      ownerUserId: DEMO_USER_ID,
      status: 'ACTIVE',
      createdAt: BASE_TIME,
      updatedAt: BASE_TIME,
    },
    conversations: [],
    messages: {},
    files: [],
    parseOutcomes: {},
    researchTasks: [],
    mcpTools: [
      {
        id: DEMO_MCP_TOOL_ID,
        serverId: null,
        name: 'market_data_lookup',
        description: '只读市场数据查询（演示数据，不连接真实外部系统）',
        riskLevel: 'READ',
        requiresApproval: true,
        status: 'ACTIVE',
      },
    ],
    researchIdempotency: {},
  };
}

/**
 * 从 sessionStorage 恢复状态。
 *
 * 解析失败或结构版本不符时回到初始状态，避免演示被旧数据卡死。
 *
 * @returns mock 状态
 */
function loadState(): MockState {
  try {
    const raw = globalThis.sessionStorage?.getItem(STORAGE_KEY);
    if (!raw) {
      return createInitialState();
    }
    const parsed = JSON.parse(raw) as Partial<MockState>;
    if (!parsed.workspace || !Array.isArray(parsed.conversations)) {
      return createInitialState();
    }
    return {
      ...createInitialState(),
      ...parsed,
      files: Array.isArray(parsed.files) ? parsed.files : [],
      messages: parsed.messages ?? {},
      parseOutcomes: parsed.parseOutcomes ?? {},
      researchTasks: Array.isArray(parsed.researchTasks) ? parsed.researchTasks : [],
      mcpTools:
        Array.isArray(parsed.mcpTools) && parsed.mcpTools.length > 0
          ? parsed.mcpTools
          : createInitialState().mcpTools,
      researchIdempotency: parsed.researchIdempotency ?? {},
    } as MockState;
  } catch {
    return createInitialState();
  }
}

/**
 * 持久化状态；隐私模式等无法写入时静默降级为内存态。
 *
 * @param state mock 状态
 */
function persist(state: MockState): void {
  try {
    globalThis.sessionStorage?.setItem(STORAGE_KEY, JSON.stringify(state));
  } catch {
    // 演示环境允许降级为内存态，不向用户抛出存储异常。
  }
}

/**
 * 构造 JSON 响应。
 *
 * @param data 响应体
 * @param status HTTP 状态
 * @returns Response
 */
function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
  });
}

/**
 * 构造 RFC 9457 ProblemDetail 响应。
 *
 * @param status HTTP 状态
 * @param code 业务错误码
 * @param title 短标题
 * @param detail 可读原因
 * @returns Response
 */
function problem(status: number, code: string, title: string, detail: string): Response {
  return new Response(
    JSON.stringify({
      type: 'about:blank',
      title,
      status,
      code,
      traceId: nextTraceId(),
      detail,
    }),
    {
      status,
      headers: { 'Content-Type': 'application/problem+json; charset=utf-8' },
    },
  );
}

/**
 * 构造当前会话响应。
 *
 * @param state mock 状态
 * @returns SessionInfo
 */
function sessionInfo(state: MockState): SessionInfo {
  return {
    authenticated: true,
    user: {
      id: DEMO_USER_ID,
      username: MOCK_CREDENTIALS.username,
      displayName: '演示员工',
      email: null,
      status: 'ACTIVE',
      roles: ['MEMBER'],
      lastLoginAt: BASE_TIME,
      createdAt: BASE_TIME,
    },
    tenant: {
      id: DEMO_TENANT_ID,
      name: '示例企业',
      status: 'ACTIVE',
      retentionDays: 180,
      createdAt: BASE_TIME,
    },
    roles: ['MEMBER'],
    permissions: [
      'workspace.read',
      'conversation.read',
      'conversation.write',
      'file.read',
      'file.upload',
      'report.read',
      'report.create',
      'report.approve',
      'artifact.read',
      'mcp.read',
    ],
    csrfToken: state.csrfToken,
    expiresAt: new Date(Date.now() + 30 * 60 * 1000).toISOString(),
  };
}

/**
 * 校验写请求的 CSRF 头。
 *
 * @param state mock 状态
 * @param init 请求初始化
 * @returns 通过返回 null，失败返回 403 响应
 */
function requireCsrf(state: MockState, init?: RequestInit): Response | null {
  const headers = new Headers(init?.headers);
  const token = headers.get('X-CSRF-TOKEN');
  if (!token || token !== state.csrfToken) {
    return problem(403, 'RICCON-AUTH-403', '安全令牌无效', '请刷新页面后重试。');
  }
  return null;
}

/**
 * 校验登录态。
 *
 * @param state mock 状态
 * @returns 已登录返回 null，否则返回 401 响应
 */
function requireSession(state: MockState): Response | null {
  if (!state.authenticated) {
    return problem(401, 'RICCON-AUTH-401', '未登录', '请先登录后再访问。');
  }
  return null;
}

/**
 * 生成 64 位十六进制伪哈希；演示不需要真实内容哈希。
 *
 * @param seed 种子文本
 * @returns 64 位十六进制字符串
 */
function pseudoSha256(seed: string): string {
  let hash = 2166136261;
  for (let index = 0; index < seed.length; index += 1) {
    hash ^= seed.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  const chunk = (hash >>> 0).toString(16).padStart(8, '0');
  return chunk.repeat(8).slice(0, 64);
}

/**
 * 推进文件解析状态。
 *
 * 用真实时间而非定时器，保证刷新后仍能根据 startedAt 继续推进；前端轮询会触发状态变化。
 *
 * @param state mock 状态
 */
function advanceParses(state: MockState): void {
  const now = Date.now();
  let changed = false;

  state.files.forEach((file) => {
    if (!file.parse || (file.parse.status !== 'PENDING' && file.parse.status !== 'RUNNING')) {
      return;
    }
    const startedAt = file.parse.startedAt ? Date.parse(file.parse.startedAt) : now;
    const elapsed = Math.max(0, now - startedAt);
    if (elapsed < PARSE_RUNNING_AFTER_MS) {
      return;
    }
    if (elapsed < PARSE_FINISH_AFTER_MS && file.parse.status !== 'RUNNING') {
      file.parse.status = 'RUNNING';
      changed = true;
      return;
    }
    if (elapsed >= PARSE_FINISH_AFTER_MS) {
      const outcome = state.parseOutcomes[file.id];
      if (outcome?.fail) {
        file.parse.status = 'FAILED';
        file.parse.errorCode = 'RICCON-FILE-500';
        file.parse.errorMessage = '文件内容无法解析，请确认文件未加密或未损坏后重试。';
      } else {
        file.parse.status = 'SUCCEEDED';
        file.parse.pageCount = file.format === 'PDF' ? 12 : null;
        file.parse.sheetCount = file.format === 'XLSX' ? 3 : null;
        file.parse.chunkCount = 8;
        file.parse.errorCode = null;
        file.parse.errorMessage = null;
      }
      file.parse.finishedAt = new Date(now).toISOString();
      changed = true;
    }
  });

  if (changed) {
    persist(state);
  }
}

/**
 * 根据文件生成引用。
 *
 * @param file 已解析文件
 * @param ordinal 引用序号
 * @returns 引用条目
 */
function citationForFile(file: WorkspaceFile, ordinal: number): Citation {
  const base = {
    ordinal,
    sourceType: 'FILE' as const,
    fileId: file.id,
    knowledgeDocumentId: null,
    chunkId: null,
    title: file.originalName,
    snippet: `演示引用片段：${file.originalName} 中与问题相关的内容。`,
    score: 0.82,
  };

  switch (file.format) {
    case 'XLSX':
      return {
        ...base,
        locatorType: 'SHEET',
        sheetName: '销售数据',
        cellRange: 'B2:D8',
        pageNo: null,
        paragraphNo: null,
      };
    case 'DOCX':
      return {
        ...base,
        locatorType: 'PARAGRAPH',
        paragraphNo: 5,
        pageNo: null,
        sheetName: null,
        cellRange: null,
      };
    case 'PNG':
    case 'JPEG':
      return {
        ...base,
        locatorType: 'PARAGRAPH',
        paragraphNo: 1,
        pageNo: null,
        sheetName: null,
        cellRange: null,
      };
    case 'PDF':
    default:
      return {
        ...base,
        locatorType: 'PAGE',
        pageNo: 3,
        sheetName: null,
        cellRange: null,
        paragraphNo: null,
      };
  }
}

/**
 * 生成演示回答文本。
 *
 * @param question 用户问题
 * @param citations 引用
 * @returns 完整回答
 */
function buildAnswer(question: string, citations: Citation[]): string {
  if (citations.length === 0) {
    return '当前知识库未检索到相关资料。你可以先上传文件，或用更具体的关键词重新提问。';
  }
  const first = citations[0];
  return [
    `根据你上传的文件《${first.title}》，我找到以下信息：`,
    '',
    `1. 文件已完成解析，可用于回答与「${question.slice(0, 40)}」相关的问题。`,
    `2. 引用位置：${first.locatorType === 'SHEET' ? `工作表 ${first.sheetName ?? ''}` : first.locatorType === 'PAGE' ? `第 ${first.pageNo ?? 1} 页` : `第 ${first.paragraphNo ?? 1} 段`}。`,
    '3. 回答内容仅用于演示 J1 流式与引用链路，不代表真实检索结果。',
    '',
    '如需进一步分析，可以继续追问，或上传更多文件。',
  ].join('\n');
}

/**
 * 构造 SSE 事件信封。
 *
 * @param runId 运行 ID
 * @param messageId 助手消息 ID
 * @param eventType 事件类型
 * @param data 事件数据
 * @returns ChatEvent
 */
function createEvent(
  runId: string,
  messageId: string,
  eventType: ChatEvent['eventType'],
  data: unknown,
): ChatEvent {
  return {
    eventId: nextId('event'),
    eventType,
    version: 1,
    occurredAt: new Date().toISOString(),
    tenantId: DEMO_TENANT_ID,
    runId,
    messageId,
    traceId: nextTraceId(),
    data,
  } as ChatEvent;
}

/**
 * 把事件编码为 SSE 帧。
 *
 * @param event 事件
 * @returns SSE 文本
 */
function encodeEvent(event: ChatEvent): string {
  return `event: ${event.eventType}\ndata: ${JSON.stringify(event)}\n\n`;
}

/**
 * 结束运行并持久化助手消息。
 *
 * @param state mock 状态
 * @param run 活动运行
 * @param status 终态
 */
function finalizeRun(state: MockState, run: ActiveRun, status: Message['status']): void {
  const messages = state.messages[run.conversationId] ?? [];
  const assistant = messages.find((message) => message.id === run.assistantId);
  if (assistant) {
    assistant.content = run.content;
    assistant.status = status;
    assistant.citations = run.citations;
    assistant.runId = run.runId;
  }
  const conversation = state.conversations.find((item) => item.id === run.conversationId);
  if (conversation) {
    conversation.lastMessageAt = new Date().toISOString();
    conversation.updatedAt = new Date().toISOString();
  }
  persist(state);
}

/**
 * 创建并返回活动运行的 SSE 响应。
 *
 * @param state mock 状态
 * @param run 活动运行
 * @param signal 请求取消信号
 * @returns SSE Response
 */
function createStreamResponse(
  state: MockState,
  run: ActiveRun,
  signal?: AbortSignal | null,
): Response {
  const encoder = new TextEncoder();

  const finishWith = (status: Message['status']) => {
    if (run.closed) {
      return;
    }
    run.closed = true;
    if (run.timer !== null) {
      globalThis.clearInterval(run.timer);
      run.timer = null;
    }
    finalizeRun(state, run, status);
    activeRuns.delete(run.runId);
  };

  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      let controllerClosed = false;
      const push = (event: ChatEvent) => {
        if (controllerClosed) {
          return;
        }
        try {
          controller.enqueue(encoder.encode(encodeEvent(event)));
        } catch {
          // 客户端已 abort 时流可能已被取消；终止写入，避免伪成功或未处理异常。
          controllerClosed = true;
        }
      };
      const close = () => {
        if (controllerClosed) {
          return;
        }
        controllerClosed = true;
        try {
          controller.close();
        } catch {
          // 同上：流已由取消路径关闭时无需再次关闭。
        }
      };

      run.timer = globalThis.setInterval(() => {
        if (run.cancelled) {
          push(
            createEvent(run.runId, run.messageId, 'riccon.chat.stopped.v1', {
              reason: 'USER_CANCELLED',
            }),
          );
          finishWith('STOPPED');
          close();
          return;
        }
        const event = run.queue[run.cursor];
        run.cursor += 1;
        if (!event) {
          finishWith('COMPLETED');
          close();
          return;
        }
        if (event.eventType === 'riccon.chat.delta.v1') {
          run.content += event.data.delta;
        } else if (event.eventType === 'riccon.chat.citation.v1') {
          run.citations = [...run.citations, ...event.data.citations];
        }
        push(event);
        if (event.eventType === 'riccon.chat.completed.v1') {
          finishWith('COMPLETED');
          close();
        } else if (event.eventType === 'riccon.chat.failed.v1') {
          finishWith('FAILED');
          close();
        }
      }, DELTA_INTERVAL_MS) as unknown as number;

      const abortHandler = () => {
        if (!run.closed) {
          run.cancelled = true;
          finishWith('STOPPED');
          close();
        }
      };
      signal?.addEventListener('abort', abortHandler, { once: true });
    },
    cancel() {
      // 浏览器中止 fetch 时触发；按 CLIENT_DISCONNECT 语义保留已生成内容。
      if (!run.closed) {
        run.cancelled = true;
        finishWith('STOPPED');
      }
    },
  });

  return new Response(body, {
    status: 200,
    headers: {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-store',
    },
  });
}

/**
 * 从请求体读取 JSON。
 *
 * @param init 请求初始化
 * @returns 解析结果；非字符串体返回 null
 */
function readJsonBody(init?: RequestInit): Record<string, unknown> | null {
  if (typeof init?.body !== 'string') {
    return null;
  }
  try {
    return JSON.parse(init.body) as Record<string, unknown>;
  } catch {
    return null;
  }
}

/**
 * 处理发送消息并创建 SSE。
 *
 * @param state mock 状态
 * @param conversationId 会话 ID
 * @param init 请求初始化
 * @returns SSE Response 或错误 Response
 */
function handleSendMessage(state: MockState, conversationId: string, init?: RequestInit): Response {
  const conversation = state.conversations.find((item) => item.id === conversationId);
  if (!conversation) {
    return problem(404, 'RICCON-TENANT-404', '会话不存在', '会话不存在或已被删除。');
  }

  const body = readJsonBody(init);
  const content = typeof body?.content === 'string' ? body.content.trim() : '';
  const clientMessageId = typeof body?.clientMessageId === 'string' ? body.clientMessageId : '';
  if (!content || content.length > 20000 || clientMessageId.length < 8) {
    return problem(400, 'RICCON-CHAT-400', '消息不合法', '消息内容为空或超出长度限制。');
  }

  const requestedFileIds = Array.isArray(body?.fileIds)
    ? (body.fileIds as unknown[]).filter((id): id is string => typeof id === 'string')
    : [];
  if (requestedFileIds.length > MAX_FILES_PER_CONVERSATION) {
    return problem(400, 'RICCON-CHAT-400', '附件过多', '单条消息最多附带 5 个文件。');
  }

  advanceParses(state);
  const files = requestedFileIds
    .map((fileId) => state.files.find((file) => file.id === fileId))
    .filter((file): file is WorkspaceFile => Boolean(file))
    .slice(0, MAX_FILES_PER_CONVERSATION);
  const usableFiles = files.filter((file) => file.parse?.status === 'SUCCEEDED');

  const messages = state.messages[conversationId] ?? [];
  const nextSeq = messages.reduce((max, message) => Math.max(max, message.seq), 0) + 1;
  const userMessage: Message = {
    id: nextId('message'),
    conversationId,
    seq: nextSeq,
    role: 'USER',
    content,
    status: 'COMPLETED',
    clientMessageId,
    citations: [],
    createdAt: new Date().toISOString(),
  };
  const assistantId = nextId('message');
  const assistantMessage: Message = {
    id: assistantId,
    conversationId,
    seq: nextSeq + 1,
    role: 'ASSISTANT',
    content: '',
    status: 'STREAMING',
    citations: [],
    createdAt: new Date().toISOString(),
  };
  state.messages[conversationId] = [...messages, userMessage, assistantMessage];

  if (!conversation.title) {
    conversation.title = content.length > 20 ? `${content.slice(0, 20)}…` : content;
  }
  conversation.updatedAt = new Date().toISOString();
  conversation.lastMessageAt = conversation.updatedAt;
  persist(state);

  const citations = usableFiles.slice(0, 2).map((file, index) => citationForFile(file, index + 1));
  const wantsFailure = /失败|出错|error/i.test(content);
  const wantsNoCitation = /无引用|无资料|没有资料/.test(content);
  const effectiveCitations = wantsNoCitation ? [] : citations;
  const answer = wantsNoCitation
    ? buildAnswer(content, [])
    : buildAnswer(content, effectiveCitations);
  const answerChunks = splitAnswer(answer);

  const runId = nextId('run');
  const run: ActiveRun = {
    runId,
    conversationId,
    assistantId,
    messageId: assistantId,
    content: '',
    citations: [],
    queue: [
      createEvent(runId, assistantId, 'riccon.chat.started.v1', {
        modelId: '33333333-3333-4333-8333-333333333333',
        modelKey: 'demo-fake-model',
      }),
      ...(effectiveCitations.length > 0
        ? [
            createEvent(runId, assistantId, 'riccon.chat.citation.v1', {
              citations: effectiveCitations,
            }),
          ]
        : []),
      ...answerChunks.map((delta) =>
        createEvent(runId, assistantId, 'riccon.chat.delta.v1', { delta }),
      ),
      wantsFailure
        ? createEvent(runId, assistantId, 'riccon.chat.failed.v1', {
            code: 'RICCON-MODEL-500',
            message: '演示模型调用失败，请重试。',
            retryable: true,
          })
        : createEvent(runId, assistantId, 'riccon.chat.completed.v1', {
            finishReason: 'stop',
            firstTokenMs: DELTA_INTERVAL_MS,
          }),
    ],
    cursor: 0,
    timer: null,
    closed: false,
    cancelled: false,
  };

  activeRuns.set(run.runId, run);
  return createStreamResponse(state, run, init?.signal);
}

/**
 * 把回答切分为小段，模拟流式输出。
 *
 * @param answer 完整回答
 * @returns 文本片段
 */
function splitAnswer(answer: string): string[] {
  const size = 24;
  const chunks: string[] = [];
  for (let index = 0; index < answer.length; index += size) {
    chunks.push(answer.slice(index, index + size));
  }
  return chunks;
}

/**
 * 判断请求路径是否匹配。
 *
 * @param pattern 正则
 * @param path 路径
 * @returns 匹配结果
 */
function match(pattern: RegExp, path: string): RegExpMatchArray | null {
  return path.match(pattern);
}

/**
 * 根据主题选择演示路径。
 *
 * 关键词只用于演示失败/重试与空检索，不影响生产代码。
 *
 * @param topic 调研主题
 * @returns 演示模式
 */
function researchModeForTopic(topic: string): MockResearchMode {
  if (/工具失败|超时/.test(topic)) {
    return 'tool-failure';
  }
  if (/生成失败/.test(topic)) {
    return 'generation-failure';
  }
  if (/无结果|没有资料/.test(topic)) {
    return 'no-sources';
  }
  return 'success';
}

/**
 * 构造两条知识库命中。
 *
 * @returns 合成引用
 */
function buildDemoSources(): Citation[] {
  return [
    {
      ordinal: 1,
      sourceType: 'KNOWLEDGE',
      knowledgeDocumentId: DEMO_KNOWLEDGE_ID_ONE,
      chunkId: null,
      locatorType: 'PARAGRAPH',
      paragraphNo: 4,
      title: '知识库：2025 中国企业协同办公市场研究',
      snippet: '2025 年国内协同办公市场规模约为 320 亿元，同比增长 18%，头部厂商份额继续集中。',
      score: 0.92,
    },
    {
      ordinal: 2,
      sourceType: 'KNOWLEDGE',
      knowledgeDocumentId: DEMO_KNOWLEDGE_ID_TWO,
      chunkId: null,
      locatorType: 'PARAGRAPH',
      paragraphNo: 9,
      title: '知识库：协同办公行业竞争格局摘录',
      snippet: '中腰部厂商通过行业解决方案与私有化部署差异化竞争，客户续费率是主要观察指标。',
      score: 0.84,
    },
  ];
}

/**
 * 构造审批记录。
 *
 * @param mock 演示任务
 * @param attempt 工具阶段尝试号
 * @param now 当前时间（毫秒）
 * @returns 待审批记录
 */
function createResearchApproval(mock: MockResearchTask, attempt: number, now: number): Approval {
  const paramsPreview: Record<string, unknown> = {
    market: mock.task.topic.slice(0, 60),
    region: '华东',
    period: '2025',
    fields: 'market_size,growth_rate',
  };
  const createdAt = new Date(now).toISOString();
  return {
    id: nextId('approval'),
    researchTaskId: mock.task.id,
    runId: mock.task.currentRunId ?? nextId('run'),
    stage: 'TOOL',
    attempt,
    status: 'PENDING',
    actionType: 'MCP_TOOL_CALL',
    toolName: 'market_data_lookup',
    paramsPreview,
    paramsSha256: pseudoSha256(JSON.stringify(paramsPreview)),
    requestedBy: DEMO_USER_ID,
    decidedBy: null,
    decidedAt: null,
    decisionComment: null,
    expiresAt: new Date(now + 15 * 60 * 1000).toISOString(),
    createdAt,
  };
}

/**
 * 生成报告正文 Markdown。
 *
 * @param title 报告标题
 * @param sources 检索来源
 * @param invocation 成功的工具调用
 * @returns Markdown 正文
 */
function buildReportBody(
  title: string,
  sources: Citation[],
  invocation: McpInvocation | null,
): string {
  const sourceTitles = sources.map((source) => `${source.title}（[${source.ordinal}]）`);
  return [
    `# ${title}`,
    '',
    '## 摘要',
    '本报告基于租户知识库检索与一次经人工审批的只读市场数据调用生成，结论仅用于演示，不代表真实市场数据。',
    '',
    '## 市场规模与增长',
    '2025 年国内协同办公市场规模约为 320 亿元，同比增长 18%[1]。',
    '- 头部厂商依靠生态与私有化交付能力保持份额',
    '- 中腰部厂商通过行业方案与数据工具提升续费率',
    '',
    '## 竞争格局',
    '竞争焦点从通用功能转向行业数据、权限隔离与可审计的 Agent 能力[2]。',
    invocation
      ? `MCP 工具 ${invocation.toolName} 返回的演示数据用于核对市场规模与增速[3]。`
      : '本次报告未使用外部工具数据。',
    '',
    '## 结论与建议',
    '建议先以部门级知识库和只读工具验证闭环，再逐步开放写操作与跨部门共享。',
    '',
    '## 数据来源',
    ...sourceTitles.map((item) => `- ${item}`),
    ...(invocation
      ? [`- MCP 工具 ${invocation.toolName}（耗时 ${invocation.durationMs ?? 0} ms）`]
      : []),
  ].join('\n');
}

/**
 * 确保任务只有一个产物；重复生成沿用同一产物，版本不重复递增。
 *
 * @param mock 演示任务
 * @param now 当前时间（毫秒）
 */
function ensureResearchArtifact(mock: MockResearchTask, now: number): void {
  if (mock.artifact) {
    return;
  }
  const invocation =
    [...mock.invocations].reverse().find((item) => item.status === 'SUCCEEDED') ?? null;
  const title = `${mock.task.topic} · 市场调研报告`.slice(0, 300);
  const body = buildReportBody(title, mock.sources, invocation);
  const nowIso = new Date(now).toISOString();
  const citations = [
    ...mock.sources.map((source) => ({
      ordinal: source.ordinal,
      sourceType: 'KNOWLEDGE' as const,
      knowledgeDocumentId: source.knowledgeDocumentId ?? null,
      title: source.title,
      snippet: source.snippet,
      locatorType: source.locatorType ?? null,
      pageNo: source.pageNo ?? null,
      sheetName: source.sheetName ?? null,
      paragraphNo: source.paragraphNo ?? null,
    })),
    ...(invocation
      ? [
          {
            ordinal: mock.sources.length + 1,
            sourceType: 'MCP' as const,
            mcpInvocationId: invocation.id,
            title: `MCP：${invocation.toolName}`,
            snippet: '返回 3 条市场规模与增速记录（演示数据）。',
            locatorType: null,
            pageNo: null,
            sheetName: null,
            paragraphNo: null,
          },
        ]
      : []),
  ];
  const artifactId = nextId('artifact');
  mock.artifact = {
    id: artifactId,
    workspaceId: mock.task.workspaceId,
    researchTaskId: mock.task.id,
    kind: 'REPORT',
    title,
    status: 'SAVED',
    currentVersion: 1,
    createdAt: nowIso,
    updatedAt: nowIso,
    currentVersionDetail: {
      id: nextId('artifact-version'),
      version: 1,
      contentType: 'text/markdown',
      sizeBytes: Math.max(1, body.length),
      sha256: pseudoSha256(body),
      summary: '报告基于知识库检索与经审批的市场数据工具生成，覆盖市场规模、竞争格局与建议。',
      createdAt: nowIso,
      citations,
    },
  };
  mock.artifactBody = body;
}

/**
 * 完成检索阶段。
 *
 * @param mock 演示任务
 * @param now 当前时间（毫秒）
 */
function completeResearchRetrieval(mock: MockResearchTask, now: number): void {
  const nowIso = new Date(now).toISOString();
  const record = mock.stages.find((stage) => stage.stage === 'RETRIEVAL' && stage.attempt === 1);
  if (record) {
    record.finishedAt = nowIso;
  }
  if (mock.mode === 'no-sources') {
    if (record) {
      record.status = 'FAILED';
      record.errorCode = 'RICCON-RESEARCH-404';
    }
    mock.task.status = 'FAILED';
    mock.task.currentStage = null;
    mock.task.failureCode = 'RICCON-RESEARCH-404';
    mock.task.failureMessage = '未检索到相关资料，请调整主题后重新创建任务。';
    mock.task.finishedAt = nowIso;
    mock.phase = 'failed';
    return;
  }
  if (record) {
    record.status = 'SUCCEEDED';
    record.details = { hitCount: 2 };
  }
  mock.sources = buildDemoSources();
  mock.task.status = 'WAITING_APPROVAL';
  mock.task.currentStage = 'APPROVAL';
  mock.approvals.push(createResearchApproval(mock, 1, now));
  mock.phase = 'waiting-approval';
  mock.phaseStartedAtMs = now;
}

/**
 * 完成工具阶段：成功则进入生成，失败则保留记录并标记任务失败。
 *
 * @param mock 演示任务
 * @param now 当前时间（毫秒）
 */
function completeResearchTool(mock: MockResearchTask, now: number): void {
  const nowIso = new Date(now).toISOString();
  const invocation = mock.invocations.find((item) => item.status === 'STARTED');
  const record = mock.stages
    .filter((stage) => stage.stage === 'TOOL' && stage.status === 'RUNNING')
    .sort((left, right) => right.attempt - left.attempt)[0];

  if (mock.mode === 'tool-failure') {
    if (record) {
      record.status = 'FAILED';
      record.errorCode = 'RICCON-MCP-504';
      record.finishedAt = nowIso;
    }
    if (invocation) {
      invocation.status = 'FAILED';
      invocation.errorCode = 'RICCON-MCP-504';
      invocation.durationMs = Math.max(0, now - Date.parse(invocation.startedAt));
      invocation.finishedAt = nowIso;
    }
    mock.task.status = 'FAILED';
    mock.task.currentStage = null;
    mock.task.failureCode = 'RICCON-MCP-504';
    mock.task.failureMessage = '外部数据工具响应超时（演示），请重试工具阶段。';
    mock.task.finishedAt = nowIso;
    mock.phase = 'failed';
    return;
  }

  if (record) {
    record.status = 'SUCCEEDED';
    record.finishedAt = nowIso;
    record.details = {
      ...(record.details ?? {}),
      resultSummary: '返回 3 条市场规模与增速记录（演示数据）。',
    };
  }
  if (invocation) {
    invocation.status = 'SUCCEEDED';
    invocation.resultSha256 = pseudoSha256('demo-market-data-result');
    invocation.durationMs = Math.max(0, now - Date.parse(invocation.startedAt));
    invocation.finishedAt = nowIso;
  }
  mock.task.status = 'RUNNING';
  mock.task.currentStage = 'GENERATION';
  const attempt = mock.stages.filter((stage) => stage.stage === 'GENERATION').length + 1;
  mock.stages.push({
    stage: 'GENERATION',
    attempt,
    status: 'RUNNING',
    startedAt: nowIso,
    finishedAt: null,
    details: null,
  });
  mock.phase = 'generation';
  mock.phaseStartedAtMs = now;
}

/**
 * 完成生成阶段。
 *
 * @param mock 演示任务
 * @param now 当前时间（毫秒）
 */
function completeResearchGeneration(mock: MockResearchTask, now: number): void {
  const nowIso = new Date(now).toISOString();
  const record = mock.stages
    .filter((stage) => stage.stage === 'GENERATION' && stage.status === 'RUNNING')
    .sort((left, right) => right.attempt - left.attempt)[0];

  if (mock.mode === 'generation-failure') {
    if (record) {
      record.status = 'FAILED';
      record.errorCode = 'RICCON-MODEL-500';
      record.finishedAt = nowIso;
    }
    mock.task.status = 'FAILED';
    mock.task.currentStage = null;
    mock.task.failureCode = 'RICCON-MODEL-500';
    mock.task.failureMessage = '报告生成失败（演示），已完成阶段记录已保留。';
    mock.task.finishedAt = nowIso;
    mock.phase = 'failed';
    return;
  }

  if (record) {
    record.status = 'SUCCEEDED';
    record.finishedAt = nowIso;
  }
  ensureResearchArtifact(mock, now);
  mock.task.status = 'SUCCEEDED';
  mock.task.currentStage = null;
  mock.task.finishedAt = nowIso;
  mock.phase = 'done';
}

/**
 * 按真实时间推进所有演示任务。
 *
 * 每次读取任务时推进，刷新后仍能继续；与解析状态同一思路，避免后台定时器在
 * 测试或页面关闭后继续运行。循环用于一次补完多个已到期阶段。
 *
 * @param state mock 状态
 */
function advanceResearchTasks(state: MockState): void {
  const now = Date.now();
  let changed = false;
  for (const mock of state.researchTasks) {
    let progressed = true;
    while (progressed) {
      progressed = false;
      if (mock.phase === 'retrieval' && now - mock.phaseStartedAtMs >= RESEARCH_RETRIEVAL_MS) {
        completeResearchRetrieval(mock, now);
        progressed = true;
        changed = true;
      } else if (mock.phase === 'tool' && now - mock.phaseStartedAtMs >= RESEARCH_TOOL_MS) {
        completeResearchTool(mock, now);
        progressed = true;
        changed = true;
      } else if (
        mock.phase === 'generation' &&
        now - mock.phaseStartedAtMs >= RESEARCH_GENERATION_MS
      ) {
        completeResearchGeneration(mock, now);
        progressed = true;
        changed = true;
      }
    }
  }
  if (changed) {
    persist(state);
  }
}

/**
 * 构造任务摘要响应。
 *
 * @param mock 演示任务
 * @returns 契约 ResearchTask
 */
function researchTaskSummary(mock: MockResearchTask): ResearchTask {
  return { ...mock.task };
}

/**
 * 构造任务详情响应。
 *
 * 只返回 OpenAPI 声明的字段，内部 phase/mode 不泄漏。
 *
 * @param mock 演示任务
 * @returns 契约 ResearchTaskDetail
 */
function researchTaskDetail(mock: MockResearchTask): ResearchTaskDetail {
  return {
    ...mock.task,
    stages: [...mock.stages].sort(compareStages),
    approvals: [...mock.approvals].sort(
      (left, right) => Date.parse(left.createdAt) - Date.parse(right.createdAt),
    ),
    mcpInvocations: [...mock.invocations].sort(
      (left, right) => Date.parse(left.startedAt) - Date.parse(right.startedAt),
    ),
    sources: [...mock.sources],
    artifact: mock.artifact,
  };
}

/**
 * 阶段时间线排序：有开始时间在前，其后按阶段尝试号。
 *
 * @param left 左记录
 * @param right 右记录
 * @returns 排序值
 */
function compareStages(left: RunStageRecord, right: RunStageRecord): number {
  const leftTime = left.startedAt ? Date.parse(left.startedAt) : Number.MAX_SAFE_INTEGER;
  const rightTime = right.startedAt ? Date.parse(right.startedAt) : Number.MAX_SAFE_INTEGER;
  if (leftTime !== rightTime) {
    return leftTime - rightTime;
  }
  return left.attempt - right.attempt;
}

/**
 * 查找任务。
 *
 * @param state mock 状态
 * @param taskId 任务 ID
 * @returns 演示任务或 undefined
 */
function findResearchTask(state: MockState, taskId: string): MockResearchTask | undefined {
  return state.researchTasks.find((item) => item.task.id === taskId);
}

/**
 * 应用审批决定。
 *
 * @param state mock 状态
 * @param approvalId 审批 ID
 * @param decision 批准或拒绝
 * @param comment 备注
 * @returns Response
 */
function decideResearchApproval(
  state: MockState,
  approvalId: string,
  decision: 'APPROVE' | 'REJECT',
  comment: string | null,
): Response {
  advanceResearchTasks(state);
  const mock = state.researchTasks.find((item) =>
    item.approvals.some((approval) => approval.id === approvalId),
  );
  const approval = mock?.approvals.find((item) => item.id === approvalId);
  if (!mock || !approval) {
    return problem(404, 'RICCON-TENANT-404', '审批不存在', '审批不存在或已被处理。');
  }
  if (approval.status !== 'PENDING') {
    const alreadyDecided = approval.status === (decision === 'APPROVE' ? 'APPROVED' : 'REJECTED');
    if (alreadyDecided) {
      return json(approval);
    }
    return problem(409, 'RICCON-APPROVAL-409', '审批已处理', '该审批已作出相反决定。');
  }

  const now = Date.now();
  const nowIso = new Date(now).toISOString();
  approval.status = decision === 'APPROVE' ? 'APPROVED' : 'REJECTED';
  approval.decidedBy = DEMO_USER_ID;
  approval.decidedAt = nowIso;
  approval.decisionComment = comment;

  if (decision === 'REJECT') {
    mock.stages.push({
      stage: 'APPROVAL',
      attempt: approval.attempt,
      status: 'FAILED',
      errorCode: 'RICCON-APPROVAL-REJECTED',
      startedAt: approval.createdAt,
      finishedAt: nowIso,
      details: null,
    });
    mock.stages.push({
      stage: 'TOOL',
      attempt: approval.attempt,
      status: 'SKIPPED',
      startedAt: null,
      finishedAt: null,
      details: null,
    });
    mock.stages.push({
      stage: 'GENERATION',
      attempt: 1,
      status: 'SKIPPED',
      startedAt: null,
      finishedAt: null,
      details: null,
    });
    mock.task.status = 'CANCELLED';
    mock.task.currentStage = null;
    mock.task.failureCode = 'RICCON-APPROVAL-REJECTED';
    mock.task.failureMessage = '审批被拒绝，任务已终止，未生成报告。';
    mock.task.finishedAt = nowIso;
    mock.phase = 'cancelled';
    persist(state);
    return json(approval);
  }

  mock.stages.push({
    stage: 'APPROVAL',
    attempt: approval.attempt,
    status: 'SUCCEEDED',
    startedAt: approval.createdAt,
    finishedAt: nowIso,
    details: { approvalId: approval.id },
  });
  mock.stages.push({
    stage: 'TOOL',
    attempt: approval.attempt,
    status: 'RUNNING',
    startedAt: nowIso,
    finishedAt: null,
    details: {
      toolName: approval.toolName,
      paramsPreview: approval.paramsPreview,
    },
  });
  const invocationId = nextId('invocation');
  const runningToolRecord = mock.stages
    .filter((stage) => stage.stage === 'TOOL' && stage.status === 'RUNNING')
    .sort((left, right) => right.attempt - left.attempt)[0];
  if (runningToolRecord?.details) {
    runningToolRecord.details.invocationId = invocationId;
  }
  mock.invocations.push({
    id: invocationId,
    toolName: approval.toolName,
    status: 'STARTED',
    paramsSha256: approval.paramsSha256,
    resultSha256: null,
    durationMs: null,
    errorCode: null,
    startedAt: nowIso,
    finishedAt: null,
  });
  mock.task.status = 'RUNNING';
  mock.task.currentStage = 'TOOL';
  mock.task.failureCode = null;
  mock.task.failureMessage = null;
  mock.task.finishedAt = null;
  mock.phase = 'tool';
  mock.phaseStartedAtMs = now;
  persist(state);
  return json(approval);
}

/**
 * 重试失败阶段。
 *
 * @param state mock 状态
 * @param taskId 任务 ID
 * @param fromStage 请求阶段
 * @returns Response
 */
function retryResearchTaskMock(
  state: MockState,
  taskId: string,
  fromStage: 'TOOL' | 'GENERATION' | null,
): Response {
  advanceResearchTasks(state);
  const mock = findResearchTask(state, taskId);
  if (!mock) {
    return problem(404, 'RICCON-TENANT-404', '任务不存在', '报告任务不存在或已被删除。');
  }
  if (mock.task.status !== 'FAILED') {
    return problem(409, 'RICCON-RESEARCH-409', '任务不可重试', '只有失败任务可以重试。');
  }
  const lastFailedStage = [...mock.stages]
    .reverse()
    .find((stage) => stage.status === 'FAILED')?.stage;
  const stage = fromStage ?? (lastFailedStage as 'TOOL' | 'GENERATION' | undefined) ?? null;
  if (stage !== 'TOOL' && stage !== 'GENERATION') {
    return problem(
      409,
      'RICCON-RESEARCH-409',
      '阶段不可重试',
      '检索阶段失败需调整主题后重新创建任务。',
    );
  }

  const now = Date.now();
  const nowIso = new Date(now).toISOString();
  mock.task.status = stage === 'TOOL' ? 'WAITING_APPROVAL' : 'RUNNING';
  mock.task.currentStage = stage === 'TOOL' ? 'APPROVAL' : 'GENERATION';
  mock.task.failureCode = null;
  mock.task.failureMessage = null;
  mock.task.finishedAt = null;
  mock.mode = 'success';

  if (stage === 'TOOL') {
    const attempt =
      Math.max(
        0,
        ...mock.stages.filter((item) => item.stage === 'TOOL').map((item) => item.attempt),
      ) + 1;
    mock.approvals.push(createResearchApproval(mock, attempt, now));
    mock.phase = 'waiting-approval';
    mock.phaseStartedAtMs = now;
  } else {
    const attempt =
      Math.max(
        0,
        ...mock.stages.filter((item) => item.stage === 'GENERATION').map((item) => item.attempt),
      ) + 1;
    mock.stages.push({
      stage: 'GENERATION',
      attempt,
      status: 'RUNNING',
      startedAt: nowIso,
      finishedAt: null,
      details: null,
    });
    mock.phase = 'generation';
    mock.phaseStartedAtMs = now;
  }
  persist(state);
  return json(researchTaskSummary(mock), 202);
}

/**
 * 创建演示报告任务。
 *
 * @param state mock 状态
 * @param body 请求体
 * @param idempotencyKey 幂等键
 * @returns Response
 */
function createResearchTaskMock(
  state: MockState,
  body: Record<string, unknown> | null,
  idempotencyKey: string,
): Response {
  if (idempotencyKey.length < 8 || idempotencyKey.length > 128) {
    return problem(
      400,
      'RICCON-RESEARCH-400',
      '缺少幂等键',
      '创建报告任务必须带有效的 Idempotency-Key。',
    );
  }
  const idempotencyKeyForUser = `${DEMO_USER_ID}:${idempotencyKey}`;
  const existingId = state.researchIdempotency[idempotencyKeyForUser];
  if (existingId) {
    const existing = findResearchTask(state, existingId);
    if (existing) {
      return json(researchTaskSummary(existing), 201);
    }
  }

  const topic = typeof body?.topic === 'string' ? body.topic.trim() : '';
  const mcpToolId = typeof body?.mcpToolId === 'string' ? body.mcpToolId : '';
  if (topic.length < 4 || topic.length > 500) {
    return problem(400, 'RICCON-RESEARCH-400', '主题不合法', '调研主题需为 4–500 个字符。');
  }
  const tool = state.mcpTools.find((item) => item.id === mcpToolId && item.status === 'ACTIVE');
  if (!tool) {
    return problem(403, 'RICCON-MCP-403', '工具不可用', '所选 MCP 工具未授权或已停用。');
  }

  const now = Date.now();
  const nowIso = new Date(now).toISOString();
  const taskId = nextId('research');
  const task: ResearchTask = {
    id: taskId,
    workspaceId: state.workspace.id,
    topic,
    status: 'RUNNING',
    currentStage: 'RETRIEVAL',
    mcpToolId,
    currentRunId: nextId('run'),
    failureCode: null,
    failureMessage: null,
    createdAt: nowIso,
    startedAt: nowIso,
    finishedAt: null,
  };
  const mock: MockResearchTask = {
    task,
    mode: researchModeForTopic(topic),
    phase: 'retrieval',
    phaseStartedAtMs: now,
    stages: [
      {
        stage: 'RETRIEVAL',
        attempt: 1,
        status: 'RUNNING',
        startedAt: nowIso,
        finishedAt: null,
        details: { query: topic.slice(0, 80) },
      },
    ],
    approvals: [],
    invocations: [],
    sources: [],
    artifact: null,
    artifactBody: null,
  };
  state.researchTasks.unshift(mock);
  state.researchIdempotency[idempotencyKeyForUser] = taskId;
  persist(state);
  return json(researchTaskSummary(mock), 201);
}

/**
 * 分页响应。
 *
 * @param items 列表
 * @returns Page
 */
function page<T>(items: T[]): {
  page: number;
  size: number;
  total: number;
  totalPages: number;
  items: T[];
} {
  return {
    page: 1,
    size: 100,
    total: items.length,
    totalPages: items.length > 0 ? 1 : 0,
    items,
  };
}

/**
 * 处理一次性请求；返回 null 表示路径未匹配。
 *
 * @param state mock 状态
 * @param path 路径
 * @param method HTTP 方法
 * @param init 请求初始化
 * @returns Response 或 null
 */
function routeRequest(
  state: MockState,
  path: string,
  method: string,
  init?: RequestInit,
): Response | null {
  if (path === '/api/v1/auth/csrf' && method === 'GET') {
    return json({ headerName: 'X-CSRF-TOKEN', token: state.csrfToken });
  }

  if (path === '/api/v1/auth/login' && method === 'POST') {
    const csrfError = requireCsrf(state, init);
    if (csrfError) return csrfError;
    const body = readJsonBody(init);
    const matches =
      body?.tenantCode === MOCK_CREDENTIALS.tenantCode &&
      body?.username === MOCK_CREDENTIALS.username &&
      body?.password === MOCK_CREDENTIALS.password;
    if (!matches) {
      return problem(401, 'RICCON-AUTH-001', '登录失败', '租户代码、账号或密码不正确。');
    }
    state.authenticated = true;
    state.csrfToken = `mock-csrf-${nextId('csrf')}`;
    persist(state);
    return json(sessionInfo(state));
  }

  if (path === '/api/v1/auth/session' && method === 'GET') {
    const authError = requireSession(state);
    return authError ?? json(sessionInfo(state));
  }

  if (path === '/api/v1/auth/logout' && method === 'POST') {
    const csrfError = requireCsrf(state, init);
    if (csrfError) return csrfError;
    state.authenticated = false;
    state.csrfToken = `mock-csrf-${nextId('csrf')}`;
    persist(state);
    return new Response(null, { status: 204 });
  }

  const authError = requireSession(state);
  if (authError) {
    return authError;
  }

  if (path === '/api/v1/workspaces' && method === 'GET') {
    return json({ page: 1, size: 100, total: 1, totalPages: 1, items: [state.workspace] });
  }

  const researchTasksMatch = match(/^\/api\/v1\/workspaces\/([^/]+)\/research-tasks$/, path);
  if (researchTasksMatch) {
    const workspaceId = decodeURIComponent(researchTasksMatch[1]);
    if (workspaceId !== state.workspace.id) {
      return problem(404, 'RICCON-TENANT-404', '工作空间不存在', '工作空间不存在或已被删除。');
    }
    if (method === 'GET') {
      advanceResearchTasks(state);
      const status = new URL(path, 'http://mock.local').searchParams.get('status');
      const items = state.researchTasks
        .filter((mockTask) => !status || mockTask.task.status === status)
        .map(researchTaskSummary)
        .sort((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt));
      return json(page(items));
    }
    if (method === 'POST') {
      const csrfError = requireCsrf(state, init);
      if (csrfError) return csrfError;
      const idempotencyKey = new Headers(init?.headers).get('Idempotency-Key') ?? '';
      return createResearchTaskMock(state, readJsonBody(init), idempotencyKey);
    }
  }

  const researchTaskMatch = match(/^\/api\/v1\/research-tasks\/([^/]+)$/, path);
  if (researchTaskMatch && method === 'GET') {
    advanceResearchTasks(state);
    const mockTask = findResearchTask(state, decodeURIComponent(researchTaskMatch[1]));
    return mockTask
      ? json(researchTaskDetail(mockTask))
      : problem(404, 'RICCON-TENANT-404', '任务不存在', '报告任务不存在或已被删除。');
  }

  const researchRetryMatch = match(/^\/api\/v1\/research-tasks\/([^/]+)\/retry$/, path);
  if (researchRetryMatch && method === 'POST') {
    const csrfError = requireCsrf(state, init);
    if (csrfError) return csrfError;
    const body = readJsonBody(init);
    const requestedStage = body?.fromStage;
    if (
      requestedStage !== undefined &&
      requestedStage !== null &&
      requestedStage !== 'TOOL' &&
      requestedStage !== 'GENERATION'
    ) {
      return problem(400, 'RICCON-RESEARCH-400', '阶段不合法', '仅允许重试 TOOL 或 GENERATION。');
    }
    return retryResearchTaskMock(
      state,
      decodeURIComponent(researchRetryMatch[1]),
      (requestedStage as 'TOOL' | 'GENERATION' | null | undefined) ?? null,
    );
  }

  if (path === '/api/v1/mcp-tools' && method === 'GET') {
    return json(page(state.mcpTools.filter((tool) => tool.status === 'ACTIVE')));
  }

  const approvalDecisionMatch = match(/^\/api\/v1\/approvals\/([^/]+)\/decision$/, path);
  if (approvalDecisionMatch && method === 'POST') {
    const csrfError = requireCsrf(state, init);
    if (csrfError) return csrfError;
    const body = readJsonBody(init);
    const decision = body?.decision;
    if (decision !== 'APPROVE' && decision !== 'REJECT') {
      return problem(
        400,
        'RICCON-APPROVAL-400',
        '决定不合法',
        '审批决定必须为 APPROVE 或 REJECT。',
      );
    }
    const rawComment = typeof body?.comment === 'string' ? body.comment.trim() : '';
    if (rawComment.length > 500) {
      return problem(400, 'RICCON-APPROVAL-400', '备注过长', '审批备注不能超过 500 个字符。');
    }
    return decideResearchApproval(
      state,
      decodeURIComponent(approvalDecisionMatch[1]),
      decision,
      rawComment || null,
    );
  }

  const artifactDownloadMatch = match(
    /^\/api\/v1\/artifacts\/([^/]+)\/versions\/(\d+)\/download-url$/,
    path,
  );
  if (artifactDownloadMatch && method === 'GET') {
    const artifactId = decodeURIComponent(artifactDownloadMatch[1]);
    const version = Number(artifactDownloadMatch[2]);
    const mockTask = state.researchTasks.find(
      (item) => item.artifact?.id === artifactId && item.artifact.currentVersion === version,
    );
    if (!mockTask?.artifact) {
      return problem(404, 'RICCON-TENANT-404', '产物不存在', '产物或版本不存在。');
    }
    return json({
      url: `/api/v1/mock/artifacts/${encodeURIComponent(artifactId)}/versions/${version}/content`,
      expiresAt: new Date(Date.now() + 5 * 60 * 1000).toISOString(),
    });
  }

  const artifactContentMatch = match(
    /^\/api\/v1\/mock\/artifacts\/([^/]+)\/versions\/(\d+)\/content$/,
    path,
  );
  if (artifactContentMatch && method === 'GET') {
    const artifactId = decodeURIComponent(artifactContentMatch[1]);
    const version = Number(artifactContentMatch[2]);
    const mockTask = state.researchTasks.find(
      (item) => item.artifact?.id === artifactId && item.artifact.currentVersion === version,
    );
    if (!mockTask?.artifactBody) {
      return problem(404, 'RICCON-TENANT-404', '产物正文不存在', '产物正文不存在或已被删除。');
    }
    return new Response(mockTask.artifactBody, {
      status: 200,
      headers: {
        'Content-Type': 'text/markdown; charset=utf-8',
        'Cache-Control': 'no-store',
      },
    });
  }

  const conversationsMatch = match(/^\/api\/v1\/workspaces\/([^/]+)\/conversations$/, path);
  if (conversationsMatch) {
    const workspaceId = decodeURIComponent(conversationsMatch[1]);
    if (workspaceId !== state.workspace.id) {
      return problem(404, 'RICCON-TENANT-404', '工作空间不存在', '工作空间不存在或已被删除。');
    }
    if (method === 'GET') {
      const items = [...state.conversations].sort(
        (left, right) => Date.parse(right.updatedAt) - Date.parse(left.updatedAt),
      );
      return json({
        page: 1,
        size: 100,
        total: items.length,
        totalPages: items.length > 0 ? 1 : 0,
        items,
      });
    }
    if (method === 'POST') {
      const csrfError = requireCsrf(state, init);
      if (csrfError) return csrfError;
      const body = readJsonBody(init);
      const now = new Date().toISOString();
      const conversation: Conversation = {
        id: nextId('conversation'),
        workspaceId,
        title: typeof body?.title === 'string' && body.title.trim() ? body.title.trim() : null,
        status: 'ACTIVE',
        lastMessageAt: null,
        createdAt: now,
        updatedAt: now,
      };
      state.conversations = [conversation, ...state.conversations];
      state.messages[conversation.id] = [];
      persist(state);
      return json(conversation, 201);
    }
  }

  const conversationMatch = match(/^\/api\/v1\/conversations\/([^/]+)$/, path);
  if (conversationMatch && method === 'GET') {
    const conversationId = decodeURIComponent(conversationMatch[1]);
    const conversation = state.conversations.find((item) => item.id === conversationId);
    if (!conversation) {
      return problem(404, 'RICCON-TENANT-404', '会话不存在', '会话不存在或已被删除。');
    }
    return json({
      ...conversation,
      messages: state.messages[conversationId] ?? [],
    });
  }

  const messagesMatch = match(/^\/api\/v1\/conversations\/([^/]+)\/messages$/, path);
  if (messagesMatch && method === 'POST') {
    const csrfError = requireCsrf(state, init);
    if (csrfError) return csrfError;
    return handleSendMessage(state, decodeURIComponent(messagesMatch[1]), init);
  }

  const stopMatch = match(/^\/api\/v1\/conversations\/([^/]+)\/stop$/, path);
  if (stopMatch && method === 'POST') {
    const csrfError = requireCsrf(state, init);
    if (csrfError) return csrfError;
    const body = readJsonBody(init);
    const runId = typeof body?.runId === 'string' ? body.runId : '';
    const run = activeRuns.get(runId);
    if (!run) {
      return problem(409, 'RICCON-CHAT-409', '没有可停止的回答', '当前回答已结束或不存在。');
    }
    run.cancelled = true;
    if (typeof run.timer === 'number') {
      globalThis.clearInterval(run.timer);
      run.timer = null;
    }
    const stopped = createEvent(run.runId, run.messageId, 'riccon.chat.stopped.v1', {
      reason: 'USER_CANCELLED',
    });
    finalizeRun(state, run, 'STOPPED');
    activeRuns.delete(runId);
    // 直接结束 SSE；客户端通常已经通过 AbortController 断开。
    run.closed = true;
    return json({ stopped: true, event: stopped }, 202);
  }

  const filesMatch = match(/^\/api\/v1\/workspaces\/([^/]+)\/files$/, path);
  if (filesMatch && method === 'POST') {
    const csrfError = requireCsrf(state, init);
    if (csrfError) return csrfError;
    const workspaceId = decodeURIComponent(filesMatch[1]);
    if (workspaceId !== state.workspace.id) {
      return problem(404, 'RICCON-TENANT-404', '工作空间不存在', '工作空间不存在或已被删除。');
    }
    if (!(init?.body instanceof FormData)) {
      return problem(400, 'RICCON-FILE-400', '上传请求无效', '缺少 multipart 文件体。');
    }
    const file = init.body.get('file');
    const conversationId = String(init.body.get('conversationId') ?? '');
    if (!(file instanceof File)) {
      return problem(400, 'RICCON-FILE-400', '上传请求无效', '缺少文件。');
    }
    const conversation = state.conversations.find((item) => item.id === conversationId);
    if (!conversation) {
      return problem(404, 'RICCON-TENANT-404', '会话不存在', '会话不存在或已被删除。');
    }

    advanceParses(state);
    const existing = state.files.filter(
      (item) => item.conversationId === conversationId && item.status !== 'DELETED',
    );
    if (existing.length >= MAX_FILES_PER_CONVERSATION) {
      return problem(409, 'RICCON-FILE-409', '文件数量已达上限', '每个会话最多上传 5 个文件。');
    }
    if (file.size > MAX_FILE_BYTES) {
      return problem(413, 'RICCON-FILE-413', '文件过大', '单文件不能超过 200 MB。');
    }
    const validation = validateFile({ name: file.name, size: file.size, type: file.type });
    if (!validation.ok) {
      return problem(415, 'RICCON-FILE-415', '不支持的文件', validation.reason);
    }

    const now = new Date().toISOString();
    const fileId = nextId('file');
    const workspaceFile: WorkspaceFile = {
      id: fileId,
      workspaceId,
      conversationId,
      originalName: file.name,
      format: validation.format,
      mimeType: file.type || 'application/octet-stream',
      sizeBytes: file.size,
      sha256: pseudoSha256(`${file.name}:${file.size}`),
      status: 'AVAILABLE',
      parse: {
        id: nextId('parse'),
        attempt: 1,
        status: 'PENDING',
        parserVersion: 'mock-parser-1',
        pageCount: null,
        sheetCount: null,
        chunkCount: null,
        errorCode: null,
        errorMessage: null,
        startedAt: now,
        finishedAt: null,
      },
      createdAt: now,
      deletedAt: null,
    };
    state.files.push(workspaceFile);
    state.parseOutcomes[fileId] = { fail: /fail|损坏|失败/i.test(file.name) };
    conversation.updatedAt = now;
    persist(state);
    return json(workspaceFile, 201);
  }

  if (filesMatch && method === 'GET') {
    const workspaceId = decodeURIComponent(filesMatch[1]);
    if (workspaceId !== state.workspace.id) {
      return problem(404, 'RICCON-TENANT-404', '工作空间不存在', '工作空间不存在或已被删除。');
    }
    advanceParses(state);
    const params = new URL(path, 'http://mock.local').searchParams;
    const conversationId = params.get('conversationId');
    const items = state.files
      .filter((file) => file.status !== 'DELETED')
      .filter((file) => !conversationId || file.conversationId === conversationId)
      .sort((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt));
    return json({
      page: 1,
      size: 100,
      total: items.length,
      totalPages: items.length > 0 ? 1 : 0,
      items,
    });
  }

  const fileMatch = match(/^\/api\/v1\/files\/([^/]+)$/, path);
  if (fileMatch && method === 'GET') {
    advanceParses(state);
    const file = state.files.find((item) => item.id === decodeURIComponent(fileMatch[1]));
    return file
      ? json(file)
      : problem(404, 'RICCON-TENANT-404', '文件不存在', '文件不存在或已被删除。');
  }

  const retryParseMatch = match(/^\/api\/v1\/files\/([^/]+)\/parse-retries$/, path);
  if (retryParseMatch && method === 'POST') {
    const csrfError = requireCsrf(state, init);
    if (csrfError) return csrfError;
    const fileId = decodeURIComponent(retryParseMatch[1]);
    const file = state.files.find((item) => item.id === fileId);
    if (!file) {
      return problem(404, 'RICCON-TENANT-404', '文件不存在', '文件不存在或已被删除。');
    }
    const now = new Date().toISOString();
    file.parse = {
      id: nextId('parse'),
      attempt: (file.parse?.attempt ?? 0) + 1,
      status: 'PENDING',
      parserVersion: 'mock-parser-1',
      pageCount: null,
      sheetCount: null,
      chunkCount: null,
      errorCode: null,
      errorMessage: null,
      startedAt: now,
      finishedAt: null,
    };
    // 演示重试会成功，便于验证失败→恢复路径。
    state.parseOutcomes[fileId] = { fail: false };
    persist(state);
    return json(file, 202);
  }

  const downloadMatch = match(/^\/api\/v1\/files\/([^/]+)\/download-url$/, path);
  if (downloadMatch && method === 'GET') {
    const fileId = decodeURIComponent(downloadMatch[1]);
    const file = state.files.find((item) => item.id === fileId);
    if (!file) {
      return problem(404, 'RICCON-TENANT-404', '文件不存在', '文件不存在或已被删除。');
    }
    return json({
      url: `https://files.example.invalid/${fileId}?token=mock`,
      expiresAt: new Date(Date.now() + 5 * 60 * 1000).toISOString(),
    });
  }

  return null;
}

/**
 * 创建 mock fetch。
 *
 * 返回函数与 `globalThis.fetch` 同签名；只处理 J1 需要的路径，其他路径返回 404。
 *
 * @returns fetch 替身
 */
export function createMockFetch(): typeof fetch {
  const state = loadState();

  return async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = new URL(
      typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url,
      'http://mock.local',
    );
    const method = (init?.method ?? 'GET').toUpperCase();
    const response = routeRequest(state, url.pathname, method, init);
    if (response) {
      return response;
    }
    return problem(404, 'RICCON-NOT-FOUND', '接口不存在', '演示适配层未实现该接口。');
  };
}

/**
 * 清空演示数据；供测试与演示重置使用。
 */
export function resetMockState(): void {
  try {
    globalThis.sessionStorage?.removeItem(STORAGE_KEY);
  } catch {
    // 存储不可用时无需处理。
  }
  activeRuns.clear();
}
