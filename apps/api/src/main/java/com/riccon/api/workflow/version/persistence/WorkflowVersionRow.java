package com.riccon.api.workflow.version.persistence;

import com.riccon.platform.domain.workflow.WorkflowRunScope;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code workflow_version} 列表行。
 *
 * <p>只读取展示与范围判定需要的列，不读取 {@code graph_json}；版本不可变，本对象只用于列表响应
 * （ADR-0024 §3.3.2/§4.1）。
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
public record WorkflowVersionRow(
        UUID id,
        UUID workflowId,
        int versionNo,
        WorkflowRunScope runScope,
        UUID departmentId,
        String graphSha256,
        int nodeCount,
        UUID publishedBy,
        Instant publishedAt) {

    /** 校验必填字段。 */
    public WorkflowVersionRow {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(runScope, "runScope must not be null");
        Objects.requireNonNull(graphSha256, "graphSha256 must not be null");
        Objects.requireNonNull(publishedBy, "publishedBy must not be null");
        Objects.requireNonNull(publishedAt, "publishedAt must not be null");
    }
}
