package com.riccon.api.chat.service;

import com.riccon.platform.common.tenant.TenantId;
import com.riccon.platform.domain.chat.CitationSourceType;
import com.riccon.platform.domain.chat.LocatorType;
import com.riccon.platform.domain.contract.EventTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Worker SSE 事件结构校验。
 *
 * <p>在转发给浏览器与落库前校验 {@code contracts/events/sse/chat-event.v1.schema.json} 的必填字段、枚举、
 * 租户/运行/trace 绑定与长度上限；未知字段按 ADR-0014 的消费方容忍规则忽略，未知事件类型由调用方跳过。
 * 该实现只覆盖契约当前版本，新增事件类型必须同步扩展并补单元测试。
 */
@Component
public final class ChatEventValidator {

    /** 已实现的对话事件类型。 */
    private static final Set<String> KNOWN_EVENT_TYPES = Set.of(
            EventTypes.CHAT_STARTED,
            EventTypes.CHAT_DELTA,
            EventTypes.CHAT_CITATION,
            EventTypes.CHAT_COMPLETED,
            EventTypes.CHAT_FAILED,
            EventTypes.CHAT_STOPPED);

    /** 完成原因。 */
    private static final Set<String> FINISH_REASONS = Set.of("stop", "length", "error");

    /** 停止原因。 */
    private static final Set<String> STOP_REASONS = Set.of("USER_CANCELLED", "CLIENT_DISCONNECT", "PERMISSION_REVOKED");

    /** 失败错误码。 */
    private static final Pattern FAILED_CODE = Pattern.compile("^RICCON-[A-Z]+-[0-9]{3}$");

    /** 引用标题上限，与 message_citation.title_snapshot 列一致。 */
    private static final int MAX_CITATION_TITLE_LENGTH = 300;

    /** 引用片段上限，与 schema 和列定义一致。 */
    private static final int MAX_CITATION_SNIPPET_LENGTH = 1000;

    /**
     * 判断事件类型是否由 API 实现。
     *
     * @param eventType 事件类型
     * @return 已知类型返回 {@code true}
     */
    public boolean isKnownEventType(String eventType) {
        return eventType != null && KNOWN_EVENT_TYPES.contains(eventType);
    }

    /**
     * 校验事件信封与 {@code data}。
     *
     * @param event 原始事件 JSON
     * @param tenantId 当前租户
     * @param runId 当前运行
     * @param traceId 当前追踪 ID
     * @return 通过校验的强类型快照
     * @throws ChatEventValidationException 缺字段、类型错误、枚举越界或租户/运行/trace 不匹配
     */
    public ValidatedEvent validate(ObjectNode event, TenantId tenantId, UUID runId, String traceId) {
        UUID eventId = requireUuid(event.get("eventId"), "eventId");
        String eventType = requireText(event.get("eventType"), "eventType");
        if (!KNOWN_EVENT_TYPES.contains(eventType)) {
            throw new ChatEventValidationException("unknown chat event type");
        }
        int version = requireInt(event.get("version"), "version");
        if (version != 1) {
            throw new ChatEventValidationException("chat event version must be 1");
        }
        Instant occurredAt = parseInstant(requireText(event.get("occurredAt"), "occurredAt"));
        String eventTenant = requireText(event.get("tenantId"), "tenantId");
        if (!tenantId.value().equals(eventTenant)) {
            throw new ChatEventValidationException("chat event tenant does not match current tenant");
        }
        UUID eventRun = requireUuid(event.get("runId"), "runId");
        if (!runId.equals(eventRun)) {
            throw new ChatEventValidationException("chat event run does not match current run");
        }
        UUID messageId = requireUuid(event.get("messageId"), "messageId");
        String eventTrace = requireText(event.get("traceId"), "traceId");
        if (!traceId.equals(eventTrace)) {
            throw new ChatEventValidationException("chat event trace does not match current trace");
        }
        JsonNode data = event.get("data");
        if (data == null || !data.isObject()) {
            throw new ChatEventValidationException("chat event data must be an object");
        }
        return switch (eventType) {
            case EventTypes.CHAT_STARTED -> started(eventId, eventType, messageId, data, occurredAt);
            case EventTypes.CHAT_DELTA -> delta(eventId, eventType, messageId, data, occurredAt);
            case EventTypes.CHAT_CITATION -> citations(eventId, eventType, messageId, data, occurredAt);
            case EventTypes.CHAT_COMPLETED -> completed(eventId, eventType, messageId, data, occurredAt);
            case EventTypes.CHAT_FAILED -> failed(eventId, eventType, messageId, data, occurredAt);
            case EventTypes.CHAT_STOPPED -> stopped(eventId, eventType, messageId, data, occurredAt);
            default -> throw new ChatEventValidationException("unsupported chat event type");
        };
    }

    /**
     * 校验 started 数据。
     *
     * @param eventId 事件 ID
     * @param eventType 类型
     * @param messageId 消息 ID
     * @param data 数据
     * @param occurredAt 发生时间
     * @return 快照
     */
    private static ValidatedEvent started(
            UUID eventId, String eventType, UUID messageId, JsonNode data, Instant occurredAt) {
        UUID modelId = requireUuid(data.get("modelId"), "data.modelId");
        String modelKey = requireText(data.get("modelKey"), "data.modelKey");
        return new ValidatedEvent(
                eventId,
                eventType,
                messageId,
                occurredAt,
                modelId,
                modelKey,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null);
    }

    /**
     * 校验 delta 数据。
     *
     * @param eventId 事件 ID
     * @param eventType 类型
     * @param messageId 消息 ID
     * @param data 数据
     * @param occurredAt 发生时间
     * @return 快照
     */
    private static ValidatedEvent delta(
            UUID eventId, String eventType, UUID messageId, JsonNode data, Instant occurredAt) {
        String delta = requireText(data.get("delta"), "data.delta");
        return new ValidatedEvent(
                eventId,
                eventType,
                messageId,
                occurredAt,
                null,
                null,
                delta,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null);
    }

    /**
     * 校验 citation 数据。
     *
     * @param eventId 事件 ID
     * @param eventType 类型
     * @param messageId 消息 ID
     * @param data 数据
     * @param occurredAt 发生时间
     * @return 快照
     */
    private static ValidatedEvent citations(
            UUID eventId, String eventType, UUID messageId, JsonNode data, Instant occurredAt) {
        JsonNode array = data.get("citations");
        if (array == null || !array.isArray() || array.isEmpty()) {
            throw new ChatEventValidationException("citation event requires a non-empty citations array");
        }
        List<ParsedCitation> citations = new ArrayList<>(array.size());
        for (JsonNode node : array) {
            if (!node.isObject()) {
                throw new ChatEventValidationException("citation must be an object");
            }
            citations.add(parseCitation(node));
        }
        return new ValidatedEvent(
                eventId,
                eventType,
                messageId,
                occurredAt,
                null,
                null,
                null,
                List.copyOf(citations),
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null);
    }

    /**
     * 校验 completed 数据。
     *
     * @param eventId 事件 ID
     * @param eventType 类型
     * @param messageId 消息 ID
     * @param data 数据
     * @param occurredAt 发生时间
     * @return 快照
     */
    private static ValidatedEvent completed(
            UUID eventId, String eventType, UUID messageId, JsonNode data, Instant occurredAt) {
        String finishReason = requireText(data.get("finishReason"), "data.finishReason");
        if (!FINISH_REASONS.contains(finishReason)) {
            throw new ChatEventValidationException("finishReason is not allowed");
        }
        Integer promptTokens = optionalNonNegativeInt(data.get("promptTokens"), "data.promptTokens");
        Integer completionTokens = optionalNonNegativeInt(data.get("completionTokens"), "data.completionTokens");
        Integer firstTokenMs = optionalNonNegativeInt(data.get("firstTokenMs"), "data.firstTokenMs");
        return new ValidatedEvent(
                eventId,
                eventType,
                messageId,
                occurredAt,
                null,
                null,
                null,
                List.of(),
                finishReason,
                promptTokens,
                completionTokens,
                firstTokenMs,
                null,
                null,
                false,
                null);
    }

    /**
     * 校验 failed 数据。
     *
     * @param eventId 事件 ID
     * @param eventType 类型
     * @param messageId 消息 ID
     * @param data 数据
     * @param occurredAt 发生时间
     * @return 快照
     */
    private static ValidatedEvent failed(
            UUID eventId, String eventType, UUID messageId, JsonNode data, Instant occurredAt) {
        String code = requireText(data.get("code"), "data.code");
        if (!FAILED_CODE.matcher(code).matches()) {
            throw new ChatEventValidationException("failed code has invalid format");
        }
        String message = requireString(data.get("message"), "data.message", 500);
        boolean retryable = requireBoolean(data.get("retryable"), "data.retryable");
        return new ValidatedEvent(
                eventId,
                eventType,
                messageId,
                occurredAt,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                code,
                message,
                retryable,
                null);
    }

    /**
     * 校验 stopped 数据。
     *
     * @param eventId 事件 ID
     * @param eventType 类型
     * @param messageId 消息 ID
     * @param data 数据
     * @param occurredAt 发生时间
     * @return 快照
     */
    private static ValidatedEvent stopped(
            UUID eventId, String eventType, UUID messageId, JsonNode data, Instant occurredAt) {
        String reason = requireText(data.get("reason"), "data.reason");
        if (!STOP_REASONS.contains(reason)) {
            throw new ChatEventValidationException("stop reason is not allowed");
        }
        return new ValidatedEvent(
                eventId,
                eventType,
                messageId,
                occurredAt,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                reason);
    }

    /**
     * 解析并校验单条引用。
     *
     * @param node 引用 JSON
     * @return 引用快照
     */
    private static ParsedCitation parseCitation(JsonNode node) {
        int ordinal = requireInt(node.get("ordinal"), "citation.ordinal");
        if (ordinal < 1) {
            throw new ChatEventValidationException("citation ordinal must be >= 1");
        }
        CitationSourceType sourceType =
                requireEnum(node.get("sourceType"), "citation.sourceType", CitationSourceType.class);
        LocatorType locatorType = requireEnum(node.get("locatorType"), "citation.locatorType", LocatorType.class);
        UUID fileId = optionalUuid(node.get("fileId"), "citation.fileId");
        UUID knowledgeDocumentId = optionalUuid(node.get("knowledgeDocumentId"), "citation.knowledgeDocumentId");
        UUID chunkId = optionalUuid(node.get("chunkId"), "citation.chunkId");
        if (sourceType == CitationSourceType.FILE) {
            if (fileId == null || knowledgeDocumentId != null) {
                throw new ChatEventValidationException("FILE citation must carry only fileId");
            }
        } else if (knowledgeDocumentId == null || fileId != null) {
            throw new ChatEventValidationException("KNOWLEDGE citation must carry only knowledgeDocumentId");
        }
        Integer pageNo = optionalPositiveInt(node.get("pageNo"), "citation.pageNo");
        Integer paragraphNo = optionalPositiveInt(node.get("paragraphNo"), "citation.paragraphNo");
        String sheetName = optionalString(node.get("sheetName"), "citation.sheetName");
        String cellRange = optionalString(node.get("cellRange"), "citation.cellRange");
        String title = requireString(node.get("title"), "citation.title", MAX_CITATION_TITLE_LENGTH);
        String snippet = requireString(node.get("snippet"), "citation.snippet", MAX_CITATION_SNIPPET_LENGTH);
        BigDecimal score = optionalScore(node.get("score"));
        return new ParsedCitation(
                ordinal,
                sourceType,
                fileId,
                knowledgeDocumentId,
                chunkId,
                locatorType,
                pageNo,
                sheetName,
                cellRange,
                paragraphNo,
                title,
                snippet,
                score);
    }

    /**
     * 读取必填 UUID。
     *
     * @param node 节点
     * @param field 字段名
     * @return UUID
     */
    private static UUID requireUuid(JsonNode node, String field) {
        String value = requireText(node, field);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new ChatEventValidationException(field + " is not a valid UUID");
        }
    }

    /**
     * 读取可选 UUID。
     *
     * @param node 节点
     * @param field 字段名
     * @return UUID 或 {@code null}
     */
    private static UUID optionalUuid(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        return requireUuid(node, field);
    }

    /**
     * 读取必填文本。
     *
     * @param node 节点
     * @param field 字段名
     * @return 非空文本
     */
    private static String requireText(JsonNode node, String field) {
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new ChatEventValidationException(field + " must be a non-blank string");
        }
        return node.asText();
    }

    /**
     * 读取必填字符串并限制长度。
     *
     * @param node 节点
     * @param field 字段名
     * @param maxLength 最大长度
     * @return 字符串
     */
    private static String requireString(JsonNode node, String field, int maxLength) {
        if (node == null || !node.isTextual()) {
            throw new ChatEventValidationException(field + " must be a string");
        }
        String value = node.asText();
        if (value.length() > maxLength) {
            throw new ChatEventValidationException(field + " exceeds " + maxLength + " characters");
        }
        return value;
    }

    /**
     * 读取可选字符串。
     *
     * @param node 节点
     * @param field 字段名
     * @return 字符串或 {@code null}
     */
    private static String optionalString(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new ChatEventValidationException(field + " must be a string");
        }
        return node.asText();
    }

    /**
     * 读取必填整数。
     *
     * @param node 节点
     * @param field 字段名
     * @return 整数
     */
    private static int requireInt(JsonNode node, String field) {
        if (node == null || !node.isIntegralNumber()) {
            throw new ChatEventValidationException(field + " must be an integer");
        }
        return node.asInt();
    }

    /**
     * 读取可选非负整数。
     *
     * @param node 节点
     * @param field 字段名
     * @return 整数或 {@code null}
     */
    private static Integer optionalNonNegativeInt(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber()) {
            throw new ChatEventValidationException(field + " must be an integer");
        }
        int value = node.asInt();
        if (value < 0) {
            throw new ChatEventValidationException(field + " must be >= 0");
        }
        return value;
    }

    /**
     * 读取可选正整数。
     *
     * @param node 节点
     * @param field 字段名
     * @return 整数或 {@code null}
     */
    private static Integer optionalPositiveInt(JsonNode node, String field) {
        Integer value = optionalNonNegativeInt(node, field);
        if (value != null && value < 1) {
            throw new ChatEventValidationException(field + " must be >= 1");
        }
        return value;
    }

    /**
     * 读取必填布尔。
     *
     * @param node 节点
     * @param field 字段名
     * @return 布尔值
     */
    private static boolean requireBoolean(JsonNode node, String field) {
        if (node == null || !node.isBoolean()) {
            throw new ChatEventValidationException(field + " must be a boolean");
        }
        return node.asBoolean();
    }

    /**
     * 读取枚举。
     *
     * @param node 节点
     * @param field 字段名
     * @param type 枚举类型
     * @param <T> 枚举类型
     * @return 枚举值
     */
    private static <T extends Enum<T>> T requireEnum(JsonNode node, String field, Class<T> type) {
        String value = requireText(node, field);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new ChatEventValidationException(field + " is not an allowed value");
        }
    }

    /**
     * 读取可选相关度分数。
     *
     * @param node 节点
     * @return 0–1 的分数；缺失时为 {@code null}
     */
    private static BigDecimal optionalScore(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            throw new ChatEventValidationException("citation.score must be a number");
        }
        BigDecimal score = new BigDecimal(node.asText());
        if (score.signum() < 0 || score.compareTo(BigDecimal.ONE) > 0) {
            throw new ChatEventValidationException("citation.score must be between 0 and 1");
        }
        return score;
    }

    /**
     * 解析 ISO-8601 时间。
     *
     * @param value 文本
     * @return 时间
     */
    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new ChatEventValidationException("occurredAt is not a valid instant");
        }
    }

    /**
     * 校验通过的引用快照。
     *
     * @param ordinal 序号
     * @param sourceType 来源类型
     * @param fileId 文件 ID
     * @param knowledgeDocumentId 知识库文档 ID
     * @param chunkId 分块 ID
     * @param locatorType 定位类型
     * @param pageNo 页码
     * @param sheetName 工作表名
     * @param cellRange 单元格范围
     * @param paragraphNo 段落号
     * @param title 标题
     * @param snippet 片段
     * @param score 分数
     */
    public record ParsedCitation(
            int ordinal,
            CitationSourceType sourceType,
            UUID fileId,
            UUID knowledgeDocumentId,
            UUID chunkId,
            LocatorType locatorType,
            Integer pageNo,
            String sheetName,
            String cellRange,
            Integer paragraphNo,
            String title,
            String snippet,
            BigDecimal score) {}

    /**
     * 校验通过的事件快照；字段按事件类型填充，未使用字段为 {@code null}。
     *
     * @param eventId 事件 ID
     * @param eventType 事件类型
     * @param messageId Worker 生成的 assistant 消息 ID
     * @param occurredAt 发生时间
     * @param modelId 模型 ID（started）
     * @param modelKey 模型 key（started）
     * @param delta 文本增量（delta）
     * @param citations 引用（citation）
     * @param finishReason 完成原因（completed）
     * @param promptTokens 输入 token（completed）
     * @param completionTokens 输出 token（completed）
     * @param firstTokenMs 首字延迟（completed）
     * @param failedCode 失败错误码（failed）
     * @param failedMessage 失败说明（failed）
     * @param retryable 是否可重试（failed）
     * @param stopReason 停止原因（stopped）
     */
    public record ValidatedEvent(
            UUID eventId,
            String eventType,
            UUID messageId,
            Instant occurredAt,
            UUID modelId,
            String modelKey,
            String delta,
            List<ParsedCitation> citations,
            String finishReason,
            Integer promptTokens,
            Integer completionTokens,
            Integer firstTokenMs,
            String failedCode,
            String failedMessage,
            boolean retryable,
            String stopReason) {

        /** 复制引用列表。 */
        public ValidatedEvent {
            citations = citations == null ? List.of() : List.copyOf(citations);
        }
    }
}
