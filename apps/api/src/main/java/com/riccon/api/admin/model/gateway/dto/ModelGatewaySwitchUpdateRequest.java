package com.riccon.api.admin.model.gateway.dto;

/**
 * 网关开关整组更新请求（OpenAPI {@code ModelGatewaySwitchesUpdateRequest}）。
 *
 * <p>三个开关必须同时提交，{@code version} 是客户端最后一次 GET 读到的整组版本；
 * 字段使用包装类型，才能把“缺失”与合法布尔值区分开并返回字段级 400（ADR-0021 §7.1/§7.3）。
 *
 * @param version 客户端读到的整组版本
 * @param routingMode ROUTED 或 BASELINE
 * @param failoverEnabled 降级开关
 * @param quotaEnforcementEnabled 配额执行开关
 */
public record ModelGatewaySwitchUpdateRequest(
        Integer version, String routingMode, Boolean failoverEnabled, Boolean quotaEnforcementEnabled) {}
