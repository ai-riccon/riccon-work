package com.riccon.worker.workflow.approval.domain;

import com.riccon.platform.domain.workflow.WorkflowApprovalActionKind;
import com.riccon.platform.domain.workflow.WorkflowApprovalAssigneeType;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * 新建审批记录的持久化参数。
 *
 * <p>{@code paramsCiphertext}/{@code paramsKeyId}/{@code paramsSha256} 仅在
 * {@code actionKind=MCP_TOOL} 时填写；密文使用 ADR-0017 信封加密，AAD 为
 * {@code tenantId|approvalId}，不保存明文（ADR-0024 §3.3.5）。{@code paramsPreview} 必须是
 * 已脱敏 JSON（≤16 键、单值 ≤200 字符、≤4096 字节）。
 *
 * @param id 审批 ID（UUIDv7）
 * @param workflowRunId 运行 ID
 * @param workflowId 流程 ID
 * @param nodeKey 审批节点 key
 * @param attempt 节点尝试号
 * @param assigneeType 审批人类型
 * @param assigneeRoleCode 角色码；用户审批为空
 * @param actionKind 解锁动作
 * @param targetNodeKey 解锁目标节点；可为空
 * @param targetToolId 目标工具 ID；仅 MCP_TOOL
 * @param targetToolName 目标工具名快照；仅 MCP_TOOL
 * @param paramsPreview 脱敏参数摘要 JSON
 * @param paramsCiphertext 参数密文；仅 MCP_TOOL
 * @param paramsKeyId 参数密文 key id；仅 MCP_TOOL
 * @param paramsSha256 规范化参数 SHA-256；仅 MCP_TOOL
 * @param planSha256 计划绑定哈希
 * @param requestedBy 发起人
 * @param now 创建时间（UTC）
 */
public record WorkflowApprovalInsert(
        UUID id,
        UUID workflowRunId,
        UUID workflowId,
        String nodeKey,
        int attempt,
        WorkflowApprovalAssigneeType assigneeType,
        String assigneeRoleCode,
        WorkflowApprovalActionKind actionKind,
        String targetNodeKey,
        UUID targetToolId,
        String targetToolName,
        String paramsPreview,
        byte[] paramsCiphertext,
        String paramsKeyId,
        String paramsSha256,
        String planSha256,
        UUID requestedBy,
        Instant now) {

    /** 校验必填字段与审批人类型一致性；防御性复制密文。 */
    public WorkflowApprovalInsert {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(workflowRunId, "workflowRunId must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(nodeKey, "nodeKey must not be null");
        Objects.requireNonNull(assigneeType, "assigneeType must not be null");
        Objects.requireNonNull(actionKind, "actionKind must not be null");
        Objects.requireNonNull(paramsPreview, "paramsPreview must not be null");
        Objects.requireNonNull(planSha256, "planSha256 must not be null");
        Objects.requireNonNull(requestedBy, "requestedBy must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        if (assigneeType == WorkflowApprovalAssigneeType.USER && assigneeRoleCode != null) {
            throw new IllegalArgumentException("user approval must not set assigneeRoleCode");
        }
        if (assigneeType == WorkflowApprovalAssigneeType.ROLE && assigneeRoleCode == null) {
            throw new IllegalArgumentException("role approval requires assigneeRoleCode");
        }
        paramsCiphertext = paramsCiphertext == null ? null : paramsCiphertext.clone();
    }

    /**
     * 返回参数密文副本。
     *
     * @return 密文副本；未设置时为空
     */
    @Override
    public byte[] paramsCiphertext() {
        return paramsCiphertext == null ? null : Arrays.copyOf(paramsCiphertext, paramsCiphertext.length);
    }
}
