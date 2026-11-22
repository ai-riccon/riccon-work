package com.riccon.api.admin.model.catalog.dto;

import tools.jackson.databind.JsonNode;

/**
 * 平台模型目录项（OpenAPI {@code AdminModel}）。
 *
 * <p>只对平台管理员返回 provider、标签、价格与定价状态；员工目录使用独立 {@code EmployeeModel} 视图
 * （ADR-0021 §7.5）。价格是只读快照字符串，6 位小数。
 *
 * @param id 模型 ID
 * @param modelKey 上游模型标识
 * @param displayName 展示名
 * @param providerId 供应商 ID
 * @param providerCode 供应商代码
 * @param environmentLabel 端点部署标签
 * @param contextWindow 上下文窗口
 * @param maxOutputTokens 最大输出 token
 * @param capabilities 能力 JSON
 * @param inputPricePer1M 输入价格
 * @param outputPricePer1M 输出价格
 * @param priceCurrency 部署币种
 * @param pricingStatus PRICED 或 UNPRICED
 * @param status 模型状态
 */
public record AdminModelResponse(
        String id,
        String modelKey,
        String displayName,
        String providerId,
        String providerCode,
        String environmentLabel,
        int contextWindow,
        int maxOutputTokens,
        JsonNode capabilities,
        String inputPricePer1M,
        String outputPricePer1M,
        String priceCurrency,
        String pricingStatus,
        String status) {}
