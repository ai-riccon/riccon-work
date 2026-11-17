package com.riccon.worker.parse.service;

import com.riccon.platform.domain.file.FileFormat;
import org.springframework.stereotype.Component;

/**
 * JPEG 图片解析器。
 *
 * <p>复用 {@link ImageDocumentParser} 的元数据策略，仅格式不同；M02 不做 OCR。
 */
@Component
public class JpegDocumentParser extends ImageDocumentParser {

    /** 返回 JPEG 格式。 */
    @Override
    public FileFormat format() {
        return FileFormat.JPEG;
    }
}
