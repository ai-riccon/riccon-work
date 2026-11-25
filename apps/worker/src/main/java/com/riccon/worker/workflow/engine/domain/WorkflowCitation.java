package com.riccon.worker.workflow.engine.domain;

import com.riccon.platform.domain.chat.CitationSourceType;
import com.riccon.platform.domain.chat.LocatorType;
import java.util.UUID;

/**
 * 节点引用快照（复用 M05 {@code Citation} 语义）。
 *
 * <p>检索节点把命中固化为本记录并序列化到 {@code workflow_node_run.citations_json}；运行详情读取时
 * 由 API 按当前 ACL/软删除/checksum 计算 {@code sourceStatus}，库内快照不改写（ADR-0024 §3.4）。
 * {@code snippet} 只保留截断片段，不存整段正文。
 *
 * @param ordinal 展示序号，从 1 开始
 * @param sourceType 来源类型
 * @param fileId 来源文件 ID
 * @param knowledgeDocumentId 来源知识库文档 ID
 * @param chunkId 来源分块 ID
 * @param locatorType 定位类型
 * @param pageNo 页码
 * @param sheetName 工作表名
 * @param cellRange 单元格范围
 * @param paragraphNo 段落号
 * @param title 来源标题
 * @param snippet 引用片段
 * @param score 相关度
 * @param sourceVersion 引用时的文档 checksum；仅知识库来源
 */
public record WorkflowCitation(
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
        Double score,
        String sourceVersion) {}
