package com.riccon.api.model.catalog.domain;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * 模型目录行（全局表 {@code model} + {@code model_provider} 的只读快照）。
 *
 * <p>只包含目录字段，不含任何凭据、价格或调用数据。员工目录与平台目录共用本行，
 * 由各自服务按可见性裁剪（ADR-0021 §7.5）。
 *
 * @param modelId 模型 ID
 * @param providerId 供应商 ID
 * @param modelKey 上游模型标识
 * @param displayName 展示名
 * @param contextWindow 上下文窗口
 * @param maxOutputTokens 最大输出 token
 * @param capabilities 能力 JSON
 * @param modelStatus 模型状态
 * @param providerCode 供应商代码
 * @param providerProtocol 供应商协议
 * @param providerBaseUrl 供应商存储的基础 URL
 * @param providerStatus 供应商状态
 */
public record CatalogModelRow(
        UUID modelId,
        UUID providerId,
        String modelKey,
        String displayName,
        int contextWindow,
        int maxOutputTokens,
        JsonNode capabilities,
        String modelStatus,
        String providerCode,
        String providerProtocol,
        String providerBaseUrl,
        String providerStatus) {}
