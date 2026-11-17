package com.riccon.worker.agent.domain;

import com.riccon.platform.domain.chat.MessageRole;
import java.util.UUID;

/**
 * 会话历史消息快照。
 *
 * @param id 消息 ID
 * @param role 角色
 * @param content 文本内容
 */
public record ConversationMessage(UUID id, MessageRole role, String content) {}
