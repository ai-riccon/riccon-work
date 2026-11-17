package com.riccon.api.auth.service;

import com.riccon.api.error.RateLimitedException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 登录失败限流。
 *
 * <p>ADR-0013 第 3 节要求按 {@code IP + tenantCode} 做进程内滑动窗口（默认 10 次/分钟）。M02 单实例足够；
 * 多实例部署在 M04/M09 改为共享限流并写 ADR。只统计失败尝试，成功登录会清空该键，避免正常用户被历史失败
 * 拖累；账号锁定逻辑由 {@code user_credential.failed_attempts} 独立承担。
 */
@Component
public class LoginRateLimiter {

    /** 默认窗口内最大失败次数。 */
    static final int DEFAULT_MAX_FAILURES = 10;

    /** 默认滑动窗口长度。 */
    static final Duration DEFAULT_WINDOW = Duration.ofMinutes(1);

    /** 统计键到失败时间戳的映射；键为 {@code ip|tenantCode}。 */
    private final ConcurrentHashMap<String, Deque<Long>> failures = new ConcurrentHashMap<>();

    /** 可注入时钟，保证测试确定性。 */
    private final Clock clock;

    /** 窗口内最大失败次数。 */
    private final int maxFailures;

    /** 滑动窗口长度。 */
    private final Duration window;

    /**
     * 使用默认参数创建限流器。
     *
     * @param clock 可注入时钟
     */
    @org.springframework.beans.factory.annotation.Autowired
    public LoginRateLimiter(Clock clock) {
        this(clock, DEFAULT_MAX_FAILURES, DEFAULT_WINDOW);
    }

    /**
     * 使用显式参数创建限流器，供单元测试缩小窗口。
     *
     * @param clock 可注入时钟
     * @param maxFailures 窗口内最大失败次数
     * @param window 滑动窗口长度
     */
    LoginRateLimiter(Clock clock, int maxFailures, Duration window) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.maxFailures = maxFailures;
        this.window = Objects.requireNonNull(window, "window must not be null");
    }

    /**
     * 检查是否允许继续尝试登录。
     *
     * @param clientIp 客户端 IP
     * @param tenantCode 登录租户代码
     * @throws RateLimitedException 窗口内失败次数已达到上限
     */
    public void checkAllowed(String clientIp, String tenantCode) {
        String key = key(clientIp, tenantCode);
        if (failureCount(key) >= maxFailures) {
            throw new RateLimitedException();
        }
    }

    /**
     * 记录一次失败的登录尝试。
     *
     * @param clientIp 客户端 IP
     * @param tenantCode 登录租户代码
     */
    public void recordFailure(String clientIp, String tenantCode) {
        String key = key(clientIp, tenantCode);
        long now = clock.millis();
        Deque<Long> timestamps = failures.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (timestamps) {
            prune(timestamps, now);
            timestamps.addLast(now);
        }
    }

    /**
     * 登录成功后清空该键的失败记录。
     *
     * @param clientIp 客户端 IP
     * @param tenantCode 登录租户代码
     */
    public void reset(String clientIp, String tenantCode) {
        failures.remove(key(clientIp, tenantCode));
    }

    /**
     * 统计窗口内失败次数，并顺带清理过期记录。
     *
     * @param key 限流键
     * @return 窗口内失败次数
     */
    private int failureCount(String key) {
        Deque<Long> timestamps = failures.get(key);
        if (timestamps == null) {
            return 0;
        }
        synchronized (timestamps) {
            long now = clock.millis();
            prune(timestamps, now);
            if (timestamps.isEmpty()) {
                failures.remove(key, timestamps);
                return 0;
            }
            return timestamps.size();
        }
    }

    /**
     * 删除窗口外的失败时间戳。
     *
     * @param timestamps 失败时间戳队列
     * @param now 当前毫秒时间
     */
    private void prune(Deque<Long> timestamps, long now) {
        long cutoff = now - window.toMillis();
        while (!timestamps.isEmpty() && timestamps.peekFirst() <= cutoff) {
            timestamps.removeFirst();
        }
    }

    /**
     * 组合限流键；租户代码允许为空（登录体校验前）。
     *
     * @param clientIp 客户端 IP
     * @param tenantCode 租户代码
     * @return 限流键
     */
    private String key(String clientIp, String tenantCode) {
        return (clientIp == null ? "unknown" : clientIp) + "|" + (tenantCode == null ? "" : tenantCode);
    }
}
