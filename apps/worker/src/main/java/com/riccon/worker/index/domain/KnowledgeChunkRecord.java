package com.riccon.worker.index.domain;

import com.riccon.platform.domain.chat.LocatorType;
import java.util.UUID;

/**
 * 知识库分块记录。
 *
 * <p>MySQL 为权威；OpenSearch 文档 ID 与本记录 {@code id} 一致，索引重建后历史引用仍可按
 * 快照解释来源。
 *
 * @param id 分块 ID（UUIDv7）
 * @param content 分块原文
 * @param locatorType 定位方式
 * @param pageNo 页码
 * @param sheetName 工作表名
 * @param cellRange 单元格范围
 * @param paragraphNo 段落序号
 * @param tokenCount 估算 token 数
 */
public record KnowledgeChunkRecord(
        UUID id,
        String content,
        LocatorType locatorType,
        Integer pageNo,
        String sheetName,
        String cellRange,
        Integer paragraphNo,
        int tokenCount) {}
