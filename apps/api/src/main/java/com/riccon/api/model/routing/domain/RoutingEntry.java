package com.riccon.api.model.routing.domain;

import java.util.UUID;

/**
 * 路由链条目读模型。
 *
 * <p>每条对应 {@code model_routing_entry} 的一行，并带出模型与供应商的展示字段。供应商地址与协议只用于
 * 服务层计算端点标签和可路由性，不进入对外响应（ADR-0021 §4.1/§7.2）。
 *
 * @param position 链内顺序，从 1 开始
 * @param modelId 模型 ID
 * @param displayName 模型展示名
 * @param status 模型状态 {@code ACTIVE|DISABLED}；停用模型仍保留在链中
 * @param providerCode 供应商代码，仅用于派生端点标签
 * @param providerBaseUrl 供应商基础 URL，仅用于端点允许列表校验
 * @param providerProtocol 供应商协议
 * @param providerStatus 供应商状态
 */
public record RoutingEntry(
        int position,
        UUID modelId,
        String displayName,
        String status,
        String providerCode,
        String providerBaseUrl,
        String providerProtocol,
        String providerStatus) {}
