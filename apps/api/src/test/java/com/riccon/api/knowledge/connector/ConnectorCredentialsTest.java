package com.riccon.api.knowledge.connector;

import static org.assertj.core.api.Assertions.assertThat;

import com.riccon.api.knowledge.connector.service.ConnectorCredentials;
import org.junit.jupiter.api.Test;

/**
 * 凭据掩码：短 key 不暴露前后缀。
 */
class ConnectorCredentialsTest {

    @Test
    void maskedHint_hidesShortKeysEntirely() {
        assertThat(ConnectorCredentials.maskedHint(null)).isEqualTo("****");
        assertThat(ConnectorCredentials.maskedHint("short")).isEqualTo("****");
        assertThat(ConnectorCredentials.maskedHint("12345678901")).isEqualTo("****");
        assertThat(ConnectorCredentials.maskedHint("123456789012")).isEqualTo("1234…012");
        assertThat(ConnectorCredentials.maskedHint("AKIA1234567890ABCD")).isEqualTo("AKIA…BCD");
    }
}
