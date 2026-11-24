package com.riccon.api.workflow.run.domain;

import com.riccon.platform.domain.workflow.WorkflowDefinitionStatus;
import java.util.Objects;
import java.util.UUID;

/**
 * 运行准入所需的流程定义摘要。
 *
 * <p>只读取运行授权需要的字段：所有者、启用状态与最近发布版本指针；不读取草稿图，避免把编辑态数据
 * 带进运行准入（ADR-0024 §4.1/§6.2）。
 *
 * @param id 流程 ID
 * @param name 流程名（响应展示用）
 * @param ownerUserId 流程所有者
 * @param status 流程状态
 * @param publishedVersionId 最近发布版本 ID；未发布时为空
 */
public record WorkflowRunDefinitionSnapshot(
        UUID id, String name, UUID ownerUserId, WorkflowDefinitionStatus status, UUID publishedVersionId) {

    /** 校验必填字段。 */
    public WorkflowRunDefinitionSnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(ownerUserId, "ownerUserId must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }
}
