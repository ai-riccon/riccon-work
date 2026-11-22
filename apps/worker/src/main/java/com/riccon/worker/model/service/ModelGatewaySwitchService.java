package com.riccon.worker.model.service;

import com.riccon.worker.model.config.ModelGatewaySwitchProperties;
import com.riccon.worker.model.domain.GatewaySwitches;
import com.riccon.worker.model.domain.ModelException;
import com.riccon.worker.model.domain.ModelFailureKind;
import com.riccon.worker.model.domain.ModelRoutingMode;
import com.riccon.worker.model.repository.GatewaySwitchRepository;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 生效开关解析：环境变量 > 数据库 > 默认（ADR-0021 §10.5）。
 *
 * <p>每次模型请求读取一次，不做跨请求缓存；非法 DB/ENV 值直接让请求失败，避免静默使用
 * 未知路由模式。环境变量存在时管理端写开关会被 API 拒绝（B3/B5 契约）。
 */
@Component
public class ModelGatewaySwitchService {

    /** 路由模式键。 */
    static final String ROUTING_MODE_KEY = "routingMode";

    /** 降级开关键。 */
    static final String FAILOVER_ENABLED_KEY = "failoverEnabled";

    /** 开关仓储。 */
    private final GatewaySwitchRepository repository;

    /** 环境变量覆盖。 */
    private final ModelGatewaySwitchProperties properties;

    /**
     * 创建开关服务。
     *
     * @param repository 开关仓储
     * @param properties 环境变量覆盖
     */
    public ModelGatewaySwitchService(GatewaySwitchRepository repository, ModelGatewaySwitchProperties properties) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /**
     * 解析当前生效开关。
     *
     * @return 生效开关
     * @throws ModelException 环境变量或数据库值非法
     */
    public GatewaySwitches effective() {
        Map<String, String> values;
        try {
            values = repository.currentValues();
        } catch (RuntimeException ex) {
            throw new ModelException(
                    ModelException.CONFIGURATION,
                    false,
                    "model gateway switches could not be read",
                    ModelFailureKind.CONFIGURATION,
                    ex);
        }
        String routingRaw = properties.routingModeOverride();
        if (routingRaw == null) {
            routingRaw = values.getOrDefault(ROUTING_MODE_KEY, ModelRoutingMode.ROUTED.name());
        }
        Boolean failoverOverride = properties.failoverEnabledOverride();
        String failoverRaw = failoverOverride != null
                ? failoverOverride.toString()
                : values.getOrDefault(FAILOVER_ENABLED_KEY, "true");
        try {
            return new GatewaySwitches(ModelRoutingMode.parse(routingRaw), parseBoolean(failoverRaw));
        } catch (IllegalArgumentException ex) {
            throw new ModelException(
                    ModelException.CONFIGURATION,
                    false,
                    "model gateway switch value is invalid",
                    ModelFailureKind.CONFIGURATION,
                    ex);
        }
    }

    /**
     * 解析严格布尔值。
     *
     * @param raw 原始值
     * @return 布尔值
     */
    private static boolean parseBoolean(String raw) {
        if ("true".equalsIgnoreCase(raw)) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return false;
        }
        throw new IllegalArgumentException("failoverEnabled must be true or false");
    }
}
