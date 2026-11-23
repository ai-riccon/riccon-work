package com.riccon.worker.index.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.worker.parse.domain.ParseSummary;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code knowledge_document.parse_summary} 契约视图（REV-TASK-0167-001 F-001）。
 *
 * <p>锁定字段名与 OpenAPI {@code KnowledgeParseSummary} 一致：{@code name/cellRange/empty/indexed/
 * indexedRange}；内部 {@code sheetName/rowCount/columnCount/skippedRange/indexedRanges} 不得
 * 出现在持久化 JSON 中，否则 API 反序列化会失败或产生额外字段。
 */
class ParseSummaryJsonTest {

    /** JSON 序列化。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 空摘要返回 null，不写空对象。 */
    @Test
    @DisplayName("from_null_returnsNull")
    void from_null_returnsNull() {
        assertThat(ParseSummaryJson.from(null)).isNull();
    }

    /** 工作表字段名、整表范围与索引标记与 API 契约一致。 */
    @Test
    @DisplayName("from_sheetSummary_usesContractShape")
    void from_sheetSummary_usesContractShape() {
        ParseSummary summary = new ParseSummary(
                List.of(
                        // 已索引 B2:D10 + 未索引 E2:E10 → 整表范围 B2:E10。
                        new ParseSummary.SheetSummary("销售数据", 9, 3, false, "B2:D10", "E2:E10"),
                        // 空表：无范围、未索引。
                        new ParseSummary.SheetSummary("空表", 0, 0, true, null, null),
                        // 整表被上限跳过：只有未索引范围。
                        new ParseSummary.SheetSummary("二", 1, 1, false, null, "A1:A1")),
                null,
                2,
                List.of("销售数据!B2:D10"),
                List.of("销售数据!E2:E10", "二!A1:A1"));

        ParseSummaryJson view = ParseSummaryJson.from(summary);

        assertThat(view).isNotNull();
        assertThat(view.hiddenSheetCount()).isEqualTo(2);
        assertThat(view.sheets()).hasSize(3);
        assertThat(view.sheets().get(0)).isEqualTo(new ParseSummaryJson.Sheet("销售数据", "B2:E10", false, true, "B2:D10"));
        assertThat(view.sheets().get(1).cellRange()).isNull();
        assertThat(view.sheets().get(1).indexed()).isFalse();
        assertThat(view.sheets().get(2).cellRange()).isEqualTo("A1:A1");
        assertThat(view.sheets().get(2).indexed()).isFalse();
        assertThat(view.ocr()).isNull();
    }

    /** 序列化 JSON 只含契约字段，内部字段名不得泄漏。 */
    @Test
    @DisplayName("serialize_usesOnlyContractFieldNames")
    void serialize_usesOnlyContractFieldNames() throws Exception {
        ParseSummary.SheetSummary sheet = new ParseSummary.SheetSummary("Sheet1", 20, 6, false, "A1:F20", "G1:H20");
        ParseSummary summary =
                new ParseSummary(List.of(sheet), null, 0, List.of("Sheet1!A1:F20"), List.of("Sheet1!G1:H20"));

        String json = objectMapper.writeValueAsString(ParseSummaryJson.from(summary));
        JsonNode root = objectMapper.readTree(json);

        assertThat(root.path("hiddenSheetCount").asInt()).isZero();
        JsonNode jsonSheet = root.path("sheets").get(0);
        assertThat(jsonSheet.path("name").asText()).isEqualTo("Sheet1");
        assertThat(jsonSheet.path("cellRange").asText()).isEqualTo("A1:H20");
        assertThat(jsonSheet.path("empty").asBoolean()).isFalse();
        assertThat(jsonSheet.path("indexed").asBoolean()).isTrue();
        assertThat(jsonSheet.path("indexedRange").asText()).isEqualTo("A1:F20");
        assertThat(jsonSheet.has("sheetName")).isFalse();
        assertThat(jsonSheet.has("rowCount")).isFalse();
        assertThat(jsonSheet.has("columnCount")).isFalse();
        assertThat(jsonSheet.has("skippedRange")).isFalse();
        assertThat(root.has("indexedRanges")).isFalse();
        assertThat(root.has("skippedRanges")).isFalse();
    }

    /** OCR 摘要按契约输出平均置信度与页集合；无块时平均值为 0。 */
    @Test
    @DisplayName("from_ocrSummary_mapsContractFields")
    void from_ocrSummary_mapsContractFields() throws Exception {
        ParseSummary withConfidence = new ParseSummary(
                List.of(),
                new ParseSummary.OcrSummary(2, new BigDecimal("0.700"), List.of(2), List.of(3), false),
                null,
                List.of(),
                List.of());
        ParseSummaryJson.Ocr ocr = ParseSummaryJson.from(withConfidence).ocr();
        assertThat(ocr).isNotNull();
        assertThat(ocr.averageConfidence()).isEqualTo(0.700);
        assertThat(ocr.lowConfidencePages()).containsExactly(2);
        assertThat(ocr.failedPages()).containsExactly(3);

        ParseSummary withoutBlocks = new ParseSummary(
                List.of(),
                new ParseSummary.OcrSummary(1, null, List.of(), List.of(1), true),
                null,
                List.of(),
                List.of());
        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(ParseSummaryJson.from(withoutBlocks)));
        assertThat(json.path("ocr").path("averageConfidence").asDouble()).isZero();
        assertThat(json.path("ocr").path("failedPages").get(0).asInt()).isEqualTo(1);
    }

    /** 范围合并不支持跨列/跨行时保持单调；非法格式退回非空一方。 */
    @Test
    @DisplayName("cellRange_union_mergesRanges")
    void cellRange_union_mergesRanges() {
        assertThat(ParseSummary.unionRange("A1:B1", "C1:D1")).isEqualTo("A1:D1");
        assertThat(ParseSummary.unionRange("A1:A3", "A4:A10")).isEqualTo("A1:A10");
        assertThat(ParseSummary.unionRange(null, "B2:C3")).isEqualTo("B2:C3");
        assertThat(ParseSummary.unionRange("B2:C3", null)).isEqualTo("B2:C3");
        assertThat(ParseSummary.unionRange("bad", "A1:A2")).isEqualTo("bad");
    }
}
