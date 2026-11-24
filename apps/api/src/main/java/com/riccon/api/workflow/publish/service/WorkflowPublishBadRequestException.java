package com.riccon.api.workflow.publish.service;

import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * 发布请求非法，HTTP 400 {@code RICCON-WF-400}。
 *
 * <p>用于请求体/范围非法、部门范围不满足负责人/启用条件等发布前校验失败；说明只含字段名、枚举与
 * 范围，不回显提示词、工具参数或文档正文（ADR-0024 §7.3/§8.2）。
 */
public class WorkflowPublishBadRequestException extends RicconException {

    /**
     * 创建发布请求非法异常。
     *
     * @param detail 脱敏说明
     */
    public WorkflowPublishBadRequestException(String detail) {
        super(HttpStatus.BAD_REQUEST, "RICCON-WF-400", "Invalid workflow publish request", detail);
    }
}
