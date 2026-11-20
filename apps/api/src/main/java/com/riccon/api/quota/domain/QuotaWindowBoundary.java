package com.riccon.api.quota.domain;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 一次准入需要使用的窗口边界。
 *
 * <p>{@code windowStart} 是写入 {@code quota_usage.window_start} 的 UTC 瞬时；{@code resetsAt}
 * 是对外展示的重置时间（带 +08:00 偏移），两者都由同一个 Asia/Shanghai 计算产生，禁止分开推算。
 *
 * @param window 窗口类型
 * @param windowStart 窗口起点（UTC）
 * @param resetsAt 下一次重置的绝对时间（Asia/Shanghai，带偏移）
 */
public record QuotaWindowBoundary(QuotaWindow window, Instant windowStart, OffsetDateTime resetsAt) {

    /** 保证窗口对象完整，避免空值进入 SQL 或响应。 */
    public QuotaWindowBoundary {
        Objects.requireNonNull(window, "window must not be null");
        Objects.requireNonNull(windowStart, "windowStart must not be null");
        Objects.requireNonNull(resetsAt, "resetsAt must not be null");
    }
}
