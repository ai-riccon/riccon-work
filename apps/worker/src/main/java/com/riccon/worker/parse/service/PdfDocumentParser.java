package com.riccon.worker.parse.service;

import com.riccon.platform.domain.chat.LocatorType;
import com.riccon.platform.domain.file.FileFormat;
import com.riccon.worker.parse.domain.FileRecord;
import com.riccon.worker.parse.domain.ParseException;
import com.riccon.worker.parse.domain.ParsedContent;
import com.riccon.worker.parse.domain.ParsedSegment;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * PDF 解析器。
 *
 * <p>按页抽取文本，页码从 1 开始写入定位字段；扫描版 PDF 没有文本层，M02 不做 OCR，
 * 由服务层统一判定“无可抽取文本”并保留失败原因。
 */
@Component
public class PdfDocumentParser implements DocumentParser {

    /** 返回 PDF 格式。 */
    @Override
    public FileFormat format() {
        return FileFormat.PDF;
    }

    /**
     * 按页抽取 PDF 文本。
     *
     * @param file 文件元数据
     * @param content PDF 字节
     * @return 解析结果
     */
    @Override
    public ParsedContent parse(FileRecord file, byte[] content) {
        try (PDDocument document = Loader.loadPDF(content)) {
            int pageCount = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            List<ParsedSegment> segments = new ArrayList<>();
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document).strip();
                if (!text.isBlank()) {
                    segments.add(new ParsedSegment(text, LocatorType.PAGE, page, null, null, null));
                }
            }
            return new ParsedContent(segments, pageCount, null, PARSER_VERSION);
        } catch (IOException | RuntimeException ex) {
            throw ParseException.unprocessable("pdf document is not readable", ex);
        }
    }
}
