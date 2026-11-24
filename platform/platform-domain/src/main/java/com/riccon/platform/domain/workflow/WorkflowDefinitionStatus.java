package com.riccon.platform.domain.workflow;

/**
 * 流程定义状态。
 *
 * <p>M06 不提供删除流程端点：已发布流程只能停用。停用阻止新运行，在途运行继续；历史仍可读
 * （ADR-0024 第 3.3.1/4.8 节，AC-15）。
 */
public enum WorkflowDefinitionStatus {
    /** 正常；可创建新运行。 */
    ACTIVE,
    /** 停用；不可创建新运行，已有运行继续。 */
    DISABLED
}
