package com.riccon.api.knowledge.domain;

import java.util.List;

/**
 * 知识库解析结构摘要，对应 OpenAPI {@code KnowledgeParseSummary}。
 *
 * <p>只包含工作表/OCR 的结构元数据，不含单元格原文或正文；由 B4/B5 写入，B3 只读取并原样返回。
 *
 * @param sheets 工作表摘要
 * @param hiddenSheetCount 被跳过的隐藏工作表数量
 * @param ocr OCR 摘要；非 OCR 文档为空
 */
public record KnowledgeParseSummary(
        List<KnowledgeParseSheetSummary> sheets, int hiddenSheetCount, KnowledgeParseOcrSummary ocr) {

    /** 保证集合非空并复制，避免调用方修改。 */
    public KnowledgeParseSummary {
        sheets = sheets == null ? List.of() : List.copyOf(sheets);
        if (hiddenSheetCount < 0) {
            throw new IllegalArgumentException("hiddenSheetCount must be >= 0");
        }
    }
}
