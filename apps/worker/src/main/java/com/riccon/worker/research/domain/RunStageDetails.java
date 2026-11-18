package com.riccon.worker.research.domain;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 运行阶段白名单摘要（{@code contracts/openapi.yaml#RunStageDetails}）。
 *
 * <p>只包含 ID、计数、哈希、短文本与脱敏预览；写入前由调用方裁剪。未知键在解析时忽略，
 * 保证 Worker 与后续契约新增字段兼容。完整工具参数不得进入本结构（ADR-0017）。
 *
 * @param query 查询摘要，可为空
 * @param querySha256 查询 SHA-256，可为空
 * @param hitCount 命中数，可为空
 * @param invocationId MCP 调用 ID，可为空
 * @param toolId 工具 ID，可为空
 * @param approvalId 审批 ID，可为空
 * @param paramsPreview 脱敏预览对象，可为空
 * @param resultSummary 结果摘要，可为空
 * @param generatedSha256 生成内容 SHA-256，可为空
 * @param generatedChars 生成字符数，可为空
 * @param approvalDecision 审批决定（APPROVE/REJECT），可为空
 * @param approvalDecidedBy 审批人账号 ID，可为空
 * @param approvalDecidedAt 审批时间（ISO 8601 带偏移），可为空
 */
public record RunStageDetails(
        String query,
        String querySha256,
        Integer hitCount,
        UUID invocationId,
        UUID toolId,
        UUID approvalId,
        JsonNode paramsPreview,
        String resultSummary,
        String generatedSha256,
        Integer generatedChars,
        String approvalDecision,
        UUID approvalDecidedBy,
        String approvalDecidedAt) {

    /** 结果摘要最大长度。 */
    public static final int MAX_RESULT_SUMMARY_LENGTH = 500;

    /** 检索来源片段最大长度；{@code details.sources[].snippet} 的写入硬上限。 */
    public static final int MAX_SOURCE_SNIPPET_LENGTH = 200;

    /**
     * 构造检索来源片段。
     *
     * <p>分块原文不可信：先去除控制字符，把连续空白（含换行、制表符）压成单空格，再截断到
     * {@link #MAX_SOURCE_SNIPPET_LENGTH} 字符。只保留可核对的短摘要，禁止把分块全文写入时间线，
     * 也不生成 HTML。
     *
     * @param content 分块原文
     * @return 单行片段；null 或空白返回空字符串
     */
    public static String sourceSnippet(String content) {
        if (content == null) {
            return "";
        }
        String normalized = content.replaceAll("[\\p{Cntrl}&&[^\\t\\n\\r]]", "")
                .replaceAll("\\s+", " ")
                .strip();
        if (normalized.length() <= MAX_SOURCE_SNIPPET_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_SOURCE_SNIPPET_LENGTH - 3) + "...";
    }

    /**
     * 把审批结论合并进阶段详情 JSON，保留原有键（含 {@code paramsPreview} 与未知扩展键）。
     *
     * <p>审批人与时间来自审批记录而不是当前时钟，因此重复投递合并出的结果不变，不会覆盖首次写入。
     * 历史脏详情无法解析时退回新建对象，避免取消/拒绝收尾因时间线数据损坏而失败。
     *
     * @param objectMapper JSON 序列化
     * @param existingJson 既有阶段详情；可为空
     * @param approval 审批记录
     * @return 合并后的 JSON
     * @throws JacksonException 序列化失败
     */
    public static String withApprovalDecision(ObjectMapper objectMapper, String existingJson, ApprovalRecord approval)
            throws JacksonException {
        ObjectNode node = objectMapper.createObjectNode();
        if (existingJson != null && !existingJson.isBlank()) {
            try {
                JsonNode parsed = objectMapper.readTree(existingJson);
                if (parsed instanceof ObjectNode existingNode) {
                    node = existingNode;
                }
            } catch (JacksonException ignored) {
                // 历史脏详情不应阻塞取消/拒绝收尾；丢弃损坏内容并写入新的审批摘要。
            }
        }
        node.put("approvalId", approval.id().toString());
        if (approval.mcpToolId() != null) {
            node.put("toolId", approval.mcpToolId().toString());
        }
        node.put("approvalDecision", decision(approval));
        if (approval.decidedBy() != null) {
            node.put("approvalDecidedBy", approval.decidedBy().toString());
        }
        if (approval.decidedAt() != null) {
            node.put("approvalDecidedAt", decidedAt(approval));
        }
        return objectMapper.writeValueAsString(node);
    }

    /**
     * 审批决定名。
     *
     * @param approval 审批记录
     * @return APPROVE/REJECT；其他终态返回状态名
     */
    public static String decision(ApprovalRecord approval) {
        return switch (approval.status()) {
            case APPROVED -> "APPROVE";
            case REJECTED -> "REJECT";
            default -> approval.status().name();
        };
    }

    /**
     * 审批时间格式化为带偏移的 ISO 8601（UTC）。
     *
     * @param approval 审批记录
     * @return 时间字符串；未决定时为 null
     */
    public static String decidedAt(ApprovalRecord approval) {
        if (approval.decidedAt() == null) {
            return null;
        }
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                approval.decidedAt().atOffset(ZoneOffset.UTC));
    }

    /**
     * 序列化为阶段详情 JSON；只输出非空已知键。
     *
     * @param objectMapper JSON 序列化
     * @return JSON 字符串
     * @throws JacksonException 序列化失败
     */
    public String toJson(ObjectMapper objectMapper) throws JacksonException {
        ObjectNode node = objectMapper.createObjectNode();
        if (query != null) {
            node.put("query", query);
        }
        if (querySha256 != null) {
            node.put("querySha256", querySha256);
        }
        if (hitCount != null) {
            node.put("hitCount", hitCount);
        }
        if (invocationId != null) {
            node.put("invocationId", invocationId.toString());
        }
        if (toolId != null) {
            node.put("toolId", toolId.toString());
        }
        if (approvalId != null) {
            node.put("approvalId", approvalId.toString());
        }
        if (paramsPreview != null) {
            node.set("paramsPreview", paramsPreview);
        }
        if (resultSummary != null) {
            node.put("resultSummary", truncate(resultSummary, MAX_RESULT_SUMMARY_LENGTH));
        }
        if (generatedSha256 != null) {
            node.put("generatedSha256", generatedSha256);
        }
        if (generatedChars != null) {
            node.put("generatedChars", generatedChars);
        }
        if (approvalDecision != null) {
            node.put("approvalDecision", approvalDecision);
        }
        if (approvalDecidedBy != null) {
            node.put("approvalDecidedBy", approvalDecidedBy.toString());
        }
        if (approvalDecidedAt != null) {
            node.put("approvalDecidedAt", approvalDecidedAt);
        }
        return objectMapper.writeValueAsString(node);
    }

    /**
     * 从 JSON 解析；未知键被忽略，损坏字段返回 null 而不是让整个阶段失败。
     *
     * @param objectMapper JSON 解析
     * @param json 详情 JSON
     * @return 阶段详情；空值或非对象返回 null
     * @throws JacksonException JSON 非法
     */
    public static RunStageDetails fromJson(ObjectMapper objectMapper, String json) throws JacksonException {
        if (json == null || json.isBlank()) {
            return null;
        }
        JsonNode node = objectMapper.readTree(json);
        if (node == null || !node.isObject()) {
            return null;
        }
        return new RunStageDetails(
                text(node, "query"),
                text(node, "querySha256"),
                integer(node, "hitCount"),
                uuid(node, "invocationId"),
                uuid(node, "toolId"),
                uuid(node, "approvalId"),
                node.hasNonNull("paramsPreview") ? node.get("paramsPreview") : null,
                text(node, "resultSummary"),
                text(node, "generatedSha256"),
                integer(node, "generatedChars"),
                text(node, "approvalDecision"),
                uuid(node, "approvalDecidedBy"),
                text(node, "approvalDecidedAt"));
    }

    /**
     * 提取文本字段。
     *
     * @param node JSON 对象
     * @param field 字段名
     * @return 文本；缺失或非文本时为 null
     */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    /**
     * 提取整数字段。
     *
     * @param node JSON 对象
     * @param field 字段名
     * @return 整数；缺失或非数字时为 null
     */
    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asInt() : null;
    }

    /**
     * 提取 UUID 字段；非法值按缺失处理，避免历史脏数据阻塞时间线。
     *
     * @param node JSON 对象
     * @param field 字段名
     * @return UUID；缺失或非法时为 null
     */
    private static UUID uuid(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            return null;
        }
        try {
            return UUID.fromString(value.asText());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * 截断摘要。
     *
     * @param value 原值
     * @param maxLength 最大长度
     * @return 截断值
     */
    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
