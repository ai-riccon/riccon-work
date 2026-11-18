package com.riccon.api.m10.oidc;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.riccon.api.error.RateLimitedException;
import com.riccon.api.oidc.service.OidcProviderRateLimiter;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** providers 端点限流：60 次/分钟，按 IP 计数。 */
class OidcProviderRateLimiterTest {

    /** 第 61 次请求触发 429。 */
    @Test
    void checkAllowed_sixtyRequests_blocksSixtyFirst() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-19T00:00:00Z"));
        OidcProviderRateLimiter limiter = new OidcProviderRateLimiter(clock, 60, Duration.ofMinutes(1));

        for (int index = 0; index < 60; index++) {
            limiter.checkAllowed("127.0.0.1");
        }

        assertThatThrownBy(() -> limiter.checkAllowed("127.0.0.1")).isInstanceOf(RateLimitedException.class);
    }

    /** 窗口过后重新允许；不同 IP 独立计数。 */
    @Test
    void checkAllowed_afterWindow_allowsAgainAndIsolatesIps() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-19T00:00:00Z"));
        OidcProviderRateLimiter limiter = new OidcProviderRateLimiter(clock, 2, Duration.ofMinutes(1));

        limiter.checkAllowed("10.0.0.1");
        limiter.checkAllowed("10.0.0.1");
        limiter.checkAllowed("10.0.0.2");
        assertThatThrownBy(() -> limiter.checkAllowed("10.0.0.1")).isInstanceOf(RateLimitedException.class);

        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        limiter.checkAllowed("10.0.0.1");
    }

    /** 测试/排障可清空计数。 */
    @Test
    void resetAll_clearsCounters() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-19T00:00:00Z"));
        OidcProviderRateLimiter limiter = new OidcProviderRateLimiter(clock, 1, Duration.ofMinutes(1));
        limiter.checkAllowed("127.0.0.1");

        limiter.resetAll();

        limiter.checkAllowed("127.0.0.1");
    }
}
