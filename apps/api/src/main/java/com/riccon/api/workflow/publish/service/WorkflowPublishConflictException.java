package com.riccon.api.workflow.publish.service;

import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * 发布状态冲突，HTTP 409 {@code RICCON-WF-409}。
 *
 * <p>预留给同一流程发布时的并发状态冲突（例如唯一版本号竞争）。同一草稿的普通重复发布走幂等返回，
 * 不抛本异常；客户端收到 409 时应重新读取流程后重试（ADR-0024 §7.3）。
 */
public class WorkflowPublishConflictException extends RicconException {

    /**
     * 创建发布冲突异常。
     *
     * @param detail 脱敏说明
     */
    public WorkflowPublishConflictException(String detail) {
        super(HttpStatus.CONFLICT, "RICCON-WF-409", "Workflow publish conflict", detail);
    }
}
