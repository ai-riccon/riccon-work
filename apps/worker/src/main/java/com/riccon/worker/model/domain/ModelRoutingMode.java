package com.riccon.worker.model.domain;

import java.util.Locale;

/**
 * 模型路由模式（ADR-0021 §4.1）。
 *
 * <p>{@link #ROUTED} 按用途读取 `model_routing_entry` 链；{@link #BASELINE} 回到 M02 语义，
 * 只选“最早创建的 ACTIVE 模型”，但仍复用端点允许列表与平台 key 池过滤。
 */
public enum ModelRoutingMode {

    /** 按用途有序链路由。 */
    ROUTED,

    /** M02 单模型基线，链长为 1。 */
    BASELINE;

    /**
     * 解析路由模式；空值使用安全的默认 {@link #ROUTED}。
     *
     * @param raw 原始配置或数据库值
     * @return 路由模式
     * @throws IllegalArgumentException 值不在允许枚举内
     */
    public static ModelRoutingMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ROUTED;
        }
        try {
            return valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("routingMode must be ROUTED or BASELINE, got: " + raw, exception);
        }
    }
}
