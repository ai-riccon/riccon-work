package com.riccon.api.chat.domain;

import com.riccon.platform.domain.chat.CitationSourceType;
import com.riccon.platform.domain.chat.LocatorType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 消息引用快照。
 *
 * <p>引用保存来源标题、片段、定位与分数，{@code chunkId} 是逻辑引用；源文件软删除或索引重建后历史引用仍可解释。
 * 片段长度在写入前由 SSE 事件校验限制为 1000 字符。
 *
 * @param id 引用 ID（UUIDv7）
 * @param ordinal 展示序号，从 1 开始
 * @param sourceType 来源类型
 * @param fileId 文件 ID；知识库引用为空
 * @param knowledgeDocumentId 知识库文档 ID；文件引用为空
 * @param chunkId 分块 ID；可为空
 * @param locatorType 定位类型
 * @param pageNo 页码；可为空
 * @param sheetName 工作表名；可为空
 * @param cellRange 单元格范围；可为空
 * @param paragraphNo 段落号；可为空
 * @param title 来源标题快照
 * @param snippet 片段
 * @param score 检索相关度；可为空
 * @param createdAt 创建时间
 */
public record CitationRecord(
        UUID id,
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
        BigDecimal score,
        Instant createdAt) {

    /** 校验必填字段与基础边界。 */
    public CitationRecord {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(sourceType, "sourceType must not be null");
        Objects.requireNonNull(locatorType, "locatorType must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(snippet, "snippet must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (ordinal < 1) {
            throw new IllegalArgumentException("ordinal must be >= 1");
        }
        if (snippet.length() > 1000) {
            throw new IllegalArgumentException("snippet must not exceed 1000 characters");
        }
        if (score != null && (score.signum() < 0 || score.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("score must be between 0 and 1");
        }
    }
}
