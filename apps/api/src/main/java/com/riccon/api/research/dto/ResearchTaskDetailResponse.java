package com.riccon.api.research.dto;

import com.riccon.api.research.domain.ApprovalSnapshot;
import com.riccon.api.research.domain.McpInvocationSnapshot;
import com.riccon.api.research.domain.ResearchTaskSnapshot;
import com.riccon.api.research.domain.RunStageSnapshot;
import com.riccon.platform.domain.research.ResearchTaskStatus;
import com.riccon.platform.domain.research.RunStage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 报告任务详情响应，对应 OpenAPI {@code ResearchTaskDetail}。
 *
 * <p>时间线、审批、MCP 调用与引用都来自权威表；{@code sources} 在产物生成前由检索阶段
 * {@code details.sources} 派生，产物生成后改用 {@code artifact_citation}；{@code artifact} 只在产物存在且未删除时返回。
 *
 * @param id 任务 ID
 * @param workspaceId 工作空间 ID
 * @param topic 主题
 * @param status 状态
 * @param currentStage 当前阶段
 * @param mcpToolId 工具 ID
 * @param currentRunId 当前运行 ID
 * @param failureCode 失败码
 * @param failureMessage 失败原因
 * @param createdAt 创建时间
 * @param startedAt 开始时间
 * @param finishedAt 终态时间
 * @param stages 阶段时间线
 * @param approvals 审批记录
 * @param mcpInvocations MCP 调用
 * @param sources 引用清单
 * @param artifact 产物详情
 */
public record ResearchTaskDetailResponse(
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
        Instant finishedAt,
        List<RunStageResponse> stages,
        List<ApprovalResponse> approvals,
        List<McpInvocationResponse> mcpInvocations,
        List<CitationResponse> sources,
        ArtifactDetailResponse artifact) {

    /**
     * 组装任务详情。
     *
     * @param task 任务快照
     * @param stages 阶段时间线
     * @param approvals 审批
     * @param invocations MCP 调用
     * @param sources 引用清单
     * @param artifact 产物详情；不存在时为 {@code null}
     * @return 详情响应
     */
    public static ResearchTaskDetailResponse of(
            ResearchTaskSnapshot task,
            List<RunStageSnapshot> stages,
            List<ApprovalSnapshot> approvals,
            List<McpInvocationSnapshot> invocations,
            List<CitationResponse> sources,
            ArtifactDetailResponse artifact) {
        return new ResearchTaskDetailResponse(
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
                task.finishedAt(),
                stages.stream().map(RunStageResponse::from).toList(),
                approvals.stream().map(ApprovalResponse::from).toList(),
                invocations.stream().map(McpInvocationResponse::from).toList(),
                sources,
                artifact);
    }
}
