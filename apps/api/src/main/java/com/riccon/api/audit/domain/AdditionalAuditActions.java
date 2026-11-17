package com.riccon.api.audit.domain;

/**
 * 审计动作名补充。
 *
 * <p>ADR-0007 的最小事件清单没有覆盖“租户更新”“伪造租户访问被拒绝”“会话删除”“消息发送/停止”等 M02 动作。
 * 为避免在业务代码里写字符串字面量，这里集中定义；M03 开放审计查询前必须把这些动作同步回
 * {@code platform-domain} 与 ADR-0007 的白名单。
 */
public final class AdditionalAuditActions {

    /** 平台管理员更新租户状态或保留策略。 */
    public static final String TENANT_UPDATED = "tenant.updated";

    /** 已认证请求携带与主体不一致的租户标识，被拒绝。 */
    public static final String TENANT_ACCESS_DENIED = "tenant.access.denied";

    /** 删除会话（软删除）。动作名与 {@code session.created} 对称。 */
    public static final String SESSION_DELETED = "session.deleted";

    /** 员工发送消息并创建对话运行。 */
    public static final String MESSAGE_SENT = "message.sent";

    /** 员工停止生成，或客户端断开导致上游取消；已生成内容保留。 */
    public static final String MESSAGE_STOPPED = "message.stopped";

    /** 工具类禁止实例化。 */
    private AdditionalAuditActions() {}
}
