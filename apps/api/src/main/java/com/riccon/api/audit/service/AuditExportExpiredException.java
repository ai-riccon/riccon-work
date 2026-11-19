package com.riccon.api.audit.service;

import com.riccon.api.error.ErrorCodes;
import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * 审计导出已过期（ADR-0020 第 2/6.2 节，{@code RICCON-AUDIT-410}）。
 *
 * <p>过期按时间派生，不改写数据库状态；同一导出在有效期内重复下载会重新生成 CSV。
 */
public class AuditExportExpiredException extends RicconException {

    /**
     * 创建过期异常。
     *
     * @param detail 服务端可读原因
     */
    public AuditExportExpiredException(String detail) {
        super(HttpStatus.GONE, ErrorCodes.AUDIT_EXPORT_EXPIRED, "Audit export expired", detail);
    }
}
