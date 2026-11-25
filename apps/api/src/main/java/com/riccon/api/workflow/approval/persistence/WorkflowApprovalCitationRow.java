package com.riccon.api.workflow.approval.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 审批数据范围摘要使用的节点引用快照行。
 *
 * <p>批量读取一页待办涉及的运行节点引用，供服务层按每个审批的创建时间过滤上游节点，避免逐条审批
 * 查询造成 N+1（REV-TASK-0229-001 F-003）。只含运行 ID、完成时间与引用 JSON，不含节点输出。
 *
 * @param workflowRunId 运行 ID
 * @param finishedAt 节点完成时间
 * @param citationsJson 引用快照 JSON
 */
public record WorkflowApprovalCitationRow(UUID workflowRunId, Instant finishedAt, String citationsJson) {

    /** 校验必填字段。 */
    public WorkflowApprovalCitationRow {
        Objects.requireNonNull(workflowRunId, "workflowRunId must not be null");
        Objects.requireNonNull(finishedAt, "finishedAt must not be null");
        Objects.requireNonNull(citationsJson, "citationsJson must not be null");
    }
}
