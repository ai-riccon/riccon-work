package com.riccon.api.run.domain;

import com.riccon.platform.domain.research.RunKind;
import com.riccon.platform.domain.research.RunStatus;
import java.util.Objects;
import java.util.UUID;

/**
 * 运行快照。
 *
 * <p>只包含 API 侧判断所需字段：类别、状态、会话与发起人。对话运行由 Worker 推进状态，API 在 SSE 事件与取消路径
 * 做条件更新，重复事件不会把终态退回进行中。
 *
 * @param id 运行 ID（UUIDv7）
 * @param kind 运行类别
 * @param status 状态
 * @param conversationId 会话 ID；仅 CHAT
 * @param userId 发起人
 * @param attempt 当前尝试号
 */
public record RunSnapshot(UUID id, RunKind kind, RunStatus status, UUID conversationId, UUID userId, int attempt) {

    /** 校验必填字段。 */
    public RunSnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
    }

    /**
     * 判断运行是否已处于终态。
     *
     * @return SUCCEEDED/FAILED/CANCELLED 时返回 {@code true}
     */
    public boolean terminal() {
        return status == RunStatus.SUCCEEDED || status == RunStatus.FAILED || status == RunStatus.CANCELLED;
    }
}
