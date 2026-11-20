package com.riccon.api.admin.model.credential.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 平台托管模型凭据行。
 *
 * <p>只映射管理端展示与状态机需要的字段；<strong>不包含</strong>密文、DEK keyId 或任何可还原明文的材料，
 * 避免列表/响应路径意外把凭据材料带出服务端（ADR-0021 §6.6）。轮换需要的 label/priority 也在本记录中，
 * 旧 key 的密文不会被读取或改写。
 *
 * @param id 凭据 ID
 * @param providerId 供应商 ID
 * @param label 管理员可读标签
 * @param maskedHint 末 4 位掩码
 * @param status ACTIVE|DISABLED|INVALID
 * @param priority 越小越先使用
 * @param version 状态机版本，每次成功变更 +1
 * @param consecutiveFailures 连续可重试失败次数（Worker 维护，管理端只读）
 * @param createdAt 创建时间（UTC）
 * @param updatedAt 最近更新时间（UTC）
 * @param rotatedAt 轮换时间（UTC）
 * @param lastUsedAt 最近成功使用时间（UTC）
 * @param cooldownUntil 冷却截止时间（UTC）
 */
public record ModelCredentialRow(
        UUID id,
        UUID providerId,
        String label,
        String maskedHint,
        String status,
        int priority,
        int version,
        int consecutiveFailures,
        Instant createdAt,
        Instant updatedAt,
        Instant rotatedAt,
        Instant lastUsedAt,
        Instant cooldownUntil) {}
