package com.riccon.api.workflow.run.domain;

import com.riccon.api.workflow.domain.WorkflowGraph;
import com.riccon.platform.domain.workflow.WorkflowRunScope;
import java.util.Objects;
import java.util.UUID;

/**
 * 运行准入使用的发布版本快照。
 *
 * <p>关键点是读取“用户所选版本”的 {@code run_scope + department_id}，而不是最近发布版本或草稿范围；
 * 同一流程不同版本可以有不同范围（v1=ONLY_SELF、v2=TENANT），运行准入必须按实际绑定版本判定
 * （ADR-0024 §6.2，T04 评审约束）。
 *
 * @param id 版本 ID
 * @param workflowId 所属流程 ID
 * @param versionNo 版本号
 * @param runScope 运行范围
 * @param departmentId 部门范围 ID；非 DEPARTMENT 为空
 * @param nodeCount 节点数
 * @param graph 发布时的规范化图快照
 */
public record WorkflowRunVersionSnapshot(
        UUID id,
        UUID workflowId,
        int versionNo,
        WorkflowRunScope runScope,
        UUID departmentId,
        int nodeCount,
        WorkflowGraph graph) {

    /** 校验必填字段与范围/部门一致性。 */
    public WorkflowRunVersionSnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(runScope, "runScope must not be null");
        Objects.requireNonNull(graph, "graph must not be null");
        if (versionNo < 1) {
            throw new IllegalArgumentException("versionNo must be >= 1");
        }
        if (nodeCount < 1) {
            throw new IllegalArgumentException("nodeCount must be >= 1");
        }
        if (runScope == WorkflowRunScope.DEPARTMENT && departmentId == null) {
            throw new IllegalArgumentException("departmentId is required for DEPARTMENT scope");
        }
        if (runScope != WorkflowRunScope.DEPARTMENT && departmentId != null) {
            throw new IllegalArgumentException("departmentId must be null unless scope is DEPARTMENT");
        }
    }
}
