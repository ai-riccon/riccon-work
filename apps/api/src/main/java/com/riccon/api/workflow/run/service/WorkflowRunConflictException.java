package com.riccon.api.workflow.run.service;

import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * 运行状态冲突，HTTP 409 {@code RICCON-WF-409}。
 *
 * <p>用于运行已终止、节点不是最新失败尝试、重试目标不是失败节点等状态机冲突；客户端应重新读取运行
 * 详情后决定下一步（ADR-0024 §7.3）。
 */
public class WorkflowRunConflictException extends RicconException {

    /**
     * 创建运行状态冲突异常。
     *
     * @param detail 脱敏说明
     */
    public WorkflowRunConflictException(String detail) {
        super(HttpStatus.CONFLICT, "RICCON-WF-409", "Workflow run conflict", detail);
    }
}
