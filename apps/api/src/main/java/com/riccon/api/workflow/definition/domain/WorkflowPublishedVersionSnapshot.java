package com.riccon.api.workflow.definition.domain;

import com.riccon.platform.domain.workflow.WorkflowRunScope;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 已发布版本摘要。
 *
 * <p>只读取展示与范围判定需要的字段，不读取 {@code graph_json}；运行绑定与执行属于 T05/T06，发布写入
 * 属于 T04。实例按发布版本不可变，读取后不更新（ADR-0024 §3.3.2/§4.1）。
 *
 * @param id 版本 ID
 * @param workflowId 流程 ID
 * @param versionNo 版本号
 * @param runScope 运行范围
 * @param departmentId 部门范围 ID；其他范围为空
 * @param graphSha256 规范化图哈希
 * @param nodeCount 节点数
 * @param publishedBy 发布人
 * @param publishedAt 发布时间
 */
public record WorkflowPublishedVersionSnapshot(
        UUID id,
        UUID workflowId,
        int versionNo,
        WorkflowRunScope runScope,
        UUID departmentId,
        String graphSha256,
        int nodeCount,
        UUID publishedBy,
        Instant publishedAt) {

    /** 校验必填字段与版本号。 */
    public WorkflowPublishedVersionSnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(runScope, "runScope must not be null");
        Objects.requireNonNull(graphSha256, "graphSha256 must not be null");
        Objects.requireNonNull(publishedBy, "publishedBy must not be null");
        Objects.requireNonNull(publishedAt, "publishedAt must not be null");
        if (versionNo < 1) {
            throw new IllegalArgumentException("versionNo must be >= 1");
        }
        if (nodeCount < 1) {
            throw new IllegalArgumentException("nodeCount must be >= 1");
        }
    }
}
