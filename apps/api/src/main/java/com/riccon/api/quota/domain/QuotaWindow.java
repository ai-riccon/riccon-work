package com.riccon.api.quota.domain;

/**
 * 配额窗口。
 *
 * <p>窗口边界按 {@code Asia/Shanghai} 自然日/自然月计算，起点转 UTC 存储；与 OpenAPI
 * {@code QuotaWindowKind} 一致（ADR-0022 第 1.6 节）。
 */
public enum QuotaWindow {
    /** 自然日窗口：Asia/Shanghai 当日 00:00:00 起。 */
    DAILY,
    /** 自然月窗口：Asia/Shanghai 当月 1 日 00:00:00 起。 */
    MONTHLY
}
