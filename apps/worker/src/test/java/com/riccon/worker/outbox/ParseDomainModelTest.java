package com.riccon.worker.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.platform.domain.chat.LocatorType;
import com.riccon.platform.domain.knowledge.ParseEngine;
import com.riccon.worker.parse.domain.BoundingBox;
import com.riccon.worker.parse.domain.ParseErrorCodes;
import com.riccon.worker.parse.domain.ParseSummary;
import com.riccon.worker.parse.domain.ParseWarningCodes;
import com.riccon.worker.parse.domain.ParsedChunk;
import com.riccon.worker.parse.domain.ParsedContent;
import com.riccon.worker.parse.domain.ParsedSegment;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 解析领域模型（ADR-0023 第 3.4/4.3 节）。
 *
 * <p>M05-B1 只提供模型与常量；B4/B5 生成、B7 落库、B3 通过 API 返回。测试锁定：旧构造器保持
 * 反序列化/编译兼容、bbox 与置信度边界、警告码格式不含空格、摘要防御性复制与 JSON 友好。
 */
class ParseDomainModelTest {

    /** bbox 不接受负坐标或空区域。 */
    @Test
    @DisplayName("bbox 校验坐标与尺寸")
    void boundingBox_rejectsInvalidValues() {
        assertThat(new BoundingBox(0, 0, 10, 20)).isEqualTo(new BoundingBox(0, 0, 10, 20));
        assertThatThrownBy(() -> new BoundingBox(-1, 0, 10, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("origin");
        assertThatThrownBy(() -> new BoundingBox(0, 0, 0, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size");
        assertThatThrownBy(() -> new BoundingBox(0, 0, 10, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size");
    }

    /** 旧构造器默认无 bbox/置信度，新构造器保留 OCR 字段并校验置信度范围。 */
    @Test
    @DisplayName("解析段/分块兼容旧构造器并校验置信度")
    void parsedSegments_keepLegacyConstructorAndValidateConfidence() {
        ParsedSegment legacy = new ParsedSegment("text", LocatorType.PAGE, 1, null, null, null);
        assertThat(legacy.bbox()).isNull();
        assertThat(legacy.ocrConfidence()).isNull();

        ParsedSegment ocr = new ParsedSegment(
                "text", LocatorType.PAGE, 1, null, null, null, new BoundingBox(1, 2, 3, 4), new BigDecimal("0.780"));
        assertThat(ocr.bbox()).isEqualTo(new BoundingBox(1, 2, 3, 4));
        assertThat(ocr.ocrConfidence()).isEqualByComparingTo("0.780");

        assertThatThrownBy(() ->
                        new ParsedSegment("text", LocatorType.PAGE, 1, null, null, null, null, new BigDecimal("1.5")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence");

        ParsedChunk chunk = new ParsedChunk(
                "text", LocatorType.PAGE, 1, null, null, null, 1, new BoundingBox(1, 2, 3, 4), new BigDecimal("0.500"));
        assertThat(chunk.bbox()).isNotNull();
        assertThat(chunk.tokenCount()).isEqualTo(1);

        ParsedChunk legacyChunk = new ParsedChunk("text", LocatorType.PARAGRAPH, null, null, null, 1, 1);
        assertThat(legacyChunk.bbox()).isNull();
        assertThat(legacyChunk.ocrConfidence()).isNull();
    }

    /** 旧 ParsedContent 构造器默认 NONE/空警告/空摘要；新构造器防御性复制。 */
    @Test
    @DisplayName("ParsedContent 兼容旧构造器并携带引擎/警告/摘要")
    void parsedContent_carriesEngineWarningsAndSummary() {
        ParsedContent legacy = new ParsedContent(List.of(), null, null, "v1");
        assertThat(legacy.parseEngine()).isEqualTo(ParseEngine.NONE);
        assertThat(legacy.warnings()).isEmpty();
        assertThat(legacy.summary()).isNull();

        ParseSummary summary = new ParseSummary(
                List.of(new ParseSummary.SheetSummary("Sheet1", 20, 6, false, "A1:F20", null)),
                new ParseSummary.OcrSummary(1, new BigDecimal("0.900"), List.of(), List.of(), false),
                1,
                List.of("Sheet1!A1:F20"),
                List.of("Sheet9!A1:F20"));
        ParsedContent content = new ParsedContent(
                List.of(new ParsedSegment("text", LocatorType.PAGE, 1, null, null, null)),
                1,
                1,
                "xlsx-v1",
                ParseEngine.POI,
                List.of(ParseWarningCodes.withCount(ParseWarningCodes.HIDDEN_SHEETS_SKIPPED, 1)),
                summary);
        assertThat(content.parseEngine()).isEqualTo(ParseEngine.POI);
        assertThat(content.warnings())
                .containsExactly(ParseWarningCodes.withCount(ParseWarningCodes.HIDDEN_SHEETS_SKIPPED, 1));
        assertThat(content.summary()).isEqualTo(summary);
    }

    /** 警告码格式固定为 CODE:参数，页号列表无空格，不写原文。 */
    @Test
    @DisplayName("解析警告码格式稳定")
    void warningCodes_useStableFormat() {
        assertThat(ParseWarningCodes.withSuffix(ParseWarningCodes.ROWS_TRUNCATED, "Sheet1"))
                .isEqualTo("ROWS_TRUNCATED:Sheet1");
        assertThat(ParseWarningCodes.withCount(ParseWarningCodes.HIDDEN_SHEETS_SKIPPED, 3))
                .isEqualTo("HIDDEN_SHEETS_SKIPPED:3");
        assertThat(ParseWarningCodes.withPages(ParseWarningCodes.FAILED_PAGES, List.of(2, 5)))
                .isEqualTo("FAILED_PAGES:[2,5]");
        assertThatThrownBy(() -> ParseWarningCodes.withPages(ParseWarningCodes.FAILED_PAGES, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ParseWarningCodes.withPages(ParseWarningCodes.FAILED_PAGES, List.of(0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ParseWarningCodes.withSuffix(ParseWarningCodes.CHARS_TRUNCATED, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** OCR 摘要置信度必须在 0–1，页集合防御性复制。 */
    @Test
    @DisplayName("OCR 摘要校验置信度并复制页集合")
    void ocrSummary_validatesConfidence() {
        assertThatThrownBy(() -> new ParseSummary.OcrSummary(1, new BigDecimal("1.2"), List.of(), List.of(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence");

        List<Integer> pages = new java.util.ArrayList<>(List.of(2));
        ParseSummary.OcrSummary summary =
                new ParseSummary.OcrSummary(2, new BigDecimal("0.700"), pages, List.of(3), false);
        pages.add(9);
        assertThat(summary.lowConfidencePages()).containsExactly(2);
        assertThat(summary.failedPages()).containsExactly(3);
    }

    /** 解析失败错误码稳定，供 B4/B5 直接复用。 */
    @Test
    @DisplayName("解析错误码稳定")
    void parseErrorCodes_areStable() {
        assertThat(ParseErrorCodes.PARSE_ENCRYPTED).isEqualTo("KB_PARSE_ENCRYPTED");
        assertThat(ParseErrorCodes.PARSE_CORRUPT).isEqualTo("KB_PARSE_CORRUPT");
        assertThat(ParseErrorCodes.PARSE_RESOURCE_LIMIT).isEqualTo("KB_PARSE_RESOURCE_LIMIT");
        assertThat(ParseErrorCodes.PARSE_TIMEOUT).isEqualTo("KB_PARSE_TIMEOUT");
        assertThat(ParseErrorCodes.NO_TEXT).isEqualTo("KB_NO_TEXT");
        assertThat(ParseErrorCodes.OCR_DISABLED).isEqualTo("KB_OCR_DISABLED");
        assertThat(ParseErrorCodes.OCR_LANG_UNAVAILABLE).isEqualTo("KB_OCR_LANG_UNAVAILABLE");
        assertThat(ParseErrorCodes.OCR_TIMEOUT).isEqualTo("KB_OCR_TIMEOUT");
        assertThat(ParseErrorCodes.OCR_FAILED).isEqualTo("KB_OCR_FAILED");
        assertThat(ParseErrorCodes.OCR_UNAVAILABLE).isEqualTo("KB_OCR_UNAVAILABLE");
    }
}
