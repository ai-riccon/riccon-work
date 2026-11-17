package com.riccon.worker.audit.domain;

import com.riccon.platform.domain.audit.AuditActorType;
import com.riccon.platform.domain.audit.AuditOutcome;
import com.riccon.platform.domain.audit.AuditTargetType;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 审计事件（append-only）。
 *
 * <p>字段与 ADR-0007 的事件信封一致。Worker 只写模型调用与 MCP 调用相关动作；
 * {@code details} 在构造前通过 {@link AuditDetailsPolicy} 白名单校验。
 *
 * @param eventId 事件 ID（UUIDv7）
 * @param occurredAt 事件时间（UTC）
 * @param tenantId 租户
 * @param actorType 操作者类型
 * @param actorId 操作者 ID；不存用户名、邮箱等 PII
 * @param action 稳定动作名
 * @param targetType 目标类型
 * @param targetId 目标 ID
 * @param outcome 结果
 * @param traceId 追踪 ID
 * @param requestId 请求或任务 ID，可为空
 * @param details 白名单字段
 * @param schemaVersion 信封版本，M02 固定 1
 */
public record AuditEvent(
        UUID eventId,
        Instant occurredAt,
        String tenantId,
        AuditActorType actorType,
        String actorId,
        String action,
        AuditTargetType targetType,
        String targetId,
        AuditOutcome outcome,
        String traceId,
        String requestId,
        Map<String, Object> details,
        int schemaVersion) {

    /** 当前信封版本。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** 校验必填字段与长度，防止不完整事件进入数据库。 */
    public AuditEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        requireText(tenantId, "tenantId", 64);
        Objects.requireNonNull(actorType, "actorType must not be null");
        requireText(actorId, "actorId", 64);
        requireText(action, "action", 100);
        Objects.requireNonNull(outcome, "outcome must not be null");
        requireText(traceId, "traceId", 64);
        details = Map.copyOf(details == null ? Map.of() : details);
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be >= 1");
        }
    }

    /**
     * 校验必填文本。
     *
     * @param value 值
     * @param field 字段名
     * @param maxLength 最大长度
     */
    private static void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
    }
}
