package com.riccon.worker.model.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Worker 计费币种配置校验。
 *
 * <p>与 API 的 {@code BillingProperties} 一致：接受小写输入并规范化为三位大写；
 * 非法值在启动时失败，避免成本快照写入非 ISO 4217 币种。
 */
class ModelInvocationBillingSettingsTest {

    /**
     * 币种规范化为大写。
     */
    @Test
    @DisplayName("billingSettings_normalizesCurrency")
    void billingSettings_normalizesCurrency() {
        ModelInvocationService.BillingSettings settings = new ModelInvocationService.BillingSettings();
        settings.setCurrency(" cny ");
        settings.afterPropertiesSet();
        assertThat(settings.currency()).isEqualTo("CNY");
    }

    /**
     * 非 ISO 4217 三位代码启动失败。
     */
    @Test
    @DisplayName("billingSettings_rejectsInvalidCurrency")
    void billingSettings_rejectsInvalidCurrency() {
        ModelInvocationService.BillingSettings settings = new ModelInvocationService.BillingSettings();
        settings.setCurrency("CN");
        assertThatThrownBy(settings::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("riccon.billing.currency");
    }
}
