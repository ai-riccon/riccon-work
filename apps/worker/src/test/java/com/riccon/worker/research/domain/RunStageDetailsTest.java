package com.riccon.worker.research.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.platform.domain.research.ApprovalStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 阶段详情与 OpenAPI {@code RunStageDetails} 对齐，并容忍未知键。 */
class RunStageDetailsTest {

    /** 测试 JSON。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 已知字段完整往返。 */
    @Test
    void toJsonAndFromJson_roundTripsKnownFields() throws Exception {
        UUID invocationId = UUID.randomUUID();
        UUID toolId = UUID.randomUUID();
        UUID approvalId = UUID.randomUUID();
        UUID decidedBy = UUID.randomUUID();
        RunStageDetails details = new RunStageDetails(
                "市场调研",
                "a".repeat(64),
                3,
                invocationId,
                toolId,
                approvalId,
                MAPPER.readTree("{\"text\":\"market-data\"}"),
                "sha256=" + "b".repeat(64) + ",bytes=11",
                "c".repeat(64),
                42,
                "APPROVE",
                decidedBy,
                "2026-11-18T03:00:00Z");

        String json = details.toJson(MAPPER);
        RunStageDetails parsed = RunStageDetails.fromJson(MAPPER, json);

        assertThat(parsed.invocationId()).isEqualTo(invocationId);
        assertThat(parsed.toolId()).isEqualTo(toolId);
        assertThat(parsed.approvalId()).isEqualTo(approvalId);
        assertThat(parsed.paramsPreview().path("text").asText()).isEqualTo("market-data");
        assertThat(parsed.resultSummary()).isEqualTo(details.resultSummary());
        assertThat(parsed.hitCount()).isEqualTo(3);
        assertThat(parsed.generatedChars()).isEqualTo(42);
        assertThat(parsed.approvalDecision()).isEqualTo("APPROVE");
        assertThat(parsed.approvalDecidedBy()).isEqualTo(decidedBy);
        assertThat(parsed.approvalDecidedAt()).isEqualTo("2026-11-18T03:00:00Z");
    }

    /** 未知键、非法 UUID 与额外字段不得让解析失败。 */
    @Test
    void fromJson_ignoresUnknownKeysAndInvalidUuid() throws Exception {
        String json = """
                {
                  "paramsPreview": {"text": "visible"},
                  "resultSummary": "ok",
                  "invocationId": "not-a-uuid",
                  "futureField": {"nested": true},
                  "extra": 1
                }
                """;

        RunStageDetails parsed = RunStageDetails.fromJson(MAPPER, json);

        assertThat(parsed).isNotNull();
        assertThat(parsed.invocationId()).isNull();
        assertThat(parsed.paramsPreview().path("text").asText()).isEqualTo("visible");
        assertThat(parsed.resultSummary()).isEqualTo("ok");
    }

    /** 结果摘要写入前截断，避免超过列与契约上限。 */
    @Test
    void toJson_truncatesResultSummary() throws Exception {
        RunStageDetails details = new RunStageDetails(
                null, null, null, null, null, null, null, "x".repeat(900), null, null, null, null, null);

        String json = details.toJson(MAPPER);
        RunStageDetails parsed = RunStageDetails.fromJson(MAPPER, json);

        assertThat(parsed.resultSummary()).hasSize(RunStageDetails.MAX_RESULT_SUMMARY_LENGTH);
    }

    /** 空、非对象 JSON 返回 null，阶段时间线按无详情处理。 */
    @Test
    void fromJson_blankOrNonObject_returnsNull() throws Exception {
        assertThat(RunStageDetails.fromJson(MAPPER, null)).isNull();
        assertThat(RunStageDetails.fromJson(MAPPER, " ")).isNull();
        assertThat(RunStageDetails.fromJson(MAPPER, "[]")).isNull();
        assertThat(RunStageDetails.fromJson(MAPPER, "{}")).isNotNull();
    }

    /** 来源片段单行化并截断，不落完整原文。 */
    @Test
    void sourceSnippet_truncatesAndCollapsesWhitespace() {
        String content = "市场增长\n\n第二段\t包含重复空白 " + "增".repeat(260);

        String snippet = RunStageDetails.sourceSnippet(content);

        assertThat(snippet).hasSize(RunStageDetails.MAX_SOURCE_SNIPPET_LENGTH);
        assertThat(snippet).startsWith("市场增长 第二段 包含重复空白 增");
        assertThat(snippet).endsWith("...");
        assertThat(snippet).doesNotContain("\n", "\t");
        assertThat(snippet).isNotEqualTo(content);
    }

    /** 空内容返回空片段，调用方仍可写入空字符串。 */
    @Test
    void sourceSnippet_nullOrBlank_returnsEmpty() {
        assertThat(RunStageDetails.sourceSnippet(null)).isEmpty();
        assertThat(RunStageDetails.sourceSnippet(" \n\t ")).isEmpty();
    }

    /** 审批决定合并保留未知键，重复合并不改变审批时间。 */
    @Test
    void withApprovalDecision_mergesUnknownKeysAndIsIdempotent() throws Exception {
        UUID decidedBy = UUID.randomUUID();
        ApprovalRecord approval = new ApprovalRecord(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "APPROVAL",
                2,
                ApprovalStatus.REJECTED,
                UUID.randomUUID(),
                "market_data",
                "{\"text\":\"market-data\"}",
                null,
                null,
                null,
                UUID.randomUUID(),
                decidedBy,
                Instant.parse("2026-11-18T03:00:00Z"));
        String existing = "{\"paramsPreview\":{\"text\":\"market-data\"},\"futureField\":{\"n\":1}}";

        String merged = RunStageDetails.withApprovalDecision(MAPPER, existing, approval);
        String mergedAgain = RunStageDetails.withApprovalDecision(MAPPER, merged, approval);

        JsonNode node = MAPPER.readTree(merged);
        assertThat(node.path("paramsPreview").path("text").asText()).isEqualTo("market-data");
        assertThat(node.path("futureField").path("n").asInt()).isEqualTo(1);
        assertThat(node.path("approvalDecision").asText()).isEqualTo("REJECT");
        assertThat(node.path("approvalDecidedBy").asText()).isEqualTo(decidedBy.toString());
        assertThat(node.path("approvalDecidedAt").asText()).isEqualTo("2026-11-18T03:00:00Z");
        assertThat(MAPPER.readTree(mergedAgain).path("approvalDecidedAt").asText())
                .isEqualTo("2026-11-18T03:00:00Z");
    }

    /** 历史脏详情不阻塞审批时间线收尾。 */
    @Test
    void withApprovalDecision_invalidExistingJson_startsWithFreshObject() throws Exception {
        ApprovalRecord approval = new ApprovalRecord(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "APPROVAL",
                1,
                ApprovalStatus.APPROVED,
                UUID.randomUUID(),
                "market_data",
                "{}",
                null,
                null,
                null,
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.parse("2026-11-18T03:00:00Z"));

        JsonNode node = MAPPER.readTree(RunStageDetails.withApprovalDecision(MAPPER, "{not-json", approval));

        assertThat(node.path("approvalDecision").asText()).isEqualTo("APPROVE");
        assertThat(node.path("approvalDecidedAt").asText()).isEqualTo("2026-11-18T03:00:00Z");
    }
}
