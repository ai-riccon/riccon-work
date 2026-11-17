package com.riccon.worker.research.domain;

import com.riccon.platform.domain.research.ApprovalStatus;
import java.util.UUID;

/**
 * 审批快照。
 *
 * <p>{@code paramsPreviewJson} 只是脱敏摘要，供审批 UI 与阶段时间线展示；
 * {@code paramsCiphertext} 与 {@code paramsKeyId} 是完整参数的加密重放数据，只允许
 * {@code ApprovalParamsCrypto} 在审批通过后读取，不得通过 API 返回或写入日志。
 *
 * @param id 审批 ID
 * @param runId 运行 ID
 * @param researchTaskId 报告任务 ID
 * @param stage 审批阶段
 * @param attemptNo 阶段尝试号
 * @param status 状态
 * @param mcpToolId 工具 ID
 * @param toolName 工具名快照
 * @param paramsPreviewJson 脱敏入参预览 JSON
 * @param paramsCiphertext 完整参数密文；历史行可为空
 * @param paramsKeyId 加密所用租户 DEK 标识；历史行可为空
 * @param paramsSha256 完整参数摘要
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
        byte[] paramsCiphertext,
        String paramsKeyId,
        String paramsSha256,
        UUID requestedBy) {

    /** 防御性复制密文，避免调用方修改后影响解密。 */
    public ApprovalRecord {
        paramsCiphertext = paramsCiphertext == null ? null : paramsCiphertext.clone();
    }

    /** 返回密文副本。 */
    @Override
    public byte[] paramsCiphertext() {
        return paramsCiphertext == null ? null : paramsCiphertext.clone();
    }
}
