package com.riccon.worker.parse.service;

import com.riccon.platform.domain.file.FileFormat;
import com.riccon.worker.ocr.service.OcrService;
import org.springframework.stereotype.Component;

/**
 * JPEG 图片解析器。
 *
 * <p>复用 {@link ImageDocumentParser} 的 M05 OCR 策略（EXIF 方向、bbox、置信度与四态），仅格式
 * 不同；JPEG 的 90°/180°/270° 方向在 OCR 层校正后识别。
 */
@Component
public class JpegDocumentParser extends ImageDocumentParser {

    /**
     * 创建 JPEG 解析器。
     *
     * @param ocrService OCR 服务
     */
    public JpegDocumentParser(OcrService ocrService) {
        super(ocrService);
    }

    /** 返回 JPEG 格式。 */
    @Override
    public FileFormat format() {
        return FileFormat.JPEG;
    }
}
