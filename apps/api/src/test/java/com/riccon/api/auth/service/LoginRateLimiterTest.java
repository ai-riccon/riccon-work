package com.riccon.api.auth.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.error.RateLimitedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** 登录失败滑动窗口限流。 */
class LoginRateLimiterTest {

    /** 10 次失败后第 11 次必须被拒绝。 */
    @Test
    void checkAllowedRejectsAfterTenFailures() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-17T00:00:00Z"));
        LoginRateLimiter limiter = new LoginRateLimiter(clock);
        for (int attempt = 0; attempt < 10; attempt++) {
            assertThatCode(() -> limiter.checkAllowed("10.0.0.1", "tenant-a")).doesNotThrowAnyException();
            limiter.recordFailure("10.0.0.1", "tenant-a");
        }
        assertThatThrownBy(() -> limiter.checkAllowed("10.0.0.1", "tenant-a")).isInstanceOf(RateLimitedException.class);
    }

    /** 成功登录清空失败记录。 */
    @Test
    void resetAllowsAttemptsAgain() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-17T00:00:00Z"));
        LoginRateLimiter limiter = new LoginRateLimiter(clock);
        for (int attempt = 0; attempt < 10; attempt++) {
            limiter.recordFailure("10.0.0.1", "tenant-a");
        }
        limiter.reset("10.0.0.1", "tenant-a");
        assertThatCode(() -> limiter.checkAllowed("10.0.0.1", "tenant-a")).doesNotThrowAnyException();
    }

    /** 窗口外的失败记录会被清理。 */
    @Test
    void expiredFailuresArePruned() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-17T00:00:00Z"));
        LoginRateLimiter limiter = new LoginRateLimiter(clock);
        for (int attempt = 0; attempt < 10; attempt++) {
            limiter.recordFailure("10.0.0.1", "tenant-a");
        }
        clock.advance(Duration.ofSeconds(61));
        assertThatCode(() -> limiter.checkAllowed("10.0.0.1", "tenant-a")).doesNotThrowAnyException();
    }

    /** 不同 IP 或租户的失败互不影响。 */
    @Test
    void keysAreIsolated() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-17T00:00:00Z"));
        LoginRateLimiter limiter = new LoginRateLimiter(clock);
        for (int attempt = 0; attempt < 10; attempt++) {
            limiter.recordFailure("10.0.0.1", "tenant-a");
        }
        assertThatCode(() -> limiter.checkAllowed("10.0.0.1", "tenant-b")).doesNotThrowAnyException();
        assertThatCode(() -> limiter.checkAllowed("10.0.0.2", "tenant-a")).doesNotThrowAnyException();
    }

    /** 可手动推进的测试时钟。 */
    private static final class MutableClock extends Clock {

        /** 当前时间。 */
        private Instant instant;

        /**
         * 创建测试时钟。
         *
         * @param instant 初始时间
         */
        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        /**
         * 推进时间。
         *
         * @param duration 推进量
         */
        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        /** 返回 UTC 时区。 */
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        /**
         * 切换时区；测试只使用 UTC。
         *
         * @param zone 目标时区
         * @return 同一时钟
         */
        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        /** 返回当前时间。 */
        @Override
        public Instant instant() {
            return instant;
        }
    }
}
