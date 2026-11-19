package com.riccon.api.audit.service;

import com.riccon.api.error.ErrorCodes;
import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * 审计查询/导出的时间范围、过滤器或分页非法（ADR-0020 第 6.2 节，{@code RICCON-AUDIT-400}）。
 */
public class AuditInvalidRequestException extends RicconException {

    /**
     * 创建参数错误异常。
     *
     * @param detail 服务端可读原因，不含筛选值原文
     */
    public AuditInvalidRequestException(String detail) {
        super(HttpStatus.BAD_REQUEST, ErrorCodes.AUDIT_INVALID_REQUEST, "Invalid audit request", detail);
    }
}
