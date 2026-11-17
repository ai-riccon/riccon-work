package com.riccon.api.research.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import java.util.UUID;

/**
 * 运行阶段白名单摘要，对应 OpenAPI {@code RunStageDetails}。
 *
 * <p>只映射契约列出的键；未知键被丢弃，避免把未来写入的字段意外暴露给前端。所有属性可空，
 * 序列化时省略空值以符合 schema 中各字段的非空类型。
 *
 * @param query 查询摘要
 * @param querySha256 查询 SHA-256
 * @param hitCount 检索命中数
 * @param invocationId MCP 调用 ID
 * @param toolId 工具 ID
 * @param approvalId 审批 ID
 * @param paramsPreview 审批入参脱敏预览
 * @param resultSummary 工具结果脱敏摘要
 * @param generatedSha256 生成内容 SHA-256
 * @param generatedChars 生成字符数
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunStageDetails(
        String query,
        String querySha256,
        Integer hitCount,
        UUID invocationId,
        UUID toolId,
        UUID approvalId,
        Map<String, Object> paramsPreview,
        String resultSummary,
        String generatedSha256,
        Integer generatedChars) {

    /**
     * 从数据库 JSON 白名单摘要构造响应对象。
     *
     * @param details 原始 details；为空时返回 {@code null}
     * @return 白名单摘要或 {@code null}
     */
    public static RunStageDetails from(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        Map<String, Object> preview = null;
        if (details.get("paramsPreview") instanceof Map<?, ?> rawPreview) {
            // 只保留字符串键，防止 JSON 结构异常导致响应序列化失败；值按 Worker 已脱敏内容原样透传。
            Map<String, Object> copy = new java.util.LinkedHashMap<>();
            rawPreview.forEach((key, value) -> {
                if (key instanceof String text) {
                    copy.put(text, value);
                }
            });
            preview = PreviewMaps.limit(copy);
        }
        return new RunStageDetails(
                asString(details.get("query")),
                asString(details.get("querySha256")),
                asInteger(details.get("hitCount")),
                asUuid(details.get("invocationId")),
                asUuid(details.get("toolId")),
                asUuid(details.get("approvalId")),
                preview,
                asString(details.get("resultSummary")),
                asString(details.get("generatedSha256")),
                asInteger(details.get("generatedChars")));
    }

    /**
     * 安全读取字符串。
     *
     * @param value 原始值
     * @return 字符串或 {@code null}
     */
    private static String asString(Object value) {
        return value instanceof String text ? text : null;
    }

    /**
     * 安全读取整数。
     *
     * @param value 原始值
     * @return 整数或 {@code null}
     */
    private static Integer asInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    /**
     * 安全读取 UUID；非法值按缺失处理，避免坏摘要让整条时间线失败。
     *
     * @param value 原始值
     * @return UUID 或 {@code null}
     */
    private static UUID asUuid(Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
