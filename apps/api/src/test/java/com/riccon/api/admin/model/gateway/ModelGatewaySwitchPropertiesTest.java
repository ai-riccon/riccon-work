package com.riccon.api.admin.model.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.admin.model.gateway.config.ModelGatewaySwitchProperties;
import com.riccon.api.admin.model.gateway.domain.ModelGatewayRoutingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 网关开关配置与路由模式解析的单元行为。
 *
 * <p>非法环境覆盖必须在启动期失败（ADR-0021 §10.5）；空白值表示“使用数据库”，不能被当成 true/false 或
 * 非法路由模式。这些行为不依赖 Spring 容器，用纯单元测试覆盖。
 */
class ModelGatewaySwitchPropertiesTest {

    /** 空白覆盖不生效，也不抛出异常。 */
    @Test
    @DisplayName("空白覆盖表示使用数据库")
    void overrides_blankValues_meanDatabase() {
        ModelGatewaySwitchProperties properties = new ModelGatewaySwitchProperties();
        properties.setRoutingMode("  ");
        properties.setFailoverEnabled("\t");

        assertThat(properties.routingModeOverride()).isNull();
        assertThat(properties.failoverEnabledOverride()).isNull();
        properties.afterPropertiesSet();
    }

    /** 覆盖值会去除首尾空白并严格解析。 */
    @Test
    @DisplayName("覆盖值去除空白并严格解析")
    void overrides_trimAndParseStrictly() {
        ModelGatewaySwitchProperties properties = new ModelGatewaySwitchProperties();
        properties.setRoutingMode(" baseline ");
        properties.setFailoverEnabled(" TRUE ");

        assertThat(properties.routingModeOverride()).isEqualTo("baseline");
        assertThat(properties.failoverEnabledOverride()).isTrue();
        properties.afterPropertiesSet();
    }

    /** 非法路由模式在启动期失败。 */
    @Test
    @DisplayName("非法路由模式启动期失败")
    void afterPropertiesSet_rejectsInvalidRoutingMode() {
        ModelGatewaySwitchProperties properties = new ModelGatewaySwitchProperties();
        properties.setRoutingMode("ROUND_ROBIN");

        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ROUTED or BASELINE");
    }

    /** 非法降级开关在启动期失败。 */
    @Test
    @DisplayName("非法降级开关启动期失败")
    void afterPropertiesSet_rejectsInvalidFailover() {
        ModelGatewaySwitchProperties properties = new ModelGatewaySwitchProperties();
        properties.setFailoverEnabled("yes");

        assertThatThrownBy(properties::afterPropertiesSet)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be true or false");
    }

    /** 路由模式解析兼容空白与大小写，并拒绝未知值。 */
    @Test
    @DisplayName("路由模式解析兼容空白、大小写并拒绝未知值")
    void routingMode_parseIsTolerantButStrict() {
        assertThat(ModelGatewayRoutingMode.parse(null)).isEqualTo(ModelGatewayRoutingMode.ROUTED);
        assertThat(ModelGatewayRoutingMode.parse("  ")).isEqualTo(ModelGatewayRoutingMode.ROUTED);
        assertThat(ModelGatewayRoutingMode.parse(" baseline ")).isEqualTo(ModelGatewayRoutingMode.BASELINE);
        assertThatThrownBy(() -> ModelGatewayRoutingMode.parse("OTHER")).isInstanceOf(IllegalArgumentException.class);
    }
}
