package com.riccon.worker.run.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.worker.run.domain.ClaimedOutboxEvent;
import com.riccon.worker.run.domain.EventProcessingException;
import com.riccon.worker.run.domain.WorkerErrorCodes;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Outbox payload 解析与字段校验测试。 */
class EventPayloadParserTest {

    /** 解析器。 */
    private final EventPayloadParser parser = new EventPayloadParser(new ObjectMapper());

    /** 合法 JSON 对象正常解析。 */
    @Test
    void parse_validObject_returnsNode() {
        JsonNode node = parser.parse(event("{\"fileId\":\"abc\"}"));
        assertThat(node.path("fileId").asText()).isEqualTo("abc");
    }

    /** 非法 JSON 与数组都视为不可重试 payload 错误。 */
    @Test
    void parse_invalidPayload_throws() {
        assertThatThrownBy(() -> parser.parse(event("{not-json")))
                .isInstanceOf(EventProcessingException.class)
                .satisfies(ex -> assertThat(((EventProcessingException) ex).errorCode())
                        .isEqualTo(WorkerErrorCodes.EVENT_INVALID));
        assertThatThrownBy(() -> parser.parse(event("[]")))
                .isInstanceOf(EventProcessingException.class)
                .hasMessageContaining("JSON object");
    }

    /** 必填 UUID 缺失、非字符串或非法时失败。 */
    @Test
    void requiredUuid_invalid_throws() {
        JsonNode empty = parser.parse(event("{}"));
        assertThatThrownBy(() -> parser.requiredUuid(empty, "fileId"))
                .isInstanceOf(EventProcessingException.class)
                .hasMessageContaining("must be a UUID string");

        JsonNode number = parser.parse(event("{\"fileId\":1}"));
        assertThatThrownBy(() -> parser.requiredUuid(number, "fileId")).isInstanceOf(EventProcessingException.class);

        JsonNode invalid = parser.parse(event("{\"fileId\":\"not-a-uuid\"}"));
        assertThatThrownBy(() -> parser.requiredUuid(invalid, "fileId"))
                .isInstanceOf(EventProcessingException.class)
                .hasMessageContaining("not a valid UUID");
    }

    /** 可选 UUID 允许缺失与 null，但拒绝非字符串/非法值。 */
    @Test
    void optionalUuid_variants() {
        JsonNode missing = parser.parse(event("{}"));
        assertThat(parser.optionalUuid(missing, "fileId")).isNull();

        JsonNode explicitNull = parser.parse(event("{\"fileId\":null}"));
        assertThat(parser.optionalUuid(explicitNull, "fileId")).isNull();

        JsonNode text = parser.parse(event("{\"fileId\":\"" + UUID.randomUUID() + "\"}"));
        assertThat(parser.optionalUuid(text, "fileId")).isNotNull();

        JsonNode number = parser.parse(event("{\"fileId\":1}"));
        assertThatThrownBy(() -> parser.optionalUuid(number, "fileId")).isInstanceOf(EventProcessingException.class);

        JsonNode invalid = parser.parse(event("{\"fileId\":\"bad\"}"));
        assertThatThrownBy(() -> parser.optionalUuid(invalid, "fileId")).isInstanceOf(EventProcessingException.class);
    }

    /** 必填正整数缺失、为零或类型错误时失败。 */
    @Test
    void requiredPositiveInt_invalid_throws() {
        JsonNode missing = parser.parse(event("{}"));
        assertThatThrownBy(() -> parser.requiredPositiveInt(missing, "attempt"))
                .isInstanceOf(EventProcessingException.class);

        JsonNode zero = parser.parse(event("{\"attempt\":0}"));
        assertThatThrownBy(() -> parser.requiredPositiveInt(zero, "attempt"))
                .isInstanceOf(EventProcessingException.class);

        JsonNode text = parser.parse(event("{\"attempt\":\"1\"}"));
        assertThatThrownBy(() -> parser.requiredPositiveInt(text, "attempt"))
                .isInstanceOf(EventProcessingException.class);

        JsonNode valid = parser.parse(event("{\"attempt\":2}"));
        assertThat(parser.requiredPositiveInt(valid, "attempt")).isEqualTo(2);
    }

    /**
     * 构造测试事件。
     *
     * @param payload payload JSON
     * @return 事件
     */
    private static ClaimedOutboxEvent event(String payload) {
        return new ClaimedOutboxEvent(
                UUID.randomUUID(),
                "tenant-a",
                UUID.randomUUID(),
                "riccon.test.v1",
                1,
                "key",
                payload,
                1,
                Instant.now(),
                Instant.now().plusSeconds(30));
    }
}
