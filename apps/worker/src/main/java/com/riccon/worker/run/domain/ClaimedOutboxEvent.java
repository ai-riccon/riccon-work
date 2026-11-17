package com.riccon.worker.run.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 已领取的 outbox 事件。
 *
 * <p>只包含 ADR-0016 允许跨租户读取的列；payload 是已脱敏的任务参数，日志不得输出其原文。
 *
 * @param id 事件 ID（UUIDv7）
 * @param tenantId 事件所属租户
 * @param runId 关联运行 ID
 * @param eventType 事件类型
 * @param eventVersion 事件 schema 版本
 * @param idempotencyKey 租户内唯一幂等键
 * @param payload 任务参数 JSON
 * @param attempts 本次领取后的尝试号（首次为 1）
 * @param availableAt 最早可领取时间
 * @param leaseUntil 本次租约到期时间
 */
public record ClaimedOutboxEvent(
        UUID id,
        String tenantId,
        UUID runId,
        String eventType,
        int eventVersion,
        String idempotencyKey,
        String payload,
        int attempts,
        Instant availableAt,
        Instant leaseUntil) {}
