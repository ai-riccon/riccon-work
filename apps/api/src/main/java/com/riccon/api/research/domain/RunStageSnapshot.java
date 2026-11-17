package com.riccon.api.research.domain;

import com.riccon.platform.domain.research.RunStage;
import com.riccon.platform.domain.research.StageStatus;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 运行阶段时间线条目。
 *
 * <p>{@code details} 只保存白名单摘要（查询 hash、命中数、工具调用 ID、审批 ID、脱敏预览），
 * 不包含提示词、工具结果原文或审批密文（ADR-0017 第 8 节）。
 *
 * @param stage 阶段
 * @param attempt 尝试号
 * @param status 阶段状态
 * @param errorCode 稳定错误码
 * @param startedAt 开始时间
 * @param finishedAt 结束时间
 * @param details 白名单摘要；可能为空
 */
public record RunStageSnapshot(
        RunStage stage,
        int attempt,
        StageStatus status,
        String errorCode,
        Instant startedAt,
        Instant finishedAt,
        Map<String, Object> details) {

    /** 校验必填字段并复制 details，避免调用方修改影响响应。 */
    public RunStageSnapshot {
        Objects.requireNonNull(stage, "stage must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1");
        }
        details = details == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }
}
