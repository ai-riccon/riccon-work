package com.riccon.api.admin.model.gateway.config;

import com.riccon.api.admin.model.gateway.domain.ModelGatewayRoutingMode;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 模型网关路由/降级开关的部署覆盖（ADR-0021 §10.5）。
 *
 * <p>空值表示使用 {@code model_gateway_switch} 表；非空值优先于数据库，并让管理端写开关返回 409。
 * 非法值必须在启动期失败，避免带病运行时把请求路由到未知模式。配额开关的覆盖复用既有
 * {@code QuotaEnforcementProperties}，本类不重复绑定同一属性。
 */
@Component
@ConfigurationProperties(prefix = "riccon.model.gateway")
public class ModelGatewaySwitchProperties implements InitializingBean {

    /** 路由模式覆盖；空表示使用数据库开关。 */
    private String routingMode = "";

    /** 降级开关覆盖；空表示使用数据库开关。 */
    private String failoverEnabled = "";

    /**
     * 返回路由模式覆盖原文。
     *
     * @return 覆盖值
     */
    public String getRoutingMode() {
        return routingMode;
    }

    /**
     * 设置路由模式覆盖。
     *
     * @param routingMode ROUTED、BASELINE 或空
     */
    public void setRoutingMode(String routingMode) {
        this.routingMode = routingMode;
    }

    /**
     * 返回降级开关覆盖原文。
     *
     * @return 覆盖值
     */
    public String getFailoverEnabled() {
        return failoverEnabled;
    }

    /**
     * 设置降级开关覆盖。
     *
     * @param failoverEnabled true、false 或空
     */
    public void setFailoverEnabled(String failoverEnabled) {
        this.failoverEnabled = failoverEnabled;
    }

    /**
     * 返回非空的路由模式覆盖。
     *
     * @return 覆盖值；未设置时返回 {@code null}
     */
    public String routingModeOverride() {
        return routingMode == null || routingMode.isBlank() ? null : routingMode.strip();
    }

    /**
     * 返回非空的降级开关覆盖。
     *
     * @return 覆盖值；未设置时返回 {@code null}
     * @throws IllegalArgumentException 值不是 true/false
     */
    public Boolean failoverEnabledOverride() {
        if (failoverEnabled == null || failoverEnabled.isBlank()) {
            return null;
        }
        return parseBoolean(failoverEnabled, "riccon.model.gateway.failover-enabled");
    }

    /**
     * 启动时拒绝非法的路由模式或降级开关覆盖值。
     *
     * <p>与 Worker 的启动校验保持一致；管理端读到非法覆盖时不应继续提供可写界面。
     */
    @Override
    public void afterPropertiesSet() {
        String routing = routingModeOverride();
        if (routing != null) {
            ModelGatewayRoutingMode.parse(routing);
        }
        failoverEnabledOverride();
    }

    /**
     * 解析严格布尔值。
     *
     * @param raw 原始值
     * @param name 配置键
     * @return 布尔值
     * @throws IllegalArgumentException 值不是 true/false
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
