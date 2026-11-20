package com.riccon.api.quota.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Asia/Shanghai 配额窗口计算。
 *
 * <p>窗口起点与重置时间必须由同一个 {@link ZonedDateTime} 派生：起点转 {@link Instant}（UTC）写入
 * {@code quota_usage.window_start}，重置时间保留 +08:00 偏移返回前端。禁止手工加 8 小时或依赖机器时区
 * （ADR-0022 第 1.6 节）。
 */
public final class QuotaWindows {

    /** 团队约定时区；合同 {@code project.timezone}。 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 对外时间格式：固定带秒与偏移，例如 {@code 2026-12-01T00:00:00+08:00}。 */
    private static final DateTimeFormatter OFFSET_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    /** 工具类禁止实例化。 */
    private QuotaWindows() {}

    /**
     * 计算当前瞬时对应的日/月窗口，顺序固定为 DAILY、MONTHLY。
     *
     * @param now 当前 UTC 瞬时
     * @return 两个窗口边界
     */
    public static List<QuotaWindowBoundary> boundariesAt(Instant now) {
        return List.of(dailyAt(now), monthlyAt(now));
    }

    /**
     * 计算 Asia/Shanghai 自然日窗口。
     *
     * @param now 当前 UTC 瞬时
     * @return 日窗口边界
     */
    public static QuotaWindowBoundary dailyAt(Instant now) {
        ZonedDateTime dayStart = now.atZone(ZONE).toLocalDate().atStartOfDay(ZONE);
        return boundary(QuotaWindow.DAILY, dayStart, dayStart.plusDays(1));
    }

    /**
     * 计算 Asia/Shanghai 自然月窗口。
     *
     * @param now 当前 UTC 瞬时
     * @return 月窗口边界
     */
    public static QuotaWindowBoundary monthlyAt(Instant now) {
        ZonedDateTime monthStart =
                now.atZone(ZONE).toLocalDate().withDayOfMonth(1).atStartOfDay(ZONE);
        return boundary(QuotaWindow.MONTHLY, monthStart, monthStart.plusMonths(1));
    }

    /**
     * 格式化带偏移时间，供 API 与审计 details 共用。
     *
     * @param value 带偏移时间
     * @return 固定秒精度的 ISO 8601 字符串
     */
    public static String format(Instant value) {
        return OFFSET_FORMAT.format(value.atZone(ZONE));
    }

    /**
     * 组装窗口边界。
     *
     * @param window 窗口类型
     * @param start 窗口起点
     * @param reset 下一次重置时间
     * @return 窗口边界
     */
    private static QuotaWindowBoundary boundary(QuotaWindow window, ZonedDateTime start, ZonedDateTime reset) {
        return new QuotaWindowBoundary(window, start.toInstant(), reset.toOffsetDateTime());
    }
}
