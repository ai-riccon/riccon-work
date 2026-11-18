package com.riccon.api.audit.domain;

import com.riccon.platform.domain.audit.AuditActions;

/**
 * 审计动作名补充。
 *
 * <p>M10 开放审计查询后，所有已写入的动作都必须进入 {@code platform-domain} 的共享词汇
 * （ADR-0020 第 6.1 节）。本类保留原有常量名以兼容既有调用，但值统一引用 {@link AuditActions}，
 * 禁止再出现第二份字符串字面量。
 */
public final class AdditionalAuditActions {

    /** 平台管理员更新租户状态或保留策略。 */
    public static final String TENANT_UPDATED = AuditActions.TENANT_UPDATED;

    /** 已认证请求携带与主体不一致的租户标识，被拒绝。 */
    public static final String TENANT_ACCESS_DENIED = AuditActions.TENANT_ACCESS_DENIED;

    /** 删除会话（软删除）。动作名与 {@code session.created} 对称。 */
    public static final String SESSION_DELETED = AuditActions.SESSION_DELETED;

    /** 员工发送消息并创建对话运行。 */
    public static final String MESSAGE_SENT = AuditActions.MESSAGE_SENT;

    /** 员工停止生成，或客户端断开导致上游取消；已生成内容保留。 */
    public static final String MESSAGE_STOPPED = AuditActions.MESSAGE_STOPPED;

    /** 员工创建市场调研报告任务，并在同一事务写入运行与 outbox。 */
    public static final String RESEARCH_TASK_CREATED = AuditActions.RESEARCH_TASK_CREATED;

    /** 员工获取报告产物下载地址；只记录产物与版本，不记录 URL 与对象 key。 */
    public static final String ARTIFACT_DOWNLOADED = AuditActions.ARTIFACT_DOWNLOADED;

    /** Worker 运行发起人已禁用，运行被拒绝；用于 API 侧读取策略识别同一动作。 */
    public static final String USER_ACCESS_DENIED = AuditActions.USER_ACCESS_DENIED;

    /** 工具类禁止实例化。 */
    private AdditionalAuditActions() {}
}
