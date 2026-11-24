package com.riccon.api.workflow.publish.domain;

import com.riccon.platform.domain.workflow.WorkflowRunScope;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 发布版本写入参数。
 *
 * <p>版本行只插入、不更新也不删除；{@code graphJson} 必须是 {@code WorkflowGraphJson.toJson} 的规范化
 * 结果，{@code graphSha256} 必须与同一规范化文本一致，保证运行绑定与草稿落后判定使用同一算法
 * （ADR-0024 §3.3.2/§4.1，TM-73）。
 *
 * @param id 版本 ID（UUIDv7）
 * @param workflowId 流程 ID
 * @param versionNo 版本号，从 1 递增
 * @param graphJson 规范化图快照
 * @param graphSha256 规范化图 SHA-256（小写 hex）
 * @param nodeCount 节点数
 * @param runScope 运行范围
 * @param departmentId 部门范围 ID；其他范围为空
 * @param publishedBy 发布人
 * @param publishedAt 发布时间
 */
public record WorkflowVersionInsert(
        UUID id,
        UUID workflowId,
        int versionNo,
        String graphJson,
        String graphSha256,
        int nodeCount,
        WorkflowRunScope runScope,
        UUID departmentId,
        UUID publishedBy,
        Instant publishedAt) {

    /** 校验必填字段与基本范围。 */
    public WorkflowVersionInsert {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(graphJson, "graphJson must not be null");
        Objects.requireNonNull(graphSha256, "graphSha256 must not be null");
        Objects.requireNonNull(runScope, "runScope must not be null");
        Objects.requireNonNull(publishedBy, "publishedBy must not be null");
        Objects.requireNonNull(publishedAt, "publishedAt must not be null");
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
