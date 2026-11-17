package com.riccon.worker.parse.service;

import com.riccon.platform.domain.chat.LocatorType;
import com.riccon.platform.domain.file.FileFormat;
import com.riccon.worker.parse.domain.FileRecord;
import com.riccon.worker.parse.domain.ParseException;
import com.riccon.worker.parse.domain.ParsedContent;
import com.riccon.worker.parse.domain.ParsedSegment;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

/**
 * DOCX 解析器。
 *
 * <p>按文档顺序遍历段落与表格，段落序号从 1 开始写入定位字段；表格按行拼成文本并占用一个
 * 段落序号，保证引用可回溯到原文位置。
 */
@Component
public class DocxDocumentParser implements DocumentParser {

    /** 返回 DOCX 格式。 */
    @Override
    public FileFormat format() {
        return FileFormat.DOCX;
    }

    /**
     * 抽取 DOCX 段落与表格文本。
     *
     * @param file 文件元数据
     * @param content DOCX 字节
     * @return 解析结果
     */
    @Override
    public ParsedContent parse(FileRecord file, byte[] content) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content))) {
            List<ParsedSegment> segments = new ArrayList<>();
            int paragraphNo = 0;
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    paragraphNo++;
                    String text = paragraph.getText().strip();
                    if (!text.isBlank()) {
                        segments.add(new ParsedSegment(text, LocatorType.PARAGRAPH, null, null, null, paragraphNo));
                    }
                } else if (element instanceof XWPFTable table) {
                    paragraphNo++;
                    String text = tableText(table);
                    if (!text.isBlank()) {
                        segments.add(new ParsedSegment(text, LocatorType.PARAGRAPH, null, null, null, paragraphNo));
                    }
                }
            }
            return new ParsedContent(segments, null, null, PARSER_VERSION);
        } catch (IOException | RuntimeException ex) {
            throw ParseException.unprocessable("docx document is not readable", ex);
        }
    }

    /**
     * 将表格按行拼成文本；单元格之间用竖线分隔。
     *
     * @param table 表格
     * @return 表格文本
     */
    private static String tableText(XWPFTable table) {
        StringBuilder builder = new StringBuilder();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                String value = cell.getText().strip();
                if (!value.isBlank()) {
                    cells.add(value);
                }
            }
            if (!cells.isEmpty()) {
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }
                builder.append(String.join(" | ", cells));
            }
        }
        return builder.toString();
    }
}
