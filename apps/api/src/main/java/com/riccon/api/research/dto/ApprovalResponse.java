package com.riccon.api.research.dto;

import com.riccon.api.research.domain.ApprovalSnapshot;
import com.riccon.platform.domain.research.ApprovalStatus;
import com.riccon.platform.domain.research.RunStage;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 审批响应，对应 OpenAPI {@code Approval}。
 *
 * <p>只包含 {@code paramsPreview} 脱敏摘要；{@code params_ciphertext} 与 {@code params_key_id} 永不出现在响应中
 * （ADR-0017 第 1 节）。
 *
 * @param id 审批 ID
 * @param researchTaskId 报告任务 ID
 * @param runId 运行 ID
 * @param stage 审批阶段
 * @param attempt 阶段尝试号
 * @param status 审批状态
 * @param actionType 动作类型，M02 固定 MCP_TOOL_CALL
 * @param toolName 工具名
 * @param paramsPreview 脱敏入参摘要
 * @param paramsSha256 规范化入参 SHA-256
 * @param requestedBy 发起人
 * @param decidedBy 审批人
 * @param decidedAt 审批时间
 * @param decisionComment 审批备注
 * @param expiresAt 过期时间
 * @param createdAt 创建时间
 */
public record ApprovalResponse(
        UUID id,
        UUID researchTaskId,
        UUID runId,
        RunStage stage,
        int attempt,
        ApprovalStatus status,
        String actionType,
        String toolName,
        Map<String, Object> paramsPreview,
        String paramsSha256,
        UUID requestedBy,
        UUID decidedBy,
        Instant decidedAt,
        String decisionComment,
        Instant expiresAt,
        Instant createdAt) {

    /** M02 审批动作类型固定为外部工具调用。 */
    public static final String ACTION_MCP_TOOL_CALL = "MCP_TOOL_CALL";

    /**
     * 将审批快照映射为响应。
     *
     * @param approval 审批快照
     * @return 响应
     */
    public static ApprovalResponse from(ApprovalSnapshot approval) {
        return new ApprovalResponse(
                approval.id(),
                approval.researchTaskId(),
                approval.runId(),
                approval.stage(),
                approval.attempt(),
                approval.status(),
                ACTION_MCP_TOOL_CALL,
                approval.toolName(),
                PreviewMaps.limit(approval.paramsPreview()),
                approval.paramsSha256(),
                approval.requestedBy(),
                approval.decidedBy(),
                approval.decidedAt(),
                approval.decisionComment(),
                approval.expiresAt(),
                approval.createdAt());
    }
}
