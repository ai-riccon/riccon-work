package com.riccon.api.chat.domain;

import com.riccon.platform.common.tenant.TenantId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次已持久化的对话提交。
 *
 * <p>用户消息、assistant 占位、agent_run 与审计在同一事务写入后生成；随后 API 用服务令牌调用 Worker 内部 SSE。
 * {@code assistantMessageId} 是 API 生成的 UUIDv7，转发给浏览器前会写入每个 SSE 事件的 {@code messageId}，
 * 保证数据库消息、SSE 与前端游标一致。
 *
 * @param tenantId 租户
 * @param userId 发起人
 * @param conversationId 会话 ID
 * @param userMessageId 用户消息 ID
 * @param assistantMessageId assistant 占位消息 ID
 * @param runId 运行 ID
 * @param traceId 追踪 ID
 * @param fileIds 附件文件 ID
 */
public record ChatSubmission(
        TenantId tenantId,
        UUID userId,
        UUID conversationId,
        UUID userMessageId,
        UUID assistantMessageId,
        UUID runId,
        String traceId,
        List<UUID> fileIds) {

    /** 校验必填字段并复制附件列表。 */
    public ChatSubmission {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(conversationId, "conversationId must not be null");
        Objects.requireNonNull(userMessageId, "userMessageId must not be null");
        Objects.requireNonNull(assistantMessageId, "assistantMessageId must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(traceId, "traceId must not be null");
        fileIds = fileIds == null ? List.of() : List.copyOf(fileIds);
    }
}
