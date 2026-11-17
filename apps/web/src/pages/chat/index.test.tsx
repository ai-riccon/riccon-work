// @vitest-environment jsdom
import { App as AntdApp } from 'antd';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import ChatPage from './index';

/**
 * 员工端工作空间组件测试。
 *
 * 覆盖骨架阶段的三个可观察行为：首屏会话与消息、会话切换、发送占位消息；
 * 同时验证空内容不会被提交（防止后续接模型后发送空请求）。
 */
describe('员工端工作空间', () => {
  it('渲染导航、会话列表与初始消息', () => {
    render(
      <AntdApp>
        <ChatPage />
      </AntdApp>,
    );

    expect(screen.getByRole('heading', { name: 'Riccon 工作空间' })).toBeVisible();
    expect(screen.getByRole('link', { name: '管理端' })).toHaveAttribute('href', '/admin');
    expect(screen.getByRole('link', { name: '登录' })).toHaveAttribute('href', '/login');

    expect(screen.getByText('我的会话')).toBeVisible();
    expect(screen.getByText('新员工入职问答')).toBeVisible();
    expect(screen.getByText('市场调研报告')).toBeVisible();
    expect(screen.getByText(/你好，我是 Riccon 工作空间助手/)).toBeVisible();
    expect(screen.getByPlaceholderText('输入消息，Enter 发送（M01 不调用模型）')).toBeVisible();
  });

  it('切换会话时更新激活项', () => {
    const { container } = render(
      <AntdApp>
        <ChatPage />
      </AntdApp>,
    );

    const marketResearch = screen.getByText('市场调研报告').closest('li');
    expect(marketResearch).not.toBeNull();
    expect(marketResearch).not.toHaveClass('ant-conversations-item-active');

    fireEvent.click(marketResearch as HTMLElement);

    expect(marketResearch).toHaveClass('ant-conversations-item-active');
    expect(container.querySelectorAll('.ant-conversations-item-active')).toHaveLength(1);
  });

  it('发送非空内容后追加本地问答并清空输入', async () => {
    const { container } = render(
      <AntdApp>
        <ChatPage />
      </AntdApp>,
    );

    const textarea = screen.getByPlaceholderText('输入消息，Enter 发送（M01 不调用模型）');
    fireEvent.change(textarea, { target: { value: '请总结今天的会议' } });

    const sendButton = container.querySelector('.ant-sender-actions-btn') as HTMLButtonElement;
    expect(sendButton).not.toBeDisabled();
    fireEvent.click(sendButton);

    const matches = screen.getAllByText('请总结今天的会议');
    expect(matches.length).toBe(1);
    expect(matches[0]).toHaveClass('ant-bubble-content');
    expect(matches[0]).toBeVisible();
    expect(screen.getByText('M01 骨架阶段不调用模型，这条回复是本地占位文本。')).toBeVisible();
    expect(await screen.findByText('M01 骨架：暂不发起模型请求')).toBeInTheDocument();
    expect(textarea).toHaveValue('');
  });

  it('空白内容不会追加消息', () => {
    const { container } = render(
      <AntdApp>
        <ChatPage />
      </AntdApp>,
    );

    const textarea = screen.getByPlaceholderText('输入消息，Enter 发送（M01 不调用模型）');
    fireEvent.change(textarea, { target: { value: '   ' } });

    const sendButton = container.querySelector('.ant-sender-actions-btn') as HTMLButtonElement;
    fireEvent.click(sendButton);

    expect(
      screen.queryByText('M01 骨架阶段不调用模型，这条回复是本地占位文本。'),
    ).not.toBeInTheDocument();
    expect(textarea).toHaveValue('   ');
  });
});
