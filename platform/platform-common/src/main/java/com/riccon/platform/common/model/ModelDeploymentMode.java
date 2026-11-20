package com.riccon.platform.common.model;

import java.util.Locale;

/**
 * 模型端点部署模式。
 *
 * <p>{@link #PRODUCTION} 是安全默认：只允许 HTTPS、拒绝回环/元数据/链路本地地址，且演示端点不可被路由选择；
 * {@link #DEMO} 仅用于本地、CI 与演示环境，可配合显式 HTTP 开关访问回环端点（ADR-0021 §2.1/§2.2）。
 */
public enum ModelDeploymentMode {

    /** 演示/本地/CI：允许显式 HTTP 开关与回环端点，但仍拒绝元数据与链路本地地址。 */
    DEMO,

    /** 生产：只允许 HTTPS 与允许列表端点，拒绝回环、元数据、链路本地与演示端点。 */
    PRODUCTION;

    /**
     * 解析部署模式；空值按安全的 {@link #PRODUCTION} 处理。
     *
     * @param raw 原始配置值，可为空
     * @return 部署模式
     * @throws IllegalArgumentException 值不在允许枚举内
     */
    public static ModelDeploymentMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return PRODUCTION;
        }
        try {
            return valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "riccon.model.deployment-mode must be DEMO or PRODUCTION, got: " + raw, exception);
        }
    }
}
