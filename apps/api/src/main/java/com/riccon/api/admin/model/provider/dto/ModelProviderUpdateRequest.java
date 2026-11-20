package com.riccon.api.admin.model.provider.dto;

/**
 * 供应商更新请求（OpenAPI {@code ModelProviderUpdateRequest}）。
 *
 * <p>只有 {@code name} 可写；{@code baseUrl}/{@code environmentLabel}/{@code protocol} 出现即 400。
 * 字段保持字符串类型，便于控制器返回精确的字段级原因码，而不是被通用反序列化错误吞掉。
 *
 * @param name 新展示名
 * @param baseUrl 只读部署字段；出现即拒绝
 * @param environmentLabel 只读部署字段；出现即拒绝
 * @param protocol 只读部署字段；出现即拒绝
 */
public record ModelProviderUpdateRequest(String name, String baseUrl, String environmentLabel, String protocol) {}
