package com.riccon.api.workflow.run.service;

import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * 编排功能关闭，HTTP 503 {@code RICCON-WF-503}。
 *
 * <p>{@code RICCON_WORKFLOW_ENABLED=false} 时只拒绝新运行、重试等新受理动作；取消、列表与历史保持可用，
 * 避免关闭开关后无法清空在途运行（ADR-0024 §4.8，AC-23）。
 */
public class WorkflowRunUnavailableException extends RicconException {

    /**
     * 创建编排关闭异常。
     *
     * @param disabledReason 部署配置的关闭原因；为空时使用固定说明
     */
    public WorkflowRunUnavailableException(String disabledReason) {
        super(
                HttpStatus.SERVICE_UNAVAILABLE,
                "RICCON-WF-503",
                "Workflow orchestration is disabled",
                disabledReason == null || disabledReason.isBlank()
                        ? "workflow orchestration is disabled by deployment configuration"
                        : disabledReason);
    }
}
