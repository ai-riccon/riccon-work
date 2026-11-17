package com.riccon.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** SHA-256 输出格式。 */
class Sha256Test {

    /** 已知向量必须匹配小写十六进制。 */
    @Test
    void hex_knownVectors_match() {
        assertThat(Sha256.hex("")).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(Sha256.hex("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(Sha256.hex("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isEqualTo(Sha256.hex("abc"));
    }
}
