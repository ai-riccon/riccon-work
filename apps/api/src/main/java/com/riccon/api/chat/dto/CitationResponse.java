package com.riccon.api.chat.dto;

import com.riccon.api.chat.domain.CitationRecord;
import com.riccon.api.chat.domain.CitationSourceStatus;
import com.riccon.platform.domain.chat.CitationSourceType;
import com.riccon.platform.domain.chat.LocatorType;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * 消息引用响应，对应 OpenAPI {@code Citation}。
 *
 * <p>{@code sourceStatus} 由读取时计算：{@code NO_ACCESS}（ACL 撤销）或 {@code DELETED}（源文档软删除）时
 * {@code title}/{@code snippet} 返回空串、定位字段全部为 {@code null}，不返回正文；{@code UPDATED} 保留快照并标注
 * 源内容已更新。库内快照不被改写，仅供审计/取证（ADR-0023 第 5.4 节、TM-68）。
 *
 * @param ordinal 展示序号
 * @param sourceType 来源类型
 * @param fileId 文件 ID；知识库引用为空
 * @param knowledgeDocumentId 知识库文档 ID；文件引用为空
 * @param chunkId 分块 ID
 * @param locatorType 定位类型；不可访问时为空
 * @param pageNo 页码；不可访问时为空
 * @param sheetName 工作表名；不可访问时为空
 * @param cellRange 单元格范围；不可访问时为空
 * @param paragraphNo 段落号；不可访问时为空
 * @param sourceStatus 引用可用性
 * @param title 来源标题快照；不可访问时为空串
 * @param snippet 片段；不可访问时为空串
 * @param score 检索相关度
 */
public record CitationResponse(
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
        CitationSourceStatus sourceStatus,
        String title,
        String snippet,
        BigDecimal score) {

    /**
     * 将持久化引用映射为响应，并按可用性遮蔽。
     *
     * @param citation 引用快照
     * @return 响应
     */
    public static CitationResponse from(CitationRecord citation) {
        CitationSourceStatus status = citation.sourceStatus();
        boolean hidden = isHidden(status);
        return new CitationResponse(
                citation.ordinal(),
                citation.sourceType(),
                citation.fileId(),
                citation.knowledgeDocumentId(),
                citation.chunkId(),
                hidden ? null : citation.locatorType(),
                hidden ? null : citation.pageNo(),
                hidden ? null : citation.sheetName(),
                hidden ? null : citation.cellRange(),
                hidden ? null : citation.paragraphNo(),
                status,
                hidden ? "" : citation.title(),
                hidden ? "" : citation.snippet(),
                citation.score());
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
