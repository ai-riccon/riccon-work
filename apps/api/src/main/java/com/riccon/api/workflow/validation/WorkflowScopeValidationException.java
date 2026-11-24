package com.riccon.api.workflow.validation;

import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * 发布/校验范围非法，HTTP 400 {@code RICCON-WF-400}。
 *
 * <p>用于 PUBLISH 与 VALIDATE 共用的范围校验：非租户管理员选择 {@code TENANT}、租户管理员选择
 * {@code DEPARTMENT}、{@code DEPARTMENT} 缺少部门、部门不是当前账号负责的启用部门，或非
 * {@code DEPARTMENT} 范围携带 {@code departmentId}。说明只含范围枚举与字段名，不回显图、提示词或
 * 工具参数（ADR-0024 §7.3/§8.2）。
 */
public class WorkflowScopeValidationException extends RicconException {

    /**
     * 创建范围非法异常。
     *
     * @param detail 脱敏说明
     */
    public WorkflowScopeValidationException(String detail) {
        super(HttpStatus.BAD_REQUEST, "RICCON-WF-400", "Invalid workflow run scope", detail);
    }
}
