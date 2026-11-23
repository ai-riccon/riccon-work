package com.riccon.worker.research.domain;

import java.util.UUID;

/**
 * 产物引用清单条目。
 *
 * <p>每条引用必须能对应运行时间线中的检索命中或 MCP 调用，供 M02-AC-10 人工抽查；
 * {@code snippet} 是面向员工展示的来源片段，不得包含完整文档或工具结果原文。
 *
 * @param id 引用 ID
 * @param artifactVersionId 所属产物版本
 * @param ordinal 引用序号，从 1 开始
 * @param sourceType 来源类型（FILE/KNOWLEDGE/MCP）
 * @param fileId 来源文件 ID
 * @param knowledgeDocumentId 来源知识库文档 ID
 * @param chunkId 来源知识库分块 ID（逻辑引用）；历史行为空
 * @param mcpInvocationId 来源 MCP 调用 ID
 * @param titleSnapshot 引用时的来源标题快照
 * @param snippet 引用片段
 * @param locatorType 定位类型
 * @param pageNo 页码
 * @param sheetName 工作表名
 * @param cellRange 单元格范围
 * @param paragraphNo 段落序号
 * @param sourceVersion 引用时的 knowledge_document.checksum；仅 KNOWLEDGE 引用写入
 */
public record ArtifactCitationRecord(
        UUID id,
        UUID artifactVersionId,
        int ordinal,
        String sourceType,
        UUID fileId,
        UUID knowledgeDocumentId,
        UUID chunkId,
        UUID mcpInvocationId,
        String titleSnapshot,
        String snippet,
        String locatorType,
        Integer pageNo,
        String sheetName,
        String cellRange,
        Integer paragraphNo,
        String sourceVersion) {}
