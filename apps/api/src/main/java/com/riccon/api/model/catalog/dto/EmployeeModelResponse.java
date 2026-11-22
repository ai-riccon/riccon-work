package com.riccon.api.model.catalog.dto;

import tools.jackson.databind.JsonNode;

/**
 * 员工可见模型（OpenAPI {@code EmployeeModel}）。
 *
 * <p>不包含 provider、{@code baseUrl}、key 或价格字段；价格只在平台 {@code AdminModel} 返回，
 * 避免“实现正确但 schema 允许泄露”（ADR-0021 §7.5）。
 *
 * @param id 模型 ID
 * @param modelKey 上游模型标识（M02 已发布字段）
 * @param displayName 展示名
 * @param contextWindow 上下文窗口
 * @param maxOutputTokens 最大输出 token
 * @param capabilities 能力 JSON
 * @param status 模型状态
 */
public record EmployeeModelResponse(
        String id,
        String modelKey,
        String displayName,
        int contextWindow,
        int maxOutputTokens,
        JsonNode capabilities,
        String status) {}
