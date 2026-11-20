package com.riccon.worker.model.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 模型网关开关环境变量覆盖配置测试（ADR-0021 §10.5）。
 *
 * <p>空值表示回落数据库开关；非空值必须严格可解析，非法值在启动期失败，避免带病运行。
 */
class ModelGatewaySwitchPropertiesTest {

    /** 空值与空白值都表示“未设置覆盖”。 */
    @Test
    void constructor_nullOrBlank_overridesAreNull() {
        ModelGatewaySwitchProperties properties = new ModelGatewaySwitchProperties(null, null);
        assertThat(properties.routingModeOverride()).isNull();
        assertThat(properties.failoverEnabledOverride()).isNull();

        ModelGatewaySwitchProperties blank = new ModelGatewaySwitchProperties("  ", "\t");
        assertThat(blank.routingModeOverride()).isNull();
        assertThat(blank.failoverEnabledOverride()).isNull();
    }

    /** 合法覆盖值去除空白并按枚举/布尔解析。 */
    @Test
    void constructor_validOverrides_trimAndParse() {
        ModelGatewaySwitchProperties routed = new ModelGatewaySwitchProperties(" ROUTED ", " TRUE ");
        assertThat(routed.routingModeOverride()).isEqualTo("ROUTED");
        assertThat(routed.failoverEnabledOverride()).isTrue();

        ModelGatewaySwitchProperties baseline = new ModelGatewaySwitchProperties("baseline", "false");
        assertThat(baseline.routingModeOverride()).isEqualTo("baseline");
        assertThat(baseline.failoverEnabledOverride()).isFalse();
    }

    /** 非法路由模式必须在构造期失败。 */
    @Test
    void constructor_invalidRoutingMode_fails() {
        assertThatThrownBy(() -> new ModelGatewaySwitchProperties("SOMETHING", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("routingMode");
    }

    /** 非法布尔值必须在构造期失败。 */
    @Test
    void constructor_invalidFailover_fails() {
        assertThatThrownBy(() -> new ModelGatewaySwitchProperties("ROUTED", "yes"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failover-enabled");
    }
}
