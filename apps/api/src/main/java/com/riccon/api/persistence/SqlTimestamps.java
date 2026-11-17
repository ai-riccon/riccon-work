package com.riccon.api.persistence;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * MySQL {@code DATETIME(6)} 与 {@link Instant} 的 UTC 互转。
 *
 * <p>连接初始化固定 {@code time_zone='+00:00'}（application.yml），因此数据库中的无时区时间按 UTC 解释；
 * 禁止使用 {@link java.sql.Timestamp} 的本地时区隐式转换。
 */
public final class SqlTimestamps {

    /** 数据库统一使用的时区。 */
    private static final ZoneOffset UTC = ZoneOffset.UTC;

    /** 工具类禁止实例化。 */
    private SqlTimestamps() {}

    /**
     * 将 UTC 时间转换为数据库写入值。
     *
     * @param instant UTC 时间；为 {@code null} 时返回 {@code null}
     * @return MySQL {@code DATETIME(6)} 对应的本地时间
     */
    public static LocalDateTime toDatabase(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, UTC);
    }

    /**
     * 将数据库读取值转换为 UTC 时间。
     *
     * @param value 数据库 {@code DATETIME(6)} 值；为 {@code null} 时返回 {@code null}
     * @return UTC 时间或 {@code null}
     */
    public static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(UTC);
    }
}
