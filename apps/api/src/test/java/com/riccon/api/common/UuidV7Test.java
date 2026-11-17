package com.riccon.api.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** UUIDv7 生成与时间戳解析。 */
class UuidV7Test {

    /** 生成值必须满足版本 7 与 RFC 变体。 */
    @Test
    void generateCreatesVersion7WithRfcVariant() {
        UUID uuid = UuidV7.generate();
        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.variant()).isEqualTo(2);
    }

    /** 指定时间生成时，时间戳必须完全一致。 */
    @Test
    void generateWithTimestampEncodesProvidedInstant() {
        Instant instant = Instant.parse("2026-11-17T12:00:00.123Z");
        UUID uuid = UuidV7.generate(instant);
        assertThat(UuidV7.timestampOf(uuid)).isEqualTo(instant);
    }

    /** 不带时间生成时，时间戳应接近当前时间。 */
    @Test
    void generateUsesCurrentTime() {
        Instant before = Instant.now();
        UUID uuid = UuidV7.generate();
        Instant encoded = UuidV7.timestampOf(uuid);
        assertThat(encoded).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
    }

    /** 非版本 7 的 UUID 必须拒绝解析，避免把 v4 当时间有序 ID。 */
    @Test
    void timestampOfRejectsNonVersion7() {
        UUID version4 = UUID.randomUUID();
        assertThatThrownBy(() -> UuidV7.timestampOf(version4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not version 7");
    }
}
