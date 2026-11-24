package com.riccon.api.workflow.persistence;

import com.riccon.api.workflow.definition.domain.WorkflowDefinitionSourceType;
import com.riccon.api.workflow.domain.WorkflowGraph;
import com.riccon.platform.domain.workflow.WorkflowDefinitionStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code workflow_definition} 行快照（含草稿图与发布哈希）。
 *
 * <p>只在 {@code workflow/persistence} 与仓储之间传递；服务层使用
 * {@link com.riccon.api.workflow.definition.domain.WorkflowDefinitionSnapshot}，避免把数据库行类型泄漏到
 * 业务层（ADR-0015 分层）。{@code publishedGraphSha256} 来自同查询的发布版本 JOIN，未发布时为空。
 *
 * @param id 流程 ID
 * @param name 流程名
 * @param description 说明
 * @param ownerUserId 所有者
 * @param draftVersion 草稿乐观锁版本
 * @param draftGraph 草稿图
 * @param status 流程状态
 * @param publishedVersionId 最近发布版本 ID
 * @param publishedVersionNo 最近发布版本号
 * @param publishedGraphSha256 最近发布版本图哈希
 * @param sourceTemplateId 来源模板 ID
 * @param sourceTemplateType 来源模板类型
 * @param sourceTemplateVersion 来源模板版本
 * @param createdBy 创建人
 * @param updatedBy 最近修改人
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record WorkflowDefinitionRow(
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
    public WorkflowDefinitionRow {
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
}
