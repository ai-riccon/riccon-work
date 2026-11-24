package com.riccon.api.workflow.definition.service;

import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * 流程请求非法，HTTP 400 {@code RICCON-WF-400}。
 *
 * <p>用于图/参数/条件/模板/范围非法、发布校验失败与无可用审批人等场景；错误说明只包含字段名、
 * 长度或范围，不回显提示词、工具参数或文档正文（ADR-0024 §7.3/§8.2）。
 */
public class WorkflowDefinitionBadRequestException extends RicconException {

    /**
     * 创建请求非法异常。
     *
     * @param detail 脱敏说明
     */
    public WorkflowDefinitionBadRequestException(String detail) {
        super(HttpStatus.BAD_REQUEST, "RICCON-WF-400", "Invalid workflow request", detail);
    }
}
