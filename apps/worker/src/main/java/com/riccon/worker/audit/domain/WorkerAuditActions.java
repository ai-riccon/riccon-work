package com.riccon.worker.audit.domain;

/**
 * Worker 额外审计动作。
 *
 * <p>与 API 的 {@code AdditionalAuditActions} 使用相同短名；ADR-0007 最小清单未包含该动作，
 * 但 ADR-0016 要求跨租户校验失败写 {@code outcome=denied} 审计，M03 审计查询会消费同名动作。
 */
public final class WorkerAuditActions {

    /** 跨租户访问被拒绝。 */
    public static final String TENANT_ACCESS_DENIED = "tenant.access.denied";

    /** 运行发起人已禁用。 */
    public static final String USER_ACCESS_DENIED = "user.access.denied";

    /** 工具类禁止实例化。 */
    private WorkerAuditActions() {}
}
