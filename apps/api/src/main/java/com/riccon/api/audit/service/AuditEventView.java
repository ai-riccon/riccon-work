package com.riccon.api.audit.service;

import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 审计事件响应（ADR-0020 第 1 节）。
 *
 * <p>明确不包含 {@code sourceIp}、{@code requestId} 与 {@code schemaVersion}；{@code details} 已通过读取
 * 白名单校验；{@code actorDisplayName} 为读取时解析，不含邮箱。
 *
 * @param eventId 事件 ID
 * @param occurredAt 事件时间（带 +08:00 偏移）
 * @param tenantId 租户
 * @param actorType 操作者类型
 * @param actorId 操作者 ID
 * @param actorDisplayName 账号展示名，可为空
 * @param action 动作名
 * @param targetType 目标类型，可为空
 * @param targetId 目标 ID，可为空
 * @param outcome 结果
 * @param traceId 追踪 ID
 * @param details 读取时通过白名单的 details；无 details 时为空映射
 */
public record AuditEventView(
        UUID eventId,
        OffsetDateTime occurredAt,
        String tenantId,
        AuditActorType actorType,
        String actorId,
        String actorDisplayName,
        String action,
        AuditTargetType targetType,
        String targetId,
        AuditOutcome outcome,
        String traceId,
        Map<String, Object> details) {}
