package com.riccon.api.research.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 运行阶段白名单摘要，对应 OpenAPI {@code RunStageDetails}。
 *
 * <p>显式映射契约列出的键，并额外提供 {@code sources}（检索来源）与未知键透传：契约对
 * {@code RunStageDetails} 声明 {@code additionalProperties: true}，Worker 写入的 {@code details.sources}
 * 正是通过该通道到达前端。未知键原样保留而不是丢弃，避免再次出现“Worker 已写来源、API 却看不到”的回归；
 * 写入侧仍由 Worker 负责脱敏，API 只读不写。
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
 * @param sources 检索来源条目
 * @param extra 未被显式映射的 details 键；通过 {@link JsonAnyGetter} 平铺到响应
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
        Integer generatedChars,
        List<CitationResponse> sources,
        @JsonIgnore Map<String, Object> extra) {

    /** 已显式映射的键；构建 {@code extra} 时排除，避免同一键重复输出。 */
    private static final Set<String> KNOWN_KEYS = Set.of(
            "query",
            "querySha256",
            "hitCount",
            "invocationId",
            "toolId",
            "approvalId",
            "paramsPreview",
            "resultSummary",
            "generatedSha256",
            "generatedChars",
            "sources");

    /**
     * 将未知键平铺到响应对象。
     *
     * @return 未知键映射；没有时为空映射
     */
    @JsonAnyGetter
    public Map<String, Object> unknownDetails() {
        return extra == null ? Map.of() : extra;
    }

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
            Map<String, Object> copy = new LinkedHashMap<>();
            rawPreview.forEach((key, value) -> {
                if (key instanceof String text) {
                    copy.put(text, value);
                }
            });
            preview = PreviewMaps.limit(copy);
        }
        List<CitationResponse> sources = sources(details.get("sources"));
        Map<String, Object> extra = new LinkedHashMap<>();
        details.forEach((key, value) -> {
            if (key != null && !KNOWN_KEYS.contains(key)) {
                extra.put(key, value);
            }
        });
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
                asInteger(details.get("generatedChars")),
                sources.isEmpty() ? null : List.copyOf(sources),
                extra.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(extra)));
    }

    /**
     * 复制阶段详情并替换来源清单。
     *
     * <p>引用可用性按当前账号重新判定后，阶段详情里的 {@code sources} 也必须同步遮蔽，避免时间线泄露标题、
     * 片段或定位（ADR-0023 第 5.4 节）。
     *
     * @param maskedSources 已计算可用性的来源；为空时保持为空
     * @return 新的阶段详情
     */
    public RunStageDetails withSources(List<CitationResponse> maskedSources) {
        List<CitationResponse> resolved =
                maskedSources == null || maskedSources.isEmpty() ? null : List.copyOf(maskedSources);
        return new RunStageDetails(
                query,
                querySha256,
                hitCount,
                invocationId,
                toolId,
                approvalId,
                paramsPreview,
                resultSummary,
                generatedSha256,
                generatedChars,
                resolved,
                extra);
    }

    /**
     * 解析检索来源数组。
     *
     * @param raw 原始 sources 值
     * @return 可展示的来源列表；无效条目被跳过
     */
    private static List<CitationResponse> sources(Object raw) {
        if (!(raw instanceof List<?> items)) {
            return List.of();
        }
        List<CitationResponse> sources = new ArrayList<>();
        int ordinal = 1;
        for (Object item : items) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> source = new LinkedHashMap<>();
                map.forEach((key, value) -> {
                    if (key instanceof String text) {
                        source.put(text, value);
                    }
                });
                CitationResponse citation = CitationResponse.fromStageSource(source, ordinal);
                if (citation != null) {
                    sources.add(citation);
                }
            }
            ordinal++;
        }
        return sources;
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
