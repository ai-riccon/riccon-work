package com.riccon.api.chat.domain;

import java.math.BigDecimal;

/**
 * 引用分块的 OCR 展示元数据。
 *
 * <p>来自 {@code knowledge_chunk} 的 bbox 与置信度；两者都可为空。历史引用 chunk_id 为空、分块已重建或非 OCR
 * 分块时返回空区域，响应字段为 {@code null}，不能因此让整页引用失败。
 *
 * @param bbox 页内区域；四元组不完整时为空
 * @param ocrConfidence OCR 置信度 0–1；非 OCR 分块为空
 */
public record CitationRegion(CitationBoundingBox bbox, BigDecimal ocrConfidence) {

    /** 空区域。 */
    public static final CitationRegion EMPTY = new CitationRegion(null, null);

    /**
     * 是否包含可展示的 OCR 元数据。
     *
     * @return bbox 或置信度至少一个非空
     */
    public boolean hasData() {
        return bbox != null || ocrConfidence != null;
    }
}
