package com.riccon.api.audit.domain;

/**
 * 审计动作名补充。
 *
 * <p>ADR-0007 的最小事件清单没有覆盖“租户更新”和“伪造租户访问被拒绝”两个 M02 动作。为避免在业务代码里
 * 写字符串字面量，这里集中定义；M03 开放审计查询前必须把这两个动作同步回 {@code platform-domain}
 * 与 ADR-0007 的白名单。
 */
public final class AdditionalAuditActions {

    /** 平台管理员更新租户状态或保留策略。 */
    public static final String TENANT_UPDATED = "tenant.updated";

    /** 已认证请求携带与主体不一致的租户标识，被拒绝。 */
    public static final String TENANT_ACCESS_DENIED = "tenant.access.denied";

    /** 工具类禁止实例化。 */
    private AdditionalAuditActions() {}
}
