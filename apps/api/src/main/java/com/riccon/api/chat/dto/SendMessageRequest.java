package com.riccon.api.chat.dto;

import java.util.List;
import java.util.UUID;

/**
 * 发送消息请求，对应 OpenAPI {@code SendMessageRequest}。
 *
 * @param content 消息内容，1–20000 字符
 * @param clientMessageId 客户端幂等键，8–64 字符
 * @param fileIds 附件文件 ID，最多 5 个
 */
public record SendMessageRequest(String content, String clientMessageId, List<UUID> fileIds) {}
