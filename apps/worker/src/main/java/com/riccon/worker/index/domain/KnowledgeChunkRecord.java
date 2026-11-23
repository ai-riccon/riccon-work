package com.riccon.worker.index.domain;

import com.riccon.platform.domain.chat.LocatorType;
import com.riccon.worker.parse.domain.BoundingBox;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * 知识库分块记录。
 *
 * <p>MySQL 为权威；OpenSearch 文档 ID 与本记录 {@code id} 一致，索引重建后历史引用仍可按
 * 快照解释来源。M05 起 OCR/扫描件分块携带渲染像素区域与置信度，文本型 PDF/DOCX/Excel
 * 为 {@code null}（ADR-0023 第 4.3 节）。
 *
 * @param id 分块 ID（UUIDv7）
 * @param content 分块原文
 * @param locatorType 定位方式
 * @param pageNo 页码
 * @param sheetName 工作表名
 * @param cellRange 单元格范围
 * @param paragraphNo 段落序号
 * @param tokenCount 估算 token 数
 * @param bbox 渲染像素区域；非 OCR 分块为空
 * @param ocrConfidence OCR 置信度 0–1；非 OCR 分块为空
 */
public record KnowledgeChunkRecord(
        UUID id,
        String content,
        LocatorType locatorType,
        Integer pageNo,
        String sheetName,
        String cellRange,
        Integer paragraphNo,
        int tokenCount,
        BoundingBox bbox,
        BigDecimal ocrConfidence) {

    /** 校验 OCR 置信度范围，避免把越界值写入 DECIMAL(4,3)。 */
    public KnowledgeChunkRecord {
        if (ocrConfidence != null && (ocrConfidence.signum() < 0 || ocrConfidence.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("ocr confidence must be between 0 and 1");
        }
    }

    /**
     * 兼容 M02–M04 的非 OCR 构造器。
     *
     * @param id 分块 ID
     * @param content 分块原文
     * @param locatorType 定位方式
     * @param pageNo 页码
     * @param sheetName 工作表名
     * @param cellRange 单元格范围
     * @param paragraphNo 段落序号
     * @param tokenCount 估算 token 数
     */
    public KnowledgeChunkRecord(
            UUID id,
            String content,
            LocatorType locatorType,
            Integer pageNo,
            String sheetName,
            String cellRange,
            Integer paragraphNo,
            int tokenCount) {
        this(id, content, locatorType, pageNo, sheetName, cellRange, paragraphNo, tokenCount, null, null);
    }
}
