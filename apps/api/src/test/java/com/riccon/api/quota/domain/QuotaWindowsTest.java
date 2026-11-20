package com.riccon.api.quota.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Asia/Shanghai 窗口边界与重置时间。
 *
 * <p>固定时钟取值覆盖“11 月最后一毫秒”和“12 月第一毫秒”，防止依赖机器时区或手工 +8 小时。
 */
class QuotaWindowsTest {

    /**
     * 日/月窗口在 11 月最后一毫秒仍属于 11 月。
     */
    @Test
    void boundariesAt_novemberLastMillisecond_staysInNovember() {
        Instant now = OffsetDateTime.parse("2026-11-30T23:59:59.999+08:00").toInstant();

        List<QuotaWindowBoundary> boundaries = QuotaWindows.boundariesAt(now);

        assertThat(boundaries).hasSize(2);
        assertThat(boundaries.get(0).window()).isEqualTo(QuotaWindow.DAILY);
        assertThat(boundaries.get(0).windowStart()).isEqualTo(Instant.parse("2026-11-29T16:00:00Z"));
        assertThat(boundaries.get(0).resetsAt()).isEqualTo(OffsetDateTime.parse("2026-12-01T00:00:00+08:00"));
        assertThat(boundaries.get(1).window()).isEqualTo(QuotaWindow.MONTHLY);
        assertThat(boundaries.get(1).windowStart()).isEqualTo(Instant.parse("2026-10-31T16:00:00Z"));
        assertThat(boundaries.get(1).resetsAt()).isEqualTo(OffsetDateTime.parse("2026-12-01T00:00:00+08:00"));
    }

    /**
     * 12 月第一毫秒同时切日窗口与月窗口。
     */
    @Test
    void boundariesAt_decemberFirstMillisecond_switchesDayAndMonth() {
        Instant now = OffsetDateTime.parse("2026-12-01T00:00:00+08:00").toInstant();

        List<QuotaWindowBoundary> boundaries = QuotaWindows.boundariesAt(now);

        assertThat(boundaries.get(0).windowStart()).isEqualTo(Instant.parse("2026-11-30T16:00:00Z"));
        assertThat(boundaries.get(0).resetsAt()).isEqualTo(OffsetDateTime.parse("2026-12-02T00:00:00+08:00"));
        assertThat(boundaries.get(1).windowStart()).isEqualTo(Instant.parse("2026-11-30T16:00:00Z"));
        assertThat(boundaries.get(1).resetsAt()).isEqualTo(OffsetDateTime.parse("2027-01-01T00:00:00+08:00"));
    }

    /**
     * 格式化输出带秒与 +08:00 偏移。
     */
    @Test
    void format_alwaysUsesShanghaiOffsetAndSeconds() {
        Instant instant = OffsetDateTime.parse("2026-11-30T16:00:00Z").toInstant();

        assertThat(QuotaWindows.format(instant)).isEqualTo("2026-12-01T00:00:00+08:00");
    }

    /**
     * 单独计算日窗口时不包含月窗口语义。
     */
    @Test
    void dailyAt_midMonth_usesLocalMidnight() {
        QuotaWindowBoundary daily = QuotaWindows.dailyAt(
                OffsetDateTime.parse("2026-06-15T12:00:00+08:00").toInstant());

        assertThat(daily.windowStart()).isEqualTo(Instant.parse("2026-06-14T16:00:00Z"));
        assertThat(daily.resetsAt()).isEqualTo(OffsetDateTime.parse("2026-06-16T00:00:00+08:00"));
    }
}
