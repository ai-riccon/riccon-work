package com.riccon.api.audit.repository;

import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import java.time.Instant;
import java.util.UUID;

/**
 * 审计查询/导出共享的事件读取模型。
 *
 * <p>{@code detailsJson} 保持数据库原始 JSON，由服务层统一执行读取白名单（ADR-0020 第 1 节）；
 * 明确不含 {@code source_ip}、{@code request_id} 与 {@code schema_version}。
 *
 * @param eventId 事件 ID
 * @param occurredAt 事件时间（UTC）
 * @param tenantId 租户
 * @param actorType 操作者类型
 * @param actorId 操作者 ID
 * @param actorDisplayName 读取时解析的账号展示名；非 USER 或账号不存在时为空
 * @param action 动作名
 * @param targetType 目标类型，可为空
 * @param targetId 目标 ID，可为空
 * @param outcome 结果
 * @param traceId 追踪 ID
 * @param detailsJson details 原始 JSON，可为空
 */
public record AuditEventRecord(
        UUID eventId,
        Instant occurredAt,
        String tenantId,
        AuditActorType actorType,
        String actorId,
        String actorDisplayName,
        String action,
        AuditTargetType targetType,
        String targetId,
        AuditOutcome outcome,
        String traceId,
        String detailsJson) {}
