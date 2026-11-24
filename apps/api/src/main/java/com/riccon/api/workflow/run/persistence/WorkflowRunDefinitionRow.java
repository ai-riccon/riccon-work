package com.riccon.api.workflow.run.persistence;

import com.riccon.platform.domain.workflow.WorkflowDefinitionStatus;
import java.util.UUID;

/**
 * 运行准入使用的流程定义行。
 *
 * @param id 流程 ID
 * @param name 流程名
 * @param ownerUserId 所有者
 * @param status 流程状态
 * @param publishedVersionId 最近发布版本 ID
 */
public record WorkflowRunDefinitionRow(
        UUID id, String name, UUID ownerUserId, WorkflowDefinitionStatus status, UUID publishedVersionId) {}
