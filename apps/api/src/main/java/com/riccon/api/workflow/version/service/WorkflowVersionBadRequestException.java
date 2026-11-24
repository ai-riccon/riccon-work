package com.riccon.api.workflow.version.service;

import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * 版本/生命周期请求非法，HTTP 400 {@code RICCON-WF-400}。
 *
 * <p>用于移交目标不是启用账号或不具备 {@code workflow.edit} 等业务条件；说明只含字段名与规则，
 * 不泄露账号邮箱、密码或任何业务内容（ADR-0024 §7.3/§8.2）。
 */
public class WorkflowVersionBadRequestException extends RicconException {

    /**
     * 创建请求非法异常。
     *
     * @param detail 脱敏说明
     */
    public WorkflowVersionBadRequestException(String detail) {
        super(HttpStatus.BAD_REQUEST, "RICCON-WF-400", "Invalid workflow lifecycle request", detail);
    }
}
