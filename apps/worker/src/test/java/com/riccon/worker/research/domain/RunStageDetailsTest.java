package com.riccon.worker.research.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
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
                42);

        String json = details.toJson(MAPPER);
        RunStageDetails parsed = RunStageDetails.fromJson(MAPPER, json);

        assertThat(parsed.invocationId()).isEqualTo(invocationId);
        assertThat(parsed.toolId()).isEqualTo(toolId);
        assertThat(parsed.approvalId()).isEqualTo(approvalId);
        assertThat(parsed.paramsPreview().path("text").asText()).isEqualTo("market-data");
        assertThat(parsed.resultSummary()).isEqualTo(details.resultSummary());
        assertThat(parsed.hitCount()).isEqualTo(3);
        assertThat(parsed.generatedChars()).isEqualTo(42);
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
        RunStageDetails details =
                new RunStageDetails(null, null, null, null, null, null, null, "x".repeat(900), null, null);

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
}
