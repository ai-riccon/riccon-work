package com.riccon.api.workflow.validation;

import com.riccon.platform.domain.workflow.WorkflowApprovalAssigneeType;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 审批人可用性查询。
 *
 * <p>只在下发校验时调用：按节点配置解析本租户 {@code ACTIVE} 账号，并排除当前操作者（发布人或运行发起人），
 * 用于阻断“只能自批”的流程（AC-06、ADR-0024 §5.2）。
 *
 * @param assigneeType 审批人解析方式
 * @param assigneeRole 角色码；仅 {@code ROLE} 使用
 * @param assigneeUserIds 用户 ID；仅 {@code USER} 使用
 * @param excludedUserId 需要排除的账号（发布人/发起人）
 */
public record WorkflowApproverQuery(
        WorkflowApprovalAssigneeType assigneeType,
        String assigneeRole,
        List<UUID> assigneeUserIds,
        UUID excludedUserId) {

    /** 校验必填字段并复制集合。 */
    public WorkflowApproverQuery {
        Objects.requireNonNull(assigneeType, "assigneeType must not be null");
        Objects.requireNonNull(excludedUserId, "excludedUserId must not be null");
        assigneeUserIds = assigneeUserIds == null ? List.of() : List.copyOf(assigneeUserIds);
    }
}
