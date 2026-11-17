package com.riccon.api.outbox.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 待写入 outbox 的 API 事件。
 *
 * <p>只保存 Worker 完成任务所需的白名单参数；事件信封中的租户、trace 与发生时间分别来自数据库列与
 * {@code agent_run.trace_id}，payload 禁止包含提示词、文件原文或凭据（ADR-0014）。
 *
 * @param id 事件 ID（UUIDv7）
 * @param runId 关联运行 ID
 * @param eventType 契约事件名
 * @param eventVersion 事件 schema 版本
 * @param idempotencyKey 租户内唯一幂等键
 * @param payload 脱敏后的业务参数
 * @param occurredAt 事件发生时间
 */
public record OutboxEvent(
        UUID id,
        UUID runId,
        String eventType,
        int eventVersion,
        String idempotencyKey,
        Map<String, Object> payload,
        Instant occurredAt) {

    /** 校验必填字段与长度，避免把非法事件写入队列。 */
    public OutboxEvent {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (eventVersion < 1) {
            throw new IllegalArgumentException("eventVersion must be >= 1");
        }
        if (eventType.isBlank() || eventType.length() > 100) {
            throw new IllegalArgumentException("eventType must be 1-100 characters");
        }
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 200) {
            throw new IllegalArgumentException("idempotencyKey must be 1-200 characters");
        }
        payload = Map.copyOf(payload);
    }
}
