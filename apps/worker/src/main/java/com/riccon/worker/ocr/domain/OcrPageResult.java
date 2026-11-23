package com.riccon.worker.ocr.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * OCR 页级结果：成功时携带文本块与置信度，失败时携带稳定错误码。
 *
 * <p>低置信度不是失败：有文本块但任一文本块低于块级阈值时 {@code lowConfidence=true}；
 * 整页平均置信度低于页级阈值时 {@code pageLowConfidence=true}。两者都由解析器映射为
 * {@code LOW_CONFIDENCE}/{@code LOW_CONFIDENCE_PAGES} 警告，仍可检索。
 *
 * @param pageNo 页码，从 1 开始
 * @param success 是否成功产出可索引文本
 * @param errorCode 失败错误码；成功时为 null
 * @param message 可读失败原因；成功时为 null
 * @param blocks 文本块，按阅读顺序
 * @param averageConfidence 平均置信度；无文本时为 null
 * @param lowConfidence 是否存在低于块级阈值的文本块
 * @param pageLowConfidence 整页平均置信度是否低于页级阈值
 */
public record OcrPageResult(
        int pageNo,
        boolean success,
        String errorCode,
        String message,
        List<OcrBlock> blocks,
        BigDecimal averageConfidence,
        boolean lowConfidence,
        boolean pageLowConfidence) {

    /** 防御性复制并校验成功/失败互斥。 */
    public OcrPageResult {
        if (pageNo < 1) {
            throw new IllegalArgumentException("ocr pageNo must start from 1");
        }
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        if (success) {
            if (errorCode != null || message != null) {
                throw new IllegalArgumentException("successful ocr page must not carry error fields");
            }
        } else {
            if (errorCode == null || errorCode.isBlank()) {
                throw new IllegalArgumentException("failed ocr page must carry an error code");
            }
            if (!blocks.isEmpty()) {
                throw new IllegalArgumentException("failed ocr page must not carry blocks");
            }
        }
    }

    /**
     * 构造成功结果。
     *
     * @param pageNo 页码
     * @param blocks 文本块
     * @param averageConfidence 平均置信度
     * @param lowConfidence 是否存在低置信块
     * @param pageLowConfidence 整页是否低置信
     * @return 成功结果
     */
    public static OcrPageResult success(
            int pageNo,
            List<OcrBlock> blocks,
            BigDecimal averageConfidence,
            boolean lowConfidence,
            boolean pageLowConfidence) {
        return new OcrPageResult(pageNo, true, null, null, blocks, averageConfidence, lowConfidence, pageLowConfidence);
    }

    /**
     * 构造失败结果。
     *
     * @param pageNo 页码
     * @param errorCode 稳定错误码
     * @param message 可读原因（不得包含原文、对象 key 或引擎堆栈）
     * @return 失败结果
     */
    public static OcrPageResult failure(int pageNo, String errorCode, String message) {
        return new OcrPageResult(pageNo, false, errorCode, message, List.of(), null, false, false);
    }
}
