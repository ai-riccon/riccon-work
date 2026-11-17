package com.riccon.worker.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** UUIDv7 与 BINARY(16) 编解码测试。 */
class UuidBinaryTest {

    /** 生成值必须是 RFC 9562 的 UUIDv7 variant。 */
    @Test
    void newV7_hasVersion7AndRfcVariant() {
        UUID uuid = UuidBinary.newV7();
        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.variant()).isEqualTo(2);
    }

    /** 编解码必须无损。 */
    @Test
    void toBytesAndFromBytes_roundTrip() {
        UUID uuid = UuidBinary.newV7();
        byte[] bytes = UuidBinary.toBytes(uuid);
        assertThat(bytes).hasSize(16);
        assertThat(UuidBinary.fromBytes(bytes)).isEqualTo(uuid);
    }

    /** 非 16 字节输入必须失败，避免读错列或截断。 */
    @Test
    void fromBytes_invalidLength_throws() {
        assertThatThrownBy(() -> UuidBinary.fromBytes(new byte[15]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("16 bytes");
        assertThatThrownBy(() -> UuidBinary.fromBytes(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
