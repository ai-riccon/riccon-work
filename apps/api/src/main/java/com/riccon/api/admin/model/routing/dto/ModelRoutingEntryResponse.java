package com.riccon.api.admin.model.routing.dto;

import java.util.UUID;

/**
 * 路由链条目响应（OpenAPI {@code ModelRoutingEntry}）。
 *
 * <p>只暴露顺序、模型标识、展示名、状态与端点环境标签；不返回 provider code、baseUrl、key 或价格
 * （ADR-0021 §7.2/§7.5）。
 *
 * @param position 链内顺序，从 1 开始
 * @param modelId 模型 ID
 * @param displayName 模型展示名
 * @param status 模型状态 {@code ACTIVE|DISABLED}
 * @param environmentLabel 端点环境标签 {@code PRODUCTION|DEMO_ONLY}
 */
public record ModelRoutingEntryResponse(
        int position, String modelId, String displayName, String status, String environmentLabel) {

    /**
     * 用 UUID 构造条目响应，统一序列化为字符串。
     *
     * @param position 顺序
     * @param modelId 模型 ID
     * @param displayName 展示名
     * @param status 状态
     * @param environmentLabel 端点标签
     * @return 条目响应
     */
    public static ModelRoutingEntryResponse of(
            int position, UUID modelId, String displayName, String status, String environmentLabel) {
        return new ModelRoutingEntryResponse(position, modelId.toString(), displayName, status, environmentLabel);
    }
}
