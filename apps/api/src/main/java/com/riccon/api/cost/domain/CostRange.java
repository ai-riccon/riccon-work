package com.riccon.api.cost.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * 成本查询时间范围（{@code Asia/Shanghai} 自然日，含端点）。
 *
 * <p>默认最近 30 天，最大 180 天；上限与保留期一致，防止跨年无界扫描（ADR-0022 §2.4/§2.5）。
 * 端点按北京时间当天 00:00 转 UTC 瞬时，数据库仍以 UTC 比较 {@code started_at}。
 *
 * @param from 起始自然日（含）
 * @param to 结束自然日（含）
 */
public record CostRange(LocalDate from, LocalDate to) {

    /** 团队默认时区；日/月与成本日桶统一使用北京时间。 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 默认查询天数（含端点）。 */
    public static final int DEFAULT_DAYS = 30;

    /** 最大查询天数（含端点）。 */
    public static final int MAX_DAYS = 180;

    /**
     * 解析并校验查询范围。
     *
     * @param from 起始日；为空时取 {@code to - 29 天}
     * @param to 结束日；为空时取当前北京日期
     * @param clock 可注入时钟
     * @return 合法范围
     * @throws CostInvalidRequestException 日期格式非法、起止倒置或超过 180 天
     */
    public static CostRange parse(String from, String to, Clock clock) {
        LocalDate resolvedTo = to == null || to.isBlank() ? LocalDate.now(clock.withZone(ZONE)) : parseDate(to, "to");
        LocalDate resolvedFrom =
                from == null || from.isBlank() ? resolvedTo.minusDays(DEFAULT_DAYS - 1L) : parseDate(from, "from");
        if (resolvedFrom.isAfter(resolvedTo)) {
            throw new CostInvalidRequestException("from must be on or before to");
        }
        long days = ChronoUnit.DAYS.between(resolvedFrom, resolvedTo) + 1;
        if (days > MAX_DAYS) {
            throw new CostInvalidRequestException("cost range must not exceed 180 days");
        }
        return new CostRange(resolvedFrom, resolvedTo);
    }

    /**
     * 解析单个日期。
     *
     * @param value 日期文本
     * @param field 字段名
     * @return 日期
     */
    private static LocalDate parseDate(String value, String field) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new CostInvalidRequestException(field + " must be an ISO date");
        }
    }

    /**
     * 起始日 00:00（北京时间）对应的 UTC 瞬时。
     *
     * @return 起始瞬时（含）
     */
    public Instant fromInstant() {
        return from.atStartOfDay(ZONE).toInstant();
    }

    /**
     * 结束日次日 00:00（北京时间）对应的 UTC 瞬时。
     *
     * @return 结束瞬时（不含）
     */
    public Instant toExclusiveInstant() {
        return to.plusDays(1).atStartOfDay(ZONE).toInstant();
    }
}
