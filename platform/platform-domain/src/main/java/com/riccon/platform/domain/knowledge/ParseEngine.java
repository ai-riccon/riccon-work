package com.riccon.platform.domain.knowledge;

/**
 * 知识库文档解析引擎标识（ADR-0023 第 3/4 节）。
 *
 * <p>写入 {@code knowledge_document.parse_engine}，只用于详情展示与排障，不参与授权判定。
 * 历史文档与未知引擎为空；{@link #NONE} 表示文档登记后未进入解析或没有可索引文本。
 */
public enum ParseEngine {
    /** PDF 文本层抽取（PDFBox）。 */
    PDFBOX,
    /** Office Open XML 流式抽取（Apache POI）。 */
    POI,
    /** Tesseract 本地 OCR（默认 OCR 引擎）。 */
    TESSERACT,
    /** RapidOCR/PP-OCR ONNX 替代引擎（精度不达标时切换）。 */
    RAPIDOCR,
    /** 未使用解析引擎或没有可索引文本。 */
    NONE
}
