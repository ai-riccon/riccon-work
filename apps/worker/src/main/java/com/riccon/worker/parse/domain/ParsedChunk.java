package com.riccon.worker.parse.domain;

import com.riccon.platform.domain.chat.LocatorType;
import java.math.BigDecimal;

/**
 * 已切分的文本块，写入 {@code file_chunk} 或 {@code knowledge_chunk}。
 *
 * <p>M05 起 OCR/扫描件分块携带像素区域与置信度；文本型 PDF/DOCX/Excel 保持 {@code null}，
 * 不新增 {@code locator_type} 枚举值（ADR-0023 第 4.3 节）。旧构造器保留，避免既有解析器在
 * B4/B5 接入前被迫改签名。
 *
 * @param content 块内容
 * @param locatorType 定位方式
 * @param pageNo 页码
 * @param sheetName 工作表名
 * @param cellRange 单元格范围
 * @param paragraphNo 段落序号
 * @param tokenCount 估算 token 数（按字符数 / 4 向上取整）
 * @param bbox 渲染像素区域；非 OCR 分块为空
 * @param ocrConfidence OCR 置信度 0–1；非 OCR 分块为空
 */
public record ParsedChunk(
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
    public ParsedChunk {
        if (ocrConfidence != null && (ocrConfidence.signum() < 0 || ocrConfidence.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("ocr confidence must be between 0 and 1");
        }
    }

    /**
     * 兼容 M02–M04 的非 OCR 构造器。
     *
     * @param content 块内容
     * @param locatorType 定位方式
     * @param pageNo 页码
     * @param sheetName 工作表名
     * @param cellRange 单元格范围
     * @param paragraphNo 段落序号
     * @param tokenCount 估算 token 数
     */
    public ParsedChunk(
            String content,
            LocatorType locatorType,
            Integer pageNo,
            String sheetName,
            String cellRange,
            Integer paragraphNo,
            int tokenCount) {
        this(content, locatorType, pageNo, sheetName, cellRange, paragraphNo, tokenCount, null, null);
    }
}
