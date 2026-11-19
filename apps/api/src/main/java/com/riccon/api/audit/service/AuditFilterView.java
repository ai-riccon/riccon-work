package com.riccon.api.audit.service;

import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 审计过滤器响应（OpenAPI {@code AuditFilter}）。
 *
 * @param occurredFrom 起始时间（带偏移）
 * @param occurredTo 结束时间（带偏移）
 * @param action 动作名，可为空
 * @param actorId 操作者 ID，可为空
 * @param targetType 目标类型，可为空
 * @param targetId 目标 ID，可为空
 * @param outcome 结果，可为空
 */
public record AuditFilterView(
        OffsetDateTime occurredFrom,
        OffsetDateTime occurredTo,
        String action,
        UUID actorId,
        AuditTargetType targetType,
        String targetId,
        AuditOutcome outcome) {}
