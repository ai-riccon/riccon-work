package com.riccon.worker.workflow.tool.domain;

import com.riccon.platform.domain.workflow.WorkflowApprovalActionKind;
import com.riccon.platform.domain.workflow.WorkflowApprovalAssigneeType;
import com.riccon.platform.domain.workflow.WorkflowApprovalStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 工具节点执行所需的审批快照（T07b 只读投影）。
 *
 * <p>包含执行前重算所需的全部字段：审批状态、审批人与发起人、目标工具快照、参数密文/key id、
 * {@code params_sha256}、{@code plan_sha256} 与创建时间（写工具重试重审批的新鲜度判断）。
 * 参数密文只在本进程内用于解密，不写日志、不进入异常消息；记录对外暴露时返回副本。
 *
 * @param id 审批 ID
 * @param nodeKey 审批节点 key（{@code plan_sha256} 的 {@code nodeKey}）
 * @param attempt 审批节点尝试号
 * @param status 审批状态
 * @param assigneeType 审批人类型
 * @param assigneeRoleCode 角色码；用户审批为空
 * @param actionKind 解锁动作；工具审批固定 {@code MCP_TOOL}
 * @param targetNodeKey 解锁目标节点（应为当前工具节点）
 * @param targetToolId 目标工具 ID 快照
 * @param targetToolName 目标工具名快照
 * @param paramsPreview 脱敏参数摘要 JSON
 * @param paramsCiphertext 参数密文；缺失时不可执行
 * @param paramsKeyId 参数密文 key id
 * @param paramsSha256 规范化参数摘要
 * @param planSha256 计划绑定哈希
 * @param requestedBy 发起人
 * @param decidedBy 审批人；未决定为空
 * @param createdAt 审批创建时间（UTC）
 */
public record WorkflowToolApprovalSnapshot(
        UUID id,
        String nodeKey,
        int attempt,
        WorkflowApprovalStatus status,
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
        UUID decidedBy,
        Instant createdAt) {

    /** 校验核心字段并复制密文，避免调用方修改行快照。 */
    public WorkflowToolApprovalSnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(nodeKey, "nodeKey must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(assigneeType, "assigneeType must not be null");
        Objects.requireNonNull(actionKind, "actionKind must not be null");
        Objects.requireNonNull(planSha256, "planSha256 must not be null");
        Objects.requireNonNull(requestedBy, "requestedBy must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        paramsCiphertext = paramsCiphertext == null ? null : paramsCiphertext.clone();
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
    }

    /** 返回密文副本。 */
    @Override
    public byte[] paramsCiphertext() {
        return paramsCiphertext == null ? null : paramsCiphertext.clone();
    }
}
