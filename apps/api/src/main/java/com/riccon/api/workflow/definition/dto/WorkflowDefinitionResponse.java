package com.riccon.api.workflow.definition.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.riccon.api.workflow.definition.domain.WorkflowDefinitionSnapshot;
import com.riccon.platform.domain.workflow.WorkflowDefinitionStatus;
import com.riccon.platform.domain.workflow.WorkflowTemplateSource;
import java.time.Instant;
import java.util.UUID;

/**
 * 流程列表项（契约 {@code WorkflowDefinition}）。
 *
 * <p>只返回元数据，不包含草稿图；列表不返回不可见对象的名称或数量。{@code hasUnpublishedChanges}
 * 由草稿规范化哈希与最近发布版本哈希比较得出，未发布草稿恒为 {@code true}
 * （ADR-0024 §6.2，AC-01/AC-03）。
 *
 * @param id 流程 ID
 * @param name 流程名
 * @param description 说明
 * @param ownerUserId 所有者
 * @param status 流程状态
 * @param draftVersion 草稿版本
 * @param publishedVersionId 最近发布版本 ID
 * @param publishedVersionNo 最近发布版本号
 * @param hasUnpublishedChanges 草稿是否落后于已发布版本
 * @param sourceTemplateId 来源模板 ID
 * @param sourceTemplateType 来源模板类型
 * @param sourceTemplateVersion 来源模板版本
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowDefinitionResponse(
        UUID id,
        String name,
        String description,
        UUID ownerUserId,
        WorkflowDefinitionStatus status,
        int draftVersion,
        UUID publishedVersionId,
        Integer publishedVersionNo,
        boolean hasUnpublishedChanges,
        UUID sourceTemplateId,
        WorkflowTemplateSource sourceTemplateType,
        Integer sourceTemplateVersion,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 从业务快照构造列表项。
     *
     * @param snapshot 流程快照
     * @param hasUnpublishedChanges 草稿是否落后
     * @return 列表项
     */
    public static WorkflowDefinitionResponse from(WorkflowDefinitionSnapshot snapshot, boolean hasUnpublishedChanges) {
        return new WorkflowDefinitionResponse(
                snapshot.id(),
                snapshot.name(),
                snapshot.description(),
                snapshot.ownerUserId(),
                snapshot.status(),
                snapshot.draftVersion(),
                snapshot.publishedVersionId(),
                snapshot.publishedVersionNo(),
                hasUnpublishedChanges,
                snapshot.sourceTemplateId(),
                snapshot.sourceTemplateType() == null
                        ? null
                        : snapshot.sourceTemplateType().toContract(),
                snapshot.sourceTemplateVersion(),
                snapshot.createdAt(),
                snapshot.updatedAt());
    }
}
