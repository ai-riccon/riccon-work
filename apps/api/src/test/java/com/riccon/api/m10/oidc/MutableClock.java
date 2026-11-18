package com.riccon.api.m10.oidc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 可前进的测试时钟；禁止测试依赖真实时间或 {@code Thread.sleep}（编码规范第 10 节）。
 */
final class MutableClock extends Clock {

    /** 当前时间。 */
    private Instant instant;

    /** 时区固定 UTC。 */
    private final ZoneId zone = ZoneOffset.UTC;

    /**
     * 以指定时间创建时钟。
     *
     * @param instant 起始时间
     */
    MutableClock(Instant instant) {
        this.instant = instant;
    }

    /**
     * 前进时间。
     *
     * @param duration 时长
     */
    void advance(Duration duration) {
        instant = instant.plus(duration);
    }

    /**
     * 返回时区。
     *
     * @return UTC
     */
    @Override
    public ZoneId getZone() {
        return zone;
    }

    /**
     * 切换时区（保持时间不变）。
     *
     * @param zone 目标时区
     * @return 新时钟
     */
    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant, zone);
    }

    /**
     * 返回当前时间。
     *
     * @return 当前时间
     */
    @Override
    public Instant instant() {
        return instant;
    }
}
