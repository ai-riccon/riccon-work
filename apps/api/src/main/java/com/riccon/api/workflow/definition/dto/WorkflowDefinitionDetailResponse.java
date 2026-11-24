package com.riccon.api.workflow.definition.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.riccon.api.workflow.domain.WorkflowGraph;
import com.riccon.platform.domain.workflow.WorkflowDefinitionStatus;
import com.riccon.platform.domain.workflow.WorkflowTemplateSource;
import java.time.Instant;
import java.util.UUID;

/**
 * 流程详情（契约 {@code WorkflowDefinitionDetail}）。
 *
 * <p>在列表字段基础上追加草稿图、最近发布版本与能力标记。{@code draftGraph} 只对所有者与租户管理员返回
 * （ADR-0024 §6.2）；其他可运行用户看到的详情不包含草稿内容。{@code canEdit}/{@code canPublish}/
 * {@code canRun} 由服务层按所有者、角色权限与发布范围计算，供 UI 禁用入口。
 *
 * @param id 流程 ID
 * @param name 流程名
 * @param description 说明
 * @param ownerUserId 所有者
 * @param status 流程状态
 * @param draftVersion 草稿版本
 * @param publishedVersionId 最近发布版本 ID
 * @param publishedVersionNo 最近发布版本号
 * @param hasUnpublishedChanges 草稿是否落后
 * @param sourceTemplateId 来源模板 ID
 * @param sourceTemplateType 来源模板类型
 * @param sourceTemplateVersion 来源模板版本
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @param draftGraph 草稿图；仅所有者/租户管理员
 * @param latestVersion 最近发布版本；未发布时为空
 * @param canEdit 是否可编辑
 * @param canPublish 是否可发布
 * @param canRun 是否可运行
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowDefinitionDetailResponse(
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
        Instant updatedAt,
        WorkflowGraph draftGraph,
        WorkflowVersionSummaryResponse latestVersion,
        boolean canEdit,
        boolean canPublish,
        boolean canRun) {

    /**
     * 从摘要与附加字段构造详情。
     *
     * @param summary 列表项
     * @param draftGraph 草稿图；不可见时为空
     * @param latestVersion 最近发布版本；未发布时为空
     * @param canEdit 是否可编辑
     * @param canPublish 是否可发布
     * @param canRun 是否可运行
     * @return 流程详情
     */
    public static WorkflowDefinitionDetailResponse from(
            WorkflowDefinitionResponse summary,
            WorkflowGraph draftGraph,
            WorkflowVersionSummaryResponse latestVersion,
            boolean canEdit,
            boolean canPublish,
            boolean canRun) {
        return new WorkflowDefinitionDetailResponse(
                summary.id(),
                summary.name(),
                summary.description(),
                summary.ownerUserId(),
                summary.status(),
                summary.draftVersion(),
                summary.publishedVersionId(),
                summary.publishedVersionNo(),
                summary.hasUnpublishedChanges(),
                summary.sourceTemplateId(),
                summary.sourceTemplateType(),
                summary.sourceTemplateVersion(),
                summary.createdAt(),
                summary.updatedAt(),
                draftGraph,
                latestVersion,
                canEdit,
                canPublish,
                canRun);
    }
}
