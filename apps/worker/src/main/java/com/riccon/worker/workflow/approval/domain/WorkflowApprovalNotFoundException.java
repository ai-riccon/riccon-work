package com.riccon.worker.workflow.approval.domain;

/**
 * 审批不存在或对调用者不可见。
 *
 * <p>跨租户、平台管理员、已取消/到期或未分配给他人的审批统一按“不存在”处理，避免通过审批 ID
 * 探测其他租户或运行内容（ADR-0024 §5.3/§6.2）。API 层 T09 把本异常映射为
 * {@code 404 RICCON-TENANT-404}，Worker 侧不暴露 HTTP 状态码。
 */
public class WorkflowApprovalNotFoundException extends RuntimeException {

    /** 被查询的审批 ID。 */
    private final String approvalId;

    /**
     * 创建不存在异常。
     *
     * @param approvalId 审批 ID
     */
    public WorkflowApprovalNotFoundException(String approvalId) {
        super("workflow approval is not available");
        this.approvalId = approvalId;
    }

    /**
     * 返回审批 ID。
     *
     * @return 审批 ID
     */
    public String approvalId() {
        return approvalId;
    }
}
