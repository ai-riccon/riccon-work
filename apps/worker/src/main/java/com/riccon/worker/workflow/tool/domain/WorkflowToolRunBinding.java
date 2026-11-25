package com.riccon.worker.workflow.tool.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 运行绑定的流程/版本（{@code plan_sha256} 重算输入）。
 *
 * <p>执行必须使用运行启动时绑定的发布版本，而不是流程当前草稿；版本 ID 与审批创建时一致，
 * 否则视为计划漂移。
 *
 * @param workflowId 流程 ID
 * @param workflowVersionId 发布版本 ID
 */
public record WorkflowToolRunBinding(UUID workflowId, UUID workflowVersionId) {

    /** 校验必填字段。 */
    public WorkflowToolRunBinding {
        Objects.requireNonNull(workflowId, "workflowId must not be null");
        Objects.requireNonNull(workflowVersionId, "workflowVersionId must not be null");
    }
}
