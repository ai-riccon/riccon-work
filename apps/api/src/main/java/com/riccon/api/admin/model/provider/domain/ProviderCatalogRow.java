package com.riccon.api.admin.model.provider.domain;

import java.util.UUID;

/**
 * 供应商目录行（全局 {@code model_provider} + 平台 key/模型计数）。
 *
 * <p>{@code activeCredentialCount} 只统计 {@code tenant_id='platform'} 且 ACTIVE 的 API_KEY；
 * 不包含任何凭据明文、指纹或密文（ADR-0021 §6.6）。
 *
 * @param id 供应商 ID
 * @param code 供应商代码
 * @param name 展示名
 * @param protocol 协议
 * @param baseUrl 目录存储的基础 URL
 * @param status 供应商状态
 * @param activeCredentialCount 平台 ACTIVE key 数量
 * @param modelCount 目录模型数量
 */
public record ProviderCatalogRow(
        UUID id,
        String code,
        String name,
        String protocol,
        String baseUrl,
        String status,
        long activeCredentialCount,
        long modelCount) {}
