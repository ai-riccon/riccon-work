package com.riccon.api.research.domain;

import com.riccon.platform.domain.research.ResearchTaskStatus;
import com.riccon.platform.domain.research.RunStage;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 报告任务快照。
 *
 * <p>业务任务与执行运行分离：Worker 只推进 {@code agent_run}，任务状态由 API 在读取时从当前运行派生并回写
 * （V4 迁移注释、ADR-0008 第 3 节）。因此本快照同时承载任务列与派生后的用户可见状态，避免前端看到
 * “任务永远是 PENDING”的假状态。
 *
 * @param id 任务 ID
 * @param workspaceId 所属工作空间
 * @param createdBy 创建人
 * @param topic 调研主题
 * @param status 用户可见状态（由当前运行派生）
 * @param currentStage 当前阶段
 * @param mcpToolId 授权的 MCP 工具 ID
 * @param currentRunId 当前运行 ID
 * @param failureCode 失败错误码
 * @param failureMessage 脱敏失败原因
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @param startedAt 首次开始时间
 * @param finishedAt 终态时间
 */
public record ResearchTaskSnapshot(
        UUID id,
        UUID workspaceId,
        UUID createdBy,
        String topic,
        ResearchTaskStatus status,
        RunStage currentStage,
        UUID mcpToolId,
        UUID currentRunId,
        String failureCode,
        String failureMessage,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant finishedAt) {

    /** 校验必填字段，避免不完整快照进入响应层。 */
    public ResearchTaskSnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        Objects.requireNonNull(createdBy, "createdBy must not be null");
        Objects.requireNonNull(topic, "topic must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }
}
