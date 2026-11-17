package com.riccon.api.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.chat.CitationSourceType;
import com.riccon.platform.domain.chat.LocatorType;
import com.riccon.platform.domain.contract.EventTypes;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * SSE 事件结构校验。
 *
 * <p>逐类覆盖信封绑定、必填字段、枚举、长度与引用来源一致性；同时验证未知字段按 ADR-0014 容忍。
 */
class ChatEventValidatorTest {

    /** JSON 工厂。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 校验器。 */
    private static final ChatEventValidator VALIDATOR = new ChatEventValidator();

    /** 当前租户。 */
    private static final TenantId TENANT = TenantId.of("tenant-a");

    /** 当前运行。 */
    private static final UUID RUN_ID = UUID.fromString("01930000-0000-7000-8000-000000000002");

    /** 当前追踪 ID。 */
    private static final String TRACE_ID = "trace-123";

    /** Worker 生成的 messageId。 */
    private static final UUID WORKER_MESSAGE_ID = UUID.fromString("01930000-0000-7000-8000-000000000003");

    /** started 事件通过校验。 */
    @Test
    void acceptsStarted() {
        ObjectNode event = envelope(EventTypes.CHAT_STARTED);
        event.putObject("data").put("modelId", UUID.randomUUID().toString()).put("modelKey", "fake-model");

        ChatEventValidator.ValidatedEvent validated = validate(event);

        assertThat(validated.eventType()).isEqualTo(EventTypes.CHAT_STARTED);
        assertThat(validated.modelId()).isNotNull();
        assertThat(validated.modelKey()).isEqualTo("fake-model");
    }

    /** delta 事件通过校验。 */
    @Test
    void acceptsDelta() {
        ObjectNode event = envelope(EventTypes.CHAT_DELTA);
        event.putObject("data").put("delta", "你好");

        assertThat(validate(event).delta()).isEqualTo("你好");
    }

    /** citation 事件通过校验。 */
    @Test
    void acceptsCitation() {
        ObjectNode event = envelope(EventTypes.CHAT_CITATION);
        ObjectNode data = event.putObject("data");
        ArrayNode citations = data.putArray("citations");
        ObjectNode citation = citations.addObject();
        citation.put("ordinal", 1);
        citation.put("sourceType", "KNOWLEDGE");
        citation.put("knowledgeDocumentId", UUID.randomUUID().toString());
        citation.put("chunkId", UUID.randomUUID().toString());
        citation.put("locatorType", "PAGE");
        citation.put("pageNo", 2);
        citation.put("title", "制度手册");
        citation.put("snippet", "片段");
        citation.put("score", 0.9);

        ChatEventValidator.ValidatedEvent validated = validate(event);
        assertThat(validated.citations()).hasSize(1);
        assertThat(validated.citations().get(0).sourceType()).isEqualTo(CitationSourceType.KNOWLEDGE);
        assertThat(validated.citations().get(0).locatorType()).isEqualTo(LocatorType.PAGE);
        assertThat(validated.citations().get(0).score()).isEqualByComparingTo("0.9");
    }

    /** completed 事件通过校验。 */
    @Test
    void acceptsCompleted() {
        ObjectNode event = envelope(EventTypes.CHAT_COMPLETED);
        event.putObject("data")
                .put("finishReason", "stop")
                .put("promptTokens", 10)
                .put("completionTokens", 20)
                .put("firstTokenMs", 5);

        ChatEventValidator.ValidatedEvent validated = validate(event);
        assertThat(validated.finishReason()).isEqualTo("stop");
        assertThat(validated.promptTokens()).isEqualTo(10);
        assertThat(validated.completionTokens()).isEqualTo(20);
        assertThat(validated.firstTokenMs()).isEqualTo(5);
    }

    /** failed 事件通过校验。 */
    @Test
    void acceptsFailed() {
        ObjectNode event = envelope(EventTypes.CHAT_FAILED);
        event.putObject("data")
                .put("code", "RICCON-MODEL-500")
                .put("message", "模型失败")
                .put("retryable", true);

        ChatEventValidator.ValidatedEvent validated = validate(event);
        assertThat(validated.failedCode()).isEqualTo("RICCON-MODEL-500");
        assertThat(validated.retryable()).isTrue();
    }

    /** stopped 事件通过校验。 */
    @Test
    void acceptsStopped() {
        ObjectNode event = envelope(EventTypes.CHAT_STOPPED);
        event.putObject("data").put("reason", "USER_CANCELLED");

        assertThat(validate(event).stopReason()).isEqualTo("USER_CANCELLED");
    }

    /** 未知字段按消费方容忍规则忽略。 */
    @Test
    void ignoresUnknownFields() {
        ObjectNode event = envelope(EventTypes.CHAT_DELTA);
        event.put("futureField", "ignored");
        ObjectNode data = event.putObject("data");
        data.put("delta", "x");
        data.put("futureData", 42);

        assertThat(validate(event).delta()).isEqualTo("x");
    }

    /** 租户不匹配拒绝。 */
    @Test
    void rejectsTenantMismatch() {
        ObjectNode event = envelope(EventTypes.CHAT_DELTA);
        event.put("tenantId", "tenant-b");
        event.putObject("data").put("delta", "x");
        expectRejected(event);
    }

    /** 运行不匹配拒绝。 */
    @Test
    void rejectsRunMismatch() {
        ObjectNode event = envelope(EventTypes.CHAT_DELTA);
        event.put("runId", UUID.randomUUID().toString());
        event.putObject("data").put("delta", "x");
        expectRejected(event);
    }

    /** trace 不匹配拒绝。 */
    @Test
    void rejectsTraceMismatch() {
        ObjectNode event = envelope(EventTypes.CHAT_DELTA);
        event.put("traceId", "other-trace");
        event.putObject("data").put("delta", "x");
        expectRejected(event);
    }

    /** 版本必须为 1。 */
    @Test
    void rejectsUnsupportedVersion() {
        ObjectNode event = envelope(EventTypes.CHAT_DELTA);
        event.put("version", 2);
        event.putObject("data").put("delta", "x");
        expectRejected(event);
    }

    /** 未知事件类型拒绝。 */
    @Test
    void rejectsUnknownEventType() {
        ObjectNode event = envelope("riccon.chat.unknown.v1");
        event.putObject("data");
        expectRejected(event);
        assertThat(VALIDATOR.isKnownEventType("riccon.chat.unknown.v1")).isFalse();
    }

    /** data 必须是对象。 */
    @Test
    void rejectsNonObjectData() {
        ObjectNode event = envelope(EventTypes.CHAT_DELTA);
        event.put("data", "not-an-object");
        expectRejected(event);
    }

    /** delta 不能为空。 */
    @Test
    void rejectsBlankDelta() {
        ObjectNode event = envelope(EventTypes.CHAT_DELTA);
        event.putObject("data").put("delta", " ");
        expectRejected(event);
    }

    /** 引用列表不能为空。 */
    @Test
    void rejectsEmptyCitations() {
        ObjectNode event = envelope(EventTypes.CHAT_CITATION);
        event.putObject("data").putArray("citations");
        expectRejected(event);
    }

    /** FILE 引用必须只带 fileId。 */
    @Test
    void rejectsCitationSourceMismatch() {
        ObjectNode event = citationEnvelope();
        ObjectNode citation = (ObjectNode) event.path("data").path("citations").get(0);
        citation.put("fileId", UUID.randomUUID().toString());
        expectRejected(event);
    }

    /** 引用必须带定位类型。 */
    @Test
    void rejectsMissingLocator() {
        ObjectNode event = citationEnvelope();
        ((ObjectNode) event.path("data").path("citations").get(0)).remove("locatorType");
        expectRejected(event);
    }

    /** 引用分数必须在 0–1。 */
    @Test
    void rejectsScoreOutOfRange() {
        ObjectNode event = citationEnvelope();
        ((ObjectNode) event.path("data").path("citations").get(0)).put("score", 1.5);
        expectRejected(event);
    }

    /** 失败错误码必须符合 RICCON 格式。 */
    @Test
    void rejectsInvalidFailedCode() {
        ObjectNode event = envelope(EventTypes.CHAT_FAILED);
        event.putObject("data").put("code", "BAD").put("message", "x").put("retryable", false);
        expectRejected(event);
    }

    /** 完成原因必须是 stop/length/error。 */
    @Test
    void rejectsInvalidFinishReason() {
        ObjectNode event = envelope(EventTypes.CHAT_COMPLETED);
        event.putObject("data").put("finishReason", "unknown");
        expectRejected(event);
    }

    /** 停止原因必须是契约枚举。 */
    @Test
    void rejectsInvalidStopReason() {
        ObjectNode event = envelope(EventTypes.CHAT_STOPPED);
        event.putObject("data").put("reason", "UNKNOWN");
        expectRejected(event);
    }

    /** 发生时间必须是 ISO-8601。 */
    @Test
    void rejectsInvalidOccurredAt() {
        ObjectNode event = envelope(EventTypes.CHAT_DELTA);
        event.put("occurredAt", "yesterday");
        event.putObject("data").put("delta", "x");
        expectRejected(event);
    }

    /** 标识必须是 UUID。 */
    @Test
    void rejectsInvalidUuid() {
        ObjectNode event = envelope(EventTypes.CHAT_DELTA);
        event.put("eventId", "not-a-uuid");
        event.putObject("data").put("delta", "x");
        expectRejected(event);
    }

    /** token 计数必须是非负整数。 */
    @Test
    void rejectsInvalidTokenCounts() {
        ObjectNode event = envelope(EventTypes.CHAT_COMPLETED);
        event.putObject("data").put("finishReason", "stop").put("promptTokens", -1);
        expectRejected(event);
    }

    /**
     * 断言事件被拒绝。
     *
     * @param event 事件
     */
    private static void expectRejected(ObjectNode event) {
        assertThatThrownBy(() -> validate(event)).isInstanceOf(ChatEventValidationException.class);
    }

    /**
     * 构造标准信封。
     *
     * @param eventType 事件类型
     * @return 事件
     */
    private static ObjectNode envelope(String eventType) {
        ObjectNode event = MAPPER.createObjectNode();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("eventType", eventType);
        event.put("version", 1);
        event.put("occurredAt", Instant.parse("2026-11-17T10:00:00Z").toString());
        event.put("tenantId", TENANT.value());
        event.put("runId", RUN_ID.toString());
        event.put("messageId", WORKER_MESSAGE_ID.toString());
        event.put("traceId", TRACE_ID);
        event.putObject("data");
        return event;
    }

    /**
     * 构造合法 citation 事件。
     *
     * @return 事件
     */
    private static ObjectNode citationEnvelope() {
        ObjectNode event = envelope(EventTypes.CHAT_CITATION);
        ObjectNode data = event.putObject("data");
        ObjectNode citation = data.putArray("citations").addObject();
        citation.put("ordinal", 1);
        citation.put("sourceType", "KNOWLEDGE");
        citation.put("knowledgeDocumentId", UUID.randomUUID().toString());
        citation.put("locatorType", "PAGE");
        citation.put("pageNo", 1);
        citation.put("title", "制度");
        citation.put("snippet", "片段");
        return event;
    }

    /**
     * 执行校验。
     *
     * @param event 事件
     * @return 快照
     */
    private static ChatEventValidator.ValidatedEvent validate(ObjectNode event) {
        return VALIDATOR.validate(event, TENANT, RUN_ID, TRACE_ID);
    }
}
