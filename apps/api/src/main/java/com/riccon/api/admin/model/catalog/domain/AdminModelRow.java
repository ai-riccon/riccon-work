package com.riccon.api.admin.model.catalog.domain;

import java.math.BigDecimal;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * 平台模型目录行（含只读价格快照）。
 *
 * <p>价格由部署种子/运维维护，M04 不提供价格编辑；管理端只读展示，员工端不会看到这些字段
 * （ADR-0021 §7.5、ADR-0022 §2.1）。
 *
 * @param modelId 模型 ID
 * @param providerId 供应商 ID
 * @param modelKey 上游模型标识
 * @param displayName 展示名
 * @param contextWindow 上下文窗口
 * @param maxOutputTokens 最大输出 token
 * @param capabilities 能力 JSON
 * @param inputPricePer1M 输入价格（可为空）
 * @param outputPricePer1M 输出价格（可为空）
 * @param modelStatus 模型状态
 * @param providerCode 供应商代码
 * @param providerProtocol 供应商协议
 * @param providerBaseUrl 供应商基础 URL
 * @param providerStatus 供应商状态
 */
public record AdminModelRow(
        UUID modelId,
        UUID providerId,
        String modelKey,
        String displayName,
        int contextWindow,
        int maxOutputTokens,
        JsonNode capabilities,
        BigDecimal inputPricePer1M,
        BigDecimal outputPricePer1M,
        String modelStatus,
        String providerCode,
        String providerProtocol,
        String providerBaseUrl,
        String providerStatus) {}
