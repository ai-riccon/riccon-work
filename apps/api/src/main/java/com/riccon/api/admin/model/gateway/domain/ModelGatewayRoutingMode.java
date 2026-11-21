package com.riccon.api.admin.model.gateway.domain;

import java.util.Locale;

/**
 * 模型网关路由模式（ADR-0021 §3.4/§4.1）。
 *
 * <p>{@link #ROUTED} 按用途读取路由链；{@link #BASELINE} 回到 M02 单模型基线。管理端只负责保存开关值，
 * 运行时解析在 Worker；API 侧必须先按同一枚举校验，避免把非法值写入全局开关表。
 */
public enum ModelGatewayRoutingMode {

    /** 按用途有序链路由。 */
    ROUTED,

    /** M02 单模型基线。 */
    BASELINE;

    /**
     * 解析路由模式。
     *
     * <p>空值按 {@link #ROUTED} 处理，与迁移种子和历史数据库值兼容；非空值必须是 ROUTED 或 BASELINE，
     * 且忽略大小写与首尾空白，避免管理端因大小写差异写入不可运行的值。
     *
     * @param raw 原始配置或数据库值
     * @return 路由模式
     * @throws IllegalArgumentException 值不在允许枚举内
     */
    public static ModelGatewayRoutingMode parse(String raw) {
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
