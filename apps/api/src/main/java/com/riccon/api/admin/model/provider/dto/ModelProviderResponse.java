package com.riccon.api.admin.model.provider.dto;

/**
 * 平台供应商目录（OpenAPI {@code ModelProvider}）。
 *
 * <p>只对平台管理员返回 {@code baseUrl}；不返回 key、掩码或密文。{@code environmentLabel} 与 {@code allowed}
 * 由部署允许列表派生，{@code activeCredentialCount} 只统计平台托管 ACTIVE key（ADR-0021 §2.3/§6.6）。
 *
 * @param id 供应商 ID
 * @param code 供应商代码
 * @param name 展示名
 * @param protocol 协议
 * @param baseUrl 目录存储的基础 URL
 * @param environmentLabel 部署标签
 * @param allowed 是否在允许列表且可被路由选择
 * @param activeCredentialCount 平台 ACTIVE key 数量
 * @param modelCount 目录模型数量
 * @param status 供应商状态
 */
public record ModelProviderResponse(
        String id,
        String code,
        String name,
        String protocol,
        String baseUrl,
        String environmentLabel,
        boolean allowed,
        long activeCredentialCount,
        long modelCount,
        String status) {}
