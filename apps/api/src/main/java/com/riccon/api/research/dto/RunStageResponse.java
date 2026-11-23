package com.riccon.api.research.dto;

import com.riccon.api.research.domain.RunStageSnapshot;
import com.riccon.platform.domain.research.RunStage;
import com.riccon.platform.domain.research.StageStatus;
import java.time.Instant;
import java.util.function.UnaryOperator;

/**
 * 运行阶段响应，对应 OpenAPI {@code RunStageRecord}。
 *
 * @param stage 阶段
 * @param attempt 尝试号
 * @param status 状态
 * @param errorCode 稳定错误码
 * @param startedAt 开始时间
 * @param finishedAt 结束时间
 * @param details 白名单摘要
 */
public record RunStageResponse(
        RunStage stage,
        int attempt,
        StageStatus status,
        String errorCode,
        Instant startedAt,
        Instant finishedAt,
        RunStageDetails details) {

    /**
     * 将阶段快照映射为响应。
     *
     * @param stage 阶段快照
     * @return 响应
     */
    public static RunStageResponse from(RunStageSnapshot stage) {
        return from(stage, source -> source);
    }

    /**
     * 将阶段快照映射为响应，并对阶段详情中的来源应用遮蔽。
     *
     * @param stage 阶段快照
     * @param sourceMapper 来源映射（例如按当前 ACL 计算可用性）
     * @return 响应
     */
    public static RunStageResponse from(RunStageSnapshot stage, UnaryOperator<CitationResponse> sourceMapper) {
        RunStageDetails details = RunStageDetails.from(stage.details());
        if (details != null && details.sources() != null && !details.sources().isEmpty()) {
            details = details.withSources(
                    details.sources().stream().map(sourceMapper).toList());
        }
        return new RunStageResponse(
                stage.stage(),
                stage.attempt(),
                stage.status(),
                stage.errorCode(),
                stage.startedAt(),
                stage.finishedAt(),
                details);
    }
}
