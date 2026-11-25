package com.riccon.api.workflow.approval.service;

import com.riccon.api.error.ErrorCodes;
import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * 审批不存在、未分配给当前用户或属于其他租户，HTTP 404 {@code RICCON-TENANT-404}。
 *
 * <p>待办/详情遵循“不可见即不存在”：非 assignee、发起人本人、跨租户与已删除统一 404，不泄露审批、流程
 * 或运行内容（ADR-0024 第 5.3/6.2 节）。
 */
public class WorkflowApprovalNotFoundException extends RicconException {

    /**
     * 创建审批不可见异常。
     *
     * @param detail 脱敏说明
     */
    public WorkflowApprovalNotFoundException(String detail) {
        super(HttpStatus.NOT_FOUND, ErrorCodes.TENANT_NOT_FOUND, "Not Found", detail);
    }
}
