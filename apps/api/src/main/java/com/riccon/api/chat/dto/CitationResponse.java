package com.riccon.api.chat.dto;

import com.riccon.api.chat.domain.CitationRecord;
import com.riccon.platform.domain.chat.CitationSourceType;
import com.riccon.platform.domain.chat.LocatorType;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * 消息引用响应，对应 OpenAPI {@code Citation}。
 *
 * @param ordinal 展示序号
 * @param sourceType 来源类型
 * @param fileId 文件 ID；知识库引用为空
 * @param knowledgeDocumentId 知识库文档 ID；文件引用为空
 * @param chunkId 分块 ID
 * @param locatorType 定位类型
 * @param pageNo 页码
 * @param sheetName 工作表名
 * @param cellRange 单元格范围
 * @param paragraphNo 段落号
 * @param title 来源标题快照
 * @param snippet 片段
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
        String title,
        String snippet,
        BigDecimal score) {

    /**
     * 将持久化引用映射为响应。
     *
     * @param citation 引用快照
     * @return 响应
     */
    public static CitationResponse from(CitationRecord citation) {
        return new CitationResponse(
                citation.ordinal(),
                citation.sourceType(),
                citation.fileId(),
                citation.knowledgeDocumentId(),
                citation.chunkId(),
                citation.locatorType(),
                citation.pageNo(),
                citation.sheetName(),
                citation.cellRange(),
                citation.paragraphNo(),
                citation.title(),
                citation.snippet(),
                citation.score());
    }
}
