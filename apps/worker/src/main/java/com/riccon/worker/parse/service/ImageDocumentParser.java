package com.riccon.worker.parse.service;

import com.riccon.platform.domain.chat.LocatorType;
import com.riccon.platform.domain.file.FileFormat;
import com.riccon.worker.parse.domain.FileRecord;
import com.riccon.worker.parse.domain.ParsedContent;
import com.riccon.worker.parse.domain.ParsedSegment;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 图片解析器（M02 仅元数据）。
 *
 * <p>M02 不做 OCR，不读取像素内容；只把文件名、格式与大小写入一个元数据块，供检索结果
 * 展示来源。图片内容本身不进入模型上下文，避免把不可信二进制当作文本处理。
 */
@Component
public class ImageDocumentParser implements DocumentParser {

    /** 返回 PNG 格式。 */
    @Override
    public FileFormat format() {
        return FileFormat.PNG;
    }

    /**
     * 生成图片元数据段。
     *
     * @param file 文件元数据
     * @param content 未使用的图片字节
     * @return 单段解析结果
     */
    @Override
    public ParsedContent parse(FileRecord file, byte[] content) {
        String text = "图片元数据：文件名=" + file.originalName()
                + "；格式=" + file.format()
                + "；大小=" + file.sizeBytes() + " 字节。M02 未执行 OCR。";
        ParsedSegment segment = new ParsedSegment(text, LocatorType.PARAGRAPH, null, null, null, 1);
        return new ParsedContent(List.of(segment), 1, null, PARSER_VERSION);
    }
}
