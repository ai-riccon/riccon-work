package com.riccon.api.knowledge.domain;

import java.util.List;

/**
 * OCR 解析摘要，对应 OpenAPI {@code KnowledgeParseOcrSummary}。
 *
 * @param averageConfidence 文档级平均置信度（0–1）
 * @param lowConfidencePages 低置信页集合
 * @param failedPages 识别失败页集合
 */
public record KnowledgeParseOcrSummary(
        double averageConfidence, List<Integer> lowConfidencePages, List<Integer> failedPages) {

    /** 校验置信度范围并复制集合。 */
    public KnowledgeParseOcrSummary {
        if (!Double.isFinite(averageConfidence) || averageConfidence < 0 || averageConfidence > 1) {
            throw new IllegalArgumentException("averageConfidence must be within [0, 1]");
        }
        lowConfidencePages = lowConfidencePages == null ? List.of() : List.copyOf(lowConfidencePages);
        failedPages = failedPages == null ? List.of() : List.copyOf(failedPages);
    }
}
