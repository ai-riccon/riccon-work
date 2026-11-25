package com.riccon.api.workflow.history.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.riccon.api.chat.domain.CitationBoundingBox;
import com.riccon.api.chat.domain.CitationSourceStatus;
import com.riccon.platform.domain.chat.CitationSourceType;
import com.riccon.platform.domain.chat.LocatorType;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * 运行详情聚合引用（契约 {@code Citation}）。
 *
 * <p>{@code sourceStatus} 由读取时重新计算；{@code NO_ACCESS}/{@code DELETED} 时标题/片段为空串、
 * 定位字段全部为空，不返回正文；{@code UPDATED} 保留快照并标注源内容已更新（ADR-0024 §3.4、
 * ADR-0023 §5.4）。{@code bbox}/{@code ocrConfidence} 为 M05 图片/Excel 预留字段，M06 检索快照没有
 * 这两个值，保持为空。
 *
 * @param ordinal 聚合后展示序号
 * @param sourceType 来源类型
 * @param fileId 文件 ID
 * @param knowledgeDocumentId 知识库文档 ID
 * @param chunkId 分块 ID
 * @param locatorType 定位类型；不可访问时为空
 * @param pageNo 页码；不可访问时为空
 * @param sheetName 工作表名；不可访问时为空
 * @param cellRange 单元格范围；不可访问时为空
 * @param paragraphNo 段落号；不可访问时为空
 * @param bbox OCR 区域；M06 为空
 * @param ocrConfidence OCR 置信度；M06 为空
 * @param sourceStatus 引用可用性
 * @param title 标题；不可访问时为空串
 * @param snippet 片段；不可访问时为空串
 * @param score 相关度
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowCitationResponse(
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
        CitationBoundingBox bbox,
        BigDecimal ocrConfidence,
        CitationSourceStatus sourceStatus,
        String title,
        String snippet,
        Double score) {}
