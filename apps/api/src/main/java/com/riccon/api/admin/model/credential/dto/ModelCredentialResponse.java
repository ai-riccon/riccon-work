package com.riccon.api.admin.model.credential.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * 平台模型凭据（OpenAPI {@code ModelCredential}）。
 *
 * <p>只返回掩码与状态字段，永不返回明文、密文或 DEK 标识；{@code changed} 仅停用/恢复接口使用，
 * 其他操作通过 {@link JsonInclude} 省略，保持契约 {@code boolean} 类型不出现 {@code null}。
 *
 * @param id 凭据 ID
 * @param providerId 供应商 ID
 * @param label 管理员可读标签
 * @param maskedHint 末 4 位掩码
 * @param status ACTIVE|DISABLED|INVALID
 * @param priority 越小越先使用
 * @param version 状态机版本
 * @param consecutiveFailures 连续可重试失败次数
 * @param cooldownUntil 冷却截止时间（+08:00）；可为空
 * @param changed 状态是否实际变化；仅停用/恢复接口返回
 * @param createdAt 创建时间（+08:00）
 * @param updatedAt 最近更新时间（+08:00）
 * @param rotatedAt 轮换时间（+08:00）；可为空
 * @param lastUsedAt 最近成功使用时间（+08:00）；可为空
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelCredentialResponse(
        String id,
        String providerId,
        String label,
        String maskedHint,
        String status,
        int priority,
        int version,
        int consecutiveFailures,
        OffsetDateTime cooldownUntil,
        Boolean changed,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime rotatedAt,
        OffsetDateTime lastUsedAt) {}
