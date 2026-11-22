package com.riccon.api.quota.dto;

import com.riccon.api.quota.domain.QuotaInvalidRequestException;
import tools.jackson.databind.JsonNode;

/**
 * 配额整体替换请求（OpenAPI {@code QuotaUpdateRequest}）。
 *
 * <p>契约要求 {@code limits} 必须同时包含 {@code daily} 与 {@code monthly}，{@code null} 表示明确不限，
 * 0 也等价于不限；缺键、负数、未知键或非整数一律 400 {@code RICCON-QUOTA-400}。由于“缺键”和“显式 null”
 * 在普通 DTO 反序列化后无法区分，这里在 controller 边界解析原始 {@link JsonNode}。
 *
 * @param daily 日限额；{@code null} 表示明确不限
 * @param monthly 月限额；{@code null} 表示明确不限
 */
public record QuotaLimitUpdate(Integer daily, Integer monthly) {

    /** 请求体顶层唯一允许的键。 */
    private static final String LIMITS = "limits";

    /**
     * 解析并校验请求体。
     *
     * @param body 原始 JSON 体
     * @return 合法限额
     * @throws QuotaInvalidRequestException 结构或取值非法
     */
    public static QuotaLimitUpdate parse(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new QuotaInvalidRequestException("request body must be an object");
        }
        if (body.size() != 1 || !body.has(LIMITS)) {
            throw new QuotaInvalidRequestException("request body must only contain limits");
        }
        JsonNode limits = body.get(LIMITS);
        if (limits == null || !limits.isObject() || limits.size() != 2) {
            throw new QuotaInvalidRequestException("limits must contain daily and monthly");
        }
        if (!limits.has("daily") || !limits.has("monthly")) {
            throw new QuotaInvalidRequestException("limits.daily and limits.monthly are required");
        }
        return new QuotaLimitUpdate(
                parseLimit(limits.get("daily"), "limits.daily"), parseLimit(limits.get("monthly"), "limits.monthly"));
    }

    /**
     * 解析单个限额。
     *
     * @param node 节点
     * @param field 字段名
     * @return 限额或 {@code null}
     */
    private static Integer parseLimit(JsonNode node, String field) {
        if (node == null) {
            throw new QuotaInvalidRequestException(field + " is required");
        }
        if (node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new QuotaInvalidRequestException(field + " must be an integer or null");
        }
        int value = node.intValue();
        if (value < 0) {
            throw new QuotaInvalidRequestException(field + " must not be negative");
        }
        return value;
    }
}
