package com.riccon.api.workflow.run.service;

import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * 运行请求非法，HTTP 400 {@code RICCON-WF-400}。
 *
 * <p>用于输入 JSON 形状/大小/键非法、版本未发布、流程停用等运行前校验失败；说明只含字段名、限制值
 * 与枚举，不回显运行输入原文、提示词或工具参数（ADR-0024 §7.3/§8.2）。
 */
public class WorkflowRunBadRequestException extends RicconException {

    /**
     * 创建运行请求非法异常。
     *
     * @param detail 脱敏说明
     */
    public WorkflowRunBadRequestException(String detail) {
        super(HttpStatus.BAD_REQUEST, "RICCON-WF-400", "Invalid workflow run request", detail);
    }
}
