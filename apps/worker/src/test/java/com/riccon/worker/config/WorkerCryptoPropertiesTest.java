package com.riccon.worker.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link CryptoProperties} 的脱敏输出。
 *
 * <p>record 默认 {@code toString()} 会拼接主密钥/上一把主密钥原值；一旦被日志、异常或调试输出打印
 * 即违反 ADR-0006 §5。这里固定覆盖后的输出只包含是否注入与主密钥标识。
 */
class WorkerCryptoPropertiesTest {

    /** 已注入的密钥材料只显示占位，不出现原值。 */
    @Test
    void toString_masksSecrets() {
        CryptoProperties properties = new CryptoProperties("master-value", "previous-value", "mk-test", true);

        assertThat(properties.toString())
                .contains("[redacted]")
                .contains("mk-test")
                .contains("rejectTestKeys=true")
                .doesNotContain("master-value")
                .doesNotContain("previous-value");
    }

    /** 未注入的密钥材料显示 {@code [not set]}，不出现占位歧义。 */
    @Test
    void toString_marksUnsetSecrets() {
        CryptoProperties properties = new CryptoProperties("", "", "mk-1", false);

        assertThat(properties.toString()).contains("[not set]").doesNotContain("[redacted]");
    }
}
