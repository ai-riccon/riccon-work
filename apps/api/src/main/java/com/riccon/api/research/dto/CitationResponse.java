package com.riccon.api.research.dto;

import com.riccon.api.chat.domain.CitationBoundingBox;
import com.riccon.api.chat.domain.CitationRegion;
import com.riccon.api.chat.domain.CitationSourceStatus;
import com.riccon.api.research.domain.ArtifactCitationSnapshot;
import com.riccon.api.research.domain.ArtifactSourceType;
import com.riccon.platform.domain.chat.LocatorType;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * 任务级引用响应，对应 OpenAPI {@code Citation}。
 *
 * <p>来源在产物生成前来自检索阶段 {@code agent_run_stage.details.sources}，产物生成后改用
 * {@code artifact_citation} 的权威快照；两种来源映射到同一响应，供 J2 时间线在审批前就能展示标题、片段与定位。
 * {@code mcpInvocationId} 是 M02 既有响应字段（ArtifactCitation 兼容），缺失时不输出具体值。
 * {@code sourceStatus=NO_ACCESS|DELETED} 时标题/片段为空串、定位字段为 {@code null}，遮蔽规则与 J1 一致。
 *
 * @param ordinal 序号，从 1 开始
 * @param sourceType 来源类型
 * @param fileId 文件 ID
 * @param knowledgeDocumentId 知识库文档 ID
 * @param chunkId 知识库分块 ID
 * @param mcpInvocationId MCP 调用 ID
 * @param locatorType 定位类型
 * @param pageNo 页码
 * @param sheetName 工作表名
 * @param cellRange 单元格范围
 * @param paragraphNo 段落号
 * @param bbox OCR 页内区域；不可访问或非 OCR 分块为空
 * @param ocrConfidence OCR 置信度；不可访问或非 OCR 分块为空
 * @param sourceStatus 引用可用性
 * @param title 标题
 * @param snippet 片段摘要；检索阶段没有片段时为空字符串
 * @param score 相关性分数
 */
public record CitationResponse(
        int ordinal,
        ArtifactSourceType sourceType,
        UUID fileId,
        UUID knowledgeDocumentId,
        UUID chunkId,
        UUID mcpInvocationId,
        LocatorType locatorType,
        Integer pageNo,
        String sheetName,
        String cellRange,
        Integer paragraphNo,
        CitationBoundingBox bbox,
        BigDecimal ocrConfidence,
        CitationSourceStatus sourceStatus,
        String title,
        String snippet,
        Double score) {

    /** 契约允许的片段最大长度。 */
    private static final int MAX_SNIPPET_LENGTH = 1000;

    /**
     * 将产物引用快照映射为响应，并按可用性遮蔽。
     *
     * @param citation 引用快照
     * @return 响应
     */
    public static CitationResponse from(ArtifactCitationSnapshot citation) {
        return new CitationResponse(
                        citation.ordinal(),
                        citation.sourceType(),
                        citation.fileId(),
                        citation.knowledgeDocumentId(),
                        citation.chunkId(),
                        citation.mcpInvocationId(),
                        citation.locatorType(),
                        citation.pageNo(),
                        citation.sheetName(),
                        citation.cellRange(),
                        citation.paragraphNo(),
                        citation.bbox(),
                        citation.ocrConfidence(),
                        citation.sourceStatus(),
                        citation.title(),
                        citation.snippet(),
                        null)
                .masked();
    }

    /**
     * 将任务详情中的既有引用响应转换为统一引用响应。
     *
     * <p>产物引用与检索来源字段集合不同：前者含 {@code mcpInvocationId} 与 {@code cellRange}；统一响应保留两者
     * 可用字段，缺失值为 {@code null}。已遮蔽的响应再次转换保持遮蔽。
     *
     * @param citation 既有引用响应
     * @return 统一引用响应
     */
    public static CitationResponse from(ArtifactCitationResponse citation) {
        return new CitationResponse(
                citation.ordinal(),
                citation.sourceType(),
                citation.fileId(),
                citation.knowledgeDocumentId(),
                null,
                citation.mcpInvocationId(),
                citation.locatorType(),
                citation.pageNo(),
                citation.sheetName(),
                citation.cellRange(),
                citation.paragraphNo(),
                citation.bbox(),
                citation.ocrConfidence(),
                citation.sourceStatus(),
                citation.title(),
                citation.snippet(),
                null);
    }

    /**
     * 将检索阶段 details.sources 的单个条目映射为响应。
     *
     * <p>Worker 只写来源 ID、标题与定位，不写原文；缺少标题或来源类型的条目直接跳过，避免展示无法核对的
     * 伪引用。未知键不进入本记录，但阶段详情本身会原样保留（见 {@link RunStageDetails}）。
     *
     * @param source 原始条目
     * @param fallbackOrdinal 条目缺少合法序号时使用的兜底序号
     * @return 引用响应；条目不可用时返回 {@code null}
     */
    public static CitationResponse fromStageSource(Map<String, Object> source, int fallbackOrdinal) {
        if (source == null) {
            return null;
        }
        String title = asString(source.get("title"));
        ArtifactSourceType sourceType = asSourceType(source.get("sourceType"));
        if (title == null || title.isBlank() || sourceType == null) {
            return null;
        }
        Integer ordinal = asInteger(source.get("ordinal"));
        int resolvedOrdinal = ordinal == null || ordinal < 1 ? fallbackOrdinal : ordinal;
        String snippet = asString(source.get("snippet"));
        if (snippet != null && snippet.length() > MAX_SNIPPET_LENGTH) {
            snippet = snippet.substring(0, MAX_SNIPPET_LENGTH);
        }
        return new CitationResponse(
                resolvedOrdinal,
                sourceType,
                asUuid(source.get("fileId")),
                asUuid(source.get("knowledgeDocumentId")),
                asUuid(source.get("chunkId")),
                asUuid(source.get("mcpInvocationId")),
                asLocatorType(source.get("locatorType")),
                asInteger(source.get("pageNo")),
                asString(source.get("sheetName")),
                asString(source.get("cellRange")),
                asInteger(source.get("paragraphNo")),
                asBoundingBox(source.get("bbox")),
                asDecimal(source.get("ocrConfidence")),
                CitationSourceStatus.AVAILABLE,
                title,
                snippet == null ? "" : snippet,
                asDouble(source.get("score")));
    }

    /**
     * 复制引用并替换可用性；{@code NO_ACCESS}/{@code DELETED} 时同时遮蔽正文与定位。
     *
     * @param status 可用性
     * @return 新响应
     */
    public CitationResponse withSourceStatus(CitationSourceStatus status) {
        return new CitationResponse(
                        ordinal,
                        sourceType,
                        fileId,
                        knowledgeDocumentId,
                        chunkId,
                        mcpInvocationId,
                        locatorType,
                        pageNo,
                        sheetName,
                        cellRange,
                        paragraphNo,
                        bbox,
                        ocrConfidence,
                        status,
                        title,
                        snippet,
                        score)
                .masked();
    }

    /**
     * 复制引用并附加分块 OCR 元数据；不可访问时不保留区域。
     *
     * @param region 分块区域；为空时保持空
     * @return 新响应
     */
    public CitationResponse withRegion(CitationRegion region) {
        CitationRegion resolved = region == null ? CitationRegion.EMPTY : region;
        return new CitationResponse(
                        ordinal,
                        sourceType,
                        fileId,
                        knowledgeDocumentId,
                        chunkId,
                        mcpInvocationId,
                        locatorType,
                        pageNo,
                        sheetName,
                        cellRange,
                        paragraphNo,
                        resolved.bbox(),
                        resolved.ocrConfidence(),
                        sourceStatus,
                        title,
                        snippet,
                        score)
                .masked();
    }

    /**
     * 按当前 {@code sourceStatus} 应用遮蔽。
     *
     * @return 遮蔽后的响应；{@code AVAILABLE}/{@code UPDATED} 返回自身
     */
    private CitationResponse masked() {
        if (sourceStatus != CitationSourceStatus.NO_ACCESS && sourceStatus != CitationSourceStatus.DELETED) {
            return this;
        }
        return new CitationResponse(
                ordinal,
                sourceType,
                fileId,
                knowledgeDocumentId,
                chunkId,
                mcpInvocationId,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                sourceStatus,
                "",
                "",
                score);
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
     * 安全读取 OCR 置信度。
     *
     * @param value 原始值
     * @return 小数或 {@code null}
     */
    private static BigDecimal asDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return null;
    }

    /**
     * 安全读取 bbox；字段不完整或非法时返回 {@code null}。
     *
     * @param value 原始值
     * @return bbox 或 {@code null}
     */
    private static CitationBoundingBox asBoundingBox(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        Integer x = asInteger(raw.get("x"));
        Integer y = asInteger(raw.get("y"));
        Integer width = asInteger(raw.get("w"));
        Integer height = asInteger(raw.get("h"));
        if (x == null || y == null || width == null || height == null) {
            return null;
        }
        if (x < 0 || y < 0 || width < 1 || height < 1) {
            return null;
        }
        return new CitationBoundingBox(x, y, width, height);
    }

    /**
     * 安全读取小数。
     *
     * @param value 原始值
     * @return 小数或 {@code null}
     */
    private static Double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
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

    /**
     * 解析来源类型；未知值按不可用处理。
     *
     * @param value 原始值
     * @return 来源类型或 {@code null}
     */
    private static ArtifactSourceType asSourceType(Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        try {
            return ArtifactSourceType.valueOf(text);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /**
     * 解析定位类型；未知值按缺失处理。
     *
     * @param value 原始值
     * @return 定位类型或 {@code null}
     */
    private static LocatorType asLocatorType(Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        try {
            return LocatorType.valueOf(text);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
