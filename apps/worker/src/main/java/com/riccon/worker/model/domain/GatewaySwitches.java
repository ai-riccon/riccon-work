package com.riccon.worker.model.domain;

import java.util.Objects;

/**
 * 生效的模型网关开关（ENV > DB > 默认，ADR-0021 §10.5）。
 *
 * @param routingMode 路由模式
 * @param failoverEnabled 是否允许降级（关闭后只尝试首个可用模型的首个 key）
 */
public record GatewaySwitches(ModelRoutingMode routingMode, boolean failoverEnabled) {

    /** 校验路由模式。 */
    public GatewaySwitches {
        Objects.requireNonNull(routingMode, "routingMode must not be null");
    }

    /**
     * 返回默认开关：ROUTED + 降级开启，与 V11 发版种子一致。
     *
     * @return 默认开关
     */
    public static GatewaySwitches defaults() {
        return new GatewaySwitches(ModelRoutingMode.ROUTED, true);
    }
}
