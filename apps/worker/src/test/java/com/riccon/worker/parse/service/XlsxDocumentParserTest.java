package com.riccon.worker.parse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.domain.chat.LocatorType;
import com.riccon.platform.domain.file.FileFormat;
import com.riccon.platform.domain.knowledge.ParseEngine;
import com.riccon.worker.parse.domain.FileRecord;
import com.riccon.worker.parse.domain.ParseErrorCodes;
import com.riccon.worker.parse.domain.ParseException;
import com.riccon.worker.parse.domain.ParseSummary;
import com.riccon.worker.parse.domain.ParseWarningCodes;
import com.riccon.worker.parse.domain.ParsedContent;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link XlsxDocumentParser} 的流式解析、定位与降级测试（ADR-0023 第 3.5 节）。
 *
 * <p>夹具全部在代码内用 POI 生成，不新增测试资源文件。重点锁定：行带与 cellRange、
 * 隐藏/空表、公式缓存值、日期渲染、行列/字符/段落上限、拒绝与 FAILED 错误码分离，
 * 以及警告/摘要不泄漏单元格原文。
 */
class XlsxDocumentParserTest {

    /**
     * 多工作表按行带输出，cellRange 带工作表维度信息。
     */
    @Test
    @DisplayName("多工作表按行带与 cellRange 输出")
    void parse_multipleSheets_usesRowBandsAndSheetLocators() {
        byte[] content = workbook(workbook -> {
            Sheet sales = workbook.createSheet("销售数据");
            for (int index = 0; index < 9; index++) {
                Row row = sales.createRow(index + 1);
                row.createCell(1).setCellValue("地区-" + (index + 1));
                row.createCell(2).setCellValue(100 + index);
                row.createCell(3).setCellValue(50 + index);
            }
            Sheet summary = workbook.createSheet("汇总");
            Row row = summary.createRow(0);
            row.createCell(0).setCellValue("总计");
            row.createCell(1).setCellValue(1368);
        });

        ParsedContent parsed = parse(content);

        assertThat(parsed.parseEngine()).isEqualTo(ParseEngine.POI);
        assertThat(parsed.sheetCount()).isEqualTo(2);
        assertThat(parsed.segments()).hasSize(2);
        assertThat(parsed.segments().get(0).locatorType()).isEqualTo(LocatorType.SHEET);
        assertThat(parsed.segments().get(0).sheetName()).isEqualTo("销售数据");
        assertThat(parsed.segments().get(0).cellRange()).isEqualTo("B2:D10");
        assertThat(parsed.segments().get(0).content()).contains("地区-1").contains("地区-9");
        assertThat(parsed.segments().get(1).sheetName()).isEqualTo("汇总");
        assertThat(parsed.segments().get(1).cellRange()).isEqualTo("A1:B1");

        ParseSummary summary = parsed.summary();
        assertThat(summary.hiddenSheetCount()).isZero();
        assertThat(summary.indexedRanges()).containsExactly("销售数据!B2:D10", "汇总!A1:B1");
        assertThat(summary.skippedRanges()).isEmpty();
        ParseSummary.SheetSummary salesSummary = summary.sheets().get(0);
        assertThat(salesSummary.sheetName()).isEqualTo("销售数据");
        assertThat(salesSummary.rowCount()).isEqualTo(9);
        assertThat(salesSummary.columnCount()).isEqualTo(3);
        assertThat(salesSummary.empty()).isFalse();
        assertThat(salesSummary.indexedRange()).isEqualTo("B2:D10");
        assertThat(salesSummary.skippedRange()).isNull();
    }

    /**
     * 空工作表只进入摘要，不生成段落、不导致失败。
     */
    @Test
    @DisplayName("空工作表标记为空且不失败")
    void parse_emptySheet_marksEmptyWithoutFailure() {
        byte[] content = workbook(workbook -> {
            Row row = workbook.createSheet("数据").createRow(0);
            row.createCell(0).setCellValue("有内容");
            workbook.createSheet("空表");
        });

        ParsedContent parsed = parse(content);

        assertThat(parsed.segments()).hasSize(1);
        assertThat(parsed.warnings()).isEmpty();
        assertThat(parsed.summary().sheets()).hasSize(2);
        ParseSummary.SheetSummary empty = parsed.summary().sheets().get(1);
        assertThat(empty.sheetName()).isEqualTo("空表");
        assertThat(empty.empty()).isTrue();
        assertThat(empty.rowCount()).isZero();
        assertThat(empty.columnCount()).isZero();
        assertThat(empty.indexedRange()).isNull();
        assertThat(empty.skippedRange()).isNull();
    }

    /**
     * 隐藏工作表整表跳过，只写数量警告，正文与摘要都不含原文。
     */
    @Test
    @DisplayName("隐藏工作表跳过且警告不含原文")
    void parse_hiddenSheet_skippedWithWhitelistWarning() {
        byte[] content = workbook(workbook -> {
            Row visible = workbook.createSheet("可见").createRow(0);
            visible.createCell(0).setCellValue("visible-content");
            Row hidden = workbook.createSheet("隐藏").createRow(0);
            hidden.createCell(0).setCellValue("hidden-marker-绝密");
            workbook.setSheetVisibility(1, SheetVisibility.HIDDEN);
            workbook.createSheet("空表");
        });

        ParsedContent parsed = parse(content);

        assertThat(parsed.segments()).hasSize(1);
        assertThat(parsed.segments().get(0).sheetName()).isEqualTo("可见");
        assertThat(parsed.warnings())
                .containsExactly(ParseWarningCodes.withCount(ParseWarningCodes.HIDDEN_SHEETS_SKIPPED, 1));
        assertThat(parsed.summary().hiddenSheetCount()).isEqualTo(1);
        assertThat(parsed.summary().sheets())
                .extracting(ParseSummary.SheetSummary::sheetName)
                .containsExactly("可见", "空表");
        assertThat(parsed.warnings().toString()).doesNotContain("hidden-marker-绝密");
        assertThat(parsed.summary().toString()).doesNotContain("hidden-marker-绝密");
    }

    /**
     * 公式只读缓存值，没有缓存值时跳过并警告，绝不计算公式。
     */
    @Test
    @DisplayName("公式只使用缓存值并警告未缓存公式")
    void parse_formulaCells_useCachedValuesWithoutEvaluation() {
        byte[] content = workbook(workbook -> {
            Sheet sheet = workbook.createSheet("公式");
            Row cached = sheet.createRow(0);
            cached.createCell(0).setCellValue(2);
            cached.createCell(1).setCellValue(3);
            cached.createCell(2).setCellFormula("A1+B1");
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            evaluator.evaluateFormulaCell(cached.getCell(2));

            Row uncached = sheet.createRow(1);
            uncached.createCell(0).setCellFormula("A1*10");
        });

        ParsedContent parsed = parse(content);

        assertThat(parsed.segments()).hasSize(1);
        assertThat(parsed.segments().get(0).content())
                .contains("2.0 | 3.0 | 5.0")
                .doesNotContain("A1+B1")
                .doesNotContain("A1*10");
        assertThat(parsed.warnings()).containsExactly(ParseWarningCodes.FORMULA_NOT_CACHED);
    }

    /**
     * 格式声明与 Spring 构造器参数保持可用。
     */
    @Test
    @DisplayName("格式与 Spring 构造器参数")
    void parse_formatAndConstructor_exposeXlsxFormat() {
        byte[] content = workbook(workbook -> workbook.createSheet("数据")
                .createRow(0)
                .createCell(0)
                .setCellValue("value"));

        assertThat(new XlsxDocumentParser().format()).isEqualTo(FileFormat.XLSX);
        XlsxDocumentParser configured =
                new XlsxDocumentParser(20, 20_000, 200, 50, 8_000_000, 5_000);
        assertThat(configured.parse(file(content), content).segments()).hasSize(1);
    }

    /**
     * 上限配置拒绝越界值，避免无界扫描或无法前进。
     */
    @Test
    @DisplayName("上限配置拒绝非法值")
    void limits_rejectInvalidValues() {
        assertThatThrownBy(() -> new XlsxDocumentParser.Limits(0, 20_000, 200, 50, 8_000_000, 5_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("band-rows");
        assertThatThrownBy(() -> new XlsxDocumentParser.Limits(20, 0, 200, 50, 8_000_000, 5_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-rows");
        assertThatThrownBy(() -> new XlsxDocumentParser.Limits(20, 20_000, 0, 50, 8_000_000, 5_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-columns");
        assertThatThrownBy(() -> new XlsxDocumentParser.Limits(20, 20_000, 200, 0, 8_000_000, 5_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-sheets");
        assertThatThrownBy(() -> new XlsxDocumentParser.Limits(20, 20_000, 200, 50, 0, 5_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-chars");
        assertThatThrownBy(() -> new XlsxDocumentParser.Limits(20, 20_000, 200, 50, 8_000_000, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-segments");
    }

    /**
     * 空字节输入返回稳定损坏码，不触发数组越界。
     */
    @Test
    @DisplayName("空字节输入返回损坏码")
    void parse_emptyContent_throwsCorrupt() {
        assertThatThrownBy(() -> parse(new byte[0]))
                .isInstanceOf(ParseException.class)
                .satisfies(exception -> assertThat(((ParseException) exception).errorCode())
                        .isEqualTo(ParseErrorCodes.PARSE_CORRUPT));
    }

    /**
     * 字符上限达到后，后续行在单元格入口即被跳过并只记录一次警告。
     */
    @Test
    @DisplayName("字符上限达到后跳过后续行")
    void parse_charLimit_stopsFollowingRowsAtCellEntry() {
        byte[] content = workbook(workbook -> {
            Sheet sheet = workbook.createSheet("数据");
            sheet.createRow(0).createCell(0).setCellValue("12345");
            sheet.createRow(1).createCell(0).setCellValue("abc");
            sheet.createRow(2).createCell(0).setCellValue("def");
        });

        ParsedContent parsed =
                parse(content, new XlsxDocumentParser.Limits(1, 20_000, 200, 50, 5, 5_000));

        assertThat(parsed.segments()).hasSize(1);
        assertThat(parsed.warnings()).containsExactly(ParseWarningCodes.CHARS_TRUNCATED);
        assertThat(parsed.summary().sheets().get(0).indexedRange()).isEqualTo("A1:A1");
        assertThat(parsed.summary().sheets().get(0).skippedRange()).isEqualTo("A2:A3");
    }

    /**
     * 单个超长单元格在 SAX 回调阶段即按文档字符上限截断，避免先撑爆堆。
     */
    @Test
    @DisplayName("单格超长文本按字符上限截断")
    void parse_singleHugeCell_truncatesAtCharLimit() {
        byte[] content = workbook(workbook -> workbook.createSheet("数据")
                .createRow(0)
                .createCell(0)
                .setCellValue("0123456789ABCDEFGHIJ"));

        ParsedContent parsed =
                parse(content, new XlsxDocumentParser.Limits(20, 20_000, 200, 50, 10, 5_000));

        assertThat(parsed.segments()).hasSize(1);
        assertThat(parsed.segments().get(0).content()).isEqualTo("0123456789");
        assertThat(parsed.warnings()).containsExactly(ParseWarningCodes.CHARS_TRUNCATED);
    }

    /**
     * 布尔值输出 TRUE/FALSE，错误值跳过且不导致解析失败。
     */
    @Test
    @DisplayName("布尔值与错误值按类型渲染")
    void parse_booleanAndErrorCells_renderWithoutFailure() {
        byte[] content = workbook(workbook -> {
            Sheet sheet = workbook.createSheet("类型");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(true);
            row.createCell(1).setCellValue(false);
            row.createCell(2).setCellErrorValue(FormulaError.DIV0.getCode());
        });

        ParsedContent parsed = parse(content);

        assertThat(parsed.segments()).hasSize(1);
        assertThat(parsed.segments().get(0).content()).isEqualTo("TRUE | FALSE").doesNotContain("#DIV/0!");
        assertThat(parsed.warnings()).isEmpty();
    }

    /**
     * 日期单元格按固定格式渲染，不依赖显示样式或 JVM 默认时区。
     */
    @Test
    @DisplayName("日期单元格使用固定格式")
    void parse_dateCell_usesFixedDateTimeFormat() {
        byte[] content = workbook(workbook -> {
            Sheet sheet = workbook.createSheet("日期");
            CellStyle style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(LocalDateTime.of(2026, 11, 23, 14, 30, 5));
            row.getCell(0).setCellStyle(style);
        });

        ParsedContent parsed = parse(content);

        assertThat(parsed.segments()).hasSize(1);
        assertThat(parsed.segments().get(0).content()).isEqualTo("2026-11-23 14:30:05");
    }

    /**
     * 合并单元格只取左上角锚点一次。
     */
    @Test
    @DisplayName("合并单元格只输出锚点一次")
    void parse_mergedCells_onlyAnchorIsEmitted() {
        byte[] content = workbook(workbook -> {
            Sheet sheet = workbook.createSheet("合并");
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("MergedValue");
        });

        ParsedContent parsed = parse(content);

        assertThat(parsed.segments()).hasSize(1);
        String text = parsed.segments().get(0).content();
        assertThat(text).contains("MergedValue");
        assertThat(text.split("MergedValue", -1).length - 1).isEqualTo(1);
    }

    /**
     * 隐藏行/列照常抽取，只有隐藏工作表才跳过。
     */
    @Test
    @DisplayName("隐藏行和列照常抽取")
    void parse_hiddenRowsAndColumns_stillExtracted() {
        byte[] content = workbook(workbook -> {
            Sheet sheet = workbook.createSheet("隐藏行列");
            Row hiddenRow = sheet.createRow(0);
            hiddenRow.createCell(0).setCellValue("hidden-row-value");
            hiddenRow.setZeroHeight(true);
            Row visibleRow = sheet.createRow(1);
            visibleRow.createCell(0).setCellValue("visible-row-value");
            visibleRow.createCell(1).setCellValue("hidden-column-value");
            sheet.setColumnHidden(1, true);
        });

        ParsedContent parsed = parse(content);

        assertThat(parsed.segments()).hasSize(1);
        assertThat(parsed.segments().get(0).content())
                .contains("hidden-row-value")
                .contains("visible-row-value")
                .contains("hidden-column-value");
        assertThat(parsed.warnings()).isEmpty();
    }

    /**
     * 单表行数上限截断，摘要记录已索引与未索引范围。
     */
    @Test
    @DisplayName("行数上限截断并记录范围")
    void parse_rowLimit_truncatesAndRecordsRanges() {
        byte[] content = workbook(workbook -> {
            Sheet sheet = workbook.createSheet("数据");
            for (int index = 0; index < 30; index++) {
                Row row = sheet.createRow(index);
                row.createCell(0).setCellValue("row-" + (index + 1));
            }
        });

        ParsedContent parsed = parse(content, new XlsxDocumentParser.Limits(10, 25, 200, 50, 8_000_000, 5_000));

        assertThat(parsed.segments()).hasSize(3);
        assertThat(parsed.segments().get(2).cellRange()).isEqualTo("A21:A25");
        assertThat(parsed.segments().get(2).content())
                .contains("row-21")
                .contains("row-25")
                .doesNotContain("row-26");
        assertThat(parsed.warnings())
                .containsExactly(ParseWarningCodes.withSuffix(ParseWarningCodes.ROWS_TRUNCATED, "数据"));
        ParseSummary.SheetSummary sheet = parsed.summary().sheets().get(0);
        assertThat(sheet.rowCount()).isEqualTo(25);
        assertThat(sheet.columnCount()).isEqualTo(1);
        assertThat(sheet.indexedRange()).isEqualTo("A1:A25");
        assertThat(sheet.skippedRange()).isEqualTo("A26:A30");
        assertThat(parsed.summary().skippedRanges()).containsExactly("数据!A26:A30");
    }

    /**
     * 单表列数上限截断，超限列不进入文本。
     */
    @Test
    @DisplayName("列数上限截断并记录范围")
    void parse_columnLimit_truncatesAndRecordsRanges() {
        byte[] content = workbook(workbook -> {
            Sheet sheet = workbook.createSheet("数据");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("A");
            row.createCell(1).setCellValue("B");
            row.createCell(2).setCellValue("C");
            row.createCell(3).setCellValue("D");
        });

        ParsedContent parsed = parse(content, new XlsxDocumentParser.Limits(20, 20_000, 2, 50, 8_000_000, 5_000));

        assertThat(parsed.segments()).hasSize(1);
        assertThat(parsed.segments().get(0).content()).isEqualTo("A | B");
        assertThat(parsed.warnings())
                .containsExactly(ParseWarningCodes.withSuffix(ParseWarningCodes.COLUMNS_TRUNCATED, "数据"));
        ParseSummary.SheetSummary sheet = parsed.summary().sheets().get(0);
        assertThat(sheet.columnCount()).isEqualTo(2);
        assertThat(sheet.indexedRange()).isEqualTo("A1:B1");
        assertThat(sheet.skippedRange()).isEqualTo("C1:D1");
    }

    /**
     * 工作表数上限只索引前 N 个，其余标记未索引范围。
     */
    @Test
    @DisplayName("工作表数上限截断并记录未索引范围")
    void parse_sheetLimit_truncatesRemainingSheets() {
        byte[] content = workbook(workbook -> {
            workbook.createSheet("一").createRow(0).createCell(0).setCellValue("one");
            workbook.createSheet("二").createRow(0).createCell(0).setCellValue("two");
        });

        ParsedContent parsed = parse(content, new XlsxDocumentParser.Limits(20, 20_000, 200, 1, 8_000_000, 5_000));

        assertThat(parsed.segments()).hasSize(1);
        assertThat(parsed.segments().get(0).sheetName()).isEqualTo("一");
        assertThat(parsed.warnings()).containsExactly(ParseWarningCodes.SHEETS_TRUNCATED);
        assertThat(parsed.summary().sheets()).hasSize(2);
        ParseSummary.SheetSummary second = parsed.summary().sheets().get(1);
        assertThat(second.indexedRange()).isNull();
        assertThat(second.skippedRange()).isEqualTo("A1:A1");
        assertThat(parsed.summary().skippedRanges()).containsExactly("二!A1:A1");
    }

    /**
     * 单文档字符上限截断，段落内容不超过上限并记录未索引行。
     */
    @Test
    @DisplayName("字符上限截断段落")
    void parse_charLimit_truncatesSegmentText() {
        byte[] content = workbook(workbook -> {
            Sheet sheet = workbook.createSheet("数据");
            for (int index = 0; index < 3; index++) {
                sheet.createRow(index).createCell(0).setCellValue("0123456789");
            }
        });

        ParsedContent parsed = parse(content, new XlsxDocumentParser.Limits(20, 20_000, 200, 50, 20, 5_000));

        assertThat(parsed.segments()).hasSize(1);
        assertThat(parsed.segments().get(0).content()).hasSize(20);
        assertThat(parsed.warnings()).containsExactly(ParseWarningCodes.CHARS_TRUNCATED);
        ParseSummary.SheetSummary sheet = parsed.summary().sheets().get(0);
        assertThat(sheet.indexedRange()).isEqualTo("A1:A2");
        assertThat(sheet.skippedRange()).isEqualTo("A3:A3");
    }

    /**
     * 单文档段落上限截断，后续行带不再生成引用。
     */
    @Test
    @DisplayName("段落上限截断后续行带")
    void parse_segmentLimit_truncatesFollowingBands() {
        byte[] content = workbook(workbook -> {
            Sheet sheet = workbook.createSheet("数据");
            sheet.createRow(0).createCell(0).setCellValue("first");
            sheet.createRow(1).createCell(0).setCellValue("second");
        });

        ParsedContent parsed = parse(content, new XlsxDocumentParser.Limits(1, 20_000, 200, 50, 8_000_000, 1));

        assertThat(parsed.segments()).hasSize(1);
        assertThat(parsed.segments().get(0).content()).isEqualTo("first");
        assertThat(parsed.warnings()).containsExactly(ParseWarningCodes.SEGMENTS_TRUNCATED);
        assertThat(parsed.summary().sheets().get(0).indexedRange()).isEqualTo("A1:A1");
        assertThat(parsed.summary().sheets().get(0).skippedRange()).isEqualTo("A2:A2");
    }

    /**
     * 默认上限下 2 万行产生 1000 个行带，且不把第 20001 行带入索引。
     */
    @Test
    @DisplayName("默认行数上限下 2 万行为 1000 个行带")
    void parse_defaultRowLimit_streamsTwentyThousandRows() {
        byte[] content = largeWorkbook(20_001);

        ParsedContent parsed = parse(content);

        assertThat(parsed.segments()).hasSize(1_000);
        assertThat(parsed.segments().get(999).cellRange()).isEqualTo("A19981:B20000");
        assertThat(parsed.segments().get(999).content()).contains("row-20000").doesNotContain("row-20001");
        assertThat(parsed.warnings())
                .containsExactly(ParseWarningCodes.withSuffix(ParseWarningCodes.ROWS_TRUNCATED, "Data"));
        ParseSummary.SheetSummary sheet = parsed.summary().sheets().get(0);
        assertThat(sheet.rowCount()).isEqualTo(20_000);
        assertThat(sheet.indexedRange()).isEqualTo("A1:B20000");
        assertThat(sheet.skippedRange()).isEqualTo("A20001:B20001");
    }

    /**
     * 非 OOXML 内容（CSV、伪装扩展名）产生稳定损坏错误码，不抛 500。
     */
    @Test
    @DisplayName("非 XLSX 内容返回稳定损坏码")
    void parse_nonXlsxContent_throwsCorrupt() {
        byte[] csv = "region,revenue\nAPAC,120\n".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> parse(csv)).isInstanceOf(ParseException.class).satisfies(exception -> {
            ParseException parseException = (ParseException) exception;
            assertThat(parseException.errorCode()).isEqualTo(ParseErrorCodes.PARSE_CORRUPT);
            assertThat(parseException.retryable()).isFalse();
        });

        byte[] fakeZip = {0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0};
        assertThatThrownBy(() -> parse(fakeZip))
                .isInstanceOf(ParseException.class)
                .satisfies(exception ->
                        assertThat(((ParseException) exception).errorCode()).isEqualTo(ParseErrorCodes.PARSE_CORRUPT));
    }

    /**
     * 加密 OOXML（OLE2 容器含 EncryptedPackage）返回加密错误码。
     */
    @Test
    @DisplayName("加密 OOXML 返回加密错误码")
    void parse_encryptedOle2_throwsEncrypted() {
        byte[] content = ole2("EncryptionInfo", "EncryptedPackage");

        assertThatThrownBy(() -> parse(content))
                .isInstanceOf(ParseException.class)
                .satisfies(exception -> {
                    ParseException parseException = (ParseException) exception;
                    assertThat(parseException.errorCode()).isEqualTo(ParseErrorCodes.PARSE_ENCRYPTED);
                    assertThat(parseException.retryable()).isFalse();
                });
    }

    /**
     * 旧版 .xls（OLE2 但无加密流）返回稳定损坏码，上传阶段仍由 M02 拒绝。
     */
    @Test
    @DisplayName("旧版 OLE2 内容返回损坏码")
    void parse_legacyOle2_throwsCorrupt() {
        byte[] content = ole2("Workbook");

        assertThatThrownBy(() -> parse(content))
                .isInstanceOf(ParseException.class)
                .satisfies(exception ->
                        assertThat(((ParseException) exception).errorCode()).isEqualTo(ParseErrorCodes.PARSE_CORRUPT));
    }

    /**
     * ZIP 条目超过资源上限时返回资源限制错误码，而不是损坏或 500。
     */
    @Test
    @DisplayName("ZIP 资源上限返回限制错误码")
    void parse_zipResourceLimit_throwsResourceLimit() {
        byte[] content = workbook(workbook -> {
            Row row = workbook.createSheet("数据").createRow(0);
            row.createCell(0).setCellValue("resource-limit");
        });
        XlsxDocumentParser parser = new XlsxDocumentParser();
        long previous = ZipSecureFile.getMaxEntrySize();
        try {
            ZipSecureFile.setMaxEntrySize(128);
            assertThatThrownBy(() -> parser.parse(file(content), content))
                    .isInstanceOf(ParseException.class)
                    .satisfies(exception -> {
                        ParseException parseException = (ParseException) exception;
                        assertThat(parseException.errorCode()).isEqualTo(ParseErrorCodes.PARSE_RESOURCE_LIMIT);
                        assertThat(parseException.retryable()).isFalse();
                    });
        } finally {
            ZipSecureFile.setMaxEntrySize(previous);
        }
    }

    /**
     * 完全无文本（只有空表）返回 KB_NO_TEXT。
     */
    @Test
    @DisplayName("完全无文本返回 KB_NO_TEXT")
    void parse_workbookWithoutText_throwsNoText() {
        byte[] content = workbook(workbook -> workbook.createSheet("空表"));

        assertThatThrownBy(() -> parse(content))
                .isInstanceOf(ParseException.class)
                .satisfies(exception ->
                        assertThat(((ParseException) exception).errorCode()).isEqualTo(ParseErrorCodes.NO_TEXT));
    }

    /**
     * 摘要与警告只含结构元数据，不泄漏单元格原文。
     */
    @Test
    @DisplayName("警告与摘要不包含单元格原文")
    void parse_warningsAndSummary_doNotContainCellText() {
        byte[] content = workbook(workbook -> {
            Sheet sheet = workbook.createSheet("数据");
            for (int index = 0; index < 3; index++) {
                sheet.createRow(index).createCell(0).setCellValue("绝密单元格文本-" + index);
            }
        });

        ParsedContent parsed = parse(content, new XlsxDocumentParser.Limits(20, 1, 200, 50, 8_000_000, 5_000));

        assertThat(parsed.warnings()).isNotEmpty();
        assertThat(parsed.warnings().toString()).doesNotContain("绝密单元格文本");
        assertThat(parsed.summary().toString()).doesNotContain("绝密单元格文本");
    }

    /**
     * 解析结果使用的文件元数据。
     *
     * @param content 文件字节
     * @return 文件快照
     */
    private static FileRecord file(byte[] content) {
        return new FileRecord(
                UUID.randomUUID(),
                "sample.xlsx",
                FileFormat.XLSX,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                content.length,
                "tenants/test/file/2026/object");
    }

    /**
     * 使用默认上限解析。
     *
     * @param content XLSX 字节
     * @return 解析结果
     */
    private static ParsedContent parse(byte[] content) {
        return new XlsxDocumentParser().parse(file(content), content);
    }

    /**
     * 使用自定义上限解析。
     *
     * @param content XLSX 字节
     * @param limits 解析上限
     * @return 解析结果
     */
    private static ParsedContent parse(byte[] content, XlsxDocumentParser.Limits limits) {
        return new XlsxDocumentParser(limits).parse(file(content), content);
    }

    /**
     * 用 POI 生成 XLSX 夹具。
     *
     * @param builder 工作簿定制逻辑
     * @return XLSX 字节
     */
    private static byte[] workbook(WorkbookBuilder builder) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            builder.build(workbook);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("failed to create xlsx fixture", ex);
        }
    }

    /**
     * 用 SXSSF 生成大工作簿夹具，避免夹具本身占用过多内存。
     *
     * @param rows 行数
     * @return XLSX 字节
     */
    private static byte[] largeWorkbook(int rows) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Data");
            for (int index = 0; index < rows; index++) {
                Row row = sheet.createRow(index);
                row.createCell(0).setCellValue("row-" + (index + 1));
                row.createCell(1).setCellValue(index + 1);
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("failed to create large xlsx fixture", ex);
        }
    }

    /**
     * 生成只含指定 OLE2 条目的容器，用于加密/旧版内容分类。
     *
     * @param entries 条目名
     * @return OLE2 字节
     */
    private static byte[] ole2(String... entries) {
        try (POIFSFileSystem fileSystem = new POIFSFileSystem();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (String entry : entries) {
                fileSystem.getRoot().createDocument(entry, new ByteArrayInputStream(new byte[] {1, 2, 3}));
            }
            fileSystem.writeFilesystem(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("failed to create ole2 fixture", ex);
        }
    }

    /**
     * 工作簿定制回调，允许抛出 IO 异常。
     */
    @FunctionalInterface
    private interface WorkbookBuilder {

        /**
         * 构建工作簿内容。
         *
         * @param workbook 工作簿
         * @throws IOException 写入失败
         */
        void build(XSSFWorkbook workbook) throws IOException;
    }
}
