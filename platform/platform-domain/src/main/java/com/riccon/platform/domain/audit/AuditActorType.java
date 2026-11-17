package com.riccon.platform.domain.audit;

/**
 * 审计操作者类型。
 *
 * <p>平台级事件使用 {@code platform} 租户与 {@link #SERVICE}/{@link #SYSTEM} 操作者；
 * 不使用用户名、邮箱等 PII 作为 actor ID。
 */
public enum AuditActorType {
    /** 已认证员工。 */
    USER,
    /** API/Worker 服务身份。 */
    SERVICE,
    /** 系统维护任务（迁移、清理）。 */
    SYSTEM
}
