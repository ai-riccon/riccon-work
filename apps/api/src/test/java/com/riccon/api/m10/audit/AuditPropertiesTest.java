package com.riccon.api.m10.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.audit.service.AuditProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 审计配置校验单元测试（不启动 Spring）。
 *
 * <p>保留天数低于 180、TTL 非正或超过 7 天、行数上限越界都必须让启动失败（ADR-0020 第 1/2 节）。
 */
class AuditPropertiesTest {

    /** 默认配置合法并暴露预期值。 */
    @Test
    @DisplayName("默认配置合法：保留 180 天、TTL 24 小时、行数上限 10000")
    void defaults_areValid() {
        AuditProperties properties = new AuditProperties();
        properties.afterPropertiesSet();
        assertThat(properties.getRetentionDays()).isEqualTo(180);
        assertThat(properties.getExport().getTtl()).isEqualTo(Duration.ofHours(24));
        assertThat(properties.getExport().getRowLimit()).isEqualTo(10_000);
    }

    /** 保留天数低于 180 启动失败。 */
    @Test
    @DisplayName("保留天数低于 180 失败")
    void retentionDays_belowMinimumFails() {
        AuditProperties properties = new AuditProperties();
        properties.setRetentionDays(179);
        assertThatThrownBy(properties::afterPropertiesSet).isInstanceOf(IllegalArgumentException.class);
    }

    /** TTL 非正或超过 7 天启动失败。 */
    @Test
    @DisplayName("TTL 非正或超过 7 天失败")
    void exportTtl_invalidFails() {
        AuditProperties zero = new AuditProperties();
        zero.getExport().setTtl(Duration.ZERO);
        assertThatThrownBy(zero::afterPropertiesSet).isInstanceOf(IllegalArgumentException.class);

        AuditProperties negative = new AuditProperties();
        negative.getExport().setTtl(Duration.ofSeconds(-1));
        assertThatThrownBy(negative::afterPropertiesSet).isInstanceOf(IllegalArgumentException.class);

        AuditProperties tooLong = new AuditProperties();
        tooLong.getExport().setTtl(Duration.ofDays(8));
        assertThatThrownBy(tooLong::afterPropertiesSet).isInstanceOf(IllegalArgumentException.class);
    }

    /** 行数上限越界启动失败。 */
    @Test
    @DisplayName("行数上限 0 或超过 50000 失败")
    void exportRowLimit_invalidFails() {
        AuditProperties zero = new AuditProperties();
        zero.getExport().setRowLimit(0);
        assertThatThrownBy(zero::afterPropertiesSet).isInstanceOf(IllegalArgumentException.class);

        AuditProperties tooLarge = new AuditProperties();
        tooLarge.getExport().setRowLimit(50_001);
        assertThatThrownBy(tooLarge::afterPropertiesSet).isInstanceOf(IllegalArgumentException.class);
    }
}
