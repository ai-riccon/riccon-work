package com.riccon.api.workflow.version.service;

import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * 流程生命周期状态冲突，HTTP 409 {@code RICCON-WF-409}。
 *
 * <p>用于重复停用/启用、重复移交或条件更新未命中（并发修改）；客户端应重新读取流程后重试
 * （ADR-0024 §7.3）。
 */
public class WorkflowVersionConflictException extends RicconException {

    /**
     * 创建状态冲突异常。
     *
     * @param detail 脱敏说明
     */
    public WorkflowVersionConflictException(String detail) {
        super(HttpStatus.CONFLICT, "RICCON-WF-409", "Workflow status conflict", detail);
    }
}
