package com.riccon.api.outbox.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Outbox 事件字段校验与 payload 防御性复制。 */
class OutboxEventTest {

    /** 合法事件复制 payload，调用方修改原 Map 不影响事件。 */
    @Test
    void constructor_copiesPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attempt", 1);
        OutboxEvent event = new OutboxEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "riccon.file.parse.requested.v1",
                1,
                "key-12345678",
                payload,
                Instant.now());
        payload.put("attempt", 2);
        assertThat(event.payload()).containsEntry("attempt", 1);
    }

    /** 版本、事件名与幂等键的边界校验。 */
    @Test
    void constructor_invalidFields_throws() {
        assertThatThrownBy(() -> new OutboxEvent(
                        UUID.randomUUID(), UUID.randomUUID(), " ", 1, "key-12345678", Map.of(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType");
        assertThatThrownBy(() -> new OutboxEvent(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "riccon.file.parse.requested.v1",
                        0,
                        "key-12345678",
                        Map.of(),
                        Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventVersion");
        assertThatThrownBy(() -> new OutboxEvent(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "riccon.file.parse.requested.v1",
                        1,
                        "x".repeat(201),
                        Map.of(),
                        Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyKey");
    }
}
