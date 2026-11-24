package com.riccon.api.workflow.definition.domain;

import com.riccon.api.workflow.domain.WorkflowGraph;
import com.riccon.platform.domain.workflow.WorkflowDefinitionStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 流程定义快照（不含发布版本明细）。
 *
 * <p>由仓储从 {@code workflow_definition} 行映射而来，供服务层做资源级判定与响应组装。
 * {@code publishedGraphSha256} 来自同一查询的发布版本哈希，用于判断草稿是否落后于已发布版本；
 * 未发布时为空（ADR-0024 §3.3.1/§4.1）。
 *
 * @param id 流程 ID
 * @param name 流程名
 * @param description 说明
 * @param ownerUserId 所有者
 * @param draftVersion 草稿乐观锁版本
 * @param draftGraph 当前草稿图
 * @param status 流程状态
 * @param publishedVersionId 最近发布版本 ID
 * @param publishedVersionNo 最近发布版本号
 * @param publishedGraphSha256 最近发布版本的规范化图哈希
 * @param sourceTemplateId 来源模板 ID
 * @param sourceTemplateType 来源模板类型
 * @param sourceTemplateVersion 来源模板版本
 * @param createdBy 创建人
 * @param updatedBy 最近修改人
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record WorkflowDefinitionSnapshot(
        UUID id,
        String name,
        String description,
        UUID ownerUserId,
        int draftVersion,
        WorkflowGraph draftGraph,
        WorkflowDefinitionStatus status,
        UUID publishedVersionId,
        Integer publishedVersionNo,
        String publishedGraphSha256,
        UUID sourceTemplateId,
        WorkflowDefinitionSourceType sourceTemplateType,
        Integer sourceTemplateVersion,
        UUID createdBy,
        UUID updatedBy,
        Instant createdAt,
        Instant updatedAt) {

    /** 校验必填字段。 */
    public WorkflowDefinitionSnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
        Objects.requireNonNull(draftGraph, "draftGraph must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdBy, "createdBy must not be null");
        Objects.requireNonNull(updatedBy, "updatedBy must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (draftVersion < 1) {
            throw new IllegalArgumentException("draftVersion must be >= 1");
        }
    }

    /**
     * 判断当前操作者是否为流程所有者。
     *
     * @param userId 操作者 ID
     * @return 是所有者时返回 {@code true}
     */
    public boolean isOwnedBy(UUID userId) {
        return ownerUserId.equals(userId);
    }
}
