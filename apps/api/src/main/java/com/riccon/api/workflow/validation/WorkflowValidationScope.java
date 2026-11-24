package com.riccon.api.workflow.validation;

/**
 * 流程图校验阶段。
 *
 * <p>保存、发布与运行前共用同一校验器，只有审批人可用性随阶段不同：保存允许草稿暂时没有可用审批人，
 * 发布必须阻断（AC-06、ADR-0024 §5.2），运行前复检不因无审批人拒绝创建运行，而由运行保持等待并返回
 * {@code WF_NO_APPROVER}。节点结构、必填参数、模型、工具、条件与结果在三个阶段始终一致（AC-02/09/18/24）。
 */
public enum WorkflowValidationScope {

    /** 保存草稿：拒绝非法图，但允许暂无可用审批人的未完成审批配置。 */
    SAVE,

    /** 发布版本：在保存规则之上要求存在非发布者的可用审批人。 */
    PUBLISH,

    /** 运行前复检：与保存规则一致，审批人缺失由运行等待处理。 */
    RUNTIME;

    /**
     * 判断当前阶段是否检查审批人可用性。
     *
     * @return 仅发布阶段返回 {@code true}
     */
    public boolean checksApproverAvailability() {
        return this == PUBLISH;
    }
}
