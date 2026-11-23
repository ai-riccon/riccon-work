package com.riccon.api.research.dto;

import com.riccon.api.chat.domain.CitationBoundingBox;
import com.riccon.api.chat.domain.CitationSourceStatus;
import com.riccon.api.research.domain.ArtifactCitationSnapshot;
import com.riccon.api.research.domain.ArtifactSourceType;
import com.riccon.platform.domain.chat.LocatorType;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * 产物引用响应，对应 OpenAPI {@code ArtifactCitation}。
 *
 * <p>{@code sourceStatus=NO_ACCESS|DELETED} 时 {@code title}/{@code snippet} 为空串、定位字段为 {@code null}，
 * 不返回正文；{@code UPDATED} 保留快照。库内 {@code artifact_citation} 仍保留完整快照供审计/取证。
 *
 * @param ordinal 序号
 * @param sourceType 来源类型
 * @param fileId 文件 ID
 * @param knowledgeDocumentId 知识库文档 ID
 * @param mcpInvocationId MCP 调用 ID
 * @param title 标题
 * @param snippet 片段
 * @param locatorType 定位类型
 * @param pageNo 页码
 * @param sheetName 工作表名
 * @param cellRange 单元格范围
 * @param paragraphNo 段落号
 * @param bbox OCR 页内区域；不可访问或非 OCR 分块为空
 * @param ocrConfidence OCR 置信度；不可访问或非 OCR 分块为空
 * @param sourceStatus 引用可用性
 */
public record ArtifactCitationResponse(
        int ordinal,
        ArtifactSourceType sourceType,
        UUID fileId,
        UUID knowledgeDocumentId,
        UUID mcpInvocationId,
        String title,
        String snippet,
        LocatorType locatorType,
        Integer pageNo,
        String sheetName,
        String cellRange,
        Integer paragraphNo,
        CitationBoundingBox bbox,
        BigDecimal ocrConfidence,
        CitationSourceStatus sourceStatus) {

    /**
     * 将引用快照映射为响应，并按可用性遮蔽。
     *
     * @param citation 引用快照
     * @return 响应
     */
    public static ArtifactCitationResponse from(ArtifactCitationSnapshot citation) {
        CitationSourceStatus status = citation.sourceStatus();
        boolean hidden = isHidden(status);
        return new ArtifactCitationResponse(
                citation.ordinal(),
                citation.sourceType(),
                citation.fileId(),
                citation.knowledgeDocumentId(),
                citation.mcpInvocationId(),
                hidden ? "" : citation.title(),
                hidden ? "" : citation.snippet(),
                hidden ? null : citation.locatorType(),
                hidden ? null : citation.pageNo(),
                hidden ? null : citation.sheetName(),
                hidden ? null : citation.cellRange(),
                hidden ? null : citation.paragraphNo(),
                hidden ? null : citation.bbox(),
                hidden ? null : citation.ocrConfidence(),
                status);
    }

    /**
     * 判断是否需要遮蔽正文与定位。
     *
     * @param status 可用性
     * @return {@code NO_ACCESS}/{@code DELETED} 返回 {@code true}
     */
    private static boolean isHidden(CitationSourceStatus status) {
        return status == CitationSourceStatus.NO_ACCESS || status == CitationSourceStatus.DELETED;
    }
}
