package com.riccon.worker.research.domain;

import com.riccon.platform.domain.research.ApprovalStatus;
import java.util.UUID;

/**
 * 审批快照。
 *
 * @param id 审批 ID
 * @param runId 运行 ID
 * @param researchTaskId 报告任务 ID
 * @param stage 审批阶段
 * @param attemptNo 阶段尝试号
 * @param status 状态
 * @param mcpToolId 工具 ID
 * @param toolName 工具名快照
 * @param paramsPreviewJson 脱敏入参预览
 * @param paramsSha256 入参摘要
 * @param requestedBy 发起人
 */
public record ApprovalRecord(
        UUID id,
        UUID runId,
        UUID researchTaskId,
        String stage,
        int attemptNo,
        ApprovalStatus status,
        UUID mcpToolId,
        String toolName,
        String paramsPreviewJson,
        String paramsSha256,
        UUID requestedBy) {}
