package com.riccon.worker.ocr.domain;

/**
 * OCR 引擎 SPI。
 *
 * <p>实现只接受图片字节与引擎无关选项，返回文本块列表（文本、置信度、bbox、行序、页码）；
 * 不得把 Tesseract/JNA 类型泄漏到接口或返回值，便于按 ADR-0023 第 4.2 节切换 RapidOCR/PP-OCR。
 * 调用方负责超时、并发与资源上限，实现不直接访问数据库、对象存储或网络。
 */
public interface OcrEngine {

    /**
     * 返回引擎标识，写入 {@code knowledge_document.parse_engine} 与启动日志。
     *
     * @return 小写引擎标识，例如 {@code tesseract}
     */
    String engineId();

    /**
     * 返回配置语言当前是否可用。
     *
     * <p>语言包缺失时引擎仍可装配，但识别调用返回 {@code KB_OCR_LANG_UNAVAILABLE}；此方法供
     * {@code /actuator/info} 与启动日志使用。
     *
     * @return 可用返回 true
     */
    boolean languageAvailable();

    /**
     * 识别一张图片。
     *
     * @param imageBytes 图片字节（PNG/JPEG 等 ImageIO 可读格式）
     * @param options 页码与 DPI 等引擎无关选项
     * @return 识别结果；无文本时块列表为空
     * @throws OcrException 图片损坏、语言包缺失或引擎执行失败
     */
    OcrResult recognize(byte[] imageBytes, OcrOptions options);
}
