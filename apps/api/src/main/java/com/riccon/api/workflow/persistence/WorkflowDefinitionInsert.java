package com.riccon.api.workflow.persistence;

import com.riccon.api.workflow.definition.domain.WorkflowDefinitionSourceType;
import com.riccon.api.workflow.domain.WorkflowGraph;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 新建流程草稿的写入参数。
 *
 * <p>新草稿固定 {@code draft_version=1}、{@code status=ACTIVE}、无发布版本；发布字段只能由 T04 的发布
 * 事务写入，本类不提供覆盖入口（ADR-0024 §3.3.1/§4.1）。
 *
 * @param id 流程 ID
 * @param name 流程名
 * @param description 说明
 * @param ownerUserId 所有者
 * @param draftGraph 初始草稿图
 * @param sourceTemplateId 来源模板 ID
 * @param sourceTemplateType 来源模板类型
 * @param sourceTemplateVersion 来源模板版本
 * @param actorId 创建人/更新人
 * @param now 创建时间
 */
public record WorkflowDefinitionInsert(
        UUID id,
        String name,
        String description,
        UUID ownerUserId,
        WorkflowGraph draftGraph,
        UUID sourceTemplateId,
        WorkflowDefinitionSourceType sourceTemplateType,
        Integer sourceTemplateVersion,
        UUID actorId,
        Instant now) {

    /** 校验必填字段与时间。 */
    public WorkflowDefinitionInsert {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
        Objects.requireNonNull(draftGraph, "draftGraph must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(now, "now must not be null");
    }
}
