package com.riccon.api.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.knowledge.domain.KnowledgeDocumentRecord;
import com.riccon.api.knowledge.domain.KnowledgeParseOcrSummary;
import com.riccon.api.knowledge.domain.KnowledgeParseSheetSummary;
import com.riccon.api.knowledge.domain.KnowledgeParseSummary;
import com.riccon.api.knowledge.domain.KnowledgeSourceType;
import com.riccon.api.knowledge.dto.KnowledgeDocumentResponse;
import com.riccon.platform.domain.knowledge.KnowledgeDocumentStatus;
import com.riccon.platform.domain.knowledge.KnowledgeVisibility;
import com.riccon.platform.domain.knowledge.ParseEngine;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * API DTO 与 worker 持久化摘要的 shape 对齐（REV-TASK-0167-001 F-001）。
 *
 * <p>worker 以 OpenAPI 契约视图写 {@code parse_summary}；本测试锁定 API 能反序列化该 JSON，
 * 并通过 {@link KnowledgeDocumentResponse} 原样返回 {@code parseEngine/warnings/parseSummary}。
 */
class KnowledgeDocumentParseShapeTest {

    /** JSON 解析。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** API 能读取 worker 写入的契约摘要，且不要求内部字段。 */
    @Test
    @DisplayName("KnowledgeParseSummary_deserializesWorkerContractJson")
    void knowledgeParseSummary_deserializesWorkerContractJson() throws Exception {
        // 与 worker ParseSummaryJson 序列化结果一致；不含 sheetName/rowCount/skippedRange。
        String workerJson = """
                {
                  "sheets": [
                    {"name": "Budget", "cellRange": "A1:B2", "empty": false,
                     "indexed": true, "indexedRange": "A1:B2"}
                  ],
                  "hiddenSheetCount": 0,
                  "ocr": {"averageConfidence": 0.9, "lowConfidencePages": [], "failedPages": []}
                }
                """;

        KnowledgeParseSummary summary = JSON.readValue(workerJson, KnowledgeParseSummary.class);

        assertThat(summary.sheets()).hasSize(1);
        assertThat(summary.sheets().get(0).name()).isEqualTo("Budget");
        assertThat(summary.sheets().get(0).cellRange()).isEqualTo("A1:B2");
        assertThat(summary.sheets().get(0).indexed()).isTrue();
        assertThat(summary.sheets().get(0).indexedRange()).isEqualTo("A1:B2");
        assertThat(summary.hiddenSheetCount()).isZero();
        assertThat(summary.ocr()).isNotNull();
        assertThat(summary.ocr().averageConfidence()).isEqualTo(0.9);
    }

    /** DTO 映射与 JSON 字段名保持一致。 */
    @Test
    @DisplayName("KnowledgeDocumentResponse_mapsParseMetadata")
    void knowledgeDocumentResponse_mapsParseMetadata() throws Exception {
        KnowledgeDocumentRecord record = new KnowledgeDocumentRecord(
                UUID.randomUUID(),
                "预算文档",
                KnowledgeSourceType.UPLOAD,
                null,
                "checksum",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                128,
                KnowledgeDocumentStatus.INDEXED,
                null,
                null,
                Instant.parse("2026-11-23T04:00:00Z"),
                null,
                UUID.randomUUID(),
                Instant.parse("2026-11-23T03:00:00Z"),
                KnowledgeVisibility.TENANT,
                null,
                2,
                ParseEngine.POI,
                List.of("HIDDEN_SHEETS_SKIPPED:1"),
                new KnowledgeParseSummary(
                        List.of(new KnowledgeParseSheetSummary("Budget", "A1:B2", false, true, "A1:B2")),
                        1,
                        new KnowledgeParseOcrSummary(0.9, List.of(), List.of())),
                Instant.parse("2027-05-22T03:00:00Z"),
                false,
                0,
                null);

        KnowledgeDocumentResponse response = KnowledgeDocumentResponse.from(record, 180);
        assertThat(response.parseEngine()).isEqualTo(ParseEngine.POI);
        assertThat(response.warnings()).containsExactly("HIDDEN_SHEETS_SKIPPED:1");
        assertThat(response.parseSummary()).isEqualTo(record.parseSummary());

        JsonNode json = JSON.readTree(JSON.writeValueAsString(response));
        assertThat(json.path("parseEngine").asText()).isEqualTo("POI");
        assertThat(json.path("warnings").get(0).asText()).isEqualTo("HIDDEN_SHEETS_SKIPPED:1");
        JsonNode sheet = json.path("parseSummary").path("sheets").get(0);
        assertThat(sheet.path("name").asText()).isEqualTo("Budget");
        assertThat(sheet.path("cellRange").asText()).isEqualTo("A1:B2");
        assertThat(sheet.path("indexed").asBoolean()).isTrue();
        assertThat(sheet.path("indexedRange").asText()).isEqualTo("A1:B2");
        assertThat(sheet.has("sheetName")).isFalse();
    }
}
