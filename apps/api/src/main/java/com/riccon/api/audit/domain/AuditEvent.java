package com.riccon.api.audit.domain;

import com.riccon.api.common.UuidV7;
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
 * <p>字段与 ADR-0007 的事件信封一致；{@code details} 只允许白名单键，由
 * {@link AuditDetailsPolicy} 在写入前校验。事件 ID 为 UUIDv7，时间有序；数据库层没有 update/delete API。
 *
 * @param eventId 事件 ID
 * @param occurredAt 事件时间（UTC）
 * @param tenantId 租户；平台级事件为 {@code platform}
 * @param actorType 操作者类型
 * @param actorId 操作者 ID；不存用户名、邮箱等 PII
 * @param action 稳定动作名
 * @param targetType 目标类型，可为空
 * @param targetId 目标 ID，可为空
 * @param outcome 结果
 * @param traceId 追踪 ID
 * @param requestId 请求 ID，可为空
 * @param sourceIp 来源 IP，仅登录事件记录
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
        String sourceIp,
        Map<String, Object> details,
        int schemaVersion) {

    /** 当前事件信封版本。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** 校验必填字段与长度，防止超长或不完整事件进入数据库。 */
    public AuditEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        requireText(tenantId, "tenantId", 64);
        Objects.requireNonNull(actorType, "actorType must not be null");
        requireText(actorId, "actorId", 64);
        requireText(action, "action", 100);
        requireOptionalText(targetId, "targetId", 64);
        Objects.requireNonNull(outcome, "outcome must not be null");
        requireText(traceId, "traceId", 64);
        requireOptionalText(requestId, "requestId", 64);
        requireOptionalText(sourceIp, "sourceIp", 45);
        details = Map.copyOf(details == null ? Map.of() : details);
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be >= 1");
        }
    }

    /**
     * 创建带新 UUIDv7 与白名单校验的审计事件。
     *
     * @param occurredAt 事件时间
     * @param tenantId 租户
     * @param actorType 操作者类型
     * @param actorId 操作者 ID
     * @param action 动作名
     * @param targetType 目标类型；可为空
     * @param targetId 目标 ID；可为空
     * @param outcome 结果
     * @param traceId 追踪 ID
     * @param requestId 请求 ID；可为空
     * @param sourceIp 来源 IP；可为空
     * @param details 白名单字段
     * @return 通过校验的审计事件
     * @throws IllegalArgumentException details 含未授权键或值类型
     */
    public static AuditEvent create(
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
            String sourceIp,
            Map<String, Object> details) {
        AuditDetailsPolicy.validate(action, details);
        return new AuditEvent(
                UuidV7.generate(occurredAt),
                occurredAt,
                tenantId,
                actorType,
                actorId,
                action,
                targetType,
                targetId,
                outcome,
                traceId,
                requestId,
                sourceIp,
                details,
                CURRENT_SCHEMA_VERSION);
    }

    /**
     * 校验必填文本长度。
     *
     * @param value 待校验值
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

    /**
     * 校验可空文本长度。
     *
     * @param value 待校验值
     * @param field 字段名
     * @param maxLength 最大长度
     */
    private static void requireOptionalText(String value, String field, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
    }
}
