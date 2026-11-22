package com.riccon.worker.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Worker Actuator 来源 CIDR 规则（OPS-M04-02a 的配置层）。
 *
 * <p>空值只允许 loopback；显式配置只允许列出的网段；非法 CIDR 启动失败。
 */
class OpsAllowedCidrsTest {

    /** 空配置只允许 loopback。 */
    @Test
    @DisplayName("空配置仅允许 loopback")
    void emptyConfigAllowsOnlyLoopback() {
        SecurityConfig.AllowedCidrs cidrs = SecurityConfig.AllowedCidrs.parse("");
        assertThat(cidrs.permits("127.0.0.1")).isTrue();
        assertThat(cidrs.permits("::1")).isTrue();
        assertThat(cidrs.permits("10.0.0.5")).isFalse();
    }

    /** 显式配置只允许列出的网段，不额外放行 loopback。 */
    @Test
    @DisplayName("显式 CIDR 生效且不放行 loopback")
    void explicitCidrReplacesLoopbackDefault() {
        SecurityConfig.AllowedCidrs cidrs = SecurityConfig.AllowedCidrs.parse("10.99.0.0/16, 192.168.1.0/24");
        assertThat(cidrs.permits("10.99.1.2")).isTrue();
        assertThat(cidrs.permits("192.168.1.10")).isTrue();
        assertThat(cidrs.permits("10.100.0.1")).isFalse();
        assertThat(cidrs.permits("127.0.0.1")).isFalse();
    }

    /** IPv6 CIDR 可用。 */
    @Test
    @DisplayName("IPv6 CIDR 生效")
    void ipv6CidrIsSupported() {
        SecurityConfig.AllowedCidrs cidrs = SecurityConfig.AllowedCidrs.parse("::1/128");
        assertThat(cidrs.permits("::1")).isTrue();
        assertThat(cidrs.permits("::2")).isFalse();
    }

    /** 非法 CIDR 启动失败。 */
    @Test
    @DisplayName("非法 CIDR 启动失败")
    void invalidCidrFails() {
        assertThatThrownBy(() -> SecurityConfig.AllowedCidrs.parse("10.0.0.0/33"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SecurityConfig.AllowedCidrs.parse("not-a-cidr"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
