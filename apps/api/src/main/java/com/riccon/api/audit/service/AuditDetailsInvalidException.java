package com.riccon.api.audit.service;

import com.riccon.api.error.ErrorCodes;
import com.riccon.api.error.RicconException;
import org.springframework.http.HttpStatus;

/**
 * 读取审计时发现白名单外或敏感的 details 键（ADR-0020 第 1/6.2 节，{@code RICCON-AUDIT-500}）。
 *
 * <p>fail-closed：查询/导出直接失败，不静默剔除字段继续返回；响应不包含 details 原文。
 */
public class AuditDetailsInvalidException extends RicconException {

    /** 创建读取策略失败异常；对外只给通用说明，避免回显敏感键。 */
    public AuditDetailsInvalidException() {
        super(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorCodes.AUDIT_DETAILS_INVALID,
                "Audit details rejected",
                "audit details failed read-time policy");
    }
}
