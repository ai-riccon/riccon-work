import type {
  ChatEvent,
  Citation,
  Conversation,
  Message,
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

/** 演示工作空间与用户。 */
const DEMO_WORKSPACE_ID = '11111111-1111-4111-8111-111111111111';
const DEMO_USER_ID = '22222222-2222-4222-8222-222222222222';
const DEMO_TENANT_ID = 'tenant-acme';

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
