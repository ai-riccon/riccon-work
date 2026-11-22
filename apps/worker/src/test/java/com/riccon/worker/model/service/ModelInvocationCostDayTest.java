package com.riccon.worker.model.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 成本日桶边界（IT-COST-103 的日历侧）。
 *
 * <p>固定瞬时覆盖日/月末边界：{@code Asia/Shanghai} 23:59:59.999 属于当日，
 * 次日 00:00:00 属于次日；使用 ZonedDateTime 而不是手工加 8 小时。
 */
class ModelInvocationCostDayTest {

    /**
     * IT-COST-103：日边界与月末/月末次日。
     */
    @Test
    @DisplayName("IT-COST-103：cost_day 使用 Asia/Shanghai 自然日")
    void costDay_usesShanghaiCalendarBoundary() {
        assertThat(ModelInvocationService.costDay(
                        Instant.parse("2026-11-30T15:59:59.999Z").toEpochMilli()))
                .isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(ModelInvocationService.costDay(
                        Instant.parse("2026-11-30T16:00:00Z").toEpochMilli()))
                .isEqualTo(LocalDate.of(2026, 12, 1));
    }

    /**
     * IT-COST-103：跨月与普通日内的日桶不漂移。
     */
    @Test
    @DisplayName("IT-COST-103：普通日与月初边界")
    void costDay_keepsMonthAndDayBoundaries() {
        assertThat(ModelInvocationService.costDay(
                        Instant.parse("2026-12-31T15:59:59Z").toEpochMilli()))
                .isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(ModelInvocationService.costDay(
                        Instant.parse("2026-12-31T16:00:00Z").toEpochMilli()))
                .isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(ModelInvocationService.costDay(
                        Instant.parse("2026-11-21T02:30:00Z").toEpochMilli()))
                .isEqualTo(LocalDate.of(2026, 11, 21));
    }
}
