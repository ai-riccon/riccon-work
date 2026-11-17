package com.riccon.platform.domain.audit;

/**
 * 审计结果。
 *
 * <p>{@link #DENIED} 用于跨租户/越权拒绝，必须与统一 404/403 语义一起记录，不能泄露对象是否存在。
 */
public enum AuditOutcome {
    /** 操作成功。 */
    SUCCESS,
    /** 操作失败（技术或业务失败）。 */
    FAILURE,
    /** 权限或租户校验拒绝。 */
    DENIED
}
