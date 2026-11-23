package com.riccon.worker.ocr.domain;

/**
 * OCR 调用选项。
 *
 * <p>只承载与引擎无关的页面元数据，不包含 Tesseract 类型；语言由部署配置绑定，避免同一引擎实例
 * 在运行期切换语言导致缓存失效。
 *
 * @param pageNo 页码，从 1 开始
 * @param dpi 渲染/图像 DPI 提示，用于 Tesseract 版面估计；0 表示不设置
 */
public record OcrOptions(int pageNo, int dpi) {

    /** 校验页码与 DPI 边界。 */
    public OcrOptions {
        if (pageNo < 1) {
            throw new IllegalArgumentException("ocr pageNo must start from 1");
        }
        if (dpi < 0 || dpi > 1200) {
            throw new IllegalArgumentException("ocr dpi must be in [0, 1200]");
        }
    }
}
