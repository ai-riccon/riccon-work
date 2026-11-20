package com.riccon.platform.common.model;

import java.util.Locale;

/**
 * 受管端点的部署环境标签。
 *
 * <p>标签不落库，只由部署配置 {@code RICCON_MODEL_ENDPOINTS} 派生：{@link #PRODUCTION} 表示客户内网/合规网关，
 * {@link #DEMO_ONLY} 表示仅演示、非生产可用。生产模式必须拒绝演示端点的路由选择（ADR-0021 §2.3）。
 */
public enum EndpointEnvironmentLabel {

    /** 生产可用：客户内网或合规网关。 */
    PRODUCTION,

    /** 仅演示/非生产可用：本地假模型、演示网关。 */
    DEMO_ONLY;

    /**
     * 解析标签。
     *
     * @param raw 原始配置值
     * @return 标签
     * @throws IllegalArgumentException 缺失或不在允许枚举内
     */
    public static EndpointEnvironmentLabel parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("model endpoint environmentLabel is required (PRODUCTION|DEMO_ONLY)");
        }
        try {
            return valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "model endpoint environmentLabel must be PRODUCTION or DEMO_ONLY, got: " + raw, exception);
        }
    }
}
