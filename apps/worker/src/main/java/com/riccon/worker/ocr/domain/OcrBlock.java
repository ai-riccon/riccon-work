package com.riccon.worker.ocr.domain;

import com.riccon.worker.parse.domain.BoundingBox;
import java.math.BigDecimal;

/**
 * OCR 识别出的单个文本块（通常是一行）。
 *
 * <p>坐标原点为渲染图片左上角，单位是渲染像素；行序从 1 开始，按阅读顺序递增。置信度为 0–1，
 * 由引擎归一化后返回，不把 Tesseract 的 0–100 分值泄漏到领域层。
 *
 * @param text 文本内容，非空白
 * @param confidence 置信度 0–1
 * @param bbox 文本块区域
 * @param lineNo 页内行序，从 1 开始
 * @param pageNo 页码，从 1 开始
 */
public record OcrBlock(String text, BigDecimal confidence, BoundingBox bbox, int lineNo, int pageNo) {

    /** 校验文本、置信度、区域与行序。 */
    public OcrBlock {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("ocr block text must not be blank");
        }
        if (confidence == null || confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("ocr block confidence must be between 0 and 1");
        }
        if (bbox == null) {
            throw new IllegalArgumentException("ocr block bbox must not be null");
        }
        if (lineNo < 1) {
            throw new IllegalArgumentException("ocr block lineNo must start from 1");
        }
        if (pageNo < 1) {
            throw new IllegalArgumentException("ocr block pageNo must start from 1");
        }
    }
}
