import { useCallback, useEffect, useRef, useState } from 'react';
import type { Dispatch, SetStateAction } from 'react';
import type { RicconApi } from '@/services/api/client';
import { ApiError, describeApiError, isAbortError } from '@/services/api/errors';
import type {
  ChatEvent,
  ChatMessage,
  Conversation,
  SessionInfo,
  Workspace,
  WorkspaceFile,
} from '@/services/api/types';
import {
  findQuestionForRetry,
  mergeCitations,
  toChatMessages,
} from '@/services/chat/message-utils';
import { createClientMessageId } from '@/services/chat/message-utils';
import { formatBytes, isParsePending, validateFiles } from '@/services/files/file-validation';

/** 工作空间加载阶段。 */
export type WorkspacePhase = 'loading' | 'ready' | 'empty' | 'forbidden' | 'error';

/** 会话详情加载错误。 */
export interface ConversationLoadError {
  kind: 'forbidden' | 'notFound' | 'error';
  message: string;
}

/** J1 工作空间控制器。 */
export interface J1WorkspaceController {
  phase: WorkspacePhase;
  error: string | null;
  workspace: Workspace | null;
  conversations: Conversation[];
  conversationsLoading: boolean;
  creatingConversation: boolean;
  activeConversationId: string | null;
  messages: ChatMessage[];
  messagesLoading: boolean;
  messagesError: ConversationLoadError | null;
  files: WorkspaceFile[];
  filesLoading: boolean;
  uploadErrors: string[];
  uploading: boolean;
  streaming: boolean;
  actionError: string | null;
  initialize: () => Promise<void>;
  createConversation: () => Promise<void>;
  selectConversation: (conversationId: string) => Promise<void>;
  reloadActive: () => Promise<void>;
  uploadFiles: (files: File[]) => Promise<void>;
  retryParse: (fileId: string) => Promise<void>;
  send: (content: string) => Promise<void>;
  stop: () => Promise<void>;
  retryMessage: (assistantMessageId: string) => Promise<void>;
  dismissUploadErrors: () => void;
  dismissActionError: () => void;
}

/** 默认解析状态轮询间隔；E2E 可在 1–2 秒内看到状态推进。 */
const DEFAULT_POLL_INTERVAL_MS = 1200;

/**
 * 封装 J1 工作空间的数据流：工作空间、会话、文件、SSE 与停止生成。
 *
 * 设计取舍：
 * - 不自建全局状态库，所有状态属于当前页面；会话切换时重新从服务端读取，保证多标签页一致；
 * - 流式回答用 AsyncGenerator 顺序消费，停止时先调用 stop 接口再 abort，已生成内容立即保留；
 * - 文件解析状态由页面轮询推进，轮询仅在存在 PENDING/RUNNING 文件时存在，避免空转。
 *
 * @param api API 客户端
 * @param session 当前会话；null 时不做加载
 * @param pollIntervalMs 文件解析轮询间隔
 * @returns 状态与动作
 */
export function useJ1Workspace(
  api: RicconApi,
  session: SessionInfo | null,
  pollIntervalMs = DEFAULT_POLL_INTERVAL_MS,
): J1WorkspaceController {
  const [phase, setPhase] = useState<WorkspacePhase>('loading');
  const [error, setError] = useState<string | null>(null);
  const [workspace, setWorkspace] = useState<Workspace | null>(null);
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [conversationsLoading, setConversationsLoading] = useState(false);
  const [creatingConversation, setCreatingConversation] = useState(false);
  const [activeConversationId, setActiveConversationId] = useState<string | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [messagesLoading, setMessagesLoading] = useState(false);
  const [messagesError, setMessagesError] = useState<ConversationLoadError | null>(null);
  const [files, setFiles] = useState<WorkspaceFile[]>([]);
  const [filesLoading, setFilesLoading] = useState(false);
  const [uploadErrors, setUploadErrors] = useState<string[]>([]);
  const [uploading, setUploading] = useState(false);
  const [streaming, setStreaming] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const workspaceRef = useRef<Workspace | null>(null);
  const filesRef = useRef<WorkspaceFile[]>([]);
  const messagesRef = useRef<ChatMessage[]>([]);
  const activeIdRef = useRef<string | null>(null);
  const streamingRef = useRef(false);
  const abortRef = useRef<AbortController | null>(null);
  const runIdRef = useRef<string | null>(null);
  const streamingMessageIdRef = useRef<string | null>(null);
  const stopRequestedRef = useRef(false);
  const pollInFlightRef = useRef(false);
  const loadTokenRef = useRef(0);
  const initializedForRef = useRef<string | null>(null);

  /**
   * 同步提交文件列表。
   *
   * 关键点：`filesRef` 与 React state 必须在同一个同步步骤内更新，不能依赖渲染后的
   * `useEffect` 回填。否则“解析刚刚成功/上传刚刚完成”时立即发送，`send` 读到的仍是
   * 上一帧的文件快照，会漏带 `fileIds`，甚至带上上一个会话的文件。
   *
   * @param update 基于当前快照计算下一份列表
   */
  const commitFiles = useCallback((update: (previous: WorkspaceFile[]) => WorkspaceFile[]) => {
    const next = update(filesRef.current);
    filesRef.current = next;
    setFiles(next);
  }, []);

  useEffect(() => {
    workspaceRef.current = workspace;
  }, [workspace]);

  useEffect(() => {
    messagesRef.current = messages;
  }, [messages]);

  useEffect(() => {
    activeIdRef.current = activeConversationId;
  }, [activeConversationId]);

  /**
   * 加载工作空间与会话列表。
   *
   * 选择第一个 ACTIVE 工作空间；没有工作空间时进入空态，而不是报错。
   */
  const initialize = useCallback(async (): Promise<void> => {
    setPhase('loading');
    setError(null);
    activeIdRef.current = null;
    setActiveConversationId(null);
    setMessages([]);
    commitFiles(() => []);
    setMessagesError(null);
    try {
      const page = await api.listWorkspaces();
      const nextWorkspace = page.items.find((item) => item.status === 'ACTIVE') ?? null;
      workspaceRef.current = nextWorkspace;
      setWorkspace(nextWorkspace);
      if (!nextWorkspace) {
        setPhase('empty');
        setConversations([]);
        return;
      }

      setConversationsLoading(true);
      const conversationPage = await api.listConversations(nextWorkspace.id);
      setConversations(conversationPage.items);
      setPhase('ready');
    } catch (loadError) {
      if (loadError instanceof ApiError && loadError.status === 403) {
        setPhase('forbidden');
        setError(describeApiError(loadError, '没有访问工作空间的权限。'));
      } else if (loadError instanceof ApiError && loadError.status === 401) {
        // 401 已由 API 客户端标记为会话过期，页面会跳转登录；这里保留可读错误。
        setPhase('error');
        setError('登录状态已失效，请重新登录。');
      } else {
        setPhase('error');
        setError(describeApiError(loadError, '工作空间加载失败，请重试。'));
      }
    } finally {
      setConversationsLoading(false);
    }
  }, [api, commitFiles]);

  // 登录态确定后只初始化一次；退出登录由页面卸载处理。
  useEffect(() => {
    if (!session) {
      return;
    }
    const key = `${session.tenant.id}:${session.user.id}`;
    if (initializedForRef.current === key) {
      return;
    }
    initializedForRef.current = key;
    void initialize();
  }, [initialize, session]);

  /**
   * 停止当前流；内部与用户点击共用。
   *
   * @param silent 是否隐藏停止请求失败提示（切换会话时使用）
   */
  const stopStreaming = useCallback(
    async (silent: boolean): Promise<void> => {
      if (!streamingRef.current) {
        return;
      }
      const conversationId = activeIdRef.current;
      const runId = runIdRef.current;
      const messageId = streamingMessageIdRef.current;

      stopRequestedRef.current = true;
      streamingRef.current = false;
      setStreaming(false);
      if (messageId) {
        setMessages((previous) =>
          previous.map((message) =>
            message.id === messageId && message.status === 'STREAMING'
              ? { ...message, status: 'STOPPED' }
              : message,
          ),
        );
      }

      const stopRequest =
        conversationId && runId
          ? api.stopGeneration(conversationId, runId).catch((stopError: unknown) => {
              // 409 表示回答已结束；停止是幂等操作，不向用户报错。
              const alreadyFinished = stopError instanceof ApiError && stopError.status === 409;
              if (!silent && !alreadyFinished) {
                setActionError(
                  `停止请求未送达：${describeApiError(stopError, '回答可能仍在服务端继续生成。')}`,
                );
              }
            })
          : Promise.resolve();

      // 先断开本地流，避免等待 stop 接口期间继续追加文本；服务端会收到独立的 stop 请求。
      abortRef.current?.abort();
      abortRef.current = null;
      runIdRef.current = null;
      streamingMessageIdRef.current = null;
      await stopRequest;
      stopRequestedRef.current = false;
    },
    [api],
  );

  /** 用户点击停止。 */
  const stop = useCallback(async (): Promise<void> => {
    await stopStreaming(false);
  }, [stopStreaming]);

  /**
   * 切换会话并加载消息与文件。
   *
   * 使用自增 token 丢弃过期响应，避免快速切换时旧请求覆盖新会话。
   *
   * @param conversationId 目标会话 ID
   */
  const selectConversation = useCallback(
    async (conversationId: string): Promise<void> => {
      if (conversationId === activeIdRef.current) {
        return;
      }
      await stopStreaming(true);
      const currentWorkspace = workspaceRef.current;
      if (!currentWorkspace) {
        return;
      }

      activeIdRef.current = conversationId;
      setActiveConversationId(conversationId);
      setMessages([]);
      commitFiles(() => []);
      setMessagesLoading(true);
      setFilesLoading(true);
      setMessagesError(null);
      setActionError(null);
      setUploadErrors([]);
      const token = loadTokenRef.current + 1;
      loadTokenRef.current = token;

      try {
        const [detail, filePage] = await Promise.all([
          api.getConversation(conversationId),
          api.listFiles(currentWorkspace.id, conversationId),
        ]);
        if (token !== loadTokenRef.current) {
          return;
        }
        setMessages(toChatMessages(detail));
        commitFiles(() => filePage.items);
      } catch (loadError) {
        if (token !== loadTokenRef.current) {
          return;
        }
        if (loadError instanceof ApiError && loadError.status === 403) {
          setMessagesError({ kind: 'forbidden', message: '没有访问该会话的权限。' });
        } else if (loadError instanceof ApiError && loadError.status === 404) {
          setMessagesError({ kind: 'notFound', message: '会话不存在或已被删除。' });
        } else {
          setMessagesError({
            kind: 'error',
            message: describeApiError(loadError, '会话加载失败，请重试。'),
          });
        }
      } finally {
        if (token === loadTokenRef.current) {
          setMessagesLoading(false);
          setFilesLoading(false);
        }
      }
    },
    [api, commitFiles, stopStreaming],
  );

  /**
   * 重新读取当前会话；用于加载失败后的重试。
   */
  const reloadActive = useCallback(async (): Promise<void> => {
    const conversationId = activeIdRef.current;
    if (!conversationId) {
      return;
    }
    // 清空当前 ID，让 selectConversation 不因“相同会话”提前返回。
    activeIdRef.current = null;
    await selectConversation(conversationId);
  }, [selectConversation]);

  /** 新建会话并立即选中。 */
  const createConversation = useCallback(async (): Promise<void> => {
    const currentWorkspace = workspaceRef.current;
    if (!currentWorkspace || creatingConversation) {
      return;
    }
    setCreatingConversation(true);
    setActionError(null);
    await stopStreaming(true);
    loadTokenRef.current += 1;
    try {
      const conversation = await api.createConversation(currentWorkspace.id);
      setConversations((previous) => [conversation, ...previous]);
      activeIdRef.current = conversation.id;
      setActiveConversationId(conversation.id);
      setMessages([]);
      setMessagesError(null);
      commitFiles(() => []);
      setUploadErrors([]);
      setMessagesLoading(false);
      setFilesLoading(false);
    } catch (createError) {
      setActionError(describeApiError(createError, '新建会话失败，请重试。'));
    } finally {
      setCreatingConversation(false);
    }
  }, [api, commitFiles, creatingConversation, stopStreaming]);

  /**
   * 上传文件；客户端先做格式、大小与会话数量上限校验。
   *
   * @param incoming 用户选择的文件
   */
  const uploadFiles = useCallback(
    async (incoming: File[]): Promise<void> => {
      const currentWorkspace = workspaceRef.current;
      const conversationId = activeIdRef.current;
      if (!currentWorkspace || !conversationId) {
        setUploadErrors(['请先新建或选择一个会话，再上传文件。']);
        return;
      }
      if (incoming.length === 0) {
        return;
      }

      const existingCount = filesRef.current.filter((file) => file.status !== 'DELETED').length;
      const { accepted, errors } = validateFiles(incoming, existingCount);
      setUploadErrors(errors);
      if (accepted.length === 0) {
        return;
      }

      setUploading(true);
      const uploaded: WorkspaceFile[] = [];
      for (const file of accepted) {
        try {
          const created = await api.uploadFile(currentWorkspace.id, conversationId, file);
          uploaded.push(created);
        } catch (uploadError) {
          errors.push(`${file.name}：${describeApiError(uploadError, '上传失败，请重试。')}`);
        }
      }

      if (activeIdRef.current === conversationId) {
        commitFiles((previous) => [...uploaded, ...previous]);
        setUploadErrors(errors);
      }
      setUploading(false);
    },
    [api, commitFiles],
  );

  /**
   * 重试失败解析；服务端返回新的解析尝试后继续轮询。
   *
   * @param fileId 文件 ID
   */
  const retryParse = useCallback(
    async (fileId: string): Promise<void> => {
      try {
        const updated = await api.retryFileParse(fileId);
        commitFiles((previous) => previous.map((file) => (file.id === fileId ? updated : file)));
      } catch (retryError) {
        setActionError(describeApiError(retryError, '重试解析失败，请稍后再试。'));
      }
    },
    [api, commitFiles],
  );

  /**
   * 发送问题并消费 SSE。
   *
   * 停止由 stopRequestedRef 控制：一旦用户点击停止，即使后续事件仍到达也不会追加文本。
   *
   * @param raw 输入框原文
   */
  const send = useCallback(
    async (raw: string): Promise<void> => {
      const content = raw.trim();
      const conversationId = activeIdRef.current;
      if (!content || !conversationId || streamingRef.current) {
        return;
      }

      // 提交瞬间读取同步维护的 ref，而不是渲染后的 state/effect，保证快速操作也使用同一份文件快照。
      const fileIds = selectUsableFileIds(filesRef.current);

      const clientMessageId = createClientMessageId();
      const userMessage: ChatMessage = {
        id: `local-user-${clientMessageId}`,
        role: 'user',
        content,
        status: 'COMPLETED',
        citations: [],
      };
      const assistantMessage: ChatMessage = {
        id: `local-assistant-${clientMessageId}`,
        role: 'assistant',
        content: '',
        status: 'STREAMING',
        citations: [],
        question: content,
      };

      streamingMessageIdRef.current = assistantMessage.id;
      runIdRef.current = null;
      stopRequestedRef.current = false;
      streamingRef.current = true;
      setStreaming(true);
      setMessages((previous) => [...previous, userMessage, assistantMessage]);
      setConversations((previous) =>
        previous.map((conversation) =>
          conversation.id === conversationId && !conversation.title
            ? {
                ...conversation,
                title: content.length > 20 ? `${content.slice(0, 20)}…` : content,
              }
            : conversation,
        ),
      );

      const controller = new AbortController();
      abortRef.current = controller;

      try {
        for await (const event of api.sendMessage(
          { conversationId, content, clientMessageId, fileIds },
          controller.signal,
        )) {
          if (stopRequestedRef.current) {
            break;
          }
          applyChatEvent(event, assistantMessage.id, runIdRef, setMessages);
        }

        if (!stopRequestedRef.current) {
          // 契约要求以 completed/failed/stopped 结束；缺失终态说明连接被意外截断。
          setMessages((previous) =>
            previous.map((message) =>
              message.id === assistantMessage.id && message.status === 'STREAMING'
                ? {
                    ...message,
                    status: 'FAILED',
                    error: {
                      code: 'RICCON-CLIENT-002',
                      message: '回答流意外结束，请重试。',
                      retryable: true,
                    },
                  }
                : message,
            ),
          );
        }
      } catch (streamError) {
        if (isAbortError(streamError)) {
          setMessages((previous) =>
            previous.map((message) =>
              message.id === assistantMessage.id && message.status === 'STREAMING'
                ? { ...message, status: 'STOPPED' }
                : message,
            ),
          );
        } else {
          const message = describeApiError(streamError, '回答生成失败，请重试。');
          setMessages((previous) =>
            previous.map((item) =>
              item.id === assistantMessage.id
                ? {
                    ...item,
                    status: 'FAILED',
                    error: {
                      code:
                        streamError instanceof ApiError ? streamError.code : 'RICCON-CLIENT-000',
                      message,
                      retryable: streamError instanceof ApiError ? streamError.retryable : true,
                    },
                  }
                : item,
            ),
          );
        }
      } finally {
        streamingRef.current = false;
        setStreaming(false);
        abortRef.current = null;
        runIdRef.current = null;
        streamingMessageIdRef.current = null;
        stopRequestedRef.current = false;
      }
    },
    [api],
  );

  /**
   * 重试失败的助手回答：重新发送原始问题，保留旧失败记录。
   *
   * @param assistantMessageId 失败消息 ID
   */
  const retryMessage = useCallback(
    async (assistantMessageId: string): Promise<void> => {
      const question = findQuestionForRetry(messagesRef.current, assistantMessageId);
      if (!question) {
        setActionError('找不到原始问题，无法重试。');
        return;
      }
      await send(question);
    },
    [send],
  );

  // 仅当存在 PENDING/RUNNING 文件时轮询；响应回来后会触发下一次 effect，终态时自动停止。
  const pendingKey = files
    .filter((file) => isParsePending(file.parse?.status))
    .map((file) => `${file.id}:${file.parse?.status ?? 'PENDING'}`)
    .join('|');

  useEffect(() => {
    if (!pendingKey) {
      return;
    }
    const timer = window.setInterval(() => {
      if (pollInFlightRef.current) {
        return;
      }
      const pending = filesRef.current.filter((file) => isParsePending(file.parse?.status));
      if (pending.length === 0) {
        return;
      }
      pollInFlightRef.current = true;
      void Promise.all(
        pending.map((file) =>
          api
            .getFile(file.id)
            .then((updated) => ({ id: file.id, updated }))
            .catch(() => null),
        ),
      )
        .then((results) => {
          const updates = new Map(
            results
              .filter((result): result is { id: string; updated: WorkspaceFile } => result !== null)
              .map((result) => [result.id, result.updated]),
          );
          if (updates.size > 0) {
            // 解析状态一落地就同步写入 ref，用户无需等待下一次渲染即可发送并携带该文件。
            commitFiles((previous) => previous.map((file) => updates.get(file.id) ?? file));
          }
        })
        .finally(() => {
          pollInFlightRef.current = false;
        });
    }, pollIntervalMs);

    return () => {
      window.clearInterval(timer);
    };
  }, [api, commitFiles, pendingKey, pollIntervalMs]);

  return {
    phase,
    error,
    workspace,
    conversations,
    conversationsLoading,
    creatingConversation,
    activeConversationId,
    messages,
    messagesLoading,
    messagesError,
    files,
    filesLoading,
    uploadErrors,
    uploading,
    streaming,
    actionError,
    initialize,
    createConversation,
    selectConversation,
    reloadActive,
    uploadFiles,
    retryParse,
    send,
    stop,
    retryMessage,
    dismissUploadErrors: () => setUploadErrors([]),
    dismissActionError: () => setActionError(null),
  };
}

/**
 * 从文件快照中选取可随消息提交的文件 ID。
 *
 * 只包含未删除且解析成功的文件，按界面顺序最多 5 个，与契约 `SendMessageRequest.fileIds`
 * 的上限一致；快照本身由调用方在提交瞬间提供，函数保持纯只读。
 *
 * @param files 当前文件快照
 * @returns 可提交的文件 ID
 */
export function selectUsableFileIds(files: WorkspaceFile[]): string[] {
  return files
    .filter((file) => file.status === 'AVAILABLE' && file.parse?.status === 'SUCCEEDED')
    .map((file) => file.id)
    .slice(0, 5);
}

/**
 * 把单个 SSE 事件应用到助手消息。
 *
 * 事件类型由契约固定；未知事件在 ApiClient 已经过滤，这里仅处理六种已知事件。
 *
 * @param event SSE 事件
 * @param assistantId 助手消息本地 ID
 * @param runIdRef 运行 ID 引用，供停止接口使用
 * @param setMessages React 状态更新函数
 */
function applyChatEvent(
  event: ChatEvent,
  assistantId: string,
  runIdRef: { current: string | null },
  setMessages: Dispatch<SetStateAction<ChatMessage[]>>,
): void {
  if (event.eventType === 'riccon.chat.started.v1') {
    runIdRef.current = event.runId;
  }
  setMessages((previous) =>
    previous.map((message) => {
      if (message.id !== assistantId) {
        return message;
      }
      switch (event.eventType) {
        case 'riccon.chat.started.v1':
          return { ...message, runId: event.runId, modelKey: event.data.modelKey };
        case 'riccon.chat.delta.v1':
          return { ...message, content: message.content + event.data.delta };
        case 'riccon.chat.citation.v1':
          return {
            ...message,
            citations: mergeCitations(message.citations, event.data.citations),
          };
        case 'riccon.chat.completed.v1':
          return { ...message, status: 'COMPLETED' };
        case 'riccon.chat.failed.v1':
          return {
            ...message,
            status: 'FAILED',
            error: {
              code: event.data.code,
              message: event.data.message,
              retryable: event.data.retryable,
            },
          };
        case 'riccon.chat.stopped.v1':
          return { ...message, status: 'STOPPED' };
        default:
          return message;
      }
    }),
  );
}

/**
 * 生成文件体积文案的轻量包装，供页面直接使用。
 *
 * @param bytes 字节数
 * @returns 易读体积
 */
export function describeFileSize(bytes: number): string {
  return formatBytes(bytes);
}
