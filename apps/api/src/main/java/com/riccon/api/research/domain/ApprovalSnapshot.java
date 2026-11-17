package com.riccon.api.research.domain;

import com.riccon.platform.domain.research.ApprovalStatus;
import com.riccon.platform.domain.research.RunStage;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 审批快照。
 *
 * <p>只映射公开字段：{@code params_preview} 是脱敏摘要，{@code params_ciphertext} 与 {@code params_key_id}
 * 仅在 Worker 重放时使用，任何 API、日志与审计都不得返回（ADR-0017 第 1、6 节）。因此本记录不包含密文列，
 * SQL 也不查询它们。
 *
 * @param id 审批 ID
 * @param researchTaskId 报告任务 ID
 * @param runId 运行 ID
 * @param stage 审批所在阶段
 * @param attempt 阶段尝试号
 * @param status 审批状态
 * @param toolName 工具名快照
 * @param paramsPreview 脱敏入参摘要
 * @param paramsSha256 规范化入参 SHA-256
 * @param requestedBy 发起人
 * @param decidedBy 审批人
 * @param decidedAt 审批时间
 * @param decisionComment 审批备注
 * @param expiresAt 过期时间
 * @param createdAt 创建时间
 */
public record ApprovalSnapshot(
        UUID id,
        UUID researchTaskId,
        UUID runId,
        RunStage stage,
        int attempt,
        ApprovalStatus status,
        String toolName,
        Map<String, Object> paramsPreview,
        String paramsSha256,
        UUID requestedBy,
        UUID decidedBy,
        Instant decidedAt,
        String decisionComment,
        Instant expiresAt,
        Instant createdAt) {

    /** 校验必填字段并复制预览，避免共享可变 Map。 */
    public ApprovalSnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(researchTaskId, "researchTaskId must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(stage, "stage must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(paramsSha256, "paramsSha256 must not be null");
        Objects.requireNonNull(requestedBy, "requestedBy must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1");
        }
        paramsPreview =
                paramsPreview == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(paramsPreview));
    }
}
