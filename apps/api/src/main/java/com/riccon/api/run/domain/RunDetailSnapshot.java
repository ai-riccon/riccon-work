package com.riccon.api.run.domain;

import com.riccon.platform.domain.research.RunKind;
import com.riccon.platform.domain.research.RunStage;
import com.riccon.platform.domain.research.RunStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 运行详情快照。
 *
 * <p>除状态机字段外，保留运行归属（发起人、会话、报告任务、文件）用于读取时做可见性判断；
 * {@code created_at} 用于 PENDING 运行尚无开始时间时给契约必填的 {@code startedAt} 提供稳定值。
 *
 * @param id 运行 ID
 * @param kind 运行类别
 * @param status 运行状态
 * @param currentStage 当前阶段；未开始时为空
 * @param attempt 当前尝试号
 * @param errorCode 稳定失败码
 * @param errorMessage 脱敏失败原因
 * @param conversationId 关联会话；仅 CHAT
 * @param researchTaskId 关联报告任务；仅 RESEARCH_REPORT
 * @param fileId 关联文件；仅 FILE_PARSE
 * @param userId 发起人
 * @param createdAt 创建时间
 * @param startedAt 开始时间；PENDING 时为空
 * @param finishedAt 终态时间
 */
public record RunDetailSnapshot(
        UUID id,
        RunKind kind,
        RunStatus status,
        RunStage currentStage,
        int attempt,
        String errorCode,
        String errorMessage,
        UUID conversationId,
        UUID researchTaskId,
        UUID fileId,
        UUID userId,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt) {

    /** 校验必填字段。 */
    public RunDetailSnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
