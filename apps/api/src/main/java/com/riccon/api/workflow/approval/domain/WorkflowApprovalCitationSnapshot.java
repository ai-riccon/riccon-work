package com.riccon.api.workflow.approval.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 审批数据范围摘要使用的节点引用快照（仓储投影）。
 *
 * <p>由审批仓储批量读取一页待办涉及的运行节点引用并映射为本记录，供服务层按每个审批的创建时间过滤
 * 上游节点；服务只依赖 {@code ..repository..} 与本领域记录，不直接接触 {@code ..persistence..}，
 * 保持 Controller → Service → Repository → Persistence 单向分层（ModuleBoundaryTest）。
 *
 * <p>只含运行 ID、节点完成时间与引用 JSON，不含节点输出、完整参数或文档正文
 * （REV-TASK-0229-001 F-003、REV-TASK-0228-002 F-006）。
 *
 * @param workflowRunId 运行 ID
 * @param finishedAt 节点完成时间
 * @param citationsJson 引用快照 JSON
 */
public record WorkflowApprovalCitationSnapshot(UUID workflowRunId, Instant finishedAt, String citationsJson) {

    /** 校验必填字段。 */
    public WorkflowApprovalCitationSnapshot {
        Objects.requireNonNull(workflowRunId, "workflowRunId must not be null");
        Objects.requireNonNull(finishedAt, "finishedAt must not be null");
        Objects.requireNonNull(citationsJson, "citationsJson must not be null");
    }
}
