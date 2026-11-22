package com.riccon.worker.parse.domain;

import com.riccon.platform.domain.chat.LocatorType;
import java.math.BigDecimal;

/**
 * 解析器输出的原始文本段，尚未按长度切分。
 *
 * <p>定位字段必须能回溯到原文：PDF 用页码、Excel 用工作表与单元格范围、Word 用段落序号；
 * M05 起 OCR/扫描件段可携带像素区域与置信度，切分后由 {@link ParsedChunk} 原样继承。
 *
 * @param content 文本内容
 * @param locatorType 定位方式
 * @param pageNo 页码，可为空
 * @param sheetName 工作表名，可为空
 * @param cellRange 单元格范围，可为空
 * @param paragraphNo 段落序号，可为空
 * @param bbox 渲染像素区域；非 OCR 段为空
 * @param ocrConfidence OCR 置信度 0–1；非 OCR 段为空
 */
public record ParsedSegment(
        String content,
        LocatorType locatorType,
        Integer pageNo,
        String sheetName,
        String cellRange,
        Integer paragraphNo,
        BoundingBox bbox,
        BigDecimal ocrConfidence) {

    /** 校验 OCR 置信度范围，避免把越界值写入 DECIMAL(4,3)。 */
    public ParsedSegment {
        if (ocrConfidence != null && (ocrConfidence.signum() < 0 || ocrConfidence.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("ocr confidence must be between 0 and 1");
        }
    }

    /**
     * 兼容 M02–M04 的非 OCR 构造器。
     *
     * @param content 文本内容
     * @param locatorType 定位方式
     * @param pageNo 页码
     * @param sheetName 工作表名
     * @param cellRange 单元格范围
     * @param paragraphNo 段落序号
     */
    public ParsedSegment(
            String content,
            LocatorType locatorType,
            Integer pageNo,
            String sheetName,
            String cellRange,
            Integer paragraphNo) {
        this(content, locatorType, pageNo, sheetName, cellRange, paragraphNo, null, null);
    }
}
