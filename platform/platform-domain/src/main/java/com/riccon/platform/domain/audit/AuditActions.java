package com.riccon.platform.domain.audit;

/**
 * 审计动作名。
 *
 * <p>与 ADR-0007 第 2 节一一对应；数据库存短名，查询与导出使用同一常量，禁止在业务代码里写字符串字面量。
 */
public interface AuditActions {

    /** 登录成功。 */
    String AUTH_LOGIN_SUCCEEDED = "auth.login.succeeded";
    /** 登录失败（账号不存在、密码错误、锁定）。 */
    String AUTH_LOGIN_FAILED = "auth.login.failed";
    /** 主动登出。 */
    String AUTH_LOGOUT = "auth.logout";

    /** 平台管理员创建租户。 */
    String TENANT_CREATED = "tenant.created";
    /** 创建账号。 */
    String USER_CREATED = "user.created";
    /** 启用/禁用账号。 */
    String USER_DISABLED = "user.disabled";
    /** 角色授予/撤销。 */
    String PERMISSION_CHANGED = "permission.changed";

    /** 文件上传并登记成功。 */
    String FILE_UPLOADED = "file.uploaded";
    /** 删除文件。 */
    String FILE_DELETED = "file.deleted";
    /** 新建会话。 */
    String SESSION_CREATED = "session.created";

    /** 开始调用模型（流式首字前）。 */
    String MODEL_INVOCATION_STARTED = "model.invocation.started";
    /** 模型调用正常结束。 */
    String MODEL_INVOCATION_COMPLETED = "model.invocation.completed";
    /** 模型调用失败。 */
    String MODEL_INVOCATION_FAILED = "model.invocation.failed";

    /** 一次 MCP 工具调用结束。 */
    String MCP_TOOL_INVOKED = "mcp.tool.invoked";
    /** 员工批准/拒绝审批。 */
    String APPROVAL_DECIDED = "approval.decided";
    /** 报告产物保存。 */
    String ARTIFACT_SAVED = "artifact.saved";

    /** 数据导出（M03）。 */
    String DATA_EXPORTED = "data.exported";
    /** 查询审计（M03）。 */
    String AUDIT_QUERIED = "audit.queried";
}
