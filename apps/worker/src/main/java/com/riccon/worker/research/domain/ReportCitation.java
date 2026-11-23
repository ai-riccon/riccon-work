package com.riccon.worker.research.domain;

import com.riccon.platform.domain.chat.CitationSourceType;
import com.riccon.platform.domain.chat.LocatorType;
import java.util.UUID;

/**
 * 报告引用（待落库的引用清单条目）。
 *
 * <p>{@code sourceType} 使用 {@code artifact_citation} 的字符串枚举值：知识库命中为
 * {@link CitationSourceType#KNOWLEDGE}，MCP 调用为 {@code MCP}；{@code snippet} 只保留截断片段。
 *
 * @param ordinal 引用序号，从 1 开始
 * @param sourceType 来源类型
 * @param fileId 来源文件 ID
 * @param knowledgeDocumentId 来源知识库文档 ID
 * @param chunkId 来源知识库分块 ID；用于读取 bbox/ocr_confidence，仅 KNOWLEDGE
 * @param mcpInvocationId 来源 MCP 调用 ID
 * @param title 来源标题
 * @param snippet 引用片段
 * @param locatorType 定位类型
 * @param pageNo 页码
 * @param sheetName 工作表名
 * @param cellRange 单元格范围
 * @param paragraphNo 段落序号
 * @param sourceVersion 引用时的 knowledge_document.checksum；仅 KNOWLEDGE 引用写入
 */
public record ReportCitation(
        int ordinal,
        String sourceType,
        UUID fileId,
        UUID knowledgeDocumentId,
        UUID chunkId,
        UUID mcpInvocationId,
        String title,
        String snippet,
        LocatorType locatorType,
        Integer pageNo,
        String sheetName,
        String cellRange,
        Integer paragraphNo,
        String sourceVersion) {}
