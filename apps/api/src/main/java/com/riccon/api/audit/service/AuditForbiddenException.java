package com.riccon.api.audit.service;

import com.riccon.api.error.ErrorCodes;
import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * 有审计权限但业务拒绝审计导出（ADR-0020 第 6.2 节，{@code RICCON-AUDIT-403}）。
 *
 * <p>用于申请人自批、非申请人下载、平台租户导出在 M10 不支持等场景；缺少 {@code audit.*} 权限仍由
 * 既有权限拦截器返回 {@code RICCON-AUTH-403}。
 */
public class AuditForbiddenException extends RicconException {

    /**
     * 创建业务拒绝异常。
     *
     * @param detail 服务端可读原因，不含导出内容
     */
    public AuditForbiddenException(String detail) {
        super(HttpStatus.FORBIDDEN, ErrorCodes.AUDIT_FORBIDDEN, "Audit export forbidden", detail);
    }
}
