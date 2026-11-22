package com.riccon.worker.audit.domain;

import com.riccon.platform.domain.audit.AuditActions;

/**
 * Worker 额外审计动作。
 *
 * <p>与 API 的 {@code AdditionalAuditActions} 使用相同短名；ADR-0007 最小清单未包含跨租户拒绝动作，
 * 但 ADR-0016 要求跨租户校验失败写 {@code outcome=denied} 审计，M03 审计查询会消费同名动作。
 *
 * <p>M04：降级与凭据自动失效由 Worker 写入，但动作名属于共享词汇（{@link AuditActions}），
 * 这里只提供 Worker 侧别名，禁止再出现第二份字符串字面量。{@code model.credential.*} 以
 * {@code tenant_id=platform}、{@code actor_type=SYSTEM} 写入（ADR-0021 §7.4）。
 */
public final class WorkerAuditActions {

    /** 跨租户访问被拒绝。 */
    public static final String TENANT_ACCESS_DENIED = "tenant.access.denied";

    /** 运行发起人已禁用。 */
    public static final String USER_ACCESS_DENIED = "user.access.denied";

    /** 首字前切换下一个 key/模型；成功降级对员工不可见（M04）。 */
    public static final String MODEL_FAILOVER_TRIGGERED = AuditActions.MODEL_FAILOVER_TRIGGERED;

    /** Worker 收到 401/403 后自动使凭据失效（M04）。 */
    public static final String MODEL_CREDENTIAL_INVALIDATED = AuditActions.MODEL_CREDENTIAL_INVALIDATED;

    /** 工具类禁止实例化。 */
    private WorkerAuditActions() {}
}
