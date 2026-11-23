package com.riccon.worker.parse.service;

import com.riccon.platform.domain.chat.LocatorType;
import com.riccon.platform.domain.file.FileFormat;
import com.riccon.platform.domain.knowledge.ParseEngine;
import com.riccon.worker.ocr.domain.OcrBlock;
import com.riccon.worker.ocr.domain.OcrPageResult;
import com.riccon.worker.ocr.service.OcrFailureMessages;
import com.riccon.worker.ocr.service.OcrService;
import com.riccon.worker.parse.domain.FileRecord;
import com.riccon.worker.parse.domain.ParseErrorCodes;
import com.riccon.worker.parse.domain.ParseException;
import com.riccon.worker.parse.domain.ParseSummary;
import com.riccon.worker.parse.domain.ParseWarningCodes;
import com.riccon.worker.parse.domain.ParsedContent;
import com.riccon.worker.parse.domain.ParsedSegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 图片解析器（M05 OCR）。
 *
 * <p>PNG/JPEG 统一走 {@link OcrService}：EXIF 方向、像素上限、Tesseract 识别与低置信告警在 OCR
 * 层完成；解析器只把文本块映射为带页码/bbox/置信度的 {@link ParsedSegment}。OCR 关闭时保留原图并
 * 以 {@code KB_OCR_DISABLED} 失败，不生成元数据或伪文本引用（M05-AC-03/05）。
 */
@Component
public class ImageDocumentParser implements DocumentParser {

    /** OCR 服务。 */
    private final OcrService ocrService;

    /**
     * 创建图片解析器。
     *
     * @param ocrService OCR 服务
     */
    public ImageDocumentParser(OcrService ocrService) {
        this.ocrService = Objects.requireNonNull(ocrService, "ocrService must not be null");
    }

    /** 返回 PNG 格式。 */
    @Override
    public FileFormat format() {
        return FileFormat.PNG;
    }

    /**
     * 对图片执行 OCR 并生成带区域的文本段。
     *
     * @param file 文件元数据
     * @param content 图片字节
     * @return 单页解析结果
     * @throws ParseException OCR 关闭、语言缺失、内容损坏或资源超限
     */
    @Override
    public ParsedContent parse(FileRecord file, byte[] content) {
        if (!ocrService.enabled()) {
            throw new ParseException(
                    ParseErrorCodes.OCR_DISABLED, false, OcrFailureMessages.messageOf(ParseErrorCodes.OCR_DISABLED));
        }
        OcrPageResult result = ocrService.recognizeImage(content, 1);
        if (!result.success()) {
            throw new ParseException(
                    result.errorCode(), OcrService.isRetryable(result.errorCode()), failureMessage(result));
        }
        List<ParsedSegment> segments = toSegments(result);
        if (segments.isEmpty()) {
            throw new ParseException(
                    ParseErrorCodes.NO_TEXT, false, OcrFailureMessages.messageOf(ParseErrorCodes.NO_TEXT));
        }
        List<String> warnings = new ArrayList<>();
        if (result.lowConfidence()) {
            warnings.add(ParseWarningCodes.LOW_CONFIDENCE);
        }
        if (result.pageLowConfidence()) {
            warnings.add(ParseWarningCodes.withPages(ParseWarningCodes.LOW_CONFIDENCE_PAGES, List.of(1)));
        }
        ParseSummary summary = new ParseSummary(
                List.of(),
                new ParseSummary.OcrSummary(
                        1,
                        result.averageConfidence(),
                        result.pageLowConfidence() ? List.of(1) : List.of(),
                        List.of(),
                        false),
                null,
                List.of(),
                List.of());
        return new ParsedContent(segments, 1, null, PARSER_VERSION, ParseEngine.TESSERACT, warnings, summary);
    }

    /**
     * 把 OCR 文本块映射为解析段。
     *
     * @param result 页级结果
     * @return 解析段，按阅读顺序
     */
    static List<ParsedSegment> toSegments(OcrPageResult result) {
        List<ParsedSegment> segments = new ArrayList<>();
        for (OcrBlock block : result.blocks()) {
            segments.add(new ParsedSegment(
                    block.text(),
                    LocatorType.PAGE,
                    block.pageNo(),
                    null,
                    null,
                    null,
                    block.bbox(),
                    block.confidence()));
        }
        return List.copyOf(segments);
    }

    /**
     * 返回失败原因；优先使用稳定错误码映射，避免把引擎内部消息带到文档详情。
     *
     * @param result 失败结果
     * @return 中文原因
     */
    static String failureMessage(OcrPageResult result) {
        return OcrFailureMessages.messageOf(result.errorCode());
    }
}
