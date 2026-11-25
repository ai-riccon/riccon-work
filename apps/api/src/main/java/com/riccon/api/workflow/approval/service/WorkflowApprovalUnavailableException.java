package com.riccon.api.workflow.approval.service;

import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * 编排关闭，审批决定不受理，HTTP 503 {@code RICCON-WF-503}。
 *
 * <p>ADR-0024 第 10.1 节：关闭开关后新运行、重试与审批决定返回 503，取消仍可用；待办与详情保持只读，
 * 便于管理员排障。关闭原因只来自部署配置，租户不可修改。
 */
public class WorkflowApprovalUnavailableException extends RicconException {

    /** 关闭态未配置原因时的固定文案。 */
    public static final String DEFAULT_MESSAGE = "workflow orchestration is disabled by deployment configuration";

    /**
     * 创建编排关闭异常。
     *
     * @param reason 部署配置的关闭原因；为空时使用固定文案
     */
    public WorkflowApprovalUnavailableException(String reason) {
        super(
                HttpStatus.SERVICE_UNAVAILABLE,
                "RICCON-WF-503",
                "Workflow orchestration unavailable",
                reason == null || reason.isBlank() ? DEFAULT_MESSAGE : reason);
    }
}
