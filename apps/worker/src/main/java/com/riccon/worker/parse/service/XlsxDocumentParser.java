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
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * XLSX 解析器。
 *
 * <p>逐工作表抽取非空单元格，定位字段保存工作表名与使用范围（如 A1:D20）。为避免恶意/异常
 * 文件撑爆内存，只读取有上限的行列；被截断的工作表仍可解析，但不会索引超出上限的内容。
 */
@Component
public class XlsxDocumentParser implements DocumentParser {

    /** 单工作表最多读取的行数。 */
    private static final int MAX_ROWS = 20_000;

    /** 单行最多读取的列数。 */
    private static final int MAX_COLUMNS = 200;

    /** 返回 XLSX 格式。 */
    @Override
    public FileFormat format() {
        return FileFormat.XLSX;
    }

    /**
     * 抽取 Excel 文本。
     *
     * @param file 文件元数据
     * @param content XLSX 字节
     * @return 解析结果
     */
    @Override
    public ParsedContent parse(FileRecord file, byte[] content) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            DataFormatter formatter = new DataFormatter();
            List<ParsedSegment> segments = new ArrayList<>();
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                ParsedSegment segment = parseSheet(sheet, formatter);
                if (segment != null) {
                    segments.add(segment);
                }
            }
            return new ParsedContent(segments, null, workbook.getNumberOfSheets(), PARSER_VERSION);
        } catch (IOException | RuntimeException ex) {
            throw ParseException.unprocessable("xlsx document is not readable", ex);
        }
    }

    /**
     * 解析单个工作表；空表返回 null。
     *
     * @param sheet 工作表
     * @param formatter 单元格格式化器
     * @return 文本段或 null
     */
    private static ParsedSegment parseSheet(Sheet sheet, DataFormatter formatter) {
        int firstRow = Math.max(sheet.getFirstRowNum(), 0);
        int lastRow = Math.min(sheet.getLastRowNum(), firstRow + MAX_ROWS - 1);
        if (lastRow < firstRow) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        int minColumn = Integer.MAX_VALUE;
        int maxColumn = -1;
        for (int rowIndex = firstRow; rowIndex <= lastRow; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            int firstCell = Math.max(row.getFirstCellNum(), 0);
            int lastCell = Math.min(row.getLastCellNum(), firstCell + MAX_COLUMNS);
            List<String> values = new ArrayList<>();
            for (int cellIndex = firstCell; cellIndex < lastCell; cellIndex++) {
                Cell cell = row.getCell(cellIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                if (cell == null) {
                    continue;
                }
                String value = formatter.formatCellValue(cell).strip();
                if (value.isBlank()) {
                    continue;
                }
                values.add(value);
                minColumn = Math.min(minColumn, cellIndex);
                maxColumn = Math.max(maxColumn, cellIndex);
            }
            if (!values.isEmpty()) {
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }
                builder.append(String.join(" | ", values));
            }
        }
        if (builder.isEmpty() || minColumn > maxColumn) {
            return null;
        }
        String cellRange = CellReference.convertNumToColString(minColumn)
                + (firstRow + 1)
                + ":"
                + CellReference.convertNumToColString(maxColumn)
                + (lastRow + 1);
        return new ParsedSegment(builder.toString(), LocatorType.SHEET, null, sheet.getSheetName(), cellRange, null);
    }
}
