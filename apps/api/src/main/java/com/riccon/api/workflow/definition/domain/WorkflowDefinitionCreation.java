package com.riccon.api.workflow.definition.domain;

import com.riccon.api.workflow.domain.WorkflowGraph;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 新建草稿命令（业务层口径）。
 *
 * <p>由服务层在完成权限、名称、模板解析与 ID 生成后构造；{@code draftVersion} 固定为 1，发布字段为空。
 * 仓储负责转换为持久化写入参数，避免持久化类型进入业务层。
 *
 * @param id 流程 ID
 * @param name 流程名
 * @param description 说明
 * @param ownerUserId 所有者
 * @param graph 初始草稿图
 * @param sourceTemplateId 来源模板 ID
 * @param sourceTemplateType 来源模板类型
 * @param sourceTemplateVersion 来源模板版本
 * @param actorId 创建人
 * @param now 创建时间
 */
public record WorkflowDefinitionCreation(
        UUID id,
        String name,
        String description,
        UUID ownerUserId,
        WorkflowGraph graph,
        UUID sourceTemplateId,
        WorkflowDefinitionSourceType sourceTemplateType,
        Integer sourceTemplateVersion,
        UUID actorId,
        Instant now) {

    /** 校验必填字段。 */
    public WorkflowDefinitionCreation {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
        Objects.requireNonNull(graph, "graph must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(now, "now must not be null");
    }
}
