import { RobotOutlined, UserOutlined } from '@ant-design/icons';
import { Bubble, Conversations, Sender } from '@ant-design/x';
import type { BubbleItemType } from '@ant-design/x';
import { Link } from '@umijs/max';
import { App as AntdApp, Avatar, Flex, Space, Typography } from 'antd';
import { useRef, useState } from 'react';

/**
 * 会话列表占位数据。
 *
 * M01 不接会话 API；M02 起由 `/api/v1/chat/sessions` 提供并带 tenant 作用域。
 */
const DEMO_CONVERSATIONS = [
  { key: 'kb-onboarding', label: '新员工入职问答', icon: <RobotOutlined /> },
  { key: 'market-research', label: '市场调研报告', icon: <RobotOutlined /> },
  { key: 'file-review', label: '合同文件审阅', icon: <UserOutlined /> },
];

/**
 * 首屏消息占位，用于验证 Bubble.List 的多角色渲染。
 *
 * 内容只描述能力边界，不含真实业务数据或 PII。
 */
const INITIAL_MESSAGES: BubbleItemType[] = [
  {
    key: 'm-1',
    role: 'ai',
    content: '你好，我是 Riccon 工作空间助手。M01 阶段我只会展示布局，不会调用模型。',
  },
  {
    key: 'm-2',
    role: 'user',
    content: 'J1 旅程里，我能上传 PDF 或 Excel 并拿到带引用的回答吗？',
  },
  {
    key: 'm-3',
    role: 'ai',
    content: 'M02 会接入文件上传、知识库检索与流式回答；当前页面只演示气泡与会话列表的结构。',
  },
];

/**
 * 员工端云工作空间占位页。
 *
 * 布局：顶部应用栏 + 左侧会话列表 + 右侧消息流与输入框，整页全高。
 * 数据流：所有交互只修改本地状态，不发起网络请求；模型与知识库在 M02 接入。
 *
 * @returns 全高聊天布局
 */
export default function ChatPage() {
  const { message } = AntdApp.useApp();
  const [activeKey, setActiveKey] = useState(DEMO_CONVERSATIONS[0].key);
  const [input, setInput] = useState('');
  const [messages, setMessages] = useState<BubbleItemType[]>(INITIAL_MESSAGES);
  const messageSeq = useRef(INITIAL_MESSAGES.length);

  /**
   * 提交输入内容。
   *
   * 骨架阶段只做本地回显：追加一条用户消息和一条说明性占位回复，避免误以为已接通模型。
   */
  const handleSubmit = (content: string) => {
    const text = content.trim();
    if (!text) {
      return;
    }
    messageSeq.current += 1;
    const userKey = `local-user-${messageSeq.current}`;
    messageSeq.current += 1;
    const aiKey = `local-ai-${messageSeq.current}`;
    setMessages((previous) => [
      ...previous,
      { key: userKey, role: 'user', content: text },
      {
        key: aiKey,
        role: 'ai',
        content: 'M01 骨架阶段不调用模型，这条回复是本地占位文本。',
      },
    ]);
    setInput('');
    message.info('M01 骨架：暂不发起模型请求');
  };

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        height: '100vh',
        background: '#fff',
      }}
    >
      <header style={{ borderBottom: '1px solid #f0f0f0' }}>
        <Flex align="center" justify="space-between" style={{ padding: '12px 24px' }}>
          <Flex align="center" gap={12}>
            <Typography.Title level={4} style={{ margin: 0 }}>
              Riccon 工作空间
            </Typography.Title>
            <Typography.Text type="secondary">M01 骨架</Typography.Text>
          </Flex>
          <nav aria-label="全局导航">
            <Space size="large">
              <Link to="/admin">管理端</Link>
              <Link to="/login">登录</Link>
            </Space>
          </nav>
        </Flex>
      </header>

      <main style={{ display: 'flex', flex: 1, gap: 16, minHeight: 0, padding: 16 }}>
        <aside
          aria-label="会话列表"
          style={{
            borderRight: '1px solid #f0f0f0',
            flex: '0 0 260px',
            overflow: 'auto',
            paddingRight: 16,
          }}
        >
          <Typography.Text strong>我的会话</Typography.Text>
          <Conversations
            activeKey={activeKey}
            items={DEMO_CONVERSATIONS}
            onActiveChange={(key) => setActiveKey(String(key))}
            style={{ marginTop: 8 }}
          />
        </aside>

        <section
          aria-label="对话区域"
          style={{
            display: 'flex',
            flex: 1,
            flexDirection: 'column',
            gap: 12,
            minWidth: 0,
          }}
        >
          <Bubble.List
            autoScroll
            items={messages}
            role={{
              ai: {
                avatar: <Avatar icon={<RobotOutlined />} size="small" />,
                placement: 'start',
              },
              user: {
                avatar: <Avatar icon={<UserOutlined />} size="small" />,
                placement: 'end',
              },
            }}
            style={{ flex: 1, overflow: 'auto' }}
          />
          <Sender
            onChange={(value) => setInput(value)}
            onSubmit={handleSubmit}
            placeholder="输入消息，Enter 发送（M01 不调用模型）"
            value={input}
          />
          <Typography.Text type="secondary">
            M01 骨架：消息仅保存在浏览器内存，刷新即清空；模型、知识库与文件处理在 M02 接入。
          </Typography.Text>
        </section>
      </main>
    </div>
  );
}
