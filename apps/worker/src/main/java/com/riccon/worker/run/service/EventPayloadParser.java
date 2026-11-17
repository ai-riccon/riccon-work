package com.riccon.worker.run.service;

import com.riccon.worker.run.domain.ClaimedOutboxEvent;
import com.riccon.worker.run.domain.EventProcessingException;
import com.riccon.worker.run.domain.WorkerErrorCodes;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Outbox 事件 payload 解析与校验。
 *
 * <p>payload 是不可信输入：只读取白名单字段，缺失、类型错误或 UUID 非法都视为不可重试错误，
 * 不把原文写进异常消息。事件类型与字段命名以 {@code contracts/events/outbox} 为准。
 */
@Component
public class EventPayloadParser {

    /** JSON 解析器。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建 payload 解析器。
     *
     * @param objectMapper JSON 解析器
     */
    public EventPayloadParser(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * 解析事件 payload。
     *
     * @param event 已领取事件
     * @return JSON 对象
     * @throws EventProcessingException payload 不是合法 JSON 对象
     */
    public JsonNode parse(ClaimedOutboxEvent event) {
        try {
            JsonNode root = objectMapper.readTree(event.payload());
            if (root == null || !root.isObject()) {
                throw invalid("payload must be a JSON object");
            }
            return root;
        } catch (JacksonException ex) {
            throw new EventProcessingException(
                    WorkerErrorCodes.EVENT_INVALID, false, "event payload is not valid JSON", ex);
        }
    }

    /**
     * 读取必填 UUID 字段。
     *
     * @param payload JSON 对象
     * @param field 字段名
     * @return UUID
     */
    public UUID requiredUuid(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || !node.isTextual()) {
            throw invalid("payload field " + field + " must be a UUID string");
        }
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException ex) {
            throw invalid("payload field " + field + " is not a valid UUID");
        }
    }

    /**
     * 读取可空 UUID 字段。
     *
     * @param payload JSON 对象
     * @param field 字段名
     * @return UUID 或 null
     */
    public UUID optionalUuid(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw invalid("payload field " + field + " must be a UUID string or null");
        }
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException ex) {
            throw invalid("payload field " + field + " is not a valid UUID");
        }
    }

    /**
     * 读取必填正整数。
     *
     * @param payload JSON 对象
     * @param field 字段名
     * @return 正整数值
     */
    public int requiredPositiveInt(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || !node.isInt() || node.asInt() < 1) {
            throw invalid("payload field " + field + " must be a positive integer");
        }
        return node.asInt();
    }

    /**
     * 构造不可重试的 payload 错误。
     *
     * @param message 脱敏摘要
     * @return 事件处理异常
     */
    private static EventProcessingException invalid(String message) {
        return new EventProcessingException(WorkerErrorCodes.EVENT_INVALID, false, message);
    }
}
