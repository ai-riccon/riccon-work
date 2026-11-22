package com.riccon.worker.model.config;

import com.riccon.worker.model.domain.ModelRoutingMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型网关在线开关的环境变量覆盖（ADR-0021 §10.5）。
 *
 * <p>空值表示“使用 `model_gateway_switch` 表”；非空值优先于数据库并让管理端写开关返回 409。
 * 非法值必须在启动期失败，避免带病运行时把请求路由到未知模式。
 *
 * @param routingMode ROUTED 或 BASELINE；空表示数据库
 * @param failoverEnabled true 或 false；空表示数据库
 */
@ConfigurationProperties(prefix = "riccon.model.gateway")
public record ModelGatewaySwitchProperties(String routingMode, String failoverEnabled) {

    /** 校验非空覆盖值的枚举/布尔格式。 */
    public ModelGatewaySwitchProperties {
        if (routingMode != null && !routingMode.isBlank()) {
            ModelRoutingMode.parse(routingMode);
        }
        if (failoverEnabled != null && !failoverEnabled.isBlank()) {
            parseBoolean(failoverEnabled, "riccon.model.gateway.failover-enabled");
        }
    }

    /**
     * 返回非空的路由模式覆盖。
     *
     * @return 覆盖值；未设置时为空
     */
    public String routingModeOverride() {
        return routingMode == null || routingMode.isBlank() ? null : routingMode.strip();
    }

    /**
     * 返回非空的降级开关覆盖。
     *
     * @return 覆盖值；未设置时为空
     */
    public Boolean failoverEnabledOverride() {
        if (failoverEnabled == null || failoverEnabled.isBlank()) {
            return null;
        }
        return parseBoolean(failoverEnabled, "riccon.model.gateway.failover-enabled");
    }

    /**
     * 解析严格布尔值。
     *
     * @param raw 原始值
     * @param name 配置键
     * @return 布尔值
     */
    private static boolean parseBoolean(String raw, String name) {
        String value = raw.strip();
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException(name + " must be true or false, got: " + raw);
    }
}
