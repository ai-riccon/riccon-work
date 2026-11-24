package com.riccon.api.workflow.definition.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.riccon.api.workflow.definition.domain.WorkflowPublishedVersionSnapshot;
import com.riccon.platform.domain.workflow.WorkflowRunScope;
import java.time.Instant;
import java.util.UUID;

/**
 * 发布版本摘要（契约 {@code WorkflowVersion}）。
 *
 * <p>只读；版本不可变。M06 T02 只在流程详情中填充最近发布版本，版本列表与发布写入属于 T04。
 *
 * @param id 版本 ID
 * @param workflowId 流程 ID
 * @param versionNo 版本号
 * @param runScope 运行范围
 * @param departmentId 部门范围 ID
 * @param graphSha256 规范化图哈希
 * @param nodeCount 节点数
 * @param publishedBy 发布人
 * @param publishedAt 发布时间
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowVersionSummaryResponse(
        UUID id,
        UUID workflowId,
        int versionNo,
        WorkflowRunScope runScope,
        UUID departmentId,
        String graphSha256,
        int nodeCount,
        UUID publishedBy,
        Instant publishedAt) {

    /**
     * 从业务快照构造版本摘要。
     *
     * @param snapshot 发布版本快照
     * @return 版本摘要
     */
    public static WorkflowVersionSummaryResponse from(WorkflowPublishedVersionSnapshot snapshot) {
        return new WorkflowVersionSummaryResponse(
                snapshot.id(),
                snapshot.workflowId(),
                snapshot.versionNo(),
                snapshot.runScope(),
                snapshot.departmentId(),
                snapshot.graphSha256(),
                snapshot.nodeCount(),
                snapshot.publishedBy(),
                snapshot.publishedAt());
    }
}
