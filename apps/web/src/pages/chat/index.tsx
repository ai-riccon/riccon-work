import {
  FileExcelOutlined,
  FileImageOutlined,
  FilePdfOutlined,
  FileWordOutlined,
  LoadingOutlined,
  LogoutOutlined,
  MessageOutlined,
  PlusOutlined,
  RobotOutlined,
  UploadOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { Bubble, Conversations, Sender, Welcome } from '@ant-design/x';
import type { BubbleItemType } from '@ant-design/x';
import { useNavigate } from '@umijs/max';
import {
  Alert,
  Avatar,
  Button,
  Drawer,
  Empty,
  Flex,
  Result,
  Skeleton,
  Space,
  Spin,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ChangeEvent, ReactNode } from 'react';
import { api as defaultApi } from '@/services/api';
import { IS_MOCK_API } from '@/services/api/config';
import type { RicconApi } from '@/services/api/client';
import { describeApiError } from '@/services/api/errors';
import type { ChatMessage, Citation, WorkspaceFile } from '@/services/api/types';
import { authStore, useAuth } from '@/services/auth/auth-store';
import {
  citationSourceLabel,
  conversationLabel,
  formatCitationLocator,
} from '@/services/chat/message-utils';
import {
  FILE_ACCEPT,
  MAX_FILES_PER_CONVERSATION,
  formatBytes,
} from '@/services/files/file-validation';
import { useJ1Workspace } from './use-j1-workspace';
import './chat.css';

/** 工作空间页可注入依赖，便于组件测试不发起真实网络请求。 */
export interface ChatPageProps {
  api?: RicconApi;
  navigate?: (to: string, options?: { replace?: boolean }) => void;
  /** 解析状态轮询间隔；测试可缩短。 */
  pollIntervalMs?: number;
}

/** Bubble 状态基础色。 */
function bubbleStatus(
  message: ChatMessage,
): 'local' | 'loading' | 'updating' | 'success' | 'error' | 'abort' {
  switch (message.status) {
    case 'STREAMING':
      return message.content ? 'updating' : 'loading';
    case 'FAILED':
      return 'error';
    case 'STOPPED':
      return 'abort';
    case 'COMPLETED':
    default:
      return 'success';
  }
}

/**
 * 员工端 J1 工作空间页。
 *
 * 责任边界：只调用 apps/api，不直连 Worker、模型或 MCP；登录态由 authStore 统一，
 * SSE 与文件解析状态由 useJ1Workspace 管理。
 *
 * @param props API、导航与轮询间隔注入
 * @returns 工作空间界面
 */
export default function ChatPage({
  api = defaultApi,
  navigate: injectedNavigate,
  pollIntervalMs,
}: ChatPageProps) {
  const umiNavigate = useNavigate();
  const navigate = injectedNavigate ?? umiNavigate;
  const auth = useAuth();
  const controller = useJ1Workspace(api, auth.session, pollIntervalMs);
  const [input, setInput] = useState('');
  const [activeCitation, setActiveCitation] = useState<Citation | null>(null);
  const [citationError, setCitationError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const [loggingOut, setLoggingOut] = useState(false);
  const [logoutError, setLogoutError] = useState<string | null>(null);

  // 首次进入 /chat 时恢复会话；401 由 authStore 转为匿名，再由下面的 effect 跳转登录。
  useEffect(() => {
    if (auth.status === 'unknown') {
      void authStore.restore(api);
    }
  }, [api, auth.status]);

  useEffect(() => {
    if (auth.status === 'anonymous' || auth.status === 'expired') {
      navigate('/login', { replace: true });
    }
  }, [auth.status, navigate]);

  /**
   * 退出登录。
   *
   * 只有服务端确认登出后才清除本地状态；失败时保留会话并显示原因。
   */
  const handleLogout = async (): Promise<void> => {
    setLoggingOut(true);
    setLogoutError(null);
    try {
      await api.logout();
      authStore.signOut('logout');
      navigate('/login', { replace: true });
    } catch (error) {
      setLogoutError(describeApiError(error, '退出登录失败，请重试。'));
    } finally {
      setLoggingOut(false);
    }
  };

  /**
   * 提交问题。
   *
   * @param value Sender 当前文本
   */
  const handleSubmit = (value: string): void => {
    const text = value.trim();
    if (!text || controller.streaming) {
      return;
    }
    setInput('');
    void controller.send(text);
  };

  /**
   * 打开引用详情；不会伪造源文件内容，只展示契约返回的标题、定位与片段。
   *
   * @param citation 引用
   */
  const handleCitation = useCallback((citation: Citation): void => {
    setCitationError(null);
    setActiveCitation(citation);
  }, []);

  /**
   * 重试失败的助手回答。
   *
   * @param assistantMessageId 失败消息 ID
   */
  const { retryMessage } = controller;
  const handleRetry = useCallback(
    (assistantMessageId: string): void => {
      void retryMessage(assistantMessageId);
    },
    [retryMessage],
  );

  /**
   * 通过预签名 URL 打开原文件。
   */
  const handleOpenFile = async (): Promise<void> => {
    if (!activeCitation?.fileId) {
      return;
    }
    setCitationError(null);
    // 先同步打开空白窗口，保留用户手势，避免预签名 URL 返回后被弹窗拦截。
    const popup = window.open('about:blank', '_blank');
    if (!popup) {
      setCitationError('浏览器阻止了新窗口，请允许弹窗后重试。');
      return;
    }
    popup.opener = null;
    try {
      const download = await api.getFileDownloadUrl(activeCitation.fileId);
      popup.location.href = download.url;
    } catch (error) {
      popup.close();
      setCitationError(describeApiError(error, '无法打开原文件，请重试。'));
    }
  };

  /**
   * 选择文件后交给上传流程；清空 input 以允许重复选择同一文件。
   *
   * @param event 原生 input 事件
   */
  const handleFileInput = (event: ChangeEvent<HTMLInputElement>): void => {
    const selected = Array.from(event.currentTarget.files ?? []);
    event.currentTarget.value = '';
    if (selected.length > 0) {
      void controller.uploadFiles(selected);
    }
  };

  const bubbleRole = useMemo(
    () => ({
      user: {
        placement: 'end' as const,
        avatar: <Avatar icon={<UserOutlined />} size="small" />,
        contentRender: (content: ChatMessage) => (
          <div className="message-text">{content.content}</div>
        ),
      },
      assistant: {
        placement: 'start' as const,
        avatar: <Avatar icon={<RobotOutlined />} size="small" />,
        contentRender: (content: ChatMessage) => (
          <AssistantBubble message={content} onCitation={handleCitation} onRetry={handleRetry} />
        ),
      },
    }),
    [handleCitation, handleRetry],
  );

  const bubbleItems = useMemo<BubbleItemType[]>(
    () =>
      controller.messages.map((message) => ({
        key: message.id,
        role: message.role,
        content: message,
        status: bubbleStatus(message),
        loading: message.role === 'assistant' && message.status === 'STREAMING' && !message.content,
        streaming: message.role === 'assistant' && message.status === 'STREAMING',
      })),
    [controller.messages],
  );

  const conversationItems = useMemo(
    () =>
      controller.conversations.map((conversation) => ({
        key: conversation.id,
        icon: <MessageOutlined />,
        label: (
          // 原生 button 让会话项可通过 Tab + Enter 切换；点击事件冒泡给 Conversations 的 li。
          <button
            aria-current={conversation.id === controller.activeConversationId ? 'true' : undefined}
            className="conversation-link"
            type="button"
          >
            {conversationLabel(conversation)}
          </button>
        ),
      })),
    [controller.activeConversationId, controller.conversations],
  );

  const fileCount = controller.files.filter((file) => file.status !== 'DELETED').length;
  const atFileLimit = fileCount >= MAX_FILES_PER_CONVERSATION;
  const canUpload =
    Boolean(controller.activeConversationId) &&
    !atFileLimit &&
    !controller.uploading &&
    !controller.messagesLoading;
  const parsedFileCount = controller.files.filter(
    (file) => file.parse?.status === 'SUCCEEDED',
  ).length;

  const lastAssistant = [...controller.messages]
    .reverse()
    .find((message) => message.role === 'assistant');
  const statusAnnouncement = lastAssistant
    ? lastAssistant.status === 'COMPLETED'
      ? '回答已完成。'
      : lastAssistant.status === 'STOPPED'
        ? '回答已停止，已生成内容保留。'
        : lastAssistant.status === 'FAILED'
          ? '回答生成失败，可重试。'
          : ''
    : '';

  if (auth.status === 'unknown' || auth.status === 'checking') {
    return <FullPageSpin text="正在检查登录状态…" />;
  }

  if (auth.status === 'anonymous' || auth.status === 'expired') {
    return <FullPageSpin text="正在跳转登录…" />;
  }

  if (auth.status === 'error') {
    return (
      <Flex align="center" justify="center" style={{ minHeight: '100vh', padding: 16 }}>
        <Result
          status="error"
          title="无法确认登录状态"
          subTitle={auth.error ?? '无法连接服务器，请检查网络后重试。'}
          extra={[
            <Button key="retry" type="primary" onClick={() => void authStore.restore(api)}>
              重试
            </Button>,
            <Button
              key="login"
              onClick={() => {
                authStore.signOut(null);
                navigate('/login', { replace: true });
              }}
            >
              前往登录
            </Button>,
          ]}
        />
      </Flex>
    );
  }

  if (!auth.session) {
    return <FullPageSpin text="正在加载会话…" />;
  }

  return (
    <div className="chat-page">
      <header className="chat-header">
        <div className="chat-header-inner">
          <div>
            <Typography.Title className="chat-header-title" level={1}>
              Riccon 工作空间
            </Typography.Title>
            <div className="chat-header-meta">
              <Typography.Text type="secondary">
                {auth.session.tenant.name}
                {controller.workspace ? ` · ${controller.workspace.name}` : ''}
              </Typography.Text>
              {IS_MOCK_API && (
                <Tag style={{ color: '#0958d9', background: '#e6f4ff', borderColor: '#91caff' }}>
                  演示数据
                </Tag>
              )}
            </div>
          </div>
          <div className="chat-header-actions">
            <Button onClick={() => navigate('/research')}>市场调研报告</Button>
            <Typography.Text>{auth.session.user.displayName}</Typography.Text>
            <Button
              icon={<LogoutOutlined />}
              loading={loggingOut}
              onClick={() => {
                void handleLogout();
              }}
            >
              退出登录
            </Button>
          </div>
        </div>
        {logoutError && (
          <Alert
            showIcon
            closable
            description={logoutError}
            onClose={() => setLogoutError(null)}
            style={{ margin: '0 24px 12px' }}
            title="退出登录失败"
            type="error"
          />
        )}
      </header>

      {controller.phase === 'loading' && <FullPageSpin text="正在加载工作空间…" />}

      {controller.phase === 'forbidden' && (
        <WorkspaceResult
          status="403"
          title="没有访问权限"
          subTitle={controller.error ?? '当前账号无权访问该工作空间，请联系管理员。'}
          onRetry={() => void controller.initialize()}
          onLogout={() => {
            void handleLogout();
          }}
        />
      )}

      {controller.phase === 'error' && (
        <WorkspaceResult
          status="error"
          title="工作空间加载失败"
          subTitle={controller.error ?? '请检查网络后重试。'}
          onRetry={() => void controller.initialize()}
          onLogout={() => {
            void handleLogout();
          }}
        />
      )}

      {controller.phase === 'empty' && (
        <WorkspaceResult
          status="warning"
          title="暂无可用工作空间"
          subTitle="当前租户还没有分配个人或团队工作空间，请联系管理员开通。"
          onRetry={() => void controller.initialize()}
          onLogout={() => {
            void handleLogout();
          }}
        />
      )}

      {controller.phase === 'ready' && (
        <main className="chat-main">
          <aside aria-label="会话列表" className="chat-sidebar">
            <div className="chat-sidebar-header">
              <Typography.Text strong>我的会话</Typography.Text>
              <Button
                aria-label="新建会话"
                icon={<PlusOutlined />}
                loading={controller.creatingConversation}
                onClick={() => {
                  void controller.createConversation();
                }}
                size="small"
              >
                新建会话
              </Button>
            </div>
            {controller.conversationsLoading ? (
              <div aria-busy="true" aria-label="正在加载会话列表">
                <Skeleton active paragraph={{ rows: 3 }} title={false} />
              </div>
            ) : controller.conversations.length === 0 ? (
              <Empty
                description="还没有会话，点击上方“新建”开始"
                image={Empty.PRESENTED_IMAGE_SIMPLE}
              />
            ) : (
              <Conversations
                activeKey={controller.activeConversationId ?? undefined}
                items={conversationItems}
                onActiveChange={(key) => {
                  void controller.selectConversation(String(key));
                }}
              />
            )}
          </aside>

          <section aria-label="对话区域" className="chat-panel">
            {!controller.activeConversationId ? (
              <Flex align="center" justify="center" style={{ flex: 1, minHeight: 0 }}>
                <Empty description="选择左侧会话，或新建一个会话开始提问">
                  <Button
                    type="primary"
                    onClick={() => {
                      void controller.createConversation();
                    }}
                  >
                    开始新会话
                  </Button>
                </Empty>
              </Flex>
            ) : (
              <>
                <section aria-labelledby="file-panel-title" className="chat-file-panel">
                  <div className="chat-file-header">
                    <div>
                      <Typography.Text id="file-panel-title" strong>
                        会话文件 {fileCount}/{MAX_FILES_PER_CONVERSATION}
                      </Typography.Text>
                      <Typography.Text className="chat-file-hint" id="file-hint" type="secondary">
                        支持 PDF、DOCX、XLSX、PNG、JPEG；单文件 ≤ 200 MB。
                      </Typography.Text>
                    </div>
                    <Button
                      aria-describedby="file-hint"
                      disabled={!canUpload}
                      icon={<UploadOutlined />}
                      loading={controller.uploading}
                      onClick={() => {
                        fileInputRef.current?.click();
                      }}
                      size="small"
                    >
                      上传文件
                    </Button>
                    <input
                      accept={FILE_ACCEPT}
                      aria-hidden="true"
                      className="file-input"
                      disabled={!canUpload}
                      id="j1-file-input"
                      ref={fileInputRef}
                      multiple
                      onChange={handleFileInput}
                      tabIndex={-1}
                      type="file"
                    />
                  </div>

                  {atFileLimit && (
                    <Typography.Text className="chat-file-hint" type="warning">
                      已达到每个会话 5 个文件上限；如需上传新文件，请先删除已有文件。
                    </Typography.Text>
                  )}

                  {controller.uploadErrors.length > 0 && (
                    <Alert
                      showIcon
                      closable
                      description={
                        <ul style={{ margin: 0, paddingLeft: 20 }}>
                          {controller.uploadErrors.map((item) => (
                            <li key={item}>{item}</li>
                          ))}
                        </ul>
                      }
                      onClose={controller.dismissUploadErrors}
                      style={{ marginTop: 8 }}
                      title="部分文件未上传"
                      type="error"
                    />
                  )}

                  {controller.filesLoading ? (
                    <Flex align="center" gap={8} style={{ marginTop: 8 }}>
                      <Spin size="small" />
                      <Typography.Text type="secondary">正在加载文件…</Typography.Text>
                    </Flex>
                  ) : controller.files.length === 0 ? (
                    <Typography.Paragraph
                      className="chat-file-list"
                      style={{ marginBottom: 0 }}
                      type="secondary"
                    >
                      尚未上传文件。上传并等待“解析成功”后，提问会自动附带这些文件。
                    </Typography.Paragraph>
                  ) : (
                    <ul aria-live="polite" className="chat-file-list">
                      {controller.files
                        .filter((file) => file.status !== 'DELETED')
                        .map((file) => (
                          <li key={file.id}>
                            <FileRow
                              file={file}
                              onRetry={(fileId) => {
                                void controller.retryParse(fileId);
                              }}
                            />
                          </li>
                        ))}
                    </ul>
                  )}
                </section>

                <div className="chat-messages">
                  <div aria-live="polite" className="sr-only">
                    {statusAnnouncement}
                  </div>
                  {controller.messagesLoading ? (
                    <Flex align="center" gap={12} justify="center" style={{ flex: 1 }}>
                      <Spin />
                      <Typography.Text>正在加载会话…</Typography.Text>
                    </Flex>
                  ) : controller.messagesError ? (
                    <Flex align="center" justify="center" style={{ flex: 1 }}>
                      {controller.messagesError.kind === 'forbidden' ? (
                        <Result
                          status="403"
                          title="没有访问权限"
                          subTitle={controller.messagesError.message}
                          extra={[
                            <Button
                              key="retry"
                              onClick={() => void controller.reloadActive()}
                              type="primary"
                            >
                              重试
                            </Button>,
                            <Button key="new" onClick={() => void controller.createConversation()}>
                              新建会话
                            </Button>,
                          ]}
                        />
                      ) : controller.messagesError.kind === 'notFound' ? (
                        <Result
                          status="404"
                          title="会话不存在"
                          subTitle={controller.messagesError.message}
                          extra={[
                            <Button
                              key="new"
                              onClick={() => void controller.createConversation()}
                              type="primary"
                            >
                              新建会话
                            </Button>,
                            <Button key="retry" onClick={() => void controller.reloadActive()}>
                              重试
                            </Button>,
                          ]}
                        />
                      ) : (
                        <Result
                          status="error"
                          title="会话加载失败"
                          subTitle={controller.messagesError.message}
                          extra={
                            <Button onClick={() => void controller.reloadActive()} type="primary">
                              重试
                            </Button>
                          }
                        />
                      )}
                    </Flex>
                  ) : controller.messages.length === 0 ? (
                    <Flex align="center" justify="center" style={{ flex: 1, minHeight: 0 }}>
                      <Welcome
                        description="上传办公文件后提问；回答会附带可定位的引用，并支持中途停止。"
                        icon={<RobotOutlined style={{ fontSize: 36 }} />}
                        title="开始 J1 问答"
                        variant="borderless"
                      />
                    </Flex>
                  ) : (
                    <Bubble.List
                      autoScroll
                      className="chat-message-list"
                      items={bubbleItems}
                      role={bubbleRole}
                    />
                  )}
                </div>

                <div className="chat-composer">
                  {controller.actionError && (
                    <Alert
                      showIcon
                      closable
                      description={controller.actionError}
                      onClose={controller.dismissActionError}
                      style={{ marginBottom: 8 }}
                      title="操作未完成"
                      type="error"
                    />
                  )}
                  {controller.streaming && (
                    <div className="chat-composer-status">
                      <Typography.Text type="secondary">正在生成回答…</Typography.Text>
                      <Button
                        danger
                        onClick={() => {
                          void controller.stop();
                        }}
                        size="small"
                        type="link"
                      >
                        停止生成
                      </Button>
                    </div>
                  )}
                  <Sender
                    disabled={controller.messagesLoading}
                    loading={controller.streaming}
                    onCancel={() => {
                      void controller.stop();
                    }}
                    onChange={setInput}
                    onSubmit={handleSubmit}
                    placeholder="输入问题，Enter 发送，Shift+Enter 换行"
                    value={input}
                  />
                  <Typography.Text className="chat-composer-hint" type="secondary">
                    {parsedFileCount > 0
                      ? `提问会附带 ${parsedFileCount} 个已解析文件；引用可点击查看位置。`
                      : '可先上传文件，也可以直接提问；无命中时不会展示伪造引用。'}
                  </Typography.Text>
                </div>
              </>
            )}
          </section>
        </main>
      )}

      <Drawer
        destroyOnHidden
        onClose={() => setActiveCitation(null)}
        open={Boolean(activeCitation)}
        size="min(480px, 92vw)"
        title="引用详情"
      >
        {activeCitation && (
          <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
            <div>
              <Typography.Text type="secondary">来源</Typography.Text>
              <Typography.Title level={4} style={{ margin: '4px 0 0' }}>
                {activeCitation.title}
              </Typography.Title>
            </div>
            <Typography.Text>
              类型：{citationSourceLabel(activeCitation)}；定位：
              {formatCitationLocator(activeCitation)}
            </Typography.Text>
            <div>
              <Typography.Text strong>片段</Typography.Text>
              <Typography.Paragraph className="citation-snippet">
                {activeCitation.snippet}
              </Typography.Paragraph>
            </div>
            {citationError && (
              <Alert showIcon description={citationError} title="无法打开原文件" type="error" />
            )}
            {activeCitation.fileId && (
              <Button
                onClick={() => {
                  void handleOpenFile();
                }}
                type="primary"
              >
                打开原文件
              </Button>
            )}
          </Space>
        )}
      </Drawer>
    </div>
  );
}

/**
 * 全屏加载提示。
 *
 * @param props 文案
 * @returns 居中加载态
 */
function FullPageSpin({ text }: { text: string }) {
  return (
    <Flex align="center" gap={16} justify="center" style={{ minHeight: '100vh', padding: 24 }}>
      <Spin size="large" />
      <Typography.Text>{text}</Typography.Text>
    </Flex>
  );
}

/**
 * 工作空间级失败/空态。
 *
 * @param props 状态、文案与动作
 * @returns Result 页面
 */
function WorkspaceResult({
  status,
  title,
  subTitle,
  onRetry,
  onLogout,
}: {
  status: '403' | 'error' | 'warning';
  title: string;
  subTitle: ReactNode;
  onRetry: () => void;
  onLogout: () => void;
}) {
  return (
    <Flex align="center" justify="center" style={{ flex: 1, padding: 16 }}>
      <Result
        status={status}
        title={title}
        subTitle={subTitle}
        extra={[
          <Button key="retry" onClick={onRetry} type="primary">
            重试
          </Button>,
          <Button key="logout" onClick={onLogout}>
            退出登录
          </Button>,
        ]}
      />
    </Flex>
  );
}

/**
 * 助手气泡内容：正文、引用、失败重试与无引用提示。
 *
 * @param props 消息与回调
 * @returns 气泡内部节点
 */
function AssistantBubble({
  message,
  onCitation,
  onRetry,
}: {
  message: ChatMessage;
  onCitation: (citation: Citation) => void;
  onRetry: (assistantMessageId: string) => void;
}) {
  const streaming = message.status === 'STREAMING';
  return (
    <div>
      {message.content ? (
        <div className="message-text">
          {message.content}
          {streaming && (
            <span aria-hidden="true" className="stream-tail">
              ▍
            </span>
          )}
        </div>
      ) : streaming ? (
        <Typography.Text type="secondary">正在生成回答…</Typography.Text>
      ) : null}

      {message.citations.length > 0 && (
        <div aria-label="引用来源" className="citation-block">
          <Typography.Text strong>引用来源</Typography.Text>
          <ul className="citation-list">
            {message.citations.map((citation) => (
              <li key={citation.ordinal}>
                <Button
                  className="citation-link"
                  onClick={() => onCitation(citation)}
                  size="small"
                  type="link"
                >
                  <span aria-hidden="true">[{citation.ordinal}] </span>
                  {citation.title} · {formatCitationLocator(citation)}
                </Button>
              </li>
            ))}
          </ul>
        </div>
      )}

      {message.status === 'COMPLETED' && message.content && message.citations.length === 0 && (
        <Typography.Text className="no-citation-note" type="secondary">
          未检索到相关资料；以上回答未引用文件或知识库。
        </Typography.Text>
      )}

      {message.status === 'STOPPED' && (
        <Tag
          className="status-tag"
          style={{ color: '#595959', background: '#f5f5f5', borderColor: '#d9d9d9' }}
        >
          已停止生成
        </Tag>
      )}

      {message.status === 'FAILED' && (
        <div className="message-error" role="alert">
          <Typography.Text type="danger">
            {message.error?.message ?? '回答生成失败，请重试。'}
          </Typography.Text>
          {message.error?.code && (
            <Typography.Text className="error-code" type="secondary">
              错误码：{message.error.code}
            </Typography.Text>
          )}
          {message.error?.retryable !== false && (
            <Button onClick={() => onRetry(message.id)} size="small">
              重试
            </Button>
          )}
        </div>
      )}
    </div>
  );
}

/**
 * 文件行：格式、体积、解析状态与失败重试。
 *
 * @param props 文件与重试回调
 * @returns 文件行内容
 */
function FileRow({ file, onRetry }: { file: WorkspaceFile; onRetry: (fileId: string) => void }) {
  const status = file.parse?.status ?? 'PENDING';
  return (
    <div className="chat-file-item">
      <span aria-hidden="true">{fileIcon(file.format)}</span>
      <Typography.Text className="chat-file-name" title={file.originalName}>
        {file.originalName}
      </Typography.Text>
      <Typography.Text type="secondary">{formatBytes(file.sizeBytes)}</Typography.Text>
      <ParseStatusTag status={status} />
      {status === 'FAILED' && (
        <>
          <Typography.Text type="danger">
            {file.parse?.errorMessage ?? '文件解析失败，请重试。'}
          </Typography.Text>
          <Button
            aria-label={`重试解析 ${file.originalName}`}
            onClick={() => onRetry(file.id)}
            size="small"
            type="link"
          >
            重试
          </Button>
        </>
      )}
    </div>
  );
}

/**
 * 解析状态标签。
 *
 * @param props 状态
 * @returns 中文状态 Tag
 */
function ParseStatusTag({ status }: { status: string }) {
  switch (status) {
    case 'SUCCEEDED':
      return (
        <Tag style={{ color: '#237804', background: '#f6ffed', borderColor: '#b7eb8f' }}>
          解析成功
        </Tag>
      );
    case 'FAILED':
      return (
        <Tag style={{ color: '#a8071a', background: '#fff1f0', borderColor: '#ffa39e' }}>
          解析失败
        </Tag>
      );
    case 'RUNNING':
      return (
        <Tag
          icon={<LoadingOutlined spin />}
          style={{ color: '#0958d9', background: '#e6f4ff', borderColor: '#91caff' }}
        >
          处理中
        </Tag>
      );
    case 'PENDING':
    default:
      return (
        <Tag style={{ color: '#595959', background: '#fafafa', borderColor: '#d9d9d9' }}>
          排队中
        </Tag>
      );
  }
}

/**
 * 按格式返回文件图标；仅作视觉辅助，文件名与状态才是可访问信息。
 *
 * @param format 文件格式
 * @returns 图标节点
 */
function fileIcon(format: WorkspaceFile['format']): ReactNode {
  switch (format) {
    case 'PDF':
      return <FilePdfOutlined />;
    case 'DOCX':
      return <FileWordOutlined />;
    case 'XLSX':
      return <FileExcelOutlined />;
    case 'PNG':
    case 'JPEG':
    default:
      return <FileImageOutlined />;
  }
}
