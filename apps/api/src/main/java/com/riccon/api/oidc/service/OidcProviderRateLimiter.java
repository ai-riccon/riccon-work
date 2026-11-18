package com.riccon.api.oidc.service;

import com.riccon.api.error.RateLimitedException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 企业登录可用性端点限流。
 *
 * <p>ADR-0019 第 4 节要求 {@code GET /api/v1/auth/oidc/providers} 按客户端 IP 做进程内 60 次/分钟轻量限流，
 * 超限返回 429 {@code RICCON-RATE-429}；**不按 tenantCode 计数**，避免用响应差异枚举租户。
 * 多实例共享限流在 M09 接入。
 */
@Component
public class OidcProviderRateLimiter {

    /** 默认窗口内最大请求数。 */
    static final int DEFAULT_MAX_REQUESTS = 60;

    /** 默认窗口长度。 */
    static final Duration DEFAULT_WINDOW = Duration.ofMinutes(1);

    /** IP 到请求时间戳的滑动窗口。 */
    private final ConcurrentHashMap<String, Deque<Long>> requests = new ConcurrentHashMap<>();

    /** 可注入时钟。 */
    private final Clock clock;

    /** 窗口内最大请求数。 */
    private final int maxRequests;

    /** 窗口长度。 */
    private final Duration window;

    /**
     * 使用默认参数创建限流器。
     *
     * @param clock 可注入时钟
     */
    @Autowired
    public OidcProviderRateLimiter(Clock clock) {
        this(clock, DEFAULT_MAX_REQUESTS, DEFAULT_WINDOW);
    }

    /**
     * 使用显式参数创建限流器，供单元测试缩小窗口。
     *
     * @param clock 可注入时钟
     * @param maxRequests 窗口内最大请求数
     * @param window 窗口长度
     */
    public OidcProviderRateLimiter(Clock clock, int maxRequests, Duration window) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.window = Objects.requireNonNull(window, "window must not be null");
        if (maxRequests <= 0) {
            throw new IllegalArgumentException("maxRequests must be positive");
        }
        this.maxRequests = maxRequests;
    }

    /**
     * 检查并记录一次请求。
     *
     * @param clientIp 客户端 IP；为空时按 {@code unknown} 计数
     * @throws RateLimitedException 窗口内请求数达到上限
     */
    public void checkAllowed(String clientIp) {
        String key = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp;
        Deque<Long> timestamps = requests.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        long now = clock.millis();
        synchronized (timestamps) {
            long cutoff = now - window.toMillis();
            while (!timestamps.isEmpty() && timestamps.peekFirst() <= cutoff) {
                timestamps.removeFirst();
            }
            if (timestamps.size() >= maxRequests) {
                throw new RateLimitedException();
            }
            timestamps.addLast(now);
        }
    }

    /**
     * 清空所有计数；仅供测试与运维排障，不参与业务判定。
     */
    public void resetAll() {
        requests.clear();
    }
}
