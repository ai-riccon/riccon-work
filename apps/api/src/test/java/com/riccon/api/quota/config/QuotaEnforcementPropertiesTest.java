package com.riccon.api.quota.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 配额执行开关的部署覆盖解析。
 */
class QuotaEnforcementPropertiesTest {

    /**
     * 空配置表示使用数据库开关。
     */
    @Test
    void enabledOverride_blank_returnsEmpty() {
        QuotaEnforcementProperties properties = new QuotaEnforcementProperties();
        properties.setQuotaEnforcementEnabled("");

        assertThat(properties.enabledOverride()).isEmpty();
    }

    /**
     * true/false 被严格解析。
     */
    @Test
    void enabledOverride_boolean_parses() {
        QuotaEnforcementProperties properties = new QuotaEnforcementProperties();
        properties.setQuotaEnforcementEnabled("TRUE");
        assertThat(properties.enabledOverride()).contains(true);
        properties.setQuotaEnforcementEnabled("false");
        assertThat(properties.enabledOverride()).contains(false);
    }

    /**
     * 非法值启动失败。
     */
    @Test
    void afterPropertiesSet_invalid_fails() {
        QuotaEnforcementProperties properties = new QuotaEnforcementProperties();
        properties.setQuotaEnforcementEnabled("maybe");

        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("true or false");
    }
}
