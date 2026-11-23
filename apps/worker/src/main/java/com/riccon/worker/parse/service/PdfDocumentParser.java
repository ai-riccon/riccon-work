package com.riccon.worker.parse.service;

import com.riccon.platform.domain.chat.LocatorType;
import com.riccon.platform.domain.file.FileFormat;
import com.riccon.platform.domain.knowledge.ParseEngine;
import com.riccon.worker.ocr.config.OcrProperties;
import com.riccon.worker.ocr.domain.OcrBlock;
import com.riccon.worker.ocr.domain.OcrPageResult;
import com.riccon.worker.ocr.domain.OcrResult;
import com.riccon.worker.ocr.service.OcrFailureMessages;
import com.riccon.worker.ocr.service.OcrService;
import com.riccon.worker.parse.domain.FileRecord;
import com.riccon.worker.parse.domain.ParseErrorCodes;
import com.riccon.worker.parse.domain.ParseException;
import com.riccon.worker.parse.domain.ParseSummary;
import com.riccon.worker.parse.domain.ParseWarningCodes;
import com.riccon.worker.parse.domain.ParsedContent;
import com.riccon.worker.parse.domain.ParsedSegment;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * PDF 解析器（M05 扫描件 OCR）。
 *
 * <p>逐页先尝试文本层：有文本走 PDFBox，保持页码与既有结果不变；无文本页才渲染为 200 DPI
 * （可配）图片走 OCR。混合文档中失败/跳过的页只产生警告，不产生伪分块；全部页失败才让文档
 * {@code FAILED}（ADR-0023 第 4.3/4.5 节）。单页与单文档超时都按预算中断 OCR，成功页仍入索引。
 */
@Component
public class PdfDocumentParser implements DocumentParser {

    /** OCR 服务。 */
    private final OcrService ocrService;

    /** OCR 配置。 */
    private final OcrProperties ocrProperties;

    /** 文档级超时时钟；测试注入可变时钟验证预算耗尽。 */
    private final Clock clock;

    /**
     * 创建 PDF 解析器。
     *
     * @param ocrService OCR 服务
     * @param ocrProperties OCR 配置
     * @param clock 时钟
     */
    public PdfDocumentParser(OcrService ocrService, OcrProperties ocrProperties, Clock clock) {
        this.ocrService = Objects.requireNonNull(ocrService, "ocrService must not be null");
        this.ocrProperties = Objects.requireNonNull(ocrProperties, "ocrProperties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** 返回 PDF 格式。 */
    @Override
    public FileFormat format() {
        return FileFormat.PDF;
    }

    /**
     * 按页抽取文本；无文本层页面按开关与预算执行 OCR。
     *
     * @param file 文件元数据
     * @param content PDF 字节
     * @return 解析结果
     * @throws ParseException PDF 损坏、全部页 OCR 失败或没有可索引文本
     */
    @Override
    public ParsedContent parse(FileRecord file, byte[] content) {
        try (PDDocument document = Loader.loadPDF(content)) {
            return parseDocument(document);
        } catch (ParseException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw ParseException.unprocessable("pdf document is not readable", exception);
        }
    }

    /**
     * 解析已加载的 PDF。
     *
     * @param document PDF 文档
     * @return 解析结果
     * @throws IOException PDF 文本层读取失败
     */
    private ParsedContent parseDocument(PDDocument document) throws IOException {
        int pageCount = document.getNumberOfPages();
        PDFTextStripper stripper = new PDFTextStripper();
        PDFRenderer renderer = new PDFRenderer(document);
        List<ParsedSegment> segments = new ArrayList<>();
        List<OcrBlock> ocrBlocks = new ArrayList<>();
        List<Integer> failedPages = new ArrayList<>();
        List<Integer> lowConfidencePages = new ArrayList<>();
        List<String> ocrErrors = new ArrayList<>();
        int pagesWithoutText = 0;
        int ocrAttempts = 0;
        int skippedByLimit = 0;
        int skippedByDeadline = 0;
        boolean anyLowConfidence = false;
        long deadlineMillis = clock.millis() + ocrProperties.documentTimeout().toMillis();
        for (int page = 1; page <= pageCount; page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            String text = stripper.getText(document).strip();
            if (!text.isBlank()) {
                // 文本型页保持 M02 行为：每个有文本的页一个段落，不触发 OCR。
                segments.add(new ParsedSegment(text, LocatorType.PAGE, page, null, null, null));
                continue;
            }
            if (!ocrService.enabled()) {
                pagesWithoutText++;
                continue;
            }
            if (ocrAttempts >= ocrProperties.maxPages()) {
                skippedByLimit++;
                pagesWithoutText++;
                continue;
            }
            long remainingMillis = deadlineMillis - clock.millis();
            if (remainingMillis <= 0) {
                skippedByDeadline++;
                pagesWithoutText++;
                continue;
            }
            long[] pixels = renderedPixels(document.getPage(page - 1));
            if (pixels[0] * pixels[1] > ocrProperties.maxDecodePixels()) {
                // 先按页面尺寸判断，避免渲染超大页时先耗尽内存。
                failedPages.add(page);
                pagesWithoutText++;
                ocrErrors.add(ParseErrorCodes.PARSE_RESOURCE_LIMIT);
                continue;
            }
            BufferedImage pageImage;
            try {
                pageImage = renderer.renderImageWithDPI(page - 1, ocrProperties.pdfDpi(), ImageType.RGB);
            } catch (IOException | RuntimeException exception) {
                failedPages.add(page);
                pagesWithoutText++;
                ocrErrors.add(ParseErrorCodes.PARSE_CORRUPT);
                continue;
            }
            Duration budget = min(ocrProperties.pageTimeout(), Duration.ofMillis(remainingMillis));
            OcrPageResult result = ocrService.recognizeRendered(pageImage, page, budget);
            ocrAttempts++;
            if (result.success()) {
                ocrBlocks.addAll(result.blocks());
                segments.addAll(ImageDocumentParser.toSegments(result));
                anyLowConfidence |= result.lowConfidence();
                if (result.pageLowConfidence()) {
                    lowConfidencePages.add(page);
                }
            } else {
                failedPages.add(page);
                pagesWithoutText++;
                ocrErrors.add(result.errorCode());
            }
        }

        if (segments.isEmpty()) {
            throw failureOf(pageCount, ocrService.enabled(), ocrErrors, pagesWithoutText);
        }

        List<String> warnings = new ArrayList<>();
        if (!failedPages.isEmpty()) {
            warnings.add(ParseWarningCodes.withPages(ParseWarningCodes.FAILED_PAGES, failedPages));
        }
        if (pagesWithoutText > 0) {
            warnings.add(ParseWarningCodes.withCount(ParseWarningCodes.PAGES_WITHOUT_TEXT, pagesWithoutText));
        }
        if (anyLowConfidence) {
            warnings.add(ParseWarningCodes.LOW_CONFIDENCE);
        }
        if (!lowConfidencePages.isEmpty()) {
            warnings.add(ParseWarningCodes.withPages(ParseWarningCodes.LOW_CONFIDENCE_PAGES, lowConfidencePages));
        }
        int skipped = skippedByLimit + skippedByDeadline;
        if (skipped > 0) {
            warnings.add(ParseWarningCodes.withCount(ParseWarningCodes.OCR_PAGE_LIMIT, skipped));
        }
        ParseSummary summary = ocrAttempts == 0
                ? null
                : new ParseSummary(
                        List.of(),
                        new ParseSummary.OcrSummary(
                                ocrAttempts,
                                OcrResult.weightedAverageConfidence(ocrBlocks),
                                lowConfidencePages,
                                failedPages,
                                skipped > 0),
                        null,
                        List.of(),
                        List.of());
        ParseEngine engine = ocrBlocks.isEmpty() ? ParseEngine.PDFBOX : ParseEngine.TESSERACT;
        return new ParsedContent(segments, pageCount, null, PARSER_VERSION, engine, warnings, summary);
    }

    /**
     * 构造整本文档失败：区分“没有文本层且 OCR 不可用”和“OCR 全部失败”。
     *
     * @param pageCount 页数
     * @param ocrEnabled OCR 是否启用
     * @param errors OCR 失败码
     * @param pagesWithoutText 无文本页数
     * @return 不可重试/可重试的解析异常
     */
    private static ParseException failureOf(
            int pageCount, boolean ocrEnabled, List<String> errors, int pagesWithoutText) {
        if (pageCount == 0) {
            return new ParseException(ParseErrorCodes.NO_TEXT, false, "PDF 没有可索引文本");
        }
        String errorCode = dominantFailure(ocrEnabled, errors, pagesWithoutText);
        return new ParseException(
                errorCode, OcrService.isRetryable(errorCode), OcrFailureMessages.messageOf(errorCode));
    }

    /**
     * 选择整本失败的主错误码，优先系统性原因（语言/资源），其余归为 OCR 失败。
     *
     * @param ocrEnabled OCR 是否启用
     * @param errors 各页错误码
     * @param pagesWithoutText 无文本页数
     * @return 稳定错误码
     */
    static String dominantFailure(boolean ocrEnabled, List<String> errors, int pagesWithoutText) {
        if (!ocrEnabled) {
            return ParseErrorCodes.OCR_UNAVAILABLE;
        }
        if (errors.contains(ParseErrorCodes.OCR_LANG_UNAVAILABLE)) {
            return ParseErrorCodes.OCR_LANG_UNAVAILABLE;
        }
        if (errors.contains(ParseErrorCodes.PARSE_RESOURCE_LIMIT)) {
            return ParseErrorCodes.PARSE_RESOURCE_LIMIT;
        }
        if (errors.isEmpty()) {
            return pagesWithoutText > 0 ? ParseErrorCodes.OCR_UNAVAILABLE : ParseErrorCodes.NO_TEXT;
        }
        if (errors.stream().allMatch(ParseErrorCodes.OCR_TIMEOUT::equals)) {
            return ParseErrorCodes.OCR_TIMEOUT;
        }
        return ParseErrorCodes.OCR_FAILED;
    }

    /**
     * 计算页面按目标 DPI 渲染后的像素尺寸。
     *
     * @param page PDF 页
     * @return 宽度与高度
     */
    private long[] renderedPixels(PDPage page) {
        PDRectangle box = page.getCropBox();
        float widthPoints = box.getWidth();
        float heightPoints = box.getHeight();
        if (page.getRotation() % 180 != 0) {
            float swap = widthPoints;
            widthPoints = heightPoints;
            heightPoints = swap;
        }
        long width = Math.max(1, Math.round(widthPoints / 72.0f * ocrProperties.pdfDpi()));
        long height = Math.max(1, Math.round(heightPoints / 72.0f * ocrProperties.pdfDpi()));
        if (width > Integer.MAX_VALUE || height > Integer.MAX_VALUE) {
            // 返回超过硬上限的像素数，让调用方按资源超限处理，而不是尝试渲染。
            return new long[] {ocrProperties.maxDecodePixels() + 1, 1};
        }
        return new long[] {width, height};
    }

    /**
     * 取两个预算的较小值。
     *
     * @param first 第一个预算
     * @param second 第二个预算
     * @return 较小值
     */
    private static Duration min(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }
}
