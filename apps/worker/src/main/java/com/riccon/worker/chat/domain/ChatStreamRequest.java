package com.riccon.worker.chat.domain;

import java.util.List;
import java.util.UUID;

/**
 * 内部对话流请求（internal-api.yaml ChatStreamRequest）。
 *
 * @param runId 运行 ID
 * @param tenantId 租户
 * @param userId 用户 ID
 * @param conversationId 会话 ID
 * @param userMessageId 用户消息 ID
 * @param fileIds 会话文件 ID，最多 5 个
 */
public record ChatStreamRequest(
        UUID runId, String tenantId, UUID userId, UUID conversationId, UUID userMessageId, List<UUID> fileIds) {

    /** 规范化可空文件列表。 */
    public ChatStreamRequest {
        fileIds = fileIds == null ? List.of() : List.copyOf(fileIds);
    }
}
