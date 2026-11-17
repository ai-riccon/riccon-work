package com.riccon.worker.chat.service;

import com.riccon.platform.domain.chat.CitationSourceType;
import com.riccon.platform.domain.contract.EventTypes;
import com.riccon.worker.agent.domain.RetrievedChunk;
import com.riccon.worker.chat.domain.ChatEvent;
import com.riccon.worker.common.UuidV7;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 对话 SSE 事件工厂。
 *
 * <p>所有事件使用 UUIDv7 事件 ID、UTC 时间与固定版本 1；引用只发送截断片段，
 * 不发送系统提示、检索原文、工具结果或密钥。
 */
@Component
public class ChatEventFactory {

    /** 引用片段最大长度。 */
    static final int CITATION_SNIPPET_LENGTH = 500;

    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建事件工厂。
     *
     * @param clock UTC 时钟
     */
    public ChatEventFactory(Clock clock) {
        this.clock = clock;
    }

    /**
     * 开始事件。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param messageId 消息 ID
     * @param traceId 追踪 ID
     * @param modelId 模型 ID
     * @param modelKey 模型 key
     * @return 事件
     */
    public ChatEvent started(
            String tenantId, UUID runId, UUID messageId, String traceId, UUID modelId, String modelKey) {
        return event(
                EventTypes.CHAT_STARTED,
                tenantId,
                runId,
                messageId,
                traceId,
                new ChatEvent.Started(modelId.toString(), modelKey));
    }

    /**
     * 增量事件。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param messageId 消息 ID
     * @param traceId 追踪 ID
     * @param delta 文本增量
     * @return 事件
     */
    public ChatEvent delta(String tenantId, UUID runId, UUID messageId, String traceId, String delta) {
        return event(EventTypes.CHAT_DELTA, tenantId, runId, messageId, traceId, new ChatEvent.Delta(delta));
    }

    /**
     * 引用事件。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param messageId 消息 ID
     * @param traceId 追踪 ID
     * @param citations 引用
     * @return 事件；无引用时返回 null，调用方不得发送空引用事件
     */
    public ChatEvent citations(
            String tenantId, UUID runId, UUID messageId, String traceId, List<RetrievedChunk> citations) {
        if (citations == null || citations.isEmpty()) {
            return null;
        }
        List<ChatEvent.Citation> mapped = citations.stream()
                .map(chunk -> new ChatEvent.Citation(
                        chunk.ordinal(),
                        chunk.sourceType(),
                        chunk.fileId(),
                        chunk.knowledgeDocumentId(),
                        chunk.chunkId(),
                        chunk.locatorType(),
                        chunk.pageNo(),
                        chunk.sheetName(),
                        chunk.cellRange(),
                        chunk.paragraphNo(),
                        title(chunk),
                        chunk.snippet(CITATION_SNIPPET_LENGTH),
                        chunk.sourceType() == CitationSourceType.KNOWLEDGE ? chunk.score() : null))
                .toList();
        return event(EventTypes.CHAT_CITATION, tenantId, runId, messageId, traceId, new ChatEvent.CitationData(mapped));
    }

    /**
     * 完成事件。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param messageId 消息 ID
     * @param traceId 追踪 ID
     * @param finishReason 完成原因
     * @param promptTokens 输入 token
     * @param completionTokens 输出 token
     * @param firstTokenMs 首字延迟
     * @return 事件
     */
    public ChatEvent completed(
            String tenantId,
            UUID runId,
            UUID messageId,
            String traceId,
            String finishReason,
            Integer promptTokens,
            Integer completionTokens,
            Integer firstTokenMs) {
        return event(
                EventTypes.CHAT_COMPLETED,
                tenantId,
                runId,
                messageId,
                traceId,
                new ChatEvent.Completed(finishReason, promptTokens, completionTokens, firstTokenMs));
    }

    /**
     * 失败事件。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param messageId 消息 ID
     * @param traceId 追踪 ID
     * @param code 错误码
     * @param message 脱敏说明
     * @param retryable 是否可重试
     * @return 事件
     */
    public ChatEvent failed(
            String tenantId,
            UUID runId,
            UUID messageId,
            String traceId,
            String code,
            String message,
            boolean retryable) {
        return event(
                EventTypes.CHAT_FAILED,
                tenantId,
                runId,
                messageId,
                traceId,
                new ChatEvent.Failed(code, message, retryable));
    }

    /**
     * 停止事件。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param messageId 消息 ID
     * @param traceId 追踪 ID
     * @param reason 原因
     * @return 事件
     */
    public ChatEvent stopped(String tenantId, UUID runId, UUID messageId, String traceId, String reason) {
        return event(EventTypes.CHAT_STOPPED, tenantId, runId, messageId, traceId, new ChatEvent.Stopped(reason));
    }

    /**
     * 构造基础事件。
     *
     * @param eventType 类型
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param messageId 消息 ID
     * @param traceId 追踪 ID
     * @param data 数据
     * @return 事件
     */
    private ChatEvent event(
            String eventType, String tenantId, UUID runId, UUID messageId, String traceId, Object data) {
        return new ChatEvent(
                UuidV7.generate(),
                eventType,
                1,
                Instant.now(clock).toString(),
                tenantId,
                runId,
                messageId,
                traceId,
                data);
    }

    /**
     * 返回非空标题，避免引用 schema 的 title 为 null。
     *
     * @param chunk 分块
     * @return 标题
     */
    private static String title(RetrievedChunk chunk) {
        return chunk.title() == null || chunk.title().isBlank() ? "未命名来源" : chunk.title();
    }
}
