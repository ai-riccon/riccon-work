package com.riccon.api.run.dto;

import com.riccon.api.research.domain.RunStageSnapshot;
import com.riccon.api.research.dto.RunStageResponse;
import com.riccon.api.run.domain.ModelInvocationSnapshot;
import com.riccon.api.run.domain.RunDetailSnapshot;
import com.riccon.platform.domain.research.RunKind;
import com.riccon.platform.domain.research.RunStage;
import com.riccon.platform.domain.research.RunStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 运行详情响应，对应 OpenAPI {@code RunDetail}。
 *
 * <p>返回状态、当前阶段、尝试号、失败原因、起止时间、模型调用记录与阶段时间线。{@code startedAt} 在契约中
 * 必填；PENDING 运行尚未开始时不伪造运行状态，而用创建时间作为稳定的展示起点，Worker 推进后自然切换为
 * 真实开始时间。
 *
 * @param id 运行 ID
 * @param kind 运行类别
 * @param status 运行状态
 * @param currentStage 当前阶段
 * @param attempt 当前尝试号
 * @param errorCode 稳定失败码
 * @param errorMessage 脱敏失败原因
 * @param startedAt 开始时间；PENDING 时以创建时间代替
 * @param finishedAt 终态时间
 * @param modelInvocations 模型调用记录
 * @param stages 阶段时间线
 */
public record RunDetailResponse(
        UUID id,
        RunKind kind,
        RunStatus status,
        RunStage currentStage,
        int attempt,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt,
        List<ModelInvocationResponse> modelInvocations,
        List<RunStageResponse> stages) {

    /**
     * 组装运行详情响应。
     *
     * @param run 运行详情快照
     * @param stages 阶段时间线
     * @param invocations 模型调用记录
     * @return 详情响应
     */
    public static RunDetailResponse from(
            RunDetailSnapshot run, List<RunStageSnapshot> stages, List<ModelInvocationSnapshot> invocations) {
        return new RunDetailResponse(
                run.id(),
                run.kind(),
                run.status(),
                run.currentStage(),
                run.attempt(),
                run.errorCode(),
                run.errorMessage(),
                run.startedAt() == null ? run.createdAt() : run.startedAt(),
                run.finishedAt(),
                invocations.stream().map(ModelInvocationResponse::from).toList(),
                stages.stream().map(RunStageResponse::from).toList());
    }
}
