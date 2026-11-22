package com.riccon.api.research.dto;

import com.riccon.api.research.domain.ResearchTaskSnapshot;
import com.riccon.platform.domain.research.ResearchTaskStatus;
import com.riccon.platform.domain.research.RunStage;
import java.time.Instant;
import java.util.UUID;

/**
 * 报告任务响应，对应 OpenAPI {@code ResearchTask}。
 *
 * @param id 任务 ID
 * @param workspaceId 所属工作空间
 * @param topic 调研主题
 * @param status 用户可见状态
 * @param currentStage 当前阶段
 * @param mcpToolId 授权工具 ID
 * @param currentRunId 当前运行 ID
 * @param failureCode 失败错误码
 * @param failureMessage 脱敏失败原因
 * @param createdAt 创建时间
 * @param startedAt 开始时间
 * @param finishedAt 终态时间
 */
public record ResearchTaskResponse(
        UUID id,
        UUID workspaceId,
        String topic,
        ResearchTaskStatus status,
        RunStage currentStage,
        UUID mcpToolId,
        UUID currentRunId,
        String failureCode,
        String failureMessage,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt) {

    /**
     * 将任务快照映射为响应。
     *
     * @param task 任务快照
     * @return 响应
     */
    public static ResearchTaskResponse from(ResearchTaskSnapshot task) {
        return new ResearchTaskResponse(
                task.id(),
                task.workspaceId(),
                task.topic(),
                task.status(),
                task.currentStage(),
                task.mcpToolId(),
                task.currentRunId(),
                task.failureCode(),
                ResearchFailureText.message(task.failureCode(), task.failureMessage()),
                task.createdAt(),
                task.startedAt(),
                task.finishedAt());
    }
}
