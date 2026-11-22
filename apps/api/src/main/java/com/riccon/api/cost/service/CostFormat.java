package com.riccon.api.cost.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Currency;
import java.util.Objects;

/**
 * 成本金额与时间格式。
 *
 * <p>金额固定 6 位小数字符串；展示按币种最小单位由前端完成，API 只提供 {@code currencyMinorUnit}
 * 与未舍入的 6 位字符串。固定免责文案由 API 返回，避免前端硬编码漂移（ADR-0022 §2.4）。
 */
public final class CostFormat {

    /** 固定免责文案。 */
    public static final String DISCLAIMER = "成本为按调用时价格快照估算，不是供应商账单。";

    /** 成本金额/价格精度。 */
    private static final int SCALE = 6;

    /** 未知币种的最小单位兜底；CNY 为 2。 */
    private static final int DEFAULT_MINOR_UNIT = 2;

    /** 币种最小单位上限，与 OpenAPI {@code currencyMinorUnit} 一致。 */
    private static final int MAX_MINOR_UNIT = 4;

    /** 工具类禁止实例化。 */
    private CostFormat() {}

    /**
     * 金额格式化为 6 位小数字符串。
     *
     * @param value 金额；为空时按 0 处理
     * @return 6 位小数字符串
     */
    public static String amount(BigDecimal value) {
        BigDecimal normalized = value == null ? BigDecimal.ZERO : value;
        return normalized.setScale(SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * 价格格式化为字符串；为空时返回 {@code null}。
     *
     * @param value 价格
     * @return 6 位小数字符串或 null
     */
    public static String price(BigDecimal value) {
        return value == null
                ? null
                : value.setScale(SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * 返回币种最小单位。
     *
     * @param currencyCode ISO 4217 三位代码
     * @return 最小单位（0..4）；未知币种按 2
     */
    public static int currencyMinorUnit(String currencyCode) {
        Objects.requireNonNull(currencyCode, "currencyCode must not be null");
        try {
            int fractionDigits = Currency.getInstance(currencyCode).getDefaultFractionDigits();
            return fractionDigits < 0 ? DEFAULT_MINOR_UNIT : Math.min(fractionDigits, MAX_MINOR_UNIT);
        } catch (IllegalArgumentException exception) {
            return DEFAULT_MINOR_UNIT;
        }
    }

    /**
     * 带偏移的 ISO 8601 北京时间。
     *
     * @param instant 瞬时
     * @return 例如 {@code 2026-11-21T10:30:00+08:00}
     */
    public static String timestamp(Instant instant) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atZone(com.riccon.api.cost.domain.CostRange.ZONE));
    }

    /**
     * 日期文本。
     *
     * @param date 日期
     * @return {@code YYYY-MM-DD}
     */
    public static String date(LocalDate date) {
        return date == null ? null : date.toString();
    }
}
