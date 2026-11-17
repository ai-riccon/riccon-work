package com.riccon.api.chat.service;

import com.riccon.api.common.UuidV7;
import com.riccon.platform.domain.contract.EventTypes;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * API 侧合成的对话 SSE 事件。
 *
 * <p>仅在 Worker 流异常结束、空闲超时或客户端断开时使用；事件结构与
 * {@code contracts/events/sse/chat-event.v1.schema.json} 一致，{@code messageId} 固定为数据库 assistant 消息 ID。
 * 不合成 delta，避免在失败后追加内容。
 */
@Component
public class ApiChatEventFactory {

    /** JSON 节点工厂。 */
    private final ObjectMapper objectMapper;

    /** UTC 时钟。 */
    private final Clock clock;

    /**
     * 创建事件工厂。
     *
     * @param objectMapper JSON 序列化
     * @param clock UTC 时钟
     */
    public ApiChatEventFactory(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 构造 failed 事件。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param messageId assistant 消息 ID
     * @param traceId 追踪 ID
     * @param code 稳定错误码
     * @param message 脱敏说明，最长 500
     * @param retryable 是否可重试
     * @return 事件 JSON
     */
    public ObjectNode failed(
            String tenantId,
            UUID runId,
            UUID messageId,
            String traceId,
            String code,
            String message,
            boolean retryable) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("code", code);
        data.put("message", message);
        data.put("retryable", retryable);
        return envelope(EventTypes.CHAT_FAILED, tenantId, runId, messageId, traceId, data);
    }

    /**
     * 构造 stopped 事件。
     *
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param messageId assistant 消息 ID
     * @param traceId 追踪 ID
     * @param reason 停止原因
     * @return 事件 JSON
     */
    public ObjectNode stopped(String tenantId, UUID runId, UUID messageId, String traceId, String reason) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("reason", reason);
        return envelope(EventTypes.CHAT_STOPPED, tenantId, runId, messageId, traceId, data);
    }

    /**
     * 构造事件信封。
     *
     * @param eventType 事件类型
     * @param tenantId 租户
     * @param runId 运行 ID
     * @param messageId assistant 消息 ID
     * @param traceId 追踪 ID
     * @param data 事件数据
     * @return 事件 JSON
     */
    private ObjectNode envelope(
            String eventType, String tenantId, UUID runId, UUID messageId, String traceId, ObjectNode data) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("eventId", UuidV7.generate().toString());
        root.put("eventType", eventType);
        root.put("version", 1);
        root.put("occurredAt", clock.instant().toString());
        root.put("tenantId", tenantId);
        root.put("runId", runId.toString());
        root.put("messageId", messageId.toString());
        root.put("traceId", traceId);
        root.set("data", data);
        return root;
    }
}
