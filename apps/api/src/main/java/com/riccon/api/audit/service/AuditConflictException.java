package com.riccon.api.audit.service;

import com.riccon.api.error.ErrorCodes;
import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * 审计导出状态不允许该操作、并发审批冲突或下载时实际行数超限（ADR-0020 第 6.2 节，{@code RICCON-AUDIT-409}）。
 */
public class AuditConflictException extends RicconException {

    /**
     * 创建冲突异常。
     *
     * @param detail 服务端可读原因，不含导出内容
     */
    public AuditConflictException(String detail) {
        super(HttpStatus.CONFLICT, ErrorCodes.AUDIT_CONFLICT, "Audit export conflict", detail);
    }
}
