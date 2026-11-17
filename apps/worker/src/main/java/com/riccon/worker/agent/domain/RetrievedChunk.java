package com.riccon.worker.agent.domain;

import com.riccon.platform.domain.chat.CitationSourceType;
import com.riccon.platform.domain.chat.LocatorType;
import java.util.UUID;

/**
 * 检索命中的上下文片段与引用元数据。
 *
 * <p>{@code content} 是不可信原文，只能作为数据进入模型上下文；引用事件只发送截断后的
 * {@code snippet}，不发送整段原文。
 *
 * @param ordinal 展示序号，从 1 开始
 * @param sourceType 来源类型
 * @param fileId 文件 ID，知识库来源为空
 * @param knowledgeDocumentId 知识库文档 ID，文件来源为空
 * @param chunkId 分块 ID
 * @param locatorType 定位类型
 * @param pageNo 页码
 * @param sheetName 工作表名
 * @param cellRange 单元格范围
 * @param paragraphNo 段落号
 * @param title 来源标题
 * @param content 分块原文（不可信）
 * @param score 相关度 0–1
 */
public record RetrievedChunk(
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
        String content,
        double score) {

    /**
     * 生成引用片段，限制长度并规范化换行。
     *
     * @param maxLength 最大字符数
     * @return 单行片段
     */
    public String snippet(int maxLength) {
        String normalized =
                content == null ? "" : content.replaceAll("\\s+", " ").strip();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }
}
