package com.riccon.api.workflow.definition.service;

import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * 流程草稿版本冲突，HTTP 409 {@code RICCON-WF-409}。
 *
 * <p>只在 {@code draft_version} 条件更新未命中时抛出；客户端应重新读取、另存副本或放弃，不静默覆盖
 * 对方修改（ADR-0024 §7.3，AC-01）。
 */
public class WorkflowDefinitionConflictException extends RicconException {

    /**
     * 创建草稿冲突异常。
     *
     * @param detail 脱敏说明
     */
    public WorkflowDefinitionConflictException(String detail) {
        super(HttpStatus.CONFLICT, "RICCON-WF-409", "Workflow draft conflict", detail);
    }
}
